package com.qlink.ai.worker.service

import com.qlink.ai.client.AiClientRouter
import com.qlink.ai.client.AiSummaryClientRequest
import com.qlink.ai.client.AiSummaryClientResponse
import com.qlink.ai.client.AiVendorException
import com.qlink.ai.domain.AiJob
import com.qlink.ai.domain.AiJob.Companion.DEFAULT_FAILURE_MESSAGE
import com.qlink.ai.domain.AiProvider
import com.qlink.ai.domain.AvailableModel
import com.qlink.ai.domain.UserProvider
import com.qlink.ai.domain.service.AiModelAvailabilityDomainService
import com.qlink.ai.dto.AiSummaryCompletion
import com.qlink.ai.dto.AiSummaryExecutionResult
import com.qlink.ai.dto.AiSummarySnapshot
import com.qlink.ai.repository.AiJobRepository
import com.qlink.ai.repository.AiProviderRepository
import com.qlink.ai.repository.AvailableModelRepository
import com.qlink.ai.repository.DailyUsageRepository
import com.qlink.ai.repository.UserProviderRepository
import com.qlink.auth.domain.Role
import com.qlink.common.coroutines.catchingFailure
import com.qlink.common.crypto.ApiKeyCipher
import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.error.requireTrue
import com.qlink.common.transaction.TransactionRunner
import com.qlink.folder.repository.FolderRepository
import com.qlink.link.repository.LinkRepository
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.serialization.JsonConvertException
import kotlinx.coroutines.delay
import org.slf4j.Logger
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.time.Duration

class ProcessAiSummaryService(
    private val tx: TransactionRunner,
    private val aiJobRepository: AiJobRepository,
    private val userProviderRepository: UserProviderRepository,
    private val availableModelRepository: AvailableModelRepository,
    private val aiProviderRepository: AiProviderRepository,
    private val dailyUsageRepository: DailyUsageRepository,
    private val folderRepository: FolderRepository,
    private val linkRepository: LinkRepository,
    private val aiClientRouter: AiClientRouter,
    private val apiKeyCipher: ApiKeyCipher,
    private val log: Logger,
) {
    suspend fun proceed(jobId: Long): AiSummaryExecutionResult = proceed(jobId = jobId, scheduleRetry = ::delay)

    suspend fun proceed(
        jobId: Long,
        scheduleRetry: suspend (Duration) -> Unit,
    ): AiSummaryExecutionResult {
        val isStarted = tx.required { aiJobRepository.markStarted(aiJobId = jobId, staleAfter = AiJob.STALE_AFTER) }
        if (!isStarted) {
            log.info("[WORKER] Job is not runnable. jobId=$jobId")
            return AiSummaryExecutionResult.Skipped
        }

        val context =
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
                val providerModels =
                    if (userProvider.userRole == Role.SUPER_ADMIN) {
                        availableModelRepository.findAllByProviderId(userProvider.providerId)
                    } else {
                        emptyList()
                    }
                val requestModel =
                    (providerModels.find { it.id == job.requestModelId } ?: availableModelRepository.findById(job.requestModelId))
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

                val selectableFolderIds = folderRepository.findAllByOwnerId(link.ownerId).mapNotNull { it.id }.toSet()
                val availableModels =
                    catchingFailure {
                        selectAvailableModels(
                            userProvider = userProvider,
                            provider = provider,
                            requestModel = requestModel,
                            providerModels = providerModels,
                        )
                    }
                AiSummaryContext(
                    job = job,
                    userProvider = userProvider,
                    provider = provider,
                    apiKey = apiKey,
                    availableModels = availableModels,
                    selectableFolderIds = selectableFolderIds,
                    fixedFolderId = job.extractFixedFolderId(),
                    snapshot =
                        AiSummarySnapshot(
                            jobId = job.id!!,
                            linkId = link.id!!,
                            folderId = link.folderId,
                        ),
                )
            } ?: return AiSummaryExecutionResult.Aborted

        log.info(
            "[WORKER] Job Context has been loaded as jobId=$jobId, userProvider=${context.userProvider.id}, provider=${context.provider.type}",
        )

        return catchingFailure { summarize(context = context, scheduleRetry = scheduleRetry) }
            .fold(
                onSuccess = { result ->
                    AiSummaryExecutionResult.Summarized(
                        linkId = context.snapshot.linkId,
                        completion =
                            catchingFailure {
                                AiSummaryCompletion(
                                    snapshot = context.snapshot,
                                    responseModelId = result.model.id!!,
                                    candidateFolderId =
                                        context.fixedFolderId
                                            ?: result.response.folderId?.takeIf { it in context.selectableFolderIds },
                                    response = result.response,
                                )
                            },
                    )
                },
                onFailure = { AiSummaryExecutionResult.Failed(jobId = context.job.id, error = it) },
            )
    }

    private suspend fun selectAvailableModels(
        userProvider: UserProvider,
        provider: AiProvider,
        requestModel: AvailableModel,
        providerModels: List<AvailableModel>,
    ): List<AvailableModel> {
        val models =
            if (userProvider.userRole == Role.SUPER_ADMIN) {
                provider.validateActive()
                AiModelAvailabilityDomainService.selectableModels(
                    provider = provider,
                    models = providerModels,
                )
            } else {
                AiModelAvailabilityDomainService.validateUsable(provider = provider, model = requestModel)
                listOf(requestModel)
            }
        if (models.isEmpty()) return emptyList()

        val usages =
            dailyUsageRepository
                .findAllByUserProviderAndUsageDate(
                    userProviderId = userProvider.id!!,
                    modelIds = models.map { it.id!! },
                    usageDate = LocalDate.now(ZoneOffset.UTC),
                ).associateBy { it.modelId }
        return models.filterNot { model -> usages[model.id]?.isOverLimit(model) == true }
    }

    private suspend fun summarize(
        context: AiSummaryContext,
        scheduleRetry: suspend (Duration) -> Unit,
    ): AiSummaryResult {
        val availableModels = context.availableModels.getOrThrow()
        var lastFailure: Throwable? = null
        val cycles = if (context.userProvider.userRole == Role.SUPER_ADMIN) MAX_MODEL_CYCLES else 1

        var rateLimitRetried = false
        var deferredFailure: AiVendorException? = null
        repeat(cycles) {
            availableModels.forEach { model ->
                deferredFailure?.let { waitForVendorRetry(job = context.job, failure = it, scheduleRetry = scheduleRetry) }
                deferredFailure = null
                val providerId = context.provider.id!!
                val modelId = model.id!!
                try {
                    revalidateAttempt(providerId = providerId, modelId = modelId)
                } catch (failure: BusinessException) {
                    if (cycles == 1 || failure.errorCode != ErrorCode.AI_MODEL_INACTIVE) throw failure
                    lastFailure = failure
                    return@forEach
                }
                log.info("[WORKER] Trial model=${model.model}")
                val request =
                    AiSummaryClientRequest(
                        providerType = context.provider.type,
                        baseUrl = context.provider.baseUrl,
                        apiKey = context.apiKey,
                        model = model.model,
                        prompt = context.job.prompt,
                        requestedUrl = context.job.requestedUrl,
                    )
                var attempt = requestSummary(request)
                val rateLimit =
                    (attempt.exceptionOrNull() as? AiVendorException)?.takeIf {
                        it.errorCode == ErrorCode.AI_VENDOR_RATE_LIMITED
                    }
                val retryAfter = rateLimit?.retryAfter
                if (retryAfter != null && !rateLimitRetried) {
                    rateLimitRetried = true
                    waitForVendorRetry(job = context.job, failure = rateLimit, scheduleRetry = scheduleRetry)
                    revalidateAttempt(providerId = providerId, modelId = modelId)
                    attempt = requestSummary(request)
                }

                val failure = attempt.exceptionOrNull()
                if (failure != null) {
                    if (failure is AiVendorException) {
                        if (failure.errorCode != ErrorCode.AI_VENDOR_TEMPORARY_UNAVAILABLE) throw failure
                        val nextDelay = failure.retryAfter
                        if (nextDelay != null) {
                            if (!canWaitForRetry(job = context.job, retryAfter = nextDelay)) throw failure
                            deferredFailure = failure
                        }
                    }
                    lastFailure = failure
                    return@forEach
                }

                val result = attempt.getOrThrow()
                (result.linkId == null || result.linkId == context.job.linkId).requireTrue(ErrorCode.AI_SUMMARY_LINK_MISMATCH)
                return AiSummaryResult(model = model, response = result)
            }
        }

        throw lastFailure ?: IllegalStateException(DEFAULT_FAILURE_MESSAGE)
    }

    private suspend fun requestSummary(request: AiSummaryClientRequest): Result<AiSummaryClientResponse> =
        catchingFailure { aiClientRouter.summarize(request) }
            .fold(
                onSuccess = { Result.success(it) },
                onFailure = { failure ->
                    Result.failure(
                        when (failure) {
                            is IOException -> AiVendorException(ErrorCode.AI_VENDOR_TEMPORARY_UNAVAILABLE)
                            is JsonConvertException, is NoTransformationFoundException -> BusinessException(ErrorCode.AI_EMPTY_RESPONSE)
                            else -> failure
                        },
                    )
                },
            )

    private suspend fun waitForVendorRetry(
        job: AiJob,
        failure: AiVendorException,
        scheduleRetry: suspend (Duration) -> Unit,
    ) {
        val retryAfter = failure.retryAfter ?: throw failure
        if (!canWaitForRetry(job = job, retryAfter = retryAfter)) throw failure
        scheduleRetry(retryAfter)
        if (!canWaitForRetry(job = job, retryAfter = Duration.ZERO)) throw failure
    }

    private suspend fun canWaitForRetry(
        job: AiJob,
        retryAfter: Duration,
    ): Boolean {
        val startedAt = job.startedAt ?: return false
        val now = tx.readOnly { aiJobRepository.now() }
        return retryAfter < AiJob.STALE_AFTER - (now - startedAt)
    }

    private suspend fun revalidateAttempt(
        providerId: Long,
        modelId: Long,
    ) {
        tx.readOnly {
            val provider = aiProviderRepository.findById(providerId) ?: throw BusinessException(ErrorCode.AI_PROVIDER_NOT_FOUND)
            val model = availableModelRepository.findById(modelId) ?: throw BusinessException(ErrorCode.AI_MODEL_NOT_FOUND)
            AiModelAvailabilityDomainService.validateUsable(provider = provider, model = model)
        }
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
        val availableModels: Result<List<AvailableModel>>,
        val selectableFolderIds: Set<Long>,
        val fixedFolderId: Long?,
        val snapshot: AiSummarySnapshot,
    )

    private data class AiSummaryResult(
        val model: AvailableModel,
        val response: AiSummaryClientResponse,
    )

    private companion object {
        const val MAX_MODEL_CYCLES = 5
    }
}
