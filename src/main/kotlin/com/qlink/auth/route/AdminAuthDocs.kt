package com.qlink.auth.route

import com.qlink.auth.dto.AdminSignInRequest
import com.qlink.auth.dto.WebAuthTokenResponse
import com.qlink.common.docs.authErrorResponse
import com.qlink.common.docs.examples
import com.qlink.common.error.ErrorCode
import com.qlink.common.response.ApiResponse
import com.qlink.common.response.ErrorDetail
import io.github.smiley4.ktoropenapi.config.RouteConfig
import io.ktor.http.HttpStatusCode

internal fun adminSignInDocs(): RouteConfig.() -> Unit =
    {
        summary = "Google 관리자 로그인 (기존 관리자 계정만 허용)"
        request { body<AdminSignInRequest>() }
        response {
            code(HttpStatusCode.Created) { body<ApiResponse<WebAuthTokenResponse>>() }
            authErrorResponse()
            code(HttpStatusCode.BadRequest) {
                body<ApiResponse<ErrorDetail>> {
                    examples(ErrorCode.AUTH_PROVIDER_NOT_SUPPORTED, ErrorCode.AUTH_ADMIN_PROVIDER_NOT_SUPPORTED)
                }
            }
            code(HttpStatusCode.Forbidden) { body<ApiResponse<ErrorDetail>> { examples(ErrorCode.AUTH_ADMIN_REQUIRED) } }
            code(HttpStatusCode.UnprocessableEntity) {
                body<ApiResponse<ErrorDetail>> {
                    examples(ErrorCode.AUTH_PROVIDER_COMMUNICATION_FAILED, ErrorCode.AUTH_PROVIDER_TOKEN_INVALID)
                }
            }
        }
    }

internal fun adminRefreshDocs(): RouteConfig.() -> Unit =
    {
        summary = "관리자 세션 갱신 (admin_refresh_token 쿠키, csrf_token 헤더)"
        response {
            code(HttpStatusCode.Created) { body<ApiResponse<WebAuthTokenResponse>>() }
            authErrorResponse()
            code(HttpStatusCode.Forbidden) {
                body<ApiResponse<ErrorDetail>> { examples(ErrorCode.AUTH_ADMIN_REQUIRED, ErrorCode.AUTH_CSRF_TOKEN_INVALID) }
            }
        }
    }

internal fun adminSignOutDocs(): RouteConfig.() -> Unit =
    {
        summary = "관리자 세션 로그아웃 (채널 세션 유지)"
        response {
            code(HttpStatusCode.OK) { body<ApiResponse<Unit>>() }
            authErrorResponse()
            code(HttpStatusCode.Forbidden) { body<ApiResponse<ErrorDetail>> { examples(ErrorCode.AUTH_CSRF_TOKEN_INVALID) } }
            code(HttpStatusCode.NotFound) { body<ApiResponse<ErrorDetail>> { examples(ErrorCode.USER_NOT_FOUND) } }
        }
    }
