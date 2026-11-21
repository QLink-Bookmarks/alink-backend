package com.qlink.folder.domain

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.search.SortDirection
import com.qlink.support.fixture.RandomFixture
import io.kotest.assertions.throwables.shouldThrowWithMessage
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class PublicFolderSortTest :
    BehaviorSpec({
        Given("공개 목록의 생성 순서 정렬") {
            When("지원하지 않는 정렬 키를 지정하면") {
                val sort = { PublicFolderSort.from(orderKey = RandomFixture.randomFixedSentence(20), order = null) }

                Then("지원하지 않는 정렬 오류를 반환한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.COMMON_INVALID_SORT_ORDER.message) { sort() }
                }
            }

            When("정렬 키와 별칭을 함께 지정하면") {
                val sort: (
                    String,
                ) -> PublicFolderSort = { PublicFolderSort.from(orderKey = "created_at", order = RandomFixture.randomCase(it)) }

                Then("지원하지 않는 정렬 오류를 반환한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.COMMON_INVALID_SORT_ORDER.message) { sort("latest") }
                    shouldThrowWithMessage<BusinessException>(ErrorCode.COMMON_INVALID_SORT_ORDER.message) { sort("earliest") }
                }
            }

            When("키 없이 latest 또는 earliest를 지정하면") {
                Then("생성 순서 내림차순 또는 오름차순으로 해석한다") {
                    PublicFolderSort.from(orderKey = null, order = RandomFixture.randomCase("latest")).direction shouldBe SortDirection.DESC
                    PublicFolderSort.from(orderKey = null, order = RandomFixture.randomCase("earliest")).direction shouldBe
                        SortDirection.ASC
                }
            }
        }
    })
