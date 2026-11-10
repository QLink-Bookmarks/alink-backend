package com.qlink.auth.service

import com.qlink.auth.dto.AuthTokenResponse

class RefreshNativeAuthTokenService(
    private val refreshAuthTokenService: RefreshAuthTokenService,
) {
    suspend fun refresh(refreshToken: String?): AuthTokenResponse =
        refreshAuthTokenService.refresh(refreshToken).let { rotated ->
            AuthTokenResponse(
                accessToken = rotated.accessToken,
                refreshToken = rotated.refreshToken.token,
            )
        }
}
