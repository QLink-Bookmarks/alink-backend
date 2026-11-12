package com.qlink.auth.domain

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrowWithMessage
import io.kotest.core.spec.style.BehaviorSpec

class RoleTest :
    BehaviorSpec({
        Given("관리자 역할") {
            When("SUPER_ADMIN이 관리자 검증을 받으면") {
                val action = { Role.SUPER_ADMIN.validateAdmin() }
                Then("통과한다") {
                    shouldNotThrowAny { action() }
                }
            }
            When("ADMIN이 관리자 검증을 받으면") {
                val action = { Role.ADMIN.validateAdmin() }
                Then("통과한다") {
                    shouldNotThrowAny { action() }
                }
            }
        }
        Given("관리자가 아닌 역할") {
            When("NORMAL이 관리자 검증을 받으면") {
                val action = { Role.NORMAL.validateAdmin() }
                Then("관리자 권한 필요 예외") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AUTH_ADMIN_REQUIRED.message) { action() }
                }
            }
            When("GUEST가 관리자 검증을 받으면") {
                val action = { Role.GUEST.validateAdmin() }
                Then("관리자 권한 필요 예외") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AUTH_ADMIN_REQUIRED.message) { action() }
                }
            }
        }
    })
