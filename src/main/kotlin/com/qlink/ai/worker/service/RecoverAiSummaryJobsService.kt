package com.qlink.ai.worker.service

import com.qlink.ai.domain.AiJobRecoveryAction
import com.qlink.ai.domain.AiJobStatus
import com.qlink.ai.dto.AiSummaryRecoveryResult
import com.qlink.ai.repository.AiJobRepository
import com.qlink.common.transaction.TransactionRunner

class RecoverAiSummaryJobsService(
    private val tx: TransactionRunner,
    private val aiJobRepository: AiJobRepository,
) {
    suspend fun recover(): AiSummaryRecoveryResult {
        val jobsByAction =
            tx.readOnly {
                val now = aiJobRepository.now()
                aiJobRepository.findAllByStatus(AiJobStatus.P).groupBy { it.recoveryAction(now) }
            }

        return AiSummaryRecoveryResult(
            expiredJobs = jobsByAction[AiJobRecoveryAction.FAIL].orEmpty(),
            runnableJobs = jobsByAction[AiJobRecoveryAction.RUN].orEmpty(),
        )
    }
}
