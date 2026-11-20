package com.qlink.folder.service

import com.qlink.common.scroll.Base64CursorCodec
import com.qlink.common.scroll.ScrollRequest
import com.qlink.folder.domain.Folder
import com.qlink.folder.domain.FolderSort
import com.qlink.folder.dto.FolderSearchCursor
import com.qlink.folder.repository.FolderRepository
import com.qlink.link.repository.LinkRepository
import com.qlink.support.BaseServiceTest
import com.qlink.support.fixture.FolderFixture
import com.qlink.support.fixture.LinkFixture
import com.qlink.support.fixture.UserFixture
import com.qlink.support.koinGet
import com.qlink.user.domain.User
import com.qlink.user.repository.UserRepository
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager

class FolderSimilarCursorServiceTest :
    BaseServiceTest({
        val service = koinGet<GetFoldersService>()
        val folderRepository = koinGet<FolderRepository>()
        val linkRepository = koinGet<LinkRepository>()
        val userRepository = koinGet<UserRepository>()

        Given("점수가 같거나 다른 폴더의 SIMILAR 커서 페이지") {
            lateinit var user: User
            lateinit var folders: List<Folder>
            val keyword = "archive"
            val pageSize = 2

            beforeTest {
                user = userRepository.insert(UserFixture.createRandomValidUser())
                val otherUser = userRepository.insert(UserFixture.createRandomValidUser())
                val names =
                    listOf(
                        keyword,
                        "$keyword 1",
                        "$keyword 2",
                        "$keyword 0123456789",
                        "$keyword 012345678901234567890123456789",
                    )
                folders =
                    names.mapIndexed { index, name ->
                        val folder = folderRepository.insert(FolderFixture.createFolderWith(ownerId = user.id!!, name = name))
                        repeat(index % 3) {
                            linkRepository.insert(LinkFixture.createRandomLinkOf(ownerId = user.id!!, folderId = folder.id))
                        }
                        linkRepository.insert(LinkFixture.createRandomLinkOf(ownerId = otherUser.id!!, folderId = folder.id))
                        folder
                    }
                folderRepository.insert(FolderFixture.createFolderWith(ownerId = otherUser.id!!, name = keyword))
            }

            for (query in listOf(keyword, null)) {
                When("${if (query == null) "검색어 없이" else "검색어와 함께"} SIMILAR 커서를 끝까지 따라가면") {
                    val getPage =
                        suspend { cursor: String? ->
                            service.getFolders(
                                loginId = user.id!!,
                                query = query,
                                order = "similar",
                                scrollRequest = ScrollRequest(cursor = cursor, size = pageSize),
                            )
                        }

                    Then("점수와 id 순서로 중복·누락 없이 반환하고 저장된 링크 수를 유지한다") {
                        val storedFolders = folderRepository.findAllByIds(folders.map { it.id!! })
                        val expected =
                            if (query == null) {
                                storedFolders.map { it.id!! }.sortedDescending().map { it to 0.0 }
                            } else {
                                readFolderScores(ownerId = user.id!!, query = query)
                            }
                        expected.map { it.first }.toSet() shouldBe storedFolders.map { it.id }.toSet()
                        if (query != null) {
                            val boundaryScore = expected[pageSize - 1].second
                            boundaryScore shouldBe expected[pageSize].second
                            boundaryScore shouldNotBe boundaryScore.toFloat().toString().toDouble()
                        }
                        val unpaged =
                            folderRepository.search(
                                ownerId = user.id!!,
                                query = query,
                                sort = FolderSort.of(orderKey = "similarity", order = null),
                                cursor = null,
                                size = 10,
                            )
                        val seenCursors = mutableSetOf<String>()
                        val actual = mutableListOf<Long>()
                        val expectedPages = expected.chunked(pageSize)
                        var cursor: String? = null
                        var pageIndex = 0
                        do {
                            val page = getPage(cursor)
                            val ids = page.contents.map { it.id }
                            val nextCursor = page.nextCursor
                            nextCursor?.let {
                                withClue("요청 커서=$cursor, 반복된 커서=$it, 응답 ID=$ids") {
                                    seenCursors.add(it) shouldBe true
                                }
                            }
                            val expectedPage = expectedPages[pageIndex]
                            ids shouldBe expectedPage.map { it.first }
                            page.hasNext shouldBe (pageIndex < expectedPages.lastIndex)
                            if (page.hasNext) {
                                val value = Base64CursorCodec.decode<FolderSearchCursor>(nextCursor.shouldNotBeNull()).value
                                value.id shouldBe ids.last()
                                (value.id to value.score) shouldBe expectedPage.last()
                            } else {
                                nextCursor shouldBe null
                            }
                            actual += ids
                            cursor = nextCursor
                            pageIndex++

                            for (folder in page.contents) {
                                val links = linkRepository.findAllByFolderIds(listOf(folder.id))
                                folder.linkCounts shouldBe links.size
                                val counts = unpaged.single { it.id == folder.id }
                                counts.linkCounts shouldBe links.size
                                counts.myLinkCounts shouldBe links.count { it.ownerId == user.id }
                            }
                        } while (cursor != null)
                        actual shouldBe expected.map { it.first }
                        unpaged.map { it.id to it.score } shouldBe expected
                    }
                }
            }
        }
    })

private fun readFolderScores(
    ownerId: Long,
    query: String,
): List<Pair<Long, Double>> =
    TransactionManager
        .current()
        .exec(
            """
            SELECT id, CAST(public.bigm_similarity(name, ?) AS DOUBLE PRECISION) AS score
            FROM folders
            WHERE owner_id = ? AND lower(name) LIKE ?
            ORDER BY score DESC, id DESC
            """.trimIndent(),
            args = listOf(TextColumnType() to query, LongColumnType() to ownerId, TextColumnType() to "%$query%"),
        ) { rows ->
            buildList {
                while (rows.next()) {
                    add(rows.getLong("id") to rows.getDouble("score"))
                }
            }
        }.shouldNotBeNull()
