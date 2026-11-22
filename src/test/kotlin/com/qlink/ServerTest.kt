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
import com.qlink.support.fixture.withPublication
import com.qlink.support.koinGet
import com.qlink.todo.repository.TodoRepository
import com.qlink.user.repository.UserRepository
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
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
import kotlinx.serialization.json.JsonPrimitive
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

        "privacy PUT requires auth and uses the regular Boolean request parser" {
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

                client.get(path).status shouldBe HttpStatusCode.NotFound
                val unauthorized = client.put(path)
                unauthorized.status shouldBe HttpStatusCode.Unauthorized
                unauthorized.headers[HttpHeaders.CacheControl] shouldBe null

                val missingLink = client.get("/api/links/${RandomFixture.randomId()}")
                missingLink.status shouldBe HttpStatusCode.NotFound
                missingLink.headers[HttpHeaders.CacheControl] shouldBe "no-store"
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
                    badRequest.headers[HttpHeaders.CacheControl] shouldBe null
                    apiErrorCode(badRequest.bodyAsText()) shouldBe ErrorCode.COMMON_BAD_REQUEST.code
                }

                for (privacyPath in listOf(path, "/api/links/${RandomFixture.randomId()}/privacy")) {
                    for (body in listOf(
                        "{}",
                        """{"isPublic":null}""",
                        """{"isPublic":1}""",
                        """{"isPublic":[]}""",
                    )) {
                        val response =
                            client.put(privacyPath) {
                                bearerAuth(token)
                                contentType(ContentType.Application.Json)
                                setBody(body)
                            }
                        response.status shouldBe HttpStatusCode.BadRequest
                        apiErrorCode(response.bodyAsText()) shouldBe ErrorCode.COMMON_BAD_REQUEST.code
                    }
                }

                val validRequest =
                    client.put(path.removeSuffix("/privacy")) {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody("""{"name":"공개 폴더","isShared":null}""")
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
                    val shared = folders.insert(FolderFixture.createSharedFolderOf(owner).withPublication()).id!!
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
                        val memberFolder = client.get(folderPath) { bearerAuth(token(viewer)) }.readData()
                        memberFolder.getValue("ownerId").jsonPrimitive.long shouldBe owner
                        memberFolder.getValue("isPublic") shouldBe JsonPrimitive(true)
                        memberFolder.containsKey("turnsPublicAt") shouldBe false
                        val detail = client.get(detailPath) { bearerAuth(token(viewer)) }.readData()
                        detail.containsKey("memo") shouldBe true
                        detail.getValue("isPublic") shouldBe JsonPrimitive(true)
                        detail.containsKey("turnsPublicAt") shouldBe false
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
                    for ((filter, id) in listOf(true to publicLink, false to privateLink)) {
                        val filtered = client.get("$linksPath&isPublic=$filter") { bearerAuth(token(member)) }
                        filtered.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                        val entry =
                            filtered
                                .readData()
                                .getValue("contents")
                                .jsonArray
                                .single()
                                .jsonObject
                        entry.getValue("id").jsonPrimitive.long shouldBe id
                        entry.getValue("isPublic") shouldBe JsonPrimitive(filter)
                        entry.containsKey("turnsPublicAt") shouldBe false
                    }
                    client.get("$linksPath&isPublic=true").readData() shouldBe guestLinks
                    for (viewer in listOf<Long?>(null, outsider)) {
                        val filtered = client.get("$linksPath&isPublic=false") { viewer?.let { bearerAuth(token(it)) } }
                        filtered.status shouldBe HttpStatusCode.OK
                        filtered
                            .readData()
                            .getValue("contents")
                            .jsonArray
                            .isEmpty() shouldBe true
                        filtered.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                    }
                    for (filter in listOf(
                        "isPublic",
                        "isPublic=",
                        "isPublic=TRUE",
                        "isPublic=1",
                        "isPublic=true&isPublic=true",
                        "isPublic=true&isPublic=false",
                    )) {
                        val rejected = client.get("$linksPath&$filter")
                        rejected.status shouldBe HttpStatusCode.BadRequest
                        rejected.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                        apiErrorCode(rejected.bodyAsText()) shouldBe ErrorCode.COMMON_INVALID_FILTER.code
                    }
                    val previousCursor = memberLinks.getValue("nextCursor").jsonPrimitive.content
                    val wrongCursor =
                        client.get("$linksPath&isPublic=true") {
                            bearerAuth(token(member))
                            url.parameters.append("cursor", previousCursor)
                        }
                    wrongCursor.status shouldBe HttpStatusCode.BadRequest
                    apiErrorCode(wrongCursor.bodyAsText()) shouldBe ErrorCode.COMMON_CURSOR_MALFORMED.code

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
                    for (path in listOf("/api/folders/{id}/privacy", "/api/links/{id}/privacy")) {
                        apiPaths.getValue(path).jsonObject.containsKey("put") shouldBe true
                        apiPaths.getValue(path).jsonObject.containsKey("get") shouldBe false
                    }
                    val linkListOperation =
                        apiPaths
                            .getValue("/api/links")
                            .jsonObject
                            .getValue("get")
                            .jsonObject
                    val publicFilter =
                        linkListOperation
                            .getValue("parameters")
                            .jsonArray
                            .single {
                                it.jsonObject
                                    .getValue("name")
                                    .jsonPrimitive.content == "isPublic"
                            }.jsonObject
                    publicFilter
                        .getValue("description")
                        .jsonPrimitive.content
                        .contains("COM_400_0003") shouldBe true
                    linkListOperation
                        .getValue("responses")
                        .jsonObject
                        .getValue("400")
                        .toString()
                        .contains("COM_400_0004") shouldBe true
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
                        HttpMethod.Get to "$folderPath/members",
                        HttpMethod.Post to "/api/folders",
                        HttpMethod.Put to folderPath,
                        HttpMethod.Put to "$folderPath/privacy",
                        HttpMethod.Delete to folderPath,
                        HttpMethod.Post to folderPath,
                        HttpMethod.Put to "$folderPath/members",
                        HttpMethod.Delete to "$folderPath/members/$member",
                        HttpMethod.Put to "$folderPath/owner",
                        HttpMethod.Post to "/api/links",
                        HttpMethod.Put to "/api/links/ai",
                        HttpMethod.Put to detailPath,
                        HttpMethod.Put to "$detailPath/privacy",
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
                            setBody("{}")
                        }
                    outsiderWrite.status shouldBe HttpStatusCode.Forbidden

                    suspend fun setPrivacy(
                        path: String,
                        isPublic: Boolean,
                    ) = client
                        .put("$path/privacy") {
                            bearerAuth(token(owner))
                            contentType(ContentType.Application.Json)
                            setBody("""{"isPublic":$isPublic}""")
                        }.readData(cacheControl = null)

                    val folderPublication = tx.readOnly { folders.findById(folder)?.turnsPublicAt }
                    val linkPublication = tx.readOnly { links.findById(publicLink)?.turnsPublicAt }
                    folderPublication shouldNotBe null
                    linkPublication shouldNotBe null
                    val published = JsonObject(mapOf("isPublic" to JsonPrimitive(true)))
                    setPrivacy(folderPath, true) shouldBe published
                    tx.readOnly { folders.findById(folder)?.turnsPublicAt } shouldBe folderPublication
                    setPrivacy(detailPath, true) shouldBe published
                    tx.readOnly { links.findById(publicLink)?.turnsPublicAt } shouldBe linkPublication
                    setPrivacy(detailPath, false) shouldBe JsonObject(mapOf("isPublic" to JsonPrimitive(false)))
                    tx.readOnly { links.findById(publicLink)?.turnsPublicAt } shouldBe null
                    client.get(detailPath).status shouldBe HttpStatusCode.NotFound
                    setPrivacy(detailPath, true) shouldBe published
                    val republished = tx.readOnly { links.findById(publicLink)?.turnsPublicAt }
                    republished shouldNotBe null
                    republished shouldNotBe linkPublication

                    val privateTitle = "비공개 AI 원본 ${RandomFixture.randomId()}"
                    val privateAction = "비공개 AI 액션 ${RandomFixture.randomId()}"
                    val privateUrl = "https://example.test/${RandomFixture.randomId()}"
                    val privateCreate =
                        client.post("/api/links") {
                            bearerAuth(token(owner))
                            contentType(ContentType.Application.Json)
                            setBody(
                                """{"folderId":$folder,"url":"$privateUrl","title":"$privateTitle","tags":[],"sourceType":"INPUT",""" +
                                    """"memo":"비공개 메모","isPublic":false,"todos":[{"title":"$privateAction"}]}""",
                            )
                        }
                    privateCreate.status shouldBe HttpStatusCode.Created
                    val privateId =
                        Json
                            .parseToJsonElement(privateCreate.bodyAsText())
                            .jsonObject
                            .getValue("data")
                            .jsonObject
                            .getValue("id")
                            .jsonPrimitive.long
                    val privatePath = "/api/links/$privateId"

                    suspend fun assertPrivateAiLink() {
                        val privateDetail = client.get(privatePath) { bearerAuth(token(owner)) }.readData()
                        privateDetail.getValue("isPublic") shouldBe JsonPrimitive(false)
                        privateDetail
                            .getValue("todos")
                            .jsonArray
                            .single()
                            .jsonObject
                            .getValue("title") shouldBe
                            JsonPrimitive(privateAction)
                        client.get(privatePath).status shouldBe HttpStatusCode.NotFound
                        client.get("/api/links?folderId=$folder").readContents().map { it.getValue("id").jsonPrimitive.long } shouldBe
                            listOf(publicLink)
                        client.get("/api/todos?folderId=$folder").readContents().map { it.getValue("title").jsonPrimitive.content } shouldBe
                            listOf(publicTodo.title)
                    }

                    assertPrivateAiLink()
                    val failedAi =
                        client.put("/api/links/ai") {
                            bearerAuth(token(owner))
                            contentType(ContentType.Application.Json)
                            setBody(
                                """{"id":$privateId,"folderId":$folder,"url":"$privateUrl",""" +
                                    """"userProviderId":${RandomFixture.randomId()},"modelId":${RandomFixture.randomId()}}""",
                            )
                        }
                    failedAi.status shouldBe HttpStatusCode.NotFound
                    apiErrorCode(failedAi.bodyAsText()) shouldBe ErrorCode.AI_USER_PROVIDER_NOT_FOUND.code
                    assertPrivateAiLink()

                    val move =
                        client.patch(detailPath) {
                            bearerAuth(token(owner))
                            contentType(ContentType.Application.Json)
                            setBody("""{"folderId":$privateFolder}""")
                        }
                    move.readData(cacheControl = null).getValue("isPublic") shouldBe JsonPrimitive(false)
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

        "folder-scoped lists recheck the same token after folder access is lost" {
            val config = ServiceTestEnvironment.applicationConfig()
            val users = koinGet<UserRepository>()
            val folders = koinGet<FolderRepository>()
            val members = koinGet<FolderMemberRepository>()
            val links = koinGet<LinkRepository>()
            val todos = koinGet<TodoRepository>()
            val tx = koinGet<TransactionRunner>()
            val identities = tx.required { List(2) { users.insert(UserFixture.createRandomValidUser()).id!! } }
            val (owner, member) = identities
            val algorithm = Algorithm.HMAC256(SecurityConfig.from(config).jwtSecret)

            fun token(userId: Long): String =
                JWT
                    .create()
                    .withSubject(userId.toString())
                    .withClaim("role", Role.NORMAL.name)
                    .sign(algorithm)

            val ownerToken = token(owner)
            val memberToken = token(member)
            val scopedFolders =
                tx.required {
                    listOf(owner to true, owner to false, member to true).map { (folderOwner, isPublic) ->
                        val collaborator = if (folderOwner == owner) member else owner
                        val folder = folders.insert(FolderFixture.createSharedFolderOf(folderOwner).withPublication(isPublic))
                        val folderId = folder.id!!
                        members.insertIfAbsent(FolderMemberFixture.createOwnerMember(folderId = folderId, userId = folderOwner))
                        members.insertIfAbsent(FolderMemberFixture.createMember(folderId = folderId, userId = collaborator))
                        for ((writer, linkVisibility) in listOf(owner to isPublic, member to false)) {
                            val link =
                                links.insert(
                                    LinkFixture.createRandomLinkOf(ownerId = writer, folderId = folderId, isPublic = linkVisibility),
                                )
                            todos.insert(TodoFixture.createOverdueCompletedTodoOf(linkId = link.id!!, ownerId = writer))
                        }
                        folder
                    }
                }
            try {
                testApplication {
                    environment { this.config = config }

                    for (folder in scopedFolders) {
                        val folderId = folder.id!!
                        val folderPath = "/api/folders/$folderId"
                        val linksPath = "/api/links?folderId=$folderId"
                        val todosPath = "/api/todos?folderId=$folderId"
                        val memberLinks = client.get(linksPath) { bearerAuth(memberToken) }.readContents()
                        val memberActions = client.get(todosPath) { bearerAuth(memberToken) }.readContents()
                        memberLinks.size shouldBe 2
                        memberLinks.all { "todoCounts" in it } shouldBe true
                        memberActions.single().containsKey("reminderAt") shouldBe true
                        val memberLinkId =
                            memberActions
                                .single()
                                .getValue("linkId")
                                .jsonPrimitive.long
                        val memberActionId =
                            memberActions
                                .single()
                                .getValue("id")
                                .jsonPrimitive.long
                        val ownerLinks = client.get(linksPath) { bearerAuth(ownerToken) }.readContents()
                        val ownerActions = client.get(todosPath) { bearerAuth(ownerToken) }.readContents()
                        ownerLinks.size shouldBe 2
                        ownerActions.single().containsKey("reminderAt") shouldBe true
                        val ownerLinkId =
                            ownerActions
                                .single()
                                .getValue("linkId")
                                .jsonPrimitive.long

                        val ownedLinkPath = "/api/links/$memberLinkId"
                        val beforeOwnedDetail = client.get(ownedLinkPath) { bearerAuth(memberToken) }.readData()

                        val accessChange =
                            if (folder.isOwnedBy(member)) {
                                client.put("$folderPath/owner") {
                                    bearerAuth(memberToken)
                                    contentType(ContentType.Application.Json)
                                    setBody("""{"ownerId":$owner}""")
                                }
                            } else {
                                client.delete("$folderPath/members/$member") {
                                    bearerAuth(if (folder.isPublished) ownerToken else memberToken)
                                }
                            }
                        accessChange.status shouldBe HttpStatusCode.OK

                        if (folder.isPublished) {
                            client.get(folderPath) { bearerAuth(memberToken) }.readData() shouldBe client.get(folderPath).readData()
                            val guestLinks = client.get(linksPath).readContents()
                            val guestActions = client.get(todosPath).readContents()
                            client.get(linksPath) { bearerAuth(memberToken) }.readContents() shouldBe guestLinks
                            client.get(todosPath) { bearerAuth(memberToken) }.readContents() shouldBe guestActions
                            guestLinks.single().keys shouldBe setOf("id", "title", "url")
                            guestLinks
                                .single()
                                .getValue("id")
                                .jsonPrimitive.long shouldBe ownerLinkId
                            guestActions.single().keys shouldBe setOf("linkId", "title", "isCompleted")
                            guestActions
                                .single()
                                .getValue("linkId")
                                .jsonPrimitive.long shouldBe ownerLinkId
                        } else {
                            for (path in listOf(folderPath, linksPath, todosPath)) {
                                val denied = client.get(path) { bearerAuth(memberToken) }
                                denied.status shouldBe HttpStatusCode.NotFound
                                denied.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                                apiErrorCode(denied.bodyAsText()) shouldBe ErrorCode.FOLDER_NOT_FOUND.code
                                denied.bodyAsText() shouldBe client.get(path).bodyAsText()
                            }
                        }

                        client.get(linksPath) { bearerAuth(ownerToken) }.readContents() shouldBe ownerLinks
                        client.get(todosPath) { bearerAuth(ownerToken) }.readContents() shouldBe ownerActions
                        val ownedDetail = client.get(ownedLinkPath) { bearerAuth(memberToken) }.readData()
                        ownedDetail shouldBe beforeOwnedDetail
                        ownedDetail.getValue("id").jsonPrimitive.long shouldBe memberLinkId
                        ownedDetail.getValue("isOwner").jsonPrimitive.content shouldBe "true"
                        ownedDetail.getValue("isPublic") shouldBe JsonPrimitive(false)
                        ownedDetail.containsKey("memo") shouldBe true
                        client.get(ownedLinkPath).status shouldBe HttpStatusCode.NotFound

                        val personalLinks = client.get("/api/links") { bearerAuth(memberToken) }.readContents()
                        val personalActions = client.get("/api/todos") { bearerAuth(memberToken) }.readContents()
                        personalLinks.any { it.getValue("id").jsonPrimitive.long == memberLinkId } shouldBe true
                        personalActions.any { it.getValue("id").jsonPrimitive.long == memberActionId } shouldBe true
                    }
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

private suspend fun HttpResponse.readData(cacheControl: String? = "no-store"): JsonObject {
    status shouldBe HttpStatusCode.OK
    headers[HttpHeaders.CacheControl] shouldBe cacheControl
    return Json
        .parseToJsonElement(bodyAsText())
        .jsonObject
        .getValue("data")
        .jsonObject
}

private suspend fun HttpResponse.readContents(): List<JsonObject> = readData().getValue("contents").jsonArray.map { it.jsonObject }
