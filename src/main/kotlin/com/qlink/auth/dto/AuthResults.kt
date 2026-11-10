@file:Suppress("ktlint:standard:filename")

package com.qlink.auth.dto

data class RefreshTokenCookie(
    val token: String,
    val maxAge: Int,
)

data class SignInResult(
    val response: SignInResponse,
    val cookie: RefreshTokenCookie?,
)

data class RotatedAuthTokenResult(
    val accessToken: String,
    val refreshToken: RefreshTokenCookie,
)

data class WebAuthTokenResult(
    val response: WebAuthTokenResponse,
    val cookie: RefreshTokenCookie,
)
