package com.qlink.ai.repository

import com.qlink.ai.domain.DailyUsage
import com.qlink.ai.dto.AiDailyUsageQuery
import com.qlink.ai.dto.toAiDailyUsageQuery
import com.qlink.ai.repository.table.AiProviders
import com.qlink.ai.repository.table.AvailableModels
import com.qlink.ai.repository.table.DailyUsages
import com.qlink.ai.repository.table.UserProviders
import com.qlink.ai.repository.table.fromDomain
import com.qlink.ai.repository.table.refreshDailyUsageUpdatedAt
import com.qlink.ai.repository.table.toDailyUsageDomain
import com.qlink.auth.domain.Role
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.castTo
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.updateReturning
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.time.Instant
import kotlin.time.toJavaInstant

class DbDailyUsageRepository : DailyUsageRepository {
    override suspend fun insert(dailyUsage: DailyUsage): DailyUsage =
        DailyUsages
            .insertReturning { it.fromDomain(dailyUsage) }
            .single()
            .toDailyUsageDomain()

    override suspend fun findByUserIdAndProviderIdAndUsageDate(
        userProviderId: Long,
        modelId: Long,
        usageDate: LocalDate,
    ): DailyUsage? =
        DailyUsages
            .selectAll()
            .where {
                (DailyUsages.userProviderId eq userProviderId) and
                    (DailyUsages.modelId eq modelId) and
                    (DailyUsages.usageDate eq usageDate)
            }.singleOrNull()
            ?.toDailyUsageDomain()

    override suspend fun findAllByUserProviderAndUsageDate(
        userProviderId: Long,
        modelIds: List<Long>,
        usageDate: LocalDate,
    ): List<DailyUsage> =
        DailyUsages
            .selectAll()
            .where {
                (DailyUsages.userProviderId eq userProviderId) and
                    (DailyUsages.modelId inList modelIds) and
                    (DailyUsages.usageDate eq usageDate)
            }.map { it.toDailyUsageDomain() }

    override suspend fun findSharedUsage(dayStart: Instant): List<AiDailyUsageQuery> {
        val usageDate = dayStart.toJavaInstant().atOffset(ZoneOffset.UTC).toLocalDate()
        val requests = DailyUsages.requests.castTo<Long>(LongColumnType()).sum()
        val tokens = DailyUsages.tokens.castTo<Long>(LongColumnType()).sum()
        return DailyUsages
            .join(UserProviders, JoinType.INNER, DailyUsages.userProviderId, UserProviders.id)
            .join(AiProviders, JoinType.INNER, UserProviders.providerId, AiProviders.id)
            .join(AvailableModels, JoinType.INNER, DailyUsages.modelId, AvailableModels.id)
            .select(AiProviders.id, AiProviders.type, AvailableModels.id, AvailableModels.model, requests, tokens)
            .where { (DailyUsages.usageDate eq usageDate) and (UserProviders.userRole eq Role.SUPER_ADMIN) }
            .groupBy(AiProviders.id, AiProviders.type, AvailableModels.id, AvailableModels.model)
            .orderBy(AiProviders.id to SortOrder.ASC, AvailableModels.id to SortOrder.ASC)
            .map { it.toAiDailyUsageQuery(requests = requests, tokens = tokens) }
    }

    override suspend fun update(dailyUsage: DailyUsage): DailyUsage =
        DailyUsages
            .updateReturning(where = { DailyUsages.id eq dailyUsage.id!! }) {
                it.fromDomain(dailyUsage)
                it.refreshDailyUsageUpdatedAt()
            }.single()
            .toDailyUsageDomain()
}
