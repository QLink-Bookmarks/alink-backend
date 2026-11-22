package com.qlink.folder.service

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.scroll.Base64CursorCodec
import com.qlink.common.scroll.ScrollRequest
import com.qlink.common.search.SortDirection
import com.qlink.folder.domain.Folder
import com.qlink.folder.repository.FolderRepository
import com.qlink.link.domain.Link
import com.qlink.link.domain.LinkOrderKey
import com.qlink.link.dto.LinkSearchCursor
import com.qlink.link.dto.LinkSearchCursorValue
import com.qlink.link.repository.LinkRepository
import com.qlink.link.service.GetLinksService
import com.qlink.support.BaseServiceTest
import com.qlink.support.fixture.FolderFixture
import com.qlink.support.fixture.LinkFixture
import com.qlink.support.fixture.PublicFolderFixture
import com.qlink.support.fixture.RandomFixture
import com.qlink.support.fixture.UserFixture
import com.qlink.support.fixture.withPublication
import com.qlink.support.getPublicLinks
import com.qlink.support.koinGet
import com.qlink.support.toPublicListItem
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
        val folders = koinGet<FolderRepository>()
        val links = koinGet<LinkRepository>()
        val users = koinGet<UserRepository>()
        val setFolderPrivacy = koinGet<SetFolderPrivacyService>()

        Given("활성 공개 폴더에 생성 시각이 같은 링크와 다른 시각의 링크가 있을 때") {
            lateinit var folder: Folder
            lateinit var other: Folder
            lateinit var savedLinks: List<Link>
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
            }

            When("대상 폴더의 링크를 조회하면") {
                Then("현재 소속 공개 링크의 원문 표시 필드만 반환한다") {
                    val result = service.getPublicLinks(folder.id!!, null, null, ScrollRequest())
                    val stored = savedLinks.map { links.findById(it.id!!)!! }

                    result.contents.toSet() shouldBe
                        stored.map { it.toPublicListItem() }.toSet()
                    result.contents.forEach {
                        Json.encodeToJsonElement(it).jsonObject.keys shouldBe
                            setOf("id", "title", "url", "thumbnailUrl", "faviconUrl", "isVideo", "contentExcerpt")
                    }
                    result.hasNext shouldBe false
                }
            }

            When("회원과 외부인이 공개 필터로 각각 조회하면") {
                Then("각 요청의 공개 필터와 권한을 페이지 경계 전에 적용한다") {
                    val folderId = folder.id!!

                    suspend fun readMember(
                        viewerId: Long,
                        isPublic: Boolean?,
                        cursor: String? = null,
                        query: String? = null,
                    ) = service
                        .getMemberLinksOrNull(
                            viewerId = viewerId,
                            query = query,
                            folderId = folderId,
                            isFavorite = null,
                            orderKey = null,
                            order = null,
                            scrollRequest = ScrollRequest(cursor = cursor, size = size),
                            isPublic = isPublic,
                        ).shouldNotBeNull()

                    suspend fun readPublic(
                        isPublic: Boolean,
                        cursor: String? = null,
                    ) = service.getPublicLinks(
                        folderId = folderId,
                        query = null,
                        isFavorite = null,
                        orderKey = null,
                        order = null,
                        scrollRequest = ScrollRequest(cursor = cursor, size = size),
                        isPublic = isPublic,
                    )

                    val ownerId = folder.ownerId
                    val ownerPage = readMember(viewerId = ownerId, isPublic = true)
                    val ownerCursor = ownerPage.nextCursor.shouldNotBeNull()
                    val ownerNext = readMember(viewerId = ownerId, isPublic = true, cursor = ownerCursor)
                    val publicIds = savedLinks.map { it.id }.toSet()
                    (ownerPage.contents + ownerNext.contents).forEach {
                        (it.id in publicIds) shouldBe true
                        it.isPublic shouldBe (links.findById(it.id)?.turnsPublicAt != null)
                    }
                    val private = readMember(viewerId = ownerId, isPublic = false)
                    private.hasNext shouldBe false
                    private.contents.single().isPublic shouldBe false
                    val query = savedLinks.first().title
                    val matching = readMember(viewerId = ownerId, isPublic = true, query = query)
                    matching.contents.single().id shouldBe savedLinks.first().id
                    val excluded = readMember(viewerId = ownerId, isPublic = false, query = query)
                    excluded.isEmpty shouldBe true

                    val guest = readPublic(isPublic = true)
                    guest.contents.single().id shouldBe ownerPage.contents.single().id
                    val guestCursor = guest.nextCursor.shouldNotBeNull()
                    val guestNext = readPublic(isPublic = true, cursor = guestCursor)
                    guestNext.contents.single().id shouldBe ownerNext.contents.single().id
                    val empty = readPublic(isPublic = false)
                    empty.contents shouldBe emptyList()
                    empty.isEmpty shouldBe true
                    empty.hasNext shouldBe false
                    empty.nextCursor shouldBe null

                    val guestWithMemberCursor = readPublic(isPublic = true, cursor = ownerCursor)
                    guestWithMemberCursor shouldBe guestNext
                    readPublic(isPublic = false, cursor = guestCursor).contents shouldBe emptyList()
                    readMember(viewerId = ownerId, isPublic = false, cursor = ownerCursor)
                        .contents
                        .all { !it.isPublic } shouldBe true
                    shouldThrowWithMessage<BusinessException>(ErrorCode.COMMON_CURSOR_MALFORMED.message) {
                        readMember(viewerId = ownerId, isPublic = true, cursor = ownerCursor + RandomFixture.randomId())
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
                    ) = service
                        .getMemberLinksOrNull(
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
                        ).shouldNotBeNull()

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
                    val filtered = read(id = savedLinks.first().id!!, folderId = folder.id, isPublic = true)
                    filtered.contents.all { it.isPublic } shouldBe true
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
                        listOf(retained.toPublicListItem())
                }
            }

            When("철회하기 전에 받은 커서를 같은 주소에서 재사용하면") {
                Then("철회 중에는 404이고 재공개 뒤에도 이전 링크가 노출되지 않는다") {
                    val folderId = folder.id!!
                    val first = service.getPublicLinks(folderId, null, null, ScrollRequest(size = size))
                    val next = ScrollRequest(cursor = first.nextCursor.shouldNotBeNull(), size = size)
                    setFolderPrivacy.setPrivacy(
                        loginId = folder.ownerId,
                        folderId = folderId,
                        isPublic = false,
                    )
                    shouldThrowWithMessage<BusinessException>(ErrorCode.FOLDER_NOT_FOUND.message) {
                        service.getPublicLinks(folderId, null, null, next)
                    }
                    setFolderPrivacy.setPrivacy(
                        loginId = folder.ownerId,
                        folderId = folderId,
                        isPublic = true,
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
                            expected.map { it.toPublicListItem() }
                        third.hasNext shouldBe false
                        third.nextCursor shouldBe null
                    }
                }
            }

            When("다른 공개 폴더의 조회에 받은 커서의 경계를 수정해서 쓰면") {
                Then("요청한 폴더에서 현재 공개된 링크만 반환한다") {
                    val first = service.getPublicLinks(folder.id!!, null, null, ScrollRequest(size = size))
                    val cursor = Base64CursorCodec.decode<LinkSearchCursor>(first.nextCursor.shouldNotBeNull())
                    val private = links.insert(LinkFixture.createRandomLinkOf(ownerId = folder.ownerId, folderId = other.id))
                    val modified = cursor.copy(value = cursor.value.copy(id = private.id, createdAt = Clock.System.now()))
                    val page = service.getPublicLinks(other.id!!, null, null, ScrollRequest(cursor = Base64CursorCodec.encode(modified)))

                    val visible = links.findAllByFolderIds(listOf(other.id!!)).single { it.turnsPublicAt != null }
                    page.contents shouldBe listOf(visible.toPublicListItem())
                }
            }

            When("링크 커서에 생성 시각이나 내부 ID가 빠지면") {
                val read: suspend (String) -> Unit = { cursor ->
                    service.getPublicLinks(folder.id!!, null, null, ScrollRequest(cursor = cursor))
                }

                Then("필수 커서 값이 없다고 응답한다") {
                    val first = savedLinks.first()
                    val cursor =
                        LinkSearchCursor(
                            orderKey = LinkOrderKey.CREATED_AT,
                            direction = SortDirection.DESC,
                            value = LinkSearchCursorValue(id = first.id, createdAt = first.createdAt),
                        )

                    for (incomplete in listOf(cursor.value.copy(createdAt = null), cursor.value.copy(id = null))) {
                        shouldThrowWithMessage<BusinessException>(ErrorCode.COMMON_CURSOR_FIELD_MISSING.message) {
                            read(Base64CursorCodec.encode(cursor.copy(value = incomplete)))
                        }
                    }
                }
            }

            When("발급된 커서로 다음 링크를 조회하면") {
                Then("다음 공개 링크를 읽고 커서는 정렬과 페이지 경계만 담는다") {
                    val folderId = folder.id!!
                    val first = service.getPublicLinks(folderId, null, null, ScrollRequest(size = size))
                    val cursor = first.nextCursor.shouldNotBeNull()
                    val second = service.getPublicLinks(folderId, null, null, ScrollRequest(cursor = cursor, size = size))
                    val expected =
                        savedLinks
                            .map { links.findById(it.id!!)!! }
                            .sortedWith(compareByDescending<Link> { it.createdAt }.thenByDescending { it.id })[size]

                    second.contents shouldBe
                        listOf(expected.toPublicListItem())
                    Json.encodeToJsonElement(second.contents.single()).jsonObject.keys shouldBe
                        setOf("id", "title", "url", "thumbnailUrl", "faviconUrl", "isVideo", "contentExcerpt")
                    val boundary =
                        Json
                            .parseToJsonElement(
                                java.util.Base64
                                    .getDecoder()
                                    .decode(cursor)
                                    .decodeToString(),
                            ).jsonObject
                    boundary.keys shouldBe setOf("orderKey", "direction", "value")
                    boundary.getValue("value").jsonObject.keys shouldBe setOf("id", "createdAt")
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

        Given("공개 폴더에 대소문자가 섞인 제목과 제목이 같은 링크가 있을 때") {
            lateinit var folder: Folder
            var outsiderId: Long? = null

            beforeTest {
                val ownerId = users.insert(UserFixture.createRandomValidUser()).id!!
                outsiderId = users.insert(UserFixture.createRandomValidUser()).id!!
                folder = folders.insert(FolderFixture.createValidUnsharedFolder(ownerId).withPublication())
                val title = RandomFixture.randomFixedSentence(20)
                for ((prefix, published) in listOf("Z" to true, "a" to true, "a" to true, "M" to false, "b" to true)) {
                    links.insert(
                        LinkFixture.createRandomLinkOf(
                            ownerId = ownerId,
                            folderId = folder.id,
                            title = "$prefix $title",
                            isPublic = published,
                        ),
                    )
                }
            }

            When("외부인이 제목순으로 한 링크씩 조회하면") {
                Then("현재 공개 행을 회원과 동일한 제목 및 ID 순서로 양방향에서 모두 읽는다") {
                    val folderId = folder.id!!
                    val stored = links.findAllByFolderIds(listOf(folderId)).filter { it.turnsPublicAt != null }
                    val ascending = stored.sortedWith(compareBy({ it.title.lowercase() }, { it.id }))

                    suspend fun readMember(
                        viewerId: Long,
                        direction: SortDirection,
                        cursor: String? = null,
                    ) = service.getMemberLinksOrNull(
                        viewerId = viewerId,
                        query = null,
                        folderId = folderId,
                        isFavorite = null,
                        orderKey = "title",
                        order = direction.name,
                        scrollRequest = ScrollRequest(cursor = cursor, size = 1),
                        isPublic = true,
                    )

                    for ((direction, expected) in listOf(SortDirection.ASC to ascending, SortDirection.DESC to ascending.reversed())) {
                        val memberFirst = readMember(viewerId = folder.ownerId, direction = direction).shouldNotBeNull()
                        for (viewerId in listOf(null, outsiderId)) {
                            var cursor: String? = null
                            for ((index, link) in expected.withIndex()) {
                                viewerId?.let { readMember(viewerId = it, direction = direction, cursor = cursor) shouldBe null }
                                val page =
                                    service.getPublicLinks(
                                        folderId = folderId,
                                        query = null,
                                        isFavorite = null,
                                        orderKey = "title",
                                        order = direction.name,
                                        scrollRequest = ScrollRequest(cursor = cursor, size = 1),
                                        isPublic = true,
                                    )
                                page.contents shouldBe listOf(link.toPublicListItem())
                                page.hasNext shouldBe (index < expected.lastIndex)
                                cursor = page.nextCursor
                                if (page.hasNext) {
                                    Base64CursorCodec.decode<LinkSearchCursor>(cursor.shouldNotBeNull()) shouldBe
                                        LinkSearchCursor(
                                            orderKey = LinkOrderKey.TITLE,
                                            direction = direction,
                                            value = LinkSearchCursorValue(id = link.id, title = link.title),
                                        )
                                    if (index == 0) cursor shouldBe memberFirst.nextCursor
                                } else {
                                    cursor shouldBe null
                                }
                            }
                        }
                    }
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
