package com.qlink.folder.service

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.response.ReadResult
import com.qlink.common.scroll.Base64CursorCodec
import com.qlink.common.scroll.ScrollRequest
import com.qlink.common.search.SortDirection
import com.qlink.folder.domain.Folder
import com.qlink.folder.dto.PublicFolderCursor
import com.qlink.folder.dto.UpdateFolderRequest
import com.qlink.folder.repository.FolderRepository
import com.qlink.link.domain.Link
import com.qlink.link.domain.LinkOrderKey
import com.qlink.link.dto.LinkSearchCursor
import com.qlink.link.dto.LinkSearchCursorValue
import com.qlink.link.dto.PublicFolderLinkResponse
import com.qlink.link.repository.LinkRepository
import com.qlink.link.service.GetLinksService
import com.qlink.support.BaseServiceTest
import com.qlink.support.assertPublicCursorHidesIds
import com.qlink.support.fixture.FolderFixture
import com.qlink.support.fixture.LinkFixture
import com.qlink.support.fixture.PublicFolderFixture
import com.qlink.support.fixture.RandomFixture
import com.qlink.support.fixture.TodoFixture
import com.qlink.support.fixture.UserFixture
import com.qlink.support.fixture.withPublication
import com.qlink.support.getPublicLinks
import com.qlink.support.getPublicTodos
import com.qlink.support.koinGet
import com.qlink.todo.repository.TodoRepository
import com.qlink.todo.service.GetTodosService
import com.qlink.user.repository.UserRepository
import io.kotest.assertions.throwables.shouldThrowWithMessage
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class GetPublicFolderLinksServiceTest :
    BaseServiceTest({
        val service = koinGet<GetLinksService>()
        val todoService = koinGet<GetTodosService>()
        val folders = koinGet<FolderRepository>()
        val links = koinGet<LinkRepository>()
        val todos = koinGet<TodoRepository>()
        val users = koinGet<UserRepository>()
        val codec = koinGet<PublicFolderCursorCodec>()
        val updateFolder = koinGet<UpdateFolderService>()

        Given("활성 공개 폴더에 생성 시각이 같은 링크와 다른 시각의 링크가 있을 때") {
            lateinit var folder: Folder
            lateinit var other: Folder
            lateinit var savedLinks: List<Link>
            var todoId = 0L
            val size = 1

            beforeTest {
                val ownerId = users.insert(UserFixture.createRandomValidUser()).id!!
                folder = folders.insert(FolderFixture.createValidUnsharedFolder(ownerId).withPublication())
                other = folders.insert(FolderFixture.createValidUnsharedFolder(ownerId).withPublication())
                links.insert(LinkFixture.createRandomLinkOf(ownerId = ownerId, folderId = other.id, isPublic = other.isPublished))
                links.insert(LinkFixture.createRandomLinkOf(ownerId = ownerId, folderId = null))
                val createdAt = RandomFixture.randomPastInstant()
                links.insert(LinkFixture.createRandomLinkOf(ownerId = ownerId, folderId = folder.id, createdAt = createdAt))
                savedLinks =
                    listOf(createdAt, createdAt, createdAt - 1.days).map {
                        links.insert(
                            LinkFixture.createRandomLinkOf(
                                ownerId = ownerId,
                                folderId = folder.id,
                                isPublic = folder.isPublished,
                                createdAt = it,
                                memo = RandomFixture.randomSentenceWithMax(50),
                            ),
                        )
                    }
                val attached = savedLinks.first()
                todoId = todos.insert(TodoFixture.createRandomTodoOf(linkId = attached.id!!, ownerId = ownerId)).id!!
                todos.insert(TodoFixture.createRandomTodoOf(linkId = attached.id, ownerId = ownerId))
            }

            When("대상 폴더의 링크를 조회하면") {
                Then("현재 소속 공개 링크의 ID, 제목, 원문 URL만 반환한다") {
                    val result = service.getPublicLinks(folder.id!!, null, null, ScrollRequest())
                    val stored = savedLinks.map { links.findById(it.id!!)!! }

                    result.contents.toSet() shouldBe
                        stored.map { PublicFolderLinkResponse(id = it.id!!, title = it.title, url = it.url) }.toSet()
                    result.contents.forEach { Json.encodeToJsonElement(it).jsonObject.keys shouldBe setOf("id", "title", "url") }
                    result.hasNext shouldBe false
                }
            }

            When("회원과 외부인이 공개 필터로 각각 조회하면") {
                Then("페이지 경계 전에 필터링하고 커서를 해당 권한과 필터에 묶는다") {
                    val folderId = folder.id!!

                    suspend fun read(
                        viewerId: Long?,
                        isPublic: Boolean?,
                        cursor: String? = null,
                        query: String? = null,
                    ) = service.getLinks(
                        viewerId = viewerId,
                        query = query,
                        folderId = folderId,
                        isFavorite = null,
                        orderKey = null,
                        order = null,
                        scrollRequest = ScrollRequest(cursor = cursor, size = size),
                        isPublic = isPublic,
                    )

                    val ownerId = folder.ownerId
                    val ownerPage = (read(viewerId = ownerId, isPublic = true) as ReadResult.Member).data
                    val ownerCursor = ownerPage.nextCursor.shouldNotBeNull()
                    val ownerNext = (read(viewerId = ownerId, isPublic = true, cursor = ownerCursor) as ReadResult.Member).data
                    val publicIds = savedLinks.map { it.id }.toSet()
                    (ownerPage.contents + ownerNext.contents).forEach {
                        (it.id in publicIds) shouldBe true
                        it.turnsPublicAt shouldBe links.findById(it.id)?.turnsPublicAt
                    }
                    val private = (read(viewerId = ownerId, isPublic = false) as ReadResult.Member).data
                    private.hasNext shouldBe false
                    private.contents.single().turnsPublicAt shouldBe null
                    val query = savedLinks.first().title
                    val matching = (read(viewerId = ownerId, isPublic = true, query = query) as ReadResult.Member).data
                    matching.contents.single().id shouldBe savedLinks.first().id
                    val excluded = (read(viewerId = ownerId, isPublic = false, query = query) as ReadResult.Member).data
                    excluded.isEmpty shouldBe true

                    val guest = (read(viewerId = null, isPublic = true) as ReadResult.Guest).data
                    guest.contents.single().id shouldBe ownerPage.contents.single().id
                    val guestCursor = guest.nextCursor.shouldNotBeNull()
                    val guestNext = (read(viewerId = null, isPublic = true, cursor = guestCursor) as ReadResult.Guest).data
                    guestNext.contents.single().id shouldBe ownerNext.contents.single().id
                    val empty = (read(viewerId = null, isPublic = false) as ReadResult.Guest).data
                    empty.contents shouldBe emptyList()
                    empty.isEmpty shouldBe true
                    empty.hasNext shouldBe false
                    empty.nextCursor shouldBe null

                    for (filter in listOf(null, false)) {
                        shouldThrowWithMessage<BusinessException>(ErrorCode.COMMON_CURSOR_MALFORMED.message) {
                            read(viewerId = ownerId, isPublic = filter, cursor = ownerCursor)
                        }
                        shouldThrowWithMessage<BusinessException>(ErrorCode.COMMON_CURSOR_MALFORMED.message) {
                            read(viewerId = null, isPublic = filter, cursor = guestCursor)
                        }
                    }
                    shouldThrowWithMessage<BusinessException>(ErrorCode.COMMON_CURSOR_MALFORMED.message) {
                        read(viewerId = null, isPublic = true, cursor = ownerCursor)
                    }
                    shouldThrowWithMessage<BusinessException>(ErrorCode.COMMON_CURSOR_MALFORMED.message) {
                        read(viewerId = ownerId, isPublic = true, cursor = ownerCursor + RandomFixture.randomId())
                    }
                }
            }

            When("이전 인증 커서가 범위 밖 ID로 생성 시각을 복원하려고 하면") {
                Then("다른 폴더, 다른 소유자, 현재 검색 범위 밖 ID가 모두 없는 행과 같은 오류를 반환한다") {
                    val otherOwner = users.insert(UserFixture.createRandomValidUser()).id!!
                    val otherOwnerLink = links.insert(LinkFixture.createRandomLinkOf(ownerId = otherOwner))
                    val wrongFolderLink = links.findAllByFolderIds(listOf(other.id!!)).single()

                    suspend fun read(
                        id: Long,
                        folderId: Long? = null,
                        isFavorite: Boolean? = null,
                        isPublic: Boolean? = null,
                    ) = service.getLinks(
                        viewerId = folder.ownerId,
                        query = null,
                        folderId = folderId,
                        isFavorite = isFavorite,
                        orderKey = null,
                        order = null,
                        scrollRequest =
                            ScrollRequest(
                                cursor =
                                    Base64CursorCodec.encode(
                                        LinkSearchCursor(
                                            orderKey = LinkOrderKey.CREATED_AT,
                                            direction = SortDirection.DESC,
                                            value = LinkSearchCursorValue(id = id),
                                        ),
                                    ),
                            ),
                        isPublic = isPublic,
                    )

                    shouldThrowWithMessage<BusinessException>(ErrorCode.COMMON_CURSOR_MALFORMED.message) {
                        read(id = otherOwnerLink.id!!)
                    }
                    shouldThrowWithMessage<BusinessException>(ErrorCode.COMMON_CURSOR_MALFORMED.message) {
                        read(id = wrongFolderLink.id!!, folderId = folder.id)
                    }
                    shouldThrowWithMessage<BusinessException>(ErrorCode.COMMON_CURSOR_MALFORMED.message) {
                        read(id = RandomFixture.randomId(), folderId = folder.id)
                    }
                    shouldThrowWithMessage<BusinessException>(ErrorCode.COMMON_CURSOR_MALFORMED.message) {
                        read(id = savedLinks.first().id!!, folderId = folder.id, isFavorite = true)
                    }
                    shouldThrowWithMessage<BusinessException>(ErrorCode.COMMON_CURSOR_MALFORMED.message) {
                        read(id = savedLinks.first().id!!, folderId = folder.id, isPublic = true)
                    }
                }
            }

            When("조회 사이에 링크가 다른 폴더로 이동하고 또 다른 링크가 삭제되면") {
                Then("이동하거나 삭제된 링크가 다음 조회에서 빠진다") {
                    val folderId = folder.id!!
                    service.getPublicLinks(folderId, null, null, ScrollRequest()).contents.size shouldBe savedLinks.size
                    val (moved, deleted, retained) = savedLinks
                    links.assignFolderByIds(linkIds = listOf(moved.id!!), folderId = other.id, now = Clock.System.now())
                    links.deleteById(deleted.id!!)

                    val result = service.getPublicLinks(folderId, null, null, ScrollRequest())

                    links.findById(moved.id)?.folderId shouldBe other.id
                    links.findById(deleted.id) shouldBe null
                    result.contents shouldBe
                        listOf(PublicFolderLinkResponse(id = retained.id!!, title = retained.title, url = retained.url))
                }
            }

            When("철회하기 전에 받은 커서를 같은 주소에서 재사용하면") {
                Then("철회 중에는 404이고 재공개 뒤에도 이전 링크가 노출되지 않는다") {
                    val folderId = folder.id!!
                    val first = service.getPublicLinks(folderId, null, null, ScrollRequest(size = size))
                    val next = ScrollRequest(cursor = first.nextCursor.shouldNotBeNull(), size = size)
                    updateFolder.updateFolder(
                        loginId = folder.ownerId,
                        folderId = folderId,
                        request = UpdateFolderRequest(name = folder.name, isPublic = false),
                    )
                    shouldThrowWithMessage<BusinessException>(ErrorCode.FOLDER_NOT_FOUND.message) {
                        service.getPublicLinks(folderId, null, null, next)
                    }
                    updateFolder.updateFolder(
                        loginId = folder.ownerId,
                        folderId = folderId,
                        request = UpdateFolderRequest(name = folder.name, isPublic = true),
                    )
                    service.getPublicLinks(folderId, null, null, next).contents shouldBe emptyList()
                    links.findAllByFolderIds(listOf(folderId)).none { it.turnsPublicAt != null } shouldBe true
                }
            }

            When("같은 생성 시각의 링크가 페이지 경계에 있으면") {
                Then("정렬 방향마다 생성 시각과 ID 순으로 중복과 누락 없이 이어진다") {
                    val folderId = folder.id!!
                    val stored = savedLinks.map { links.findById(it.id!!)!! }.sortedWith(compareBy({ it.createdAt }, { it.id }))
                    for ((order, expected) in listOf("asc" to stored, "desc" to stored.reversed())) {
                        val first = service.getPublicLinks(folderId, null, order, ScrollRequest(size = size))
                        val second =
                            service.getPublicLinks(
                                folderId,
                                null,
                                order,
                                ScrollRequest(cursor = first.nextCursor.shouldNotBeNull(), size = size),
                            )
                        val third =
                            service.getPublicLinks(
                                folderId,
                                null,
                                order,
                                ScrollRequest(cursor = second.nextCursor.shouldNotBeNull(), size = size),
                            )

                        (first.contents + second.contents + third.contents) shouldBe
                            expected.map { PublicFolderLinkResponse(id = it.id!!, title = it.title, url = it.url) }
                        third.hasNext shouldBe false
                        third.nextCursor shouldBe null
                    }
                }
            }

            When("다른 공개 폴더나 액션 목록의 커서를 링크 목록에 쓰면") {
                val read: suspend (Long, String) -> Unit = { folderId, cursor ->
                    service.getPublicLinks(folderId, null, null, ScrollRequest(cursor = cursor, size = size))
                }

                Then("두 경우 모두 커서를 해석할 수 없다") {
                    val folderId = folder.id!!
                    val linkCursor = service.getPublicLinks(folderId, null, null, ScrollRequest(size = size)).nextCursor.shouldNotBeNull()
                    val todoCursor =
                        todoService
                            .getPublicTodos(
                                folderId,
                                null,
                                null,
                                ScrollRequest(size = size),
                            ).nextCursor
                            .shouldNotBeNull()

                    shouldThrowWithMessage<BusinessException>(
                        ErrorCode.COMMON_CURSOR_MALFORMED.message,
                    ) { read(other.id!!, linkCursor) }
                    shouldThrowWithMessage<BusinessException>(ErrorCode.COMMON_CURSOR_MALFORMED.message) { read(folderId, todoCursor) }
                }
            }

            When("링크 커서에 생성 시각이나 내부 ID가 빠지면") {
                val read: suspend (String) -> Unit = { cursor ->
                    service.getPublicLinks(folder.id!!, null, null, ScrollRequest(cursor = cursor))
                }

                Then("필수 커서 값이 없다고 응답한다") {
                    val first = savedLinks.first()
                    val cursor =
                        PublicFolderCursor(
                            folderId = folder.id!!,
                            collection = "links",
                            direction = SortDirection.DESC,
                            id = first.id,
                            createdAt = first.createdAt,
                        )

                    for (incomplete in listOf(cursor.copy(createdAt = null), cursor.copy(id = null))) {
                        shouldThrowWithMessage<BusinessException>(ErrorCode.COMMON_CURSOR_FIELD_MISSING.message) {
                            read(codec.encode(incomplete))
                        }
                    }
                }
            }

            When("발급된 커서로 다음 링크를 조회하면") {
                Then("저장소의 다음 링크를 읽되 행과 커서에서 내부 ID를 확인할 수 없다") {
                    val folderId = folder.id!!
                    val first = service.getPublicLinks(folderId, null, null, ScrollRequest(size = size))
                    val cursor = first.nextCursor.shouldNotBeNull()
                    val second = service.getPublicLinks(folderId, null, null, ScrollRequest(cursor = cursor, size = size))
                    val expected =
                        savedLinks
                            .map { links.findById(it.id!!)!! }
                            .sortedWith(compareByDescending<Link> { it.createdAt }.thenByDescending { it.id })[size]

                    second.contents shouldBe
                        listOf(PublicFolderLinkResponse(id = expected.id!!, title = expected.title, url = expected.url))
                    Json.encodeToJsonElement(second.contents.single()).jsonObject.keys shouldBe setOf("id", "title", "url")
                    assertPublicCursorHidesIds(cursor = cursor, ids = listOf(folder.id!!, todoId) + savedLinks.map { it.id!! })
                }
            }
        }

        Given("활성 공개 폴더에 링크가 없을 때") {
            lateinit var folder: Folder

            beforeTest {
                val ownerId = users.insert(UserFixture.createRandomValidUser()).id!!
                folder = folders.insert(FolderFixture.createValidUnsharedFolder(ownerId).withPublication())
            }

            When("링크를 조회하면") {
                Then("빈 목록이며 다음 페이지가 없다") {
                    val result = service.getPublicLinks(folder.id!!, null, null, ScrollRequest())

                    result.contents shouldBe emptyList()
                    result.isEmpty shouldBe true
                    result.nextCursor shouldBe null
                    result.hasNext shouldBe false
                }
            }
        }

        Given("접근할 수 없거나 존재하지 않는 폴더 주소") {
            lateinit var folderIds: List<Long>

            beforeTest {
                val ownerId = users.insert(UserFixture.createRandomValidUser()).id!!
                folderIds = PublicFolderFixture.createUnavailableIds(folders = folders, ownerId = ownerId)
            }

            When("유효하지 않은 커서와 함께 링크를 조회하면") {
                val cursor = RandomFixture.randomFixedSentence(20)
                val read: suspend (Long) -> Unit = { service.getPublicLinks(it, null, null, ScrollRequest(cursor = cursor)) }

                Then("커서 검사보다 모든 주소에서 같은 공개 폴더 오류가 우선한다") {
                    for (folderId in folderIds) {
                        shouldThrowWithMessage<BusinessException>(ErrorCode.FOLDER_NOT_FOUND.message) { read(folderId) }
                    }
                }
            }
        }
    })
