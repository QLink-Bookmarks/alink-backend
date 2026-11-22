package com.qlink.link.service

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.transaction.TransactionRunner
import com.qlink.folder.domain.Folder
import com.qlink.folder.repository.FolderRepository
import com.qlink.image.service.PreviewImageUrlResolver
import com.qlink.link.domain.Link
import com.qlink.link.dto.UpdateLinkRequest
import com.qlink.link.dto.UpdateLinkResponse
import com.qlink.link.repository.LinkRepository
import com.qlink.linkcontent.domain.ContentPriority
import com.qlink.linkcontent.domain.ContentStatus
import com.qlink.linkcontent.domain.LinkContent
import com.qlink.linkcontent.repository.LinkContentRepository
import com.qlink.support.BaseServiceTest
import com.qlink.support.fixture.FolderFixture
import com.qlink.support.fixture.LinkContentFixture
import com.qlink.support.fixture.LinkFixture
import com.qlink.support.fixture.RandomFixture
import com.qlink.support.fixture.TodoFixture
import com.qlink.support.fixture.UserFixture
import com.qlink.support.koinGet
import com.qlink.support.runWhileFolderLocked
import com.qlink.support.runWhileLinkLocked
import com.qlink.todo.repository.TodoRepository
import com.qlink.user.domain.User
import com.qlink.user.repository.UserRepository
import io.kotest.assertions.throwables.shouldThrowWithMessage
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlin.time.Clock

class UpdateLinkServiceTest :
    BaseServiceTest({
        val updateLinkService = koinGet<UpdateLinkService>()
        val urlResolver = koinGet<PreviewImageUrlResolver>()
        val userRepository = koinGet<UserRepository>()
        val linkRepository = koinGet<LinkRepository>()
        val linkContentRepository = koinGet<LinkContentRepository>()
        val tx = koinGet<TransactionRunner>()
        val folderRepository = koinGet<FolderRepository>()
        val todoRepository = koinGet<TodoRepository>()
        val linkDetail = koinGet<GetLinkDetailService>()

        Given("비공개 링크와 액션이 있는 공개 폴더") {
            lateinit var folder: Folder
            lateinit var link: Link
            var otherId = 0L

            beforeTest {
                val ownerId = userRepository.insert(UserFixture.createRandomValidUser()).id!!
                otherId = userRepository.insert(UserFixture.createRandomValidUser()).id!!
                folder = folderRepository.insert(FolderFixture.createValidUnsharedFolder(ownerId).setPublic(true))
                link = linkRepository.insert(LinkFixture.createRandomLinkOf(ownerId = ownerId, folderId = folder.id))
                todoRepository.insert(TodoFixture.createRandomTodoOf(linkId = link.id!!, ownerId = ownerId))
            }

            When("링크 소유자가 공개하고 공개값을 생략해 제목을 저장한 뒤 철회하면") {
                Then("개별 공개값을 유지하며 익명 링크·액션 조회와 인증 상세에 반영한다") {
                    val published = folder.isPublic
                    val linkId = link.id!!
                    val request = LinkFixture.createUpdateLinkRequestFrom(link)

                    suspend fun update(isPublic: Boolean?) =
                        updateLinkService.updateLink(link.ownerId, linkId, request.copy(isPublic = isPublic))

                    update(true).isPublic shouldBe true
                    val title = RandomFixture.randomSentenceWithMax(300)
                    updateLinkService.updateLink(link.ownerId, linkId, request.copy(title = title)).isPublic shouldBe true
                    val stored = linkRepository.findById(linkId)!!
                    stored.isPublicIn(published) shouldBe true
                    stored.title shouldBe title
                    linkDetail.getLinkDetail(loginId = link.ownerId, linkId = linkId).isPublic shouldBe true

                    update(false).isPublic shouldBe false
                    linkRepository.findById(linkId)!!.isPublic shouldBe false
                    linkDetail.getLinkDetail(loginId = link.ownerId, linkId = linkId).isPublic shouldBe false
                }
            }

            When("다른 사용자가 링크를 공개하면") {
                val update =
                    suspend {
                        val request = LinkFixture.createUpdateLinkRequestFrom(link).copy(isPublic = true)
                        updateLinkService.updateLink(otherId, link.id!!, request)
                    }

                Then("링크 소유자 오류가 발생하고 링크는 비공개다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.LINK_DIFFERENT_OWNER.message) { update() }
                    linkRepository.findById(link.id!!)!!.isPublic shouldBe false
                }
            }
        }

        Given("링크 수정 서비스 테스트") {
            lateinit var user: User
            lateinit var otherUser: User
            lateinit var folder: Folder
            lateinit var otherFolder: Folder
            lateinit var link: Link
            lateinit var otherUserLink: Link
            lateinit var linkWithoutRequestedFolder: Link
            lateinit var linkWithOtherUserFolderRequest: Link
            lateinit var updateRequest: UpdateLinkRequest
            lateinit var otherUserLinkUpdateRequest: UpdateLinkRequest
            lateinit var missingFolderRequest: UpdateLinkRequest
            lateinit var otherUserFolderRequest: UpdateLinkRequest

            beforeTest {
                user = userRepository.insert(UserFixture.createRandomValidUser())
                otherUser = userRepository.insert(UserFixture.createRandomValidUser())
                folder = folderRepository.insert(FolderFixture.createValidUnsharedFolder(user.id!!))
                otherFolder = folderRepository.insert(FolderFixture.createValidUnsharedFolder(otherUser.id!!))
                link =
                    linkRepository.insert(
                        LinkFixture.createRandomLinkOf(
                            ownerId = user.id!!,
                        ),
                    )
                otherUserLink =
                    linkRepository.insert(
                        LinkFixture.createRandomLinkOf(
                            ownerId = otherUser.id!!,
                        ),
                    )
                linkWithoutRequestedFolder =
                    linkRepository.insert(
                        LinkFixture.createRandomLinkOf(
                            ownerId = user.id!!,
                        ),
                    )
                linkWithOtherUserFolderRequest =
                    linkRepository.insert(
                        LinkFixture.createRandomLinkOf(
                            ownerId = user.id!!,
                        ),
                    )
                updateRequest =
                    LinkFixture.createValidUpdateLinkRequest(folderId = folder.id).copy(
                        thumbnailUrl = LinkFixture.validPreviewUrlOf(urlResolver),
                        faviconUrl = LinkFixture.validPreviewUrlOf(urlResolver, kind = "favicon"),
                        isVideo = true,
                    )
                otherUserLinkUpdateRequest = LinkFixture.createValidUpdateLinkRequest()
                missingFolderRequest = LinkFixture.createValidUpdateLinkRequest(folderId = RandomFixture.randomId())
                otherUserFolderRequest = LinkFixture.createValidUpdateLinkRequest(folderId = otherFolder.id)
            }

            When("본인 링크 수정을") {
                val update =
                    suspend {
                        val response = updateLinkService.updateLink(user.id!!, link.id!!, updateRequest)

                        UpdateResult(
                            link = link,
                            request = updateRequest,
                            response = response,
                        )
                    }

                Then("성공한다") {
                    val (link, request, response) = update()
                    val actual = linkRepository.findById(link.id!!)
                    val expectedTags = request.tags.distinct()

                    response.folderId shouldBe request.folderId
                    response.url shouldBe request.url
                    response.title shouldBe request.title
                    response.summary shouldBe request.summary
                    response.memo shouldBe request.memo
                    response.tags shouldBe expectedTags
                    response.thumbnailUrl shouldBe request.thumbnailUrl
                    response.sourceType shouldBe request.sourceType

                    actual shouldNotBe null
                    actual!!.folderId shouldBe request.folderId
                    actual.url shouldBe request.url
                    actual.title shouldBe request.title
                    actual.summary shouldBe request.summary
                    actual.memo shouldBe request.memo
                    actual.tags shouldBe expectedTags
                    actual.thumbnailUrl shouldBe request.thumbnailUrl
                    actual.faviconUrl shouldBe request.faviconUrl
                    actual.isVideo shouldBe request.isVideo
                    actual.sourceType shouldBe request.sourceType
                    actual.favoriteAt shouldBe null
                    actual.updatedAt shouldNotBe link.updatedAt
                }
            }

            When("바로가기로 지정된 링크를 수정하면") {
                val favoritedLink =
                    linkRepository.insert(
                        LinkFixture.createRandomLinkOf(
                            ownerId = user.id!!,
                            favoriteAt = RandomFixture.randomPastInstant(),
                        ),
                    )
                updateLinkService.updateLink(
                    user.id!!,
                    favoritedLink.id!!,
                    LinkFixture.createValidUpdateLinkRequest(),
                )
                val actual = linkRepository.findById(favoritedLink.id!!)

                Then("favoriteAt가 그대로 유지된다") {
                    actual!!.favoriteAt shouldBe favoritedLink.favoriteAt
                }
            }

            When("요청에서 이미지 값과 영상 여부를 생략하면") {
                val imageLink =
                    linkRepository.insert(
                        LinkFixture.createRandomLinkOf(
                            ownerId = user.id!!,
                            thumbnailUrl = RandomFixture.randomPreviewUrl(),
                            faviconUrl = RandomFixture.randomPreviewUrl(kind = "favicon"),
                            isVideo = true,
                        ),
                    )
                val request = LinkFixture.createValidUpdateLinkRequest()
                updateLinkService.updateLink(user.id!!, imageLink.id!!, request)

                Then("저장된 이미지 값은 null, 영상 여부는 false다") {
                    val actual = linkRepository.findById(imageLink.id!!)!!
                    actual.thumbnailUrl shouldBe null
                    actual.faviconUrl shouldBe null
                    actual.isVideo shouldBe false
                }
            }

            When("로그인 사용자가 없으면") {
                val invalidUserId = RandomFixture.randomId()
                val request = LinkFixture.createValidUpdateLinkRequest()
                val update =
                    suspend {
                        updateLinkService.updateLink(invalidUserId, RandomFixture.randomId(), request)
                    }

                Then("예외를 반환한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.LINK_OWNER_NOT_FOUND.message) {
                        update()
                    }
                }
            }

            When("없는 링크 수정을") {
                val invalidLinkId = RandomFixture.randomId()
                val request = LinkFixture.createValidUpdateLinkRequest()
                val update =
                    suspend {
                        updateLinkService.updateLink(user.id!!, invalidLinkId, request)
                    }

                Then("예외를 반환한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.LINK_NOT_FOUND.message) {
                        update()
                    }
                }
            }

            When("다른 사용자의 링크 수정을") {
                val update =
                    suspend {
                        updateLinkService.updateLink(user.id!!, otherUserLink.id!!, otherUserLinkUpdateRequest)
                    }

                Then("예외를 반환한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.LINK_DIFFERENT_OWNER.message) {
                        update()
                    }
                }
            }

            When("요청 폴더가 없으면") {
                val update =
                    suspend {
                        updateLinkService.updateLink(user.id!!, linkWithoutRequestedFolder.id!!, missingFolderRequest)
                    }

                Then("예외를 반환한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.LINK_FOLDER_NOT_FOUND.message) {
                        update()
                    }
                }
            }

            When("요청 폴더가 다른 사용자 소유이면") {
                val update =
                    suspend {
                        updateLinkService.updateLink(user.id!!, linkWithOtherUserFolderRequest.id!!, otherUserFolderRequest)
                    }

                Then("예외를 반환한다") {
                    shouldThrowWithMessage<BusinessException>(ErrorCode.LINK_FOLDER_ACCESS_DENIED.message) {
                        update()
                    }
                }
            }
        }

        Given("본문 행이 있는 링크") {
            lateinit var user: User
            lateinit var link: Link
            lateinit var content: LinkContent

            beforeTest {
                user = userRepository.insert(UserFixture.createRandomValidUser())
                link = linkRepository.insert(LinkFixture.createRandomLinkOf(ownerId = user.id!!))
                content = LinkContentFixture.insertCollected(linkContentRepository, linkId = link.id!!, sourceUrl = link.url)
            }

            When("URL을 바꾸면") {
                val newUrl = RandomFixture.randomUrl()
                val update =
                    suspend {
                        updateLinkService.updateLink(
                            user.id!!,
                            link.id!!,
                            LinkFixture.createUpdateLinkRequestFrom(link).copy(url = newUrl),
                        )
                    }

                Then("행이 같은 id로 새 URL의 PENDING이 되고 본문·발췌가 비어 있다") {
                    update()
                    val actual = linkContentRepository.findByLinkId(link.id!!)

                    actual shouldNotBe null
                    actual!!.id shouldBe content.id
                    actual.status shouldBe ContentStatus.PENDING
                    actual.sourceUrl shouldBe newUrl
                    actual.page shouldBe null
                    actual.excerpt shouldBe null
                    actual.contentHash shouldBe null
                    actual.collectedAt shouldBe null
                    actual.attempt shouldBe 0
                    actual.claimCount shouldBe 0
                }
            }

            When("제목만 바꾸면") {
                val update =
                    suspend {
                        updateLinkService.updateLink(
                            user.id!!,
                            link.id!!,
                            LinkFixture.createUpdateLinkRequestFrom(link).copy(title = RandomFixture.randomSentenceWithMax(300)),
                        )
                    }

                Then("행이 그대로다") {
                    update()
                    val actual = linkContentRepository.findByLinkId(link.id!!)

                    actual shouldNotBe null
                    actual!!.id shouldBe content.id
                    actual.status shouldBe ContentStatus.COLLECTED
                    actual.sourceUrl shouldBe link.url
                    actual.excerpt shouldBe content.excerpt
                    actual.contentHash shouldBe content.contentHash
                }
            }
        }

        Given("가져오기 대기(BULK) 본문 행이 있는 링크") {
            lateinit var user: User
            lateinit var link: Link

            beforeTest {
                user = userRepository.insert(UserFixture.createRandomValidUser())
                link = linkRepository.insert(LinkFixture.createRandomLinkOf(ownerId = user.id!!))
                linkContentRepository.insertAll(
                    listOf(
                        LinkContentFixture.createPendingContentOf(
                            linkId = link.id!!,
                            sourceUrl = link.url,
                            priority = ContentPriority.BULK,
                        ),
                    ),
                )
            }

            When("URL을 바꾸면") {
                val newUrl = RandomFixture.randomUrl()
                val update =
                    suspend {
                        updateLinkService.updateLink(
                            user.id!!,
                            link.id!!,
                            LinkFixture.createUpdateLinkRequestFrom(link).copy(url = newUrl),
                        )
                    }

                Then("행이 새 URL의 INTERACTIVE가 된다") {
                    update()
                    val actual = linkContentRepository.findByLinkId(link.id!!)

                    actual shouldNotBe null
                    actual!!.sourceUrl shouldBe newUrl
                    actual.priority shouldBe ContentPriority.INTERACTIVE
                }
            }
        }

        Given("본문 행이 없는(기능 이전) 링크") {
            lateinit var user: User
            lateinit var link: Link

            beforeTest {
                user = userRepository.insert(UserFixture.createRandomValidUser())
                link = linkRepository.insert(LinkFixture.createRandomLinkOf(ownerId = user.id!!))
            }

            When("URL을 바꾸면") {
                val newUrl = RandomFixture.randomUrl()
                val update =
                    suspend {
                        updateLinkService.updateLink(
                            user.id!!,
                            link.id!!,
                            LinkFixture.createUpdateLinkRequestFrom(link).copy(url = newUrl),
                        )
                    }

                Then("새 PENDING 행이 생긴다") {
                    update()
                    val actual = linkContentRepository.findByLinkId(link.id!!)

                    actual shouldNotBe null
                    actual!!.status shouldBe ContentStatus.PENDING
                    actual.sourceUrl shouldBe newUrl
                }
            }
        }

        Given("다른 트랜잭션이 링크를 잠그고 즐겨찾기를 쓰는 중인 커밋된 링크") {
            When("제목을 수정하면") {
                val newTitle = RandomFixture.randomSentenceWithMax(300)
                val updateDuringFavoriteWrite =
                    suspend {
                        CoroutineScope(Dispatchers.IO)
                            .async {
                                val (owner, link) =
                                    tx.required {
                                        val owner = userRepository.insert(UserFixture.createRandomValidUser())
                                        owner to
                                            linkRepository.insert(LinkFixture.createRandomLinkOf(ownerId = owner.id!!, favoriteAt = null))
                                    }
                                try {
                                    runWhileLinkLocked(
                                        tx = tx,
                                        holdLock = {
                                            linkRepository.findByIdForUpdate(link.id!!)
                                            linkRepository.updateFavorite(linkId = link.id!!, favoriteAt = Clock.System.now())
                                        },
                                        action = {
                                            updateLinkService.updateLink(
                                                owner.id!!,
                                                link.id!!,
                                                LinkFixture.createUpdateLinkRequestFrom(link).copy(title = newTitle),
                                            )
                                        },
                                    )
                                    tx.readOnly { linkRepository.findById(link.id!!)!! }
                                } finally {
                                    tx.required { userRepository.deleteById(owner.id!!) }
                                }
                            }.await()
                    }

                Then("잠금을 기다린 뒤 먼저 커밋된 즐겨찾기를 지키고 제목만 바꾼다") {
                    val actual = updateDuringFavoriteWrite()

                    actual.favoriteAt.shouldNotBeNull()
                    actual.title shouldBe newTitle
                }
            }
        }

        Given("다른 트랜잭션이 폴더를 잠근 뒤 링크를 잠그고 즐겨찾기를 쓰는 중인 커밋된 링크") {
            When("링크를 그 폴더로 수정하면") {
                val updateDuringFolderLock =
                    suspend {
                        CoroutineScope(Dispatchers.IO)
                            .async {
                                val (folder, link) =
                                    tx.required {
                                        val owner = userRepository.insert(UserFixture.createRandomValidUser())
                                        folderRepository.insert(FolderFixture.createValidUnsharedFolder(owner.id!!)) to
                                            linkRepository.insert(LinkFixture.createRandomLinkOf(ownerId = owner.id!!, favoriteAt = null))
                                    }
                                try {
                                    runWhileFolderLocked(
                                        tx = tx,
                                        holdLock = { folderRepository.findAllByIdsForUpdate(listOf(folder.id!!)) },
                                        afterWaiting = {
                                            linkRepository.findByIdForUpdate(link.id!!)
                                            linkRepository.updateFavorite(linkId = link.id!!, favoriteAt = Clock.System.now())
                                        },
                                        action = {
                                            updateLinkService.updateLink(
                                                link.ownerId,
                                                link.id!!,
                                                LinkFixture.createUpdateLinkRequestFrom(link).copy(folderId = folder.id),
                                            )
                                        },
                                    )
                                    folder to tx.readOnly { linkRepository.findById(link.id!!)!! }
                                } finally {
                                    tx.required { userRepository.deleteById(link.ownerId) }
                                }
                            }.await()
                    }

                Then("폴더 잠금을 먼저 기다려 교착 없이 즐겨찾기를 지키고 폴더를 옮긴다") {
                    val (folder, actual) = updateDuringFolderLock()

                    actual.folderId shouldBe folder.id
                    actual.favoriteAt.shouldNotBeNull()
                }
            }
        }
    })

private data class UpdateResult(
    val link: Link,
    val request: UpdateLinkRequest,
    val response: UpdateLinkResponse,
)
