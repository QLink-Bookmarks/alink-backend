package com.qlink.ai.route

import com.qlink.ai.dto.CreateAiProviderRequest
import com.qlink.ai.dto.CreateAvailableModelRequest
import com.qlink.ai.dto.UpdateAiActiveRequest
import com.qlink.ai.dto.UpdateAiProviderRequest
import com.qlink.ai.dto.UpdateAvailableModelRequest
import com.qlink.ai.service.CreateAiProviderService
import com.qlink.ai.service.CreateAvailableModelService
import com.qlink.ai.service.GetAiProviderService
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

internal fun Route.aiAdminRoutes() {
    val getAiProvider by inject<GetAiProviderService>()
    val createAiProvider by inject<CreateAiProviderService>()
    val updateAiProvider by inject<UpdateAiProviderService>()
    val updateAiProviderActive by inject<UpdateAiProviderActiveService>()
    val createAvailableModel by inject<CreateAvailableModelService>()
    val updateAvailableModel by inject<UpdateAvailableModelService>()
    val updateAvailableModelActive by inject<UpdateAvailableModelActiveService>()
    authenticate {
        get<AiResources.Providers.ById>(getAiProviderDocs()) { resource ->
            val response = getAiProvider.getAiProvider(loginId = call.principal<JwtPrincipal>()!!.userId, id = resource.id)
            call.respondSuccess(HttpStatusCode.OK, response)
        }
        post<AiResources.Providers>(createAiProviderDocs()) {
            val response =
                createAiProvider.createAiProvider(
                    loginId = call.principal<JwtPrincipal>()!!.userId,
                    request = call.receive<CreateAiProviderRequest>(),
                )
            call.respondSuccess(HttpStatusCode.Created, response)
        }
        put<AiResources.Providers.ById>(updateAiProviderDocs()) { resource ->
            val response =
                updateAiProvider.updateAiProvider(
                    loginId = call.principal<JwtPrincipal>()!!.userId,
                    id = resource.id,
                    request = call.receive<UpdateAiProviderRequest>(),
                )
            call.respondSuccess(HttpStatusCode.OK, response)
        }
        put<AiResources.Providers.ById.Active>(updateAiProviderActiveDocs()) { resource ->
            val response =
                updateAiProviderActive.updateAiProviderActive(
                    loginId = call.principal<JwtPrincipal>()!!.userId,
                    id = resource.parent.id,
                    request = call.receive<UpdateAiActiveRequest>(),
                )
            call.respondSuccess(HttpStatusCode.OK, response)
        }
        post<AiResources.Models>(createAvailableModelDocs()) {
            val response =
                createAvailableModel.createAvailableModel(
                    loginId = call.principal<JwtPrincipal>()!!.userId,
                    request = call.receive<CreateAvailableModelRequest>(),
                )
            call.respondSuccess(HttpStatusCode.Created, response)
        }
        put<AiResources.Models.ById>(updateAvailableModelDocs()) { resource ->
            val response =
                updateAvailableModel.updateAvailableModel(
                    loginId = call.principal<JwtPrincipal>()!!.userId,
                    id = resource.id,
                    request = call.receive<UpdateAvailableModelRequest>(),
                )
            call.respondSuccess(HttpStatusCode.OK, response)
        }
        put<AiResources.Models.ById.Active>(updateAvailableModelActiveDocs()) { resource ->
            val response =
                updateAvailableModelActive.updateAvailableModelActive(
                    loginId = call.principal<JwtPrincipal>()!!.userId,
                    id = resource.parent.id,
                    request = call.receive<UpdateAiActiveRequest>(),
                )
            call.respondSuccess(HttpStatusCode.OK, response)
        }
    }
}
