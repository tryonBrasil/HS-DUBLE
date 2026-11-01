package com.alad.app.core.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.util.Log

class AudioCaptureManager {
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var isRecording = false
    @Volatile private var audioSignalDetected = false
    @Volatile private var captureThread: Thread? = null
    private val lifecycleLock = Any()

    companion object {
        private const val TAG = "AudioCaptureManager"
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        val MIN_BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        const val CHUNK_SIZE = 1600 // 50 ms, 16 kHz, mono, PCM 16-bit; menor espera antes de enviar áudio ao Gemini.
    }

    @SuppressLint("MissingPermission")
    fun startCapture(
        mediaProjection: MediaProjection,
        appUid: Int,
        onAudioData: (ByteArray) -> Unit,
        onCaptureStatus: ((String) -> Unit)? = null
    ) {
        synchronized(lifecycleLock) {
            if (isRecording || captureThread?.isAlive == true) return
            audioSignalDetected = false
            onCaptureStatus?.invoke("Iniciando captura de áudio interno")

            var recorder: AudioRecord? = null
            try {
                val configBuilder = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                if (appUid > 0) configBuilder.excludeUid(appUid)

                val format = AudioFormat.Builder()
                    .setEncoding(AUDIO_FORMAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_CONFIG)
                    .build()

                val bufferSize = maxOf(MIN_BUFFER_SIZE, CHUNK_SIZE * 4)
                val activeRecorder = AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setAudioPlaybackCaptureConfig(configBuilder.build())
                    .setBufferSizeInBytes(bufferSize)
                    .build()
                recorder = activeRecorder

                if (activeRecorder.state != AudioRecord.STATE_INITIALIZED) {
                    val message = "Falha: AudioRecord não inicializou (estado ${activeRecorder.state})"
                    Log.e(TAG, message)
                    onCaptureStatus?.invoke(message)
                    activeRecorder.release()
                    recorder = null
                    return
                }

                activeRecorder.startRecording()
                if (activeRecorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    throw IllegalStateException("AudioRecord não entrou em estado de gravação")
                }

                audioRecord = activeRecorder
                isRecording = true
                onCaptureStatus?.invoke("Captura ativa; aguardando áudio do jogo")

                val captureWorker = Thread({
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                    val buffer = ByteArray(CHUNK_SIZE)
                    val startedAt = android.os.SystemClock.elapsedRealtime()
                    var warnedNoAudio = false
                    var chunksRead = 0
                    try {
                        while (isRecording && !Thread.currentThread().isInterrupted) {
                            val read = activeRecorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                            if (read > 0) {
                                chunksRead++
                                if (chunksRead % 200 == 0) {
                                    Log.d(TAG, "Captured 100 audio chunks; latest size=$read")
                                }
                                val chunk = buffer.copyOf(read)
                                val rms = AudioUtils.calculateRms(chunk)
                                if (!audioSignalDetected && rms >= 250f) {
                                    audioSignalDetected = true
                                    Log.i(TAG, "Non-silent game audio detected (RMS=$rms)")
                                    onCaptureStatus?.invoke("Áudio do jogo detectado (sinal RMS ${rms.toInt()})")
                                } else if (!audioSignalDetected && !warnedNoAudio &&
                                    android.os.SystemClock.elapsedRealtime() - startedAt >= 10_000L
                                ) {
                                    warnedNoAudio = true
                                    val warning = "Nenhum áudio detectado após 10 s. Confirme o volume do jogo e se ele permite captura interna."
                                    Log.w(TAG, warning)
                                    onCaptureStatus?.invoke(warning)
                                }
                                onAudioData(chunk)
                            } else if (read < 0) {
                                val message = "Falha na leitura do áudio interno (código $read)"
                                Log.e(TAG, message)
                                onCaptureStatus?.invoke(message)
                                break
                            }
                        }
                    } catch (e: Exception) {
                        if (isRecording) {
                            Log.e(TAG, "Audio capture thread failed", e)
                            onCaptureStatus?.invoke("Erro na captura: ${e.message ?: e.javaClass.simpleName}")
                        }
                    } finally {
                        isRecording = false
                    }
                }, "AudioCaptureThread").apply { isDaemon = true }
                captureThread = captureWorker
                captureWorker.start()
            } catch (e: Exception) {
                Log.e(TAG, "Error starting capture", e)
                isRecording = false
                audioRecord = null
                runCatching { recorder?.stop() }
                runCatching { recorder?.release() }
                onCaptureStatus?.invoke("Erro na captura de áudio: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun stopCapture() {
        val recorder: AudioRecord?
        val thread: Thread?
        synchronized(lifecycleLock) {
            isRecording = false
            audioSignalDetected = false
            recorder = audioRecord
            thread = captureThread
            audioRecord = null
            captureThread = null
        }

        try {
            if (recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord", e)
        }
        if (thread != null && thread !== Thread.currentThread()) {
            try {
                thread.join(500)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        try {
            recorder?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioRecord", e)
        }
    }
}
