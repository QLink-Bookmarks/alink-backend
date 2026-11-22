package com.qlink.folder.service

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.response.ReadResult
import com.qlink.common.scroll.ScrollRequest
import com.qlink.common.transaction.TransactionRunner
import com.qlink.folder.domain.Folder
import com.qlink.folder.dto.UpdateFolderRequest
import com.qlink.folder.dto.UpdateFolderResponse
import com.qlink.folder.repository.FolderRepository
import com.qlink.foldermember.domain.MemberRole
import com.qlink.foldermember.repository.FolderMemberRepository
import com.qlink.link.domain.Link
import com.qlink.link.dto.CopyLinkRequest
import com.qlink.link.dto.CreateLinkTodoRequest
import com.qlink.link.repository.LinkRepository
import com.qlink.link.service.CopyLinkService
import com.qlink.link.service.CreateLinkService
import com.qlink.link.service.GetLinksService
import com.qlink.link.service.PatchLinkService
import com.qlink.link.service.SetLinkPrivacyService
import com.qlink.support.BaseServiceTest
import com.qlink.support.fixture.FolderFixture
import com.qlink.support.fixture.FolderMemberFixture
import com.qlink.support.fixture.LinkFixture
import com.qlink.support.fixture.RandomFixture
import com.qlink.support.fixture.TodoFixture
import com.qlink.support.fixture.UserFixture
import com.qlink.support.fixture.withPublication
import com.qlink.support.getPublicLinks
import com.qlink.support.getPublicTodos
import com.qlink.support.koinGet
import com.qlink.support.runWhileFolderLocked
import com.qlink.support.runWhileLinkLocked
import com.qlink.todo.repository.TodoRepository
import com.qlink.todo.service.GetTodosService
import com.qlink.user.domain.User
import com.qlink.user.repository.UserRepository
import io.kotest.assertions.throwables.shouldThrowWithMessage
import io.kotest.core.test.TestType
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlin.time.Instant

class UpdateFolderServiceTest :
    BaseServiceTest({
        val updateFolderService = koinGet<UpdateFolderService>()
        val setFolderPrivacyService = koinGet<SetFolderPrivacyService>()
        val setLinkPrivacyService = koinGet<SetLinkPrivacyService>()
        val transferFolderOwnerService = koinGet<TransferFolderOwnerService>()
        val folderRepository = koinGet<FolderRepository>()
        val folderMemberRepository = koinGet<FolderMemberRepository>()
        val userRepository = koinGet<UserRepository>()
        val linkRepository = koinGet<LinkRepository>()
        val createLinkService = koinGet<CreateLinkService>()
        val patchLinkService = koinGet<PatchLinkService>()
        val copyLinkService = koinGet<CopyLinkService>()
        val foldersService = koinGet<GetFolderService>()
        val publicLinks = koinGet<GetLinksService>()
        val publicTodos = koinGet<GetTodosService>()
        val todoRepository = koinGet<TodoRepository>()
        val tx = koinGet<TransactionRunner>()

        suspend fun setPublic(
            folder: Folder,
            isPublic: Boolean,
        ): Instant? {
            val folderId = folder.id!!
            setFolderPrivacyService.setPrivacy(loginId = folder.ownerId, folderId = folderId, isPublic = isPublic).isPublic shouldBe
                isPublic
            return tx.readOnly { folderRepository.findById(folderId)?.turnsPublicAt }
        }

        Given("공유 비공개 폴더와 기존 링크") {
            lateinit var folder: Folder
            lateinit var existing: Link
            lateinit var duplicate: Folder
            var memberId = 0L

            beforeTest {
                val ownerId = userRepository.insert(UserFixture.createRandomValidUser()).id!!
                memberId = userRepository.insert(UserFixture.createRandomValidUser()).id!!
                folder = folderRepository.insert(FolderFixture.createSharedFolderOf(ownerId))
                duplicate = folderRepository.insert(FolderFixture.createValidUnsharedFolder(ownerId))
                folderMemberRepository.insertIfAbsent(FolderMemberFixture.createOwnerMember(folderId = folder.id!!, userId = ownerId))
                folderMemberRepository.insertIfAbsent(FolderMemberFixture.createMember(folderId = folder.id!!, userId = memberId))
                existing = linkRepository.insert(LinkFixture.createRandomLinkOf(ownerId = memberId, folderId = folder.id))
            }

            When("최초 공개하고 새 링크를 저장한 뒤 반복 공개, 철회, 재공개하면") {
                Then("반복 공개는 유지하고 철회와 재공개는 기존 링크를 비공개로 전환한다") {
                    val folderId = folder.id!!
                    val first = setPublic(folder, true)
                    linkRepository.findById(existing.id!!)!!.isPublishedIn(first != null) shouldBe false
                    val created = createLinkService.createLink(memberId, LinkFixture.createValidLinkRequest(folderId = folderId))

                    setPublic(folder, true) shouldBe first
                    linkRepository.findById(created.id)!!.isPublishedIn(first != null) shouldBe true
                    setPublic(folder, false) shouldBe null
                    setPublic(folder, false) shouldBe null
                    shouldThrowWithMessage<BusinessException>(ErrorCode.FOLDER_NOT_FOUND.message) {
                        publicLinks.getPublicLinks(folderId, null, null, ScrollRequest())
                    }
                    val next = setPublic(folder, true)
                    next shouldNotBe null
                    next shouldNotBe first
                    folderRepository.findById(folderId)?.turnsPublicAt shouldBe next
                    folderRepository.findById(folderId)?.sharedAt shouldBe folder.sharedAt
                    folderMemberRepository.findByFolderIdAndUserId(folderId, memberId)?.role shouldBe MemberRole.MEMBER
                    linkRepository.findAllByFolderIds(listOf(folderId)).none { it.isPublishedIn(next != null) } shouldBe true
                    publicLinks.getPublicLinks(folderId, null, null, ScrollRequest()).contents shouldBe emptyList()
                }
            }

            When("공개 폴더를 중복 이름으로 수정하면") {
                var createdId = 0L
                var originalVisibility: Instant? = null

                beforeTest {
                    originalVisibility = setPublic(folder, true)
                    createdId = createLinkService.createLink(memberId, LinkFixture.createValidLinkRequest(folderId = folder.id)).id
                }
                val update =
                    suspend {
                        updateFolderService.updateFolder(
                            loginId = folder.ownerId,
                            folderId = folder.id!!,
                            request = UpdateFolderRequest(name = duplicate.name),
                        )
                    }

                Then("이름·공개 상태·링크 공개 상태가 이전과 같다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.FOLDER_DUPLICATE_NAME.message) { update() }
                    val stored = folderRepository.findById(folder.id!!)!!
                    stored.name shouldBe folder.name
                    stored.turnsPublicAt shouldBe originalVisibility
                    linkRepository.findById(createdId)!!.isPublishedIn(originalVisibility != null) shouldBe true
                }
            }

            When("공개 폴더의 이름만 변경하면") {
                Then("기존 폴더와 링크의 공개 시각을 유지한다") {
                    val originalVisibility = setPublic(folder, true)
                    val created = createLinkService.createLink(memberId, LinkFixture.createValidLinkRequest(folderId = folder.id))
                    val originalLinkVisibility = linkRepository.findById(created.id)?.turnsPublicAt
                    val name = RandomFixture.randomSentenceWithMax(100)

                    val response =
                        updateFolderService.updateFolder(
                            loginId = folder.ownerId,
                            folderId = folder.id!!,
                            request = UpdateFolderRequest(name = name),
                        )

                    response.isPublic shouldBe true
                    val stored = folderRepository.findById(folder.id!!)!!
                    stored.name shouldBe name
                    stored.turnsPublicAt shouldBe originalVisibility
                    originalLinkVisibility shouldNotBe null
                    linkRepository.findById(created.id)?.turnsPublicAt shouldBe originalLinkVisibility
                }
            }

            When("공유 폴더 멤버가 폴더를 공개하면") {
                val update =
                    suspend {
                        setFolderPrivacyService.setPrivacy(loginId = memberId, folderId = folder.id!!, isPublic = true)
                    }

                Then("폴더를 찾을 수 없고 공개 상태가 유지된다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.FOLDER_NOT_FOUND.message) { update() }
                    folderRepository.findById(folder.id!!)?.turnsPublicAt shouldBe null
                }
            }
        }

        Given("여러 트랜잭션에서 접근할 수 있는 폴더") {
            lateinit var folder: Folder

            beforeTest {
                if (it.type != TestType.Test) return@beforeTest
                folder =
                    CoroutineScope(Dispatchers.IO)
                        .async {
                            tx.required {
                                val ownerId = userRepository.insert(UserFixture.createRandomValidUser()).id!!
                                folderRepository.insert(FolderFixture.createValidUnsharedFolder(ownerId))
                            }
                        }.await()
            }

            afterTest { (testCase, _) ->
                if (testCase.type != TestType.Test) return@afterTest
                CoroutineScope(Dispatchers.IO).async { tx.required { userRepository.deleteById(folder.ownerId) } }.await()
            }

            When("두 공개 요청이 겹치면") {
                Then("두 요청이 모두 공개 상태를 반환한다") {
                    CoroutineScope(Dispatchers.IO)
                        .async {
                            val folderId = folder.id!!
                            var first: Instant? = null
                            val second =
                                runWhileFolderLocked(
                                    tx = tx,
                                    holdLock = { folderRepository.findAllByIdsForUpdate(listOf(folderId)) },
                                    afterWaiting = { first = setPublic(folder, true) },
                                    action = { setPublic(folder, true) },
                                )
                            first shouldNotBe null
                            second shouldBe first
                            tx.readOnly { folderRepository.findById(folderId)?.turnsPublicAt } shouldBe first
                        }.await()
                }
            }

            When("공개 철회 저장 후 바깥 트랜잭션이 롤백되면") {
                var folderPublicationAt: Instant? = null
                var linkId = 0L
                var linkPublicationAt: Instant? = null

                beforeTest {
                    if (it.type != TestType.Test) return@beforeTest
                    CoroutineScope(Dispatchers.IO)
                        .async {
                            folderPublicationAt = setPublic(folder, true)
                            linkId =
                                createLinkService
                                    .createLink(
                                        folder.ownerId,
                                        LinkFixture.createValidLinkRequest(folderId = folder.id),
                                    ).id
                            linkPublicationAt = tx.readOnly { linkRepository.findById(linkId)?.turnsPublicAt }
                        }.await()
                }

                val revoke =
                    suspend {
                        tx.required<Unit> {
                            setPublic(folder, false)
                            throw BusinessException(ErrorCode.FOLDER_DUPLICATE_NAME)
                        }
                    }

                Then("폴더와 링크의 기존 공개 시각을 함께 복구한다") {
                    CoroutineScope(Dispatchers.IO)
                        .async {
                            folderPublicationAt shouldNotBe null
                            linkPublicationAt shouldNotBe null
                            shouldThrowWithMessage<BusinessException>(ErrorCode.FOLDER_DUPLICATE_NAME.message) { revoke() }
                            tx.readOnly {
                                folderRepository.findById(folder.id!!)?.turnsPublicAt shouldBe folderPublicationAt
                                linkRepository.findById(linkId)?.turnsPublicAt shouldBe linkPublicationAt
                            }
                        }.await()
                }
            }

            When("링크 생성과 최초 공개·철회가 서로 경합하면") {
                Then("선행 트랜잭션의 공개 상태를 기준으로 저장하며 철회한 폴더의 링크는 재공개해도 비공개다") {
                    CoroutineScope(Dispatchers.IO)
                        .async {
                            val folderId = folder.id!!
                            val create =
                                suspend {
                                    createLinkService.createLink(folder.ownerId, LinkFixture.createValidLinkRequest(folderId = folderId))
                                }
                            var createdBeforePublish: Long? = null
                            val published =
                                runWhileFolderLocked(
                                    tx = tx,
                                    holdLock = { folderRepository.findAllByIdsForKeyShare(listOf(folderId)) },
                                    afterWaiting = { createdBeforePublish = create().id },
                                    action = { setPublic(folder, true) },
                                )
                            val createdWhilePublic = create().id
                            val createdPrivate =
                                createLinkService
                                    .createLink(
                                        folder.ownerId,
                                        LinkFixture.createValidLinkRequest(folderId = folderId, isPublic = false),
                                    ).id
                            val createdAfterRevoke =
                                runWhileFolderLocked(
                                    tx = tx,
                                    holdLock = { folderRepository.findAllByIdsForUpdate(listOf(folderId)) },
                                    afterWaiting = { setPublic(folder, false) },
                                    action = create,
                                ).id
                            val stored = tx.readOnly { linkRepository.findAllByFolderIds(listOf(folderId)).associateBy { it.id } }
                            stored.getValue(createdBeforePublish).turnsPublicAt shouldBe null
                            published shouldNotBe null
                            stored.getValue(createdWhilePublic).turnsPublicAt shouldBe null
                            stored.getValue(createdPrivate).turnsPublicAt shouldBe null
                            stored.getValue(createdAfterRevoke).turnsPublicAt shouldBe null
                            shouldThrowWithMessage<BusinessException>(ErrorCode.FOLDER_NOT_FOUND.message) {
                                publicLinks.getPublicLinks(folderId, null, null, ScrollRequest())
                            }
                            val next = setPublic(folder, true)
                            tx.readOnly {
                                linkRepository
                                    .findAllByFolderIds(
                                        listOf(folderId),
                                    ).none { it.isPublishedIn(next != null) }
                            } shouldBe
                                true
                            publicLinks.getPublicLinks(folderId, null, null, ScrollRequest()).contents shouldBe emptyList()
                        }.await()
                }
            }

            When("최초 공개가 대기 중인 링크 생성보다 먼저 커밋되면") {
                Then("생략한 공개값은 전체 공개이고 명시한 비공개는 유지한다") {
                    CoroutineScope(Dispatchers.IO)
                        .async {
                            for (visibility in listOf(null, false)) {
                                val privateFolder =
                                    if (visibility == null) {
                                        folder
                                    } else {
                                        tx.required {
                                            folderRepository.insert(FolderFixture.createValidUnsharedFolder(folder.ownerId))
                                        }
                                    }
                                val folderId = privateFolder.id!!
                                var published: Instant? = null
                                val request = LinkFixture.createValidLinkRequest(folderId = folderId, isPublic = visibility)
                                val result =
                                    runWhileFolderLocked(
                                        tx = tx,
                                        holdLock = { folderRepository.findAllByIdsForUpdate(listOf(folderId)) },
                                        afterWaiting = { published = setPublic(privateFolder, true) },
                                        action = { createLinkService.createLink(folder.ownerId, request) },
                                    )

                                published shouldNotBe null
                                val stored = tx.readOnly { linkRepository.findById(result.id)!! }
                                (stored.turnsPublicAt != null) shouldBe (visibility == null)
                            }
                        }.await()
                }
            }

            When("링크 생성이 대기 중인 공개 철회보다 먼저 커밋되면") {
                Then("이전 주소가 닫히고 재공개 뒤에도 그 링크와 액션은 조회되지 않는다") {
                    CoroutineScope(Dispatchers.IO)
                        .async {
                            val folderId = folder.id!!
                            setPublic(folder, true) shouldNotBe null
                            val request =
                                LinkFixture.createValidLinkRequest(
                                    folderId = folderId,
                                    todos = listOf(CreateLinkTodoRequest(title = RandomFixture.randomSentenceWithMax(50))),
                                )
                            var createdId: Long? = null
                            val result =
                                runWhileFolderLocked(
                                    tx = tx,
                                    holdLock = { folderRepository.findAllByIdsForKeyShare(listOf(folderId)) },
                                    afterWaiting = { createdId = createLinkService.createLink(folder.ownerId, request).id },
                                    action = { setPublic(folder, false) },
                                )
                            val linkId = createdId!!
                            result shouldBe null
                            tx.readOnly {
                                linkRepository.findById(linkId)!!.turnsPublicAt shouldBe null
                                todoRepository.findAllByLinkId(linkId).size shouldBe 1
                            }
                            shouldThrowWithMessage<BusinessException>(ErrorCode.FOLDER_NOT_FOUND.message) {
                                publicLinks.getPublicLinks(folderId, null, null, ScrollRequest())
                            }
                            shouldThrowWithMessage<BusinessException>(ErrorCode.FOLDER_NOT_FOUND.message) {
                                publicTodos.getPublicTodos(folderId, null, null, ScrollRequest())
                            }
                            val next = setPublic(folder, true)
                            tx.readOnly { linkRepository.findById(linkId)!!.isPublishedIn(next != null) } shouldBe false
                            publicLinks.getPublicLinks(folderId, null, null, ScrollRequest()).contents shouldBe emptyList()
                            publicTodos.getPublicTodos(folderId, null, null, ScrollRequest()).contents shouldBe emptyList()
                        }.await()
                }
            }

            When("철회와 링크 공개값 단독 변경이 경합하면") {
                Then("두 커밋 순서에서 재공개 뒤에도 링크와 액션은 보이지 않는다") {
                    CoroutineScope(Dispatchers.IO)
                        .async {
                            val folderId = folder.id!!
                            for (revokeFirst in listOf(true, false)) {
                                setPublic(folder, true) shouldNotBe null
                                val linkId =
                                    createLinkService
                                        .createLink(
                                            folder.ownerId,
                                            LinkFixture.createValidLinkRequest(
                                                folderId = folderId,
                                                isPublic = false,
                                                todos = listOf(CreateLinkTodoRequest(title = RandomFixture.randomSentenceWithMax(50))),
                                            ),
                                        ).id
                                val publishLink =
                                    suspend {
                                        setLinkPrivacyService.setPrivacy(loginId = folder.ownerId, linkId = linkId, isPublic = true)
                                    }

                                if (revokeFirst) {
                                    val result =
                                        runWhileLinkLocked(
                                            tx = tx,
                                            holdLock = { setPublic(folder, false) },
                                            action = { runCatching { publishLink() } },
                                        )
                                    shouldThrowWithMessage<BusinessException>(ErrorCode.LINK_PUBLIC_FOLDER_REQUIRED.message) {
                                        result.getOrThrow()
                                    }
                                } else {
                                    runWhileLinkLocked(
                                        tx = tx,
                                        holdLock = { linkRepository.findByIdForUpdate(linkId) },
                                        afterWaiting = { publishLink().isPublic shouldBe true },
                                        action = { setPublic(folder, false) },
                                    )
                                }
                                tx.readOnly { folderRepository.findById(folderId)?.turnsPublicAt } shouldBe null
                                shouldThrowWithMessage<BusinessException>(ErrorCode.FOLDER_NOT_FOUND.message) {
                                    publicTodos.getPublicTodos(folderId, null, null, ScrollRequest())
                                }
                                val next = setPublic(folder, true)
                                tx.readOnly { linkRepository.findById(linkId)!!.isPublishedIn(next != null) } shouldBe false
                                publicLinks.getPublicLinks(folderId, null, null, ScrollRequest()).contents shouldBe emptyList()
                                publicTodos.getPublicTodos(folderId, null, null, ScrollRequest()).contents shouldBe emptyList()
                            }
                        }.await()
                }
            }

            When("목적지 철회와 링크 이동·복사가 경합하면") {
                Then("비공개 목적지에 저장되며 예전 주소나 재공개 주소로 링크·액션을 읽을 수 없다") {
                    CoroutineScope(Dispatchers.IO)
                        .async {
                            val folderId = folder.id!!
                            val ownerId = folder.ownerId
                            val (source, link) =
                                tx.required {
                                    val source =
                                        folderRepository.insert(
                                            FolderFixture
                                                .createSharedFolderOf(
                                                    ownerId,
                                                ).withPublication(),
                                        )
                                    folderMemberRepository.insertIfAbsent(
                                        FolderMemberFixture.createOwnerMember(folderId = source.id!!, userId = ownerId),
                                    )
                                    val link =
                                        linkRepository.insert(
                                            LinkFixture.createRandomLinkOf(
                                                ownerId = ownerId,
                                                folderId = source.id,
                                                turnsPublicAt = source.turnsPublicAt,
                                            ),
                                        )
                                    todoRepository.insert(TodoFixture.createRandomTodoOf(linkId = link.id!!, ownerId = ownerId))
                                    source to link
                                }
                            setPublic(folder, true) shouldNotBe null
                            val copy =
                                runWhileFolderLocked(
                                    tx = tx,
                                    holdLock = { folderRepository.findAllByIdsForUpdate(listOf(folderId)) },
                                    afterWaiting = { setPublic(folder, false) },
                                    action = {
                                        copyLinkService.copyLink(
                                            loginId = ownerId,
                                            linkId = link.id!!,
                                            request = CopyLinkRequest(fromFolderId = source.id!!, toFolderId = folderId),
                                        )
                                    },
                                )
                            setPublic(folder, true) shouldNotBe null
                            val move =
                                runWhileFolderLocked(
                                    tx = tx,
                                    holdLock = { folderRepository.findAllByIdsForUpdate(listOf(folderId)) },
                                    afterWaiting = { setPublic(folder, false) },
                                    action = {
                                        val request = LinkFixture.createPatchLinkRequest(folderId = folderId)
                                        patchLinkService.patchLink(ownerId, link.id!!, request)
                                    },
                                )

                            move.isPublic shouldBe false
                            val stored = tx.readOnly { linkRepository.findAllByFolderIds(listOf(folderId)) }
                            stored.map { it.id }.toSet() shouldBe setOf(link.id, copy.id)
                            stored.none { it.turnsPublicAt != null } shouldBe true
                            shouldThrowWithMessage<BusinessException>(ErrorCode.FOLDER_NOT_FOUND.message) {
                                publicLinks.getPublicLinks(folderId, null, null, ScrollRequest())
                            }
                            setPublic(folder, true) shouldNotBe null
                            publicLinks.getPublicLinks(folderId, null, null, ScrollRequest()).contents shouldBe emptyList()
                            publicTodos.getPublicTodos(folderId, null, null, ScrollRequest()).contents shouldBe emptyList()
                        }.await()
                }
            }
        }

        Given("폴더 수정 서비스 테스트") {
            lateinit var user: User
            lateinit var otherUser: User
            lateinit var folder: Folder
            lateinit var otherUserFolder: Folder

            beforeTest {
                user = userRepository.insert(UserFixture.createRandomValidUser())
                otherUser = userRepository.insert(UserFixture.createRandomValidUser())
                folder = folderRepository.insert(FolderFixture.createValidUnsharedFolder(user.id!!))
                otherUserFolder = folderRepository.insert(FolderFixture.createValidUnsharedFolder(otherUser.id!!))
            }

            When("본인 폴더 수정을") {
                val request =
                    UpdateFolderRequest(
                        name = RandomFixture.randomSentenceWithMax(100),
                        emoji = RandomFixture.randomEmoji(),
                    )
                val update =
                    suspend {
                        UpdateResult(
                            previous = folder,
                            response = updateFolderService.updateFolder(user.id!!, folder.id!!, request),
                            request = request,
                        )
                    }

                Then("성공한다") {
                    val result = update()
                    val actual = folderRepository.findById(result.previous.id!!)

                    result.response.id shouldBe result.previous.id
                    actual shouldNotBe null
                    actual!!.name shouldBe result.request.name
                    actual.emoji shouldBe result.request.emoji
                    actual.updatedAt shouldNotBe result.previous.updatedAt
                }
            }

            When("소유자가 폴더를 공유 상태로 전환하면") {
                val request =
                    UpdateFolderRequest(
                        name = RandomFixture.randomSentenceWithMax(100),
                        emoji = RandomFixture.randomEmoji(),
                        isShared = true,
                    )
                val update =
                    suspend {
                        updateFolderService.updateFolder(user.id!!, folder.id!!, request)
                    }

                Then("공유 시각과 소유자 멤버가 저장된다") {
                    val response = update()
                    val actual = folderRepository.findById(response.id)
                    val member = folderMemberRepository.findByFolderIdAndUserId(response.id, user.id!!)

                    actual shouldNotBe null
                    actual!!.sharedAt shouldNotBe null
                    member shouldNotBe null
                    member!!.userName shouldBe user.nickname
                    member.role shouldBe MemberRole.OWNER
                }
            }

            When("로그인 사용자가 없으면") {
                val request =
                    UpdateFolderRequest(
                        name = RandomFixture.randomSentenceWithMax(100),
                        emoji = RandomFixture.randomEmoji(),
                    )
                val update =
                    suspend {
                        updateFolderService.updateFolder(RandomFixture.randomId(), folder.id!!, request)
                    }

                Then("예외를 반환한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.FOLDER_OWNER_NOT_FOUND.message) {
                        update()
                    }
                }
            }

            When("없는 폴더 수정을") {
                val request =
                    UpdateFolderRequest(
                        name = RandomFixture.randomSentenceWithMax(100),
                        emoji = RandomFixture.randomEmoji(),
                    )
                val update =
                    suspend {
                        updateFolderService.updateFolder(user.id!!, RandomFixture.randomId(), request)
                    }

                Then("예외를 반환한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.FOLDER_NOT_FOUND.message) {
                        update()
                    }
                }
            }

            When("다른 사용자의 폴더 수정을") {
                val request =
                    UpdateFolderRequest(
                        name = RandomFixture.randomSentenceWithMax(100),
                        emoji = RandomFixture.randomEmoji(),
                    )
                val update =
                    suspend {
                        updateFolderService.updateFolder(user.id!!, otherUserFolder.id!!, request)
                    }

                Then("예외를 반환한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.FOLDER_DIFFERENT_OWNER.message) {
                        update()
                    }
                }
            }

            When("다른 사용자의 폴더를 공유 상태로 전환하면") {
                val request =
                    UpdateFolderRequest(
                        name = RandomFixture.randomSentenceWithMax(100),
                        emoji = RandomFixture.randomEmoji(),
                        isShared = true,
                    )
                val update =
                    suspend {
                        updateFolderService.updateFolder(user.id!!, otherUserFolder.id!!, request)
                    }

                Then("예외를 반환한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.FOLDER_DIFFERENT_OWNER.message) {
                        update()
                    }
                }
            }

            When("같은 이름의 다른 폴더가 이미 있으면") {
                val duplicatedName = RandomFixture.randomSentenceWithMax(100)

                beforeTest {
                    folderRepository.insert(
                        FolderFixture.createFolderWith(
                            ownerId = user.id!!,
                            name = duplicatedName,
                        ),
                    )
                }

                val request =
                    UpdateFolderRequest(
                        name = duplicatedName,
                        emoji = RandomFixture.randomEmoji(),
                    )
                val update =
                    suspend {
                        updateFolderService.updateFolder(user.id!!, folder.id!!, request)
                    }

                Then("예외를 반환한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.FOLDER_DUPLICATE_NAME.message) {
                        update()
                    }
                }
            }

            When("같은 이름으로 자기 자신을 수정하면") {
                val update =
                    suspend {
                        val request =
                            UpdateFolderRequest(
                                name = folder.name,
                                emoji = RandomFixture.randomEmoji(),
                            )
                        val response = updateFolderService.updateFolder(user.id!!, folder.id!!, request)

                        response to request
                    }

                Then("중복으로 보지 않고 성공한다") {
                    val (response, request) = update()
                    val actual = folderRepository.findById(folder.id!!)

                    response.id shouldBe folder.id
                    actual shouldNotBe null
                    actual!!.name shouldBe folder.name
                    actual.emoji shouldBe request.emoji
                }
            }
        }

        Given("공개된 공유 폴더를 다른 멤버에게 이전하는 소유자") {
            lateinit var folder: Folder
            var recipientId = 0L

            beforeTest {
                val ownerId = userRepository.insert(UserFixture.createRandomValidUser()).id!!
                recipientId = userRepository.insert(UserFixture.createRandomValidUser()).id!!
                folder =
                    folderRepository.insert(
                        FolderFixture.createSharedFolderOf(ownerId).withPublication(),
                    )
                folderMemberRepository.insertIfAbsent(FolderMemberFixture.createOwnerMember(folderId = folder.id!!, userId = ownerId))
                folderMemberRepository.insertIfAbsent(FolderMemberFixture.createMember(folderId = folder.id!!, userId = recipientId))
                transferFolderOwnerService.transferOwner(loginId = ownerId, folderId = folder.id!!, ownerId = recipientId)
            }

            When("이전 소유자가 이전 전에 준비한 공개 철회 요청을 보내면") {
                val update =
                    suspend {
                        setFolderPrivacyService.setPrivacy(loginId = folder.ownerId, folderId = folder.id!!, isPublic = false)
                    }

                Then("폴더를 찾을 수 없고 새 소유자의 공개 상태와 주소가 그대로다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.FOLDER_NOT_FOUND.message) { update() }
                    val stored = folderRepository.findById(folder.id!!)!!
                    stored.ownerId shouldBe recipientId
                    stored.turnsPublicAt shouldNotBe null
                    stored.turnsPublicAt shouldBe folder.turnsPublicAt
                    (foldersService.getFolder(folderId = folder.id!!, viewerId = null) as ReadResult.Guest).data.name shouldBe folder.name
                }
            }
        }

        Given("소유권이 위임된 공유 폴더의 수정 회귀 검사") {
            lateinit var owner: User
            lateinit var target: User
            lateinit var transferred: Folder
            beforeTest {
                owner = userRepository.insert(UserFixture.createRandomValidUser())
                target = userRepository.insert(UserFixture.createRandomValidUser())
                transferred = folderRepository.insert(FolderFixture.createSharedFolderOf(owner.id!!))
                folderMemberRepository.insertIfAbsent(
                    FolderMemberFixture.createMember(folderId = transferred.id!!, userId = target.id!!),
                )
                folderRepository.update(transferred.delegateTo(target.id!!))
                folderMemberRepository.updateRole(
                    folderId = transferred.id!!,
                    userId = target.id!!,
                    role = MemberRole.OWNER,
                )
            }
            When("새 소유자가 폴더를 수정하면 (회귀)") {
                val request = UpdateFolderRequest(name = RandomFixture.randomSentenceWithMax(100), emoji = RandomFixture.randomEmoji())
                val update =
                    suspend { updateFolderService.updateFolder(loginId = target.id!!, folderId = transferred.id!!, request = request) }
                Then("현재 소유권을 유지하며 변경 내용이 저장된다") {
                    update()
                    folderRepository.findById(transferred.id!!)?.ownerId shouldBe target.id
                    folderRepository.findById(transferred.id!!)?.name shouldBe request.name
                }
            }
        }
    })

private data class UpdateResult(
    val previous: Folder,
    val response: UpdateFolderResponse,
    val request: UpdateFolderRequest,
)
