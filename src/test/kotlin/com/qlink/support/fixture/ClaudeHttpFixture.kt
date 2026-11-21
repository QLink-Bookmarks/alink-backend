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
import kotlin.time.TimeSource

class ClaudeHttpFixture : AutoCloseable {
    data class Request(
        val method: String,
        val url: URI,
        val headers: Headers,
        val body: String,
        val receivedAt: TimeSource.Monotonic.ValueTimeMark,
    )

    val requests = CopyOnWriteArrayList<Request>()
    val sdkRequestUrls = CopyOnWriteArrayList<URI>()

    @Volatile var modelsStatus: HttpStatusCode? = HttpStatusCode.OK

    @Volatile var messagesStatus: HttpStatusCode = HttpStatusCode.OK

    @Volatile var nextMessageStatus: HttpStatusCode? = null

    @Volatile var messagesBody = "{}"

    @Volatile var errorBody = """{"error":{"message":"$ERROR_MARKER"}}"""

    @Volatile var retryAfter: String? = null

    @Volatile var retryAfterMillis: String? = null

    val pendingRequest = CompletableDeferred<Unit>()
    val pendingResponse = CompletableFuture<HttpResponse>()

    @Volatile var holdResponse = false

    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                exchange.use {
                    val url = URI("https://api.anthropic.com${it.requestURI}")
                    val body = it.requestBody.bufferedReader().use { reader -> reader.readText() }
                    requests.add(
                        Request(
                            method = it.requestMethod,
                            url = url,
                            headers = it.requestHeaders,
                            body = body,
                            receivedAt = TimeSource.Monotonic.markNow(),
                        ),
                    )
                    val isModelsRequest = it.requestMethod == "GET" && url.rawPath.endsWith("/v1/models")
                    val isMessagesRequest = it.requestMethod == "POST" && url.rawPath.endsWith("/v1/messages")
                    val status =
                        when {
                            isModelsRequest -> modelsStatus ?: return@createContext
                            isMessagesRequest -> nextMessageStatus.also { nextMessageStatus = null } ?: messagesStatus
                            else -> HttpStatusCode.NotFound
                        }
                    if (status == HttpStatusCode.Found) {
                        it.responseHeaders.set("Location", "http://localhost:${it.localAddress.port}/redirect")
                    }
                    if (status == HttpStatusCode.TooManyRequests) {
                        retryAfter?.let { value -> it.responseHeaders.set("Retry-After", value) }
                        retryAfterMillis?.let { value -> it.responseHeaders.set("Retry-After-Ms", value) }
                    }
                    it.responseHeaders.set("Content-Type", "application/json")
                    val response =
                        when {
                            status.value !in 200..299 -> errorBody
                            isModelsRequest -> "{}"
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
                sdkRequestUrls.add(url)
                val basePath = URI(request.baseUrl).rawPath
                val local = request.toBuilder().baseUrl("http://127.0.0.1:${server.address.port}$basePath").build()
                val response = client.executeAsync(local, requestOptions)
                if (request.method.name != "GET" || modelsStatus != null) return response
                return response.exceptionally { throw AnthropicIoException(TRANSPORT_MARKER, it) }
            }
        }

    override fun close() = server.stop(0)

    companion object {
        const val ERROR_MARKER = "dummy-private-provider-error"
        const val TRANSPORT_MARKER = "dummy-private-transport-detail"
    }
}
