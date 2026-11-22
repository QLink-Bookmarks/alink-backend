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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

class ClaudeHttpFixture : AutoCloseable {
    data class Request(
        val method: String,
        val url: URI,
        val headers: Headers,
        val body: String,
    )

    val requests = CopyOnWriteArrayList<Request>()
    val sdkRequestUrls = CopyOnWriteArrayList<URI>()

    @Volatile var modelsStatus: HttpStatusCode? = HttpStatusCode.OK

    @Volatile var messagesStatus: HttpStatusCode = HttpStatusCode.OK

    @Volatile var nextMessageStatus: HttpStatusCode? = null

    @Volatile var messagesBody = "{}"

    @Volatile var errorBody = """{"error":{"message":"$ERROR_MARKER"}}"""

    @Volatile var modelsRedirect: String? = null

    @Volatile var messagesRedirect: String? = null

    val pendingRequest = CompletableDeferred<Unit>()

    @Volatile var interrupted = false

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
                        ),
                    )
                    val isModelsRequest = it.requestMethod == "GET" && url.rawPath.endsWith("/v1/models")
                    val isMessagesRequest = it.requestMethod == "POST" && url.rawPath.endsWith("/v1/messages")
                    val status =
                        when {
                            isModelsRequest && modelsRedirect != null -> HttpStatusCode.Found
                            isModelsRequest -> modelsStatus ?: return@createContext
                            url.rawPath == "/redirected/models" -> HttpStatusCode.OK
                            isMessagesRequest && messagesRedirect != null -> HttpStatusCode.TemporaryRedirect
                            isMessagesRequest -> nextMessageStatus.also { nextMessageStatus = null } ?: messagesStatus
                            url.rawPath == "/redirected/messages" -> HttpStatusCode.OK
                            else -> HttpStatusCode.NotFound
                        }
                    if (status == HttpStatusCode.Found || status == HttpStatusCode.TemporaryRedirect) {
                        val redirect = if (isModelsRequest) modelsRedirect else messagesRedirect
                        it.responseHeaders.set("Location", redirect ?: "http://localhost:${it.localAddress.port}/redirect")
                    }
                    it.responseHeaders.set("Content-Type", "application/json")
                    val response =
                        when {
                            status.value !in 200..299 -> errorBody
                            isModelsRequest || url.rawPath == "/redirected/models" -> "{}"
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
            override fun execute(
                request: HttpRequest,
                requestOptions: RequestOptions,
            ): HttpResponse {
                if (holdResponse) {
                    pendingRequest.complete(Unit)
                    try {
                        CountDownLatch(1).await()
                    } catch (e: InterruptedException) {
                        interrupted = true
                        throw e
                    }
                }
                val url = URI(request.url())
                if (url.host != "api.anthropic.com" || url.scheme != "https" || url.port !in listOf(-1, 443)) {
                    throw AssertionError("Unexpected Claude origin: ${url.scheme}://${url.authority}")
                }
                sdkRequestUrls.add(url)
                val base = URI(request.baseUrl)
                val localBase =
                    "http://127.0.0.1:${server.address.port}${base.rawPath}" +
                        base.rawQuery?.let { "?$it" }.orEmpty()
                val local = request.toBuilder().baseUrl(localBase).build()
                return try {
                    client.execute(local, requestOptions)
                } catch (e: AnthropicIoException) {
                    throw AnthropicIoException(TRANSPORT_MARKER, e)
                }
            }
        }

    override fun close() {
        server.stop(0)
    }

    companion object {
        const val ERROR_MARKER = "dummy-private-provider-error"
        const val TRANSPORT_MARKER = "dummy-private-transport-detail"
    }
}
