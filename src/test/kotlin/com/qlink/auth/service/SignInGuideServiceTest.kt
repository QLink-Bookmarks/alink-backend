package com.qlink.auth.service

import com.qlink.auth.domain.AuthProviderType
import com.qlink.auth.dto.AuthPlatform
import com.qlink.auth.dto.SignInRequest
import com.qlink.auth.repository.AuthProviderRepository
import com.qlink.auth.repository.RefreshTokenRepository
import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.di.serviceModule
import com.qlink.folder.repository.FolderRepository
import com.qlink.guidecache.domain.GuideCacheStatus
import com.qlink.guidecache.domain.GuideDefinition
import com.qlink.guidecache.repository.GuideCacheRepository
import com.qlink.guidecache.repository.table.GuideCaches
import com.qlink.link.repository.LinkRepository
import com.qlink.linkcontent.domain.ContentStatus
import com.qlink.linkcontent.repository.LinkContentRepository
import com.qlink.support.BaseServiceTest
import com.qlink.support.FakeAuthResourceClient
import com.qlink.support.fixture.FailingGuideLinkRepository
import com.qlink.support.fixture.FakeLinkContentSyncClient
import com.qlink.support.fixture.FakeLinkPreviewSyncClient
import com.qlink.support.fixture.GuideCacheFixture
import com.qlink.support.fixture.RandomFixture
import com.qlink.support.koinGet
import com.qlink.user.repository.UserRepository
import io.kotest.assertions.throwables.shouldThrowWithMessage
import io.kotest.core.test.TestType
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.job
import kotlinx.coroutines.supervisorScope
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module

class SignInGuideServiceTest :
    BaseServiceTest({
        val users = koinGet<UserRepository>()
        val providers = koinGet<AuthProviderRepository>()
        val tokens = koinGet<RefreshTokenRepository>()
        val folders = koinGet<FolderRepository>()
        val links = koinGet<LinkRepository>()
        val contents = koinGet<LinkContentRepository>()
        val caches = koinGet<GuideCacheRepository>()
        val auth = koinGet<FakeAuthResourceClient>()
        val preview = koinGet<FakeLinkPreviewSyncClient>()
        val content = koinGet<FakeLinkContentSyncClient>()
        lateinit var providerId: String
        lateinit var request: SignInRequest

        beforeTest {
            if (it.type != TestType.Test) return@beforeTest
            GuideCaches.deleteAll()
            auth.reset()
            preview.reset()
            content.reset()
            providerId = "guide-${RandomFixture.randomId()}"
            auth.providerId = providerId
            request =
                SignInRequest(
                    provider = "kakao",
                    token = RandomFixture.randomSentenceWithMax(50),
                    platform = AuthPlatform.NATIVE,
                )
        }
        afterTest { (testCase, _) ->
            if (testCase.type != TestType.Test) return@afterTest
            loadKoinModules(listOf(module { single<LinkRepository> { links } }, serviceModule()))
        }

        Given("READY 캐시로 가입하는 소셜 사용자") {
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
            When("처음 로그인하면") {
                val signIn = suspend { koinGet<SignInService>().signIn(request) }
                Then("사용자와 기본 데이터 및 토큰이 함께 저장된다") {
                    val response = signIn()
                    val provider =
                        providers
                            .findByProvider(
                                providerType = AuthProviderType.KAKAO,
                                providerId = providerId,
                            ).shouldNotBeNull()
                    users.findById(provider.userId).shouldNotBeNull()
                    tokens.findByToken(response.refreshToken).shouldNotBeNull().userId shouldBe provider.userId
                    val folder = folders.findAllByOwnerId(provider.userId).single()
                    val saved = links.findAllByFolderIds(listOf(folder.id!!))
                    saved.size shouldBe 4
                    saved.forEach {
                        it.thumbnailUrl shouldBe caches.findByUrl(it.url)!!.preview!!.thumbnailUrl
                        contents.findByLinkId(it.id!!)!!.status shouldBe ContentStatus.COLLECTED
                    }
                    preview.calls.size shouldBe 0
                    content.calls.size shouldBe 0
                }
            }
            When("같은 사용자가 두 번째 로그인하면") {
                val signIn = suspend { koinGet<SignInService>().signIn(request) }
                Then("폴더와 링크를 다시 만들지 않는다") {
                    val first = signIn()
                    val provider =
                        providers.findByProvider(
                            providerType = AuthProviderType.KAKAO,
                            providerId = providerId,
                        )!!
                    val before = folders.findAllByOwnerId(provider.userId).single()
                    val ids = links.findAllByFolderIds(listOf(before.id!!)).map { it.id }
                    val second = signIn()
                    folders.findAllByOwnerId(provider.userId).map { it.id } shouldBe listOf(before.id)
                    links.findAllByFolderIds(listOf(before.id)).map { it.id } shouldBe ids
                    tokens.findByToken(first.refreshToken).shouldNotBeNull()
                    tokens.findByToken(second.refreshToken).shouldNotBeNull()
                }
            }
        }
        Given("캐시가 없는 가입") {
            When("처음 로그인하면") {
                val signIn = suspend { koinGet<SignInService>().signIn(request) }
                Then("가입이 성공하고 일반 수집 가능한 링크가 저장된다") {
                    val response = signIn()
                    val provider =
                        providers.findByProvider(
                            providerType = AuthProviderType.KAKAO,
                            providerId = providerId,
                        )!!
                    tokens.findByToken(response.refreshToken).shouldNotBeNull()
                    val folder = folders.findAllByOwnerId(provider.userId).single()
                    links.findAllByFolderIds(listOf(folder.id!!)).forEach {
                        it.hasPreview shouldBe false
                        contents.findByLinkId(it.id!!)!!.status shouldBe ContentStatus.PENDING
                    }
                    preview.calls.size shouldBe 0
                    content.calls.size shouldBe 0
                }
            }
        }
        Given("가이드 저장 도중 실패하는 가입") {
            lateinit var failingLinks: FailingGuideLinkRepository
            beforeTest {
                if (it.type != TestType.Test) return@beforeTest
                failingLinks = FailingGuideLinkRepository(links)
                loadKoinModules(listOf(module { single<LinkRepository> { failingLinks } }, serviceModule()))
            }
            When("첫 링크 저장 후 다음 저장이 실패하면") {
                val signIn =
                    suspend {
                        supervisorScope {
                            CoroutineScope(Dispatchers.IO + coroutineContext.job)
                                .async {
                                    koinGet<SignInService>().signIn(request)
                                }.await()
                        }
                    }
                Then("신규 사용자와 부분 가이드가 모두 롤백된다") {
                    val userIds = users.findAllIds()
                    shouldThrowWithMessage<BusinessException>(ErrorCode.COMMON_INTERNAL_SERVER_ERROR.message) { signIn() }
                    val first = failingLinks.inserted.single()
                    users.findAllIds() shouldBe userIds
                    users.findById(first.ownerId) shouldBe null
                    providers.findByProvider(
                        providerType = AuthProviderType.KAKAO,
                        providerId = providerId,
                    ) shouldBe null
                    folders.findAllByOwnerId(first.ownerId) shouldBe emptyList()
                    links.findById(first.id!!) shouldBe null
                    contents.findByLinkId(first.id) shouldBe null
                }
            }
        }
    })
