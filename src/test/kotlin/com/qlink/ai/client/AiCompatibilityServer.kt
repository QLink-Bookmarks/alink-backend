package com.qlink.ai.client

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

internal class AiCompatibilityServer : AutoCloseable {
    @Serializable
    data class Request(
        val method: String,
        val path: String,
        val query: String?,
        val authorization: String?,
        val googleApiKey: String?,
        val body: JsonElement?,
        val connection: Int,
    )

    data class Reply(
        val status: Int = 200,
        val body: String = "",
        val location: String? = null,
    )

    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val captured = CopyOnWriteArrayList<Request>()
    val requests: List<Request> get() = captured.toList()
    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    @Volatile
    var reply: (Request) -> Reply = { Reply() }

    @Volatile
    var onResponseWrite: (Boolean) -> Unit = {}

    init {
        server.executor = executor
        server.createContext("/", ::handle)
        server.start()
    }

    fun saveCapture(name: String) {
        val directory = Path.of("build", "sdk-compatibility")
        Files.createDirectories(directory)
        val json = Json { prettyPrint = true }
        Files.writeString(directory.resolve("$name.json"), json.encodeToString(requests) + "\n")
    }

    private fun handle(exchange: HttpExchange) {
        exchange.use {
            val rawBody = it.requestBody.bufferedReader().use { reader -> reader.readText() }
            val request =
                Request(
                    method = it.requestMethod,
                    path = it.requestURI.rawPath,
                    query = it.requestURI.rawQuery,
                    authorization = it.requestHeaders.getFirst("Authorization"),
                    googleApiKey = it.requestHeaders.getFirst("x-goog-api-key"),
                    body = rawBody.takeIf(String::isNotEmpty)?.let(Json::parseToJsonElement),
                    connection = it.remoteAddress.port,
                )
            captured.add(request)
            val response = reply(request)
            it.responseHeaders.set("Content-Type", "application/json")
            response.location?.let { location -> it.responseHeaders.set("Location", location) }
            val bytes = response.body.toByteArray()
            try {
                it.sendResponseHeaders(response.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                it.responseBody.use { body -> body.write(bytes) }
                onResponseWrite(true)
            } catch (_: IOException) {
                onResponseWrite(false)
            }
        }
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }
}
