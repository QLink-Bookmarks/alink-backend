package com.qlink.post.domain

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.search.SearchOrder
import com.qlink.common.search.SortDirection
import com.qlink.support.fixture.RandomFixture
import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.assertions.throwables.shouldThrowWithMessage
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class PostSortTest :
    BehaviorSpec({
        Given("게시글 정렬 요청을 정할 때") {
            When("기준과 방향이 모두 없으면") {
                val actual = PostSort.of(orderKey = null, order = null)

                Then("생성일 내림차순이다") {
                    actual.orderKey shouldBe PostOrderKey.CREATED_AT
                    actual.direction shouldBe SortDirection.DESC
                }
            }

            When("기준만 생성일이면") {
                val actual = PostSort.of(orderKey = "created_at", order = null)

                Then("생성일 내림차순이다") {
                    actual.orderKey shouldBe PostOrderKey.CREATED_AT
                    actual.direction shouldBe SortDirection.DESC
                }
            }

            When("방향만 오름차순이면") {
                val actual = PostSort.of(orderKey = null, order = "asc")

                Then("생성일 오름차순이다") {
                    actual.orderKey shouldBe PostOrderKey.CREATED_AT
                    actual.direction shouldBe SortDirection.ASC
                }
            }

            When("생성일 오름차순이면") {
                val actual = PostSort.of(orderKey = "created_at", order = "asc")

                Then("생성일 오름차순이다") {
                    actual.orderKey shouldBe PostOrderKey.CREATED_AT
                    actual.direction shouldBe SortDirection.ASC
                }
            }

            When("기준·방향을 대소문자를 섞어 보내면") {
                val actual = PostSort.of(orderKey = RandomFixture.randomCase("created_at"), order = RandomFixture.randomCase("asc"))

                Then("소문자로 보낸 것과 같은 정렬이다") {
                    actual.orderKey shouldBe PostOrderKey.CREATED_AT
                    actual.direction shouldBe SortDirection.ASC
                }
            }

            When("기준·방향이 빈 문자열이면") {
                val actual = PostSort.of(orderKey = "", order = " ")

                Then("값이 없을 때와 같은 기본 정렬이다") {
                    actual.orderKey shouldBe PostOrderKey.CREATED_AT
                    actual.direction shouldBe SortDirection.DESC
                }
            }

            When("기존 값 latest이면") {
                val actual = PostSort.of(orderKey = null, order = "latest")

                Then("생성일 내림차순이다") {
                    actual.orderKey shouldBe PostOrderKey.CREATED_AT
                    actual.direction shouldBe SortDirection.DESC
                }
            }

            When("기존 값 earliest·laxico·similar이면") {
                val ofs = listOf("earliest", "laxico", "similar").map { { PostSort.of(orderKey = null, order = it) } }

                Then("예외를 반환한다") {
                    ofs.forEach { of ->
                        shouldThrowWithMessage<BusinessException>(ErrorCode.COMMON_INVALID_SORT_ORDER.message) {
                            of()
                        }
                    }
                }
            }

            When("기준과 기존 값을 함께 보내면") {
                val of = { PostSort.of(orderKey = "created_at", order = "latest") }

                Then("예외를 반환한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.COMMON_INVALID_SORT_ORDER.message) {
                        of()
                    }
                }
            }

            When("제목·이름처럼 지원하지 않는 기준이면") {
                val ofs = listOf("title", "name").map { { PostSort.of(orderKey = it, order = null) } }

                Then("예외를 반환한다") {
                    ofs.forEach { of ->
                        shouldThrowWithMessage<BusinessException>(ErrorCode.COMMON_INVALID_SORT_ORDER.message) {
                            of()
                        }
                    }
                }
            }

            When("지원하지 않는 방향 값이면") {
                val of = { PostSort.of(orderKey = "created_at", order = "unknown") }

                Then("예외를 반환한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.COMMON_INVALID_SORT_ORDER.message) {
                        of()
                    }
                }
            }
        }

        Given("생성일 내림차순 정렬") {
            val sort = PostSort.of(orderKey = null, order = null)

            When("같은 기준·방향의 커서이면") {
                val validate = { sort.validateCursor(orderKey = PostOrderKey.CREATED_AT, direction = SortDirection.DESC, order = null) }

                Then("예외 없이 통과한다") {
                    shouldNotThrow<BusinessException> { validate() }
                }
            }

            When("기존 값 LATEST만 든 커서이면") {
                val validate = { sort.validateCursor(orderKey = null, direction = null, order = SearchOrder.LATEST) }

                Then("예외 없이 통과한다") {
                    shouldNotThrow<BusinessException> { validate() }
                }
            }

            When("방향이 다른 커서이면") {
                val validate = { sort.validateCursor(orderKey = PostOrderKey.CREATED_AT, direction = SortDirection.ASC, order = null) }

                Then("예외를 반환한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.COMMON_CURSOR_ORDER_MISMATCH.message) {
                        validate()
                    }
                }
            }

            When("기존 값 EARLIEST만 든 커서이면") {
                val validate = { sort.validateCursor(orderKey = null, direction = null, order = SearchOrder.EARLIEST) }

                Then("예외를 반환한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.COMMON_CURSOR_ORDER_MISMATCH.message) {
                        validate()
                    }
                }
            }

            When("기준·방향·기존 값이 모두 없는 커서이면") {
                val validate = { sort.validateCursor(orderKey = null, direction = null, order = null) }

                Then("예외를 반환한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.COMMON_CURSOR_ORDER_MISMATCH.message) {
                        validate()
                    }
                }
            }
        }
    })
