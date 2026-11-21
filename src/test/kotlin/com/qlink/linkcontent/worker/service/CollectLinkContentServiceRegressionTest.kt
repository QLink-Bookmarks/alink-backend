package com.qlink.linkcontent.worker.service

import com.qlink.link.domain.Link
import com.qlink.link.repository.LinkRepository
import com.qlink.linkcontent.domain.CollectOutcome
import com.qlink.linkcontent.domain.ContentStatus
import com.qlink.linkcontent.domain.LinkContent
import com.qlink.linkcontent.repository.LinkContentRepository
import com.qlink.support.BaseServiceTest
import com.qlink.support.dbNow
import com.qlink.support.fixture.FakeLinkContentSyncClient
import com.qlink.support.fixture.LinkContentFixture
import com.qlink.support.fixture.LinkFixture
import com.qlink.support.fixture.RandomFixture
import com.qlink.support.fixture.UserFixture
import com.qlink.support.koinGet
import com.qlink.user.repository.UserRepository
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class CollectLinkContentServiceRegressionTest :
    BaseServiceTest({
        val service = koinGet<CollectLinkContentService>()
        val client = koinGet<FakeLinkContentSyncClient>()
        val userRepository = koinGet<UserRepository>()
        val linkRepository = koinGet<LinkRepository>()
        val linkContentRepository = koinGet<LinkContentRepository>()

        suspend fun insertLink(): Link {
            val user = userRepository.insert(UserFixture.createRandomValidUser())
            return linkRepository.insert(LinkFixture.createRandomLinkOf(ownerId = user.id!!))
        }

        suspend fun insertDueContentOf(
            link: Link,
            now: Instant,
            sourceUrl: String = link.url,
        ): LinkContent {
            linkContentRepository.insertAll(
                listOf(
                    LinkContentFixture.createPendingContentOf(
                        linkId = link.id!!,
                        sourceUrl = sourceUrl,
                        nextAttemptAt = now - RandomFixture.randomInt(1, 60).minutes,
                    ),
                ),
            )
            return linkContentRepository.findByLinkId(link.id).shouldNotBeNull()
        }

        Given("가짜 클라이언트의 호출 중 훅으로 끼어드는 변경") {
            lateinit var now: Instant
            lateinit var link: Link
            lateinit var content: LinkContent
            lateinit var text: String

            beforeEach {
                client.reset()
                now = dbNow()
                link = insertLink()
                content = insertDueContentOf(link = link, now = now)
                text = LinkContentFixture.randomBodyText()
                client.enqueue(LinkContentFixture.collectedResult(text = text))
            }

            When("같은 URL을 재선점한 행이 PENDING일 때 이전 실행이 성공 결과를 먼저 저장하면") {
                val collect =
                    suspend {
                        client.onInvoke = {
                            val reclaimedAt = now + LinkContent.STALE_AFTER + RandomFixture.randomInt(1, 60).minutes
                            linkContentRepository
                                .claim(contentId = content.id!!, staleAfter = LinkContent.STALE_AFTER, now = reclaimedAt)
                                .shouldNotBeNull()
                        }
                        service.collect(contentId = content.id!!, now = now)
                    }

                Then("이전 실행의 본문과 COLLECTED 상태가 저장되고 재선점으로 증가한 claimCount가 유지된다") {
                    val result = collect()

                    result.outcome shouldBe CollectOutcome.COLLECTED
                    val actual = linkContentRepository.findById(content.id!!).shouldNotBeNull()
                    actual.status shouldBe ContentStatus.COLLECTED
                    actual.sourceUrl shouldBe link.url
                    actual.page?.text shouldBe text
                    actual.claimCount shouldBe content.claimCount + 2
                }
            }
        }
    })
