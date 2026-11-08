package com.qlink.guidecache.service

import com.qlink.common.transaction.TransactionRunner
import com.qlink.guidecache.domain.GuideCacheStatus
import com.qlink.guidecache.domain.GuideDefinition
import com.qlink.guidecache.repository.GuideCacheRepository
import com.qlink.guidecache.repository.table.GuideCaches
import com.qlink.link.client.LinkPreviewExtraction
import com.qlink.link.client.LinkPreviewResult
import com.qlink.support.BaseServiceTest
import com.qlink.support.fixture.FakeLinkContentSyncClient
import com.qlink.support.fixture.FakeLinkPreviewSyncClient
import com.qlink.support.fixture.GuideCacheFixture
import com.qlink.support.fixture.LinkContentFixture
import com.qlink.support.fixture.LinkPreviewFixture
import com.qlink.support.koinGet
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.test.TestType
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.job
import kotlinx.coroutines.supervisorScope
import org.jetbrains.exposed.v1.jdbc.deleteAll
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

class WarmGuideCacheServiceTest :
    BaseServiceTest({
        val service = koinGet<WarmGuideCacheService>()
        val repository = koinGet<GuideCacheRepository>()
        val preview = koinGet<FakeLinkPreviewSyncClient>()
        val content = koinGet<FakeLinkContentSyncClient>()

        beforeTest {
            if (it.type != TestType.Test) return@beforeTest
            preview.reset()
            content.reset()
            preview.respond(LinkPreviewResult(thumbnailKey = LinkPreviewFixture.key(kind = "preview")))
            repeat(4) { content.enqueue(LinkContentFixture.collectedResult()) }
        }

        Given("캐시가 없는 시작 실행") {
            beforeTest {
                if (it.type != TestType.Test) return@beforeTest
                GuideCaches.deleteAll()
            }
            When("네 URL을 준비하면") {
                val warm = suspend { service.warm() }
                Then("종류별 URL당 한 번만 호출하고 READY를 저장한다") {
                    shouldNotThrowAny { warm() }
                    val rows = repository.findAllByUrls(GuideDefinition.entries.map { it.url })
                    rows.size shouldBe 4
                    rows.all { it.previewStatus == GuideCacheStatus.READY && it.contentStatus == GuideCacheStatus.READY } shouldBe true
                    preview.calls.map { it.first }.toSet() shouldBe GuideDefinition.entries.map { it.url }.toSet()
                    preview.calls.size shouldBe 4
                    content.calls.size shouldBe 4
                }
            }
            When("프리뷰가 실패하면") {
                val warm =
                    suspend {
                        preview.extraction = LinkPreviewExtraction.Failed
                        service.warm()
                    }
                Then("이번 실행에서 재시도하지 않고 본문은 독립 저장한다") {
                    shouldNotThrowAny { warm() }
                    repository.findAllByUrls(GuideDefinition.entries.map { it.url }).forEach {
                        it.previewStatus shouldBe GuideCacheStatus.FAILED
                        it.contentStatus shouldBe GuideCacheStatus.READY
                    }
                    preview.calls.size shouldBe 4
                }
            }
            When("본문이 공백이면") {
                val warm =
                    suspend {
                        content.reset()
                        repeat(4) { content.enqueue(LinkContentFixture.collectedResult(text = " ")) }
                        service.warm()
                    }
                Then("본문은 FAILED이다") {
                    shouldNotThrowAny { warm() }
                    repository
                        .findAllByUrls(GuideDefinition.entries.map { it.url })
                        .all { it.contentStatus == GuideCacheStatus.FAILED } shouldBe true
                }
            }
        }
        Given("독립된 두 시작 실행") {
            When("다른 시작 실행이 진행 중에 들어오면") {
                val warm =
                    suspend {
                        supervisorScope {
                            val scope = CoroutineScope(Dispatchers.Default.limitedParallelism(1) + coroutineContext.job)
                            val entered = CompletableDeferred<Unit>()
                            val release = CompletableDeferred<Unit>()
                            preview.onExtract = { url ->
                                if (url == GuideDefinition.TIPS.url) {
                                    entered.complete(Unit)
                                    release.await()
                                }
                            }
                            try {
                                scope.async { koinGet<TransactionRunner>().required { GuideCaches.deleteAll() } }.await()
                                val first = scope.async { service.warm() }
                                entered.await()
                                scope.async { service.warm() }.await()
                                release.complete(Unit)
                                first.await()
                                val rows = repository.findAllByUrls(GuideDefinition.entries.map { it.url })
                                rows.forEach { it.previewStatus shouldBe GuideCacheStatus.READY }
                                preview.calls.size shouldBe 4
                                content.calls.size shouldBe 4
                            } finally {
                                release.complete(Unit)
                                scope.async { koinGet<TransactionRunner>().required { GuideCaches.deleteAll() } }.await()
                            }
                        }
                    }
                Then("독립 연결에서도 진행 중인 종류를 중복 호출하지 않는다") {
                    shouldNotThrowAny { warm() }
                }
            }
        }

        Given("이미 READY인 캐시") {
            beforeTest {
                if (it.type != TestType.Test) return@beforeTest
                GuideCaches.deleteAll()
                GuideDefinition.entries.forEach {
                    repository.insertIfAbsent(
                        GuideCacheFixture.cache(
                            url = it.url,
                            previewStatus = GuideCacheStatus.READY,
                            contentStatus = GuideCacheStatus.READY,
                        ),
                    )
                }
            }
            When("다시 시작하면") {
                val warm = suspend { service.warm() }
                Then("스냅샷을 유지하고 호출하지 않는다") {
                    val before = repository.findAllByUrls(GuideDefinition.entries.map { it.url })
                    shouldNotThrowAny { warm() }
                    repository.findAllByUrls(GuideDefinition.entries.map { it.url }) shouldBe before
                    preview.calls.size shouldBe 0
                    content.calls.size shouldBe 0
                }
            }
        }
        Given("FAILED인 영속 캐시") {
            beforeTest {
                if (it.type != TestType.Test) return@beforeTest
                GuideCaches.deleteAll()
                GuideDefinition.entries.forEach {
                    repository.insertIfAbsent(
                        GuideCacheFixture.cache(
                            url = it.url,
                            previewStatus = GuideCacheStatus.FAILED,
                            contentStatus = GuideCacheStatus.FAILED,
                        ),
                    )
                }
            }
            When("다음 시작에서 실행하면") {
                val warm = suspend { service.warm() }
                Then("종류별 한 번씩 재시도하여 READY가 된다") {
                    shouldNotThrowAny { warm() }
                    repository.findAllByUrls(GuideDefinition.entries.map { it.url }).forEach {
                        it.previewStatus shouldBe GuideCacheStatus.READY
                        it.contentStatus shouldBe GuideCacheStatus.READY
                    }
                    preview.calls.size shouldBe 4
                    content.calls.size shouldBe 4
                }
            }
        }
        for (age in listOf(1.minutes, 11.minutes)) {
            Given("$age 전에 STARTED가 된 캐시") {
                beforeTest {
                    if (it.type != TestType.Test) return@beforeTest
                    GuideCaches.deleteAll()
                    GuideDefinition.entries.forEach {
                        repository.insertIfAbsent(
                            GuideCacheFixture.cache(
                                url = it.url,
                                previewStatus = GuideCacheStatus.STARTED,
                                contentStatus = GuideCacheStatus.STARTED,
                                startedAt = Clock.System.now() - age,
                            ),
                        )
                    }
                }
                When("시작 작업을 실행하면") {
                    val warm = suspend { service.warm() }
                    Then("오래된 시작만 회수한다") {
                        shouldNotThrowAny { warm() }
                        val expected = if (age > 10.minutes) GuideCacheStatus.READY else GuideCacheStatus.STARTED
                        repository.findAllByUrls(GuideDefinition.entries.map { it.url }).forEach {
                            it.previewStatus shouldBe expected
                            it.contentStatus shouldBe expected
                        }
                        preview.calls.size shouldBe if (age > 10.minutes) 4 else 0
                        content.calls.size shouldBe if (age > 10.minutes) 4 else 0
                    }
                }
            }
        }
        Given("결과 도착 전에 새 시도로 회수된 프리뷰") {
            beforeTest {
                if (it.type != TestType.Test) return@beforeTest
                GuideCaches.deleteAll()
                preview.onExtract = { url ->
                    val old = repository.findByUrl(url)!!
                    val next = old.startPreview(old.previewStartedAt!! + 11.minutes)!!
                    repository.updatePreview(expected = old, next = next)
                }
            }
            When("이전 시도의 결과가 저장되면") {
                val warm = suspend { service.warm() }
                Then("새 STARTED를 덮어쓰지 않는다") {
                    shouldNotThrowAny { warm() }
                    repository.findAllByUrls(GuideDefinition.entries.map { it.url }).forEach {
                        it.previewStatus shouldBe GuideCacheStatus.STARTED
                        it.preview shouldBe null
                        it.contentStatus shouldBe GuideCacheStatus.READY
                    }
                }
            }
        }
    })
