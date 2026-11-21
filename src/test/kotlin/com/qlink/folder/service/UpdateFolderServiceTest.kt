package com.qlink.folder.service

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
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
import com.qlink.link.service.PatchLinkService
import com.qlink.support.BaseServiceTest
import com.qlink.support.fixture.FolderFixture
import com.qlink.support.fixture.FolderMemberFixture
import com.qlink.support.fixture.LinkFixture
import com.qlink.support.fixture.RandomFixture
import com.qlink.support.fixture.TodoFixture
import com.qlink.support.fixture.UserFixture
import com.qlink.support.koinGet
import com.qlink.support.runWhileFolderLocked
import com.qlink.todo.repository.TodoRepository
import com.qlink.user.domain.User
import com.qlink.user.repository.UserRepository
import io.kotest.assertions.throwables.shouldThrowWithMessage
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async

class UpdateFolderServiceTest :
    BaseServiceTest({
        val updateFolderService = koinGet<UpdateFolderService>()
        val folderRepository = koinGet<FolderRepository>()
        val folderMemberRepository = koinGet<FolderMemberRepository>()
        val userRepository = koinGet<UserRepository>()
        val linkRepository = koinGet<LinkRepository>()
        val createLinkService = koinGet<CreateLinkService>()
        val patchLinkService = koinGet<PatchLinkService>()
        val copyLinkService = koinGet<CopyLinkService>()
        val publicLinks = koinGet<GetPublicFolderLinksService>()
        val publicTodos = koinGet<GetPublicFolderTodosService>()
        val todoRepository = koinGet<TodoRepository>()
        val tx = koinGet<TransactionRunner>()

        suspend fun setPublic(
            folder: Folder,
            isPublic: Boolean,
        ): UpdateFolderResponse =
            updateFolderService.updateFolder(
                loginId = folder.ownerId,
                folderId = folder.id!!,
                request = UpdateFolderRequest(name = folder.name, emoji = folder.emoji, isPublic = isPublic),
            )

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
                Then("반복 공개는 유지하고 철회와 재공개는 이전 주소와 링크를 비활성화한다") {
                    val folderId = folder.id!!
                    val first = setPublic(folder, true).publicId!!
                    linkRepository.findById(existing.id!!)!!.isPublicIn(first) shouldBe false
                    val created = createLinkService.createLink(memberId, LinkFixture.createValidLinkRequest(folderId = folderId))

                    setPublic(folder, true).publicId shouldBe first
                    linkRepository.findById(created.id)!!.isPublicIn(first) shouldBe true
                    setPublic(folder, false).publicId shouldBe null
                    setPublic(folder, false).publicId shouldBe null
                    shouldThrowWithMessage<BusinessException>(ErrorCode.FOLDER_PUBLIC_NOT_FOUND.message) {
                        publicLinks.getLinks(first, null, null, ScrollRequest())
                    }
                    val next = setPublic(folder, true).publicId!!
                    next shouldNotBe first
                    folderRepository.findById(folderId)?.publicId shouldBe next
                    folderRepository.findById(folderId)?.sharedAt shouldBe folder.sharedAt
                    folderMemberRepository.findByFolderIdAndUserId(folderId, memberId)?.role shouldBe MemberRole.MEMBER
                    linkRepository.findAllByFolderIds(listOf(folderId)).none { it.isPublicIn(next) } shouldBe true
                    publicLinks.getLinks(next, null, null, ScrollRequest()).contents shouldBe emptyList()
                }
            }

            When("공개 상태를 철회하면서 중복 이름으로 폴더를 수정하면") {
                var createdId = 0L
                lateinit var originalPublicId: String

                beforeTest {
                    originalPublicId = setPublic(folder, true).publicId!!
                    createdId = createLinkService.createLink(memberId, LinkFixture.createValidLinkRequest(folderId = folder.id)).id
                }
                val update =
                    suspend {
                        updateFolderService.updateFolder(
                            loginId = folder.ownerId,
                            folderId = folder.id!!,
                            request = UpdateFolderRequest(name = duplicate.name, isPublic = false),
                        )
                    }

                Then("이름·활성 ID·링크 공개 상태가 이전과 같다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.FOLDER_DUPLICATE_NAME.message) { update() }
                    val stored = folderRepository.findById(folder.id!!)!!
                    stored.name shouldBe folder.name
                    stored.publicId shouldBe originalPublicId
                    linkRepository.findById(createdId)!!.isPublicIn(originalPublicId) shouldBe true
                }
            }

            When("공유 폴더 멤버가 폴더를 공개하면") {
                val update =
                    suspend {
                        updateFolderService.updateFolder(
                            loginId = memberId,
                            folderId = folder.id!!,
                            request = UpdateFolderRequest(name = folder.name, isPublic = true),
                        )
                    }

                Then("폴더를 찾을 수 없고 공개 상태가 유지된다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.FOLDER_NOT_FOUND.message) { update() }
                    folderRepository.findById(folder.id!!)?.publicId shouldBe null
                }
            }
        }

        Given("여러 트랜잭션에서 접근할 수 있는 폴더") {
            lateinit var folder: Folder

            beforeTest {
                folder =
                    CoroutineScope(Dispatchers.IO)
                        .async {
                            tx.required {
                                val ownerId = userRepository.insert(UserFixture.createRandomValidUser()).id!!
                                folderRepository.insert(FolderFixture.createValidUnsharedFolder(ownerId))
                            }
                        }.await()
            }

            afterTest {
                CoroutineScope(Dispatchers.IO).async { tx.required { userRepository.deleteById(folder.ownerId) } }.await()
            }

            When("두 공개 요청이 겹치면") {
                Then("같은 활성 ID를 반환한다") {
                    CoroutineScope(Dispatchers.IO)
                        .async {
                            val folderId = folder.id!!
                            var first: String? = null
                            val second =
                                runWhileFolderLocked(
                                    tx = tx,
                                    holdLock = { folderRepository.findAllByIdsForUpdate(listOf(folderId)) },
                                    afterWaiting = { first = setPublic(folder, true).publicId },
                                    action = { setPublic(folder, true) },
                                )
                            first shouldNotBe null
                            second.publicId shouldBe first
                            tx.readOnly { folderRepository.findById(folderId)?.publicId } shouldBe first
                        }.await()
                }
            }

            When("링크 생성과 최초 공개·철회가 서로 경합하면") {
                Then("선행 트랜잭션의 공개 상태를 기준으로 저장하며 철회한 세대의 링크는 재공개해도 비공개다") {
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
                                ).publicId!!
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
                            stored.getValue(createdBeforePublish).publicationId shouldBe null
                            stored.getValue(createdWhilePublic).publicationId shouldBe published
                            stored.getValue(createdPrivate).publicationId shouldBe null
                            stored.getValue(createdAfterRevoke).publicationId shouldBe null
                            shouldThrowWithMessage<BusinessException>(ErrorCode.FOLDER_PUBLIC_NOT_FOUND.message) {
                                publicLinks.getLinks(published, null, null, ScrollRequest())
                            }
                            val next = setPublic(folder, true).publicId!!
                            tx.readOnly { linkRepository.findAllByFolderIds(listOf(folderId)).none { it.isPublicIn(next) } } shouldBe true
                            publicLinks.getLinks(next, null, null, ScrollRequest()).contents shouldBe emptyList()
                        }.await()
                }
            }

            When("철회와 링크 공개값 단독 변경이 경합하면") {
                Then("두 커밋 순서에서 재공개 뒤에도 링크와 액션은 보이지 않는다") {
                    CoroutineScope(Dispatchers.IO)
                        .async {
                            val folderId = folder.id!!
                            for (revokeFirst in listOf(true, false)) {
                                val activeId = setPublic(folder, true).publicId!!
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
                                        patchLinkService.patchLink(
                                            folder.ownerId,
                                            linkId,
                                            LinkFixture.createPatchLinkRequest().copy(isPublic = true),
                                        )
                                    }

                                if (revokeFirst) {
                                    val result =
                                        runWhileFolderLocked(
                                            tx = tx,
                                            holdLock = { folderRepository.findAllByIdsForUpdate(listOf(folderId)) },
                                            afterWaiting = { setPublic(folder, false) },
                                            action = { runCatching { publishLink() } },
                                        )
                                    shouldThrowWithMessage<BusinessException>(ErrorCode.LINK_PUBLIC_FOLDER_REQUIRED.message) {
                                        result.getOrThrow()
                                    }
                                } else {
                                    runWhileFolderLocked(
                                        tx = tx,
                                        holdLock = { folderRepository.findAllByIdsForKeyShare(listOf(folderId)) },
                                        afterWaiting = { publishLink().isPublic shouldBe true },
                                        action = { setPublic(folder, false) },
                                    )
                                }
                                tx.readOnly { folderRepository.findById(folderId)?.publicId } shouldBe null
                                shouldThrowWithMessage<BusinessException>(ErrorCode.FOLDER_PUBLIC_NOT_FOUND.message) {
                                    publicTodos.getTodos(activeId, null, null, ScrollRequest())
                                }
                                val next = setPublic(folder, true).publicId!!
                                tx.readOnly { linkRepository.findById(linkId)!!.isPublicIn(next) } shouldBe false
                                publicLinks.getLinks(next, null, null, ScrollRequest()).contents shouldBe emptyList()
                                publicTodos.getTodos(next, null, null, ScrollRequest()).contents shouldBe emptyList()
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
                                            FolderFixture.createSharedFolderOf(ownerId).setPublic(FolderFixture.createPublicId()),
                                        )
                                    folderMemberRepository.insertIfAbsent(
                                        FolderMemberFixture.createOwnerMember(folderId = source.id!!, userId = ownerId),
                                    )
                                    val link =
                                        linkRepository.insert(
                                            LinkFixture.createRandomLinkOf(
                                                ownerId = ownerId,
                                                folderId = source.id,
                                                publicationId = source.publicId,
                                            ),
                                        )
                                    todoRepository.insert(TodoFixture.createRandomTodoOf(linkId = link.id!!, ownerId = ownerId))
                                    source to link
                                }
                            val publishedForCopy = setPublic(folder, true).publicId!!
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
                            val publishedForMove = setPublic(folder, true).publicId!!
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
                            stored.all { it.publicationId == null } shouldBe true
                            for (revoked in listOf(publishedForCopy, publishedForMove)) {
                                shouldThrowWithMessage<BusinessException>(ErrorCode.FOLDER_PUBLIC_NOT_FOUND.message) {
                                    publicLinks.getLinks(revoked, null, null, ScrollRequest())
                                }
                            }
                            val next = setPublic(folder, true).publicId!!
                            publicLinks.getLinks(next, null, null, ScrollRequest()).contents shouldBe emptyList()
                            publicTodos.getTodos(next, null, null, ScrollRequest()).contents shouldBe emptyList()
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
