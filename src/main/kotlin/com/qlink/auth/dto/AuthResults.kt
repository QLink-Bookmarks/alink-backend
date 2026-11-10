@file:Suppress("ktlint:standard:filename")

package com.qlink.auth.dto

data class SignInResult(
    val response: SignInResponse,
    val cookieRefreshToken: String?,
)

data class RotatedAuthTokenResult(
    val accessToken: String,
    val refreshToken: String,
)

data class WebAuthTokenResult(
    val response: WebAuthTokenResponse,
    val cookieRefreshToken: String,
)
