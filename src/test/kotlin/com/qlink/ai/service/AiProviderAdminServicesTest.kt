package com.qlink.ai.service

import com.qlink.ai.domain.AiJob
import com.qlink.ai.domain.AiProvider
import com.qlink.ai.domain.AiProviderType
import com.qlink.ai.domain.AvailableModel
import com.qlink.ai.domain.DailyUsage
import com.qlink.ai.domain.UserProvider
import com.qlink.ai.dto.CreateAiProviderRequest
import com.qlink.ai.dto.UpdateAiActiveRequest
import com.qlink.ai.dto.UpdateAiProviderRequest
import com.qlink.ai.repository.AiJobRepository
import com.qlink.ai.repository.AiProviderRepository
import com.qlink.ai.repository.AvailableModelRepository
import com.qlink.ai.repository.DailyUsageRepository
import com.qlink.ai.repository.UserProviderRepository
import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.link.domain.Link
import com.qlink.link.repository.LinkRepository
import com.qlink.support.BaseServiceTest
import com.qlink.support.fixture.AiFixture
import com.qlink.support.fixture.LinkFixture
import com.qlink.support.fixture.RandomFixture
import com.qlink.support.fixture.UserFixture
import com.qlink.support.koinGet
import com.qlink.user.domain.User
import com.qlink.user.repository.UserRepository
import io.kotest.assertions.throwables.shouldThrowWithMessage
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class AiProviderAdminServicesTest :
    BaseServiceTest({
        val users = koinGet<UserRepository>()
        val providers = koinGet<AiProviderRepository>()
        val models = koinGet<AvailableModelRepository>()
        val keys = koinGet<UserProviderRepository>()
        val links = koinGet<LinkRepository>()
        val jobs = koinGet<AiJobRepository>()
        val usages = koinGet<DailyUsageRepository>()
        val createProvider = koinGet<CreateAiProviderService>()
        val updateProvider = koinGet<UpdateAiProviderService>()
        val activateProvider = koinGet<UpdateAiProviderActiveService>()
        val getProvider = koinGet<GetAiProviderService>()
        val getProviders = koinGet<GetAiProvidersService>()
        Given("관리자, 일반 사용자와 제공자·모델") {
            lateinit var admin: User
            lateinit var normal: User
            lateinit var provider: AiProvider
            lateinit var model: AvailableModel
            beforeTest {
                if (it.type != io.kotest.core.test.TestType.Test) return@beforeTest
                admin = users.insert(UserFixture.createRandomValidSuperAdmin())
                normal = users.insert(UserFixture.createRandomValidUser())
                provider = providers.insert(AiFixture.createRandomValidAiProvider(type = AiProviderType.OPENAI))
                model = models.insert(AiFixture.createRandomAvailableModelOf(providerId = provider.id!!))
            }
            When("새 제공자는 비활성으로 생성") {
                val action =
                    suspend {
                        createProvider.createAiProvider(
                            loginId = admin.id!!,
                            request =
                                CreateAiProviderRequest(
                                    type = AiProviderType.GEMINI,
                                    baseUrl = "https://generativelanguage.googleapis.com/v1/",
                                ),
                        )
                    }
                Then("저장소 상태와 응답을 확인한다") {
                    val result = action()
                    val saved = providers.findById(result.id).shouldNotBeNull()
                    saved.isActive shouldBe false
                    saved.baseUrl shouldBe "https://generativelanguage.googleapis.com/v1"
                }
            }
            When("제공자 중복 업체 거절") {
                val action =
                    suspend {
                        createProvider.createAiProvider(
                            loginId = admin.id!!,
                            request = CreateAiProviderRequest(type = provider.type, baseUrl = provider.baseUrl),
                        )
                    }
                Then("저장소 상태와 응답을 확인한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_PROVIDER_DUPLICATE_TYPE.message) { action() }
                }
            }
            When("제공자 주소 정규화와 식별자 보존") {
                val action =
                    suspend {
                        updateProvider.updateAiProvider(
                            loginId = admin.id!!,
                            id = provider.id!!,
                            request = UpdateAiProviderRequest(baseUrl = "https://api.openai.com/v2/"),
                        )
                    }
                Then("저장소 상태와 응답을 확인한다") {
                    val result = action()
                    val saved = providers.findById(provider.id!!).shouldNotBeNull()
                    saved.id shouldBe provider.id
                    saved.type shouldBe provider.type
                    saved.baseUrl shouldBe "https://api.openai.com/v2"
                    saved.updatedAt shouldNotBe provider.updatedAt
                    result.updatedAt shouldBe saved.updatedAt
                    models.findById(model.id!!)!!.providerId shouldBe provider.id
                }
            }
            When("활성화·비활성화의 멱등과 id 보존") {
                lateinit var key: UserProvider
                lateinit var link: Link
                lateinit var job: AiJob
                lateinit var usage: DailyUsage
                beforeTest {
                    if (it.type != io.kotest.core.test.TestType.Test) return@beforeTest
                    key = keys.insert(AiFixture.createUserProviderOf(userId = normal.id!!, providerId = provider.id!!))
                    link = links.insert(LinkFixture.createRandomLinkOf(ownerId = normal.id!!, workModelId = model.id))
                    job = jobs.insert(AiFixture.createPendingAiJobOf(link = link, userProviderId = key.id!!, modelId = model.id!!))
                    usage = usages.insert(AiFixture.createDailyUsageOf(userProviderId = key.id!!, modelId = model.id!!))
                    normal =
                        users.update(
                            normal.changeSettings(
                                theme = null,
                                accent = null,
                                allowsReminder = null,
                                defaultAiProviderId = provider.id,
                                defaultModelId = model.id,
                            ),
                        )
                }
                val action =
                    suspend {
                        activateProvider.updateAiProviderActive(
                            loginId = admin.id!!,
                            id = provider.id!!,
                            request = UpdateAiActiveRequest(isActive = false),
                        )
                    }
                Then("저장소 상태와 응답을 확인한다") {
                    val first = action()
                    providers.findById(provider.id!!)!!.isActive shouldBe false
                    keys.findById(key.id!!)!!.apiKey shouldBe key.apiKey
                    users.findById(normal.id!!)!!.defaultAiProviderId shouldBe provider.id
                    users.findById(normal.id!!)!!.defaultModelId shouldBe model.id
                    links.findById(link.id!!)!!.workModelId shouldBe model.id
                    jobs.findById(job.id!!)!!.requestModelId shouldBe model.id
                    usages
                        .findByUserIdAndProviderIdAndUsageDate(
                            userProviderId = key.id!!,
                            modelId = model.id!!,
                            usageDate = usage.usageDate,
                        )!!
                        .id shouldBe usage.id
                    action() shouldBe first
                    val restored =
                        activateProvider.updateAiProviderActive(
                            loginId = admin.id!!,
                            id = provider.id!!,
                            request = UpdateAiActiveRequest(isActive = true),
                        )
                    providers.findById(provider.id!!)!!.isActive shouldBe true
                    restored.id shouldBe provider.id
                    providers.findById(provider.id!!)!!.type shouldBe provider.type
                    models.findById(model.id!!)!!.providerId shouldBe provider.id
                }
            }
            When("실행 어댑터 없는 업체 활성화 차단") {
                beforeTest {
                    if (it.type != io.kotest.core.test.TestType.Test) return@beforeTest
                    provider = providers.insert(AiFixture.createRandomValidAiProvider(type = AiProviderType.CLAUDE, isActive = false))
                }
                val action =
                    suspend {
                        activateProvider.updateAiProviderActive(
                            loginId = admin.id!!,
                            id = provider.id!!,
                            request = UpdateAiActiveRequest(isActive = true),
                        )
                    }
                Then("저장소 상태와 응답을 확인한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_PROVIDER_CLIENT_MISSING.message) { action() }
                    providers.findById(provider.id!!)!!.isActive shouldBe false
                }
            }
            When("상세에는 비활성 모델도 우선순위·id 순서") {
                beforeTest {
                    if (it.type != io.kotest.core.test.TestType.Test) return@beforeTest
                    models.insert(
                        AiFixture.createRandomAvailableModelOf(providerId = provider.id!!, isActive = false, priority = model.priority),
                    )
                }
                val action = suspend { getProvider.getAiProvider(loginId = admin.id!!, id = provider.id!!) }
                Then("저장소 상태와 응답을 확인한다") {
                    val response = action()
                    response.models.map { it.id } shouldBe models.findAllByProviderId(provider.id!!).map { it.id }
                    response.models.any { !it.isActive } shouldBe true
                    response.baseUrl shouldBe provider.baseUrl
                }
            }
            When("기본 목록은 활성만·관리자 목록은 모두") {
                beforeTest {
                    if (it.type != io.kotest.core.test.TestType.Test) return@beforeTest
                    providers.insert(AiFixture.createRandomValidAiProvider(type = AiProviderType.CLAUDE, isActive = false))
                    models.insert(AiFixture.createRandomAvailableModelOf(providerId = provider.id!!, isActive = false))
                }
                val action = suspend { getProviders.getAiProviders() }
                Then("저장소 상태와 응답을 확인한다") {
                    val visible = action()
                    visible.size shouldBe 1
                    visible.single().modelCount shouldBe 2
                    visible.single().activeModelCount shouldBe 1
                    visible.single().isExecutable shouldBe true
                    val all = getProviders.getAiProviders(loginId = admin.id, includesInactive = true)
                    all.map { it.type } shouldBe listOf(AiProviderType.CLAUDE, AiProviderType.OPENAI)
                    val withoutModels = all.first { it.type == AiProviderType.CLAUDE }
                    withoutModels.isExecutable shouldBe false
                    withoutModels.modelCount shouldBe 0
                    withoutModels.activeModelCount shouldBe 0
                    all.first { it.id == provider.id }.updatedAt shouldBe providers.findById(provider.id!!)!!.updatedAt
                }
            }
            listOf(ErrorCode.AI_ADMIN_REQUIRED, ErrorCode.AI_ADMIN_USER_NOT_FOUND).forEach { errorCode ->
                val loginId = { if (errorCode == ErrorCode.AI_ADMIN_REQUIRED) normal.id!! else RandomFixture.randomId() }
                val actions: Map<String, suspend () -> Any> =
                    mapOf(
                        "상세" to { getProvider.getAiProvider(loginId = loginId(), id = provider.id!!) },
                        "목록" to { getProviders.getAiProviders(loginId = loginId(), includesInactive = true) },
                        "추가" to {
                            createProvider.createAiProvider(
                                loginId = loginId(),
                                request =
                                    CreateAiProviderRequest(
                                        type = AiProviderType.GEMINI,
                                        baseUrl = "https://generativelanguage.googleapis.com",
                                    ),
                            )
                        },
                        "주소 변경" to {
                            updateProvider.updateAiProvider(
                                loginId = loginId(),
                                id = provider.id!!,
                                request = UpdateAiProviderRequest(baseUrl = provider.baseUrl),
                            )
                        },
                        "상태 변경" to {
                            activateProvider.updateAiProviderActive(
                                loginId = loginId(),
                                id = provider.id!!,
                                request = UpdateAiActiveRequest(isActive = false),
                            )
                        },
                    )
                actions.forEach { (name, action) ->
                    When("$name 요청에 ${errorCode.name} 조건이면") {
                        Then("거절하고 제공자와 모델을 보존한다") {
                            shouldThrowWithMessage<BusinessException>(errorCode.message) { action() }
                            providers.findById(provider.id!!) shouldBe provider
                            providers.findAll().size shouldBe 1
                            models.findById(model.id!!) shouldBe model
                        }
                    }
                }
            }
            When("제공자 상세 없음") {
                val action = suspend { getProvider.getAiProvider(loginId = admin.id!!, id = RandomFixture.randomId()) }
                Then("저장소 상태와 응답을 확인한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_ADMIN_PROVIDER_NOT_FOUND.message) { action() }
                }
            }
            When("제공자 변경 없음") {
                val action =
                    suspend {
                        updateProvider.updateAiProvider(
                            loginId = admin.id!!,
                            id = RandomFixture.randomId(),
                            request = UpdateAiProviderRequest(baseUrl = "https://api.openai.com"),
                        )
                    }
                Then("저장소 상태와 응답을 확인한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_ADMIN_PROVIDER_NOT_FOUND.message) { action() }
                }
            }
            When("제공자 상태 없음") {
                val action =
                    suspend {
                        activateProvider.updateAiProviderActive(
                            loginId = admin.id!!,
                            id = RandomFixture.randomId(),
                            request = UpdateAiActiveRequest(isActive = false),
                        )
                    }
                Then("저장소 상태와 응답을 확인한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_ADMIN_PROVIDER_NOT_FOUND.message) { action() }
                }
            }
        }
        Given("제공자가 없을 때") {
            When("공개 목록을 조회하면") {
                val action = suspend { getProviders.getAiProviders() }
                Then("저장소와 응답이 모두 비어 있다") {
                    action() shouldBe emptyList()
                    providers.findAll() shouldBe emptyList()
                }
            }
        }
    })
