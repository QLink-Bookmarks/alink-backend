package com.qlink.folder.domain

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.search.SearchOrder
import com.qlink.common.search.SortDirection
import com.qlink.support.fixture.RandomFixture
import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.assertions.throwables.shouldThrowWithMessage
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class FolderSortTest :
    BehaviorSpec({
        Given("폴더 정렬 요청을 정할 때") {
            When("기준과 방향이 모두 없으면") {
                val actual = FolderSort.of(orderKey = null, order = null)

                Then("생성일 내림차순이다") {
                    actual.orderKey shouldBe FolderOrderKey.CREATED_AT
                    actual.direction shouldBe SortDirection.DESC
                }
            }

            When("기준만 생성일이면") {
                val actual = FolderSort.of(orderKey = "created_at", order = null)

                Then("생성일 내림차순이다") {
                    actual.orderKey shouldBe FolderOrderKey.CREATED_AT
                    actual.direction shouldBe SortDirection.DESC
                }
            }

            When("기준만 이름이면") {
                val actual = FolderSort.of(orderKey = "name", order = null)

                Then("이름 오름차순이다") {
                    actual.orderKey shouldBe FolderOrderKey.NAME
                    actual.direction shouldBe SortDirection.ASC
                }
            }

            When("기준만 유사도이면") {
                val actual = FolderSort.of(orderKey = "similarity", order = null)

                Then("유사도 내림차순이다") {
                    actual.orderKey shouldBe FolderOrderKey.SIMILARITY
                    actual.direction shouldBe SortDirection.DESC
                }
            }

            When("방향만 오름차순이면") {
                val actual = FolderSort.of(orderKey = null, order = "asc")

                Then("생성일 오름차순이다") {
                    actual.orderKey shouldBe FolderOrderKey.CREATED_AT
                    actual.direction shouldBe SortDirection.ASC
                }
            }

            When("이름 내림차순이면") {
                val actual = FolderSort.of(orderKey = "name", order = "desc")

                Then("이름 내림차순이다") {
                    actual.orderKey shouldBe FolderOrderKey.NAME
                    actual.direction shouldBe SortDirection.DESC
                }
            }

            When("기준·방향을 대소문자를 섞어 보내면") {
                val actual = FolderSort.of(orderKey = RandomFixture.randomCase("name"), order = RandomFixture.randomCase("desc"))

                Then("소문자로 보낸 것과 같은 정렬이다") {
                    actual.orderKey shouldBe FolderOrderKey.NAME
                    actual.direction shouldBe SortDirection.DESC
                }
            }

            When("기준·방향이 빈 문자열이면") {
                val actual = FolderSort.of(orderKey = "", order = " ")

                Then("값이 없을 때와 같은 기본 정렬이다") {
                    actual.orderKey shouldBe FolderOrderKey.CREATED_AT
                    actual.direction shouldBe SortDirection.DESC
                }
            }

            When("기존 값 latest·earliest·laxico·similar를 각각 보내면") {
                val actual = listOf("latest", "earliest", "laxico", "similar").map { FolderSort.of(orderKey = null, order = it) }

                Then("생성일 내림차순·생성일 오름차순·이름 오름차순·유사도 내림차순이다") {
                    actual.map { it.orderKey to it.direction } shouldBe
                        listOf(
                            FolderOrderKey.CREATED_AT to SortDirection.DESC,
                            FolderOrderKey.CREATED_AT to SortDirection.ASC,
                            FolderOrderKey.NAME to SortDirection.ASC,
                            FolderOrderKey.SIMILARITY to SortDirection.DESC,
                        )
                }
            }

            When("기준과 기존 값을 함께 보내면") {
                val of = { FolderSort.of(orderKey = "name", order = "laxico") }

                Then("예외를 반환한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.COMMON_INVALID_SORT_ORDER.message) {
                        of()
                    }
                }
            }

            When("링크 기준(제목)처럼 지원하지 않는 기준이면") {
                val of = { FolderSort.of(orderKey = "title", order = null) }

                Then("예외를 반환한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.COMMON_INVALID_SORT_ORDER.message) {
                        of()
                    }
                }
            }

            When("지원하지 않는 방향 값이면") {
                val of = { FolderSort.of(orderKey = "name", order = "unknown") }

                Then("예외를 반환한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.COMMON_INVALID_SORT_ORDER.message) {
                        of()
                    }
                }
            }

            When("유사도 오름차순이면") {
                val of = { FolderSort.of(orderKey = "similarity", order = "asc") }

                Then("예외를 반환한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.COMMON_INVALID_SORT_ORDER.message) {
                        of()
                    }
                }
            }
        }

        Given("생성일 내림차순 정렬") {
            val sort = FolderSort.of(orderKey = null, order = null)

            When("같은 기준·방향의 커서이면") {
                val validate = { sort.validateCursor(orderKey = FolderOrderKey.CREATED_AT, direction = SortDirection.DESC, order = null) }

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
                val validate = { sort.validateCursor(orderKey = FolderOrderKey.CREATED_AT, direction = SortDirection.ASC, order = null) }

                Then("예외를 반환한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.COMMON_CURSOR_ORDER_MISMATCH.message) {
                        validate()
                    }
                }
            }

            When("기준이 다른 커서이면") {
                val validate = { sort.validateCursor(orderKey = FolderOrderKey.NAME, direction = SortDirection.DESC, order = null) }

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

        Given("이름 오름차순 정렬") {
            val sort = FolderSort.of(orderKey = "name", order = null)

            When("기존 값 LAXICO만 든 커서이면") {
                val validate = { sort.validateCursor(orderKey = null, direction = null, order = SearchOrder.LAXICO) }

                Then("예외 없이 통과한다") {
                    shouldNotThrow<BusinessException> { validate() }
                }
            }
        }

        Given("유사도 내림차순 정렬") {
            val sort = FolderSort.of(orderKey = "similarity", order = null)

            When("기존 값 SIMILAR만 든 커서이면") {
                val validate = { sort.validateCursor(orderKey = null, direction = null, order = SearchOrder.SIMILAR) }

                Then("예외 없이 통과한다") {
                    shouldNotThrow<BusinessException> { validate() }
                }
            }
        }
    })
