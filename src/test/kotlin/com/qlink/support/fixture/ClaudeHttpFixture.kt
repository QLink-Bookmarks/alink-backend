package com.qlink.support.fixture

import com.qlink.di.configureClaudeHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException

class ClaudeHttpFixture {
    val requests = mutableListOf<HttpRequestData>()
    var modelsStatus: HttpStatusCode? = HttpStatusCode.OK
    var messagesStatus: HttpStatusCode = HttpStatusCode.OK
    var messagesBody = "{}"

    val client =
        HttpClient(MockEngine) {
            configureClaudeHttpClient()
            engine {
                addHandler { request ->
                    requests.add(request)
                    val status =
                        if (request.url.encodedPath == "/v1/models") {
                            modelsStatus ?: throw IOException(TRANSPORT_MARKER)
                        } else {
                            messagesStatus
                        }
                    if (status == HttpStatusCode.Found) {
                        respond("", status, headersOf(HttpHeaders.Location, REDIRECT_URL))
                    } else {
                        val body = if (request.url.encodedPath == "/v1/models") "{}" else messagesBody
                        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                }
            }
        }

    companion object {
        const val REDIRECT_URL = "https://claude-redirect.example/redirect"
        const val TRANSPORT_MARKER = "dummy-private-transport-detail"
    }
}
