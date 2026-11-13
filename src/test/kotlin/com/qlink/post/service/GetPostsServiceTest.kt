package com.qlink.post.service

import com.qlink.auth.domain.Role
import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.scroll.Base64CursorCodec
import com.qlink.common.scroll.ScrollRequest
import com.qlink.common.scroll.ScrollResponse
import com.qlink.common.search.SearchCursorCodec
import com.qlink.common.search.SearchOrder
import com.qlink.common.search.SortCursor
import com.qlink.common.search.SortDirection
import com.qlink.post.domain.Post
import com.qlink.post.domain.PostOrderKey
import com.qlink.post.domain.PostType
import com.qlink.post.dto.GetPostsContentResponse
import com.qlink.post.dto.PostSearchCursor
import com.qlink.post.dto.PostSearchCursorValue
import com.qlink.post.repository.PostRepository
import com.qlink.post.repository.table.Posts
import com.qlink.support.BaseServiceTest
import com.qlink.support.fixture.PostFixture
import com.qlink.support.fixture.RandomFixture
import com.qlink.support.fixture.UserFixture
import com.qlink.support.koinGet
import com.qlink.user.domain.User
import com.qlink.user.repository.UserRepository
import io.kotest.assertions.throwables.shouldThrowWithMessage
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.time.toJavaInstant

class GetPostsServiceTest :
    BaseServiceTest({
        val service = koinGet<GetPostsService>()
        val postRepository = koinGet<PostRepository>()
        val userRepository = koinGet<UserRepository>()
        lateinit var admin: User
        beforeTest {
            if (it.type != io.kotest.core.test.TestType.Test) return@beforeTest
            admin = userRepository.insert(UserFixture.createRandomValidSuperAdmin())
        }

        suspend fun seed(): User {
            val author = userRepository.insert(UserFixture.createRandomValidUser())
            postRepository.insert(Post(title = "공지 하나", contents = "c", type = PostType.ANNOUNCEMENT, authorId = author.id!!))
            postRepository.insert(Post(title = "공지 둘", contents = "c", type = PostType.ANNOUNCEMENT, authorId = author.id!!))
            postRepository.insert(Post(title = "피드백 하나", contents = "c", type = PostType.FEEDBACK, authorId = author.id!!))
            return author
        }

        Given("게시글 목록 조회 서비스 테스트") {
            When("타입 없이 GUEST 가 조회하면") {
                Then("기본 ANNOUNCEMENT 만, 작성자/제목 포함으로 반환한다") {
                    val author = seed()

                    val response =
                        service.getPosts(
                            loginId = null,
                            type = null,
                            query = null,
                            order = "latest",
                            scrollRequest = ScrollRequest(size = 30),
                        )

                    response.contents.size shouldBe 2
                    response.contents.all { it.author == author.nickname } shouldBe true
                    response.contents.first().title shouldBe "공지 둘"
                }
            }

            When("일반 사용자가 FEEDBACK 목록을 조회하면") {
                lateinit var normal: User
                beforeTest {
                    if (it.type != io.kotest.core.test.TestType.Test) return@beforeTest
                    normal = seed()
                }
                val action =
                    suspend {
                        service.getPosts(
                            loginId = normal.id,
                            type = "feedback",
                            query = null,
                            order = "latest",
                            scrollRequest = ScrollRequest(size = 30),
                        )
                    }
                Then("권한 예외를 반환한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.POST_FEEDBACK_FORBIDDEN.message) { action() }
                }
            }

            When("ADMIN 이 FEEDBACK 을 제목 검색으로 조회하면") {
                Then("해당 피드백만 반환한다") {
                    seed()

                    val response =
                        service.getPosts(
                            loginId = admin.id,
                            type = "FEEDBACK",
                            query = "피드백",
                            order = "latest",
                            scrollRequest = ScrollRequest(size = 30),
                        )

                    response.contents.size shouldBe 1
                    response.contents.first().title shouldBe "피드백 하나"
                }
            }

            When("결과가 페이지 크기보다 많으면") {
                Then("커서로 다음 페이지를 이어서 조회한다") {
                    val author = userRepository.insert(UserFixture.createRandomValidUser())
                    repeat(3) { i ->
                        postRepository.insert(
                            Post(title = "공지 $i", contents = "c", type = PostType.ANNOUNCEMENT, authorId = author.id!!),
                        )
                    }

                    val page1 =
                        service.getPosts(
                            loginId = null,
                            type = null,
                            query = null,
                            order = "latest",
                            scrollRequest = ScrollRequest(size = 2),
                        )
                    page1.hasNext shouldBe true
                    page1.nextCursor shouldNotBe null

                    val page2 =
                        service.getPosts(
                            loginId = null,
                            type = null,
                            query = null,
                            order = "latest",
                            scrollRequest = ScrollRequest(cursor = page1.nextCursor, size = 2),
                        )
                    page2.contents.size shouldBe 1
                    page2.hasNext shouldBe false
                }
            }

            When("지원하지 않는 정렬 기준이면") {
                Then("예외를 반환한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.COMMON_INVALID_SORT_ORDER.message) {
                        service.getPosts(
                            loginId = null,
                            type = null,
                            query = null,
                            order = "earliest",
                            scrollRequest = ScrollRequest(size = 2),
                        )
                    }
                }
            }
        }

        Given("생성 시각이 id 순서와 다른 공지 게시글 여러 개와 피드백 게시글") {
            val keyword = "kw${RandomFixture.randomId()}"
            lateinit var announcementIds: List<Long>
            lateinit var matchedIds: List<Long>
            lateinit var feedbackIds: List<Long>

            beforeEach {
                val author = userRepository.insert(UserFixture.createRandomValidUser())
                val base = RandomFixture.randomPastInstant()

                suspend fun insertPostAt(
                    createdHours: Int,
                    type: PostType = PostType.ANNOUNCEMENT,
                    title: String = RandomFixture.randomSentenceWithMax(50),
                ): Long {
                    val post = postRepository.insert(PostFixture.createRandomPostOf(authorId = author.id!!, type = type, title = title))
                    updateCreatedAt(postId = post.id!!, createdAt = base + createdHours.hours)
                    return post.id!!
                }

                val matchedLater = insertPostAt(createdHours = 2, title = "$keyword 하나")
                val earliest = insertPostAt(createdHours = 0)
                val sameAsMatchedLater = insertPostAt(createdHours = 2)
                val matchedMiddle = insertPostAt(createdHours = 1, title = "$keyword 둘")
                announcementIds = listOf(matchedLater, earliest, sameAsMatchedLater, matchedMiddle)
                matchedIds = listOf(matchedLater, matchedMiddle)
                feedbackIds =
                    listOf(
                        insertPostAt(createdHours = 3, type = PostType.FEEDBACK),
                        insertPostAt(createdHours = 1, type = PostType.FEEDBACK),
                    )
            }

            suspend fun getPosts(
                role: Role = Role.GUEST,
                type: String? = null,
                query: String? = null,
                orderKey: String? = null,
                order: String? = null,
                cursor: String? = null,
                size: Int = 10,
            ) = service.getPosts(
                loginId = if (role.isAdmin) admin.id else null,
                type = type,
                query = query,
                orderKey = orderKey,
                order = order,
                scrollRequest = ScrollRequest(cursor = cursor, size = size),
            )

            suspend fun createdAtDesc(ids: List<Long>): List<Long> =
                ids
                    .map { postRepository.findById(it).shouldNotBeNull() }
                    .sortedWith(compareByDescending<Post> { it.createdAt }.thenByDescending { it.id })
                    .map { it.id!! }

            When("정렬 없이 조회하면") {
                val get = suspend { getPosts() to getPosts(order = "latest") }

                Then("생성 시각과 무관하게 id 내림차순이고 기존 latest와 같은 순서다") {
                    val (actual, legacy) = get()

                    actual.contents.map { it.id } shouldBe announcementIds.sortedDescending()
                    actual.contents.map { it.id } shouldNotBe createdAtDesc(announcementIds)
                    legacy.contents.map { it.id } shouldBe actual.contents.map { it.id }
                }
            }

            When("생성일 오름차순으로 조회하면") {
                val get = suspend { getPosts(orderKey = "created_at", order = "asc") }

                Then("생성 시각과 무관하게 id 오름차순이다") {
                    get().contents.map { it.id } shouldBe announcementIds.sorted()
                }
            }

            When("검색어와 함께 생성일 오름차순으로 조회하면") {
                val get = suspend { getPosts(query = keyword, order = "asc") }

                Then("제목에 검색어가 든 게시글만 id 오름차순으로 나온다") {
                    get().contents.map { it.id } shouldBe matchedIds.sorted()
                }
            }

            When("생성일 오름차순으로 다음 커서를 따라 끝까지 조회하면") {
                val get =
                    suspend {
                        val pages = mutableListOf<ScrollResponse<GetPostsContentResponse>>()
                        var cursor: String? = null
                        do {
                            val page = getPosts(order = "asc", cursor = cursor, size = 3)
                            pages += page
                            cursor = page.nextCursor
                        } while (cursor != null)
                        pages
                    }

                Then("겹치거나 빠지는 게시글이 없고 마지막 페이지는 다음 커서가 없다") {
                    val pages = get()

                    pages shouldHaveSize 2
                    pages.flatMap { page -> page.contents.map { it.id } } shouldBe announcementIds.sorted()
                    pages.last().hasNext shouldBe false
                    pages.last().nextCursor shouldBe null
                }
            }

            When("기존 형식 id만 든 LATEST 커서로 정렬 없이 조회하면") {
                val get =
                    suspend {
                        val cursorId = announcementIds.sortedDescending()[1]
                        val cursor = SearchCursorCodec.encode(order = SearchOrder.LATEST, value = PostSearchCursorValue(id = cursorId))
                        cursorId to getPosts(cursor = cursor)
                    }

                Then("커서 게시글 다음부터 id 내림차순으로 이어진다") {
                    val (cursorId, actual) = get()

                    actual.contents.map { it.id } shouldBe announcementIds.sortedDescending().filter { it < cursorId }
                }
            }

            When("생성 시각이 든 생성일 내림차순 커서로 조회하면") {
                val get =
                    suspend {
                        val cursorId = announcementIds.sortedDescending()[1]
                        val cursor =
                            Base64CursorCodec.encode(
                                SortCursor(
                                    orderKey = PostOrderKey.CREATED_AT,
                                    direction = SortDirection.DESC,
                                    value =
                                        buildJsonObject {
                                            put("id", cursorId)
                                            put("createdAt", RandomFixture.randomPastInstant().toString())
                                        },
                                ),
                            )
                        cursorId to getPosts(cursor = cursor)
                    }

                Then("생성 시각은 무시하고 커서 게시글 다음부터 id 내림차순으로 이어진다") {
                    val (cursorId, actual) = get()

                    actual.contents.map { it.id } shouldBe announcementIds.sortedDescending().filter { it < cursorId }
                }
            }

            When("생성일 내림차순 새 커서로 기존 값 latest를 조회하면") {
                val get =
                    suspend {
                        val firstPage = getPosts(orderKey = "created_at", size = 2)
                        firstPage to getPosts(order = "latest", cursor = firstPage.nextCursor)
                    }

                Then("커서 게시글 다음부터 이어진다") {
                    val (firstPage, secondPage) = get()

                    (firstPage.contents + secondPage.contents).map { it.id } shouldBe announcementIds.sortedDescending()
                }
            }

            When("기존 형식 id 커서의 게시글이 없으면") {
                val get =
                    suspend {
                        getPosts(
                            cursor =
                                SearchCursorCodec.encode(
                                    order = SearchOrder.LATEST,
                                    value = PostSearchCursorValue(id = Long.MAX_VALUE),
                                ),
                        )
                    }

                Then("예외 없이 그 id 다음부터 이어진다") {
                    get().contents.map { it.id } shouldBe announcementIds.sortedDescending()
                }
            }

            When("커서에 id가 없으면") {
                val get =
                    suspend {
                        getPosts(
                            cursor =
                                Base64CursorCodec.encode(
                                    PostSearchCursor(
                                        orderKey = PostOrderKey.CREATED_AT,
                                        direction = SortDirection.DESC,
                                        value = PostSearchCursorValue(title = RandomFixture.randomSentenceWithMax(50)),
                                    ),
                                ),
                        )
                    }

                Then("예외를 반환한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.COMMON_CURSOR_FIELD_MISSING.message) {
                        get()
                    }
                }
            }

            When("해석할 수 없는 커서이면") {
                val get = suspend { getPosts(cursor = "not-a-cursor") }

                Then("예외를 반환한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.COMMON_CURSOR_MALFORMED.message) {
                        get()
                    }
                }
            }

            When("비로그인으로 생성일 오름차순 조회하면") {
                val get = suspend { getPosts(role = Role.GUEST, order = "asc") }

                Then("공지만 나온다") {
                    get().contents.map { it.id }.toSet() shouldBe announcementIds.toSet()
                }
            }

            When("현재 관리자가 피드백을 기본 순서로 조회하면") {
                val get = suspend { getPosts(role = Role.ADMIN, type = "FEEDBACK") }

                Then("피드백만 최신 id 순서로 반환한다") {
                    val actual = get()
                    actual.contents.map { it.id } shouldBe feedbackIds.sortedDescending()
                    actual.contents.map { it.id }.forEach { postRepository.findById(it)!!.type shouldBe PostType.FEEDBACK }
                }
            }

            When("관리자가 피드백을 생성일 오름차순으로 조회하면") {
                val get = suspend { getPosts(role = Role.ADMIN, type = "FEEDBACK", order = "asc") }

                Then("피드백만 id 오름차순으로 나온다") {
                    get().contents.map { it.id } shouldBe feedbackIds.sorted()
                }
            }
        }

        Given("게시글이 없을 때") {
            When("생성일 오름차순으로 조회하면") {
                val get =
                    suspend {
                        service.getPosts(
                            loginId = null,
                            type = null,
                            query = null,
                            order = "asc",
                            scrollRequest = ScrollRequest(),
                        )
                    }

                Then("빈 목록이고 다음 커서가 없다") {
                    val response = get()

                    response.isEmpty shouldBe true
                    response.contents shouldHaveSize 0
                    response.nextCursor shouldBe null
                }
            }
        }
    })

private fun updateCreatedAt(
    postId: Long,
    createdAt: Instant,
) {
    Posts.update({ Posts.id eq postId }) { it[Posts.createdAt] = createdAt.toJavaInstant() }
}
