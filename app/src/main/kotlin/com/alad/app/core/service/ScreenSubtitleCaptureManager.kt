package com.alad.app.core.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures only the lower area of the current game screen, runs OCR on-device,
 * and sends the recognized subtitle text (not screenshots) to Gemini for translation.
 */
class ScreenSubtitleCaptureManager(
    private val context: Context,
    private val projection: MediaProjection,
    private val apiKey: String,
    private val targetLanguage: String,
    private val targetDialect: String,
    private val onSourceText: (String) -> Unit,
    private val onTranslatedText: (String) -> Unit,
    private val onStatus: (String) -> Unit
) {
    companion object {
        private const val TAG = "ScreenSubtitleOCR"
        private const val MIN_FRAME_INTERVAL_MS = 550L
        private const val MIN_STABLE_FRAMES = 2
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val frameExecutor = Executors.newSingleThreadExecutor()
    private val translationExecutor = Executors.newSingleThreadExecutor()
    private val processingFrame = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private val translationLock = Any()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val latinRecognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val koreanRecognizer: TextRecognizer =
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())

    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var lastFrameAt = 0L
    private var candidateText = ""
    private var candidateNormalized = ""
    private var candidateCount = 0
    private var lastRequestedNormalized = ""
    private var currentSourceNormalized = ""
    private var pendingTranslationText: String? = null
    private var translationInProgress = false
    private var blankFrameCount = 0

    fun start() {
        if (apiKey.isBlank()) {
            reportStatus("Erro: configure a chave da API Gemini antes de usar OCR")
            return
        }
        try {
            val metrics = DisplayMetrics()
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi
            if (width < 1 || height < 1 || density < 1) {
                reportStatus("Erro: não foi possível obter o tamanho da tela")
                return
            }

            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            imageReader = reader
            reader.setOnImageAvailableListener({ source ->
                if (stopped.get()) {
                    source.acquireLatestImage()?.close()
                } else if (SystemClock.elapsedRealtime() - lastFrameAt < MIN_FRAME_INTERVAL_MS) {
                    source.acquireLatestImage()?.close()
                } else if (!processingFrame.compareAndSet(false, true)) {
                    source.acquireLatestImage()?.close()
                } else {
                    lastFrameAt = SystemClock.elapsedRealtime()
                    val image = source.acquireLatestImage()
                    if (image == null) {
                        processingFrame.set(false)
                    } else {
                        val crop = try {
                            makeSubtitleCrop(image)
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not prepare screenshot frame", e)
                            null
                        } finally {
                            image.close()
                        }
                        if (crop == null) {
                            processingFrame.set(false)
                        } else {
                            runOcr(crop)
                        }
                    }
                }
            }, mainHandler)

            virtualDisplay = projection.createVirtualDisplay(
                "HSVoiceGameSubtitleOCR",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                mainHandler
            )
            if (virtualDisplay == null) {
                stop()
                reportStatus("Erro: não foi possível iniciar a captura da tela")
                return
            }
            reportStatus("Captura da tela ativa — aguardando legenda do jogo")
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start screen subtitle capture", e)
            stop()
            reportStatus("Erro ao capturar legenda da tela: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun makeSubtitleCrop(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val paddedWidth = plane.rowStride / plane.pixelStride
        if (paddedWidth < image.width || image.height < 10) return null

        val paddedBitmap = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        paddedBitmap.copyPixelsFromBuffer(plane.buffer)
        val fullBitmap = Bitmap.createBitmap(paddedBitmap, 0, 0, image.width, image.height)
        paddedBitmap.recycle()

        // Game dialogue subtitles are typically rendered in the lower half. Cropping avoids most HUD
        // and menu text while reducing the amount of pixels OCR must process on each frame.
        val left = (image.width * 0.05f).toInt().coerceIn(0, image.width - 1)
        val right = (image.width * 0.95f).toInt().coerceAtMost(image.width)
        val top = (image.height * 0.52f).toInt().coerceIn(0, image.height - 1)
        val bottom = (image.height * 0.97f).toInt().coerceAtMost(image.height)
        val cropWidth = (right - left).coerceAtLeast(1)
        val cropHeight = (bottom - top).coerceAtLeast(1)
        val crop = Bitmap.createBitmap(fullBitmap, left, top, cropWidth, cropHeight)
        fullBitmap.recycle()
        return crop
    }

    private fun runOcr(bitmap: Bitmap) {
        try {
            val input = InputImage.fromBitmap(bitmap, 0)
            val latinTask = latinRecognizer.process(input)
            val koreanTask = koreanRecognizer.process(input)
            Tasks.whenAllSuccess<Text>(latinTask, koreanTask)
                .addOnSuccessListener(frameExecutor) { results ->
                    if (stopped.get()) return@addOnSuccessListener
                    val latin = results.getOrNull(0)?.text.orEmpty()
                    val korean = results.getOrNull(1)?.text.orEmpty()
                    val selectedText = chooseSubtitleText(
                        results.getOrNull(0),
                        results.getOrNull(1),
                        bitmap.width,
                        bitmap.height
                    )
                    acceptOcrCandidate(selectedText.ifBlank {
                        if (hasHangul(korean)) korean else latin
                    })
                }
                .addOnFailureListener(frameExecutor) { error ->
                    Log.w(TAG, "OCR frame failed", error)
                }
                .addOnCompleteListener {
                    if (!bitmap.isRecycled) bitmap.recycle()
                    processingFrame.set(false)
                }
        } catch (e: Exception) {
            if (!bitmap.isRecycled) bitmap.recycle()
            processingFrame.set(false)
            Log.w(TAG, "Could not run OCR", e)
        }
    }

    private fun chooseSubtitleText(
        latinResult: Text?,
        koreanResult: Text?,
        width: Int,
        height: Int
    ): String {
        val koreanText = koreanResult?.text.orEmpty()
        val latinText = latinResult?.text.orEmpty()
        val selected = if (hasHangul(koreanText)) koreanResult else {
            latinResult?.takeIf { it.text.isNotBlank() } ?: koreanResult
        } ?: return ""

        val lines = selected.textBlocks
            .flatMap { block -> block.lines }
            .mapNotNull { line ->
                val lineText = line.text.trim()
                val box = line.boundingBox
                if (box == null || lineText.length < 2) return@mapNotNull null
                val centerX = box.centerX().toFloat() / width.coerceAtLeast(1)
                val heightRatio = box.height().toFloat() / height.coerceAtLeast(1)
                if (centerX < 0.08f || centerX > 0.92f || heightRatio < 0.012f) null
                else box.bottom to lineText
            }
            .sortedBy { it.first }
            .takeLast(3)
            .map { it.second }
        val candidate = lines.joinToString(" ").replace(Regex("\\s+"), " ").trim()
        return candidate.ifBlank { selected.text.trim() }
    }

    private fun hasHangul(text: String): Boolean = text.any {
        it in '\uAC00'..'\uD7A3' || it in '\u1100'..'\u11FF' || it in '\u3130'..'\u318F'
    }

    private fun normalizeForDedup(text: String): String =
        text.lowercase(Locale.ROOT).replace(Regex("[\\s\\p{P}]+"), "").trim()

    private fun acceptOcrCandidate(rawText: String) {
        if (stopped.get()) return
        val cleaned = rawText.lines()
            .map { it.trim() }
            .filter { it.length >= 2 }
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (cleaned.isBlank()) {
            blankFrameCount++
            if (blankFrameCount >= 3) {
                candidateText = ""
                candidateNormalized = ""
                candidateCount = 0
            }
            return
        }
        blankFrameCount = 0
        val normalized = normalizeForDedup(cleaned)
        if (normalized.length < 2) return

        if (normalized == candidateNormalized) {
            candidateCount++
            candidateText = cleaned
        } else {
            candidateNormalized = normalized
            candidateText = cleaned
            candidateCount = 1
        }
        if (candidateCount < MIN_STABLE_FRAMES || normalized == lastRequestedNormalized) return

        lastRequestedNormalized = normalized
        currentSourceNormalized = normalized
        reportStatus("Legenda encontrada — traduzindo texto do jogo")
        mainHandler.post {
            if (!stopped.get()) onSourceText(candidateText)
        }
        queueTranslation(cleaned, normalized)
    }

    private fun queueTranslation(text: String, normalized: String) {
        synchronized(translationLock) {
            pendingTranslationText = text
            if (translationInProgress) return
            translationInProgress = true
        }
        translationExecutor.execute {
            while (!stopped.get()) {
                val next = synchronized(translationLock) {
                    val pending = pendingTranslationText
                    pendingTranslationText = null
                    if (pending == null) translationInProgress = false
                    pending
                } ?: break

                val normalizedNext = normalizeForDedup(next)
                val translated = try {
                    translateText(next)
                } catch (e: Exception) {
                    Log.w(TAG, "Subtitle translation request failed", e)
                    reportStatus("Erro ao traduzir a legenda: ${e.message ?: "falha de rede"}".take(150))
                    ""
                }
                if (translated.isNotBlank() && !stopped.get() && normalizedNext == currentSourceNormalized) {
                    reportStatus("Legenda do jogo traduzida")
                    mainHandler.post {
                        if (!stopped.get() && normalizedNext == currentSourceNormalized) {
                            onTranslatedText(translated)
                        }
                    }
                }
            }
            synchronized(translationLock) { translationInProgress = false }
            // A caption might have arrived just as the worker was exiting.
            val restart = synchronized(translationLock) {
                if (pendingTranslationText != null && !translationInProgress && !stopped.get()) {
                    translationInProgress = true
                    true
                } else false
            }
            if (restart) translationExecutor.execute { drainTranslations() }
        }
    }

    private fun drainTranslations() {
        while (!stopped.get()) {
            val next = synchronized(translationLock) {
                val pending = pendingTranslationText
                pendingTranslationText = null
                if (pending == null) translationInProgress = false
                pending
            } ?: break
            val normalizedNext = normalizeForDedup(next)
            val translated = try {
                translateText(next)
            } catch (e: Exception) {
                Log.w(TAG, "Subtitle translation request failed", e)
                reportStatus("Erro ao traduzir a legenda".take(150))
                ""
            }
            if (translated.isNotBlank() && !stopped.get() && normalizedNext == currentSourceNormalized) {
                reportStatus("Legenda do jogo traduzida")
                mainHandler.post {
                    if (!stopped.get() && normalizedNext == currentSourceNormalized) onTranslatedText(translated)
                }
            }
        }
        synchronized(translationLock) { translationInProgress = false }
    }

    private fun translateText(sourceText: String): String {
        val destination = targetLanguageDescription()
        val prompt = "Traduza somente o texto da legenda de um jogo para $destination. " +
            "Identifique o idioma de origem pelo próprio texto. Preserve o sentido, nomes de personagens, " +
            "nomes de lugares, termos de habilidades e o tom emocional. Não resuma, não invente falas, " +
            "não explique e não acrescente contexto. Se o trecho estiver incompleto, traduza apenas o que " +
            "está visível. Retorne exclusivamente a tradução, sem aspas.\n\nTexto reconhecido na legenda:\n$sourceText"

        val requestBody = JSONObject().apply {
            put("contents", JSONArray().put(
                JSONObject().put("role", "user").put(
                    "parts", JSONArray().put(JSONObject().put("text", prompt))
                )
            ))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.1)
                put("maxOutputTokens", 160)
                put("candidateCount", 1)
            })
        }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        val url = HttpUrl.Builder()
            .scheme("https")
            .host("generativelanguage.googleapis.com")
            .addPathSegment("v1beta")
            .addPathSegment("models")
            .addPathSegment("gemini-3.8-flash:generateContent")
            .addQueryParameter("key", apiKey)
            .build()

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val reason = try {
                    JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
                } catch (_: Exception) { "" }
                throw IOException("HTTP ${response.code}${if (reason.isBlank()) "" else ": $reason"}")
            }
            val root = JSONObject(body)
            val candidates = root.optJSONArray("candidates") ?: return ""
            val first = candidates.optJSONObject(0) ?: return ""
            val parts = first.optJSONObject("content")?.optJSONArray("parts") ?: return ""
            val result = (0 until parts.length()).mapNotNull { index ->
                parts.optJSONObject(index)?.optString("text")?.takeIf { it.isNotBlank() }
            }.joinToString(" ").trim()
            return result.trim().trim('"', '“', '”', '‘', '’')
        }
    }

    private fun targetLanguageDescription(): String {
        return when (targetLanguage.substringBefore('-').lowercase(Locale.ROOT)) {
            "pt" -> if (targetDialect.equals("pt-PT", true)) "português europeu" else "português brasileiro"
            "en" -> "inglês natural"
            "es" -> "espanhol natural"
            "ko" -> "coreano natural"
            "ja" -> "japonês natural"
            "fr" -> "francês natural"
            "de" -> "alemão natural"
            "it" -> "italiano natural"
            "zh" -> "chinês simplificado"
            else -> Locale.forLanguageTag(targetLanguage).getDisplayLanguage(Locale("pt", "BR"))
                .ifBlank { targetLanguage }
        }
    }

    private fun reportStatus(status: String) {
        mainHandler.post { if (!stopped.get()) onStatus(status) }
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        try {
            imageReader?.setOnImageAvailableListener(null, null)
            virtualDisplay?.release()
            imageReader?.close()
            latinRecognizer.close()
            koreanRecognizer.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping OCR capture", e)
        } finally {
            virtualDisplay = null
            imageReader = null
            frameExecutor.shutdownNow()
            translationExecutor.shutdownNow()
        }
    }
}
