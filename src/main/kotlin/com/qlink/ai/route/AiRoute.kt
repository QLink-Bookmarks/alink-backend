package com.qlink.ai.route

import com.qlink.ai.dto.CreateAiProviderRequest
import com.qlink.ai.dto.CreateAvailableModelRequest
import com.qlink.ai.dto.PutAiUserProviderRequest
import com.qlink.ai.dto.UpdateAiActiveRequest
import com.qlink.ai.dto.UpdateAiProviderRequest
import com.qlink.ai.dto.UpdateAvailableModelRequest
import com.qlink.ai.service.CreateAiProviderService
import com.qlink.ai.service.CreateAvailableModelService
import com.qlink.ai.service.GetAiProviderModelsService
import com.qlink.ai.service.GetAiProviderService
import com.qlink.ai.service.GetAiProvidersService
import com.qlink.ai.service.PutAiUserProviderService
import com.qlink.ai.service.UpdateAiProviderActiveService
import com.qlink.ai.service.UpdateAiProviderService
import com.qlink.ai.service.UpdateAvailableModelActiveService
import com.qlink.ai.service.UpdateAvailableModelService
import com.qlink.auth.domain.JwtPrincipal
import com.qlink.common.response.respondSuccess
import io.github.smiley4.ktoropenapi.resources.get
import io.github.smiley4.ktoropenapi.resources.post
import io.github.smiley4.ktoropenapi.resources.put
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import org.koin.ktor.ext.inject

fun Route.aiRoutes() {
    val getAiProvidersService by inject<GetAiProvidersService>()
    val getAiProviderModelsService by inject<GetAiProviderModelsService>()
    val putAiUserProviderService by inject<PutAiUserProviderService>()
    val getAiProviderService by inject<GetAiProviderService>()
    val createAiProviderService by inject<CreateAiProviderService>()
    val updateAiProviderService by inject<UpdateAiProviderService>()
    val updateAiProviderActiveService by inject<UpdateAiProviderActiveService>()
    val createAvailableModelService by inject<CreateAvailableModelService>()
    val updateAvailableModelService by inject<UpdateAvailableModelService>()
    val updateAvailableModelActiveService by inject<UpdateAvailableModelActiveService>()

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

    authenticate {
        get<AiResources.Providers.ById>(getAiProviderDocs()) { resource ->
            val response = getAiProviderService.getAiProvider(loginId = call.principal<JwtPrincipal>()!!.userId, id = resource.id)
            call.respondSuccess(HttpStatusCode.OK, response)
        }
        post<AiResources.Providers>(createAiProviderDocs()) {
            val response =
                createAiProviderService.createAiProvider(
                    loginId = call.principal<JwtPrincipal>()!!.userId,
                    request = call.receive<CreateAiProviderRequest>(),
                )
            call.respondSuccess(HttpStatusCode.Created, response)
        }
        put<AiResources.Providers.ById>(updateAiProviderDocs()) { resource ->
            val response =
                updateAiProviderService.updateAiProvider(
                    loginId = call.principal<JwtPrincipal>()!!.userId,
                    id = resource.id,
                    request = call.receive<UpdateAiProviderRequest>(),
                )
            call.respondSuccess(HttpStatusCode.OK, response)
        }
        put<AiResources.Providers.ById.Active>(updateAiProviderActiveDocs()) { resource ->
            val response =
                updateAiProviderActiveService.updateAiProviderActive(
                    loginId = call.principal<JwtPrincipal>()!!.userId,
                    id = resource.parent.id,
                    request = call.receive<UpdateAiActiveRequest>(),
                )
            call.respondSuccess(HttpStatusCode.OK, response)
        }
        post<AiResources.Models>(createAvailableModelDocs()) {
            val response =
                createAvailableModelService.createAvailableModel(
                    loginId = call.principal<JwtPrincipal>()!!.userId,
                    request = call.receive<CreateAvailableModelRequest>(),
                )
            call.respondSuccess(HttpStatusCode.Created, response)
        }
        put<AiResources.Models.ById>(updateAvailableModelDocs()) { resource ->
            val response =
                updateAvailableModelService.updateAvailableModel(
                    loginId = call.principal<JwtPrincipal>()!!.userId,
                    id = resource.id,
                    request = call.receive<UpdateAvailableModelRequest>(),
                )
            call.respondSuccess(HttpStatusCode.OK, response)
        }
        put<AiResources.Models.ById.Active>(updateAvailableModelActiveDocs()) { resource ->
            val response =
                updateAvailableModelActiveService.updateAvailableModelActive(
                    loginId = call.principal<JwtPrincipal>()!!.userId,
                    id = resource.parent.id,
                    request = call.receive<UpdateAiActiveRequest>(),
                )
            call.respondSuccess(HttpStatusCode.OK, response)
        }
    }
}
