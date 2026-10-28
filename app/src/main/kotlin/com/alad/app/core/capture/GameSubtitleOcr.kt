package com.alad.app.core.capture

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
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean

/** Optional OCR for game subtitles. OCR errors never stop the existing audio capture. */
class GameSubtitleOcr(
    private val projection: MediaProjection,
    private val onSubtitle: (String) -> Unit
) {
    companion object {
        private const val TAG = "GameSubtitleOcr"
        private const val FRAME_INTERVAL_MS = 900L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val processing = AtomicBoolean(false)
    private var reader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var lastText = ""
    private var lastFrameAt = 0L
    @Volatile private var stopped = false

    fun start() {
        try {
            val metrics = android.content.res.Resources.getSystem().displayMetrics
            val width = metrics.widthPixels.coerceAtLeast(1)
            val height = metrics.heightPixels.coerceAtLeast(1)
            val density = metrics.densityDpi
            val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            reader = imageReader
            imageReader.setOnImageAvailableListener({ source ->
                val now = SystemClock.elapsedRealtime()
                if (stopped || now - lastFrameAt < FRAME_INTERVAL_MS ||
                    !processing.compareAndSet(false, true)) {
                    source.acquireLatestImage()?.close()
                    return@setOnImageAvailableListener
                }
                val image = source.acquireLatestImage()
                if (image == null) {
                    processing.set(false)
                    return@setOnImageAvailableListener
                }
                lastFrameAt = now
                val bitmap = try { imageToBitmap(image) } catch (e: Exception) {
                    Log.w(TAG, "Frame conversion failed", e)
                    null
                } finally {
                    image.close()
                }
                if (bitmap == null) {
                    processing.set(false)
                    return@setOnImageAvailableListener
                }

                // Subtitles are commonly in the lower half; ignore most HUD text above.
                val top = (bitmap.height * 0.52f).toInt().coerceIn(0, bitmap.height - 1)
                val crop = Bitmap.createBitmap(bitmap, 0, top, bitmap.width, bitmap.height - top)
                if (crop !== bitmap) bitmap.recycle()
                recognizer.process(InputImage.fromBitmap(crop, 0))
                    .addOnSuccessListener { result ->
                        val text = result.text.replace(Regex("\\s+"), " ").trim()
                        if (text.length >= 3 && text != lastText) {
                            lastText = text
                            onSubtitle(text)
                        }
                    }
                    .addOnFailureListener { Log.d(TAG, "OCR skipped: ${it.message}") }
                    .addOnCompleteListener {
                        crop.recycle()
                        processing.set(false)
                    }
            }, handler)

            virtualDisplay = projection.createVirtualDisplay(
                "HSVoiceSubtitleOCR", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface, null, handler
            )
            Log.i(TAG, "Subtitle OCR started")
        } catch (e: Exception) {
            Log.e(TAG, "Subtitle OCR unavailable; continuing with audio only", e)
            stop()
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        if (plane.pixelStride < 4) return null
        val paddedWidth = plane.rowStride / plane.pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        val buffer = plane.buffer
        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)
        val bitmap = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        if (bitmap !== padded) padded.recycle()
        return bitmap
    }

    fun stop() {
        stopped = true
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { reader?.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        try { recognizer.close() } catch (_: Exception) {}
    }
}
