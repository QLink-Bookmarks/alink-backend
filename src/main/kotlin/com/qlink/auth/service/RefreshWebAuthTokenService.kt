package com.qlink.auth.service

import com.qlink.auth.dto.WebAuthTokenResponse
import com.qlink.auth.dto.WebAuthTokenResult

class RefreshWebAuthTokenService(
    private val refreshAuthTokenService: RefreshAuthTokenService,
) {
    suspend fun refresh(refreshToken: String?): WebAuthTokenResult =
        refreshAuthTokenService.refresh(refreshToken).let { rotated ->
            WebAuthTokenResult(
                response = WebAuthTokenResponse(accessToken = rotated.accessToken),
                cookie = rotated.refreshToken,
            )
        }
}
