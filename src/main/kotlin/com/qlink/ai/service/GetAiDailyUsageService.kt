package com.qlink.ai.service

import com.qlink.ai.dto.AiDailyUsageItemResponse
import com.qlink.ai.dto.AiDailyUsageResponse
import com.qlink.ai.repository.DailyUsageRepository
import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.transaction.TransactionRunner
import kotlin.time.Instant

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
            val dayStart = parseUtcDayStart(requestedDate)
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

    private fun parseUtcDayStart(date: String): Instant {
        if (date.length != 10) {
            throw BusinessException(ErrorCode.COMMON_BAD_REQUEST)
        }

        return try {
            Instant.parse("${date}T00:00:00Z")
        } catch (_: IllegalArgumentException) {
            throw BusinessException(ErrorCode.COMMON_BAD_REQUEST)
        }
    }
}
