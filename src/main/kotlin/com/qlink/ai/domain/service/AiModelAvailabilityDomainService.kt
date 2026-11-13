package com.qlink.ai.domain.service

import com.qlink.ai.domain.AiProvider
import com.qlink.ai.domain.AvailableModel

object AiModelAvailabilityDomainService {
    fun selectableModels(
        provider: AiProvider,
        models: List<AvailableModel>,
    ): List<AvailableModel> =
        if (provider.isActive) models.filter { it.isActive }.sortedWith(compareBy({ it.priority }, { it.id })) else emptyList()

    fun defaultModel(
        provider: AiProvider,
        models: List<AvailableModel>,
    ): AvailableModel? = selectableModels(provider = provider, models = models).firstOrNull()

    fun validateUsable(
        provider: AiProvider,
        model: AvailableModel,
    ) {
        provider.validateActive()
        model.validateActive()
    }
}
