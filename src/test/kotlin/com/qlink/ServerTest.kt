package com.qlink

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.qlink.auth.domain.Role
import com.qlink.common.error.ErrorCode
import com.qlink.common.transaction.TransactionRunner
import com.qlink.config.SecurityConfig
import com.qlink.folder.repository.FolderRepository
import com.qlink.foldermember.repository.FolderMemberRepository
import com.qlink.link.repository.LinkRepository
import com.qlink.support.ServiceTestEnvironment
import com.qlink.support.fixture.FolderFixture
import com.qlink.support.fixture.FolderMemberFixture
import com.qlink.support.fixture.LinkFixture
import com.qlink.support.fixture.RandomFixture
import com.qlink.support.fixture.TodoFixture
import com.qlink.support.fixture.UserFixture
import com.qlink.support.koinGet
import com.qlink.todo.repository.TodoRepository
import com.qlink.user.repository.UserRepository
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

class ServerTest :
    StringSpec({
        "application context starts with PostgreSQL testcontainer" {
            testApplication {
                environment {
                    config = ServiceTestEnvironment.applicationConfig()
                }

                client.get("/").status shouldBe HttpStatusCode.OK
            }
        }

        "folder privacy endpoints set no-store on authentication and request errors" {
            val config = ServiceTestEnvironment.applicationConfig()
            val userId = RandomFixture.randomId()
            val token =
                JWT
                    .create()
                    .withSubject(userId.toString())
                    .withClaim("role", Role.NORMAL.name)
                    .sign(Algorithm.HMAC256(SecurityConfig.from(config).jwtSecret))
            val path = "/api/folders/${RandomFixture.randomId()}/privacy"

            testApplication {
                environment { this.config = config }

                val unauthorized = client.get(path)
                unauthorized.status shouldBe HttpStatusCode.Unauthorized
                unauthorized.headers[HttpHeaders.CacheControl] shouldBe "no-store"

                val missingLink = client.get("/api/links/${RandomFixture.randomId()}")
                missingLink.status shouldBe HttpStatusCode.NotFound
                apiErrorCode(missingLink.bodyAsText()) shouldBe ErrorCode.LINK_NOT_FOUND.code

                val malformedBodies =
                    listOf("{}", """{"name":"공개 폴더","isPublic":1}""", """{"name":"공개 폴더","isPublic":"true"}""")
                for (body in malformedBodies) {
                    val badRequest =
                        client.put(path.removeSuffix("/privacy")) {
                            bearerAuth(token)
                            contentType(ContentType.Application.Json)
                            setBody(body)
                        }

                    badRequest.status shouldBe HttpStatusCode.BadRequest
                    badRequest.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                    apiErrorCode(badRequest.bodyAsText()) shouldBe ErrorCode.COMMON_BAD_REQUEST.code
                }

                val validRequest =
                    client.put(path.removeSuffix("/privacy")) {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody("""{"name":"공개 폴더","isPublic":null}""")
                    }
                validRequest.status shouldBe HttpStatusCode.NotFound
                apiErrorCode(validRequest.bodyAsText()) shouldBe ErrorCode.FOLDER_OWNER_NOT_FOUND.code

                val createBody = """{"name":"공개 폴더"}"""
                val createWithDefault =
                    client.post("/api/folders") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody(createBody)
                    }
                createWithDefault.status shouldBe HttpStatusCode.NotFound
                apiErrorCode(createWithDefault.bodyAsText()) shouldBe ErrorCode.FOLDER_OWNER_NOT_FOUND.code

                val createWithNull =
                    client.post("/api/folders") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody("""{"name":"공개 폴더","isPublic":null}""")
                    }
                createWithNull.status shouldBe HttpStatusCode.BadRequest
                apiErrorCode(createWithNull.bodyAsText()) shouldBe ErrorCode.COMMON_BAD_REQUEST.code

                val publicNotFound = client.get("/api/links?folderId=${RandomFixture.randomId()}&cursor=invalid")
                publicNotFound.status shouldBe HttpStatusCode.NotFound
                publicNotFound.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                apiErrorCode(publicNotFound.bodyAsText()) shouldBe ErrorCode.FOLDER_NOT_FOUND.code
            }
        }

        "existing read routes select the member or guest projection and keep writes authenticated" {
            val config = ServiceTestEnvironment.applicationConfig()
            val users = koinGet<UserRepository>()
            val folders = koinGet<FolderRepository>()
            val members = koinGet<FolderMemberRepository>()
            val links = koinGet<LinkRepository>()
            val todos = koinGet<TodoRepository>()
            val tx = koinGet<TransactionRunner>()
            val identities = tx.required { List(3) { users.insert(UserFixture.createRandomValidUser()).id!! } }
            val (owner, member, outsider) = identities
            val (folder, privateFolder) =
                tx.required {
                    val shared = folders.insert(FolderFixture.createSharedFolderOf(owner).setPublic(true)).id!!
                    members.insertIfAbsent(FolderMemberFixture.createMember(folderId = shared, userId = member))
                    shared to folders.insert(FolderFixture.createValidUnsharedFolder(owner)).id!!
                }
            val (publicLink, privateLink) =
                tx.required {
                    val visible = links.insert(LinkFixture.createRandomLinkOf(ownerId = owner, folderId = folder, isPublic = true)).id!!
                    val hidden = links.insert(LinkFixture.createRandomLinkOf(ownerId = owner, folderId = folder)).id!!
                    visible to hidden
                }
            val publicTodo =
                tx.required {
                    todos.insert(TodoFixture.createOverdueCompletedTodoOf(linkId = publicLink, ownerId = owner)).also {
                        todos.insert(TodoFixture.createRandomTodoOf(linkId = privateLink, ownerId = member))
                    }
                }
            val jwtSecret = SecurityConfig.from(config).jwtSecret

            fun token(userId: Long): String =
                JWT
                    .create()
                    .withSubject(userId.toString())
                    .withClaim("role", Role.NORMAL.name)
                    .sign(Algorithm.HMAC256(jwtSecret))

            val folderPath = "/api/folders/$folder"
            val detailPath = "/api/links/$publicLink"
            val linksPath = "/api/links?folderId=$folder&size=1"
            val todosPath = "/api/todos?folderId=$folder&size=1"
            try {
                testApplication {
                    environment { this.config = config }

                    val publicFolder = client.get(folderPath).readData()
                    publicFolder.keys shouldBe setOf("id", "name", "emoji")
                    publicFolder.getValue("id").jsonPrimitive.long shouldBe folder
                    client.get(folderPath) { bearerAuth(token(outsider)) }.readData() shouldBe publicFolder
                    for (viewer in listOf(owner, member)) {
                        client
                            .get(folderPath) { bearerAuth(token(viewer)) }
                            .readData()
                            .getValue("ownerId")
                            .jsonPrimitive.long shouldBe
                            owner
                        val detail = client.get(detailPath) { bearerAuth(token(viewer)) }.readData()
                        detail.containsKey("memo") shouldBe true
                        detail.getValue("isOwner").jsonPrimitive.content shouldBe (viewer == owner).toString()
                        client.get("/api/links/$privateLink") { bearerAuth(token(viewer)) }.status shouldBe HttpStatusCode.OK
                    }

                    val guestDetail = client.get(detailPath).readData()
                    client.get(detailPath) { bearerAuth(token(outsider)) }.readData() shouldBe guestDetail
                    guestDetail.keys shouldBe setOf("id", "folderId", "title", "url", "folderName", "folderEmoji", "todos")
                    val action =
                        guestDetail
                            .getValue("todos")
                            .jsonArray
                            .single()
                            .jsonObject
                    action.keys shouldBe setOf("title", "isCompleted")
                    action.getValue("title").jsonPrimitive.content shouldBe publicTodo.title
                    action.getValue("isCompleted").jsonPrimitive.content shouldBe "true"

                    val guestLinks = client.get(linksPath).readData()
                    client.get(linksPath) { bearerAuth(token(outsider)) }.readData() shouldBe guestLinks
                    guestLinks.getValue("hasNext").jsonPrimitive.content shouldBe "false"
                    val listedLink =
                        guestLinks
                            .getValue("contents")
                            .jsonArray
                            .single()
                            .jsonObject
                    listedLink.keys shouldBe setOf("id", "title", "url")
                    listedLink.getValue("id").jsonPrimitive.long shouldBe publicLink
                    val memberLinks = client.get(linksPath) { bearerAuth(token(member)) }.readData()
                    memberLinks.getValue("hasNext").jsonPrimitive.content shouldBe "true"

                    val guestTodos = client.get(todosPath).readData()
                    client.get(todosPath) { bearerAuth(token(outsider)) }.readData() shouldBe guestTodos
                    guestTodos.getValue("hasNext").jsonPrimitive.content shouldBe "false"
                    val listedTodo =
                        guestTodos
                            .getValue("contents")
                            .jsonArray
                            .single()
                            .jsonObject
                    listedTodo.keys shouldBe setOf("linkId", "title", "isCompleted")
                    listedTodo.getValue("linkId").jsonPrimitive.long shouldBe publicLink
                    client
                        .get(todosPath) { bearerAuth(token(member)) }
                        .readData()
                        .getValue("contents")
                        .jsonArray
                        .single()
                        .jsonObject
                        .containsKey("reminderAt") shouldBe true

                    val missingLink = client.get("/api/links/${RandomFixture.randomId()}")
                    val hiddenLink = client.get("/api/links/$privateLink")
                    hiddenLink.status shouldBe HttpStatusCode.NotFound
                    hiddenLink.bodyAsText() shouldBe missingLink.bodyAsText()
                    client.get("/api/links/$privateLink") { bearerAuth(token(outsider)) }.bodyAsText() shouldBe missingLink.bodyAsText()
                    val missingFolder = client.get("/api/folders/${RandomFixture.randomId()}")
                    val hiddenFolder = client.get("/api/folders/$privateFolder")
                    hiddenFolder.status shouldBe HttpStatusCode.NotFound
                    hiddenFolder.bodyAsText() shouldBe missingFolder.bodyAsText()

                    for (path in listOf(folderPath, detailPath, linksPath, todosPath)) {
                        val malformed = client.get(path) { bearerAuth("invalid") }
                        malformed.status shouldBe HttpStatusCode.Unauthorized
                        malformed.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                    }

                    client
                        .get("/api/links?folderId=0") { bearerAuth(token(owner)) }
                        .readData()
                        .getValue("contents")
                        .jsonArray.size shouldBe 0
                    val api = Json.parseToJsonElement(client.get("/openapi").bodyAsText()).jsonObject
                    val apiPaths = api.getValue("paths").jsonObject
                    val apiSchemas =
                        api
                            .getValue("components")
                            .jsonObject
                            .getValue("schemas")
                            .jsonObject
                    for ((path, schemaName) in listOf(
                        "/api/folders/{id}" to "Folder",
                        "/api/links/{id}" to "Link",
                        "/api/links" to "Links",
                        "/api/todos" to "Todos",
                    )) {
                        val operation =
                            apiPaths
                                .getValue(path)
                                .jsonObject
                                .getValue("get")
                                .jsonObject
                        operation
                            .getValue("security")
                            .jsonArray
                            .first()
                            .jsonObject
                            .isEmpty() shouldBe true
                        val schema =
                            operation
                                .getValue("responses")
                                .jsonObject
                                .getValue("200")
                                .jsonObject
                                .getValue("content")
                                .jsonObject
                                .getValue("application/json")
                                .jsonObject
                                .getValue("schema")
                                .jsonObject
                        schema.getValue("anyOf").jsonArray.size shouldBe 2
                        apiSchemas.containsKey("Member$schemaName") shouldBe true
                        apiSchemas.containsKey("Guest$schemaName") shouldBe true
                    }

                    val oldRoute = client.get("/api/public-folders/$folder")
                    oldRoute.status shouldBe HttpStatusCode.NotFound
                    val actionPath = "/api/todos/${publicTodo.id}"
                    for ((method, path) in listOf(
                        HttpMethod.Get to "/api/folders",
                        HttpMethod.Get to "/api/links",
                        HttpMethod.Get to "/api/links?folderId=0",
                        HttpMethod.Get to "/api/todos",
                        HttpMethod.Get to "/api/todos/counts",
                        HttpMethod.Get to "$folderPath/privacy",
                        HttpMethod.Get to "$folderPath/members",
                        HttpMethod.Post to "/api/folders",
                        HttpMethod.Put to folderPath,
                        HttpMethod.Delete to folderPath,
                        HttpMethod.Post to folderPath,
                        HttpMethod.Put to "$folderPath/members",
                        HttpMethod.Delete to "$folderPath/members/$member",
                        HttpMethod.Put to "$folderPath/owner",
                        HttpMethod.Post to "/api/links",
                        HttpMethod.Put to "/api/links/ai",
                        HttpMethod.Put to detailPath,
                        HttpMethod.Patch to detailPath,
                        HttpMethod.Delete to detailPath,
                        HttpMethod.Put to "$detailPath/favorite",
                        HttpMethod.Post to "$detailPath/copy",
                        HttpMethod.Post to "/api/todos",
                        HttpMethod.Put to actionPath,
                        HttpMethod.Put to "$actionPath/completed",
                        HttpMethod.Delete to actionPath,
                    )) {
                        val denied = client.request(path) { this.method = method }
                        denied.status shouldBe HttpStatusCode.Unauthorized
                        apiErrorCode(denied.bodyAsText()) shouldBe ErrorCode.AUTH_ACCESS_TOKEN_MISSING.code
                    }

                    val outsiderWrite =
                        client.patch(detailPath) {
                            bearerAuth(token(outsider))
                            contentType(ContentType.Application.Json)
                            setBody("""{"isPublic":false}""")
                        }
                    outsiderWrite.status shouldBe HttpStatusCode.Forbidden

                    val move =
                        client.patch(detailPath) {
                            bearerAuth(token(owner))
                            contentType(ContentType.Application.Json)
                            setBody("""{"folderId":$privateFolder}""")
                        }
                    move.status shouldBe HttpStatusCode.OK
                    client.get(detailPath).status shouldBe HttpStatusCode.NotFound
                    client
                        .get(linksPath)
                        .readData()
                        .getValue("contents")
                        .jsonArray.size shouldBe 0
                    client
                        .get(todosPath)
                        .readData()
                        .getValue("contents")
                        .jsonArray.size shouldBe 0
                }
            } finally {
                ServiceTestEnvironment.start()
                val cleanupUsers = koinGet<UserRepository>()
                koinGet<TransactionRunner>().required { identities.forEach { cleanupUsers.deleteById(it) } }
            }
        }
    })

private fun apiErrorCode(json: String): String =
    Json
        .parseToJsonElement(json)
        .jsonObject
        .getValue("error")
        .jsonObject
        .getValue("code")
        .jsonPrimitive.content

private suspend fun HttpResponse.readData(): JsonObject {
    status shouldBe HttpStatusCode.OK
    headers[HttpHeaders.CacheControl] shouldBe "no-store"
    return Json
        .parseToJsonElement(bodyAsText())
        .jsonObject
        .getValue("data")
        .jsonObject
}
