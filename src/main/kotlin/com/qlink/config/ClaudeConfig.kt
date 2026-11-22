package com.qlink.config

import com.anthropic.client.AnthropicClient
import com.anthropic.client.AnthropicClientImpl
import com.anthropic.core.ClientOptions
import com.anthropic.core.LogLevel
import com.anthropic.core.http.HttpClient
import com.anthropic.core.http.HttpMethod
import com.anthropic.core.http.Interceptor
import com.anthropic.core.http.QueryParams
import com.anthropic.models.messages.CitationsConfigParam
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.WebFetchTool20260318
import com.fasterxml.jackson.databind.json.JsonMapper
import com.qlink.ai.client.AiSummarySpec
import com.qlink.ai.domain.AiProviderType
import io.ktor.server.config.ApplicationConfig
import java.net.URI
import java.net.URISyntaxException
import java.time.Duration
import kotlin.jvm.optionals.getOrNull

class ClaudeConfig(
    config: ApplicationConfig,
) {
    val sdkEndpoint: String? =
        runCatching {
            config.optionalString("ai.claude.sdkEndpoint") ?: System.getenv("AI_CLAUDE_SDK_ENDPOINT") ?: DEFAULT_BASE_URL
        }.getOrNull()?.takeIf(AiProviderType.CLAUDE::isAllowedBaseUrl)?.let { configured ->
            val endpoint = configured.trimEnd('/')
            if (endpoint.endsWith("/v1")) endpoint.removeSuffix("/v1") + "/" else configured
        }

    val maxOutputTokens: Long? =
        runCatching {
            config.optionalString("ai.claude.maxOutputTokens") ?: System.getenv("AI_CLAUDE_MAX_OUTPUT_TOKENS")
        }.getOrNull()?.toLongOrNull()?.takeIf { it > 0 }

    val outputConfig: OutputConfig =
        OutputConfig
            .builder()
            .format(
                JsonOutputFormat.of(
                    JsonMapper.builder().build().readValue(
                        AiSummarySpec.claudeJsonSchema.toString(),
                        JsonOutputFormat.Schema::class.java,
                    ),
                ),
            ).build()

    fun webFetch(sourceHost: String): WebFetchTool20260318 =
        WebFetchTool20260318
            .builder()
            .maxUses(1)
            .addAllowedCaller(WebFetchTool20260318.AllowedCaller.DIRECT)
            .addAllowedDomain(sourceHost)
            .citations(CitationsConfigParam.builder().enabled(false).build())
            .build()

    fun client(transport: HttpClient): AnthropicClient =
        AnthropicClientImpl(
            ClientOptions
                .builder()
                .httpClient(transport)
                .baseUrl(sdkEndpoint ?: DEFAULT_BASE_URL)
                .putHeader("anthropic-version", "2023-06-01")
                .maxRetries(0)
                .timeout(Duration.ofSeconds(15))
                .logLevel(LogLevel.OFF)
                .addInterceptor(sameOriginRedirect)
                .build(),
        )

    private val sameOriginRedirect =
        Interceptor.syncOnly { client, request, options ->
            val safeOptions = options.toBuilder().followRedirects(false).build()
            val response = client.execute(request, safeOptions)
            val statuses = if (request.method == HttpMethod.GET) setOf(301, 302, 303, 307, 308) else setOf(307, 308)
            if (response.statusCode() !in statuses || request.body().getOrNull()?.repeatable() == false) return@syncOnly response

            val location = response.headers().values("Location").singleOrNull() ?: return@syncOnly response
            val target = redirectTarget(request.url(), location) ?: return@syncOnly response
            response.close()
            client.execute(
                request
                    .toBuilder()
                    .baseUrl(target.toASCIIString())
                    .replaceAllPathSegments()
                    .queryParams(QueryParams.builder().build())
                    .build(),
                safeOptions,
            )
        }

    private fun redirectTarget(
        requestUrl: String,
        location: String,
    ): URI? {
        val origin = URI(requestUrl)
        val target =
            try {
                origin.resolve(URI(location))
            } catch (_: URISyntaxException) {
                return null
            }
        return target.takeIf {
            it.scheme == "https" &&
                it.host.equals(origin.host, ignoreCase = true) &&
                it.port in listOf(-1, 443) &&
                it.rawUserInfo == null &&
                it.rawFragment == null
        }
    }

    private companion object {
        const val DEFAULT_BASE_URL = "https://api.anthropic.com"
    }
}
