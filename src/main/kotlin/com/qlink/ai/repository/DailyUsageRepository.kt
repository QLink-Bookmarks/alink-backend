package com.qlink.ai.repository

import com.qlink.ai.domain.DailyUsage
import com.qlink.ai.dto.AiDailyUsageQuery
import java.time.LocalDate
import kotlin.time.Instant

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

    suspend fun findSharedUsage(dayStart: Instant): List<AiDailyUsageQuery>

    suspend fun update(dailyUsage: DailyUsage): DailyUsage
}
