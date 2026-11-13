package com.qlink.support.fixture

import com.qlink.ai.client.AiSummaryClientResponse
import com.qlink.ai.client.AiSummaryTodo
import com.qlink.ai.domain.AiJob
import com.qlink.ai.domain.AiJobStatus
import com.qlink.ai.domain.AiProvider
import com.qlink.ai.domain.AiProviderType
import com.qlink.ai.domain.AvailableModel
import com.qlink.ai.dto.AiSummaryCompletion
import com.qlink.ai.dto.AiSummarySnapshot
import com.qlink.ai.dto.AiSummaryTodoRequest
import com.qlink.link.domain.Link
import com.qlink.todo.domain.RepeatDay
import kotlin.random.Random
import kotlin.time.Instant

object AiFixture {
    fun createRandomValidAiProvider(
        type: AiProviderType = randomAiProviderType(),
        isActive: Boolean = true,
        id: Long? = null,
    ): AiProvider =
        AiProvider(
            id = id,
            type = type,
            activeAt = RandomFixture.randomPastInstant().takeIf { isActive },
            baseUrl =
                when (type) {
                    AiProviderType.GEMINI -> "https://generativelanguage.googleapis.com"
                    AiProviderType.OPENAI -> "https://api.openai.com"
                    AiProviderType.CLAUDE -> "https://api.anthropic.com"
                } + "/v${RandomFixture.randomInt(1, 9)}",
        )

    fun createRandomAvailableModelOf(
        providerId: Long,
        isActive: Boolean = true,
        id: Long? = null,
        priority: Int = RandomFixture.randomInt(1, 100),
    ): AvailableModel =
        AvailableModel(
            id = id,
            activeAt = RandomFixture.randomPastInstant().takeIf { isActive },
            providerId = providerId,
            model = "model-${RandomFixture.randomId()}",
            priority = priority,
            rpdLimit = RandomFixture.randomInt(1, 1000),
            tpdLimit = RandomFixture.randomInt(1, 10_000_000),
        )

    fun createAiJobWith(
        requestedAt: Instant,
        status: AiJobStatus = AiJobStatus.P,
        startedAt: Instant? = null,
    ): AiJob =
        AiJob(
            id = RandomFixture.randomId(),
            linkId = RandomFixture.randomId(),
            userProviderId = RandomFixture.randomId(),
            requestModelId = RandomFixture.randomId(),
            requestedUrl = RandomFixture.randomUrl(),
            prompt = RandomFixture.randomSentenceWithMax(1000),
            status = status,
            requestedAt = requestedAt,
            startedAt = startedAt,
        )

    fun createPendingAiJobOf(
        link: Link,
        userProviderId: Long,
        modelId: Long,
    ): AiJob =
        AiJob(
            linkId = link.id!!,
            userProviderId = userProviderId,
            requestModelId = modelId,
            requestedUrl = link.url,
            prompt = RandomFixture.randomSentenceWithMax(1000),
            startedAt = RandomFixture.randomPastInstant(),
        )

    fun createAiSummaryCompletionOf(
        job: AiJob,
        link: Link,
        candidateFolderId: Long?,
    ): AiSummaryCompletion =
        AiSummaryCompletion(
            snapshot =
                AiSummarySnapshot(
                    jobId = job.id!!,
                    linkId = link.id!!,
                    folderId = link.folderId,
                ),
            responseModelId = job.requestModelId,
            candidateFolderId = candidateFolderId,
            response =
                AiSummaryClientResponse(
                    linkId = link.id,
                    folderId = candidateFolderId,
                    rawResponse = RandomFixture.randomSentenceWithMax(1000),
                    title = RandomFixture.randomSentenceWithMax(100),
                    summary = RandomFixture.randomSentenceWithMax(500),
                    tags = listOf(RandomFixture.randomSentenceWithMax(10)),
                    todos =
                        listOf(
                            AiSummaryTodo(
                                title = RandomFixture.randomSentenceWithMax(50),
                                reminderAt = RandomFixture.randomFutureInstantAfterTomorrow(),
                            ),
                        ),
                    usedTokens = RandomFixture.randomInt(1, 1000),
                ),
        )

    fun createAiSummaryTodoRequest(
        title: String = RandomFixture.randomSentenceWithMax(50),
        reminderAt: Instant? = RandomFixture.randomFutureInstantAfterTomorrow(),
        repeatUntil: Instant? = null,
        repeatDays: List<RepeatDay>? = null,
        repeatTime: String? = null,
        repeatTimezone: String? = null,
    ): AiSummaryTodoRequest =
        AiSummaryTodoRequest(
            title = title,
            reminderAt = reminderAt,
            repeatUntil = repeatUntil,
            repeatDays = repeatDays,
            repeatTime = repeatTime,
            repeatTimezone = repeatTimezone,
        )

    private fun randomAiProviderType(): AiProviderType = AiProviderType.entries[Random.nextInt(AiProviderType.entries.size)]
}

fun AiProvider.copyForTestActive(isActive: Boolean): AiProvider =
    AiProvider(
        id = id,
        type = type,
        baseUrl = baseUrl,
        activeAt = RandomFixture.randomPastInstant().takeIf { isActive },
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

fun AvailableModel.copyForTestActive(isActive: Boolean): AvailableModel =
    AvailableModel(
        id = id,
        providerId = providerId,
        model = model,
        priority = priority,
        rpdLimit = rpdLimit,
        tpdLimit = tpdLimit,
        activeAt = RandomFixture.randomPastInstant().takeIf { isActive },
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
