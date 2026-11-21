package com.qlink

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.qlink.auth.domain.Role
import com.qlink.common.error.ErrorCode
import com.qlink.config.SecurityConfig
import com.qlink.support.ServiceTestEnvironment
import com.qlink.support.fixture.FolderFixture
import com.qlink.support.fixture.RandomFixture
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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

                val publicNotFound = client.get("/api/public-folders/${FolderFixture.createPublicId()}/links?cursor=invalid")
                publicNotFound.status shouldBe HttpStatusCode.NotFound
                publicNotFound.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                apiErrorCode(publicNotFound.bodyAsText()) shouldBe ErrorCode.FOLDER_PUBLIC_NOT_FOUND.code
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
