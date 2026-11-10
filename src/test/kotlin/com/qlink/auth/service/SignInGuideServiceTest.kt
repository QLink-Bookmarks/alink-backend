package com.qlink.auth.service

import com.qlink.auth.domain.AuthProvider
import com.qlink.auth.domain.AuthProviderType
import com.qlink.auth.dto.AuthPlatform
import com.qlink.auth.dto.SignInRequest
import com.qlink.auth.repository.AuthProviderRepository
import com.qlink.auth.repository.RefreshTokenRepository
import com.qlink.folder.repository.FolderRepository
import com.qlink.guide.domain.GuideDefinition
import com.qlink.link.domain.LinkSort
import com.qlink.link.domain.LinkStatus
import com.qlink.link.domain.SourceType
import com.qlink.link.repository.LinkRepository
import com.qlink.linkcontent.domain.ContentStatus
import com.qlink.linkcontent.repository.LinkContentRepository
import com.qlink.support.BaseServiceTest
import com.qlink.support.FakeAuthResourceClient
import com.qlink.support.fixture.FakeLinkContentSyncClient
import com.qlink.support.fixture.FakeLinkPreviewSyncClient
import com.qlink.support.fixture.RandomFixture
import com.qlink.support.fixture.UserFixture
import com.qlink.support.koinGet
import com.qlink.user.repository.UserRepository
import io.kotest.core.test.TestType
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class SignInGuideServiceTest :
    BaseServiceTest({
        val service = koinGet<SignInService>()
        val users = koinGet<UserRepository>()
        val providers = koinGet<AuthProviderRepository>()
        val tokens = koinGet<RefreshTokenRepository>()
        val folders = koinGet<FolderRepository>()
        val links = koinGet<LinkRepository>()
        val contents = koinGet<LinkContentRepository>()
        val auth = koinGet<FakeAuthResourceClient>()
        val preview = koinGet<FakeLinkPreviewSyncClient>()
        val content = koinGet<FakeLinkContentSyncClient>()
        lateinit var providerId: String
        lateinit var request: SignInRequest

        beforeTest {
            if (it.type != TestType.Test) return@beforeTest
            auth.reset()
            preview.reset()
            content.reset()
            providerId = "guide-${RandomFixture.randomId()}"
            auth.providerId = providerId
            request = SignInRequest(provider = "kakao", token = RandomFixture.randomSentenceWithMax(50), platform = AuthPlatform.NATIVE)
        }

        Given("새로 가입하는 소셜 사용자") {
            When("처음 로그인하면") {
                val signIn = suspend { service.signIn(request) }
                Then("개인 가이드 폴더와 정해진 순서의 네 링크 및 본문 행을 저장한다") {
                    val refreshToken = signIn().response.refreshToken.shouldNotBeNull()
                    val provider =
                        providers
                            .findByProvider(
                                providerType = AuthProviderType.KAKAO,
                                providerId = providerId,
                            ).shouldNotBeNull()
                    users.findById(provider.userId).shouldNotBeNull()
                    tokens.findByToken(refreshToken).shouldNotBeNull().userId shouldBe provider.userId
                    val folder = folders.findAllByOwnerId(provider.userId).single()
                    folder.name shouldBe "ALink Tips"
                    folder.emoji shouldBe "💡"
                    folder.sharedAt shouldBe null
                    val rows =
                        links.search(
                            ownerId = provider.userId,
                            keyword = null,
                            folderId = folder.id.shouldNotBeNull(),
                            isFavorite = null,
                            sort = LinkSort.of(orderKey = null, order = null),
                            cursor = null,
                            size = GuideDefinition.entries.size,
                        )
                    rows.map { it.title } shouldBe GuideDefinition.entries.map { it.title }
                    rows.map { it.url } shouldBe GuideDefinition.entries.map { it.url }
                    rows.map { it.tags } shouldBe GuideDefinition.entries.map { it.tags }
                    rows.zip(GuideDefinition.entries).forEach { (row, definition) ->
                        val link = links.findById(row.id).shouldNotBeNull()
                        link.ownerId shouldBe provider.userId
                        link.sourceType shouldBe SourceType.INPUT
                        link.status shouldBe LinkStatus.C
                        link.thumbnailUrl shouldBe definition.preview.thumbnailUrl
                        link.faviconUrl shouldBe definition.preview.faviconUrl
                        link.isVideo shouldBe definition.preview.isVideo
                        val savedContent = contents.findByLinkId(row.id).shouldNotBeNull()
                        savedContent.sourceUrl shouldBe definition.url
                        savedContent.status shouldBe if (definition.page == null) ContentStatus.PENDING else ContentStatus.COLLECTED
                        definition.page?.let {
                            val savedPage = savedContent.page.shouldNotBeNull()
                            savedPage.text shouldBe it.text
                            savedPage.title shouldBe definition.title
                            it.title shouldBe definition.title
                            savedPage.siteName shouldBe it.siteName
                            savedPage.author shouldBe it.author
                            savedPage.publishedAt shouldBe it.publishedAt
                            savedPage.canonicalUrl shouldBe it.canonicalUrl
                            savedPage.finalUrl shouldBe it.finalUrl
                            savedPage.language shouldBe it.language
                            savedPage.isTruncated shouldBe it.isTruncated
                            savedContent.excerpt.shouldNotBeNull()
                            savedContent.collectedAt shouldBe link.createdAt
                        }
                    }
                    preview.calls.size shouldBe 0
                    content.calls.size shouldBe 0
                }
            }
            When("같은 사용자가 다시 로그인하면") {
                val signIn = suspend { service.signIn(request) }
                Then("기존 폴더와 링크를 다시 생성하지 않는다") {
                    signIn()
                    val provider =
                        providers
                            .findByProvider(
                                providerType = AuthProviderType.KAKAO,
                                providerId = providerId,
                            ).shouldNotBeNull()
                    val before = folders.findAllByOwnerId(provider.userId).single()
                    val folderId = before.id.shouldNotBeNull()
                    val ids = links.findAllByFolderIds(listOf(folderId)).map { it.id }
                    val refreshToken = signIn().response.refreshToken.shouldNotBeNull()
                    folders.findAllByOwnerId(provider.userId).map { it.id } shouldBe listOf(folderId)
                    links.findAllByFolderIds(listOf(folderId)).map { it.id } shouldBe ids
                    tokens.findByToken(refreshToken).shouldNotBeNull()
                }
            }
        }
        Given("가이드 폴더가 없는 기존 사용자") {
            var existingUserId = 0L
            beforeTest {
                if (it.type != TestType.Test) return@beforeTest
                existingUserId = users.insert(UserFixture.createRandomValidUser()).id.shouldNotBeNull()
                providers.insert(
                    AuthProvider(userId = existingUserId, providerType = AuthProviderType.KAKAO, providerId = providerId),
                )
            }
            When("로그인하면") {
                val signIn = suspend { service.signIn(request) }
                Then("가이드를 소급 생성하지 않는다") {
                    val refreshToken = signIn().response.refreshToken.shouldNotBeNull()
                    folders.findAllByOwnerId(existingUserId) shouldBe emptyList()
                    links.findAllUrlsByOwnerId(existingUserId) shouldBe emptyList()
                    tokens.findByToken(refreshToken).shouldNotBeNull().userId shouldBe existingUserId
                }
            }
        }
    })
