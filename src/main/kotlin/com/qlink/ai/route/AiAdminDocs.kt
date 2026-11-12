package com.qlink.ai.route

import com.qlink.ai.dto.AiProviderDetailResponse
import com.qlink.ai.dto.CreateAiProviderRequest
import com.qlink.ai.dto.CreateAiResourceResponse
import com.qlink.ai.dto.UpdateAiActiveRequest
import com.qlink.ai.dto.UpdateAiActiveResponse
import com.qlink.ai.dto.UpdateAiProviderRequest
import com.qlink.ai.dto.UpdateAiProviderResponse
import com.qlink.common.docs.authErrorResponse
import com.qlink.common.docs.examples
import com.qlink.common.error.ErrorCode
import com.qlink.common.response.ApiResponse
import com.qlink.common.response.ErrorDetail
import io.github.smiley4.ktoropenapi.config.RouteConfig
import io.ktor.http.HttpStatusCode

internal fun getAiProviderDocs(): RouteConfig.() -> Unit =
    {
        summary = "AI 제공자 상세 조회 (현재 DB 관리자 권한 필요)"
        response {
            code(HttpStatusCode.OK) { body<ApiResponse<AiProviderDetailResponse>>() }
            authErrorResponse()
            code(HttpStatusCode.Forbidden) { body<ApiResponse<ErrorDetail>> { examples(ErrorCode.AI_ADMIN_REQUIRED) } }
            code(HttpStatusCode.NotFound) {
                body<ApiResponse<ErrorDetail>> { examples(ErrorCode.AI_ADMIN_USER_NOT_FOUND, ErrorCode.AI_ADMIN_PROVIDER_NOT_FOUND) }
            }
        }
    }

internal fun createAiProviderDocs(): RouteConfig.() -> Unit =
    {
        summary = "AI 제공자 추가 (현재 DB 관리자 권한 필요)"
        request { body<CreateAiProviderRequest>() }
        response {
            code(HttpStatusCode.Created) { body<ApiResponse<CreateAiResourceResponse>>() }
            authErrorResponse()
            code(HttpStatusCode.BadRequest) { body<ApiResponse<ErrorDetail>> { examples(ErrorCode.AI_PROVIDER_BASE_URL_INVALID) } }
            code(HttpStatusCode.Forbidden) { body<ApiResponse<ErrorDetail>> { examples(ErrorCode.AI_ADMIN_REQUIRED) } }
            code(HttpStatusCode.NotFound) { body<ApiResponse<ErrorDetail>> { examples(ErrorCode.AI_ADMIN_USER_NOT_FOUND) } }
            code(HttpStatusCode.Conflict) { body<ApiResponse<ErrorDetail>> { examples(ErrorCode.AI_PROVIDER_DUPLICATE_TYPE) } }
        }
    }

internal fun updateAiProviderDocs(): RouteConfig.() -> Unit =
    {
        summary = "AI 제공자 주소 수정 (현재 DB 관리자 권한 필요)"
        request { body<UpdateAiProviderRequest>() }
        response {
            code(HttpStatusCode.OK) { body<ApiResponse<UpdateAiProviderResponse>>() }
            authErrorResponse()
            code(HttpStatusCode.BadRequest) { body<ApiResponse<ErrorDetail>> { examples(ErrorCode.AI_PROVIDER_BASE_URL_INVALID) } }
            code(HttpStatusCode.Forbidden) { body<ApiResponse<ErrorDetail>> { examples(ErrorCode.AI_ADMIN_REQUIRED) } }
            code(HttpStatusCode.NotFound) {
                body<ApiResponse<ErrorDetail>> { examples(ErrorCode.AI_ADMIN_USER_NOT_FOUND, ErrorCode.AI_ADMIN_PROVIDER_NOT_FOUND) }
            }
        }
    }

internal fun updateAiProviderActiveDocs(): RouteConfig.() -> Unit =
    {
        summary = "AI 제공자 활성 상태 변경 (현재 DB 관리자 권한 필요)"
        request { body<UpdateAiActiveRequest>() }
        response {
            code(HttpStatusCode.OK) { body<ApiResponse<UpdateAiActiveResponse>>() }
            authErrorResponse()
            code(HttpStatusCode.Forbidden) { body<ApiResponse<ErrorDetail>> { examples(ErrorCode.AI_ADMIN_REQUIRED) } }
            code(HttpStatusCode.NotFound) {
                body<ApiResponse<ErrorDetail>> { examples(ErrorCode.AI_ADMIN_USER_NOT_FOUND, ErrorCode.AI_ADMIN_PROVIDER_NOT_FOUND) }
            }
            code(HttpStatusCode.UnprocessableEntity) { body<ApiResponse<ErrorDetail>> { examples(ErrorCode.AI_PROVIDER_CLIENT_MISSING) } }
        }
    }
