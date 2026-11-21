package com.qlink.notification.worker.service

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.device.domain.DevicePlatform
import com.qlink.device.repository.DeviceTokenRepository
import com.qlink.link.repository.LinkRepository
import com.qlink.notification.domain.Notification
import com.qlink.notification.domain.NotificationContext
import com.qlink.notification.repository.NotificationRepository
import com.qlink.push.client.PushNotificationSendRequest
import com.qlink.push.client.PushNotificationSendResult
import com.qlink.push.client.PushNotificationSender
import com.qlink.push.client.PushNotificationSenderRouter
import com.qlink.support.BaseServiceTest
import com.qlink.support.fixture.DeviceTokenFixture
import com.qlink.support.fixture.LinkFixture
import com.qlink.support.fixture.NotificationFixture
import com.qlink.support.fixture.RandomFixture
import com.qlink.support.fixture.TodoFixture
import com.qlink.support.fixture.UserFixture
import com.qlink.support.koinGet
import com.qlink.todo.domain.RepeatDay
import com.qlink.todo.domain.Todo
import com.qlink.todo.repository.TodoRepository
import com.qlink.user.domain.User
import com.qlink.user.repository.UserRepository
import io.kotest.assertions.throwables.shouldThrowWithMessage
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.core.context.unloadKoinModules
import org.koin.dsl.module
import org.slf4j.LoggerFactory
import java.time.ZoneOffset
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaInstant

class SendNotificationServiceRegressionTest :
    BaseServiceTest({
        val notificationRepository = koinGet<NotificationRepository>()
        val userRepository = koinGet<UserRepository>()
        val deviceTokenRepository = koinGet<DeviceTokenRepository>()
        val linkRepository = koinGet<LinkRepository>()
        val todoRepository = koinGet<TodoRepository>()
        val fcmSender = FakePushNotificationSender(DevicePlatform.WEB)
        val expoSender = FakePushNotificationSender(DevicePlatform.NATIVE)
        val originalService = koinGet<SendNotificationService>()
        var missingUserId: Long? = null
        val testModule =
            module {
                factory {
                    SendNotificationService(
                        tx = get(),
                        notificationRepository = get(),
                        userRepository =
                            object : UserRepository by userRepository {
                                override suspend fun findById(userId: Long): User? =
                                    if (userId == missingUserId) null else userRepository.findById(userId)
                            },
                        deviceTokenRepository = get(),
                        senderRouter = PushNotificationSenderRouter(listOf(fcmSender, expoSender)),
                        todoRepository = get(),
                        linkRepository = get(),
                        log = LoggerFactory.getLogger(SendNotificationService::class.java),
                    )
                }
            }
        beforeSpec { loadKoinModules(testModule) }
        afterSpec {
            unloadKoinModules(testModule)
            GlobalContext.get().declare(originalService)
        }

        beforeTest {
            fcmSender.reset()
            expoSender.reset()
            missingUserId = null
        }

        Given("푸시 알림 발송 테스트") {
            lateinit var user: User

            beforeTest {
                user = userRepository.insert(UserFixture.createRandomValidUser())
            }

            When("사용자가 리마인더를 허용하지 않으면") {
                var notificationId = 0L

                beforeTest {
                    user = userRepository.insert(UserFixture.createRandomValidUser(allowsReminder = false))
                    deviceTokenRepository.insert(
                        DeviceTokenFixture.createRandomValidDeviceToken(
                            userId = user.id!!,
                        ),
                    )
                    notificationId = notificationRepository.insert(NotificationFixture.createRandomNotificationOf(user.id!!)).id!!
                }

                Then("외부 발송 없이 실패 완료 상태를 기록한다") {
                    val result = koinGet<SendNotificationService>().send(notificationId)

                    result.successCount shouldBe 0
                    result.failureCount shouldBe 0
                    fcmSender.requests shouldBe emptyList()
                    expoSender.requests shouldBe emptyList()

                    val saved = notificationRepository.findById(notificationId)
                    saved shouldNotBe null
                    saved!!.firedAt shouldBe null
                    saved.failedAt shouldBe null
                    saved.successCount shouldBe 0
                    saved.failureCount shouldBe 0
                }
            }

            When("대기 알림의 수신자가 조회되지 않으면") {
                var notificationId = 0L

                beforeTest {
                    notificationId = notificationRepository.insert(NotificationFixture.createRandomNotificationOf(user.id!!)).id!!
                    missingUserId = user.id
                }

                val send = suspend { koinGet<SendNotificationService>().send(notificationId) }

                Then("USER_NOT_FOUND를 반환하고 외부 발송을 생략한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.USER_NOT_FOUND.message) { send() }

                    fcmSender.requests shouldBe emptyList()
                    expoSender.requests shouldBe emptyList()
                    notificationRepository.findPendingById(notificationId)?.id shouldBe notificationId
                }
            }
        }
    }) {
    private class FakePushNotificationSender(
        override val platform: DevicePlatform,
    ) : PushNotificationSender {
        var beforeSuccess: suspend () -> Unit = {}
        var failTokens: Set<String> = emptySet()
        val requests: MutableList<PushNotificationSendRequest> = mutableListOf()

        override suspend fun send(request: PushNotificationSendRequest): PushNotificationSendResult {
            requests.add(request)

            return if (request.token in failTokens) {
                PushNotificationSendResult.failure(errorMessage = "failed")
            } else {
                beforeSuccess()
                PushNotificationSendResult.success(messageId = "sent-${request.token}")
            }
        }

        fun reset() {
            beforeSuccess = {}
            failTokens = emptySet()
            requests.clear()
        }
    }
}
