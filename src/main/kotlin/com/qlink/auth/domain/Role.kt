package com.qlink.auth.domain

import com.qlink.common.error.ErrorCode
import com.qlink.common.error.requireTrue
import kotlinx.serialization.Serializable

@Serializable
enum class Role {
    SUPER_ADMIN,
    ADMIN,
    NORMAL,
    GUEST,
    ;

    val isAdmin: Boolean
        get() = this == SUPER_ADMIN || this == ADMIN

    fun validateAdmin() {
        isAdmin.requireTrue(ErrorCode.AUTH_ADMIN_REQUIRED)
    }
}
