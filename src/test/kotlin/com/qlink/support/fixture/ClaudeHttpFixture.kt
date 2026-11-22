package com.qlink.support.fixture

import com.anthropic.backends.AnthropicBackend
import com.anthropic.client.okhttp.OkHttpClient
import com.anthropic.core.RequestOptions
import com.anthropic.core.http.HttpClient
import com.anthropic.core.http.HttpRequest
import com.anthropic.core.http.HttpResponse
import com.anthropic.errors.AnthropicIoException
import com.qlink.config.configureClaudeCurl
import com.sun.net.httpserver.Headers
import com.sun.net.httpserver.HttpServer
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.HttpRequestPipeline
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import kotlinx.coroutines.channels.Channel
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import io.ktor.client.HttpClient as KtorHttpClient

class ClaudeHttpFixture : AutoCloseable {
    data class Request(
        val method: String,
        val url: URI,
        val headers: Headers,
        val body: String,
        val connectionPort: Int,
    )

    val requests = CopyOnWriteArrayList<Request>()
    val sdkRequestUrls = CopyOnWriteArrayList<URI>()
    val curlRequestUrls = CopyOnWriteArrayList<URI>()

    @Volatile var modelsStatus: HttpStatusCode? = HttpStatusCode.OK

    @Volatile var messagesStatus: HttpStatusCode = HttpStatusCode.OK

    @Volatile var nextMessageStatus: HttpStatusCode? = null

    @Volatile var messagesBody = "{}"

    @Volatile var errorBody = """{"error":{"message":"$ERROR_MARKER"}}"""

    @Volatile var modelsRedirect: String? = null

    @Volatile var messagesRedirect: String? = null

    val heldRequests = Channel<Request>(Channel.UNLIMITED)
    private val responseGate = CountDownLatch(1)
    private val serverExecutor = Executors.newVirtualThreadPerTaskExecutor()

    @Volatile var holdResponse = false

    @Volatile var holdBody = false

    @Volatile var holdRedirectBody = false

    @Volatile var heldPath: String? = null

    @Volatile var failKtorPipeline = false

    fun releaseResponses() = responseGate.countDown()

    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = serverExecutor
            createContext("/") { exchange ->
                exchange.use {
                    val url = URI("https://api.anthropic.com${it.requestURI}")
                    val body = it.requestBody.bufferedReader().use { reader -> reader.readText() }
                    val request =
                        Request(
                            method = it.requestMethod,
                            url = url,
                            headers = it.requestHeaders,
                            body = body,
                            connectionPort = it.remoteAddress.port,
                        )
                    requests.add(request)
                    val hold = holdResponse && (heldPath == null || heldPath == url.rawPath)
                    if (hold && !holdBody) {
                        heldRequests.trySend(request)
                        if (!responseGate.await(20, TimeUnit.SECONDS)) {
                            return@createContext
                        }
                    }
                    val isModelsRequest = it.requestMethod == "GET" && url.rawPath.endsWith("/models")
                    val isMessagesRequest = it.requestMethod == "POST" && url.rawPath.endsWith("/messages")
                    val status =
                        when {
                            url.rawPath.startsWith("/redirected/") -> HttpStatusCode.OK
                            isModelsRequest && modelsRedirect != null -> HttpStatusCode.Found
                            isModelsRequest -> modelsStatus ?: return@createContext
                            isMessagesRequest && messagesRedirect != null -> HttpStatusCode.TemporaryRedirect
                            isMessagesRequest -> nextMessageStatus.also { nextMessageStatus = null } ?: messagesStatus
                            else -> HttpStatusCode.NotFound
                        }
                    val isRedirect = status == HttpStatusCode.Found || status == HttpStatusCode.TemporaryRedirect
                    if (isRedirect) {
                        val redirect =
                            if (isModelsRequest) {
                                modelsRedirect
                            } else {
                                messagesRedirect
                            }
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
                        if ((hold && holdBody) || (holdRedirectBody && isRedirect)) {
                            heldRequests.trySend(request)
                            if (!responseGate.await(20, TimeUnit.SECONDS)) {
                                return@createContext
                            }
                        }
                        it.responseBody.write(response)
                    }
                }
            }
            start()
        }
    private val curlClient =
        lazy {
            KtorHttpClient(CIO) { configureClaudeCurl() }.apply {
                requestPipeline.intercept(HttpRequestPipeline.Before) {
                    if (failKtorPipeline) {
                        throw AssertionError(TRANSPORT_MARKER)
                    }
                    val url = URI(context.url.buildString())
                    assertClaudeOrigin(url)
                    curlRequestUrls.add(url)
                    context.url.protocol = URLProtocol.HTTP
                    context.url.host = "127.0.0.1"
                    context.url.port = server.address.port
                }
            }
        }

    val ktorTransport: KtorHttpClient get() = curlClient.value

    val transport: HttpClient by lazy {
        val client = OkHttpClient.builder().backend(AnthropicBackend.builder().build()).build()
        object : HttpClient by client {
            override fun execute(
                request: HttpRequest,
                requestOptions: RequestOptions,
            ): HttpResponse {
                val url = URI(request.url())
                assertClaudeOrigin(url)
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
    }

    private fun assertClaudeOrigin(url: URI) {
        if (url.host != "api.anthropic.com" || url.scheme != "https" || url.port !in listOf(-1, 443)) {
            throw AssertionError("Unexpected Claude origin: ${url.scheme}://${url.authority}")
        }
    }

    override fun close() {
        releaseResponses()
        if (curlClient.isInitialized()) {
            curlClient.value.close()
        }
        server.stop(0)
        serverExecutor.close()
        heldRequests.close()
    }

    companion object {
        const val ERROR_MARKER = "dummy-private-provider-error"
        const val TRANSPORT_MARKER = "dummy-private-transport-detail"
    }
}
