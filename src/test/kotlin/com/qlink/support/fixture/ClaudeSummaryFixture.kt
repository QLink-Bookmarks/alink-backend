package com.qlink.support.fixture

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Instant

class ClaudeSummaryFixture(
    val sourceUrl: String = "https://claude-source.example/${RandomFixture.randomId()}",
    val title: String = RandomFixture.randomSentenceWithMax(100),
    val summary: String = RandomFixture.randomSentenceWithMax(200),
    val tag: String = RandomFixture.randomSentenceWithMax(10).trim(),
    val scheduledAction: String = RandomFixture.randomSentenceWithMax(30),
    val unscheduledAction: String = RandomFixture.randomSentenceWithMax(30),
    val reminderAt: Instant = RandomFixture.randomFutureInstantAfterTomorrow(),
) {
    fun summaryJson(
        linkId: Long?,
        folderId: Long?,
    ): String =
        buildJsonObject {
            linkId?.let { put("id", it) }
            put("folderId", folderId)
            put("title", title)
            put("summary", summary)
            putJsonArray("tags") { add(tag) }
            putJsonArray("todos") {
                addJsonObject {
                    put("title", scheduledAction)
                    put("reminderAt", reminderAt.toString())
                }
                addJsonObject {
                    put("title", unscheduledAction)
                    put("reminderAt", null as String?)
                }
            }
        }.toString()

    fun messagesResponse(
        finalText: String,
        fetchUrl: String = sourceUrl,
        resultUrl: String = fetchUrl,
        resultId: String = TOOL_ID,
        fetchStatus: String? = "web_fetch_result",
        stopReason: String = "end_turn",
        includeCache: Boolean = true,
        inputTokens: Long = 11,
        fetchErrorCode: String? = null,
        editContent: ((JsonArray) -> List<JsonElement>)? = null,
        editUsage: ((JsonObject) -> JsonElement)? = null,
    ): String =
        buildJsonObject {
            put("stop_reason", stopReason)
            putJsonArray("content") {
                addJsonObject {
                    put("type", "text")
                    put("text", summaryJson(linkId = RandomFixture.randomId(), folderId = null))
                }
                addJsonObject {
                    put("type", "server_tool_use")
                    put("name", "web_fetch")
                    put("id", TOOL_ID)
                    putJsonObject("input") { put("url", fetchUrl) }
                }
                fetchStatus?.let {
                    addJsonObject {
                        put("type", "web_fetch_tool_result")
                        put("tool_use_id", resultId)
                        putJsonObject("content") {
                            put("type", fetchStatus)
                            if (fetchErrorCode == null) {
                                put("url", resultUrl)
                                putJsonObject("content") {
                                    put("type", "document")
                                    put("title", PAGE_MARKER)
                                    putJsonObject("source") {
                                        put("type", "base64")
                                        put("media_type", "application/pdf")
                                        put("data", PDF_MARKER)
                                    }
                                }
                            } else {
                                put("error_code", fetchErrorCode)
                            }
                        }
                    }
                }
                addJsonObject {
                    put("type", "text")
                    put("text", finalText)
                }
            }
            putJsonObject("usage") {
                put("input_tokens", inputTokens)
                put("output_tokens", 3)
                if (includeCache) {
                    put("cache_creation_input_tokens", 5)
                    put("cache_read_input_tokens", 7)
                    putJsonObject("cache_creation") {
                        put("ephemeral_5m_input_tokens", 3)
                        put("ephemeral_1h_input_tokens", 2)
                    }
                }
            }
        }.let { response ->
            val content = editContent?.let { JsonArray(it(response.getValue("content").jsonArray)) } ?: response.getValue("content")
            val usage = editUsage?.invoke(response.getValue("usage").jsonObject) ?: response.getValue("usage")
            JsonObject(response + mapOf("content" to content, "usage" to usage)).toString()
        }

    companion object {
        const val PAGE_MARKER = "dummy-private-page-marker"
        const val PDF_MARKER = "ZHVtbXktcHJpdmF0ZS1wZGYtZGF0YQ=="
        private const val TOOL_ID = "srvtoolu_fixture"
    }
}
