package com.qlink.support.fixture

import com.qlink.ai.client.AiSummaryClientResponse
import com.qlink.ai.client.AiSummaryTodo
import com.qlink.ai.domain.AiProviderType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Instant

internal object AiCompatibilityFixture {
    fun summary(linkId: Long = RandomFixture.randomId()): AiSummaryClientResponse {
        val fallbackId = if (linkId == Long.MAX_VALUE) linkId - 1 else linkId + 1
        val title = RandomFixture.randomSentenceWithMax(30)
        val summary = RandomFixture.randomSentenceWithMax(50)
        val tags = listOf(RandomFixture.randomSentenceWithMax(12))
        val todos =
            listOf(
                AiSummaryTodo(title = RandomFixture.randomSentenceWithMax(30), reminderAt = RandomFixture.randomPastInstant()),
                AiSummaryTodo(title = RandomFixture.randomSentenceWithMax(30), reminderAt = null),
            )
        val body =
            buildJsonObject {
                put("id", fallbackId)
                put("linkId", linkId)
                put("folderId", null as Long?)
                put("title", title)
                put("summary", summary)
                put("tags", JsonArray(tags.map(::JsonPrimitive)))
                put(
                    "todos",
                    JsonArray(
                        todos.map {
                            buildJsonObject {
                                put("title", it.title)
                                put("reminderAt", it.reminderAt?.let(Instant::toString))
                            }
                        },
                    ),
                )
            }
        return AiSummaryClientResponse(
            linkId = linkId,
            folderId = null,
            rawResponse = body.toString(),
            title = title,
            summary = summary,
            tags = tags,
            todos = todos,
            usedTokens = 0,
        )
    }

    fun envelope(
        provider: AiProviderType,
        groups: List<List<String?>>? = null,
        usage: JsonObject? = null,
    ): String =
        buildJsonObject {
            when (provider) {
                AiProviderType.OPENAI -> {
                    groups?.let {
                        put(
                            "output",
                            JsonArray(
                                groups.map { texts ->
                                    buildJsonObject {
                                        put("type", "message")
                                        put("content", JsonArray(texts.map { text("output_text", it) }))
                                    }
                                },
                            ),
                        )
                    }
                    usage?.let { put("usage", it) }
                }

                else -> {
                    groups?.let {
                        put(
                            "candidates",
                            JsonArray(
                                groups.map { texts ->
                                    buildJsonObject {
                                        put(
                                            "content",
                                            buildJsonObject {
                                                put("parts", JsonArray(texts.map { text(null, it) }))
                                            },
                                        )
                                    }
                                },
                            ),
                        )
                    }
                    usage?.let { put("usageMetadata", it) }
                }
            }
        }.toString()

    private fun text(
        type: String?,
        text: String?,
    ): JsonObject =
        buildJsonObject {
            type?.let { put("type", it) }
            put("text", text)
        }
}
