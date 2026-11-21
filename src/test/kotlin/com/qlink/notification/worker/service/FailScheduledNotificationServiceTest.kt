package com.qlink.notification.worker.service

import com.qlink.common.error.BusinessException
import com.qlink.notification.repository.NotificationRepository
import com.qlink.support.BaseServiceTest
import com.qlink.support.fixture.NotificationFixture
import com.qlink.support.fixture.RandomFixture
import com.qlink.support.fixture.UserFixture
import com.qlink.support.koinGet
import com.qlink.user.repository.UserRepository
import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.core.test.TestType
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class FailScheduledNotificationServiceTest :
    BaseServiceTest({
        val service = koinGet<FailScheduledNotificationService>()
        val notificationRepository = koinGet<NotificationRepository>()
        val userRepository = koinGet<UserRepository>()

        Given("발송 예외가 발생한 알림") {
            var userId = 0L
            var notificationId = 0L

            beforeTest {
                if (it.type != TestType.Test) return@beforeTest
                userId = userRepository.insert(UserFixture.createRandomValidUser()).id!!
            }

            When("대상 알림이 대기 중이면") {
                beforeTest {
                    if (it.type != TestType.Test) return@beforeTest
                    notificationId = notificationRepository.insert(NotificationFixture.createRandomNotificationOf(userId = userId)).id!!
                }

                val action = suspend { service.fail(notificationId) }

                Then("알림이 실패 상태로 저장된다") {
                    shouldNotThrow<BusinessException> { action() }

                    val saved = notificationRepository.findById(notificationId)!!
                    saved.isPending shouldBe false
                    saved.failedAt shouldNotBe null
                    saved.failureCount shouldBe 1
                }
            }

            When("대상 알림의 발송이 이미 끝났으면") {
                beforeTest {
                    if (it.type != TestType.Test) return@beforeTest
                    val firedAt = RandomFixture.randomPastInstant()
                    val notification = NotificationFixture.createRandomNotificationOf(userId = userId, firedAt = firedAt)
                    notificationId = notificationRepository.insert(notification).id!!
                }

                val action = suspend { service.fail(notificationId) }

                Then("기존 처리 결과가 보존된다") {
                    val firedAt = notificationRepository.findById(notificationId)!!.firedAt
                    shouldNotThrow<BusinessException> { action() }

                    val saved = notificationRepository.findById(notificationId)!!
                    saved.firedAt shouldBe firedAt
                    saved.failedAt shouldBe null
                    saved.failureCount shouldBe 0
                }
            }
        }
    })
