package com.qlink.ai.worker.service

import com.qlink.ai.domain.DailyUsage
import com.qlink.ai.dto.AiSummaryCompletion
import com.qlink.ai.dto.AiSummaryCompletionResult
import com.qlink.ai.repository.AiJobRepository
import com.qlink.ai.repository.DailyUsageRepository
import com.qlink.common.retry.retryUntilNotNull
import com.qlink.common.transaction.TransactionRunner
import com.qlink.folder.repository.FolderRepository
import com.qlink.foldermember.repository.FolderMemberRepository
import com.qlink.image.service.PreviewImageUrlResolver
import com.qlink.link.domain.LinkStatus
import com.qlink.link.domain.service.LinkAiFolderDomainService
import com.qlink.link.repository.LinkRepository
import com.qlink.notification.service.ScheduleTodoNotificationService
import com.qlink.todo.domain.Todo
import com.qlink.todo.repository.TodoRepository
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.time.Clock

class CompleteAiSummaryService(
    private val tx: TransactionRunner,
    private val aiJobRepository: AiJobRepository,
    private val dailyUsageRepository: DailyUsageRepository,
    private val folderRepository: FolderRepository,
    private val folderMemberRepository: FolderMemberRepository,
    private val linkRepository: LinkRepository,
    private val todoRepository: TodoRepository,
    private val scheduleTodoNotificationService: ScheduleTodoNotificationService,
    private val urlResolver: PreviewImageUrlResolver,
    private val log: Logger,
) {
    suspend fun complete(completion: AiSummaryCompletion): AiSummaryCompletionResult {
        val attempt =
            retryUntilNotNull {
                tx.required {
                    val snapshot = completion.snapshot
                    val linkBeforeLock = linkRepository.findById(snapshot.linkId) ?: return@required CompletionAttempt.Skipped
                    val lockedFolders =
                        folderRepository.findAllByIdsForUpdate(
                            listOfNotNull(linkBeforeLock.folderId, completion.candidateFolderId),
                        )
                    val candidate = lockedFolders.find { it.id == completion.candidateFolderId }
                    val member =
                        candidate?.let {
                            folderMemberRepository.findByFolderIdAndUserIdForUpdate(
                                folderId = it.id!!,
                                userId = linkBeforeLock.ownerId,
                            )
                        }
                    val link = linkRepository.findByIdForUpdate(snapshot.linkId) ?: return@required CompletionAttempt.Skipped
                    if (link.hasFolderChangedSince(linkBeforeLock.folderId)) return@required null
                    val job = aiJobRepository.findById(snapshot.jobId) ?: return@required CompletionAttempt.Skipped
                    val folderId =
                        LinkAiFolderDomainService.resolve(
                            link = link,
                            startedFolderId = snapshot.folderId,
                            candidateFolderId = completion.candidateFolderId,
                            candidate = candidate,
                            member = member,
                        )
                    val response = completion.response
                    aiJobRepository.update(
                        job.complete(
                            responseModelId = completion.responseModelId,
                            response = response.rawResponse,
                            completedAt = Clock.System.now(),
                        ),
                    )
                    val usageDate = LocalDate.now(ZoneOffset.UTC)
                    val usage =
                        dailyUsageRepository.findByUserIdAndProviderIdAndUsageDate(
                            userProviderId = job.userProviderId,
                            modelId = completion.responseModelId,
                            usageDate = usageDate,
                        )
                    val updatedUsage =
                        usage?.addUsage(response.usedTokens)
                            ?: DailyUsage(
                                userProviderId = job.userProviderId,
                                modelId = completion.responseModelId,
                                usageDate = usageDate,
                                requests = 1,
                                tokens = response.usedTokens,
                            )
                    if (usage == null) {
                        dailyUsageRepository.insert(updatedUsage)
                    } else {
                        dailyUsageRepository.update(updatedUsage)
                    }
                    val summarizedLink =
                        link
                            .update(
                                folderId = folderId,
                                url = link.url,
                                title = response.title,
                                summary = response.summary,
                                memo = link.memo,
                                tags = response.tags,
                                thumbnailUrl = link.thumbnailUrl,
                                sourceType = link.sourceType,
                                previewPublicBaseUrl = urlResolver.publicBaseUrl,
                                previewEnvironment = urlResolver.environment,
                                publicationId =
                                    link.resolveUpdatedPublicationId(
                                        sourcePublicId = lockedFolders.find { it.id == link.folderId }?.publicId,
                                        targetPublicId = lockedFolders.find { it.id == folderId }?.publicId,
                                        isPublic = null,
                                    ),
                            ).copyAiStatus(status = LinkStatus.A, workModelId = completion.responseModelId)
                    linkRepository.updateAiResult(
                        linkId = link.id!!,
                        folderId = summarizedLink.folderId,
                        title = summarizedLink.title,
                        summary = summarizedLink.summary,
                        tags = summarizedLink.tags,
                        status = summarizedLink.status,
                        workModelId = summarizedLink.workModelId,
                        publicationId = summarizedLink.publicationId,
                    )
                    val todos =
                        response.todos.map {
                            todoRepository.insert(
                                Todo(
                                    linkId = link.id!!,
                                    ownerId = link.ownerId,
                                    title = it.title,
                                    reminderAt = it.reminderAt,
                                ),
                            )
                        }
                    CompletionAttempt.Applied(
                        createdTodos = todos,
                        folderKeptAfterMove = link.hasFolderChangedSince(snapshot.folderId),
                    )
                }
            }
        val applied = attempt as? CompletionAttempt.Applied ?: return AiSummaryCompletionResult()

        return try {
            applied.createdTodos.forEach { scheduleTodoNotificationService.createForTodo(it) }
            rescheduleOtherTodos(linkId = completion.snapshot.linkId, createdTodos = applied.createdTodos)
            AiSummaryCompletionResult(folderKeptAfterMove = applied.folderKeptAfterMove)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.warn("[AI_SUMMARY] Todo notification scheduling failed after completion. jobId=${completion.snapshot.jobId}", error)
            AiSummaryCompletionResult(schedulingFailed = true, folderKeptAfterMove = applied.folderKeptAfterMove)
        }
    }

    private suspend fun rescheduleOtherTodos(
        linkId: Long,
        createdTodos: List<Todo>,
    ) {
        val createdTodoIds = createdTodos.map { it.id }

        tx
            .readOnly { todoRepository.findAllByLinkId(linkId) }
            .filterNot { it.id in createdTodoIds }
            .forEach { scheduleTodoNotificationService.replaceForTodo(it) }
    }

    private sealed interface CompletionAttempt {
        data object Skipped : CompletionAttempt

        data class Applied(
            val createdTodos: List<Todo>,
            val folderKeptAfterMove: Boolean,
        ) : CompletionAttempt
    }
}
