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
    var onSourceLanguageDetected: ((String) -> Unit)? = null
    var onTurnComplete: (() -> Unit)? = null
    var onInterrupted: (() -> Unit)? = null
    var onStatusChanged: ((String) -> Unit)? = null

    companion object {
        private const val TAG = "ALADWebSocketManager"
        private const val GEMINI_WS_URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        // 100 ms per chunk: retain up to ~2 seconds while the socket is reconnecting.
        private const val MAX_PENDING_AUDIO_CHUNKS = 20
        private const val MAX_RECONNECT_DELAY_SECONDS = 30L

    }

    private val reconnectExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "GeminiReconnect").apply { isDaemon = true }
    }
    private var reconnectFuture: ScheduledFuture<*>? = null
    private var reconnectAttempts = 0
    @Volatile private var lastDetectedSourceLanguage = ""
    private val audioLock = Any()
    private val pendingAudio = ArrayDeque<ByteArray>()

    @Volatile private var currentApiKey = ""
    @Volatile private var currentSourceLang = "auto"
    @Volatile private var currentTargetLang = "pt-BR"
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
        currentSourceLang = sourceLang.ifBlank { "auto" }
        currentTargetLang = targetLang
        currentDubbingMode = dubbingMode
        currentTargetDialect = targetDialect
        lastDetectedSourceLanguage = ""
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
                sendGeminiSetup(webSocket, currentSourceLang, currentTargetLang)
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
                                val detectedCode = inputTranscription?.let { transcription ->
                                    transcription.optString("languageCode")
                                        .ifBlank { transcription.optString("language_code") }
                                }.orEmpty()
                                if (detectedCode.isNotBlank() && detectedCode != lastDetectedSourceLanguage) {
                                    lastDetectedSourceLanguage = detectedCode
                                    Log.i(TAG, "Detected spoken source language: $detectedCode")
                                    onSourceLanguageDetected?.invoke(detectedCode)
                                }

                                val parts = (serverContent.optJSONObject("modelTurn")
                                    ?: serverContent.optJSONObject("model_turn"))?.optJSONArray("parts")
                                if (parts != null) {
                                    for (i in 0 until parts.length()) {
                                        val part = parts.optJSONObject(i) ?: continue
                                        // Translated captions come only from outputTranscription above.
                                        // modelTurn.parts.text is not guaranteed to be speech transcription.
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
                        val errorObject = json.optJSONObject("error")
                        val errMessage = errorObject?.optString("message", "Erro desconhecido") ?: "Erro desconhecido"
                        val errorCode = errorObject?.optString("code", "")?.takeIf { it.isNotBlank() && it != "0" }
                        val errorStatus = errorObject?.optString("status", "")?.takeIf { it.isNotBlank() }
                        val details = listOfNotNull(errorCode, errorStatus, errMessage).distinct().joinToString(" — ")
                        Log.e(TAG, "Gemini API error: $details")
                        onStatusChanged?.invoke("Erro Gemini: $details")
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
                if (shouldReconnect) scheduleReconnect("Conexão encerrada (código $code${if (reason.isBlank()) "" else ": $reason"})")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket !== this@ALADWebSocketManager.webSocket) return
                isSetupComplete = false
                val detail = buildString {
                    append("falha no WebSocket")
                    if (response != null) append(" (HTTP ${response.code}${if (response.message.isNotBlank()) " ${response.message}" else ""})")
                    t.message?.takeIf { it.isNotBlank() }?.let { append(": $it") }
                }
                Log.e(TAG, "Gemini WebSocket failure (HTTP ${response?.code})", t)
                if (shouldReconnect) scheduleReconnect(detail)
                else onStatusChanged?.invoke("Erro: $detail")
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
        onStatusChanged?.invoke("Reconectando em ${delay}s: $reason")
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

    private fun sendGeminiSetup(socket: WebSocket, sourceLang: String, targetLang: String) {
        // The dedicated Live Translate model identifies the spoken source language automatically.
        // Keep the translation target fixed; a generic Portuguese target defaults to Brazilian Portuguese.
        val baseTargetLanguage = targetLang.substringBefore('-')
        val targetLanguageCode = when {
            baseTargetLanguage.equals("pt", ignoreCase = true) -> {
                currentTargetDialect.takeIf { it == "pt-BR" || it == "pt-PT" }
                    ?: targetLang.takeIf { it.contains('-') }
                    ?: "pt-BR"
            }
            targetLang.contains('-') -> targetLang
            else -> baseTargetLanguage
        }
        // Language picker stores short ISO language codes, while the transcription hint
        // expects BCP-47 tags. Use the regional code for Korean so recognition is biased
        // toward standard Korean rather than relying on automatic detection.
        val sourceLanguageHint = when (sourceLang.trim().lowercase()) {
            "", "auto" -> null
            "ko" -> "ko-KR"
            "pt" -> "pt-BR"
            "en" -> "en-US"
            "ja" -> "ja-JP"
            "de" -> "de-DE"
            "es" -> "es-ES"
            "fr" -> "fr-FR"
            "it" -> "it-IT"
            "zh" -> "cmn-Hans-CN"
            else -> sourceLang.trim()
        }
        val setupPayload = JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", "models/gemini-3.5-live-translate-preview")
                // Live Translate requires transcription and translation options inside
                // generationConfig. Keep OCR/text input out of this audio-only stream.
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().put("AUDIO"))
                    put("inputAudioTranscription", JSONObject().apply {
                        sourceLanguageHint?.let { put("languageCodes", JSONArray().put(it)) }
                    })
                    put("outputAudioTranscription", JSONObject())
                    put("translationConfig", JSONObject().apply {
                        put("targetLanguageCode", targetLanguageCode)
                        put("echoTargetLanguage", false)
                    })
                })
            })
        }
        if (!socket.send(setupPayload.toString())) {
            scheduleReconnect("Não foi possível enviar a configuração")
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
        for (index in queued.indices) {
            if (!sendAudioNow(socket, queued[index])) {
                // Restore the failed chunk and every later chunk in original order.
                // Previously the remainder of this batch was silently discarded.
                synchronized(audioLock) {
                    for (pendingIndex in queued.lastIndex downTo index) {
                        pendingAudio.addFirst(queued[pendingIndex])
                    }
                    while (pendingAudio.size > MAX_PENDING_AUDIO_CHUNKS) {
                        pendingAudio.removeLast()
                    }
                }
                scheduleReconnect("Fila de áudio não pôde ser enviada")
                return
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

    /** Final disposal when the foreground service is ending permanently. */
    @Synchronized
    fun shutdown() {
        disconnect()
        reconnectExecutor.shutdownNow()
    }
}
