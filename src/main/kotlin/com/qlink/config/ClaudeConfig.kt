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
import com.qlink.ai.dto.ClaudeMessagesRequest
import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.server.config.ApplicationConfig
import java.net.URI
import java.net.URISyntaxException
import java.time.Duration
import kotlin.jvm.optionals.getOrNull

class ClaudeConfig(
    config: ApplicationConfig,
) {
    val useSdk: Boolean = readUseSdk(config)

    val sdkEndpoint: String? by lazy {
        runCatching {
            config.optionalString("ai.claude.sdkEndpoint") ?: System.getenv("AI_CLAUDE_SDK_ENDPOINT") ?: DEFAULT_BASE_URL
        }.getOrNull()?.takeIf(AiProviderType.CLAUDE::isAllowedBaseUrl)
    }

    val maxOutputTokens: Long? by lazy {
        runCatching {
            config.optionalString("ai.claude.maxOutputTokens") ?: System.getenv("AI_CLAUDE_MAX_OUTPUT_TOKENS")
        }.getOrNull()?.toLongOrNull()?.takeIf { it > 0 }
    }

    val outputConfig: OutputConfig by lazy {
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
    }

    internal val curlOutputConfig by lazy {
        ClaudeMessagesRequest.OutputConfig(
            ClaudeMessagesRequest.OutputFormat(type = "json_schema", schema = AiSummarySpec.claudeJsonSchema),
        )
    }

    internal fun curlWebFetch(sourceHost: String): ClaudeMessagesRequest.WebFetch =
        ClaudeMessagesRequest.WebFetch(
            type = "web_fetch_20260318",
            name = "web_fetch",
            maxUses = WEB_FETCH_MAX_USES,
            allowedCallers = listOf("direct"),
            allowedDomains = listOf(sourceHost),
            citations = ClaudeMessagesRequest.Citations(enabled = WEB_FETCH_CITATIONS),
        )

    fun webFetch(sourceHost: String): WebFetchTool20260318 =
        WebFetchTool20260318
            .builder()
            .maxUses(WEB_FETCH_MAX_USES)
            .addAllowedCaller(WebFetchTool20260318.AllowedCaller.DIRECT)
            .addAllowedDomain(sourceHost)
            .citations(CitationsConfigParam.builder().enabled(WEB_FETCH_CITATIONS).build())
            .build()

    fun client(transport: HttpClient): AnthropicClient =
        AnthropicClientImpl(
            ClientOptions
                .builder()
                .httpClient(transport)
                .baseUrl(sdkEndpoint ?: DEFAULT_BASE_URL)
                .putHeader(CLAUDE_VERSION_HEADER, CLAUDE_API_VERSION)
                .maxRetries(0)
                .timeout(Duration.ofMillis(CLAUDE_TIMEOUT_MILLIS))
                .logLevel(LogLevel.OFF)
                .addInterceptor(sameOriginRedirect)
                .build(),
        )

    private val sameOriginRedirect =
        Interceptor.syncOnly { client, request, options ->
            val safeOptions = options.toBuilder().followRedirects(false).build()
            val response = client.execute(request, safeOptions)
            val statuses =
                if (request.method == HttpMethod.GET) {
                    setOf(301, 302, 303, 307, 308)
                } else {
                    setOf(307, 308)
                }
            if (response.statusCode() !in statuses || request.body().getOrNull()?.repeatable() == false) {
                return@syncOnly response
            }

            val location = response.headers().values("Location").singleOrNull() ?: return@syncOnly response
            val target = claudeRedirectTarget(URI(request.url()), location) ?: return@syncOnly response
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

    private companion object {
        const val DEFAULT_BASE_URL = "https://api.anthropic.com"
        const val WEB_FETCH_MAX_USES = 1L
        const val WEB_FETCH_CITATIONS = false

        fun readUseSdk(config: ApplicationConfig): Boolean {
            val value =
                try {
                    config.optionalString("ai.claude.useSdk") ?: System.getenv("AI_CLAUDE_USE_SDK")
                } catch (_: RuntimeException) {
                    throw BusinessException(ErrorCode.CONFIG_CLAUDE_USE_SDK_INVALID)
                }
            return value?.let { it.toBooleanStrictOrNull() ?: throw BusinessException(ErrorCode.CONFIG_CLAUDE_USE_SDK_INVALID) } ?: true
        }
    }
}

internal const val CLAUDE_API_KEY_HEADER = "x-api-key"
internal const val CLAUDE_VERSION_HEADER = "anthropic-version"
internal const val CLAUDE_API_VERSION = "2023-06-01"
private const val CLAUDE_TIMEOUT_MILLIS = 15_000L

internal fun HttpClientConfig<*>.configureClaudeCurl() {
    followRedirects = false
    expectSuccess = false
    install(HttpTimeout) { requestTimeoutMillis = CLAUDE_TIMEOUT_MILLIS }
}

internal fun claudeRedirectTarget(
    origin: URI,
    location: String,
): URI? {
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
