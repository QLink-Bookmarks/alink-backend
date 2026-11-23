package com.qlink.folder.service

import com.qlink.common.scroll.Base64CursorCodec
import com.qlink.common.scroll.ScrollRequest
import com.qlink.folder.domain.Folder
import com.qlink.folder.domain.FolderSort
import com.qlink.folder.dto.FolderSearchCursor
import com.qlink.folder.dto.GetFoldersContentResponse
import com.qlink.folder.repository.FolderRepository
import com.qlink.link.repository.LinkRepository
import com.qlink.support.BaseServiceTest
import com.qlink.support.fixture.FolderFixture
import com.qlink.support.fixture.LinkFixture
import com.qlink.support.fixture.RandomFixture
import com.qlink.support.fixture.UserFixture
import com.qlink.support.koinGet
import com.qlink.user.domain.User
import com.qlink.user.repository.UserRepository
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
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
            lateinit var keyword: String

            beforeTest {
                user = userRepository.insert(UserFixture.createRandomValidUser())
                val otherUser = userRepository.insert(UserFixture.createRandomValidUser())
                keyword = RandomFixture.randomFixedSentence(8).lowercase()
                val names =
                    listOf(
                        keyword,
                        "$keyword a",
                        "$keyword b",
                        "$keyword ${RandomFixture.randomFixedSentence(10)}",
                        "$keyword ${RandomFixture.randomFixedSentence(30)}",
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

            for (hasQuery in listOf(true, false)) {
                When("검색어 유무=$hasQuery 로 SIMILAR 정렬의 다음 페이지를 조회하면") {
                    val get =
                        suspend {
                            val contents = mutableListOf<GetFoldersContentResponse>()
                            val seenCursors = mutableSetOf<String>()
                            var cursor: String? = null
                            var pages = 0
                            do {
                                val page =
                                    service.getFolders(
                                        loginId = user.id!!,
                                        query = if (hasQuery) keyword else null,
                                        order = "similar",
                                        scrollRequest = ScrollRequest(cursor = cursor, size = 2),
                                    )
                                contents.addAll(page.contents)
                                page.hasNext shouldBe (page.nextCursor != null)
                                page.nextCursor?.let { nextCursor ->
                                    withClue("요청 커서=$cursor, 반복된 커서=$nextCursor, 응답 ID=${page.contents.map { it.id }}") {
                                        seenCursors.add(nextCursor) shouldBe true
                                    }
                                    if (!hasQuery) {
                                        val value = Base64CursorCodec.decode<FolderSearchCursor>(nextCursor).value
                                        value.id shouldBe page.contents.last().id
                                        value.score shouldBe 0.0
                                    }
                                }
                                cursor = page.nextCursor
                                pages++
                            } while (cursor != null && pages < 4)
                            pages shouldBe 3
                            contents
                        }

                    Then("점수와 id 순서로 중복·누락 없이 반환하고 저장된 링크 수를 유지한다") {
                        val actual = get()
                        val storedFolders = folderRepository.findAllByIds(folders.map { it.id!! })
                        val unpaged =
                            folderRepository.search(
                                ownerId = user.id!!,
                                query = if (hasQuery) keyword else null,
                                sort = FolderSort.of(orderKey = "similarity", order = null),
                                cursor = null,
                                size = 10,
                            )
                        if (hasQuery) {
                            unpaged
                                .map { it.score }
                                .distinct()
                                .size
                                .shouldBeGreaterThan(1)
                        } else {
                            unpaged.map { it.score }.distinct() shouldBe listOf(0.0)
                            actual.map { it.id } shouldBe storedFolders.map { it.id!! }.sortedDescending()
                        }
                        actual.map { it.id } shouldBe unpaged.map { it.id }
                        actual.map { it.id }.distinct() shouldHaveSize storedFolders.size
                        actual.map { it.id }.toSet() shouldBe storedFolders.map { it.id }.toSet()
                        for (folder in actual) {
                            val links = linkRepository.findAllByFolderIds(listOf(folder.id))
                            folder.linkCounts shouldBe links.size
                            val counts = unpaged.single { it.id == folder.id }
                            counts.linkCounts shouldBe links.size
                            counts.myLinkCounts shouldBe links.count { it.ownerId == user.id }
                        }
                    }
                }
            }
        }

        Given("PostgreSQL의 유사도 점수가 같고 짧은 소수 문자열로 변환하면 값이 달라지는 폴더 두 개") {
            val keyword = "archive"
            lateinit var user: User
            lateinit var folderIds: List<Long>

            beforeTest {
                if (it.type != io.kotest.core.test.TestType.Test) return@beforeTest
                user = userRepository.insert(UserFixture.createRandomValidUser())
                folderIds =
                    listOf("$keyword 1", "$keyword 2").map { name ->
                        folderRepository.insert(FolderFixture.createFolderWith(ownerId = user.id!!, name = name)).id!!
                    }
            }

            When("검색어와 함께 한 건씩 SIMILAR 커서를 따라가면") {
                val getPage =
                    suspend { cursor: String? ->
                        service.getFolders(
                            loginId = user.id!!,
                            query = keyword,
                            order = "similar",
                            scrollRequest = ScrollRequest(cursor = cursor, size = 1),
                        )
                    }

                Then("동점의 DB 점수가 커서에 정확히 보존되고 다음 ID부터 이어진다") {
                    val expected = readFolderScores(ownerId = user.id!!, query = keyword)
                    expected.map { it.first } shouldBe folderIds.sortedDescending()
                    val score = expected.first().second
                    score shouldBe expected.last().second
                    score shouldNotBe score.toFloat().toString().toDouble()

                    val first = getPage(null)
                    first.contents.map { it.id } shouldBe listOf(expected.first().first)
                    first.hasNext shouldBe true
                    val cursor = first.nextCursor.shouldNotBeNull()
                    val value = Base64CursorCodec.decode<FolderSearchCursor>(cursor).value
                    (value.id to value.score) shouldBe expected.first()

                    val second = getPage(cursor)
                    val secondIds = second.contents.map { it.id }
                    withClue("요청 커서=$cursor, 응답 커서=${second.nextCursor}, 응답 ID=$secondIds") {
                        second.nextCursor shouldBe null
                    }
                    secondIds shouldBe listOf(expected.last().first)
                    second.hasNext shouldBe false
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
