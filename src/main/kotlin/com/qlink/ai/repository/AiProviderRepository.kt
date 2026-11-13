package com.qlink.ai.repository

import com.qlink.ai.domain.AiProvider
import com.qlink.ai.domain.AiProviderType
import com.qlink.ai.dto.AiProviderListQuery

interface AiProviderRepository {
    suspend fun findListQueries(includesInactive: Boolean): List<AiProviderListQuery>

    suspend fun findQuery(id: Long): com.qlink.ai.dto.AiProviderQuery?

    suspend fun insert(aiProvider: AiProvider): AiProvider

    suspend fun findById(providerId: Long): AiProvider?

    suspend fun findByType(type: AiProviderType): AiProvider?

    suspend fun findAll(): List<AiProvider>

    suspend fun update(aiProvider: AiProvider): AiProvider
}
