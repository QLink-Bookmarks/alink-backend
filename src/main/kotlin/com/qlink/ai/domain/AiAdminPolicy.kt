package com.qlink.ai.domain

import com.qlink.common.error.ErrorCode
import com.qlink.common.error.requireTrue

object AiAdminPolicy {
    fun validate(isAdmin: Boolean) {
        isAdmin.requireTrue(ErrorCode.AI_ADMIN_REQUIRED)
    }
}
