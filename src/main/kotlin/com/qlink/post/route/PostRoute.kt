package com.qlink.post.route

import com.qlink.auth.domain.JwtPrincipal
import com.qlink.common.response.respondSuccess
import com.qlink.common.scroll.ScrollRequest
import com.qlink.plugin.jwtPrincipalOrGuest
import com.qlink.post.dto.CreatePostRequest
import com.qlink.post.dto.UpdatePostRequest
import com.qlink.post.service.CreatePostService
import com.qlink.post.service.DeletePostService
import com.qlink.post.service.GetPostService
import com.qlink.post.service.GetPostsService
import com.qlink.post.service.UpdatePostService
import io.github.smiley4.ktoropenapi.resources.delete
import io.github.smiley4.ktoropenapi.resources.get
import io.github.smiley4.ktoropenapi.resources.post
import io.github.smiley4.ktoropenapi.resources.put
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import org.koin.ktor.ext.inject

fun Route.postRoutes() {
    val createPostService by inject<CreatePostService>()
    val getPostService by inject<GetPostService>()
    val getPostsService by inject<GetPostsService>()
    val updatePostService by inject<UpdatePostService>()
    val deletePostService by inject<DeletePostService>()

    authenticate {
        put<PostResources.ById>(updatePostDocs()) { resource ->
            val response =
                updatePostService.updatePost(
                    loginId = call.principal<JwtPrincipal>()!!.userId,
                    id = resource.id,
                    request = call.receive<UpdatePostRequest>(),
                )
            call.respondSuccess(HttpStatusCode.OK, response)
        }
        delete<PostResources.ById>(deletePostDocs()) { resource ->
            deletePostService.deletePost(loginId = call.principal<JwtPrincipal>()!!.userId, id = resource.id)
            call.respondSuccess(HttpStatusCode.OK)
        }
        post<PostResources>(createPostDocs()) {
            val principal = call.principal<JwtPrincipal>()!!
            val request = call.receive<CreatePostRequest>()
            val response = createPostService.createPost(loginId = principal.userId, request = request)

            call.respondSuccess(HttpStatusCode.Created, response)
        }
    }

    authenticate(optional = true) {
        get<PostResources>(getPostsDocs()) { resource ->
            val principal = call.jwtPrincipalOrGuest()
            val response =
                getPostsService.getPosts(
                    loginId = principal.userId.takeIf { it > 0 },
                    type = resource.type,
                    query = resource.query,
                    orderKey = resource.orderKey,
                    order = resource.order,
                    scrollRequest =
                        ScrollRequest(
                            cursor = resource.cursor,
                            size = resource.size,
                        ),
                )

            call.respondSuccess(HttpStatusCode.OK, response)
        }

        get<PostResources.ById>(getPostDocs()) { resource ->
            val principal = call.jwtPrincipalOrGuest()
            val response = getPostService.getPost(postId = resource.id, loginId = principal.userId.takeIf { it > 0 })

            call.respondSuccess(HttpStatusCode.OK, response)
        }
    }
}
