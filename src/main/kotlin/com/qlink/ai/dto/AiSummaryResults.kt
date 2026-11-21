package com.qlink.ai.dto

import com.qlink.ai.client.AiSummaryClientResponse
import com.qlink.ai.domain.AiJob

// Captured from the repository-returned link before the external AI request.
data class AiSummarySnapshot(
    val jobId: Long,
    val linkId: Long,
    val folderId: Long?,
)

data class AiSummaryCompletion(
    val snapshot: AiSummarySnapshot,
    val responseModelId: Long,
    val candidateFolderId: Long?,
    val response: AiSummaryClientResponse,
)

data class AiSummaryCompletionResult(
    val schedulingFailed: Boolean = false,
    val folderKeptAfterMove: Boolean = false,
)

sealed interface AiSummaryExecutionResult {
    data object Skipped : AiSummaryExecutionResult

    data object Aborted : AiSummaryExecutionResult

    data class Summarized(
        val linkId: Long,
        val completion: Result<AiSummaryCompletion>,
    ) : AiSummaryExecutionResult

    data class Failed(
        val jobId: Long?,
        val error: Throwable,
    ) : AiSummaryExecutionResult
}

data class AiSummaryRecoveryResult(
    val expiredJobs: List<AiJob>,
    val runnableJobs: List<AiJob>,
)
