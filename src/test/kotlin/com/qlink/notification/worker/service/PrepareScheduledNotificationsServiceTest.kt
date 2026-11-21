package com.qlink.notification.worker.service

import com.qlink.link.repository.LinkRepository
import com.qlink.notification.domain.NotificationContext
import com.qlink.notification.repository.NotificationRepository
import com.qlink.support.BaseServiceTest
import com.qlink.support.fixture.LinkFixture
import com.qlink.support.fixture.NotificationFixture
import com.qlink.support.fixture.TodoFixture
import com.qlink.support.fixture.UserFixture
import com.qlink.support.koinGet
import com.qlink.todo.domain.Todo
import com.qlink.todo.repository.TodoRepository
import com.qlink.user.repository.UserRepository
import io.kotest.core.test.TestType
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinInstant

class PrepareScheduledNotificationsServiceTest :
    BaseServiceTest({
        val service = koinGet<PrepareScheduledNotificationsService>()
        val userRepository = koinGet<UserRepository>()
        val linkRepository = koinGet<LinkRepository>()
        val todoRepository = koinGet<TodoRepository>()
        val notificationRepository = koinGet<NotificationRepository>()

        Given("UTC 날짜 경계 양쪽에 예약된 액션") {
            val date = LocalDate.now(ZoneOffset.UTC)
            val start = date.atStartOfDay(ZoneOffset.UTC).toInstant().toKotlinInstant()
            val end =
                date
                    .plusDays(1)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant()
                    .toKotlinInstant()
            lateinit var todos: List<Todo>

            beforeTest {
                if (it.type != TestType.Test) return@beforeTest
                val user = userRepository.insert(UserFixture.createRandomValidUser())
                val link = linkRepository.insert(LinkFixture.createRandomLinkOf(ownerId = user.id!!))
                todos =
                    listOf(start - 1.seconds, start, end - 1.seconds, end).map { reminderAt ->
                        todoRepository.insert(
                            TodoFixture.createRandomTodoOf(linkId = link.id!!, ownerId = user.id, reminderAt = reminderAt),
                        )
                    }
            }

            suspend fun notificationsOf(todo: Todo) =
                notificationRepository.findByContext(context = NotificationContext.TODO, contextId = todo.id!!)

            When("하루를 준비하면") {
                var existingId: Long? = null

                beforeTest {
                    if (it.type != TestType.Test) return@beforeTest
                    existingId = notificationRepository.insert(NotificationFixture.createTodoNotificationOf(todos[2])).id
                }

                Then("UTC 시작을 포함하고 다음 날 시작을 제외한 대기 알림을 조회한다") {
                    val prepared = service.prepare(startInclusive = start, endExclusive = end).filter { it.userId == todos.first().ownerId }
                    val saved = todos.map { notificationsOf(it) }

                    saved.map { it.size } shouldBe listOf(0, 1, 1, 0)
                    saved[2].single().id shouldBe existingId
                    prepared.map { it.id }.toSet() shouldBe saved.flatten().map { it.id }.toSet()
                    saved.flatten().map { it.willFireAt } shouldBe listOf(start, end - 1.seconds)
                }
            }

            When("같은 날짜를 다시 준비하면") {
                beforeTest {
                    if (it.type != TestType.Test) return@beforeTest
                    service.prepare(startInclusive = start, endExclusive = end)
                }

                Then("액션과 예약 시각마다 한 건만 저장된다") {
                    val prepared = service.prepare(startInclusive = start, endExclusive = end).filter { it.userId == todos.first().ownerId }
                    val inRange = todos.subList(1, 3)
                    val saved = inRange.map { notificationsOf(it).single() }

                    saved.map { it.willFireAt } shouldBe inRange.map { it.reminderAt }
                    prepared.map { it.id }.toSet() shouldBe saved.map { it.id }.toSet()
                }
            }
        }
    })
