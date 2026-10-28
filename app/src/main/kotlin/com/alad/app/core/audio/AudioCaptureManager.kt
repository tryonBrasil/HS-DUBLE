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

    companion object {
        private const val TAG = "AudioCaptureManager"
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        val MIN_BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        const val CHUNK_SIZE = 3200 // 100ms of 16kHz 16-bit mono PCM, as recommended by Gemini Live Translate
    }

    @SuppressLint("MissingPermission")
    fun startCapture(
        mediaProjection: MediaProjection,
        appUid: Int,
        onAudioData: (ByteArray) -> Unit,
        onCaptureStatus: ((String) -> Unit)? = null
    ) {
        if (isRecording) return
        audioSignalDetected = false
        onCaptureStatus?.invoke("Iniciando captura de áudio interno")

        try {
            val configBuilder = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                
            if (appUid > 0) {
                configBuilder.excludeUid(appUid)
            }
                
            val config = configBuilder.build()

            val format = AudioFormat.Builder()
                .setEncoding(AUDIO_FORMAT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_CONFIG)
                .build()

            val bufferSize = maxOf(MIN_BUFFER_SIZE, CHUNK_SIZE * 4)

            audioRecord = AudioRecord.Builder()
                .setAudioFormat(format)
                .setAudioPlaybackCaptureConfig(config)
                .setBufferSizeInBytes(bufferSize)
                .build()

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                val message = "Falha: AudioRecord não inicializou (estado ${audioRecord?.state})"
                Log.e(TAG, message)
                onCaptureStatus?.invoke(message)
                return
            }

            audioRecord?.startRecording()
            isRecording = true
            Log.d(TAG, "AudioPlaybackCapture started successfully. Recording state: ${audioRecord?.recordingState}")
            onCaptureStatus?.invoke("Captura ativa; aguardando áudio do jogo")
            
            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                val buffer = ByteArray(CHUNK_SIZE)
                var chunksRead = 0
                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        chunksRead++
                        if (chunksRead % 100 == 0) {
                            Log.d(TAG, "Captured 100 chunks of size $read ($CHUNK_SIZE expected)")
                        }
                        val chunk = buffer.copyOf(read)
                        if (!audioSignalDetected && AudioUtils.calculateRms(chunk) >= 250f) {
                            audioSignalDetected = true
                            Log.i(TAG, "Non-silent game audio detected")
                            onCaptureStatus?.invoke("Áudio do jogo detectado")
                        }
                        onAudioData(chunk)
                    } else if (read < 0) {
                        val message = "Falha na leitura do áudio interno (código $read)"
                        Log.e(TAG, message)
                        onCaptureStatus?.invoke(message)
                        break
                    }
                }
            }, "AudioCaptureThread").start()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting capture", e)
            onCaptureStatus?.invoke("Erro na captura de áudio: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun stopCapture() {
        isRecording = false
        audioSignalDetected = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping audioRecord", e)
        }
        audioRecord = null
    }
}
