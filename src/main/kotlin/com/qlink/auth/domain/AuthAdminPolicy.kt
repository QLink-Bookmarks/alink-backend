package com.qlink.auth.domain

import com.qlink.common.error.ErrorCode
import com.qlink.common.error.requireTrue

object AuthAdminPolicy {
    fun validate(isAdmin: Boolean) {
        isAdmin.requireTrue(ErrorCode.AUTH_ADMIN_REQUIRED)
    }
}
