package com.qlink.auth.domain

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrowWithMessage
import io.kotest.core.spec.style.BehaviorSpec

class AuthAdminPolicyTest :
    BehaviorSpec({
        Given("현재 역할의 관리자 권한") {
            When("관리자 허용") {
                val action = { AuthAdminPolicy.validate(true) }
                Then("규칙에 맞게 처리한다") {
                    shouldNotThrowAny { action() }
                }
            }
            When("일반 역할 거절") {
                val action = { AuthAdminPolicy.validate(false) }
                Then("규칙에 맞게 처리한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AUTH_ADMIN_REQUIRED.message) { action() }
                }
            }
        }
    })
