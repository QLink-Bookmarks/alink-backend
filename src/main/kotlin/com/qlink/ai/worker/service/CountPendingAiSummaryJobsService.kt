package com.qlink.ai.worker.service

import com.qlink.ai.repository.AiJobRepository
import com.qlink.common.transaction.TransactionRunner

class CountPendingAiSummaryJobsService(
    private val tx: TransactionRunner,
    private val aiJobRepository: AiJobRepository,
) {
    suspend fun count(): Long = tx.readOnly { aiJobRepository.countNotStarted() }
}
