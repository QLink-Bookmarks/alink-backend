package com.qlink.folder.service

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.scroll.DEFAULT_SCROLL_SIZE
import com.qlink.common.scroll.ScrollRequest
import com.qlink.common.search.SortDirection
import com.qlink.folder.domain.Folder
import com.qlink.folder.dto.PublicFolderCursor
import com.qlink.folder.repository.FolderRepository
import com.qlink.link.domain.Link
import com.qlink.link.repository.LinkRepository
import com.qlink.support.BaseServiceTest
import com.qlink.support.assertPublicCursorHidesIds
import com.qlink.support.fixture.FolderFixture
import com.qlink.support.fixture.LinkFixture
import com.qlink.support.fixture.PublicFolderFixture
import com.qlink.support.fixture.RandomFixture
import com.qlink.support.fixture.TodoFixture
import com.qlink.support.fixture.UserFixture
import com.qlink.support.getPublicTodos
import com.qlink.support.koinGet
import com.qlink.todo.domain.Todo
import com.qlink.todo.dto.PublicFolderTodoResponse
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

class GetPublicFolderTodosServiceTest :
    BaseServiceTest({
        val service = koinGet<GetTodosService>()
        val folders = koinGet<FolderRepository>()
        val links = koinGet<LinkRepository>()
        val todos = koinGet<TodoRepository>()
        val users = koinGet<UserRepository>()
        val codec = koinGet<PublicFolderCursorCodec>()

        Given("공개 폴더의 링크에 완료 및 미완료 액션이 연결되어 있을 때") {
            lateinit var folder: Folder
            lateinit var other: Folder
            lateinit var movedLink: Link
            lateinit var deletedLink: Link
            lateinit var completed: Todo
            lateinit var incomplete: Todo

            beforeTest {
                val ownerId = users.insert(UserFixture.createRandomValidUser()).id!!
                folder = folders.insert(FolderFixture.createValidUnsharedFolder(ownerId).setPublic(true))
                other = folders.insert(FolderFixture.createValidUnsharedFolder(ownerId))
                movedLink =
                    links.insert(LinkFixture.createRandomLinkOf(ownerId = ownerId, folderId = folder.id, isPublic = folder.isPublished))
                deletedLink =
                    links.insert(LinkFixture.createRandomLinkOf(ownerId = ownerId, folderId = folder.id, isPublic = folder.isPublished))
                val otherLink = links.insert(LinkFixture.createRandomLinkOf(ownerId = ownerId, folderId = other.id))
                val privateLink = links.insert(LinkFixture.createRandomLinkOf(ownerId = ownerId, folderId = folder.id))
                completed = todos.insert(TodoFixture.createOverdueCompletedTodoOf(linkId = movedLink.id!!, ownerId = ownerId))
                incomplete = todos.insert(TodoFixture.createRandomTodoOf(linkId = deletedLink.id!!, ownerId = ownerId))
                todos.insert(TodoFixture.createRandomTodoOf(linkId = otherLink.id!!, ownerId = ownerId))
                todos.insert(TodoFixture.createRandomTodoOf(linkId = privateLink.id!!, ownerId = ownerId))
            }

            When("액션을 조회하면") {
                Then("현재 폴더 링크의 액션 제목과 완료 여부만 반환한다") {
                    val result = service.getPublicTodos(folder.id!!, null, null, ScrollRequest())
                    val stored = listOf(completed, incomplete).map { todos.findById(it.id!!)!! }

                    result.contents.toSet() shouldBe
                        stored
                            .map {
                                PublicFolderTodoResponse(
                                    linkId = it.linkId,
                                    title = it.title,
                                    isCompleted = it.completedAt != null,
                                )
                            }.toSet()
                    result.contents.forEach {
                        Json
                            .encodeToJsonElement(
                                it,
                            ).jsonObject.keys shouldBe setOf("linkId", "title", "isCompleted")
                    }
                }
            }

            When("조회 사이에 링크가 이동하거나 삭제되면") {
                Then("이동하거나 삭제된 링크에 연결된 액션이 다음 조회에서 빠진다") {
                    val folderId = folder.id!!
                    service.getPublicTodos(folderId, null, null, ScrollRequest()).contents.size shouldBe 2
                    links.assignFolderByIds(linkIds = listOf(movedLink.id!!), folderId = other.id, now = Clock.System.now())
                    links.deleteById(deletedLink.id!!)

                    val result = service.getPublicTodos(folderId, null, null, ScrollRequest())

                    todos.findById(completed.id!!)?.linkId shouldBe movedLink.id
                    links.findById(movedLink.id!!)?.folderId shouldBe other.id
                    todos.findById(incomplete.id!!) shouldBe null
                    result.isEmpty shouldBe true
                    result.contents shouldBe emptyList()
                }
            }
        }

        Given("공개 폴더의 링크에 액션이 하나 연결되어 있을 때") {
            lateinit var folder: Folder
            lateinit var todo: Todo

            beforeTest {
                val ownerId = users.insert(UserFixture.createRandomValidUser()).id!!
                folder = folders.insert(FolderFixture.createValidUnsharedFolder(ownerId).setPublic(true))
                val link =
                    links.insert(
                        LinkFixture.createRandomLinkOf(ownerId = ownerId, folderId = folder.id, isPublic = folder.isPublished),
                    )
                todo = todos.insert(TodoFixture.createRandomTodoOf(linkId = link.id!!, ownerId = ownerId))
            }

            When("액션을 조회하면") {
                Then("한 항목만 반환하고 다음 페이지가 없다") {
                    val result = service.getPublicTodos(folder.id!!, null, null, ScrollRequest())
                    val stored = todos.findById(todo.id!!)!!

                    result.contents shouldBe
                        listOf(PublicFolderTodoResponse(linkId = stored.linkId, title = stored.title, isCompleted = false))
                    result.isEmpty shouldBe false
                    result.nextCursor shouldBe null
                    result.hasNext shouldBe false
                }
            }
        }

        Given("공개 폴더의 액션이 기본 페이지 크기를 넘을 때") {
            lateinit var folder: Folder
            lateinit var link: Link
            lateinit var savedTodos: List<Todo>

            beforeTest {
                val ownerId = users.insert(UserFixture.createRandomValidUser()).id!!
                folder = folders.insert(FolderFixture.createValidUnsharedFolder(ownerId).setPublic(true))
                link =
                    links.insert(LinkFixture.createRandomLinkOf(ownerId = ownerId, folderId = folder.id, isPublic = folder.isPublished))
                savedTodos =
                    List(DEFAULT_SCROLL_SIZE + 2) {
                        todos.insert(TodoFixture.createRandomTodoOf(linkId = link.id!!, ownerId = ownerId))
                    }
            }

            When("기본 크기로 액션 목록을 이어 읽으면") {
                Then("첫 15개 뒤의 행부터 ID 내림차순으로 이어진다") {
                    val folderId = folder.id!!
                    val first = service.getPublicTodos(folderId, null, null, ScrollRequest())
                    val second = service.getPublicTodos(folderId, null, null, ScrollRequest(cursor = first.nextCursor.shouldNotBeNull()))
                    val stored = todos.findAllByLinkId(link.id!!).sortedByDescending { it.id }

                    first.contents.size shouldBe 15
                    first.hasNext shouldBe true
                    (first.contents + second.contents) shouldBe
                        stored.map { PublicFolderTodoResponse(linkId = it.linkId, title = it.title, isCompleted = it.completedAt != null) }
                    second.hasNext shouldBe false
                    second.nextCursor shouldBe null
                }
            }

            When("다음 요청의 정렬 방향을 바꾸면") {
                val read: suspend (String) -> Unit = { cursor ->
                    service.getPublicTodos(folder.id!!, null, "asc", ScrollRequest(cursor = cursor))
                }

                Then("정렬이 다르다는 오류를 반환한다") {
                    val cursor = service.getPublicTodos(folder.id!!, null, "desc", ScrollRequest()).nextCursor.shouldNotBeNull()

                    shouldThrowWithMessage<BusinessException>(ErrorCode.COMMON_CURSOR_ORDER_MISMATCH.message) { read(cursor) }
                }
            }

            When("액션 커서에 내부 ID가 빠지면") {
                val read: suspend (String) -> Unit = { cursor ->
                    service.getPublicTodos(folder.id!!, null, null, ScrollRequest(cursor = cursor))
                }

                Then("필수 커서 값이 없다고 응답한다") {
                    val cursor =
                        codec.encode(
                            PublicFolderCursor(
                                folderId = folder.id!!,
                                collection = "todos",
                                direction = SortDirection.DESC,
                            ),
                        )

                    shouldThrowWithMessage<BusinessException>(ErrorCode.COMMON_CURSOR_FIELD_MISSING.message) { read(cursor) }
                }
            }

            When("발급된 커서로 다음 액션을 조회하면") {
                Then("저장소의 다음 액션을 읽되 행과 커서에서 내부 ID를 확인할 수 없다") {
                    val folderId = folder.id!!
                    val first = service.getPublicTodos(folderId, null, null, ScrollRequest(size = 1))
                    val cursor = first.nextCursor.shouldNotBeNull()
                    val second = service.getPublicTodos(folderId, null, null, ScrollRequest(cursor = cursor, size = 1))
                    val expected = todos.findAllByLinkId(link.id!!).sortedByDescending { it.id }[1]

                    second.contents shouldBe
                        listOf(
                            PublicFolderTodoResponse(
                                linkId = expected.linkId,
                                title = expected.title,
                                isCompleted =
                                    expected.completedAt != null,
                            ),
                        )
                    Json.encodeToJsonElement(second.contents.single()).jsonObject.keys shouldBe setOf("linkId", "title", "isCompleted")
                    assertPublicCursorHidesIds(cursor = cursor, ids = listOf(folder.id!!, link.id!!) + savedTodos.map { it.id!! })
                }
            }
        }

        Given("접근할 수 없거나 존재하지 않는 폴더 주소") {
            lateinit var folderIds: List<Long>

            beforeTest {
                val ownerId = users.insert(UserFixture.createRandomValidUser()).id!!
                folderIds = PublicFolderFixture.createUnavailableIds(folders = folders, ownerId = ownerId)
            }

            When("유효하지 않은 커서와 함께 액션을 조회하면") {
                val cursor = RandomFixture.randomFixedSentence(20)
                val read: suspend (Long) -> Unit = { service.getPublicTodos(it, null, null, ScrollRequest(cursor = cursor)) }

                Then("커서 검사보다 모든 주소에서 같은 공개 폴더 오류가 우선한다") {
                    for (folderId in folderIds) {
                        shouldThrowWithMessage<BusinessException>(ErrorCode.FOLDER_NOT_FOUND.message) { read(folderId) }
                    }
                }
            }
        }
    })
