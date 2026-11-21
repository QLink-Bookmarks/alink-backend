package com.qlink.ai.repository

import com.qlink.ai.domain.DailyUsage
import java.time.LocalDate

interface DailyUsageRepository {
    suspend fun insert(dailyUsage: DailyUsage): DailyUsage

    suspend fun findByUserIdAndProviderIdAndUsageDate(
        userProviderId: Long,
        modelId: Long,
        usageDate: LocalDate,
    ): DailyUsage?

    suspend fun findAllByUserProviderAndUsageDate(
        userProviderId: Long,
        modelIds: List<Long>,
        usageDate: LocalDate,
    ): List<DailyUsage>

    suspend fun update(dailyUsage: DailyUsage): DailyUsage
}
