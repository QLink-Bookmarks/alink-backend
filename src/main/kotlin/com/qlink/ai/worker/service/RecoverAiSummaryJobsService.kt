package com.qlink.ai.worker.service

import com.qlink.ai.domain.AiJobRecoveryAction
import com.qlink.ai.domain.AiJobStatus
import com.qlink.ai.repository.AiJobRepository
import com.qlink.ai.worker.DEFAULT_FAILURE_MESSAGE
import com.qlink.common.transaction.TransactionRunner
import kotlin.time.Clock

class RecoverAiSummaryJobsService(
    private val tx: TransactionRunner,
    private val aiJobRepository: AiJobRepository,
    private val failAiSummaryService: FailAiSummaryService,
) {
    suspend fun recover(onExpired: suspend (Long) -> Unit): List<Long> {
        val jobsByAction =
            tx.readOnly {
                val now = aiJobRepository.now()
                aiJobRepository.findAllByStatus(AiJobStatus.P).groupBy { it.recoveryAction(now) }
            }

        jobsByAction[AiJobRecoveryAction.FAIL].orEmpty().forEach { job ->
            val jobId = job.id!!
            failAiSummaryService.fail(jobId = jobId, reason = DEFAULT_FAILURE_MESSAGE, now = Clock.System.now())
            onExpired(jobId)
        }
        return jobsByAction[AiJobRecoveryAction.RUN].orEmpty().map { it.id!! }
    }
}
