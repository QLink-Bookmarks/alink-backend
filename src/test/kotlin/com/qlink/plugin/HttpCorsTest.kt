package com.qlink.plugin

import com.qlink.di.pluginModule
import com.qlink.support.fixture.RandomFixture
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.config.mergeWith
import io.ktor.server.config.yaml.YamlConfig
import io.ktor.server.testing.runTestApplication
import org.koin.ktor.plugin.KoinIsolated

private const val ADMIN_ORIGIN = "https://admin.archivelink.app"

class HttpCorsTest :
    BehaviorSpec({
        mapOf(
            "dev" to "https://dev.archivelink.app",
            "prod" to "https://archivelink.app",
        ).forEach { (profile, existingOrigin) ->
            Given("$profile 프로필의 CORS 설정") {
                When("관리자 웹 출처가 preflight를 보내면") {
                    val response = preflight(profile = profile, origin = ADMIN_ORIGIN)

                    Then("그 출처와 자격 증명을 허용한다") {
                        response.status shouldBe HttpStatusCode.OK
                        response.headers[HttpHeaders.AccessControlAllowOrigin] shouldBe ADMIN_ORIGIN
                        response.headers[HttpHeaders.AccessControlAllowCredentials] shouldBe "true"
                    }
                }

                When("기존 허용 출처가 preflight를 보내면") {
                    val response = preflight(profile = profile, origin = existingOrigin)

                    Then("그 출처를 그대로 허용한다") {
                        response.status shouldBe HttpStatusCode.OK
                        response.headers[HttpHeaders.AccessControlAllowOrigin] shouldBe existingOrigin
                    }
                }

                When("허용 목록에 없는 출처가 preflight를 보내면") {
                    val response = preflight(profile = profile, origin = unlistedOrigin())

                    Then("거절한다") {
                        response.status shouldBe HttpStatusCode.Forbidden
                        response.headers[HttpHeaders.AccessControlAllowOrigin].shouldBeNull()
                    }
                }
            }
        }
    })

private fun unlistedOrigin(): String = "https://cors-${RandomFixture.randomId()}.example.com"

private suspend fun preflight(
    profile: String,
    origin: String,
): HttpResponse {
    lateinit var response: HttpResponse
    runTestApplication {
        application {
            install(KoinIsolated) {
                modules(pluginModule(profileConfig(profile)))
            }
            configureHttp()
        }
        response =
            client.options("/") {
                header(HttpHeaders.Origin, origin)
                header(HttpHeaders.AccessControlRequestMethod, HttpMethod.Post.value)
                header(HttpHeaders.AccessControlRequestHeaders, "csrf_token, Alink-Requester, Authorization, Content-Type")
            }
    }
    return response
}

private fun profileConfig(profile: String) =
    requireNotNull(YamlConfig("application.yaml"))
        .mergeWith(requireNotNull(YamlConfig("application-$profile.yaml")))
