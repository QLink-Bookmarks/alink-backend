package com.qlink.ai.client

import com.qlink.ai.domain.AiProviderType
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

internal suspend fun <T> withAiCompatibilityClient(
    provider: AiProviderType,
    sdk: Boolean,
    timeoutMillis: Long = 15_000,
    run: suspend (AiClient, AiCompatibilityServer) -> T,
): T =
    AiCompatibilityServer().use { server ->
        when {
            sdk && provider == AiProviderType.OPENAI -> {
                OpenAiSdkExperiment(timeoutMillis).use { run(it, server) }
            }

            sdk -> {
                GeminiSdkExperiment(timeoutMillis).use { run(it, server) }
            }

            else -> {
                HttpClient(CIO) {
                    followRedirects = false
                    expectSuccess = false
                    engine { requestTimeout = timeoutMillis }
                    install(ContentNegotiation) {
                        json(
                            Json {
                                ignoreUnknownKeys = true
                                encodeDefaults = true
                            },
                        )
                    }
                }.use { http ->
                    val client = if (provider == AiProviderType.OPENAI) OpenAiClient(http) else GeminiAiClient(http)
                    run(client, server)
                }
            }
        }
    }
