package com.qlink.ai.route

import com.qlink.ai.dto.PutAiUserProviderRequest
import com.qlink.ai.service.GetAiProviderModelsService
import com.qlink.ai.service.GetAiProvidersService
import com.qlink.ai.service.PutAiUserProviderService
import com.qlink.auth.domain.JwtPrincipal
import com.qlink.common.response.respondSuccess
import io.github.smiley4.ktoropenapi.resources.get
import io.github.smiley4.ktoropenapi.resources.put
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import org.koin.ktor.ext.inject

fun Route.aiRoutes() {
    aiAdminRoutes()
    val getAiProvidersService by inject<GetAiProvidersService>()
    val getAiProviderModelsService by inject<GetAiProviderModelsService>()
    val putAiUserProviderService by inject<PutAiUserProviderService>()

    authenticate(optional = true) {
        get<AiResources.Providers>(getAiProvidersDocs()) { resource ->
            val response =
                getAiProvidersService.getAiProviders(
                    loginId = call.principal<JwtPrincipal>()?.userId,
                    includesInactive = resource.includesInactive,
                )

            call.respondSuccess(HttpStatusCode.OK, response)
        }
    }

    authenticate(optional = true) {
        get<AiResources.Providers.Models>(getAiProviderModelsDocs()) { resource ->
            val principal = call.principal<JwtPrincipal>()
            val response =
                getAiProviderModelsService.getAiProviderModels(
                    loginId = principal?.userId,
                    isMine = resource.isMine,
                )

            call.respondSuccess(HttpStatusCode.OK, response)
        }
    }

    authenticate {
        put<AiResources.UserProviders>(putAiUserProviderDocs()) {
            val principal = call.principal<JwtPrincipal>()!!
            val request = call.receive<PutAiUserProviderRequest>()
            val response = putAiUserProviderService.putAiUserProvider(principal.userId, request)

            call.respondSuccess(HttpStatusCode.Created, response)
        }
    }
}
