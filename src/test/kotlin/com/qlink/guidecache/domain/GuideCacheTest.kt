package com.qlink.guidecache.domain

import com.qlink.support.fixture.GuideCacheFixture
import com.qlink.support.fixture.RandomFixture
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.minutes

class GuideCacheTest :
    BehaviorSpec({
        Given("시작할 수 있는 캐시") {
            for (status in listOf(GuideCacheStatus.NOT_STARTED, GuideCacheStatus.FAILED)) {
                When("프리뷰가 $status 이면 시작한다") {
                    val cache =
                        GuideCacheFixture.cache(
                            previewStatus = status,
                        )
                    val now = RandomFixture.randomPastInstant()
                    val started = cache.startPreview(now)
                    Then("프리뷰만 STARTED가 된다") {
                        started.shouldNotBeNull().previewStatus shouldBe GuideCacheStatus.STARTED
                        started.previewStartedAt shouldBe now
                        started.contentStatus shouldBe cache.contentStatus
                    }
                }
                When("본문이 $status 이면 시작한다") {
                    val cache =
                        GuideCacheFixture.cache(
                            contentStatus = status,
                        )
                    val now = RandomFixture.randomPastInstant()
                    val started = cache.startContent(now)
                    Then("본문만 STARTED가 된다") {
                        started.shouldNotBeNull().contentStatus shouldBe GuideCacheStatus.STARTED
                        started.contentStartedAt shouldBe now
                        started.previewStatus shouldBe cache.previewStatus
                    }
                }
            }
        }
        Given("이미 READY인 캐시") {
            val cache =
                GuideCacheFixture.cache(
                    previewStatus = GuideCacheStatus.READY,
                    contentStatus = GuideCacheStatus.READY,
                )
            When("프리뷰를 시작하면") {
                val result = cache.startPreview(RandomFixture.randomPastInstant())
                Then("획득할 수 없다") { result.shouldBeNull() }
            }
            When("본문을 시작하면") {
                val result = cache.startContent(RandomFixture.randomPastInstant())
                Then("획득할 수 없다") { result.shouldBeNull() }
            }
        }
        Given("STARTED 캐시") {
            val now = RandomFixture.randomPastInstant()
            for (age in listOf(1.minutes, 10.minutes, 10.minutes + 1.microseconds)) {
                val cache =
                    GuideCacheFixture.cache(
                        previewStatus = GuideCacheStatus.STARTED,
                        contentStatus = GuideCacheStatus.STARTED,
                        startedAt =
                            now - age,
                    )
                When("$age 지난 프리뷰를 회수하면") {
                    val result = cache.startPreview(now)
                    Then("10분 초과 여부로 판정한다") {
                        if (age > 10.minutes) result.shouldNotBeNull().previewStartedAt shouldBe now else result.shouldBeNull()
                    }
                }
                When("$age 지난 본문을 회수하면") {
                    val result = cache.startContent(now)
                    Then("10분 초과 여부로 판정한다") {
                        if (age > 10.minutes) result.shouldNotBeNull().contentStartedAt shouldBe now else result.shouldBeNull()
                    }
                }
            }
        }
        Given("획득한 캐시") {
            val cache =
                GuideCacheFixture.cache(
                    previewStatus = GuideCacheStatus.STARTED,
                    contentStatus = GuideCacheStatus.STARTED,
                )
            When("이미지 없는 프리뷰 성공을 저장하면") {
                val preview = CachedGuidePreview(thumbnailUrl = null, faviconUrl = null, isVideo = false)
                val result = cache.completePreview(preview)
                Then("빈 성공도 READY이다") {
                    result.previewStatus shouldBe GuideCacheStatus.READY
                    result.readyPreview() shouldBe preview
                    result.contentStatus shouldBe cache.contentStatus
                }
            }
            When("본문 성공을 저장하면") {
                val page = GuideCacheFixture.page()
                val now = RandomFixture.randomPastInstant()
                val result = cache.completeContent(page = page, now = now)
                Then("본문과 수집시각을 보존한다") {
                    result.contentStatus shouldBe GuideCacheStatus.READY
                    result.readyContent() shouldBe page
                    result.collectedAt shouldBe now
                }
            }
            When("프리뷰 실패를 저장하면") {
                val result = cache.failPreview()
                Then("프리뷰만 FAILED이다") {
                    result.previewStatus shouldBe GuideCacheStatus.FAILED
                    result.readyPreview().shouldBeNull()
                    result.contentStatus shouldBe cache.contentStatus
                }
            }
            When("본문 실패를 저장하면") {
                val result = cache.failContent()
                Then("본문만 FAILED이다") {
                    result.contentStatus shouldBe GuideCacheStatus.FAILED
                    result.readyContent().shouldBeNull()
                    result.previewStatus shouldBe cache.previewStatus
                }
            }
        }
    })
