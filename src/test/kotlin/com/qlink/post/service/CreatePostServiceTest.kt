package com.qlink.post.service

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.notification.domain.NotificationContext
import com.qlink.notification.repository.NotificationRepository
import com.qlink.post.domain.PostSort
import com.qlink.post.domain.PostType
import com.qlink.post.dto.CreatePostRequest
import com.qlink.post.repository.PostRepository
import com.qlink.support.BaseServiceTest
import com.qlink.support.fixture.RandomFixture
import com.qlink.support.fixture.UserFixture
import com.qlink.support.koinGet
import com.qlink.user.domain.User
import com.qlink.user.repository.UserRepository
import io.kotest.assertions.throwables.shouldThrowWithMessage
import io.kotest.core.test.TestType
import io.kotest.matchers.shouldBe

class CreatePostServiceTest :
    BaseServiceTest({
        val service = koinGet<CreatePostService>()
        val users = koinGet<UserRepository>()
        val posts = koinGet<PostRepository>()
        val notifications = koinGet<NotificationRepository>()

        Given("DB에 저장된 관리자와 일반 사용자") {
            lateinit var admin: User
            lateinit var normal: User
            lateinit var request: CreatePostRequest
            beforeTest {
                if (it.type != TestType.Test) return@beforeTest
                admin = users.insert(UserFixture.createRandomValidSuperAdmin())
                normal = users.insert(UserFixture.createRandomValidUser())
                request =
                    CreatePostRequest(
                        title = RandomFixture.randomSentenceWithMax(100),
                        contents = RandomFixture.randomSentenceWithMax(200),
                        type = "ANNOUNCEMENT",
                        imageUrls = listOf(RandomFixture.randomUrl()),
                    )
            }
            When("현재 관리자가 공지를 생성하면") {
                Then("공지를 저장하고 모든 사용자에게 알림을 한 번씩 저장한다") {
                    val response = service.createPost(loginId = admin.id!!, request = request)
                    val saved = posts.findById(response.id)!!
                    saved.title shouldBe request.title
                    saved.contents shouldBe request.contents
                    saved.authorId shouldBe admin.id
                    posts.findImagesByPostId(response.id) shouldBe emptyList()
                    notifications
                        .findByContext(context = NotificationContext.ANNOUNCE, contextId = response.id)
                        .groupingBy { it.userId }
                        .eachCount() shouldBe users.findAllIds().associateWith { 1 }
                }
            }
            When("현재 일반 사용자가 공지를 생성하면") {
                val action = suspend { service.createPost(loginId = normal.id!!, request = request) }
                Then("권한 오류이며 공지와 알림이 생기지 않는다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.POST_ANNOUNCEMENT_FORBIDDEN.message) { action() }
                    posts.search(
                        type = PostType.ANNOUNCEMENT,
                        query = null,
                        sort = PostSort.of(orderKey = null, order = null),
                        cursor = null,
                        size = Int.MAX_VALUE - 1,
                    ) shouldBe emptyList()
                    users.findAllIds().forEach { notifications.countUnread(it) shouldBe 0 }
                }
            }
            When("일반 사용자가 피드백을 생성하면") {
                Then("이미지가 저장되고 공지 알림은 생기지 않는다") {
                    val response = service.createPost(loginId = normal.id!!, request = request.copy(type = "FEEDBACK"))
                    posts.findById(response.id)!!.type shouldBe PostType.FEEDBACK
                    posts.findImagesByPostId(response.id).map { it.url } shouldBe request.imageUrls
                    notifications.findByContext(context = NotificationContext.ANNOUNCE, contextId = response.id) shouldBe emptyList()
                }
            }
        }
    })
