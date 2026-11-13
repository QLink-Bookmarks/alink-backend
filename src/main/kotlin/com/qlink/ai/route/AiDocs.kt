package com.qlink.ai.route

import com.qlink.ai.dto.AiProviderDetailResponse
import com.qlink.ai.dto.AiProviderModelsResponse
import com.qlink.ai.dto.AiProviderResponse
import com.qlink.ai.dto.CreateAiProviderRequest
import com.qlink.ai.dto.CreateAiResourceResponse
import com.qlink.ai.dto.CreateAvailableModelRequest
import com.qlink.ai.dto.PutAiUserProviderRequest
import com.qlink.ai.dto.PutAiUserProviderResponse
import com.qlink.ai.dto.UpdateAiActiveRequest
import com.qlink.ai.dto.UpdateAiActiveResponse
import com.qlink.ai.dto.UpdateAiProviderRequest
import com.qlink.ai.dto.UpdateAiProviderResponse
import com.qlink.ai.dto.UpdateAvailableModelRequest
import com.qlink.ai.dto.UpdateAvailableModelResponse
import com.qlink.common.docs.authErrorResponse
import com.qlink.common.docs.examples
import com.qlink.common.error.ErrorCode
import com.qlink.common.response.ApiResponse
import com.qlink.common.response.ErrorDetail
import io.github.smiley4.ktoropenapi.config.RouteConfig
import io.ktor.http.HttpStatusCode

internal fun getAiProvidersDocs(): RouteConfig.() -> Unit =
    {
        summary = "AI Provider 목록 조회 API"
        description = "활성 제공자를 조회합니다. includesInactive=true는 현재 관리자 권한이 필요합니다."
        request { queryParameter<Boolean>("includesInactive") { description = "비활성 포함 여부 (기본 false)" } }
        response {
            code(HttpStatusCode.OK) {
                description = "AI Provider 목록 조회 성공"
                body<ApiResponse<List<AiProviderResponse>>>()
            }
            authErrorResponse()
            code(HttpStatusCode.Forbidden) { body<ApiResponse<ErrorDetail>> { examples(ErrorCode.AI_ADMIN_REQUIRED) } }
            code(HttpStatusCode.NotFound) { body<ApiResponse<ErrorDetail>> { examples(ErrorCode.AI_ADMIN_USER_NOT_FOUND) } }
        }
    }

internal fun getAiProviderModelsDocs(): RouteConfig.() -> Unit =
    {
        summary = "AI Provider 설정 조회 API"
        description =
            "isMine=true 면 로그인 사용자의 AI Provider 설정을 조회하고, " +
            "isMine=false 면 인증 없이 AI Provider별 사용 가능 모델을 조회합니다."
        request {
            queryParameter<Boolean>("isMine") { description = "로그인 사용자 설정 조회 여부 (기본값 false)" }
        }
        response {
            code(HttpStatusCode.OK) {
                description = "AI Provider 설정 조회 성공"
                body<ApiResponse<List<AiProviderModelsResponse>>>()
            }
            authErrorResponse()
            code(HttpStatusCode.NotFound) {
                description = "로그인 사용자 조회 실패"
                body<ApiResponse<ErrorDetail>> {
                    examples(ErrorCode.USER_NOT_FOUND)
                }
            }
        }
    }

internal fun putAiUserProviderDocs(): RouteConfig.() -> Unit =
    {
        summary = "AI API Key 등록 API"
        request { body<PutAiUserProviderRequest>() }
        response {
            code(HttpStatusCode.Created) {
                description = "AI API Key 등록 성공"
                body<ApiResponse<PutAiUserProviderResponse>>()
            }
            code(HttpStatusCode.BadRequest) {
                description = "AI API Key 등록 요청 또는 API Key 검증 실패"
                body<ApiResponse<ErrorDetail>> {
                    examples(
                        ErrorCode.COMMON_BAD_REQUEST,
                        ErrorCode.AI_PROVIDER_NOT_SUPPORTED,
                        ErrorCode.AI_API_KEY_INVALID,
                    )
                }
            }
            authErrorResponse()
            code(HttpStatusCode.NotFound) {
                description = "AI API Key 등록 대상 리소스 조회 실패"
                body<ApiResponse<ErrorDetail>> {
                    examples(
                        ErrorCode.USER_NOT_FOUND,
                        ErrorCode.AI_PROVIDER_NOT_FOUND,
                    )
                }
            }
            code(HttpStatusCode.UnprocessableEntity) {
                description = "AI 제공자 비활성 또는 서비스 일시적 사용 불가"
                body<ApiResponse<ErrorDetail>> {
                    examples(ErrorCode.AI_PROVIDER_INACTIVE, ErrorCode.AI_VENDOR_TEMPORARY_UNAVAILABLE)
                }
            }
        }
    }

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

internal fun createAvailableModelDocs(): RouteConfig.() -> Unit =
    {
        summary = "AI 모델 추가 (현재 DB 관리자 권한 필요)"
        request { body<CreateAvailableModelRequest>() }
        response {
            code(HttpStatusCode.Created) { body<ApiResponse<CreateAiResourceResponse>>() }
            authErrorResponse()
            code(HttpStatusCode.BadRequest) {
                body<ApiResponse<ErrorDetail>> {
                    examples(
                        ErrorCode.AI_MODEL_NAME_BLANK,
                        ErrorCode.AI_MODEL_NAME_OVER_MAX,
                        ErrorCode.AI_MODEL_NAME_INVALID,
                        ErrorCode.AI_MODEL_PRIORITY_NOT_POSITIVE,
                        ErrorCode.AI_MODEL_LIMIT_NOT_POSITIVE,
                    )
                }
            }
            code(HttpStatusCode.Forbidden) { body<ApiResponse<ErrorDetail>> { examples(ErrorCode.AI_ADMIN_REQUIRED) } }
            code(HttpStatusCode.NotFound) {
                body<ApiResponse<ErrorDetail>> { examples(ErrorCode.AI_ADMIN_USER_NOT_FOUND, ErrorCode.AI_ADMIN_MODEL_PROVIDER_NOT_FOUND) }
            }
            code(HttpStatusCode.Conflict) { body<ApiResponse<ErrorDetail>> { examples(ErrorCode.AI_MODEL_DUPLICATE) } }
        }
    }

internal fun updateAvailableModelDocs(): RouteConfig.() -> Unit =
    {
        summary = "AI 모델 설정 수정 (현재 DB 관리자 권한 필요)"
        request { body<UpdateAvailableModelRequest>() }
        response {
            code(HttpStatusCode.OK) { body<ApiResponse<UpdateAvailableModelResponse>>() }
            authErrorResponse()
            code(HttpStatusCode.BadRequest) {
                body<ApiResponse<ErrorDetail>> { examples(ErrorCode.AI_MODEL_PRIORITY_NOT_POSITIVE, ErrorCode.AI_MODEL_LIMIT_NOT_POSITIVE) }
            }
            code(HttpStatusCode.Forbidden) { body<ApiResponse<ErrorDetail>> { examples(ErrorCode.AI_ADMIN_REQUIRED) } }
            code(HttpStatusCode.NotFound) {
                body<ApiResponse<ErrorDetail>> { examples(ErrorCode.AI_ADMIN_USER_NOT_FOUND, ErrorCode.AI_ADMIN_MODEL_NOT_FOUND) }
            }
        }
    }

internal fun updateAvailableModelActiveDocs(): RouteConfig.() -> Unit =
    {
        summary = "AI 모델 활성 상태 변경 (현재 DB 관리자 권한 필요)"
        request { body<UpdateAiActiveRequest>() }
        response {
            code(HttpStatusCode.OK) { body<ApiResponse<UpdateAiActiveResponse>>() }
            authErrorResponse()
            code(HttpStatusCode.Forbidden) { body<ApiResponse<ErrorDetail>> { examples(ErrorCode.AI_ADMIN_REQUIRED) } }
            code(HttpStatusCode.NotFound) {
                body<ApiResponse<ErrorDetail>> { examples(ErrorCode.AI_ADMIN_USER_NOT_FOUND, ErrorCode.AI_ADMIN_MODEL_NOT_FOUND) }
            }
        }
    }
