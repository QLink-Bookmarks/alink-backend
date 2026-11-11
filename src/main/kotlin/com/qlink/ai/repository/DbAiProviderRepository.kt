package com.qlink.ai.repository

import com.qlink.ai.domain.AiProvider
import com.qlink.ai.domain.AiProviderType
import com.qlink.ai.dto.AiProviderQuery
import com.qlink.ai.dto.toAiProviderQuery
import com.qlink.ai.repository.table.AiProviders
import com.qlink.ai.repository.table.fromDomain
import com.qlink.ai.repository.table.refreshAiProviderUpdatedAt
import com.qlink.ai.repository.table.toAiProviderDomain
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.updateReturning

class DbAiProviderRepository : AiProviderRepository {
    override suspend fun findQueries(includesInactive: Boolean): List<AiProviderQuery> =
        AiProviders
            .selectAll()
            .apply { if (!includesInactive) where { AiProviders.isActive eq true } }
            .orderBy(AiProviders.type to SortOrder.ASC, AiProviders.id to SortOrder.ASC)
            .map { it.toAiProviderQuery() }

    override suspend fun findQuery(id: Long): AiProviderQuery? =
        AiProviders
            .selectAll()
            .where { AiProviders.id eq id }
            .singleOrNull()
            ?.toAiProviderQuery()

    override suspend fun insert(aiProvider: AiProvider): AiProvider =
        AiProviders
            .insertReturning { it.fromDomain(aiProvider) }
            .single()
            .toAiProviderDomain()

    override suspend fun findById(providerId: Long): AiProvider? =
        AiProviders
            .selectAll()
            .where { AiProviders.id eq providerId }
            .singleOrNull()
            ?.toAiProviderDomain()

    override suspend fun findByType(type: AiProviderType): AiProvider? =
        AiProviders
            .selectAll()
            .where { AiProviders.type eq type }
            .singleOrNull()
            ?.toAiProviderDomain()

    override suspend fun findAll(): List<AiProvider> =
        AiProviders
            .selectAll()
            .orderBy(AiProviders.id to SortOrder.ASC)
            .map { it.toAiProviderDomain() }

    override suspend fun update(aiProvider: AiProvider): AiProvider =
        AiProviders
            .updateReturning(where = { AiProviders.id eq aiProvider.id!! }) {
                it.fromDomain(aiProvider)
                it.refreshAiProviderUpdatedAt()
            }.single()
            .toAiProviderDomain()
}
