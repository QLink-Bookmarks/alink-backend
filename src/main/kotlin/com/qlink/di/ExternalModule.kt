package com.qlink.di

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.lambda.LambdaClient
import aws.sdk.kotlin.services.s3.S3Client
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.net.url.Url
import com.qlink.ai.client.AiClient
import com.qlink.ai.client.AiClientRouter
import com.qlink.ai.client.GeminiAiClient
import com.qlink.ai.client.OpenAiClient
import com.qlink.auth.client.AppleAuthResourceClient
import com.qlink.auth.client.AuthResourceClientRouter
import com.qlink.auth.client.GoogleAuthResourceClient
import com.qlink.auth.client.KakaoAuthResourceClient
import com.qlink.auth.client.NaverAuthResourceClient
import com.qlink.auth.client.OidcIdTokenVerifier
import com.qlink.common.crypto.ApiKeyCipher
import com.qlink.common.error.ErrorCode
import com.qlink.config.LinkContentConfig
import com.qlink.config.LinkPreviewConfig
import com.qlink.config.MonitoringConfig
import com.qlink.config.NotificationConfig
import com.qlink.config.S3Config
import com.qlink.config.string
import com.qlink.image.service.PreviewImageUrlResolver
import com.qlink.image.storage.ImageStorage
import com.qlink.image.storage.S3ImageStorage
import com.qlink.link.client.LambdaLinkPreviewSyncClient
import com.qlink.link.client.LinkPreviewSyncClient
import com.qlink.linkcontent.client.LambdaLinkContentSyncClient
import com.qlink.linkcontent.client.LinkContentSyncClient
import com.qlink.plugin.ExternalHttpClientLogging
import com.qlink.push.client.ExpoPushClient
import com.qlink.push.client.FcmPushClient
import com.qlink.push.client.FirebaseInitializer
import com.qlink.push.client.PushNotificationSenderRouter
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.ApplicationConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.serialization.json.Json
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.slf4j.LoggerFactory

fun externalModule(
    config: ApplicationConfig,
    s3Config: S3Config = S3Config.from(config),
) = module {
    val notificationConfig = NotificationConfig.from(config)

    single {
        val monitoringConfig = get<MonitoringConfig>()

        HttpClient(CIO) {
            followRedirects = false
            expectSuccess = false
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        encodeDefaults = true
                    },
                )
            }

            install(ExternalHttpClientLogging) {
                httpLogging = monitoringConfig.httpLogging
            }
        }
    }

    single {
        val keyBase64 =
            System
                .getenv("AI_API_KEY_ENCRYPTION_KEY_BASE64")
                ?.takeUnless { it.isBlank() }
                ?: config.string("security.aiApiKeyEncryptionKeyBase64")

        ApiKeyCipher(keyBase64 = keyBase64, invalidKeyErrorCode = ErrorCode.CONFIG_AI_API_KEY_ENCRYPTION_KEY_INVALID)
    }

    single {
        GeminiAiClient(
            httpClient = get(),
        )
    }

    single {
        OpenAiClient(
            httpClient = get(),
        )
    }

    single {
        KakaoAuthResourceClient(
            httpClient = get(),
        )
    }

    single {
        GoogleAuthResourceClient(
            httpClient = get(),
            googleConfig = get(),
            idTokenVerifierLog = LoggerFactory.getLogger(OidcIdTokenVerifier::class.java),
        )
    }

    single {
        NaverAuthResourceClient(
            httpClient = get(),
        )
    }

    single {
        AppleAuthResourceClient(
            httpClient = get(),
            appleConfig = get(),
            idTokenVerifierLog = LoggerFactory.getLogger(OidcIdTokenVerifier::class.java),
        )
    }

    single {
        AuthResourceClientRouter(
            clients =
                listOf(
                    get<KakaoAuthResourceClient>(),
                    get<GoogleAuthResourceClient>(),
                    get<NaverAuthResourceClient>(),
                    get<AppleAuthResourceClient>(),
                ),
        )
    }

    registerClaude(config)

    single {
        AiClientRouter(
            clients =
                listOf(
                    get<GeminiAiClient>(),
                    get<OpenAiClient>(),
                    get<AiClient>(named("claude")),
                ),
        )
    }

    single { LinkPreviewConfig.from(config) }

    single {
        val previewConfig = get<LinkPreviewConfig>()
        LambdaClient {
            region = previewConfig.region
            previewConfig.endpoint?.let { endpoint ->
                endpointUrl = Url.parse(endpoint)
                credentialsProvider = StaticCredentialsProvider(Credentials("local", "local"))
            }
        }
    }

    single<LinkPreviewSyncClient> {
        LambdaLinkPreviewSyncClient(
            lambdaClient = get(),
            config = get(),
            meterRegistry = get<PrometheusMeterRegistry>(),
            log = LoggerFactory.getLogger(LambdaLinkPreviewSyncClient::class.java),
        )
    }

    single { LinkContentConfig.from(config) }

    single<LinkContentSyncClient> {
        LambdaLinkContentSyncClient(
            lambdaClient = get(),
            config = get(),
            meterRegistry = get<PrometheusMeterRegistry>(),
            log = LoggerFactory.getLogger(LambdaLinkContentSyncClient::class.java),
        )
    }

    single {
        FirebaseInitializer(notificationConfig.fcm.serviceAccountJson)
    }

    single {
        get<FirebaseInitializer>().initializeIfConfigured()
        FcmPushClient(firebaseInitializer = get())
    }

    single {
        ExpoPushClient(
            httpClient = get(),
            sendUrl = notificationConfig.expo.sendUrl,
            accessToken = notificationConfig.expo.accessToken,
        )
    }

    single {
        PushNotificationSenderRouter(
            senders =
                listOf(
                    get<FcmPushClient>(),
                    get<ExpoPushClient>(),
                ),
        )
    }

    single { s3Config }

    single {
        buildS3Client(s3Config)
    }

    single<ImageStorage> {
        S3ImageStorage(
            s3Client = get(),
            config = s3Config,
        )
    }

    single {
        PreviewImageUrlResolver(config = s3Config)
    }
}

private fun buildS3Client(config: S3Config): S3Client =
    S3Client {
        region = config.region
        forcePathStyle = config.forcePathStyle

        config.endpoint?.let { endpoint ->
            endpointUrl = Url.parse(endpoint)
            credentialsProvider = StaticCredentialsProvider(Credentials("local", "local"))
        }
    }
