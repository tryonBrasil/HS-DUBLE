package com.alad.app.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

class AudioPlayerManager(private val context: Context) {
    private data class QueuedAudio(val data: ByteArray, val generation: Long)

    private val playbackLock = Any()
    @Volatile private var playbackGeneration = 0L

    private var audioTrack: AudioTrack? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    private val playerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // Preserve all speech chunks; dropping old chunks cuts syllables while subtitles keep advancing.
    private val audioChannel = Channel<QueuedAudio>(20)
    private var playbackJob: Job? = null

    private var initialMusicVolume: Int = -1
    private var baseMusicVolume: Int = 10
    private var maxMusicVolume: Int = 15

    companion object {
        private const val TAG = "AudioPlayerManager"
        private const val SAMPLE_RATE = 24000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    @Volatile private var currentVolume: Float = 1.0f
    @Volatile private var isDubbingMuted: Boolean = false
    @Volatile private var currentVoicePersona: String = "Puck"

    fun setVoicePersona(persona: String) {
        currentVoicePersona = persona
        Log.d(TAG, "Active voice persona set to: $persona")
    }

    fun start() {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        
        try {
            initialMusicVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1
            maxMusicVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
            baseMusicVolume = if (initialMusicVolume > 0) {
                initialMusicVolume
            } else {
                (maxMusicVolume * 0.7f).toInt().coerceAtLeast(1)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not query STREAM_MUSIC volume", e)
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
            
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener { }
                .build()
                
            audioManager?.requestAudioFocus(focusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(
                { },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }

        val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = maxOf(minBufferSize, 4096 * 2)

        val audioFormat = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(CHANNEL_CONFIG)
            .setEncoding(AUDIO_FORMAT)
            .build()

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        audioTrack?.play()

        playbackJob = playerScope.launch {
            for (queuedAudio in audioChannel) {
                if (queuedAudio.generation != playbackGeneration) continue
                val chunk = queuedAudio.data
                if (isDubbingMuted || currentVolume <= 0.01f) {
                    continue
                }

                try {
                    // Apply the user's volume once in software; AudioTrack remains at unity gain.
                    val vol = currentVolume.coerceIn(0f, 1f)
                    val gain = vol

                    val processedChunk = ByteArray(chunk.size)
                    val numSamples = chunk.size / 2

                    for (i in 0 until numSamples) {
                        val byteIndex = i * 2
                        val low = chunk[byteIndex].toInt() and 0xFF
                        val high = chunk[byteIndex + 1].toInt()
                        var sample = ((high shl 8) or low).toShort().toFloat()

                        // Preserve the voice generated by Gemini. Do not apply persona DSP filters.
                        // Apply the user's volume once and clamp PCM to avoid integer overflow.
                        val scaled = (sample * gain).coerceIn(-32767f, 32767f).toInt()

                        processedChunk[byteIndex] = (scaled and 0xFF).toByte()
                        processedChunk[byteIndex + 1] = ((scaled shr 8) and 0xFF).toByte()
                    }

                    synchronized(playbackLock) {
                        // An interruption may happen while this chunk is being processed.
                        // Re-check under the same lock used by flushPlayback so stale speech
                        // cannot be written back after the AudioTrack buffer has been cleared.
                        if (queuedAudio.generation == playbackGeneration) {
                            audioTrack?.let { track ->
                                val written = track.write(
                                    processedChunk, 0, processedChunk.size, AudioTrack.WRITE_BLOCKING
                                )
                                if (written < 0) {
                                    Log.w(TAG, "AudioTrack write failed: $written")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error writing audio chunk", e)
                }
            }
        }
    }

    fun playAudioData(data: ByteArray) {
        val queued = QueuedAudio(data.copyOf(), playbackGeneration)
        // Keep latency bounded: if playback falls behind, discard the oldest queued chunk.
        if (audioChannel.trySend(queued).isFailure) {
            audioChannel.tryReceive()
            audioChannel.trySend(queued)
        }
    }

    /**
     * Flush audio from an interrupted model turn. The generation check prevents a chunk
     * already taken by the playback worker from being written after this flush.
     */
    fun flushPlayback() {
        synchronized(playbackLock) {
            playbackGeneration += 1L
            while (audioChannel.tryReceive().isSuccess) {
                // Discard every queued chunk from the interrupted turn.
            }
            try {
                audioTrack?.let { track ->
                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        track.pause()
                    }
                    track.flush()
                    if (track.state == AudioTrack.STATE_INITIALIZED) {
                        track.play()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not flush interrupted audio", e)
            }
        }
    }

    fun setDubbingAudio(volume: Float, isMuted: Boolean) {
        currentVolume = volume.coerceIn(0f, 1f)
        isDubbingMuted = isMuted
        val targetHardwareVolume = if (isMuted) 0f else 1f
        try {
            audioTrack?.setVolume(targetHardwareVolume)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting dubbing volume", e)
        }
    }

    fun setOriginalMediaAudio(volumeRatio: Float, isMuted: Boolean) {
        try {
            val baseVol = baseMusicVolume
            val targetVolume = if (isMuted) {
                0
            } else {
                (baseVol * volumeRatio.coerceIn(0f, 1f)).toInt().coerceIn(0, maxMusicVolume)
            }
            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
        } catch (e: Exception) {
            Log.w(TAG, "Error adjusting STREAM_MUSIC volume", e)
        }
    }

    fun stop() {
        // Restore original system music volume
        if (initialMusicVolume >= 0) {
            try {
                audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, initialMusicVolume, 0)
            } catch (e: Exception) {
                Log.w(TAG, "Error restoring STREAM_MUSIC volume", e)
            }
        }

        playbackJob?.cancel()
        playbackJob = null
        playerScope.cancel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus { }
        }

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping audioTrack", e)
        }
        audioTrack = null
    }
}
