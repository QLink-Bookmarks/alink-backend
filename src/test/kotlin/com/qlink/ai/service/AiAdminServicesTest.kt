package com.qlink.ai.service

import com.qlink.ai.domain.AiProvider
import com.qlink.ai.domain.AiProviderType
import com.qlink.ai.domain.AvailableModel
import com.qlink.ai.dto.CreateAiProviderRequest
import com.qlink.ai.dto.CreateAvailableModelRequest
import com.qlink.ai.dto.UpdateAiActiveRequest
import com.qlink.ai.dto.UpdateAiProviderRequest
import com.qlink.ai.dto.UpdateAvailableModelRequest
import com.qlink.ai.repository.AiProviderRepository
import com.qlink.ai.repository.AvailableModelRepository
import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.support.BaseServiceTest
import com.qlink.support.fixture.AiFixture
import com.qlink.support.fixture.RandomFixture
import com.qlink.support.fixture.UserFixture
import com.qlink.support.koinGet
import com.qlink.user.domain.User
import com.qlink.user.repository.UserRepository
import io.kotest.assertions.throwables.shouldThrowWithMessage
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class AiAdminServicesTest :
    BaseServiceTest({
        val users = koinGet<UserRepository>()
        val providers = koinGet<AiProviderRepository>()
        val models = koinGet<AvailableModelRepository>()
        val createProvider = koinGet<CreateAiProviderService>()
        val updateProvider = koinGet<UpdateAiProviderService>()
        val activateProvider = koinGet<UpdateAiProviderActiveService>()
        val createModel = koinGet<CreateAvailableModelService>()
        val updateModel = koinGet<UpdateAvailableModelService>()
        val activateModel = koinGet<UpdateAvailableModelActiveService>()
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
                    result.updatedAt shouldBe saved.updatedAt
                    models.findById(model.id!!)!!.providerId shouldBe provider.id
                }
            }
            When("활성화·비활성화의 멱등과 id 보존") {
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
                    action() shouldBe first
                    val restored =
                        activateProvider.updateAiProviderActive(
                            loginId = admin.id!!,
                            id = provider.id!!,
                            request = UpdateAiActiveRequest(isActive = true),
                        )
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
            When("비활성 제공자에도 활성 모델 추가") {
                beforeTest {
                    if (it.type != io.kotest.core.test.TestType.Test) return@beforeTest
                    provider = providers.update(provider.copy(isActive = false))
                }
                val action =
                    suspend {
                        createModel.createAvailableModel(
                            loginId = admin.id!!,
                            request =
                                CreateAvailableModelRequest(
                                    providerId = provider.id!!,
                                    model = "new-${RandomFixture.randomId()}",
                                    priority = 1,
                                ),
                        )
                    }
                Then("저장소 상태와 응답을 확인한다") {
                    val result = action()
                    val saved = models.findById(result.id).shouldNotBeNull()
                    saved.isActive shouldBe true
                    saved.providerId shouldBe provider.id
                }
            }
            When("같은 제공자 모델 식별자 중복 거절") {
                val action =
                    suspend {
                        createModel.createAvailableModel(
                            loginId = admin.id!!,
                            request = CreateAvailableModelRequest(providerId = provider.id!!, model = model.model, priority = 1),
                        )
                    }
                Then("저장소 상태와 응답을 확인한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_MODEL_DUPLICATE.message) { action() }
                }
            }
            When("다른 제공자에는 같은 모델 식별자 허용") {
                beforeTest {
                    if (it.type != io.kotest.core.test.TestType.Test) return@beforeTest
                    provider = providers.insert(AiFixture.createRandomValidAiProvider(type = AiProviderType.GEMINI))
                }
                val action =
                    suspend {
                        createModel.createAvailableModel(
                            loginId = admin.id!!,
                            request = CreateAvailableModelRequest(providerId = provider.id!!, model = model.model, priority = 1),
                        )
                    }
                Then("저장소 상태와 응답을 확인한다") {
                    val result = action()
                    models.findById(result.id)!!.providerId shouldBe provider.id
                }
            }
            When("모델 우선순위 동률과 제한 해제·식별자 유지") {
                beforeTest {
                    if (it.type != io.kotest.core.test.TestType.Test) return@beforeTest
                    models.insert(AiFixture.createRandomAvailableModelOf(providerId = provider.id!!, priority = 1))
                }
                val action =
                    suspend {
                        updateModel.updateAvailableModel(
                            loginId = admin.id!!,
                            id = model.id!!,
                            request = UpdateAvailableModelRequest(priority = 1),
                        )
                    }
                Then("저장소 상태와 응답을 확인한다") {
                    val result = action()
                    val saved = models.findById(model.id!!).shouldNotBeNull()
                    saved.id shouldBe model.id
                    saved.model shouldBe model.model
                    saved.providerId shouldBe model.providerId
                    saved.rpdLimit shouldBe null
                    saved.tpdLimit shouldBe null
                    result.updatedAt shouldBe saved.updatedAt
                    models.findAllByProviderId(provider.id!!).all { it.priority == 1 } shouldBe true
                }
            }
            When("모델 상태 멱등과 재활성화") {
                val action =
                    suspend {
                        activateModel.updateAvailableModelActive(
                            loginId = admin.id!!,
                            id = model.id!!,
                            request = UpdateAiActiveRequest(isActive = false),
                        )
                    }
                Then("저장소 상태와 응답을 확인한다") {
                    val first = action()
                    action() shouldBe first
                    val restored =
                        activateModel.updateAvailableModelActive(
                            loginId = admin.id!!,
                            id = model.id!!,
                            request = UpdateAiActiveRequest(isActive = true),
                        )
                    restored.id shouldBe model.id
                    val saved = models.findById(model.id!!).shouldNotBeNull()
                    saved.model shouldBe model.model
                    saved.providerId shouldBe model.providerId
                    saved.isActive shouldBe true
                }
            }
            When("상세에는 비활성 모델도 우선순위·id 순서") {
                beforeTest {
                    if (it.type != io.kotest.core.test.TestType.Test) return@beforeTest
                    models.insert(AiFixture.createRandomAvailableModelOf(providerId = provider.id!!, isActive = false, priority = 1))
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
                }
                val action = suspend { getProviders.getAiProviders() }
                Then("저장소 상태와 응답을 확인한다") {
                    val visible = action()
                    visible.size shouldBe 1
                    visible.single().modelCount shouldBe 1
                    visible.single().activeModelCount shouldBe 1
                    visible.single().isExecutable shouldBe true
                    val all = getProviders.getAiProviders(loginId = admin.id, includesInactive = true)
                    all.map { it.type } shouldBe listOf(AiProviderType.CLAUDE, AiProviderType.OPENAI)
                    all.first { it.id == provider.id }.updatedAt shouldBe providers.findById(provider.id!!)!!.updatedAt
                }
            }
            When("조회 AI_ADMIN_REQUIRED") {
                val action = suspend { getProvider.getAiProvider(loginId = normal.id!!, id = provider.id!!) }
                Then("저장소 상태와 응답을 확인한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_ADMIN_REQUIRED.message) { action() }
                    providers.findById(provider.id!!) shouldBe provider
                    models.findById(model.id!!) shouldBe model
                    providers.findAll().size shouldBe 1
                    models.findAllByProviderId(provider.id!!).size shouldBe 1
                }
            }
            When("조회 AI_ADMIN_USER_NOT_FOUND") {
                val action = suspend { getProvider.getAiProvider(loginId = RandomFixture.randomId(), id = provider.id!!) }
                Then("저장소 상태와 응답을 확인한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_ADMIN_USER_NOT_FOUND.message) { action() }
                    providers.findById(provider.id!!) shouldBe provider
                    models.findById(model.id!!) shouldBe model
                    providers.findAll().size shouldBe 1
                    models.findAllByProviderId(provider.id!!).size shouldBe 1
                }
            }
            When("목록 AI_ADMIN_REQUIRED") {
                val action = suspend { getProviders.getAiProviders(loginId = normal.id!!, includesInactive = true) }
                Then("저장소 상태와 응답을 확인한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_ADMIN_REQUIRED.message) { action() }
                    providers.findById(provider.id!!) shouldBe provider
                    models.findById(model.id!!) shouldBe model
                    providers.findAll().size shouldBe 1
                    models.findAllByProviderId(provider.id!!).size shouldBe 1
                }
            }
            When("목록 AI_ADMIN_USER_NOT_FOUND") {
                val action = suspend { getProviders.getAiProviders(loginId = RandomFixture.randomId(), includesInactive = true) }
                Then("저장소 상태와 응답을 확인한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_ADMIN_USER_NOT_FOUND.message) { action() }
                    providers.findById(provider.id!!) shouldBe provider
                    models.findById(model.id!!) shouldBe model
                    providers.findAll().size shouldBe 1
                    models.findAllByProviderId(provider.id!!).size shouldBe 1
                }
            }
            When("제공자 추가 AI_ADMIN_REQUIRED") {
                val action =
                    suspend {
                        createProvider.createAiProvider(
                            loginId = normal.id!!,
                            request =
                                CreateAiProviderRequest(
                                    type = AiProviderType.GEMINI,
                                    baseUrl = "https://generativelanguage.googleapis.com",
                                ),
                        )
                    }
                Then("저장소 상태와 응답을 확인한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_ADMIN_REQUIRED.message) { action() }
                    providers.findById(provider.id!!) shouldBe provider
                    models.findById(model.id!!) shouldBe model
                    providers.findAll().size shouldBe 1
                    models.findAllByProviderId(provider.id!!).size shouldBe 1
                }
            }
            When("제공자 추가 AI_ADMIN_USER_NOT_FOUND") {
                val action =
                    suspend {
                        createProvider.createAiProvider(
                            loginId = RandomFixture.randomId(),
                            request =
                                CreateAiProviderRequest(
                                    type = AiProviderType.GEMINI,
                                    baseUrl = "https://generativelanguage.googleapis.com",
                                ),
                        )
                    }
                Then("저장소 상태와 응답을 확인한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_ADMIN_USER_NOT_FOUND.message) { action() }
                    providers.findById(provider.id!!) shouldBe provider
                    models.findById(model.id!!) shouldBe model
                    providers.findAll().size shouldBe 1
                    models.findAllByProviderId(provider.id!!).size shouldBe 1
                }
            }
            When("제공자 변경 AI_ADMIN_REQUIRED") {
                val action =
                    suspend {
                        updateProvider.updateAiProvider(
                            loginId = normal.id!!,
                            id = provider.id!!,
                            request = UpdateAiProviderRequest(baseUrl = "https://api.openai.com/v2"),
                        )
                    }
                Then("저장소 상태와 응답을 확인한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_ADMIN_REQUIRED.message) { action() }
                    providers.findById(provider.id!!) shouldBe provider
                    models.findById(model.id!!) shouldBe model
                    providers.findAll().size shouldBe 1
                    models.findAllByProviderId(provider.id!!).size shouldBe 1
                }
            }
            When("제공자 변경 AI_ADMIN_USER_NOT_FOUND") {
                val action =
                    suspend {
                        updateProvider.updateAiProvider(
                            loginId = RandomFixture.randomId(),
                            id = provider.id!!,
                            request = UpdateAiProviderRequest(baseUrl = "https://api.openai.com/v2"),
                        )
                    }
                Then("저장소 상태와 응답을 확인한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_ADMIN_USER_NOT_FOUND.message) { action() }
                    providers.findById(provider.id!!) shouldBe provider
                    models.findById(model.id!!) shouldBe model
                    providers.findAll().size shouldBe 1
                    models.findAllByProviderId(provider.id!!).size shouldBe 1
                }
            }
            When("제공자 상태 AI_ADMIN_REQUIRED") {
                val action =
                    suspend {
                        activateProvider.updateAiProviderActive(
                            loginId = normal.id!!,
                            id = provider.id!!,
                            request = UpdateAiActiveRequest(isActive = false),
                        )
                    }
                Then("저장소 상태와 응답을 확인한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_ADMIN_REQUIRED.message) { action() }
                    providers.findById(provider.id!!) shouldBe provider
                    models.findById(model.id!!) shouldBe model
                    providers.findAll().size shouldBe 1
                    models.findAllByProviderId(provider.id!!).size shouldBe 1
                }
            }
            When("제공자 상태 AI_ADMIN_USER_NOT_FOUND") {
                val action =
                    suspend {
                        activateProvider.updateAiProviderActive(
                            loginId = RandomFixture.randomId(),
                            id = provider.id!!,
                            request = UpdateAiActiveRequest(isActive = false),
                        )
                    }
                Then("저장소 상태와 응답을 확인한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_ADMIN_USER_NOT_FOUND.message) { action() }
                    providers.findById(provider.id!!) shouldBe provider
                    models.findById(model.id!!) shouldBe model
                    providers.findAll().size shouldBe 1
                    models.findAllByProviderId(provider.id!!).size shouldBe 1
                }
            }
            When("모델 추가 AI_ADMIN_REQUIRED") {
                val action =
                    suspend {
                        createModel.createAvailableModel(
                            loginId = normal.id!!,
                            request = CreateAvailableModelRequest(providerId = provider.id!!, model = "new-model", priority = 1),
                        )
                    }
                Then("저장소 상태와 응답을 확인한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_ADMIN_REQUIRED.message) { action() }
                    providers.findById(provider.id!!) shouldBe provider
                    models.findById(model.id!!) shouldBe model
                    providers.findAll().size shouldBe 1
                    models.findAllByProviderId(provider.id!!).size shouldBe 1
                }
            }
            When("모델 추가 AI_ADMIN_USER_NOT_FOUND") {
                val action =
                    suspend {
                        createModel.createAvailableModel(
                            loginId = RandomFixture.randomId(),
                            request = CreateAvailableModelRequest(providerId = provider.id!!, model = "new-model", priority = 1),
                        )
                    }
                Then("저장소 상태와 응답을 확인한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_ADMIN_USER_NOT_FOUND.message) { action() }
                    providers.findById(provider.id!!) shouldBe provider
                    models.findById(model.id!!) shouldBe model
                    providers.findAll().size shouldBe 1
                    models.findAllByProviderId(provider.id!!).size shouldBe 1
                }
            }
            When("모델 변경 AI_ADMIN_REQUIRED") {
                val action =
                    suspend {
                        updateModel.updateAvailableModel(
                            loginId = normal.id!!,
                            id = model.id!!,
                            request = UpdateAvailableModelRequest(priority = 1),
                        )
                    }
                Then("저장소 상태와 응답을 확인한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_ADMIN_REQUIRED.message) { action() }
                    providers.findById(provider.id!!) shouldBe provider
                    models.findById(model.id!!) shouldBe model
                    providers.findAll().size shouldBe 1
                    models.findAllByProviderId(provider.id!!).size shouldBe 1
                }
            }
            When("모델 변경 AI_ADMIN_USER_NOT_FOUND") {
                val action =
                    suspend {
                        updateModel.updateAvailableModel(
                            loginId = RandomFixture.randomId(),
                            id = model.id!!,
                            request = UpdateAvailableModelRequest(priority = 1),
                        )
                    }
                Then("저장소 상태와 응답을 확인한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_ADMIN_USER_NOT_FOUND.message) { action() }
                    providers.findById(provider.id!!) shouldBe provider
                    models.findById(model.id!!) shouldBe model
                    providers.findAll().size shouldBe 1
                    models.findAllByProviderId(provider.id!!).size shouldBe 1
                }
            }
            When("모델 상태 AI_ADMIN_REQUIRED") {
                val action =
                    suspend {
                        activateModel.updateAvailableModelActive(
                            loginId = normal.id!!,
                            id = model.id!!,
                            request = UpdateAiActiveRequest(isActive = false),
                        )
                    }
                Then("저장소 상태와 응답을 확인한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_ADMIN_REQUIRED.message) { action() }
                    providers.findById(provider.id!!) shouldBe provider
                    models.findById(model.id!!) shouldBe model
                    providers.findAll().size shouldBe 1
                    models.findAllByProviderId(provider.id!!).size shouldBe 1
                }
            }
            When("모델 상태 AI_ADMIN_USER_NOT_FOUND") {
                val action =
                    suspend {
                        activateModel.updateAvailableModelActive(
                            loginId = RandomFixture.randomId(),
                            id = model.id!!,
                            request = UpdateAiActiveRequest(isActive = false),
                        )
                    }
                Then("저장소 상태와 응답을 확인한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_ADMIN_USER_NOT_FOUND.message) { action() }
                    providers.findById(provider.id!!) shouldBe provider
                    models.findById(model.id!!) shouldBe model
                    providers.findAll().size shouldBe 1
                    models.findAllByProviderId(provider.id!!).size shouldBe 1
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
            When("모델 생성 제공자 없음") {
                val action =
                    suspend {
                        createModel.createAvailableModel(
                            loginId = admin.id!!,
                            request = CreateAvailableModelRequest(providerId = RandomFixture.randomId(), model = "model", priority = 1),
                        )
                    }
                Then("저장소 상태와 응답을 확인한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_ADMIN_MODEL_PROVIDER_NOT_FOUND.message) { action() }
                }
            }
            When("모델 변경 없음") {
                val action =
                    suspend {
                        updateModel.updateAvailableModel(
                            loginId = admin.id!!,
                            id = RandomFixture.randomId(),
                            request = UpdateAvailableModelRequest(priority = 1),
                        )
                    }
                Then("저장소 상태와 응답을 확인한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_ADMIN_MODEL_NOT_FOUND.message) { action() }
                }
            }
            When("모델 상태 없음") {
                val action =
                    suspend {
                        activateModel.updateAvailableModelActive(
                            loginId = admin.id!!,
                            id = RandomFixture.randomId(),
                            request = UpdateAiActiveRequest(isActive = false),
                        )
                    }
                Then("저장소 상태와 응답을 확인한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_ADMIN_MODEL_NOT_FOUND.message) { action() }
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
