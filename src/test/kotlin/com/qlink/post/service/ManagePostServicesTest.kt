package com.qlink.post.service

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.notification.domain.Notification
import com.qlink.notification.domain.NotificationContext
import com.qlink.notification.repository.NotificationRepository
import com.qlink.post.domain.Post
import com.qlink.post.domain.PostImage
import com.qlink.post.domain.PostType
import com.qlink.post.dto.UpdatePostRequest
import com.qlink.post.repository.PostRepository
import com.qlink.support.BaseServiceTest
import com.qlink.support.fixture.PostFixture
import com.qlink.support.fixture.RandomFixture
import com.qlink.support.fixture.UserFixture
import com.qlink.support.koinGet
import com.qlink.user.domain.User
import com.qlink.user.repository.UserRepository
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrowWithMessage
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Clock

class ManagePostServicesTest :
    BaseServiceTest({
        val users = koinGet<UserRepository>()
        val posts = koinGet<PostRepository>()
        val notifications = koinGet<NotificationRepository>()
        val update = koinGet<UpdatePostService>()
        val delete = koinGet<DeletePostService>()
        Given("관리자·일반 사용자와 알림·이미지가 연결된 공지") {
            lateinit var admin: User
            lateinit var normal: User
            lateinit var post: Post
            lateinit var other: Post
            lateinit var request: UpdatePostRequest
            beforeTest {
                if (it.type != io.kotest.core.test.TestType.Test) return@beforeTest
                admin = users.insert(UserFixture.createRandomValidSuperAdmin())
                normal = users.insert(UserFixture.createRandomValidUser())
                post = posts.insert(PostFixture.createRandomPostOf(authorId = normal.id!!))
                other = posts.insert(PostFixture.createRandomPostOf(authorId = admin.id!!))
                posts.insertImages(listOf(PostImage(postId = post.id!!, url = RandomFixture.randomUrl())))
                for (id in listOf(post.id!!, other.id!!)) {
                    notifications.insert(
                        Notification
                            .announce(
                                userId = normal.id!!,
                                postId = id,
                                postTitle = post.title,
                                at = Clock.System.now(),
                            ).markFired(Clock.System.now()),
                    )
                }
                request =
                    UpdatePostRequest(title = RandomFixture.randomSentenceWithMax(100), contents = RandomFixture.randomSentenceWithMax(200))
            }
            When("수정은 식별자·작성자·타입·기존 알림 보존과 재발송 없음") {
                val action = suspend { update.updatePost(loginId = admin.id!!, id = post.id!!, request = request) }
                Then("저장소 상태와 응답을 확인한다") {
                    val before =
                        notifications.findByContext(context = NotificationContext.ANNOUNCE, contextId = post.id!!).map {
                            listOf(it.id, it.contextId, it.title, it.message)
                        }
                    val response = action()
                    val saved = posts.findById(post.id!!)!!
                    saved.id shouldBe post.id
                    saved.authorId shouldBe post.authorId
                    saved.type shouldBe post.type
                    saved.title shouldBe request.title
                    saved.contents shouldBe request.contents
                    saved.updatedAt shouldBe response.updatedAt
                    notifications.findByContext(context = NotificationContext.ANNOUNCE, contextId = post.id!!).map {
                        listOf(it.id, it.contextId, it.title, it.message)
                    } shouldBe
                        before
                    action()
                    posts.findById(post.id!!)!!.title shouldBe request.title
                    notifications.findByContext(context = NotificationContext.ANNOUNCE, contextId = post.id!!).map {
                        listOf(it.id, it.contextId, it.title, it.message)
                    } shouldBe
                        before
                }
            }
            When("삭제는 해당 공지·이미지·인앱 알림만 제거") {
                val action = suspend { delete.deletePost(loginId = admin.id!!, id = post.id!!) }
                Then("저장소 상태와 응답을 확인한다") {
                    shouldNotThrowAny { action() }
                    posts.findById(post.id!!).shouldBeNull()
                    posts.findImagesByPostId(post.id!!) shouldBe emptyList()
                    notifications.findByContext(context = NotificationContext.ANNOUNCE, contextId = post.id!!).map {
                        listOf(it.id, it.contextId, it.title, it.message)
                    } shouldBe
                        emptyList()
                    notifications.findByContext(context = NotificationContext.ANNOUNCE, contextId = other.id!!).size shouldBe 1
                    shouldThrowWithMessage<BusinessException>(ErrorCode.POST_ANNOUNCEMENT_NOT_FOUND.message) { action() }
                }
            }
            When("수정 일반 역할 거절") {
                val action = suspend { update.updatePost(loginId = normal.id!!, id = post.id!!, request = request) }
                Then("저장소 상태와 응답을 확인한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.POST_ANNOUNCEMENT_FORBIDDEN.message) { action() }
                    posts.findById(post.id!!) shouldBe post
                    notifications.findByContext(context = NotificationContext.ANNOUNCE, contextId = other.id!!).size shouldBe 1
                }
            }
            When("수정 사용자 없음 거절") {
                val action = suspend { update.updatePost(loginId = RandomFixture.randomId(), id = post.id!!, request = request) }
                Then("저장소 상태와 응답을 확인한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.POST_EDITOR_NOT_FOUND.message) { action() }
                    posts.findById(post.id!!) shouldBe post
                    notifications.findByContext(context = NotificationContext.ANNOUNCE, contextId = other.id!!).size shouldBe 1
                }
            }
            When("수정 대상 없음 거절") {
                val action = suspend { update.updatePost(loginId = admin.id!!, id = RandomFixture.randomId(), request = request) }
                Then("저장소 상태와 응답을 확인한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.POST_ANNOUNCEMENT_NOT_FOUND.message) { action() }
                    posts.findById(post.id!!) shouldBe post
                    notifications.findByContext(context = NotificationContext.ANNOUNCE, contextId = other.id!!).size shouldBe 1
                }
            }
            When("수정 피드백 거절") {
                beforeTest {
                    if (it.type != io.kotest.core.test.TestType.Test) return@beforeTest
                    post = posts.insert(PostFixture.createRandomPostOf(authorId = normal.id!!, type = PostType.FEEDBACK))
                }
                val action = suspend { update.updatePost(loginId = admin.id!!, id = post.id!!, request = request) }
                Then("저장소 상태와 응답을 확인한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.POST_FEEDBACK_NOT_EDITABLE.message) { action() }
                    posts.findById(post.id!!) shouldBe post
                    notifications.findByContext(context = NotificationContext.ANNOUNCE, contextId = other.id!!).size shouldBe 1
                }
            }
            When("삭제 일반 역할 거절") {
                val action = suspend { delete.deletePost(loginId = normal.id!!, id = post.id!!) }
                Then("저장소 상태와 응답을 확인한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.POST_ANNOUNCEMENT_FORBIDDEN.message) { action() }
                    posts.findById(post.id!!) shouldBe post
                    notifications.findByContext(context = NotificationContext.ANNOUNCE, contextId = other.id!!).size shouldBe 1
                }
            }
            When("삭제 사용자 없음 거절") {
                val action = suspend { delete.deletePost(loginId = RandomFixture.randomId(), id = post.id!!) }
                Then("저장소 상태와 응답을 확인한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.POST_EDITOR_NOT_FOUND.message) { action() }
                    posts.findById(post.id!!) shouldBe post
                    notifications.findByContext(context = NotificationContext.ANNOUNCE, contextId = other.id!!).size shouldBe 1
                }
            }
            When("삭제 대상 없음 거절") {
                val action = suspend { delete.deletePost(loginId = admin.id!!, id = RandomFixture.randomId()) }
                Then("저장소 상태와 응답을 확인한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.POST_ANNOUNCEMENT_NOT_FOUND.message) { action() }
                    posts.findById(post.id!!) shouldBe post
                    notifications.findByContext(context = NotificationContext.ANNOUNCE, contextId = other.id!!).size shouldBe 1
                }
            }
            When("삭제 피드백 거절") {
                beforeTest {
                    if (it.type != io.kotest.core.test.TestType.Test) return@beforeTest
                    post = posts.insert(PostFixture.createRandomPostOf(authorId = normal.id!!, type = PostType.FEEDBACK))
                }
                val action = suspend { delete.deletePost(loginId = admin.id!!, id = post.id!!) }
                Then("저장소 상태와 응답을 확인한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.POST_FEEDBACK_NOT_EDITABLE.message) { action() }
                    posts.findById(post.id!!) shouldBe post
                    notifications.findByContext(context = NotificationContext.ANNOUNCE, contextId = other.id!!).size shouldBe 1
                }
            }
        }
    })
