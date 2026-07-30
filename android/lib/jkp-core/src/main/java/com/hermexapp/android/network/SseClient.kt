package com.hermexapp.android.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

/**
 * Chat-stream events, mirroring the iOS `SSEEvent`. Event names and payload
 * shapes come from the iOS `SSEEventDecoder`, which is verified against the
 * pinned upstream `api/streaming.py`. Unknown event types map to [Ignored] —
 * new upstream events must never break the stream (hard rule #3).
 */
sealed class SseEvent {
    data class Token(val text: String) : SseEvent()
    data class InterimAssistant(val text: String?, val alreadyStreamed: Boolean?) : SseEvent()
    data class Reasoning(val text: String) : SseEvent()
    data class ToolStarted(val tool: ToolStreamEvent) : SseEvent()
    data class ToolCompleted(val tool: ToolStreamEvent) : SseEvent()
    data class Title(val sessionId: String?, val title: String?) : SseEvent()
    data class Done(val session: JsonElement?, val usage: JsonElement?) : SseEvent()
    data class PendingSteerLeftover(val text: String) : SseEvent()
    /** In-band approval/clarification prompt raised mid-stream. */
    data class ApprovalPending(val payload: JsonElement) : SseEvent()
    data class ClarificationPending(val payload: JsonElement) : SseEvent()
    data object StreamEnd : SseEvent()
    data object Cancelled : SseEvent()
    data class Error(val message: String) : SseEvent()
    data class TransportError(val message: String) : SseEvent()
    data object Ignored : SseEvent()
}

@Serializable
data class ToolStreamEvent(
    @SerialName("event_type") val eventType: String? = null,
    val name: String? = null,
    val preview: String? = null,
    val duration: Double? = null,
    @SerialName("is_error") val isError: Boolean? = null,
    // First non-empty of the upstream id aliases, same order as iOS.
    val tid: String? = null,
    val id: String? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    @SerialName("tool_use_id") val toolUseId: String? = null,
    @SerialName("call_id") val callId: String? = null,
) {
    val stableId: String?
        get() = listOf(tid, id, toolCallId, toolUseId, callId)
            .firstOrNull { !it?.trim().isNullOrEmpty() }
            ?.trim()
}

/** Parses one named SSE event into an [SseEvent] — the iOS `SSEEventDecoder` port. */
object SseEventParser {

    @Serializable
    private data class TextPayload(val text: String? = null)

    @Serializable
    private data class InterimPayload(
        val text: String? = null,
        @SerialName("already_streamed") val alreadyStreamed: Boolean? = null,
    )

    @Serializable
    private data class TitlePayload(
        @SerialName("session_id") val sessionId: String? = null,
        val title: String? = null,
    )

    @Serializable
    private data class DonePayload(
        val session: JsonElement? = null,
        val usage: JsonElement? = null,
    )

    @Serializable
    private data class ErrorPayload(val error: String? = null, val message: String? = null)

    fun parse(eventType: String, data: String): SseEvent = when (eventType) {
        "token" -> SseEvent.Token(decode<TextPayload>(data)?.text ?: "")
        "interim_assistant" -> decode<InterimPayload>(data).let {
            SseEvent.InterimAssistant(it?.text, it?.alreadyStreamed)
        }
        "reasoning" -> SseEvent.Reasoning(decode<TextPayload>(data)?.text ?: "")
        "tool" -> SseEvent.ToolStarted(decode<ToolStreamEvent>(data) ?: ToolStreamEvent())
        "tool_complete" -> SseEvent.ToolCompleted(decode<ToolStreamEvent>(data) ?: ToolStreamEvent())
        "title" -> decode<TitlePayload>(data).let { SseEvent.Title(it?.sessionId, it?.title) }
        "done" -> decode<DonePayload>(data).let { SseEvent.Done(it?.session, it?.usage) }
        "pending_steer_leftover" -> SseEvent.PendingSteerLeftover(decode<TextPayload>(data)?.text ?: "")
        // An in-band pending prompt: `initial` disambiguates by markers (like iOS),
        // `approval`/`clarify` are already typed.
        "initial" -> classifyPending(data)
        "approval" -> decodeElement(data)?.let { SseEvent.ApprovalPending(it) } ?: SseEvent.Ignored
        "clarify" -> decodeElement(data)?.let { SseEvent.ClarificationPending(it) } ?: SseEvent.Ignored
        "stream_end" -> SseEvent.StreamEnd
        "cancel" -> SseEvent.Cancelled
        "error" -> decode<ErrorPayload>(data).let {
            SseEvent.Error(it?.error ?: it?.message ?: "The stream returned an error.")
        }
        else -> SseEvent.Ignored
    }

    /** Mirrors the iOS `initial` branch: a clarification carries question/choices markers. */
    private fun classifyPending(data: String): SseEvent {
        val element = decodeElement(data) ?: return SseEvent.Ignored
        val obj = element as? kotlinx.serialization.json.JsonObject
        val candidate = (obj?.get("pending") as? kotlinx.serialization.json.JsonObject) ?: obj
        val looksLikeClarification = candidate?.let {
            it.containsKey("question") || it.containsKey("choices_offered") || it.containsKey("choicesOffered")
        } ?: false
        return if (looksLikeClarification) SseEvent.ClarificationPending(element)
        else SseEvent.ApprovalPending(element)
    }

    private fun decodeElement(data: String): JsonElement? = try {
        ApiJson.parseToJsonElement(data)
    } catch (_: Exception) {
        null
    }

    private inline fun <reified T> decode(data: String): T? = try {
        ApiJson.decodeFromString<T>(data)
    } catch (_: Exception) {
        null
    }
}

/** Seam for tests: what the chat view model needs from a streaming client. */
interface SseStreaming {
    fun start(url: HttpUrl, onEvent: (SseEvent) -> Unit)
    fun stop()
}

/**
 * okhttp-sse wrapper, the Android counterpart of the iOS `SSEClient` (which
 * wraps LDSwiftEventSource). One live stream at a time; `start` replaces any
 * previous stream. Events are delivered on OkHttp's reader thread — consumers
 * update thread-safe state (StateFlow).
 */
class SseClient(baseClient: OkHttpClient) : SseStreaming {

    // SSE streams outlive normal call timeouts: no read timeout, like the
    // EventSource defaults the iOS client relies on.
    private val client = baseClient.newBuilder()
        .readTimeout(java.time.Duration.ZERO)
        .build()

    private var source: EventSource? = null

    @Synchronized
    override fun start(url: HttpUrl, onEvent: (SseEvent) -> Unit) {
        stop()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache, no-transform")
            .build()

        source = EventSources.createFactory(client).newEventSource(
            request,
            object : EventSourceListener() {
                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String,
                ) {
                    onEvent(SseEventParser.parse(type.orEmpty(), data))
                }

                override fun onFailure(
                    eventSource: EventSource,
                    t: Throwable?,
                    response: Response?,
                ) {
                    onEvent(
                        SseEvent.TransportError(
                            t?.message ?: "The stream connection was lost.",
                        ),
                    )
                }
            },
        )
    }

    @Synchronized
    override fun stop() {
        source?.cancel()
        source = null
    }
}
