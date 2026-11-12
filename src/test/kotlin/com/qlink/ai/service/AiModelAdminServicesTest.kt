package com.qlink.ai.service

import com.qlink.ai.domain.AiJob
import com.qlink.ai.domain.AiProvider
import com.qlink.ai.domain.AiProviderType
import com.qlink.ai.domain.AvailableModel
import com.qlink.ai.domain.DailyUsage
import com.qlink.ai.domain.UserProvider
import com.qlink.ai.dto.CreateAvailableModelRequest
import com.qlink.ai.dto.UpdateAiActiveRequest
import com.qlink.ai.dto.UpdateAvailableModelRequest
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

class AiModelAdminServicesTest :
    BaseServiceTest({
        val users = koinGet<UserRepository>()
        val providers = koinGet<AiProviderRepository>()
        val models = koinGet<AvailableModelRepository>()
        val keys = koinGet<UserProviderRepository>()
        val links = koinGet<LinkRepository>()
        val jobs = koinGet<AiJobRepository>()
        val usages = koinGet<DailyUsageRepository>()
        val createModel = koinGet<CreateAvailableModelService>()
        val updateModel = koinGet<UpdateAvailableModelService>()
        val activateModel = koinGet<UpdateAvailableModelActiveService>()
        Given("관리자, 일반 사용자와 이력이 연결된 모델") {
            lateinit var admin: User
            lateinit var normal: User
            lateinit var provider: AiProvider
            lateinit var model: AvailableModel
            lateinit var key: UserProvider
            lateinit var link: Link
            lateinit var job: AiJob
            lateinit var usage: DailyUsage
            beforeTest {
                if (it.type != io.kotest.core.test.TestType.Test) return@beforeTest
                admin = users.insert(UserFixture.createRandomValidSuperAdmin())
                normal = users.insert(UserFixture.createRandomValidUser())
                provider = providers.insert(AiFixture.createRandomValidAiProvider())
                model = models.insert(AiFixture.createRandomAvailableModelOf(providerId = provider.id!!))
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
            listOf(true, false).forEach { providerActive ->
                When("제공자 활성=$providerActive 상태에서 모델을 추가하면") {
                    lateinit var request: CreateAvailableModelRequest
                    beforeTest {
                        if (it.type != io.kotest.core.test.TestType.Test) return@beforeTest
                        provider = providers.update(provider.copy(isActive = providerActive))
                        val candidate = AiFixture.createRandomAvailableModelOf(providerId = provider.id!!)
                        request =
                            CreateAvailableModelRequest(
                                providerId = candidate.providerId,
                                model = candidate.model,
                                priority = candidate.priority,
                                rpdLimit = candidate.rpdLimit,
                                tpdLimit = candidate.tpdLimit,
                            )
                    }
                    val action = suspend { createModel.createAvailableModel(loginId = admin.id!!, request = request) }
                    Then("활성 모델과 설정이 저장된다") {
                        val saved = models.findById(action().id).shouldNotBeNull()
                        saved.providerId shouldBe provider.id
                        saved.model shouldBe request.model
                        saved.priority shouldBe request.priority
                        saved.rpdLimit shouldBe request.rpdLimit
                        saved.tpdLimit shouldBe request.tpdLimit
                        saved.isActive shouldBe true
                        models.findAllByProviderId(provider.id!!).size shouldBe 2
                    }
                }
            }
            When("같은 제공자에 같은 식별자로 추가하면") {
                val action =
                    suspend {
                        createModel.createAvailableModel(
                            loginId = admin.id!!,
                            request =
                                CreateAvailableModelRequest(
                                    providerId = provider.id!!,
                                    model = model.model,
                                    priority = model.priority,
                                ),
                        )
                    }
                Then("중복 오류이며 기존 모델만 남는다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_MODEL_DUPLICATE.message) { action() }
                    models.findAllByProviderId(provider.id!!) shouldBe listOf(model)
                }
            }
            When("다른 제공자에 같은 식별자로 추가하면") {
                lateinit var other: AiProvider
                beforeTest {
                    if (it.type != io.kotest.core.test.TestType.Test) return@beforeTest
                    other =
                        providers.insert(
                            AiFixture.createRandomValidAiProvider(type = AiProviderType.entries.first { it != provider.type }),
                        )
                }
                val action =
                    suspend {
                        createModel.createAvailableModel(
                            loginId = admin.id!!,
                            request =
                                CreateAvailableModelRequest(
                                    providerId = other.id!!,
                                    model = model.model,
                                    priority = model.priority,
                                ),
                        )
                    }
                Then("새 제공자 아래 저장되고 원본도 남는다") {
                    val saved = models.findById(action().id).shouldNotBeNull()
                    saved.providerId shouldBe other.id
                    saved.model shouldBe model.model
                    models.findById(model.id!!) shouldBe model
                }
            }
            When("제한과 우선순위를 수정하면") {
                lateinit var request: UpdateAvailableModelRequest
                beforeTest {
                    if (it.type != io.kotest.core.test.TestType.Test) return@beforeTest
                    request =
                        UpdateAvailableModelRequest(
                            priority = model.priority + 1,
                            rpdLimit = model.rpdLimit!! + 1,
                            tpdLimit = model.tpdLimit!! + 1,
                        )
                }
                val action = suspend { updateModel.updateAvailableModel(loginId = admin.id!!, id = model.id!!, request = request) }
                Then("설정과 수정 시각만 바뀌고 식별자와 모든 참조는 유지된다") {
                    val result = action()
                    val saved = models.findById(model.id!!).shouldNotBeNull()
                    saved shouldBe
                        model.copy(
                            priority = request.priority,
                            rpdLimit = request.rpdLimit,
                            tpdLimit = request.tpdLimit,
                            updatedAt = saved.updatedAt,
                        )
                    saved.updatedAt shouldNotBe model.updatedAt
                    result.id shouldBe saved.id
                    result.updatedAt shouldBe saved.updatedAt
                    val savedUser = users.findById(normal.id!!).shouldNotBeNull()
                    savedUser.defaultAiProviderId shouldBe normal.defaultAiProviderId
                    savedUser.defaultModelId shouldBe normal.defaultModelId
                    savedUser.updatedAt shouldBe normal.updatedAt
                    links.findById(link.id!!)!!.workModelId shouldBe model.id
                    val savedJob = jobs.findById(job.id!!).shouldNotBeNull()
                    savedJob.requestModelId shouldBe job.requestModelId
                    savedJob.responseModelId shouldBe job.responseModelId
                    val savedKey = keys.findById(key.id!!).shouldNotBeNull()
                    savedKey.apiKey shouldBe key.apiKey
                    savedKey.providerId shouldBe key.providerId
                    val savedUsage =
                        usages
                            .findByUserIdAndProviderIdAndUsageDate(
                                userProviderId = key.id!!,
                                modelId = model.id!!,
                                usageDate = usage.usageDate,
                            ).shouldNotBeNull()
                    savedUsage.id shouldBe usage.id
                    savedUsage.requests shouldBe usage.requests
                    savedUsage.tokens shouldBe usage.tokens
                }
            }
            When("같은 우선순위를 설정하고 제한을 생략하면") {
                beforeTest {
                    if (it.type != io.kotest.core.test.TestType.Test) return@beforeTest
                    models.insert(AiFixture.createRandomAvailableModelOf(providerId = provider.id!!, priority = model.priority))
                }
                val action =
                    suspend {
                        updateModel.updateAvailableModel(
                            loginId = admin.id!!,
                            id = model.id!!,
                            request = UpdateAvailableModelRequest(priority = model.priority),
                        )
                    }
                Then("동률을 허용하며 기존 제한은 해제된다") {
                    action()
                    val saved = models.findById(model.id!!).shouldNotBeNull()
                    saved.rpdLimit shouldBe null
                    saved.tpdLimit shouldBe null
                    models.findAllByProviderId(provider.id!!).map { it.priority } shouldBe listOf(model.priority, model.priority)
                }
            }
            When("비활성화 후 같은 상태를 반복하고 재활성화하면") {
                val action =
                    suspend {
                        activateModel.updateAvailableModelActive(
                            loginId = admin.id!!,
                            id = model.id!!,
                            request = UpdateAiActiveRequest(isActive = false),
                        )
                    }
                Then("상태가 저장되고 반복 시각과 식별자 및 이력은 보존된다") {
                    val first = action()
                    val inactive = models.findById(model.id!!).shouldNotBeNull()
                    inactive.isActive shouldBe false
                    first.updatedAt shouldBe inactive.updatedAt
                    action() shouldBe first
                    models.findById(model.id!!) shouldBe inactive
                    val savedUser = users.findById(normal.id!!).shouldNotBeNull()
                    savedUser.defaultAiProviderId shouldBe normal.defaultAiProviderId
                    savedUser.defaultModelId shouldBe normal.defaultModelId
                    savedUser.updatedAt shouldBe normal.updatedAt
                    links.findById(link.id!!)!!.workModelId shouldBe model.id
                    val savedJob = jobs.findById(job.id!!).shouldNotBeNull()
                    savedJob.requestModelId shouldBe job.requestModelId
                    savedJob.responseModelId shouldBe job.responseModelId
                    val savedKey = keys.findById(key.id!!).shouldNotBeNull()
                    savedKey.apiKey shouldBe key.apiKey
                    savedKey.providerId shouldBe key.providerId
                    val savedUsage =
                        usages
                            .findByUserIdAndProviderIdAndUsageDate(
                                userProviderId = key.id!!,
                                modelId = model.id!!,
                                usageDate = usage.usageDate,
                            ).shouldNotBeNull()
                    savedUsage.id shouldBe usage.id
                    savedUsage.requests shouldBe usage.requests
                    savedUsage.tokens shouldBe usage.tokens
                    val restored =
                        activateModel.updateAvailableModelActive(
                            loginId = admin.id!!,
                            id = model.id!!,
                            request = UpdateAiActiveRequest(isActive = true),
                        )
                    val saved = models.findById(model.id!!).shouldNotBeNull()
                    saved shouldBe model.copy(updatedAt = saved.updatedAt)
                    restored.id shouldBe model.id
                    restored.isActive shouldBe true
                    restored.updatedAt shouldBe saved.updatedAt
                }
            }
            listOf(ErrorCode.AI_ADMIN_REQUIRED, ErrorCode.AI_ADMIN_USER_NOT_FOUND).forEach { errorCode ->
                val loginId = { if (errorCode == ErrorCode.AI_ADMIN_REQUIRED) normal.id!! else RandomFixture.randomId() }
                val actions: Map<String, suspend () -> Any> =
                    mapOf(
                        "생성" to {
                            createModel.createAvailableModel(
                                loginId = loginId(),
                                request =
                                    CreateAvailableModelRequest(
                                        providerId = provider.id!!,
                                        model = model.model,
                                        priority = model.priority,
                                    ),
                            )
                        },
                        "설정 수정" to {
                            updateModel.updateAvailableModel(
                                loginId = loginId(),
                                id = model.id!!,
                                request = UpdateAvailableModelRequest(priority = model.priority + 1),
                            )
                        },
                        "상태 변경" to {
                            activateModel.updateAvailableModelActive(
                                loginId = loginId(),
                                id = model.id!!,
                                request = UpdateAiActiveRequest(isActive = false),
                            )
                        },
                    )
                actions.forEach { (name, action) ->
                    When("$name 요청이 ${errorCode.name} 조건이면") {
                        Then("거절하고 모델과 제공자를 보존한다") {
                            shouldThrowWithMessage<BusinessException>(errorCode.message) { action() }
                            models.findAllByProviderId(provider.id!!) shouldBe listOf(model)
                            providers.findById(provider.id!!) shouldBe provider
                        }
                    }
                }
            }
            When("없는 제공자에 모델을 생성하면") {
                val action =
                    suspend {
                        createModel.createAvailableModel(
                            loginId = admin.id!!,
                            request =
                                CreateAvailableModelRequest(
                                    providerId = RandomFixture.randomId(),
                                    model = model.model,
                                    priority = model.priority,
                                ),
                        )
                    }
                Then("모델 생성 제공자 없음 오류가 발생한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_ADMIN_MODEL_PROVIDER_NOT_FOUND.message) { action() }
                    models.findAllByProviderId(provider.id!!) shouldBe listOf(model)
                }
            }
            When("없는 모델의 설정을 수정하면") {
                val action =
                    suspend {
                        updateModel.updateAvailableModel(
                            loginId = admin.id!!,
                            id = RandomFixture.randomId(),
                            request = UpdateAvailableModelRequest(priority = model.priority),
                        )
                    }
                Then("모델 없음 오류가 발생한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_ADMIN_MODEL_NOT_FOUND.message) { action() }
                    models.findById(model.id!!) shouldBe model
                }
            }
            When("없는 모델의 상태를 변경하면") {
                val action =
                    suspend {
                        activateModel.updateAvailableModelActive(
                            loginId = admin.id!!,
                            id = RandomFixture.randomId(),
                            request = UpdateAiActiveRequest(isActive = false),
                        )
                    }
                Then("모델 없음 오류가 발생한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_ADMIN_MODEL_NOT_FOUND.message) { action() }
                    models.findById(model.id!!) shouldBe model
                }
            }
        }
    })
