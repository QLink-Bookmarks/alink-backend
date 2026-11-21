package com.qlink.ai.worker.service

import com.qlink.ai.domain.AiJobStatus
import com.qlink.ai.dto.AiSummaryCompletion
import com.qlink.ai.repository.AiJobRepository
import com.qlink.ai.repository.AiProviderRepository
import com.qlink.ai.repository.AvailableModelRepository
import com.qlink.ai.repository.DailyUsageRepository
import com.qlink.ai.repository.UserProviderRepository
import com.qlink.folder.repository.FolderRepository
import com.qlink.foldermember.repository.FolderMemberRepository
import com.qlink.image.service.PreviewImageUrlResolver
import com.qlink.link.domain.Link
import com.qlink.link.domain.LinkStatus
import com.qlink.link.repository.LinkRepository
import com.qlink.notification.domain.Notification
import com.qlink.notification.domain.NotificationContext
import com.qlink.notification.repository.NotificationRepository
import com.qlink.notification.service.ScheduleTodoNotificationService
import com.qlink.notification.worker.TaskScheduler
import com.qlink.support.BaseServiceTest
import com.qlink.support.fixture.AiFixture
import com.qlink.support.fixture.FolderFixture
import com.qlink.support.fixture.LinkFixture
import com.qlink.support.fixture.RandomFixture
import com.qlink.support.fixture.TodoFixture
import com.qlink.support.fixture.UserFixture
import com.qlink.support.insertAiContext
import com.qlink.support.koinGet
import com.qlink.todo.repository.TodoRepository
import com.qlink.user.repository.UserRepository
import io.kotest.core.test.TestType
import io.kotest.matchers.shouldBe
import org.slf4j.LoggerFactory

class CompleteAiSummaryServiceRegressionTest :
    BaseServiceTest({
        val userRepository = koinGet<UserRepository>()
        val linkRepository = koinGet<LinkRepository>()
        val folderRepository = koinGet<FolderRepository>()
        val memberRepository = koinGet<FolderMemberRepository>()
        val jobRepository = koinGet<AiJobRepository>()
        val usageRepository = koinGet<DailyUsageRepository>()
        val todoRepository = koinGet<TodoRepository>()
        val notificationRepository = koinGet<NotificationRepository>()
        val resolver = koinGet<PreviewImageUrlResolver>()

        Given("메모와 입력한 액션이 있는 생성 중 링크와 진행 중인 AI 작업") {
            lateinit var link: Link
            lateinit var completion: AiSummaryCompletion

            beforeTest {
                if (it.type != TestType.Test) return@beforeTest
                val userId = userRepository.insert(UserFixture.createRandomValidUser()).id!!
                val (provider, model) =
                    insertAiContext(
                        userId = userId,
                        aiProviderRepository = koinGet<AiProviderRepository>(),
                        availableModelRepository = koinGet<AvailableModelRepository>(),
                        userProviderRepository = koinGet<UserProviderRepository>(),
                    )
                link =
                    linkRepository.insert(
                        LinkFixture.createRandomLinkOf(
                            ownerId = userId,
                            memo = RandomFixture.randomSentenceWithMax(1000),
                            status = LinkStatus.G,
                            workModelId = model.id,
                            thumbnailUrl = LinkFixture.validPreviewUrlOf(resolver),
                        ),
                    )
                val reminderTodo =
                    todoRepository.insert(
                        TodoFixture.createRandomTodoOf(
                            linkId = link.id!!,
                            ownerId = userId,
                            reminderAt = RandomFixture.randomFutureInstantAfterTomorrow(),
                        ),
                    )
                notificationRepository.insert(
                    Notification.todo(todo = reminderTodo, linkTitle = link.title, linkUrl = link.url)!!,
                )
                val job =
                    jobRepository.insert(
                        AiFixture.createPendingAiJobOf(link = link, userProviderId = provider.id!!, modelId = model.id!!),
                    )
                val candidate = folderRepository.insert(FolderFixture.createValidUnsharedFolder(userId))
                completion = AiFixture.createAiSummaryCompletionOf(job = job, link = link, candidateFolderId = candidate.id)
            }

            fun serviceWith(notificationRepository: NotificationRepository): CompleteAiSummaryService =
                CompleteAiSummaryService(
                    tx = koinGet(),
                    aiJobRepository = jobRepository,
                    dailyUsageRepository = usageRepository,
                    folderRepository = folderRepository,
                    folderMemberRepository = memberRepository,
                    linkRepository = linkRepository,
                    todoRepository = todoRepository,
                    scheduleTodoNotificationService =
                        ScheduleTodoNotificationService(
                            tx = koinGet(),
                            notificationRepository = notificationRepository,
                            linkRepository = linkRepository,
                            taskScheduler = koinGet<TaskScheduler>(),
                        ),
                    urlResolver = resolver,
                    log = LoggerFactory.getLogger(CompleteAiSummaryService::class.java),
                )

            When("입력한 액션의 알림 재예약이 실패하면") {
                val failingService =
                    serviceWith(
                        object : NotificationRepository by notificationRepository {
                            override suspend fun deletePendingByContext(
                                context: NotificationContext,
                                contextId: Long,
                            ): List<Long> = throw IllegalStateException("replace failed")
                        },
                    )
                val complete = suspend { failingService.complete(completion) }
                Then("작업과 링크 완료 상태를 보존하고 예약 실패를 반환한다") {
                    val result = complete()
                    result.schedulingFailed shouldBe true
                    jobRepository.findById(completion.snapshot.jobId)!!.status shouldBe AiJobStatus.C
                    linkRepository.findById(link.id!!)!!.status shouldBe LinkStatus.A
                }
            }
        }
    })
