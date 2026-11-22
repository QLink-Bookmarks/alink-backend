package com.qlink.ai.service

import com.qlink.ai.dto.AiDailyUsageItemResponse
import com.qlink.ai.dto.AiDailyUsageResponse
import com.qlink.ai.repository.DailyUsageRepository
import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.transaction.TransactionRunner
import java.time.LocalDate
import java.time.format.DateTimeParseException

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
            val usageDate = parseUsageDate(date)
            val items =
                dailyUsageRepository.findSharedUsage(usageDate).map {
                    AiDailyUsageItemResponse(
                        providerId = it.providerId,
                        providerType = it.providerType,
                        modelId = it.modelId,
                        model = it.model,
                        requests = it.requests,
                        tokens = it.tokens,
                    )
                }
            AiDailyUsageResponse(date = usageDate.toString(), items = items)
        }

    private fun parseUsageDate(date: String?): LocalDate {
        if (date == null || date.length != 10) {
            throw BusinessException(ErrorCode.COMMON_BAD_REQUEST)
        }

        return try {
            LocalDate.parse(date)
        } catch (_: DateTimeParseException) {
            throw BusinessException(ErrorCode.COMMON_BAD_REQUEST)
        }
    }
}
