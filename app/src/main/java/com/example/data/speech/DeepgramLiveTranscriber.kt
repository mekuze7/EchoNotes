package com.example.data.speech

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class DeepgramLiveTranscriber(
    private val onFinalTranscript: (text: String, startMs: Long, endMs: Long) -> Unit,
    private val onInterimTranscript: (String) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val scope = CoroutineScope(Dispatchers.IO)
    private var webSocket: WebSocket? = null
    private var keepAliveJob: Job? = null
    private var closeSignal = CompletableDeferred<Unit>()
    @Volatile private var isPaused = false
    @Volatile private var isClosed = false

    suspend fun connect(apiKey: String, language: String) {
        require(apiKey.isNotBlank()) { "Add a Deepgram API key in Settings to use Deepgram captions." }
        require(language != "am") {
            "Deepgram captions do not support Amharic in this app. Select Auto or English, or use Gemini."
        }

        val languageParameter = when (language) {
            "en" -> "en-US"
            else -> "multi"
        }
        val request = Request.Builder()
            .url(
                "wss://api.deepgram.com/v1/listen" +
                    "?model=nova-3&encoding=linear16&sample_rate=16000&channels=1" +
                    "&interim_results=true&smart_format=true&language=$languageParameter" +
                    "&endpointing=200&utterance_end_ms=1000"
            )
            .header("Authorization", "Token $apiKey")
            .build()

        suspendCancellableCoroutine<Unit> { continuation ->
            val socket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (continuation.isActive) continuation.resume(Unit)
                    onStatus("Connected to Deepgram. Listening for live captions...")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleMessage(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    isClosed = true
                    closeSignal.complete(Unit)
                    if (continuation.isActive) {
                        val detail = response?.let { "HTTP ${it.code}: ${it.message}" }
                            ?: t.localizedMessage
                            ?: "Check your network."
                        continuation.resumeWithException(
                            IllegalStateException(
                                when (response?.code) {
                                    401, 403 -> "Deepgram rejected the API key. Check your key in Settings."
                                    402 -> "Deepgram account has no available balance or transcription access. Check billing."
                                    400 -> "Deepgram rejected the live transcription request ($detail)."
                                    else -> "Deepgram connection failed: $detail"
                                },
                                t
                            )
                        )
                    } else {
                        val detail = response?.let { "HTTP ${it.code}: ${it.message}" }
                            ?: t.localizedMessage
                            ?: "Check your network."
                        onStatus("Deepgram connection lost: $detail")
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    isClosed = true
                    closeSignal.complete(Unit)
                    onInterimTranscript("")
                }
            })
            webSocket = socket
            continuation.invokeOnCancellation { socket.cancel() }
        }

        keepAliveJob = scope.launch {
            while (!isClosed) {
                delay(5_000)
                if (!isPaused) {
                    webSocket?.send("""{"type":"KeepAlive"}""")
                }
            }
        }
    }

    fun sendAudio(audio: ByteArray): Boolean {
        if (isClosed) return false
        return webSocket?.send(ByteString.of(*audio)) == true
    }

    fun setPaused(paused: Boolean) {
        isPaused = paused
    }

    suspend fun finish() {
        val socket = webSocket ?: return
        if (isClosed) return

        socket.send("""{"type":"CloseStream"}""")
        withTimeoutOrNull(2_000) { closeSignal.await() }
        socket.close(1000, "Recording finished")
        isClosed = true
        keepAliveJob?.cancel()
        onInterimTranscript("")
    }

    fun cancel() {
        isClosed = true
        keepAliveJob?.cancel()
        webSocket?.cancel()
        webSocket = null
    }

    private fun handleMessage(message: String) {
        when (val event = DeepgramMessageParser.parse(message)) {
            is DeepgramMessageEvent.Transcript -> {
                if (event.isFinal) {
                    onFinalTranscript(event.text, event.startMs, event.endMs)
                    onInterimTranscript("")
                } else {
                    onInterimTranscript(event.text)
                }
            }
            is DeepgramMessageEvent.Error -> onStatus(event.message)
            DeepgramMessageEvent.Ignore -> Unit
        }
    }
}

internal sealed interface DeepgramMessageEvent {
    data class Transcript(
        val text: String,
        val startMs: Long,
        val endMs: Long,
        val isFinal: Boolean
    ) : DeepgramMessageEvent

    data class Error(val message: String) : DeepgramMessageEvent
    data object Ignore : DeepgramMessageEvent
}

internal object DeepgramMessageParser {
    fun parse(message: String): DeepgramMessageEvent {
        val json = try {
            JSONObject(message)
        } catch (e: JSONException) {
            return DeepgramMessageEvent.Error("Deepgram returned an invalid response.")
        }

        return when (json.optString("type")) {
            "Error" -> DeepgramMessageEvent.Error(
                json.optString("err_msg").ifBlank {
                    json.optString("description").ifBlank { "Deepgram transcription failed." }
                }
            )
            "Results" -> {
                val transcript = json.optJSONObject("channel")
                    ?.optJSONArray("alternatives")
                    ?.optJSONObject(0)
                    ?.optString("transcript")
                    ?.trim()
                    .orEmpty()
                if (transcript.isBlank()) return DeepgramMessageEvent.Ignore

                val startMs = (json.optDouble("start", 0.0) * 1_000).toLong().coerceAtLeast(0)
                val durationMs = (json.optDouble("duration", 0.0) * 1_000).toLong()
                DeepgramMessageEvent.Transcript(
                    text = transcript,
                    startMs = startMs,
                    endMs = (startMs + durationMs).coerceAtLeast(startMs + 1),
                    isFinal = json.optBoolean("is_final")
                )
            }
            else -> DeepgramMessageEvent.Ignore
        }
    }
}
