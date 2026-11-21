package com.qlink.notification.worker.service

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.notification.repository.NotificationRepository
import com.qlink.support.BaseServiceTest
import com.qlink.support.fixture.NotificationFixture
import com.qlink.support.fixture.UserFixture
import com.qlink.support.koinGet
import com.qlink.user.repository.UserRepository
import io.kotest.assertions.throwables.shouldThrowWithMessage
import io.kotest.core.test.TestType
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class MarkNotificationScheduledServiceTest :
    BaseServiceTest({
        val service = koinGet<MarkNotificationScheduledService>()
        val notificationRepository = koinGet<NotificationRepository>()
        val userRepository = koinGet<UserRepository>()

        Given("단건 알림 예약") {
            var notificationId = 0L

            beforeTest {
                if (it.type != TestType.Test) return@beforeTest
                val user = userRepository.insert(UserFixture.createRandomValidUser())
                notificationId = notificationRepository.insert(NotificationFixture.createRandomNotificationOf(userId = user.id!!)).id!!
            }

            When("대기 알림을 예약하면") {
                Then("예약 시각이 저장된다") {
                    val scheduled = service.markScheduled(notificationId)
                    val saved = notificationRepository.findPendingById(notificationId)!!

                    saved.scheduledAt shouldNotBe null
                    saved.scheduledAt shouldBe scheduled.scheduledAt
                }
            }

            When("알림이 이미 발송되었으면") {
                beforeTest {
                    if (it.type != TestType.Test) return@beforeTest
                    val notification = notificationRepository.findById(notificationId)!!
                    notificationRepository.update(notification.markFired(notification.willFireAt))
                }

                val action = suspend { service.markScheduled(notificationId) }

                Then("NOTIFICATION_NOT_FOUND가 발생한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.NOTIFICATION_NOT_FOUND.message) { action() }
                    notificationRepository.findById(notificationId)!!.scheduledAt shouldBe null
                }
            }
        }
    })
