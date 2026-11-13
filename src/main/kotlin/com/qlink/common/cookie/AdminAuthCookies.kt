package com.qlink.common.cookie

import io.ktor.http.Cookie
import io.ktor.server.application.ApplicationCall

const val ADMIN_REFRESH_TOKEN_COOKIE_NAME = "admin_refresh_token"

fun ApplicationCall.appendAdminRefreshTokenCookie(
    refreshToken: String,
    maxAge: Int,
) {
    response.cookies.append(
        Cookie(
            name = ADMIN_REFRESH_TOKEN_COOKIE_NAME,
            value = refreshToken,
            path = "/api/auth/admin",
            maxAge = maxAge,
            httpOnly = true,
            secure = true,
            extensions = mapOf("SameSite" to "Strict"),
        ),
    )
}
