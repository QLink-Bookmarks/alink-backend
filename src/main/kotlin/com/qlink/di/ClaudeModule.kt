package com.qlink.di

import com.anthropic.backends.AnthropicBackend
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.OkHttpClient
import com.anthropic.core.http.HttpClient
import com.qlink.ai.client.AiClient
import com.qlink.ai.client.AiClientRouter
import com.qlink.ai.client.ClaudeCurlClient
import com.qlink.ai.client.ClaudeSdkClient
import com.qlink.config.ClaudeConfig
import com.qlink.config.configureClaudeCurl
import io.ktor.client.engine.cio.CIO
import io.ktor.server.config.ApplicationConfig
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope
import org.koin.dsl.onClose
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import io.ktor.client.HttpClient as KtorHttpClient

internal fun Module.registerClaude(config: ApplicationConfig) {
    val claudeConfig = ClaudeConfig(config)
    single { claudeConfig }

    if (claudeConfig.useSdk) {
        single<HttpClient> {
            OkHttpClient.builder().backend(AnthropicBackend.builder().build()).build()
        }

        single<AnthropicClient> { get<ClaudeConfig>().client(get()) } onClose { it?.close() }

        single(named<ClaudeSdkClient>()) { Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher() } onClose { it?.close() }

        single<AiClient>(named("claude")) {
            ClaudeSdkClient(
                client = get(),
                config = get(),
                dispatcher = get<ExecutorCoroutineDispatcher>(named<ClaudeSdkClient>()),
                log = LoggerFactory.getLogger(ClaudeSdkClient::class.java),
            )
        }
    } else {
        single(named<ClaudeCurlClient>()) { KtorHttpClient(CIO) { configureClaudeCurl() } } onClose { it?.close() }

        single<AiClient>(named("claude")) {
            ClaudeCurlClient(
                httpClient = get(named<ClaudeCurlClient>()),
                config = get(),
                log = LoggerFactory.getLogger(ClaudeCurlClient::class.java),
            )
        }
    }
}

internal fun Scope.aiClientRouter(clients: List<AiClient>): AiClientRouter =
    AiClientRouter(
        clients =
            clients + get<AiClient>(named("claude")),
    )
