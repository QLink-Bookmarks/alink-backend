package com.qlink.ai.domain

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.support.fixture.AiFixture
import com.qlink.support.fixture.RandomFixture
import io.kotest.assertions.throwables.shouldThrowWithMessage
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs

class AvailableModelTest :
    BehaviorSpec({
        Given("모델 생성과 변경 규칙") {
            When("정상") {
                val now = RandomFixture.randomPastInstant()
                val action = {
                    AvailableModel.create(
                        providerId = RandomFixture.randomId(),
                        model = "model-A.1_2",
                        priority = 1,
                        rpdLimit = null,
                        tpdLimit = null,
                        now = now,
                    )
                }
                Then("규칙에 맞게 처리한다") {
                    val saved = action()
                    saved.isActive shouldBe true
                    saved.activeAt shouldBe now
                    saved.model shouldBe "model-A.1_2"
                    saved.rpdLimit shouldBe null
                    saved.tpdLimit shouldBe null
                }
            }
            When("100자") {
                val action = {
                    AvailableModel.create(
                        providerId = RandomFixture.randomId(),
                        model = "a".repeat(100),
                        priority = RandomFixture.randomInt(1, 100),
                        rpdLimit = 1,
                        tpdLimit = 1,
                        now = RandomFixture.randomPastInstant(),
                    )
                }
                Then("규칙에 맞게 처리한다") {
                    val saved = action()
                    saved.isActive shouldBe true
                    saved.model shouldBe "a".repeat(100)
                    saved.rpdLimit shouldBe 1
                    saved.tpdLimit shouldBe 1
                }
            }
            When("101자") {
                val action = {
                    AvailableModel.create(
                        providerId = RandomFixture.randomId(),
                        model = "a".repeat(101),
                        priority = RandomFixture.randomInt(1, 100),
                        rpdLimit = 1,
                        tpdLimit = 1,
                        now = RandomFixture.randomPastInstant(),
                    )
                }
                Then("규칙에 맞게 처리한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_MODEL_NAME_OVER_MAX.message) { action() }
                }
            }
            When("공백") {
                val action = {
                    AvailableModel.create(
                        providerId = RandomFixture.randomId(),
                        model = " ",
                        priority = RandomFixture.randomInt(1, 100),
                        rpdLimit = null,
                        tpdLimit = null,
                        now = RandomFixture.randomPastInstant(),
                    )
                }
                Then("규칙에 맞게 처리한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_MODEL_NAME_BLANK.message) { action() }
                }
            }
            When("경로") {
                val action = {
                    AvailableModel.create(
                        providerId = RandomFixture.randomId(),
                        model = "../model",
                        priority = RandomFixture.randomInt(1, 100),
                        rpdLimit = null,
                        tpdLimit = null,
                        now = RandomFixture.randomPastInstant(),
                    )
                }
                Then("규칙에 맞게 처리한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_MODEL_NAME_INVALID.message) { action() }
                }
            }
            When("콜론") {
                val action = {
                    AvailableModel.create(
                        providerId = RandomFixture.randomId(),
                        model = "a:b",
                        priority = RandomFixture.randomInt(1, 100),
                        rpdLimit = null,
                        tpdLimit = null,
                        now = RandomFixture.randomPastInstant(),
                    )
                }
                Then("규칙에 맞게 처리한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_MODEL_NAME_INVALID.message) { action() }
                }
            }
            When("내부 공백") {
                val action = {
                    AvailableModel.create(
                        providerId = RandomFixture.randomId(),
                        model = "a b",
                        priority = RandomFixture.randomInt(1, 100),
                        rpdLimit = null,
                        tpdLimit = null,
                        now = RandomFixture.randomPastInstant(),
                    )
                }
                Then("규칙에 맞게 처리한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_MODEL_NAME_INVALID.message) { action() }
                }
            }
            When("우선순위 0") {
                val action = {
                    AvailableModel.create(
                        providerId = RandomFixture.randomId(),
                        model = "model-${RandomFixture.randomId()}",
                        priority = 0,
                        rpdLimit = null,
                        tpdLimit = null,
                        now = RandomFixture.randomPastInstant(),
                    )
                }
                Then("규칙에 맞게 처리한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_MODEL_PRIORITY_NOT_POSITIVE.message) { action() }
                }
            }
            When("우선순위 음수") {
                val action = {
                    AvailableModel.create(
                        providerId = RandomFixture.randomId(),
                        model = "model-${RandomFixture.randomId()}",
                        priority = -1,
                        rpdLimit = null,
                        tpdLimit = null,
                        now = RandomFixture.randomPastInstant(),
                    )
                }
                Then("규칙에 맞게 처리한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_MODEL_PRIORITY_NOT_POSITIVE.message) { action() }
                }
            }
            When("rpd 0") {
                val action = {
                    AvailableModel.create(
                        providerId = RandomFixture.randomId(),
                        model = "model-${RandomFixture.randomId()}",
                        priority = RandomFixture.randomInt(1, 100),
                        rpdLimit = 0,
                        tpdLimit = null,
                        now = RandomFixture.randomPastInstant(),
                    )
                }
                Then("규칙에 맞게 처리한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_MODEL_LIMIT_NOT_POSITIVE.message) { action() }
                }
            }
            When("tpd 음수") {
                val action = {
                    AvailableModel.create(
                        providerId = RandomFixture.randomId(),
                        model = "model-${RandomFixture.randomId()}",
                        priority = RandomFixture.randomInt(1, 100),
                        rpdLimit = null,
                        tpdLimit = -1,
                        now = RandomFixture.randomPastInstant(),
                    )
                }
                Then("규칙에 맞게 처리한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_MODEL_LIMIT_NOT_POSITIVE.message) { action() }
                }
            }
            When("설정을 바꾸면 식별자 유지와 제한 해제") {
                val original = AiFixture.createRandomAvailableModelOf(providerId = RandomFixture.randomId(), id = RandomFixture.randomId())
                val now = RandomFixture.randomFutureInstantAfterTomorrow()
                val priority = RandomFixture.randomInt(1, 100)
                val action = { original.change(priority = priority, rpdLimit = null, tpdLimit = null, now = now) }
                Then("규칙에 맞게 처리한다") {
                    val changed = action()
                    changed.id shouldBe original.id
                    changed.model shouldBe original.model
                    changed.providerId shouldBe original.providerId
                    changed.priority shouldBe priority
                    changed.rpdLimit shouldBe null
                    changed.tpdLimit shouldBe null
                    changed.updatedAt shouldBe now
                }
            }
            When("잘못된 변경 0/null/null") {
                val original = AiFixture.createRandomAvailableModelOf(providerId = RandomFixture.randomId())
                val action = {
                    original.change(
                        priority = 0,
                        rpdLimit = null,
                        tpdLimit = null,
                        now = RandomFixture.randomFutureInstantAfterTomorrow(),
                    )
                }
                Then("규칙에 맞게 처리한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_MODEL_PRIORITY_NOT_POSITIVE.message) { action() }
                }
            }
            When("잘못된 변경 1/0/null") {
                val original = AiFixture.createRandomAvailableModelOf(providerId = RandomFixture.randomId())
                val action = {
                    original.change(
                        priority = RandomFixture.randomInt(1, 100),
                        rpdLimit = 0,
                        tpdLimit = null,
                        now = RandomFixture.randomFutureInstantAfterTomorrow(),
                    )
                }
                Then("규칙에 맞게 처리한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_MODEL_LIMIT_NOT_POSITIVE.message) { action() }
                }
            }
            When("잘못된 변경 1/null/0") {
                val original = AiFixture.createRandomAvailableModelOf(providerId = RandomFixture.randomId())
                val action = {
                    original.change(
                        priority = RandomFixture.randomInt(1, 100),
                        rpdLimit = null,
                        tpdLimit = 0,
                        now = RandomFixture.randomFutureInstantAfterTomorrow(),
                    )
                }
                Then("규칙에 맞게 처리한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_MODEL_LIMIT_NOT_POSITIVE.message) { action() }
                }
            }
            When("활성 상태를 false로 변경") {
                val original = AiFixture.createRandomAvailableModelOf(providerId = RandomFixture.randomId(), id = RandomFixture.randomId())
                val now = RandomFixture.randomFutureInstantAfterTomorrow()
                val action = { original.changeActive(isActive = false, now = now) }
                Then("규칙에 맞게 처리한다") {
                    val changed = action()
                    changed.isActive shouldBe false
                    changed.activeAt shouldBe null
                    changed.updatedAt shouldBe now
                    changed.id shouldBe original.id
                    changed.model shouldBe original.model
                    changed.providerId shouldBe original.providerId
                    original.isActive shouldBe true
                }
            }
            When("비활성 상태를 true로 변경") {
                val original =
                    AiFixture.createRandomAvailableModelOf(
                        providerId = RandomFixture.randomId(),
                        id = RandomFixture.randomId(),
                        isActive = false,
                    )
                val now = RandomFixture.randomFutureInstantAfterTomorrow()
                val action = { original.changeActive(isActive = true, now = now) }
                Then("규칙에 맞게 처리한다") {
                    val changed = action()
                    changed.isActive shouldBe true
                    changed.activeAt shouldBe now
                    changed.updatedAt shouldBe now
                    changed.id shouldBe original.id
                    changed.model shouldBe original.model
                    changed.providerId shouldBe original.providerId
                    original.isActive shouldBe false
                }
            }
            When("같은 활성 상태를 요청하면") {
                val original = AiFixture.createRandomAvailableModelOf(providerId = RandomFixture.randomId())
                val action = { original.changeActive(isActive = true, now = RandomFixture.randomFutureInstantAfterTomorrow()) }
                Then("원래 모델과 변경 시각을 유지한다") {
                    action() shouldBeSameInstanceAs original
                }
            }
            When("비활성 모델 검증") {
                val original = AiFixture.createRandomAvailableModelOf(providerId = RandomFixture.randomId(), isActive = false)
                val action = { original.validateActive() }
                Then("규칙에 맞게 처리한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_MODEL_INACTIVE.message) { action() }
                }
            }
        }
    })
