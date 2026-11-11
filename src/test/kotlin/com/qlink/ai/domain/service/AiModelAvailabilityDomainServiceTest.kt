package com.qlink.ai.domain.service

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.support.fixture.AiFixture
import com.qlink.support.fixture.RandomFixture
import io.kotest.assertions.throwables.shouldThrowWithMessage
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class AiModelAvailabilityDomainServiceTest :
    BehaviorSpec({
        Given("제공자와 모델 활성 상태") {
            When("활성 모델을 우선순위·id 순으로 선택") {
                val provider = AiFixture.createRandomValidAiProvider(id = RandomFixture.randomId())
                val first = AiFixture.createRandomAvailableModelOf(providerId = provider.id!!, id = 1, priority = 1)
                val second = AiFixture.createRandomAvailableModelOf(providerId = provider.id, id = 2, priority = 1)
                val inactive = AiFixture.createRandomAvailableModelOf(providerId = provider.id, isActive = false, priority = 1)
                val later = AiFixture.createRandomAvailableModelOf(providerId = provider.id, id = 0, priority = 2)
                val models = listOf(later, second, inactive, first)
                val action = { AiModelAvailabilityDomainService.selectableModels(provider = provider, models = models) }
                Then("규칙에 맞게 처리한다") {
                    action() shouldBe listOf(first, second, later)
                }
            }
            When("기본 모델은 사용 가능한 첫 모델") {
                val provider = AiFixture.createRandomValidAiProvider(id = RandomFixture.randomId())
                val first = AiFixture.createRandomAvailableModelOf(providerId = provider.id!!, id = 1, priority = 1)
                val second = AiFixture.createRandomAvailableModelOf(providerId = provider.id, id = 2, priority = 1)
                val inactive = AiFixture.createRandomAvailableModelOf(providerId = provider.id, isActive = false, priority = 1)
                val later = AiFixture.createRandomAvailableModelOf(providerId = provider.id, id = 0, priority = 2)
                val models = listOf(later, second, inactive, first)
                val action = { AiModelAvailabilityDomainService.defaultModel(provider = provider, models = models) }
                Then("규칙에 맞게 처리한다") {
                    action() shouldBe first
                }
            }
            When("비활성 모델 사용 거절") {
                val provider = AiFixture.createRandomValidAiProvider(id = RandomFixture.randomId())
                val first = AiFixture.createRandomAvailableModelOf(providerId = provider.id!!, id = 1, priority = 1)
                val second = AiFixture.createRandomAvailableModelOf(providerId = provider.id, id = 2, priority = 1)
                val inactive = AiFixture.createRandomAvailableModelOf(providerId = provider.id, isActive = false, priority = 1)
                val models = listOf(second, inactive, first)
                val action = { AiModelAvailabilityDomainService.validateUsable(provider = provider, model = inactive) }
                Then("규칙에 맞게 처리한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_MODEL_INACTIVE.message) { action() }
                }
            }
            When("비활성 제공자는 모델을 반환하지 않는다") {
                val provider = AiFixture.createRandomValidAiProvider(id = RandomFixture.randomId())
                val first = AiFixture.createRandomAvailableModelOf(providerId = provider.id!!, id = 1, priority = 1)
                val second = AiFixture.createRandomAvailableModelOf(providerId = provider.id, id = 2, priority = 1)
                val inactive = AiFixture.createRandomAvailableModelOf(providerId = provider.id, isActive = false, priority = 1)
                val models = listOf(second, inactive, first)
                val action = {
                    AiModelAvailabilityDomainService.selectableModels(provider = provider.copy(isActive = false), models = models)
                }
                Then("규칙에 맞게 처리한다") {
                    action() shouldBe emptyList()
                }
            }
            When("비활성 제공자는 기본값도 없다") {
                val provider = AiFixture.createRandomValidAiProvider(id = RandomFixture.randomId())
                val first = AiFixture.createRandomAvailableModelOf(providerId = provider.id!!, id = 1, priority = 1)
                val second = AiFixture.createRandomAvailableModelOf(providerId = provider.id, id = 2, priority = 1)
                val inactive = AiFixture.createRandomAvailableModelOf(providerId = provider.id, isActive = false, priority = 1)
                val models = listOf(second, inactive, first)
                val action = { AiModelAvailabilityDomainService.defaultModel(provider = provider.copy(isActive = false), models = models) }
                Then("규칙에 맞게 처리한다") {
                    action() shouldBe null
                }
            }
            When("비활성 제공자의 모델 사용 거절") {
                val provider = AiFixture.createRandomValidAiProvider(id = RandomFixture.randomId())
                val first = AiFixture.createRandomAvailableModelOf(providerId = provider.id!!, id = 1, priority = 1)
                val second = AiFixture.createRandomAvailableModelOf(providerId = provider.id, id = 2, priority = 1)
                val inactive = AiFixture.createRandomAvailableModelOf(providerId = provider.id, isActive = false, priority = 1)
                val models = listOf(second, inactive, first)
                val action = { AiModelAvailabilityDomainService.validateUsable(provider = provider.copy(isActive = false), model = first) }
                Then("규칙에 맞게 처리한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_PROVIDER_INACTIVE.message) { action() }
                }
            }
            When("활성 모델이 없으면 빈 목록") {
                val provider = AiFixture.createRandomValidAiProvider(id = RandomFixture.randomId())
                val first = AiFixture.createRandomAvailableModelOf(providerId = provider.id!!, id = 1, priority = 1)
                val second = AiFixture.createRandomAvailableModelOf(providerId = provider.id, id = 2, priority = 1)
                val inactive = AiFixture.createRandomAvailableModelOf(providerId = provider.id, isActive = false, priority = 1)
                val models = listOf(second, inactive, first)
                val action = { AiModelAvailabilityDomainService.selectableModels(provider = provider, models = listOf(inactive)) }
                Then("규칙에 맞게 처리한다") {
                    action() shouldBe emptyList()
                }
            }
        }
    })
