package com.qlink.folder.service

import com.qlink.ai.repository.AiJobRepository
import com.qlink.common.text.sha256Hex
import com.qlink.folder.repository.FolderRepository
import com.qlink.foldermember.repository.FolderMemberRepository
import com.qlink.guidecache.domain.GuideCacheStatus
import com.qlink.guidecache.domain.GuideDefinition
import com.qlink.guidecache.repository.GuideCacheRepository
import com.qlink.guidecache.repository.table.GuideCaches
import com.qlink.link.domain.LinkSort
import com.qlink.link.domain.LinkStatus
import com.qlink.link.domain.SourceType
import com.qlink.link.repository.LinkRepository
import com.qlink.linkcontent.domain.ContentStatus
import com.qlink.linkcontent.repository.LinkContentRepository
import com.qlink.notification.repository.NotificationRepository
import com.qlink.support.BaseServiceTest
import com.qlink.support.fixture.FakeLinkContentSyncClient
import com.qlink.support.fixture.FakeLinkPreviewSyncClient
import com.qlink.support.fixture.GuideCacheFixture
import com.qlink.support.fixture.RandomFixture
import com.qlink.support.fixture.UserFixture
import com.qlink.support.koinGet
import com.qlink.todo.repository.TodoRepository
import com.qlink.user.repository.UserRepository
import io.kotest.core.test.TestType
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.deleteAll

class ProvisionGuideFolderServiceTest :
    BaseServiceTest({
        val service = koinGet<ProvisionGuideFolderService>()
        val users = koinGet<UserRepository>()
        val folders = koinGet<FolderRepository>()
        val links = koinGet<LinkRepository>()
        val contents = koinGet<LinkContentRepository>()
        val caches = koinGet<GuideCacheRepository>()
        val previewClient = koinGet<FakeLinkPreviewSyncClient>()
        val contentClient = koinGet<FakeLinkContentSyncClient>()
        var ownerId = 0L
        val now = RandomFixture.randomPastInstant()

        beforeTest {
            if (it.type != TestType.Test) return@beforeTest
            GuideCaches.deleteAll()
            ownerId = users.insert(UserFixture.createRandomValidUser()).id!!
            previewClient.reset()
            contentClient.reset()
        }

        Given("READY 캐시") {
            beforeTest {
                if (it.type != TestType.Test) return@beforeTest
                GuideDefinition.entries.forEach {
                    caches.insertIfAbsent(
                        GuideCacheFixture.cache(
                            url = it.url,
                            previewStatus = GuideCacheStatus.READY,
                            contentStatus = GuideCacheStatus.READY,
                        ),
                    )
                }
            }
            When("기본 폴더를 생성하면") {
                val provision = suspend { service.provision(ownerId = ownerId, now = now) }
                Then("고정 카탈로그와 READY 자산을 사용자별로 저장한다") {
                    val result = provision()
                    val folder = folders.findAllByOwnerId(ownerId).single()
                    folder.name shouldBe "ALink Tips"
                    folder.emoji shouldBe "💡"
                    folder.sharedAt shouldBe null
                    val rows =
                        links.search(
                            ownerId = ownerId,
                            keyword = null,
                            folderId = folder.id,
                            isFavorite = null,
                            sort = LinkSort.of(orderKey = null, order = null),
                            cursor = null,
                            size = 10,
                        )
                    rows.map { it.title } shouldBe listOf("ALink Tips", "ALink 빠른 공유하기 설정", "공유하기로 간편 저장", "웹 브라우저 북마크 가져오기")
                    rows.map { it.url } shouldBe
                        listOf(
                            "https://warp-raft-63c.notion.site/ALink-Tips-412d602d12548000ab51c9da56783374",
                            "https://warp-raft-63c.notion.site/412d602d1254809a9b84c0b6c82c6dbf",
                            "https://warp-raft-63c.notion.site/412d602d1254803dbca0deec90d418ee",
                            "https://warp-raft-63c.notion.site/412d602d1254803d8c41e9366c33f8fc",
                        )
                    rows.map { it.tags } shouldBe
                        listOf(
                            listOf("ALink Tips"),
                            listOf("ALink Tips", "고정 설정"),
                            listOf("ALink Tips", "간편 저장"),
                            listOf("ALink Tips", "북마크 가져오기"),
                        )
                    rows.forEach { row ->
                        val link = links.findById(row.id)!!
                        val cache = caches.findByUrl(link.url)!!
                        val content = contents.findByLinkId(row.id)!!
                        link.ownerId shouldBe ownerId
                        link.sourceType shouldBe SourceType.INPUT
                        link.status shouldBe LinkStatus.C
                        link.memo shouldBe null
                        link.summary shouldBe null
                        link.favoriteAt shouldBe null
                        link.thumbnailUrl shouldBe cache.preview!!.thumbnailUrl
                        link.isVideo shouldBe cache.preview.isVideo
                        content.status shouldBe ContentStatus.COLLECTED
                        content.page!!.text shouldBe cache.page!!.text
                        content.page.title shouldBe cache.page.title
                        content.page.siteName shouldBe cache.page.siteName
                        content.page.author shouldBe cache.page.author
                        content.page.canonicalUrl shouldBe cache.page.canonicalUrl
                        content.page.finalUrl shouldBe cache.page.finalUrl
                        content.collectedAt shouldBe cache.collectedAt
                        content.contentHash shouldBe cache.page.text.sha256Hex()
                    }
                    koinGet<FolderMemberRepository>().findAllByFolderIdOrderByJoinedAtAsc(folder.id!!) shouldBe emptyList()
                    koinGet<TodoRepository>().findAllByOwnerId(ownerId) shouldBe emptyList()
                    koinGet<NotificationRepository>().countUnread(ownerId) shouldBe 0
                    rows.forEach { koinGet<AiJobRepository>().findAllByLinkId(it.id) shouldBe emptyList() }
                    result.previewFillLinkIds shouldBe emptyList()
                    result.hasPendingContent shouldBe false
                    previewClient.calls.size shouldBe 0
                    contentClient.calls.size shouldBe 0
                }
            }
        }
        Given("캐시가 없는 가입") {
            When("기본 폴더를 생성하면") {
                val provision = suspend { service.provision(ownerId = ownerId, now = now) }
                Then("빈 프리뷰와 PENDING 본문을 저장하며 추출을 호출하지 않는다") {
                    val result = provision()
                    val folder = folders.findAllByOwnerId(ownerId).single()
                    val saved = links.findAllByFolderIds(listOf(folder.id!!))
                    saved.size shouldBe 4
                    saved.forEach {
                        it.hasPreview shouldBe false
                        contents.findByLinkId(it.id!!)!!.status shouldBe ContentStatus.PENDING
                    }
                    result.previewFillLinkIds.toSet() shouldBe saved.map { it.id!! }.toSet()
                    result.hasPendingContent shouldBe true
                    previewClient.calls.size shouldBe 0
                    contentClient.calls.size shouldBe 0
                }
            }
        }
        Given("프리뷰만 READY인 캐시") {
            beforeTest {
                if (it.type != TestType.Test) return@beforeTest
                GuideDefinition.entries.forEach {
                    caches.insertIfAbsent(
                        GuideCacheFixture.cache(
                            url = it.url,
                            previewStatus = GuideCacheStatus.READY,
                            contentStatus = GuideCacheStatus.FAILED,
                        ),
                    )
                }
            }
            When("기본 폴더를 생성하면") {
                val provision = suspend { service.provision(ownerId = ownerId, now = now) }
                Then("프리뷰만 복사하고 본문은 일반 fallback이다") {
                    val result = provision()
                    val folder = folders.findAllByOwnerId(ownerId).single()
                    links.findAllByFolderIds(listOf(folder.id!!)).forEach {
                        it.hasPreview shouldBe true
                        contents.findByLinkId(it.id!!)!!.status shouldBe ContentStatus.PENDING
                    }
                    result.previewFillLinkIds shouldBe emptyList()
                    result.hasPendingContent shouldBe true
                }
            }
        }
    })
