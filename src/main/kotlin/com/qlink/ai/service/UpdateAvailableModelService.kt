package com.qlink.ai.service

import com.qlink.ai.dto.UpdateAvailableModelRequest
import com.qlink.ai.dto.UpdateAvailableModelResponse
import com.qlink.ai.repository.AvailableModelRepository
import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.transaction.TransactionRunner
import kotlin.time.Clock

class UpdateAvailableModelService(
    private val tx: TransactionRunner,
    private val adminValidator: AiAdminValidator,
    private val availableModelRepository: AvailableModelRepository,
) {
    suspend fun updateAvailableModel(
        loginId: Long,
        id: Long,
        request: UpdateAvailableModelRequest,
    ): UpdateAvailableModelResponse =
        tx.required {
            adminValidator.validate(loginId)
            val model = availableModelRepository.findById(id) ?: throw BusinessException(ErrorCode.AI_ADMIN_MODEL_NOT_FOUND)
            val saved =
                availableModelRepository.update(
                    model.change(
                        priority = request.priority,
                        rpdLimit = request.rpdLimit,
                        tpdLimit = request.tpdLimit,
                        now = Clock.System.now(),
                    ),
                )
            UpdateAvailableModelResponse(id = saved.id!!, updatedAt = saved.updatedAt!!)
        }
}
