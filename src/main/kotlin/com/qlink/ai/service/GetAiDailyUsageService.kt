package com.qlink.ai.service

import com.qlink.ai.dto.AiDailyUsageItemResponse
import com.qlink.ai.dto.AiDailyUsageResponse
import com.qlink.ai.repository.DailyUsageRepository
import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.text.parseUtcDayStart
import com.qlink.common.transaction.TransactionRunner

class GetAiDailyUsageService(
    private val tx: TransactionRunner,
    private val adminValidator: AiAdminValidator,
    private val dailyUsageRepository: DailyUsageRepository,
) {
    suspend fun getAiDailyUsage(
        loginId: Long,
        date: String?,
    ): AiDailyUsageResponse =
        tx.readOnly {
            adminValidator.validate(loginId)
            val requestedDate = date ?: throw BusinessException(ErrorCode.COMMON_BAD_REQUEST)
            val dayStart = requestedDate.parseUtcDayStart()
            val items =
                dailyUsageRepository.findSharedUsage(dayStart).map {
                    AiDailyUsageItemResponse(
                        providerId = it.providerId,
                        providerType = it.providerType,
                        modelId = it.modelId,
                        model = it.model,
                        requests = it.requests,
                        tokens = it.tokens,
                    )
                }
            AiDailyUsageResponse(date = requestedDate, items = items)
        }
}
