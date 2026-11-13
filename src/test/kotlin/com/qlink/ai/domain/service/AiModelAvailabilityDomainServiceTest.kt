package com.qlink.ai.domain.service

import com.qlink.support.fixture.AiFixture
import com.qlink.support.fixture.RandomFixture
import com.qlink.support.fixture.copyForTestActive
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class AiModelAvailabilityDomainServiceTest :
    BehaviorSpec({
        Given("활성 제공자와 활성·비활성 모델") {
            val provider = AiFixture.createRandomValidAiProvider(id = RandomFixture.randomId())
            val first = AiFixture.createRandomAvailableModelOf(providerId = provider.id!!, id = 1, priority = 1)
            val second = AiFixture.createRandomAvailableModelOf(providerId = provider.id, id = 2, priority = 1)
            val inactive = AiFixture.createRandomAvailableModelOf(providerId = provider.id, isActive = false, priority = 1)
            val later = AiFixture.createRandomAvailableModelOf(providerId = provider.id, id = 0, priority = 2)
            val models = listOf(later, second, inactive, first)

            When("사용 가능한 모델을 선택하면") {
                val selected = AiModelAvailabilityDomainService.selectableModels(provider = provider, models = models)
                Then("활성 모델만 priority, id 오름차순으로 반환한다") {
                    selected shouldBe listOf(first, second, later)
                }
            }
            When("기본 모델을 선택하면") {
                val selected = AiModelAvailabilityDomainService.defaultModel(provider = provider, models = models)
                Then("활성 모델 중 priority와 id가 가장 작은 모델을 반환한다") {
                    selected shouldBe first
                }
            }
            When("활성 모델의 사용 가능 여부를 검증하면") {
                val validate = { AiModelAvailabilityDomainService.validateUsable(provider = provider, model = first) }
                Then("통과한다") {
                    shouldNotThrowAny { validate() }
                }
            }
            When("제공자가 비활성이면") {
                val selected =
                    AiModelAvailabilityDomainService.selectableModels(
                        provider = provider.copyForTestActive(isActive = false),
                        models = models,
                    )
                Then("빈 목록을 반환한다") {
                    selected shouldBe emptyList()
                }
            }
            When("비활성 제공자의 기본 모델을 선택하면") {
                val selected =
                    AiModelAvailabilityDomainService.defaultModel(
                        provider = provider.copyForTestActive(isActive = false),
                        models = models,
                    )
                Then("기본 모델이 없다") {
                    selected shouldBe null
                }
            }
            When("활성 모델이 없으면") {
                val selected = AiModelAvailabilityDomainService.selectableModels(provider = provider, models = listOf(inactive))
                Then("빈 목록을 반환한다") {
                    selected shouldBe emptyList()
                }
            }
        }
    })
