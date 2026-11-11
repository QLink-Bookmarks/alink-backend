package com.qlink.ai.domain

import com.qlink.common.error.ErrorCode
import com.qlink.common.error.requireNotBlank
import com.qlink.common.error.requireNotOver
import com.qlink.common.error.requireTrue
import kotlin.time.Instant

data class AvailableModel(
    val id: Long? = null,
    val providerId: Long,
    val model: String,
    val priority: Int,
    val rpdLimit: Int?,
    val tpdLimit: Int?,
    val isActive: Boolean = true,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
) {
    fun change(
        priority: Int,
        rpdLimit: Int?,
        tpdLimit: Int?,
        now: Instant,
    ): AvailableModel {
        validateLimits(priority = priority, rpdLimit = rpdLimit, tpdLimit = tpdLimit)
        return copy(priority = priority, rpdLimit = rpdLimit, tpdLimit = tpdLimit, updatedAt = now)
    }

    fun changeActive(
        isActive: Boolean,
        now: Instant,
    ): AvailableModel = if (this.isActive == isActive) this else copy(isActive = isActive, updatedAt = now)

    fun validateActive() {
        isActive.requireTrue(ErrorCode.AI_MODEL_INACTIVE)
    }

    fun validateProvider(providerId: Long) {
        (this.providerId == providerId).requireTrue(ErrorCode.AI_MODEL_DIFFERENT_PROVIDER)
    }

    companion object {
        fun create(
            providerId: Long,
            model: String,
            priority: Int,
            rpdLimit: Int?,
            tpdLimit: Int?,
        ): AvailableModel {
            model.requireNotBlank(ErrorCode.AI_MODEL_NAME_BLANK)
            model.requireNotOver(100, ErrorCode.AI_MODEL_NAME_OVER_MAX)
            model.matches(Regex("[A-Za-z0-9._-]+")).requireTrue(ErrorCode.AI_MODEL_NAME_INVALID)
            validateLimits(priority = priority, rpdLimit = rpdLimit, tpdLimit = tpdLimit)
            return AvailableModel(providerId = providerId, model = model, priority = priority, rpdLimit = rpdLimit, tpdLimit = tpdLimit)
        }

        private fun validateLimits(
            priority: Int,
            rpdLimit: Int?,
            tpdLimit: Int?,
        ) {
            (priority > 0).requireTrue(ErrorCode.AI_MODEL_PRIORITY_NOT_POSITIVE)
            (rpdLimit == null || rpdLimit > 0).requireTrue(ErrorCode.AI_MODEL_LIMIT_NOT_POSITIVE)
            (tpdLimit == null || tpdLimit > 0).requireTrue(ErrorCode.AI_MODEL_LIMIT_NOT_POSITIVE)
        }
    }
}
