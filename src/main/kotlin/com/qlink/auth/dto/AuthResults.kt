@file:Suppress("ktlint:standard:filename")

package com.qlink.auth.dto

import com.qlink.auth.domain.AuthSessionScope

data class RefreshTokenClaims(
    val userId: Long,
    val familyId: String,
    val scope: AuthSessionScope,
)

data class IssuedRefreshToken(
    val token: String,
    val maxAge: Int,
)

data class SignInResult(
    val response: SignInResponse,
    val cookie: IssuedRefreshToken?,
)

data class RotatedAuthTokenResult(
    val accessToken: String,
    val refreshToken: IssuedRefreshToken,
)

data class WebAuthTokenResult(
    val response: WebAuthTokenResponse,
    val cookie: IssuedRefreshToken,
)
