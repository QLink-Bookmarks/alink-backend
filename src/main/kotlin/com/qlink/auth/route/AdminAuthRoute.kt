package com.qlink.auth.route

import com.qlink.auth.domain.AuthSessionScope
import com.qlink.auth.domain.JwtPrincipal
import com.qlink.auth.dto.AdminSignInRequest
import com.qlink.auth.dto.WebAuthTokenResponse
import com.qlink.auth.service.RefreshAuthTokenService
import com.qlink.auth.service.SignInAdminService
import com.qlink.auth.service.SignOutService
import com.qlink.common.cookie.ADMIN_REFRESH_TOKEN_COOKIE_NAME
import com.qlink.common.cookie.appendAdminRefreshTokenCookie
import com.qlink.common.error.ErrorCode
import com.qlink.common.response.respondError
import com.qlink.common.response.respondSuccess
import io.github.smiley4.ktoropenapi.resources.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.csrf.CSRF
import io.ktor.server.request.receive
import io.ktor.server.resources.resource
import io.ktor.server.routing.Route
import org.koin.ktor.ext.inject
import io.github.smiley4.ktoropenapi.delete as deleteRoute
import io.github.smiley4.ktoropenapi.post as postRoute

internal fun Route.adminAuthRoutes() {
    val signIn by inject<SignInAdminService>()
    val refresh by inject<RefreshAuthTokenService>()
    val signOut by inject<SignOutService>()

    post<AuthResources.Admin.Sign>(adminSignInDocs()) {
        val result = signIn.signIn(call.receive<AdminSignInRequest>())
        call.appendAdminRefreshTokenCookie(refreshToken = result.refreshToken.token, maxAge = result.refreshToken.maxAge)
        call.respondSuccess(HttpStatusCode.Created, WebAuthTokenResponse(accessToken = result.accessToken))
    }
    resource<AuthResources.Admin.Refresh> {
        installAdminCsrf()
        postRoute(adminRefreshDocs()) {
            val result =
                refresh.refresh(
                    refreshToken = call.request.cookies[ADMIN_REFRESH_TOKEN_COOKIE_NAME],
                    scope = AuthSessionScope.ADMIN,
                )
            call.appendAdminRefreshTokenCookie(refreshToken = result.refreshToken.token, maxAge = result.refreshToken.maxAge)
            call.respondSuccess(HttpStatusCode.Created, WebAuthTokenResponse(accessToken = result.accessToken))
        }
    }
    authenticate {
        resource<AuthResources.Admin.Signout> {
            installAdminCsrf()
            deleteRoute(adminSignOutDocs()) {
                signOut.signOut(
                    loginId = call.principal<JwtPrincipal>()!!.userId,
                    refreshToken = call.request.cookies[ADMIN_REFRESH_TOKEN_COOKIE_NAME],
                )
                call.appendAdminRefreshTokenCookie(refreshToken = "", maxAge = 0)
                call.respondSuccess(HttpStatusCode.OK)
            }
        }
    }
}

private fun Route.installAdminCsrf() {
    install(CSRF) {
        checkHeader(CSRF_TOKEN_HEADER_NAME)
        onFailure { respondError(ErrorCode.AUTH_CSRF_TOKEN_INVALID) }
    }
}
