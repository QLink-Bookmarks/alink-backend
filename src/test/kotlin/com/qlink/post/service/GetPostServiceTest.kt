package com.qlink.post.service

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.post.domain.Post
import com.qlink.post.domain.PostImage
import com.qlink.post.domain.PostType
import com.qlink.post.repository.PostRepository
import com.qlink.support.BaseServiceTest
import com.qlink.support.fixture.PostFixture
import com.qlink.support.fixture.RandomFixture
import com.qlink.support.fixture.UserFixture
import com.qlink.support.koinGet
import com.qlink.support.shouldBeStoredAs
import com.qlink.user.domain.User
import com.qlink.user.repository.UserRepository
import io.kotest.assertions.throwables.shouldThrowWithMessage
import io.kotest.core.test.TestType
import io.kotest.matchers.shouldBe

class GetPostServiceTest :
    BaseServiceTest({
        val service = koinGet<GetPostService>()
        val posts = koinGet<PostRepository>()
        val users = koinGet<UserRepository>()
        Given("현재 관리자와 일반 사용자, 이미지가 있는 피드백과 공지") {
            lateinit var admin: User
            lateinit var author: User
            lateinit var feedback: Post
            lateinit var announcement: Post
            val imageUrl = RandomFixture.randomUrl()
            beforeTest {
                if (it.type != TestType.Test) return@beforeTest
                admin = users.insert(UserFixture.createRandomValidSuperAdmin())
                author = users.insert(UserFixture.createRandomValidUser())
                feedback = posts.insert(PostFixture.createRandomPostOf(authorId = author.id!!, type = PostType.FEEDBACK))
                announcement = posts.insert(PostFixture.createRandomPostOf(authorId = author.id!!))
                posts.insertImages(listOf(PostImage(postId = feedback.id!!, url = imageUrl)))
            }
            When("관리자가 피드백을 반복 조회하면") {
                Then("모든 필드와 이미지를 반환하고 저장된 데이터는 유지한다") {
                    val response = service.getPost(postId = feedback.id!!, loginId = admin.id)
                    response.id shouldBe feedback.id
                    response.title shouldBe feedback.title
                    response.contents shouldBe feedback.contents
                    response.type shouldBe feedback.type
                    response.authorId shouldBe author.id
                    response.author shouldBe author.nickname
                    response.createdAt shouldBe feedback.createdAt
                    response.updatedAt shouldBe feedback.updatedAt
                    response.imageUrls shouldBe listOf(imageUrl)
                    service.getPost(postId = feedback.id!!, loginId = admin.id) shouldBe response
                    posts.findById(feedback.id!!)!!.shouldBeStoredAs(feedback)
                    posts.findImagesByPostId(feedback.id!!).map { it.url } shouldBe listOf(imageUrl)
                }
            }
            When("현재 일반 사용자가 피드백을 조회하면") {
                val action = suspend { service.getPost(postId = feedback.id!!, loginId = author.id) }
                Then("현재 DB 역할로 거절한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.POST_FEEDBACK_FORBIDDEN.message) { action() }
                    posts.findById(feedback.id!!)!!.shouldBeStoredAs(feedback)
                }
            }
            When("게스트가 공지를 조회하면") {
                Then("공개 공지를 반환한다") {
                    val response = service.getPost(postId = announcement.id!!, loginId = null)
                    response.id shouldBe posts.findById(announcement.id!!)!!.id
                    response.imageUrls shouldBe emptyList()
                }
            }
            When("존재하지 않는 게시글을 조회하면") {
                val action = suspend { service.getPost(postId = RandomFixture.randomId(), loginId = admin.id) }
                Then("게시글 없음 오류를 반환한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.POST_NOT_FOUND.message) { action() }
                }
            }
        }
    })
