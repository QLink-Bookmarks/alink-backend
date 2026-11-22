package com.qlink.di

import com.anthropic.backends.AnthropicBackend
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.OkHttpClient
import com.anthropic.core.http.HttpClient
import com.qlink.ai.client.AiClient
import com.qlink.ai.client.AiClientRouter
import com.qlink.ai.client.ClaudeSdkClient
import com.qlink.config.ClaudeConfig
import io.ktor.server.config.ApplicationConfig
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope
import org.koin.dsl.bind
import org.koin.dsl.onClose
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors

internal fun Module.registerClaudeSdk(config: ApplicationConfig) {
    single<HttpClient> {
        OkHttpClient.builder().backend(AnthropicBackend.builder().build()).build()
    }

    single { ClaudeConfig(config) }

    single<AnthropicClient> { get<ClaudeConfig>().client(get()) } onClose { it?.close() }

    single(named<ClaudeSdkClient>()) { Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher() } onClose { it?.close() }

    single {
        ClaudeSdkClient(
            client = get(),
            config = get(),
            dispatcher = get<ExecutorCoroutineDispatcher>(named<ClaudeSdkClient>()),
            log = LoggerFactory.getLogger(ClaudeSdkClient::class.java),
        )
    } bind AiClient::class
}

internal fun Scope.aiClientRouter(clients: List<AiClient>): AiClientRouter = AiClientRouter(clients = clients + get<ClaudeSdkClient>())
