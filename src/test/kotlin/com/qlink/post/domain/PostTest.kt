package com.qlink.post.domain

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.support.fixture.PostFixture
import com.qlink.support.fixture.RandomFixture
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrowWithMessage
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class PostTest :
    BehaviorSpec({
        Given("저장된 공지") {
            When("제목과 본문 변경은 원본 식별자와 작성자·타입 유지") {
                val original = PostFixture.createRandomPostOf(authorId = RandomFixture.randomId(), id = RandomFixture.randomId())
                val title = RandomFixture.randomSentenceWithMax(100)
                val contents = RandomFixture.randomSentenceWithMax(200)
                val now = RandomFixture.randomFutureInstantAfterTomorrow()
                val action = { original.change(title = title, contents = contents, now = now) }
                Then("규칙에 맞게 처리한다") {
                    val changed = action()
                    changed.id shouldBe original.id
                    changed.authorId shouldBe original.authorId
                    changed.type shouldBe original.type
                    changed.title shouldBe title
                    changed.contents shouldBe contents
                    changed.updatedAt shouldBe now
                }
            }
            When("POST_TITLE_BLANK") {
                val original = PostFixture.createRandomPostOf(authorId = RandomFixture.randomId())
                val action = {
                    original.change(
                        title = " ",
                        contents = RandomFixture.randomSentenceWithMax(100),
                        now = RandomFixture.randomFutureInstantAfterTomorrow(),
                    )
                }
                Then("규칙에 맞게 처리한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.POST_TITLE_BLANK.message) { action() }
                }
            }
            When("POST_TITLE_OVER_MAX") {
                val original = PostFixture.createRandomPostOf(authorId = RandomFixture.randomId())
                val action = {
                    original.change(
                        title = RandomFixture.randomFixedSentence(101),
                        contents = RandomFixture.randomSentenceWithMax(100),
                        now = RandomFixture.randomFutureInstantAfterTomorrow(),
                    )
                }
                Then("규칙에 맞게 처리한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.POST_TITLE_OVER_MAX.message) { action() }
                }
            }
            When("POST_CONTENTS_BLANK") {
                val original = PostFixture.createRandomPostOf(authorId = RandomFixture.randomId())
                val action = {
                    original.change(
                        title = RandomFixture.randomSentenceWithMax(100),
                        contents = " ",
                        now = RandomFixture.randomFutureInstantAfterTomorrow(),
                    )
                }
                Then("규칙에 맞게 처리한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.POST_CONTENTS_BLANK.message) { action() }
                }
            }
            When("피드백 편집 거절") {
                val action = { PostType.FEEDBACK.validateEditable() }
                Then("규칙에 맞게 처리한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.POST_FEEDBACK_NOT_EDITABLE.message) { action() }
                }
            }
            When("공지 편집 허용") {
                val action = { PostType.ANNOUNCEMENT.validateEditable() }
                Then("규칙에 맞게 처리한다") {
                    shouldNotThrowAny { action() }
                }
            }
        }
    })
