package com.qlink.ai.domain

import com.qlink.common.error.BusinessException
import com.qlink.support.fixture.AiFixture
import com.qlink.support.fixture.RandomFixture
import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class AvailableModelValidationTest :
    BehaviorSpec({
        Given("활성 AI 모델") {
            val model = AiFixture.createRandomAvailableModelOf(providerId = RandomFixture.randomId(), isActive = true)

            When("활성 상태를 검증하면") {
                val validate = { model.validateActive() }

                Then("예외 없이 활성 상태를 유지한다") {
                    shouldNotThrow<BusinessException> { validate() }
                    model.isActive shouldBe true
                }
            }
        }
    })
