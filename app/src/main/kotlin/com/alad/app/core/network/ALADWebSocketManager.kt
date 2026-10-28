package com.alad.app.core.network

import android.util.Base64
import android.util.Log
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Maintains a Gemini Live WebSocket and reconnects after transient network/server failures.
 * Audio buffered during a reconnect is deliberately bounded to avoid latency and memory growth.
 */
class ALADWebSocketManager(private val client: OkHttpClient) {
    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var isSetupComplete = false
    @Volatile private var shouldReconnect = false

    var onBinaryMessageReceived: ((ByteArray) -> Unit)? = null
    var onTextMessageReceived: ((String) -> Unit)? = null
    var onSourceTextReceived: ((String) -> Unit)? = null
    var onTurnComplete: (() -> Unit)? = null
    var onInterrupted: (() -> Unit)? = null
    var onStatusChanged: ((String) -> Unit)? = null

    companion object {
        private const val TAG = "ALADWebSocketManager"
        private const val GEMINI_WS_URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        // 40 ms per chunk: retain up to ~2 seconds while the socket is reconnecting.
        private const val MAX_PENDING_AUDIO_CHUNKS = 50
        private const val MAX_RECONNECT_DELAY_SECONDS = 30L

        // Keep translation instructions concise: the live stream must stay responsive on mid-range phones.
        private const val SPEAKER_AWARE_INSTRUCTION = """
            Translate game dialogue faithfully into the requested target language with low latency.
            Continuously distinguish speakers using changes in vocal timbre, pitch range, resonance, accent,
            rhythm and speaking style; do not treat loudness alone as a new speaker. Keep a lightweight,
            consistent internal speaker map across nearby turns so replies from the same character remain
            associated with that voice, and never merge overlapping or consecutive characters into one line.
            For each utterance, estimate the audible vocal presentation as masculine, feminine, or uncertain
            only when the voice provides enough evidence. Use that estimate and the dialogue context to preserve
            grammatical gender where the target language requires it, but never present an uncertain guess as fact.
            Preserve vocal delivery and prosody: distinguish calm speech, excitement, anger, sadness, fear,
            surprise, questions, shouting and whispering when audible, without inventing emotion.
            Translate the meaning and intent, not just individual words; keep names and short replies, avoid
            repetition, and never add speaker labels or analysis to the spoken translation unless explicitly asked.
            Begin streaming partial translation as soon as reliable speech is available. Keep listening through
            pauses, include the final words when a character stops speaking, and do not wait unnecessarily for
            the whole scene to finish. Prioritize speaker consistency, completeness and natural spoken output.
        """
    }

    private val reconnectExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "GeminiReconnect").apply { isDaemon = true }
    }
    private var reconnectFuture: ScheduledFuture<*>? = null
    private var reconnectAttempts = 0
    private val audioLock = Any()
    private val pendingAudio = ArrayDeque<ByteArray>()

    @Volatile private var currentApiKey = ""
    @Volatile private var currentTargetLang = "pt-BR"
    @Volatile private var currentVoicePersona = "Puck"
    @Volatile private var currentDubbingMode = "HYBRID"
    @Volatile private var currentTargetDialect = ""

    @Synchronized
    fun connect(
        apiKey: String,
        sourceLang: String,
        targetLang: String,
        voicePersona: String = "Puck",
        dubbingMode: String = "HYBRID",
        targetDialect: String = ""
    ) {
        currentApiKey = apiKey
        currentTargetLang = targetLang
        currentVoicePersona = voicePersona
        currentDubbingMode = dubbingMode
        currentTargetDialect = targetDialect
        shouldReconnect = true
        reconnectAttempts = 0
        reconnectFuture?.cancel(false)
        reconnectFuture = null
        isSetupComplete = false
        val previous = webSocket
        webSocket = null
        previous?.cancel()
        synchronized(audioLock) { pendingAudio.clear() }
        openSocket()
    }

    @Synchronized
    private fun openSocket() {
        if (!shouldReconnect) return
        isSetupComplete = false
        val request = Request.Builder().url("$GEMINI_WS_URL?key=$currentApiKey").build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (webSocket !== this@ALADWebSocketManager.webSocket || !shouldReconnect) return
                Log.d(TAG, "Connected to Gemini Live API")
                onStatusChanged?.invoke("Connected")
                sendGeminiSetup(webSocket, currentTargetLang, currentVoicePersona)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket !== this@ALADWebSocketManager.webSocket || !shouldReconnect) return
                try {
                    val json = JSONObject(text)
                    if (json.has("serverContent") || json.has("server_content")) {
                        val serverContent = json.optJSONObject("serverContent") ?: json.optJSONObject("server_content")
                        if (serverContent != null) {
                            val interrupted = serverContent.optBoolean("interrupted", false)
                            if (interrupted) {
                                Log.i(TAG, "Live response interrupted; clearing queued speech and captions")
                                onInterrupted?.invoke()
                            } else {
                                val outputTranscription = serverContent.optJSONObject("outputTranscription")
                                    ?: serverContent.optJSONObject("output_transcription")
                                outputTranscription?.optString("text", "")?.takeIf { it.isNotEmpty() }?.let {
                                    onTextMessageReceived?.invoke(it)
                                }

                                val inputTranscription = serverContent.optJSONObject("inputTranscription")
                                    ?: serverContent.optJSONObject("input_transcription")
                                inputTranscription?.optString("text", "")?.takeIf { it.isNotEmpty() }?.let {
                                    onSourceTextReceived?.invoke(it)
                                }

                                val parts = (serverContent.optJSONObject("modelTurn")
                                    ?: serverContent.optJSONObject("model_turn"))?.optJSONArray("parts")
                                if (parts != null) {
                                    for (i in 0 until parts.length()) {
                                        val part = parts.optJSONObject(i) ?: continue
                                        part.optString("text", "").takeIf { it.isNotEmpty() }?.let {
                                            onTextMessageReceived?.invoke(it)
                                        }
                                        val inlineData = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data")
                                        val encoded = inlineData?.optString("data", "")
                                        if (!encoded.isNullOrEmpty()) {
                                            onBinaryMessageReceived?.invoke(Base64.decode(encoded, Base64.DEFAULT))
                                        }
                                    }
                                }
                            }

                            if (serverContent.optBoolean("turnComplete", false) ||
                                serverContent.optBoolean("turn_complete", false)) {
                                onTurnComplete?.invoke()
                            }
                        }
                    } else if (json.has("setupComplete") || json.has("setup_complete")) {
                        isSetupComplete = true
                        reconnectAttempts = 0
                        Log.d(TAG, "Gemini setup complete")
                        onStatusChanged?.invoke("Gemini Ready")
                        flushPendingAudio(webSocket)
                    } else if (json.has("error")) {
                        val errMessage = json.optJSONObject("error")?.optString("message", "Erro desconhecido")
                            ?: "Erro desconhecido"
                        Log.e(TAG, "Gemini API error: $errMessage")
                        onStatusChanged?.invoke("Error: $errMessage")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Could not parse Gemini message", e)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                onMessage(webSocket, bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket !== this@ALADWebSocketManager.webSocket) return
                isSetupComplete = false
                Log.w(TAG, "WebSocket closing: $code $reason")
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket !== this@ALADWebSocketManager.webSocket) return
                isSetupComplete = false
                if (shouldReconnect) scheduleReconnect("Conexão encerrada")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket !== this@ALADWebSocketManager.webSocket) return
                isSetupComplete = false
                Log.e(TAG, "Gemini WebSocket failure (HTTP ${response?.code})", t)
                if (shouldReconnect) scheduleReconnect("Falha na conexão")
                else onStatusChanged?.invoke("Error: ${t.message ?: "conexão perdida"}")
            }
        })
        webSocket = socket
    }

    @Synchronized
    private fun scheduleReconnect(reason: String) {
        if (!shouldReconnect || reconnectFuture?.isDone == false) return
        isSetupComplete = false
        reconnectAttempts++
        val delay = (1L shl (reconnectAttempts - 1).coerceAtMost(5)).coerceAtMost(MAX_RECONNECT_DELAY_SECONDS)
        Log.w(TAG, "$reason; reconnect attempt $reconnectAttempts in ${delay}s")
        onStatusChanged?.invoke("Reconnecting")
        reconnectFuture = reconnectExecutor.schedule({
            synchronized(this) {
                reconnectFuture = null
                if (shouldReconnect) {
                    val old = webSocket
                    webSocket = null
                    old?.cancel()
                    openSocket()
                }
            }
        }, delay, TimeUnit.SECONDS)
    }

    private fun sendGeminiSetup(socket: WebSocket, targetLang: String, voicePersona: String) {
        val targetLangCode = targetLang.split("-")[0]
        val setupPayload = JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", "models/gemini-3.5-live-translate-preview")
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", SPEAKER_AWARE_INSTRUCTION.trimIndent())))
                })
                // Detect speech onset quickly, keep a little pre-roll, and wait briefly for trailing words
                // before closing a turn. This balances fast partial translation with complete sentence endings.
                put("realtimeInputConfig", JSONObject().apply {
                    put("automaticActivityDetection", JSONObject().apply {
                        put("disabled", false)
                        put("startOfSpeechSensitivity", "START_SENSITIVITY_HIGH")
                        put("endOfSpeechSensitivity", "END_SENSITIVITY_LOW")
                        put("prefixPaddingMs", 160)
                        put("silenceDurationMs", 800)
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().put("AUDIO"))
                    put("speechConfig", JSONObject().apply {
                        put("voiceConfig", JSONObject().apply {
                            put("prebuiltVoiceConfig", JSONObject().apply { put("voiceName", voicePersona) })
                        })
                    })
                    put("translationConfig", JSONObject().apply {
                        put("targetLanguageCode", targetLangCode)
                        put("echoTargetLanguage", true)
                    })
                })
                put("inputAudioTranscription", JSONObject())
                put("outputAudioTranscription", JSONObject())
                put("sessionResumption", JSONObject().apply { put("handle", JSONObject.NULL) })
            })
        }
        if (!socket.send(setupPayload.toString())) {
            scheduleReconnect("Não foi possível enviar a configuração")
        }
    }

    /**
     * Sends a deduplicated on-screen subtitle as context, not as dialogue to translate.
     * The audio stream remains the primary source of truth.
     */
    fun sendSubtitleContext(subtitle: String) {
        val clean = subtitle.trim().take(500)
        if (clean.isEmpty() || !isSetupComplete) return
        val socket = webSocket ?: return
        // Use realtimeInput.text, not clientContent: clientContent can interrupt ongoing
        // model generation, cutting a dubbed sentence off whenever the on-screen caption changes.
        val payload = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put(
                    "text",
                    "SCREEN SUBTITLE CONTEXT ONLY (Portuguese text visible in the game): \"$clean\". " +
                        "This is visual context, not a spoken utterance or a request. Do not translate, repeat, or speak this text by itself. " +
                        "Use it only to clarify the concurrent English game audio."
                )
            })
        }
        if (!socket.send(payload.toString())) {
            Log.d(TAG, "Could not send subtitle context")
        }
    }

    fun sendAudioData(pcmData: ByteArray) {
        if (!isSetupComplete) {
            queueAudio(pcmData)
            return
        }
        val socket = webSocket
        if (socket == null || !sendAudioNow(socket, pcmData)) {
            queueAudio(pcmData)
            scheduleReconnect("Envio de áudio falhou")
        }
    }

    private fun sendAudioNow(socket: WebSocket, pcmData: ByteArray): Boolean {
        val inputPayload = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("audio", JSONObject().apply {
                    put("mimeType", "audio/pcm;rate=16000")
                    put("data", Base64.encodeToString(pcmData, Base64.NO_WRAP))
                })
            })
        }
        return socket.send(inputPayload.toString())
    }

    private fun queueAudio(pcmData: ByteArray) {
        synchronized(audioLock) {
            if (pendingAudio.size >= MAX_PENDING_AUDIO_CHUNKS) pendingAudio.removeFirst()
            pendingAudio.addLast(pcmData.copyOf())
        }
    }

    private fun flushPendingAudio(socket: WebSocket) {
        val queued = synchronized(audioLock) {
            val copy = pendingAudio.toList()
            pendingAudio.clear()
            copy
        }
        for (chunk in queued) {
            if (!sendAudioNow(socket, chunk)) {
                queueAudio(chunk)
                scheduleReconnect("Fila de áudio não pôde ser enviada")
                break
            }
        }
    }

    @Synchronized
    fun disconnect() {
        shouldReconnect = false
        isSetupComplete = false
        reconnectFuture?.cancel(false)
        reconnectFuture = null
        reconnectAttempts = 0
        synchronized(audioLock) { pendingAudio.clear() }
        val oldSocket = webSocket
        webSocket = null
        oldSocket?.close(1000, "User requested stop")
        onStatusChanged?.invoke("Disconnected")
    }
}
