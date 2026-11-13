package com.qlink.ai.domain

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.support.fixture.AiFixture
import com.qlink.support.fixture.RandomFixture
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrowWithMessage
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs

class AiProviderTest :
    BehaviorSpec({
        Given("공식 주소를 사용하는 제공자") {
            When("공식 주소로 생성하면") {
                val action = { AiProvider.create(type = AiProviderType.OPENAI, baseUrl = "https://api.openai.com/v1/") }
                Then("규칙에 맞게 처리한다") {
                    val result = action()
                    result.isActive shouldBe false
                    result.activeAt shouldBe null
                    result.baseUrl shouldBe "https://api.openai.com/v1"
                }
            }
            When("주소가 255자이면") {
                val base = "https://api.openai.com/"
                val url = base + "a".repeat(255 - base.length)
                val action = { AiProvider.create(type = AiProviderType.OPENAI, baseUrl = url) }
                Then("규칙에 맞게 처리한다") {
                    action().baseUrl.length shouldBe 255
                }
            }
            When("주소가 256자이면") {
                val base = "https://api.openai.com/"
                val url = base + "a".repeat(256 - base.length)
                val action = { AiProvider.create(type = AiProviderType.OPENAI, baseUrl = url) }
                Then("규칙에 맞게 처리한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_PROVIDER_BASE_URL_INVALID.message) { action() }
                }
            }
            When("잘못된 주소로 생성하면") {
                val action = { AiProvider.create(type = AiProviderType.OPENAI, baseUrl = RandomFixture.randomUrl()) }
                Then("규칙에 맞게 처리한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_PROVIDER_BASE_URL_INVALID.message) { action() }
                }
            }
            When("주소를 변경하면 식별자가 유지된다") {
                val original = AiFixture.createRandomValidAiProvider(type = AiProviderType.OPENAI, id = RandomFixture.randomId())
                val now = RandomFixture.randomFutureInstantAfterTomorrow()
                val action = { original.changeBaseUrl(baseUrl = "https://api.openai.com/v2/", now = now) }
                Then("규칙에 맞게 처리한다") {
                    val changed = action()
                    changed.id shouldBe original.id
                    changed.type shouldBe original.type
                    changed.baseUrl shouldBe "https://api.openai.com/v2"
                    changed.activeAt shouldBe original.activeAt
                    changed.updatedAt shouldBe now
                    original.updatedAt shouldBe null
                }
            }
            When("잘못된 주소로 변경하면") {
                val original = AiFixture.createRandomValidAiProvider()
                val action = { original.changeBaseUrl(baseUrl = "http://localhost", now = RandomFixture.randomPastInstant()) }
                Then("규칙에 맞게 처리한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_PROVIDER_BASE_URL_INVALID.message) { action() }
                }
            }
            When("상태 false에서 true, 실행 어댑터 true") {
                val original = AiFixture.createRandomValidAiProvider(isActive = false, id = RandomFixture.randomId())
                val now = RandomFixture.randomFutureInstantAfterTomorrow()
                val action = { original.changeActive(isActive = true, isExecutable = true, now = now) }
                Then("규칙에 맞게 처리한다") {
                    val changed = action()
                    changed.isActive shouldBe true
                    changed.activeAt shouldBe now
                    changed.id shouldBe original.id
                    changed.type shouldBe original.type
                    changed.updatedAt shouldBe now
                }
            }
            When("상태 false에서 true, 실행 어댑터 false") {
                val original = AiFixture.createRandomValidAiProvider(isActive = false, id = RandomFixture.randomId())
                val now = RandomFixture.randomFutureInstantAfterTomorrow()
                val action = { original.changeActive(isActive = true, isExecutable = false, now = now) }
                Then("규칙에 맞게 처리한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_PROVIDER_CLIENT_MISSING.message) { action() }
                }
            }
            When("상태 false에서 false, 실행 어댑터 false") {
                val original = AiFixture.createRandomValidAiProvider(isActive = false, id = RandomFixture.randomId())
                val now = RandomFixture.randomFutureInstantAfterTomorrow()
                val action = { original.changeActive(isActive = false, isExecutable = false, now = now) }
                Then("규칙에 맞게 처리한다") {
                    val changed = action()
                    changed.isActive shouldBe false
                    changed.id shouldBe original.id
                    changed.type shouldBe original.type
                    changed shouldBeSameInstanceAs original
                }
            }
            When("상태 true에서 false, 실행 어댑터 false") {
                val original = AiFixture.createRandomValidAiProvider(isActive = true, id = RandomFixture.randomId())
                val now = RandomFixture.randomFutureInstantAfterTomorrow()
                val action = { original.changeActive(isActive = false, isExecutable = false, now = now) }
                Then("규칙에 맞게 처리한다") {
                    val changed = action()
                    changed.isActive shouldBe false
                    changed.activeAt shouldBe null
                    changed.id shouldBe original.id
                    changed.type shouldBe original.type
                    changed.updatedAt shouldBe now
                }
            }
            When("기존 활성 제공자에 같은 상태를 보내면 어댑터가 없어도 변경하지 않는다") {
                val original = AiFixture.createRandomValidAiProvider(isActive = true, id = RandomFixture.randomId())
                val action = {
                    original.changeActive(isActive = true, isExecutable = false, now = RandomFixture.randomFutureInstantAfterTomorrow())
                }
                Then("원래 상태와 변경 시각을 유지한다") {
                    action() shouldBeSameInstanceAs original
                }
            }
            When("활성 상태 false를 검증하면") {
                val original = AiFixture.createRandomValidAiProvider(isActive = false)
                val action = { original.validateActive() }
                Then("규칙에 맞게 처리한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_PROVIDER_INACTIVE.message) { action() }
                }
            }
            When("활성 상태 true를 검증하면") {
                val original = AiFixture.createRandomValidAiProvider(isActive = true)
                val action = { original.validateActive() }
                Then("규칙에 맞게 처리한다") {
                    shouldNotThrowAny { action() }
                }
            }
        }
    })
