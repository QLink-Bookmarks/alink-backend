package com.qlink.ai.worker.service

import com.qlink.ai.client.AiClientRouter
import com.qlink.ai.client.AiSummaryClientRequest
import com.qlink.ai.client.AiSummaryClientResponse
import com.qlink.ai.domain.AiJob
import com.qlink.ai.domain.AiProvider
import com.qlink.ai.domain.AvailableModel
import com.qlink.ai.domain.DailyUsage
import com.qlink.ai.domain.UserProvider
import com.qlink.ai.domain.service.AiModelAvailabilityDomainService
import com.qlink.ai.dto.AiSummaryCompletion
import com.qlink.ai.dto.AiSummarySnapshot
import com.qlink.ai.repository.AiJobRepository
import com.qlink.ai.repository.AiProviderRepository
import com.qlink.ai.repository.AvailableModelRepository
import com.qlink.ai.repository.DailyUsageRepository
import com.qlink.ai.repository.UserProviderRepository
import com.qlink.ai.worker.DEFAULT_FAILURE_MESSAGE
import com.qlink.ai.worker.catchingFailure
import com.qlink.ai.worker.failureSummary
import com.qlink.auth.domain.Role
import com.qlink.common.crypto.ApiKeyCipher
import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.error.requireTrue
import com.qlink.common.transaction.TransactionRunner
import com.qlink.folder.repository.FolderRepository
import com.qlink.link.repository.LinkRepository
import org.slf4j.Logger
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.time.Clock

class ProcessAiSummaryService(
    private val tx: TransactionRunner,
    private val aiJobRepository: AiJobRepository,
    private val userProviderRepository: UserProviderRepository,
    private val availableModelRepository: AvailableModelRepository,
    private val aiProviderRepository: AiProviderRepository,
    private val dailyUsageRepository: DailyUsageRepository,
    private val folderRepository: FolderRepository,
    private val linkRepository: LinkRepository,
    private val completeAiSummaryService: CompleteAiSummaryService,
    private val failAiSummaryService: FailAiSummaryService,
    private val aiClientRouter: AiClientRouter,
    private val apiKeyCipher: ApiKeyCipher,
    private val log: Logger,
) {
    suspend fun proceed(
        jobId: Long,
        onResult: (Outcome) -> Unit = {},
    ) {
        val isStarted = tx.required { aiJobRepository.markStarted(aiJobId = jobId, staleAfter = AiJob.STALE_AFTER) }
        if (!isStarted) {
            log.info("[WORKER] Job is not runnable. jobId=$jobId")
            onResult(Outcome.SKIPPED)
            return
        }

        val context = loadContext(jobId) ?: return onResult(Outcome.ABORTED)

        log.info(
            "[WORKER] Job Context has been loaded as jobId=$jobId, userProvider=${context.userProvider.id}, provider=${context.provider.type}, requestModel=${context.requestModel.model}",
        )

        catchingFailure { summarize(context).getOrThrow() }
            .onSuccess { result ->
                if (result == null) {
                    log.warn("AI summary failed. jobId={}", jobId)
                    fail(jobId = jobId, reason = DEFAULT_FAILURE_MESSAGE, onResult = onResult)
                    return
                }

                catchingFailure {
                    completeAiSummaryService.complete(
                        AiSummaryCompletion(
                            snapshot = context.snapshot,
                            responseModelId = result.model.id!!,
                            candidateFolderId =
                                context.fixedFolderId
                                    ?: result.response.folderId?.takeIf { it in context.selectableFolderIds },
                            response = result.response,
                        ),
                    )
                }.onSuccess { completionResult ->
                    log.info(
                        "[WORKER] Job has been successful for job=$jobId, link=${context.snapshot.linkId}, folderKeptAfterMove=${completionResult.folderKeptAfterMove}",
                    )
                    onResult(if (completionResult.schedulingFailed) Outcome.ERROR else Outcome.COMPLETED)
                }.onFailure {
                    log.warn("[WORKER] AI summary completion failed. jobId=$jobId", it)
                    fail(jobId = jobId, reason = it.failureSummary(), onResult = onResult)
                }
            }.onFailure {
                log.warn("AI summary failed. jobId={}", context.job.id, it)
                fail(jobId = context.job.id!!, reason = it.failureSummary(), onResult = onResult)
            }
    }

    private suspend fun loadContext(jobId: Long): AiSummaryContext? =
        tx.readOnly {
            val job =
                aiJobRepository
                    .findById(jobId)
                    ?.also { log.info("[WORKER] Job has been loaded. jobId=$jobId") }
                    ?: run {
                        log.warn("[WORKER] Job load failed. jobId=$jobId")
                        return@readOnly null
                    }
            val userProvider =
                userProviderRepository
                    .findById(job.userProviderId)
                    ?.also {
                        log.info(
                            "[WORKER] User provider has been loaded. jobId=$jobId, userProviderId=${job.userProviderId}",
                        )
                    }
                    ?: run {
                        log.warn("[WORKER] User provider load failed. jobId=$jobId, userProviderId=${job.userProviderId}")
                        return@readOnly null
                    }
            val requestModel =
                availableModelRepository
                    .findById(job.requestModelId)
                    ?.also {
                        log.info(
                            "[WORKER] Request model has been loaded. jobId=$jobId, requestModelId=${job.requestModelId}",
                        )
                    }
                    ?: run {
                        log.warn("[WORKER] Request model load failed. jobId=$jobId, requestModelId=${job.requestModelId}")
                        return@readOnly null
                    }
            val provider =
                aiProviderRepository
                    .findById(userProvider.providerId)
                    ?.also {
                        log.info(
                            "[WORKER] AI provider has been loaded. jobId=$jobId, providerId=${userProvider.providerId}",
                        )
                    }
                    ?: run {
                        log.warn("[WORKER] AI provider load failed. jobId=$jobId, providerId=${userProvider.providerId}")
                        return@readOnly null
                    }
            val link =
                linkRepository
                    .findById(job.linkId)
                    ?.also { log.info("[WORKER] Link has been loaded. jobId=$jobId, linkId=${job.linkId}") }
                    ?: run {
                        log.warn("[WORKER] Link load failed. jobId=$jobId, linkId=${job.linkId}")
                        return@readOnly null
                    }
            val apiKey =
                apiKeyCipher
                    .decrypt(userProvider.apiKey)
                    ?.also {
                        log.info(
                            "[WORKER] User provider API key has been decrypted. jobId=$jobId, userProviderId=${userProvider.id}",
                        )
                    }
                    ?: run {
                        log.warn("[WORKER] User provider API key decrypt failed. jobId=$jobId, userProviderId=${userProvider.id}")
                        return@readOnly null
                    }

            AiSummaryContext(
                job = job,
                userProvider = userProvider,
                provider = provider,
                apiKey = apiKey,
                requestModel = requestModel,
                selectableFolderIds = folderRepository.findAllByOwnerId(link.ownerId).mapNotNull { it.id }.toSet(),
                fixedFolderId = job.extractFixedFolderId(),
                snapshot =
                    AiSummarySnapshot(
                        jobId = job.id!!,
                        linkId = link.id!!,
                        folderId = link.folderId,
                    ),
            )
        }

    private suspend fun summarize(context: AiSummaryContext): Result<AiSummaryResult?> {
        val currentProvider =
            tx.readOnly { aiProviderRepository.findById(context.provider.id!!) }
                ?: throw BusinessException(ErrorCode.AI_PROVIDER_NOT_FOUND)
        currentProvider.validateActive()
        val models =
            if (context.userProvider.userRole == Role.SUPER_ADMIN) {
                AiModelAvailabilityDomainService.selectableModels(
                    provider = currentProvider,
                    models =
                        tx.readOnly {
                            availableModelRepository.findAllByProviderId(currentProvider.id!!)
                        },
                )
            } else {
                val model =
                    tx.readOnly { availableModelRepository.findById(context.requestModel.id!!) }
                        ?: throw BusinessException(ErrorCode.AI_MODEL_NOT_FOUND)
                AiModelAvailabilityDomainService.validateUsable(provider = currentProvider, model = model)
                listOf(model)
            }
        var lastFailure: Throwable? = null
        val availableModels =
            tx.readOnly {
                models.filterNot { model ->
                    todayUsage(context.userProvider.id!!, model.id!!)?.isOverLimit(model) == true
                }
            }

        val cycles = if (context.userProvider.userRole == Role.SUPER_ADMIN) MAX_MODEL_CYCLES else 1
        repeat(cycles) {
            availableModels.forEach { model ->
                log.info("[WORKER] Trial model=${model.model}")

                val summary =
                    catchingFailure {
                        tx.readOnly {
                            val provider =
                                aiProviderRepository.findById(currentProvider.id!!)
                                    ?: throw BusinessException(ErrorCode.AI_PROVIDER_NOT_FOUND)
                            val freshModel =
                                availableModelRepository.findById(model.id!!)
                                    ?: throw BusinessException(ErrorCode.AI_MODEL_NOT_FOUND)
                            AiModelAvailabilityDomainService.validateUsable(provider = provider, model = freshModel)
                        }
                        aiClientRouter.summarize(
                            AiSummaryClientRequest(
                                providerType = context.provider.type,
                                baseUrl = context.provider.baseUrl,
                                apiKey = context.apiKey,
                                model = model.model,
                                prompt = context.job.prompt,
                            ),
                        )
                    }
                val result = summary.getOrNull()
                if (result == null) {
                    lastFailure = summary.exceptionOrNull()
                    return@forEach
                }

                (result.linkId == null || result.linkId == context.job.linkId).requireTrue(ErrorCode.AI_SUMMARY_LINK_MISMATCH)
                return Result.success(AiSummaryResult(model = model, response = result))
            }
        }

        return lastFailure?.let { Result.failure(it) } ?: Result.success(null)
    }

    private suspend fun todayUsage(
        userProviderId: Long,
        modelId: Long,
    ): DailyUsage? =
        dailyUsageRepository.findByUserIdAndProviderIdAndUsageDate(
            userProviderId = userProviderId,
            modelId = modelId,
            usageDate = LocalDate.now(ZoneOffset.UTC),
        )

    private suspend fun fail(
        jobId: Long,
        reason: String,
        onResult: (Outcome) -> Unit,
    ) {
        failAiSummaryService.fail(jobId = jobId, reason = reason, now = Clock.System.now())
        onResult(Outcome.FAILED)
    }

    private fun AiJob.extractFixedFolderId(): Long? =
        prompt
            .lineSequence()
            .dropWhile { it.trim() != "## Fixed Folder ID" }
            .drop(1)
            .firstOrNull { it.trim().startsWith("- ") }
            ?.trim()
            ?.removePrefix("- ")
            ?.toLongOrNull()

    private data class AiSummaryContext(
        val job: AiJob,
        val userProvider: UserProvider,
        val provider: AiProvider,
        val apiKey: String,
        val requestModel: AvailableModel,
        val selectableFolderIds: Set<Long>,
        val fixedFolderId: Long?,
        val snapshot: AiSummarySnapshot,
    )

    private data class AiSummaryResult(
        val model: AvailableModel,
        val response: AiSummaryClientResponse,
    )

    enum class Outcome {
        COMPLETED,
        FAILED,
        SKIPPED,
        ABORTED,
        ERROR,
    }

    private companion object {
        const val MAX_MODEL_CYCLES = 5
    }
}
