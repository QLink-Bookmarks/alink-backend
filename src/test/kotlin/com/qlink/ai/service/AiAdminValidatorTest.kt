package com.qlink.ai.service

import com.qlink.auth.domain.Role
import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.support.BaseServiceTest
import com.qlink.support.fixture.RandomFixture
import com.qlink.support.fixture.UserFixture
import com.qlink.support.fixture.copyForTestRole
import com.qlink.support.koinGet
import com.qlink.user.domain.User
import com.qlink.user.repository.UserRepository
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrowWithMessage

class AiAdminValidatorTest :
    BaseServiceTest({
        val users = koinGet<UserRepository>()
        val validator = koinGet<AiAdminValidator>()
        Given("현재 DB 역할이 저장된 사용자") {
            lateinit var superAdmin: User
            lateinit var admin: User
            lateinit var normal: User
            beforeTest {
                if (it.type != io.kotest.core.test.TestType.Test) return@beforeTest
                superAdmin = users.insert(UserFixture.createRandomValidSuperAdmin())
                admin = users.insert(UserFixture.createRandomValidUser().copyForTestRole(Role.ADMIN))
                normal = users.insert(UserFixture.createRandomValidUser())
            }
            When("SUPER_ADMIN을 검증하면") {
                val action = suspend { validator.validate(superAdmin.id) }
                Then("통과한다") {
                    shouldNotThrowAny { action() }
                }
            }
            When("ADMIN을 검증하면") {
                val action = suspend { validator.validate(admin.id) }
                Then("통과한다") {
                    shouldNotThrowAny { action() }
                }
            }
            When("일반 역할을 검증하면") {
                val action = suspend { validator.validate(normal.id) }
                Then("관리자 권한 필요 예외") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_ADMIN_REQUIRED.message) { action() }
                }
            }
            When("없는 사용자를 검증하면") {
                val action = suspend { validator.validate(RandomFixture.randomId()) }
                Then("관리자 사용자 없음 예외") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_ADMIN_USER_NOT_FOUND.message) { action() }
                }
            }
            When("로그인하지 않은 요청을 검증하면") {
                val action = suspend { validator.validate(null) }
                Then("관리자 사용자 없음 예외") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.AI_ADMIN_USER_NOT_FOUND.message) { action() }
                }
            }
        }
    })
