package com.qlink.support.fixture

import com.anthropic.backends.AnthropicBackend
import com.anthropic.client.okhttp.OkHttpClient
import com.anthropic.core.RequestOptions
import com.anthropic.core.http.HttpClient
import com.anthropic.core.http.HttpRequest
import com.anthropic.core.http.HttpResponse
import com.anthropic.errors.AnthropicIoException
import com.sun.net.httpserver.Headers
import com.sun.net.httpserver.HttpServer
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

class ClaudeHttpFixture : AutoCloseable {
    data class Request(
        val method: String,
        val url: URI,
        val headers: Headers,
        val body: String,
    )

    val requests = CopyOnWriteArrayList<Request>()

    @Volatile var modelsStatus: HttpStatusCode? = HttpStatusCode.OK

    @Volatile var messagesStatus: HttpStatusCode = HttpStatusCode.OK

    @Volatile var nextMessageStatus: HttpStatusCode? = null

    @Volatile var messagesBody = "{}"

    val pendingRequest = CompletableDeferred<Unit>()
    val pendingResponse = CompletableFuture<HttpResponse>()

    @Volatile var holdResponse = false

    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                exchange.use {
                    val url = URI("https://api.anthropic.com${it.requestURI}")
                    val body = it.requestBody.bufferedReader().use { reader -> reader.readText() }
                    requests.add(Request(method = it.requestMethod, url = url, headers = it.requestHeaders, body = body))
                    val status =
                        if (url.path == "/v1/models") {
                            modelsStatus ?: return@createContext
                        } else {
                            nextMessageStatus.also { nextMessageStatus = null } ?: messagesStatus
                        }
                    if (status == HttpStatusCode.Found) {
                        it.responseHeaders.set("Location", "http://localhost:${it.localAddress.port}/redirect")
                    }
                    it.responseHeaders.set("Content-Type", "application/json")
                    val response =
                        when {
                            status.value !in 200..299 -> """{"error":{"message":"$ERROR_MARKER"}}"""
                            url.path == "/v1/models" -> "{}"
                            else -> messagesBody
                        }.toByteArray()
                    if (status == HttpStatusCode.NoContent) {
                        it.sendResponseHeaders(status.value, -1)
                    } else {
                        it.sendResponseHeaders(status.value, response.size.toLong())
                        it.responseBody.write(response)
                    }
                }
            }
            start()
        }
    private val client = OkHttpClient.builder().backend(AnthropicBackend.builder().build()).build()

    val transport =
        object : HttpClient by client {
            override fun executeAsync(
                request: HttpRequest,
                requestOptions: RequestOptions,
            ): CompletableFuture<HttpResponse> {
                if (holdResponse) {
                    pendingRequest.complete(Unit)
                    return pendingResponse
                }
                val url = URI(request.url())
                if (url.host != "api.anthropic.com") {
                    throw AssertionError("Unexpected Claude origin: ${url.host}")
                }
                val local = request.toBuilder().baseUrl("http://127.0.0.1:${server.address.port}").build()
                val response = client.executeAsync(local, requestOptions)
                if (url.path != "/v1/models" || modelsStatus != null) return response
                return response.exceptionally { throw AnthropicIoException(TRANSPORT_MARKER, it) }
            }
        }

    override fun close() = server.stop(0)

    companion object {
        const val ERROR_MARKER = "dummy-private-provider-error"
        const val TRANSPORT_MARKER = "dummy-private-transport-detail"
    }
}
