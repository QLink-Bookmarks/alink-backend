package com.qlink.notification.service

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.scroll.Base64CursorCodec
import com.qlink.common.scroll.ScrollRequest
import com.qlink.common.scroll.ScrollResponse
import com.qlink.common.search.SearchCursorCodec
import com.qlink.common.search.SearchOrder
import com.qlink.common.search.SortCursor
import com.qlink.common.search.SortDirection
import com.qlink.notification.domain.Notification
import com.qlink.notification.domain.NotificationContext
import com.qlink.notification.domain.NotificationOrderKey
import com.qlink.notification.dto.GetNotificationsContentResponse
import com.qlink.notification.dto.NotificationSearchCursor
import com.qlink.notification.dto.NotificationSearchCursorValue
import com.qlink.notification.repository.NotificationRepository
import com.qlink.notification.repository.table.Notifications
import com.qlink.support.BaseServiceTest
import com.qlink.support.fixture.NotificationFixture
import com.qlink.support.fixture.RandomFixture
import com.qlink.support.fixture.UserFixture
import com.qlink.support.koinGet
import com.qlink.user.domain.User
import com.qlink.user.repository.UserRepository
import io.kotest.assertions.throwables.shouldThrowWithMessage
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.time.toJavaInstant

class GetNotificationsServiceTest :
    BaseServiceTest({
        val service = koinGet<GetNotificationsService>()
        val notificationRepository = koinGet<NotificationRepository>()
        val userRepository = koinGet<UserRepository>()

        Given("알림 목록 조회 테스트") {
            lateinit var user: User
            lateinit var otherUser: User

            beforeTest {
                user = userRepository.insert(UserFixture.createRandomValidUser())
                otherUser = userRepository.insert(UserFixture.createRandomValidUser())
            }

            When("발송 완료된 알림들이 있으면") {
                lateinit var oldNotificationId: Number
                lateinit var newNotificationId: Number

                beforeTest {
                    oldNotificationId =
                        notificationRepository
                            .insert(
                                NotificationFixture.createRandomNotificationOf(
                                    userId = user.id!!,
                                    firedAt = Clock.System.now(),
                                ),
                            ).id!!
                    newNotificationId =
                        notificationRepository
                            .insert(
                                NotificationFixture.createRandomNotificationOf(
                                    userId = user.id!!,
                                    firedAt = Clock.System.now(),
                                ),
                            ).id!!
                    notificationRepository.insert(NotificationFixture.createRandomNotificationOf(userId = user.id!!))
                    notificationRepository.insert(
                        NotificationFixture.createRandomNotificationOf(
                            userId = otherUser.id!!,
                            firedAt = Clock.System.now(),
                        ),
                    )
                }

                Then("로그인 사용자의 발송 완료 알림만 최신순으로 반환한다") {
                    val response =
                        service.getNotifications(
                            loginId = user.id!!,
                            query = null,
                            order = "latest",
                            scrollRequest = ScrollRequest(size = 30),
                        )

                    response.contents.map { it.id } shouldContainExactly
                        listOf(newNotificationId.toLong(), oldNotificationId.toLong())
                    response.hasNext shouldBe false
                }
            }

            When("검색어가 제목 또는 메시지에 포함되면") {
                lateinit var titleMatchedId: Number
                lateinit var messageMatchedId: Number

                beforeTest {
                    val keyword = "needle-${RandomFixture.randomId()}"
                    titleMatchedId =
                        notificationRepository
                            .insert(
                                NotificationFixture.createRandomNotificationOf(
                                    userId = user.id!!,
                                    title = "title $keyword",
                                    message = RandomFixture.randomSentenceWithMax(200),
                                    firedAt = Clock.System.now(),
                                ),
                            ).id!!
                    messageMatchedId =
                        notificationRepository
                            .insert(
                                NotificationFixture.createRandomNotificationOf(
                                    userId = user.id!!,
                                    title = RandomFixture.randomSentenceWithMax(50),
                                    message = "message $keyword",
                                    firedAt = Clock.System.now(),
                                ),
                            ).id!!
                    notificationRepository.insert(
                        NotificationFixture.createRandomNotificationOf(
                            userId = user.id!!,
                            title = RandomFixture.randomSentenceWithMax(50),
                            message = RandomFixture.randomSentenceWithMax(200),
                            firedAt = Clock.System.now(),
                        ),
                    )
                }

                Then("매칭된 알림만 반환한다") {
                    val keyword = "needle"
                    val response =
                        service.getNotifications(
                            loginId = user.id!!,
                            query = keyword,
                            order = "latest",
                            scrollRequest = ScrollRequest(size = 30),
                        )

                    response.contents.map { it.id } shouldContainExactly
                        listOf(messageMatchedId.toLong(), titleMatchedId.toLong())
                }
            }

            When("페이지 크기보다 알림이 많으면") {
                beforeTest {
                    repeat(3) {
                        notificationRepository.insert(
                            NotificationFixture.createRandomNotificationOf(
                                userId = user.id!!,
                                firedAt = Clock.System.now(),
                            ),
                        )
                    }
                }

                Then("커서로 다음 페이지를 조회한다") {
                    val firstPage =
                        service.getNotifications(
                            loginId = user.id!!,
                            query = null,
                            order = "latest",
                            scrollRequest = ScrollRequest(size = 2),
                        )

                    val secondPage =
                        service.getNotifications(
                            loginId = user.id!!,
                            query = null,
                            order = "latest",
                            scrollRequest =
                                ScrollRequest(
                                    cursor = firstPage.nextCursor,
                                    size = 2,
                                ),
                        )

                    firstPage.contents.size shouldBe 2
                    firstPage.hasNext shouldBe true
                    secondPage.contents.size shouldBe 1
                    secondPage.hasNext shouldBe false
                }
            }

            When("로그인 사용자가 없으면") {
                val getNotifications =
                    suspend {
                        service.getNotifications(
                            loginId = RandomFixture.randomId(),
                            query = null,
                            order = "latest",
                            scrollRequest = ScrollRequest(size = 30),
                        )
                    }

                Then("비즈니스 예외를 던진다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.USER_NOT_FOUND.message) {
                        getNotifications()
                    }
                }
            }
        }

        Given("생성 시각이 id 순서와 다르고 발송 순서도 다른 발송된 알림(같은 발송일 포함)") {
            lateinit var user: User
            lateinit var firedIds: List<Long>
            lateinit var todoIds: List<Long>
            lateinit var pendingId: Number
            lateinit var otherUserId: Number

            beforeTest {
                user = userRepository.insert(UserFixture.createRandomValidUser())
                val otherUser = userRepository.insert(UserFixture.createRandomValidUser())
                val base = RandomFixture.randomPastInstant()

                suspend fun insertNotificationAt(
                    userId: Long,
                    createdHours: Int,
                    firedHours: Int?,
                    context: NotificationContext,
                ): Long {
                    val notification =
                        notificationRepository.insert(
                            NotificationFixture.createRandomNotificationOf(
                                userId = userId,
                                context = context,
                                firedAt = firedHours?.let { base + it.hours },
                            ),
                        )
                    updateCreatedAt(notificationId = notification.id!!, createdAt = base + createdHours.hours)
                    return notification.id!!
                }

                val firedLast =
                    insertNotificationAt(userId = user.id!!, createdHours = 2, firedHours = 5, context = NotificationContext.TODO)
                val createdLastFiredFirst =
                    insertNotificationAt(userId = user.id!!, createdHours = 3, firedHours = 1, context = NotificationContext.ANNOUNCE)
                val firedMiddle =
                    insertNotificationAt(userId = user.id!!, createdHours = 1, firedHours = 3, context = NotificationContext.TODO)
                val sameCreatedSameFired =
                    insertNotificationAt(userId = user.id!!, createdHours = 3, firedHours = 3, context = NotificationContext.ANNOUNCE)
                firedIds = listOf(firedLast, createdLastFiredFirst, firedMiddle, sameCreatedSameFired)
                todoIds = listOf(firedLast, firedMiddle)
                pendingId =
                    insertNotificationAt(userId = user.id!!, createdHours = 6, firedHours = null, context = NotificationContext.TODO)
                otherUserId =
                    insertNotificationAt(userId = otherUser.id!!, createdHours = 4, firedHours = 4, context = NotificationContext.TODO)
            }

            suspend fun getNotifications(
                type: String? = null,
                orderKey: String? = null,
                order: String? = null,
                cursor: String? = null,
                size: Int = 30,
            ) = service.getNotifications(
                loginId = user.id!!,
                query = null,
                type = type,
                orderKey = orderKey,
                order = order,
                scrollRequest = ScrollRequest(cursor = cursor, size = size),
            )

            suspend fun readAll(
                orderKey: String?,
                order: String?,
            ): List<ScrollResponse<GetNotificationsContentResponse>> {
                val pages = mutableListOf<ScrollResponse<GetNotificationsContentResponse>>()
                var cursor: String? = null
                do {
                    val page = getNotifications(orderKey = orderKey, order = order, cursor = cursor, size = 2)
                    pages += page
                    cursor = page.nextCursor
                } while (cursor != null)
                return pages
            }

            suspend fun stored(
                ids: List<Long>,
                comparator: Comparator<Notification>,
            ): List<Long> = ids.map { notificationRepository.findById(it).shouldNotBeNull() }.sortedWith(comparator).map { it.id!! }

            val createdAtDesc = compareByDescending<Notification> { it.createdAt }.thenByDescending { it.id }
            val firedAtDesc = compareByDescending<Notification> { it.firedAt }.thenByDescending { it.id }

            When("정렬 없이 조회하면") {
                val get = suspend { getNotifications() to getNotifications(order = "latest") }

                Then("생성 시각과 무관하게 id 내림차순이고 기존 latest와 같은 순서다") {
                    val (actual, legacy) = get()

                    actual.contents.map { it.id } shouldBe firedIds.sortedDescending()
                    actual.contents.map { it.id } shouldNotBe stored(firedIds, createdAtDesc)
                    legacy.contents.map { it.id } shouldBe actual.contents.map { it.id }
                }
            }

            When("생성일 오름차순으로 조회하면") {
                val get = suspend { getNotifications(orderKey = "created_at", order = "asc") }

                Then("생성 시각과 무관하게 id 오름차순이다") {
                    get().contents.map { it.id } shouldBe firedIds.sorted()
                }
            }

            When("type과 함께 생성일 오름차순으로 조회하면") {
                val get = suspend { getNotifications(type = "todo", order = "asc") }

                Then("그 타입의 알림만 생성 시각과 무관하게 id 오름차순으로 나온다") {
                    val actual = get().contents.map { it.id }

                    actual shouldBe todoIds.sorted()
                    actual shouldNotBe stored(todoIds, createdAtDesc).reversed()
                }
            }

            When("발송일로 조회하면") {
                val get = suspend { getNotifications(orderKey = "fired_at") }

                Then("최근 발송된 알림부터 나온다") {
                    val actual = get().contents.map { it.id }

                    actual shouldBe stored(firedIds, firedAtDesc)
                    actual.first() shouldBe firedIds[0]
                }
            }

            When("발송일 오름차순으로 조회하면") {
                val get = suspend { getNotifications(orderKey = "fired_at", order = "asc") }

                Then("먼저 발송된 알림부터 나온다") {
                    val actual = get().contents.map { it.id }

                    actual shouldBe stored(firedIds, firedAtDesc).reversed()
                    actual.first() shouldBe firedIds[1]
                }
            }

            When("발송일과 생성일 순서가 다를 때 정렬 없이 조회하면") {
                val get = suspend { getNotifications() }

                Then("발송일과 무관하게 id 내림차순이다") {
                    val actual = get().contents.map { it.id }

                    actual shouldBe firedIds.sortedDescending()
                    actual shouldNotBe stored(firedIds, firedAtDesc)
                }
            }

            When("발송일 오름차순·내림차순으로 조회하면") {
                val get = suspend { getNotifications(orderKey = "fired_at", order = "asc") to getNotifications(orderKey = "fired_at") }

                Then("같은 발송일끼리는 id가 각각 오름차순·내림차순이다") {
                    val (ascending, descending) = get()
                    val sameFired = listOf(firedIds[2], firedIds[3])

                    ascending.contents.map { it.id }.filter { it in sameFired } shouldBe sameFired.sorted()
                    descending.contents.map { it.id }.filter { it in sameFired } shouldBe sameFired.sortedDescending()
                }
            }

            When("생성일 오름차순으로 다음 커서를 따라 끝까지 조회하면") {
                val get = suspend { readAll(orderKey = "created_at", order = "asc") }

                Then("겹치거나 빠지는 알림이 없고 마지막 페이지는 다음 커서가 없다") {
                    val pages = get()

                    pages.flatMap { page -> page.contents.map { it.id } } shouldBe firedIds.sorted()
                    pages.last().hasNext shouldBe false
                    pages.last().nextCursor shouldBe null
                }
            }

            When("발송일 내림차순으로 다음 커서를 따라 끝까지 조회하면") {
                val get = suspend { readAll(orderKey = "fired_at", order = "desc") }

                Then("페이지 경계에 같은 발송일이 걸려도 겹치거나 빠지는 알림이 없다") {
                    val pages = get()
                    val expected = stored(firedIds, firedAtDesc)

                    pages.flatMap { page -> page.contents.map { it.id } } shouldBe expected
                    pages
                        .first()
                        .contents
                        .last()
                        .id shouldBe expected[1]
                    pages[1].contents.first().id shouldBe expected[2]
                }
            }

            When("기존 형식 id만 든 LATEST 커서로 정렬 없이 조회하면") {
                val get =
                    suspend {
                        val cursorId = firedIds.sortedDescending()[1]
                        val cursor =
                            SearchCursorCodec.encode(
                                order = SearchOrder.LATEST,
                                value = NotificationSearchCursorValue(id = cursorId),
                            )
                        cursorId to getNotifications(cursor = cursor)
                    }

                Then("커서 알림 다음부터 id 내림차순으로 이어진다") {
                    val (cursorId, actual) = get()

                    actual.contents.map { it.id } shouldBe firedIds.sortedDescending().filter { it < cursorId }
                }
            }

            When("생성 시각이 든 생성일 내림차순 커서로 조회하면") {
                val get =
                    suspend {
                        val cursorId = firedIds.sortedDescending()[1]
                        val cursor =
                            Base64CursorCodec.encode(
                                SortCursor(
                                    orderKey = NotificationOrderKey.CREATED_AT,
                                    direction = SortDirection.DESC,
                                    value =
                                        buildJsonObject {
                                            put("id", cursorId)
                                            put("createdAt", RandomFixture.randomPastInstant().toString())
                                        },
                                ),
                            )
                        cursorId to getNotifications(cursor = cursor)
                    }

                Then("생성 시각은 무시하고 커서 알림 다음부터 id 내림차순으로 이어진다") {
                    val (cursorId, actual) = get()

                    actual.contents.map { it.id } shouldBe firedIds.sortedDescending().filter { it < cursorId }
                }
            }

            When("생성일 내림차순 새 커서로 기존 값 latest를 조회하면") {
                val get =
                    suspend {
                        val firstPage = getNotifications(orderKey = "created_at", size = 2)
                        firstPage to getNotifications(order = "latest", cursor = firstPage.nextCursor)
                    }

                Then("커서 알림 다음부터 이어진다") {
                    val (firstPage, secondPage) = get()

                    (firstPage.contents + secondPage.contents).map { it.id } shouldBe firedIds.sortedDescending()
                }
            }

            When("기존 형식 id 커서의 알림이 없으면") {
                val get =
                    suspend {
                        val cursor =
                            SearchCursorCodec.encode(
                                order = SearchOrder.LATEST,
                                value = NotificationSearchCursorValue(id = Long.MAX_VALUE),
                            )
                        getNotifications(cursor = cursor)
                    }

                Then("예외 없이 그 id 다음부터 이어진다") {
                    get().contents.map { it.id } shouldBe firedIds.sortedDescending()
                }
            }

            When("커서에 id가 없으면") {
                val get =
                    suspend {
                        val cursor =
                            Base64CursorCodec.encode(
                                NotificationSearchCursor(
                                    orderKey = NotificationOrderKey.CREATED_AT,
                                    direction = SortDirection.DESC,
                                    value = NotificationSearchCursorValue(type = NotificationContext.TODO),
                                ),
                            )
                        getNotifications(cursor = cursor)
                    }

                Then("예외를 반환한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.COMMON_CURSOR_FIELD_MISSING.message) {
                        get()
                    }
                }
            }

            When("발송일 커서에 발송일 값이 없으면") {
                val get =
                    suspend {
                        val cursor =
                            Base64CursorCodec.encode(
                                NotificationSearchCursor(
                                    orderKey = NotificationOrderKey.FIRED_AT,
                                    direction = SortDirection.DESC,
                                    value = NotificationSearchCursorValue(id = firedIds.first()),
                                ),
                            )
                        getNotifications(orderKey = "fired_at", cursor = cursor)
                    }

                Then("예외를 반환한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.COMMON_CURSOR_FIELD_MISSING.message) {
                        get()
                    }
                }
            }

            When("해석할 수 없는 커서이면") {
                val get = suspend { getNotifications(cursor = "not-a-cursor") }

                Then("예외를 반환한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.COMMON_CURSOR_MALFORMED.message) {
                        get()
                    }
                }
            }

            When("모르는 type 값이면") {
                val get = suspend { getNotifications(type = "unknown") }

                Then("예외를 반환한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.COMMON_INVALID_FILTER.message) {
                        get()
                    }
                }
            }

            When("어떤 방향으로 조회해도") {
                val get =
                    suspend {
                        listOf("created_at", "fired_at").flatMap { orderKey ->
                            listOf("asc", "desc").map { order -> getNotifications(orderKey = orderKey, order = order) }
                        }
                    }

                Then("호출자의 발송된 알림만 나온다") {
                    get().forEach { page ->
                        page.contents.map { it.id }.toSet() shouldBe firedIds.toSet()
                    }
                    notificationRepository.findById(pendingId.toLong()).shouldNotBeNull().firedAt shouldBe null
                    notificationRepository.findById(otherUserId.toLong()).shouldNotBeNull().userId shouldNotBe user.id
                }
            }
        }

        Given("알림이 없는 로그인 사용자") {
            lateinit var user: User

            beforeTest {
                user = userRepository.insert(UserFixture.createRandomValidUser())
            }

            When("생성일 오름차순으로 조회하면") {
                val get =
                    suspend {
                        service.getNotifications(
                            loginId = user.id!!,
                            query = null,
                            order = "asc",
                            scrollRequest = ScrollRequest(),
                        )
                    }

                Then("빈 목록이고 다음 커서가 없다") {
                    val response = get()

                    response.isEmpty shouldBe true
                    response.contents.size shouldBe 0
                    response.nextCursor shouldBe null
                }
            }
        }
    })

private fun updateCreatedAt(
    notificationId: Long,
    createdAt: Instant,
) {
    Notifications.update({ Notifications.id eq notificationId }) { it[Notifications.createdAt] = createdAt.toJavaInstant() }
}
