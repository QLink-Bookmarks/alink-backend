package com.qlink.support.fixture

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue

class AiProviderServer : AutoCloseable {
    data class Request(
        val method: String,
        val path: String,
        val authorization: String?,
        val googleApiKey: String?,
        val body: JsonObject?,
        val connection: Int,
    )

    data class Reply(
        val status: Int = 200,
        val body: String = "",
        val location: String? = null,
        val retryAfter: String? = null,
    )

    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val received = CopyOnWriteArrayList<Request>()
    val requests: List<Request> get() = received.toList()
    val writes = LinkedBlockingQueue<Boolean>()
    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    @Volatile
    var reply: (Request) -> Reply = { Reply() }

    init {
        server.executor = executor
        server.createContext("/") { exchange ->
            exchange.use {
                val body = exchange.requestBody.bufferedReader().use { it.readText() }
                val request =
                    Request(
                        method = exchange.requestMethod,
                        path = exchange.requestURI.path,
                        authorization = exchange.requestHeaders.getFirst("Authorization"),
                        googleApiKey = exchange.requestHeaders.getFirst("x-goog-api-key"),
                        body = body.takeIf(String::isNotBlank)?.let { Json.parseToJsonElement(it).jsonObject },
                        connection = exchange.remoteAddress.port,
                    )
                received.add(request)
                val response = reply(request)
                exchange.responseHeaders.set("Content-Type", "application/json")
                response.location?.let { exchange.responseHeaders.set("Location", it) }
                response.retryAfter?.let { exchange.responseHeaders.set("Retry-After", it) }
                val bytes = response.body.toByteArray()
                writes.add(
                    try {
                        exchange.sendResponseHeaders(response.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                        exchange.responseBody.use { it.write(bytes) }
                        true
                    } catch (_: IOException) {
                        false
                    },
                )
            }
        }
        server.start()
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }
}
