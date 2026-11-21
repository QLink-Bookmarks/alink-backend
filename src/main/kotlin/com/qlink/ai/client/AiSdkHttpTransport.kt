package com.qlink.ai.client

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.time.Duration

internal class AiSdkHttpTransport : AutoCloseable {
    private val activeCall = ThreadLocal<CancellableContinuation<*>>()

    val client: OkHttpClient =
        OkHttpClient
            .Builder()
            .followRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(Duration.ofSeconds(5))
            .callTimeout(Duration.ofSeconds(15))
            .readTimeout(Duration.ZERO)
            .writeTimeout(Duration.ZERO)
            .addInterceptor { chain ->
                activeCall.get()?.invokeOnCancellation { chain.call().cancel() }
                chain.proceed(chain.request())
            }.addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (response.code == 503 && response.header("Retry-After")?.toIntOrNull() == 0) {
                    response.newBuilder().removeHeader("Retry-After").build()
                } else {
                    response
                }
            }.build()

    suspend fun <T> execute(block: () -> T): T =
        withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { continuation ->
                activeCall.set(continuation)
                try {
                    continuation.resumeWith(runCatching(block))
                } finally {
                    activeCall.remove()
                }
            }
        }

    override fun close() {
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
