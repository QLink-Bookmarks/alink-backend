package com.qlink.ai.service

import com.qlink.ai.domain.AiJob
import com.qlink.ai.domain.AiJobStatus
import com.qlink.ai.domain.AiProvider
import com.qlink.ai.domain.AiProviderType
import com.qlink.ai.domain.AvailableModel
import com.qlink.ai.domain.DailyUsage
import com.qlink.ai.domain.UserProvider
import com.qlink.ai.dto.AiSummaryRequest
import com.qlink.ai.dto.PutAiUserProviderRequest
import com.qlink.ai.dto.UpdateAiActiveRequest
import com.qlink.ai.repository.AiJobRepository
import com.qlink.ai.repository.AiProviderRepository
import com.qlink.ai.repository.AvailableModelRepository
import com.qlink.ai.repository.DailyUsageRepository
import com.qlink.ai.repository.UserProviderRepository
import com.qlink.auth.domain.Role
import com.qlink.common.crypto.ApiKeyCipher
import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.link.domain.Link
import com.qlink.link.repository.LinkRepository
import com.qlink.support.BaseServiceTest
import com.qlink.support.FakeAiClient
import com.qlink.support.fixture.AiFixture
import com.qlink.support.fixture.LinkFixture
import com.qlink.support.fixture.RandomFixture
import com.qlink.support.fixture.UserFixture
import com.qlink.support.koinGet
import com.qlink.user.domain.User
import com.qlink.user.domain.UserTheme
import com.qlink.user.dto.UpdateMySettingsRequest
import com.qlink.user.repository.UserRepository
import com.qlink.user.service.UpdateMySettingsService
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrowWithMessage
import io.kotest.matchers.shouldBe
import java.time.LocalDate

class AiAvailabilityServicesTest :
    BaseServiceTest({
        val users = koinGet<UserRepository>()
        val providers = koinGet<AiProviderRepository>()
        val models = koinGet<AvailableModelRepository>()
        val keys = koinGet<UserProviderRepository>()
        val links = koinGet<LinkRepository>()
        val jobs = koinGet<AiJobRepository>()
        val usages = koinGet<DailyUsageRepository>()
        val fake = koinGet<FakeAiClient>()
        val getModels = koinGet<GetAiProviderModelsService>()
        val settings = koinGet<UpdateMySettingsService>()
        val summary = koinGet<UpdateLinkAiSummaryService>()
        val putKey = koinGet<PutAiUserProviderService>()
        val activateProvider = koinGet<UpdateAiProviderActiveService>()
        val activateModel = koinGet<UpdateAvailableModelActiveService>()
        Given("키·기본 모델·이력이 연결된 제공자와 모델") {
            lateinit var admin: User
            lateinit var user: User
            lateinit var provider: AiProvider
            lateinit var model: AvailableModel
            lateinit var key: UserProvider
            lateinit var link: Link
            lateinit var job: AiJob
            lateinit var usage: DailyUsage
            val date = LocalDate.now()
            beforeTest {
                if (it.type != io.kotest.core.test.TestType.Test) return@beforeTest
                fake.reset()
                admin = users.insert(UserFixture.createRandomValidSuperAdmin())
                user = users.insert(UserFixture.createRandomValidUser())
                provider = providers.insert(AiFixture.createRandomValidAiProvider(type = AiProviderType.OPENAI))
                model = models.insert(AiFixture.createRandomAvailableModelOf(providerId = provider.id!!, priority = 1))
                user =
                    users.update(
                        user.changeSettings(
                            theme = null,
                            accent = null,
                            allowsReminder = null,
                            defaultAiProviderId = provider.id,
                            defaultModelId = model.id,
                        ),
                    )
                key =
                    keys.insert(
                        UserProvider(
                            userId = user.id!!,
                            providerId = provider.id!!,
                            apiKey = koinGet<ApiKeyCipher>().encrypt(RandomFixture.randomFixedSentence(30)),
                        ),
                    )
                link = links.insert(LinkFixture.createRandomLinkOf(ownerId = user.id!!, workModelId = model.id))
                job = jobs.insert(AiFixture.createPendingAiJobOf(link = link, userProviderId = key.id!!, modelId = model.id!!))
                usage =
                    usages.insert(
                        DailyUsage(
                            userProviderId = key.id!!,
                            modelId = model.id!!,
                            usageDate = date,
                            requests = RandomFixture.randomPositive(),
                            tokens = RandomFixture.randomPositive(),
                        ),
                    )
            }
            When("공개 모델은 활성 모델만 노출") {
                beforeTest {
                    if (it.type != io.kotest.core.test.TestType.Test) return@beforeTest
                    models.insert(AiFixture.createRandomAvailableModelOf(providerId = provider.id!!, isActive = false))
                    providers.insert(AiFixture.createRandomValidAiProvider(type = AiProviderType.CLAUDE, isActive = false))
                    providers.insert(AiFixture.createRandomValidAiProvider(type = AiProviderType.GEMINI))
                }
                val action = suspend { getModels.getAiProviderModels(loginId = null, isMine = false) }
                Then("저장소 상태와 응답을 확인한다") {
                    val response = action()
                    response.single().models.map { it.id } shouldBe listOf(model.id)
                }
            }
            When("내 모델은 비활성 제공자·모델 제외하고 키 보존") {
                beforeTest {
                    if (it.type != io.kotest.core.test.TestType.Test) return@beforeTest
                    providers.update(provider.copy(isActive = false))
                }
                val action = suspend { getModels.getAiProviderModels(loginId = user.id!!, isMine = true) }
                Then("저장소 상태와 응답을 확인한다") {
                    action() shouldBe emptyList()
                    keys.findById(key.id!!)!!.id shouldBe key.id
                }
            }
            When("기본 키 DEFAULT는 활성 최소 우선순위 모델") {
                beforeTest {
                    if (it.type != io.kotest.core.test.TestType.Test) return@beforeTest
                    models.update(model.copy(isActive = false))
                    models.insert(AiFixture.createRandomAvailableModelOf(providerId = provider.id!!, priority = 2))
                    keys.insert(
                        UserProvider(
                            userId = admin.id!!,
                            providerId = provider.id!!,
                            userRole = Role.SUPER_ADMIN,
                            apiKey = koinGet<ApiKeyCipher>().encrypt(RandomFixture.randomFixedSentence(30)),
                        ),
                    )
                }
                val action = suspend { getModels.getAiProviderModels(loginId = user.id!!, isMine = true) }
                Then("저장소 상태와 응답을 확인한다") {
                    val selected = action().single().models.first { it.model == "DEFAULT" }
                    selected.id shouldBe models.findAllByProviderId(provider.id!!).first { it.isActive }.id
                }
            }
            When("비활성 제공자 키 등록은 검증 요청·저장 없이 거절") {
                beforeTest {
                    if (it.type != io.kotest.core.test.TestType.Test) return@beforeTest
                    providers.update(provider.copy(isActive = false))
                }
                val action =
                    suspend {
                        putKey.putAiUserProvider(
                            loginId = user.id!!,
                            request = PutAiUserProviderRequest(providerId = provider.id!!, apiKey = RandomFixture.randomFixedSentence(30)),
                        )
                    }
                Then("저장소 상태와 응답을 확인한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_PROVIDER_INACTIVE.message) { action() }
                    fake.requestedApiKeys shouldBe emptyList()
                    keys.findById(key.id!!)!!.apiKey shouldBe key.apiKey
                }
            }
            When("비활성 제공자 기본값 지정 거절") {
                beforeTest {
                    if (it.type != io.kotest.core.test.TestType.Test) return@beforeTest
                    providers.update(provider.copy(isActive = false))
                }
                val action =
                    suspend {
                        settings.updateMySettings(
                            loginId = user.id!!,
                            request = UpdateMySettingsRequest(defaultProviderId = provider.id),
                        )
                    }
                Then("저장소 상태와 응답을 확인한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_PROVIDER_INACTIVE.message) { action() }
                    users.findById(user.id!!)!!.defaultModelId shouldBe model.id
                }
            }
            When("비활성 제공자 요약은 작업·링크 생성 없이 거절") {
                beforeTest {
                    if (it.type != io.kotest.core.test.TestType.Test) return@beforeTest
                    providers.update(provider.copy(isActive = false))
                }
                val action =
                    suspend {
                        summary.updateLinkAiSummary(
                            loginId = user.id!!,
                            request = AiSummaryRequest(userProviderId = key.id!!, modelId = model.id!!, url = RandomFixture.randomUrl()),
                        )
                    }
                Then("저장소 상태와 응답을 확인한다") {
                    val count = jobs.findAllByStatus(AiJobStatus.P).size
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_PROVIDER_INACTIVE.message) { action() }
                    jobs.findAllByStatus(AiJobStatus.P).size shouldBe count
                }
            }
            When("비활성 모델 기본값 지정 거절") {
                beforeTest {
                    if (it.type != io.kotest.core.test.TestType.Test) return@beforeTest
                    models.update(model.copy(isActive = false))
                }
                val action =
                    suspend { settings.updateMySettings(loginId = user.id!!, request = UpdateMySettingsRequest(defaultModelId = model.id)) }
                Then("저장소 상태와 응답을 확인한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_MODEL_INACTIVE.message) { action() }
                    users.findById(user.id!!)!!.defaultModelId shouldBe model.id
                }
            }
            When("비활성 모델 요약은 작업·링크 생성 없이 거절") {
                beforeTest {
                    if (it.type != io.kotest.core.test.TestType.Test) return@beforeTest
                    models.update(model.copy(isActive = false))
                }
                val action =
                    suspend {
                        summary.updateLinkAiSummary(
                            loginId = user.id!!,
                            request = AiSummaryRequest(userProviderId = key.id!!, modelId = model.id!!, url = RandomFixture.randomUrl()),
                        )
                    }
                Then("저장소 상태와 응답을 확인한다") {
                    val count = jobs.findAllByStatus(AiJobStatus.P).size
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_MODEL_INACTIVE.message) { action() }
                    jobs.findAllByStatus(AiJobStatus.P).size shouldBe count
                }
            }
            When("비활성 기본 모델이 있어도 테마 변경 가능") {
                beforeTest {
                    if (it.type != io.kotest.core.test.TestType.Test) return@beforeTest
                    models.update(model.copy(isActive = false))
                    providers.update(provider.copy(isActive = false))
                }
                val action =
                    suspend {
                        settings.updateMySettings(
                            loginId = user.id!!,
                            request = UpdateMySettingsRequest(theme = UserTheme.DARK.responseName),
                        )
                    }
                Then("저장소 상태와 응답을 확인한다") {
                    shouldNotThrowAny { action() }
                    val saved = users.findById(user.id!!)!!
                    saved.theme shouldBe UserTheme.DARK
                    saved.defaultModelId shouldBe model.id
                    saved.defaultAiProviderId shouldBe provider.id
                }
            }
            When("모델 설정 변경은 연결·이력의 모델 참조 보존") {
                val action =
                    suspend {
                        koinGet<UpdateAvailableModelService>().updateAvailableModel(
                            loginId = admin.id!!,
                            id = model.id!!,
                            request =
                                com.qlink.ai.dto
                                    .UpdateAvailableModelRequest(priority = RandomFixture.randomPositive()),
                        )
                    }
                Then("원래 모델 식별자와 모든 참조가 유지된다") {
                    action()
                    val saved = models.findById(model.id!!)!!
                    saved.id shouldBe model.id
                    saved.model shouldBe model.model
                    saved.providerId shouldBe provider.id
                    users.findById(user.id!!)!!.defaultModelId shouldBe model.id
                    links.findById(link.id!!)!!.workModelId shouldBe model.id
                    jobs.findById(job.id!!)!!.requestModelId shouldBe model.id
                    usages
                        .findByUserIdAndProviderIdAndUsageDate(
                            userProviderId = key.id!!,
                            modelId = model.id!!,
                            usageDate = date,
                        )!!
                        .id shouldBe usage.id
                }
            }
            When("제공자 비활성화는 모든 연결·이력 보존") {
                val action =
                    suspend {
                        activateProvider.updateAiProviderActive(
                            loginId = admin.id!!,
                            id = provider.id!!,
                            request = UpdateAiActiveRequest(isActive = false),
                        )
                    }
                Then("저장소 상태와 응답을 확인한다") {
                    action()
                    users.findById(user.id!!)!!.defaultModelId shouldBe model.id
                    users.findById(user.id!!)!!.defaultAiProviderId shouldBe provider.id
                    keys.findById(key.id!!)!!.apiKey shouldBe key.apiKey
                    links.findById(link.id!!)!!.workModelId shouldBe model.id
                    jobs.findById(job.id!!)!!.requestModelId shouldBe model.id
                    usages
                        .findByUserIdAndProviderIdAndUsageDate(
                            userProviderId = key.id!!,
                            modelId = model.id!!,
                            usageDate = date,
                        )!!
                        .id shouldBe
                        usage.id
                }
            }
            When("모델 비활성화는 모든 연결·이력 보존") {
                val action =
                    suspend {
                        activateModel.updateAvailableModelActive(
                            loginId = admin.id!!,
                            id = model.id!!,
                            request = UpdateAiActiveRequest(isActive = false),
                        )
                    }
                Then("저장소 상태와 응답을 확인한다") {
                    action()
                    users.findById(user.id!!)!!.defaultModelId shouldBe model.id
                    users.findById(user.id!!)!!.defaultAiProviderId shouldBe provider.id
                    keys.findById(key.id!!)!!.apiKey shouldBe key.apiKey
                    links.findById(link.id!!)!!.workModelId shouldBe model.id
                    jobs.findById(job.id!!)!!.requestModelId shouldBe model.id
                    usages
                        .findByUserIdAndProviderIdAndUsageDate(
                            userProviderId = key.id!!,
                            modelId = model.id!!,
                            usageDate = date,
                        )!!
                        .id shouldBe
                        usage.id
                }
            }
        }
    })
