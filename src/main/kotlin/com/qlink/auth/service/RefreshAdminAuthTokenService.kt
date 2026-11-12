package com.qlink.auth.service

import com.qlink.auth.domain.AuthSessionScope
import com.qlink.auth.dto.RotatedAuthTokenResult

class RefreshAdminAuthTokenService(
    private val refreshAuthTokenService: RefreshAuthTokenService,
) {
    suspend fun refresh(refreshToken: String?): RotatedAuthTokenResult =
        refreshAuthTokenService.refresh(refreshToken = refreshToken, scope = AuthSessionScope.ADMIN)
}
