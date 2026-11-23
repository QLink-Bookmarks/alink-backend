package com.qlink.link.route

import com.qlink.ai.dto.AiSummaryRequest
import com.qlink.ai.service.UpdateLinkAiSummaryService
import com.qlink.auth.domain.JwtPrincipal
import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.response.respondSuccess
import com.qlink.common.scroll.ScrollRequest
import com.qlink.link.dto.CopyLinkRequest
import com.qlink.link.dto.CreateLinkRequest
import com.qlink.link.dto.PatchLinkRequest
import com.qlink.link.dto.SetLinkFavoriteRequest
import com.qlink.link.dto.SetLinkPrivacyRequest
import com.qlink.link.dto.UpdateLinkRequest
import com.qlink.link.service.CopyLinkService
import com.qlink.link.service.CreateLinkService
import com.qlink.link.service.DeleteLinkService
import com.qlink.link.service.GetLinkDetailService
import com.qlink.link.service.GetLinksService
import com.qlink.link.service.PatchLinkService
import com.qlink.link.service.SetLinkFavoriteService
import com.qlink.link.service.SetLinkPrivacyService
import com.qlink.link.service.UpdateLinkService
import com.qlink.plugin.respondMissingAccessToken
import io.github.smiley4.ktoropenapi.resources.delete
import io.github.smiley4.ktoropenapi.resources.get
import io.github.smiley4.ktoropenapi.resources.patch
import io.github.smiley4.ktoropenapi.resources.post
import io.github.smiley4.ktoropenapi.resources.put
import io.ktor.http.HttpStatusCode
import io.ktor.http.decodeURLQueryComponent
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.queryString
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import org.koin.ktor.ext.inject

fun Route.linkRoutes() {
    val createLinkService by inject<CreateLinkService>()
    val getLinkDetailService by inject<GetLinkDetailService>()
    val getLinksService by inject<GetLinksService>()
    val updateLinkService by inject<UpdateLinkService>()
    val setLinkPrivacyService by inject<SetLinkPrivacyService>()
    val patchLinkService by inject<PatchLinkService>()
    val deleteLinkService by inject<DeleteLinkService>()
    val setLinkFavoriteService by inject<SetLinkFavoriteService>()
    val copyLinkService by inject<CopyLinkService>()
    val updateLinkAiSummaryService by inject<UpdateLinkAiSummaryService>()

    authenticate {
        post<LinkResources>(createLinkDocs()) {
            val principal = call.principal<JwtPrincipal>()!!
            val request = call.receive<CreateLinkRequest>()
            val response = createLinkService.createLink(principal.userId, request)

            call.respondSuccess(HttpStatusCode.Created, response)
        }
    }

    authenticate(optional = true) {
        get<LinkResources>(getLinksDocs()) { resource ->
            val viewerId = call.principal<JwtPrincipal>()?.userId
            if (viewerId == null && (resource.folderId == null || resource.folderId == 0L)) {
                call.respondMissingAccessToken()
                return@get
            }
            val filterCount =
                call.request
                    .queryString()
                    .splitToSequence('&')
                    .count { it.substringBefore('=').decodeURLQueryComponent() == "isPublic" }
            val isPublic =
                if (filterCount == 0) {
                    null
                } else {
                    resource.isPublic
                        .singleOrNull()
                        ?.toBooleanStrictOrNull()
                        ?.takeIf { filterCount == 1 }
                        ?: throw BusinessException(ErrorCode.COMMON_INVALID_FILTER)
                }
            val scrollRequest = ScrollRequest(cursor = resource.cursor, size = resource.size)
            val member =
                viewerId?.let {
                    getLinksService.getMemberLinksOrNull(
                        viewerId = it,
                        query = resource.query,
                        folderId = resource.folderId,
                        isFavorite = resource.isFavorite,
                        isPublic = isPublic,
                        orderKey = resource.orderKey,
                        order = resource.order,
                        scrollRequest = scrollRequest,
                    )
                }
            if (member != null) {
                call.respondSuccess(HttpStatusCode.OK, member)
            } else {
                val folderId = resource.folderId ?: throw BusinessException(ErrorCode.AUTH_ACCESS_TOKEN_MISSING)
                val public =
                    getLinksService.getPublicLinks(
                        folderId = folderId,
                        query = resource.query,
                        isFavorite = resource.isFavorite,
                        isPublic = isPublic,
                        orderKey = resource.orderKey,
                        order = resource.order,
                        scrollRequest = scrollRequest,
                    )
                call.respondSuccess(HttpStatusCode.OK, public)
            }
        }

        get<LinkResources.ById>(getLinkDetailDocs()) { resource ->
            val viewerId = call.principal<JwtPrincipal>()?.userId
            val member = viewerId?.let { getLinkDetailService.getMemberLinkDetailOrNull(viewerId = it, linkId = resource.id) }
            if (member != null) {
                call.respondSuccess(HttpStatusCode.OK, member)
            } else {
                call.respondSuccess(HttpStatusCode.OK, getLinkDetailService.getPublicLinkDetail(resource.id))
            }
        }
    }

    authenticate {
        put<LinkResources.Ai>(updateLinkAiSummaryDocs()) {
            val principal = call.principal<JwtPrincipal>()!!
            val request = call.receive<AiSummaryRequest>()
            val response = updateLinkAiSummaryService.updateLinkAiSummary(principal.userId, request)

            call.respondSuccess(HttpStatusCode.OK, response)
        }
    }

    authenticate {
        put<LinkResources.ById>(updateLinkDocs()) { resource ->
            val principal = call.principal<JwtPrincipal>()!!
            val request = call.receive<UpdateLinkRequest>()
            val response = updateLinkService.updateLink(principal.userId, resource.id, request)

            call.respondSuccess(HttpStatusCode.OK, response)
        }
    }

    authenticate {
        patch<LinkResources.ById>(patchLinkDocs()) { resource ->
            val principal = call.principal<JwtPrincipal>()!!
            val request = call.receive<PatchLinkRequest>()
            val response = patchLinkService.patchLink(principal.userId, resource.id, request)

            call.respondSuccess(HttpStatusCode.OK, response)
        }
    }

    authenticate {
        put<LinkResources.ById.Privacy>(setLinkPrivacyDocs()) { resource ->
            val principal = call.principal<JwtPrincipal>()!!
            val request = call.receive<SetLinkPrivacyRequest>()
            val response =
                setLinkPrivacyService.setPrivacy(loginId = principal.userId, linkId = resource.parent.id, isPublic = request.isPublic)

            call.respondSuccess(HttpStatusCode.OK, response)
        }
    }

    authenticate {
        put<LinkResources.ById.Favorite>(setLinkFavoriteDocs()) { resource ->
            val principal = call.principal<JwtPrincipal>()!!
            val request = call.receive<SetLinkFavoriteRequest>()
            setLinkFavoriteService.setFavorite(principal.userId, resource.parent.id, request.isFavorite)

            call.respondSuccess(HttpStatusCode.OK)
        }
    }

    authenticate {
        post<LinkResources.ById.Copy>(copyLinkDocs()) { resource ->
            val principal = call.principal<JwtPrincipal>()!!
            val request = call.receive<CopyLinkRequest>()
            val response = copyLinkService.copyLink(principal.userId, resource.parent.id, request)

            call.respondSuccess(HttpStatusCode.Created, response)
        }
    }

    authenticate {
        delete<LinkResources.ById>(deleteLinkDocs()) { resource ->
            val principal = call.principal<JwtPrincipal>()!!
            deleteLinkService.deleteLink(principal.userId, resource.id)

            call.respondSuccess(HttpStatusCode.OK)
        }
    }
}
