package com.alad.app.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.speech.tts.TextToSpeech
import android.os.SystemClock
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.alad.app.R
import com.alad.app.core.audio.AudioCaptureManager
import com.alad.app.core.audio.AudioPlayerManager
import com.alad.app.core.audio.AudioUtils
import com.alad.app.core.network.ALADWebSocketManager
import com.alad.app.data.repository.DubbingSessionRepository
import com.alad.app.data.repository.UserPreferencesRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.util.Locale

class AudioDubbingForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "alad_dubbing_channel"
        private const val NOTIFICATION_ID = 101

        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        private const val TAG = "DubbingService"

        // Delegated to DubbingSessionRepository for backward compatibility
        val isRunning: StateFlow<Boolean> get() = DubbingSessionRepository.isDubbingRunning
        val audioAmplitude: StateFlow<Float> get() = DubbingSessionRepository.audioAmplitude
    }

    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Unhandled exception in serviceScope", throwable)
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + coroutineExceptionHandler)

    private var mediaProjection: MediaProjection? = null
    private var audioCaptureManager: AudioCaptureManager? = null
    private var audioPlayerManager: AudioPlayerManager? = null
    private var webSocketManager: ALADWebSocketManager? = null
    private var settingsObservationJob: Job? = null
    @Volatile private var startupJob: Job? = null
    @Volatile private var isStopping = false
    private var screenSubtitleCaptureManager: ScreenSubtitleCaptureManager? = null
    private var screenSubtitleTts: TextToSpeech? = null
    @Volatile private var screenSubtitleTtsReady = false
    @Volatile private var pendingScreenSpeech: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        when (action) {
            ACTION_START -> {
                val notification = createNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    } else {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    }
                    startForeground(NOTIFICATION_ID, notification, serviceType)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

                if (resultCode != 0 && data != null) {
                    startDubbing(resultCode, data)
                } else {
                    Log.e(TAG, "ACTION_START received without valid MediaProjection permission data")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
            ACTION_STOP -> {
                stopDubbing()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    @Synchronized
    private fun startDubbing(resultCode: Int, data: Intent) {
        if (DubbingSessionRepository.isDubbingRunning.value || startupJob?.isActive == true) {
            Log.w(TAG, "Ignoring duplicate dubbing start request")
            return
        }
        isStopping = false
        DubbingSessionRepository.setDubbingRunning(true)
        DubbingSessionRepository.setConnectionStatus(getString(R.string.status_initializing))
        DubbingSessionRepository.clearSubtitle()

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, data)
        if (projection == null) {
            Log.e(TAG, "Failed to get MediaProjection! ResultCode: $resultCode")
            DubbingSessionRepository.setConnectionStatus(getString(R.string.status_capture_denied))
            DubbingSessionRepository.setDubbingRunning(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        // CRITICAL FOR ANDROID 14 (API 34): Register MediaProjection.Callback before starting capture
        val projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection stopped by system")
                if (isStopping) return
                serviceScope.launch(Dispatchers.Main) {
                    if (isStopping) return@launch
                    try {
                        stopDubbing()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error stopping dubbing from projection callback", e)
                    }
                }
            }
        }
        projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
        mediaProjection = projection

        startupJob = serviceScope.launch {
            try {
                val repository = UserPreferencesRepository(applicationContext)

                val apiKey = repository.apiKeyFlow.first()
                val sourceLang = repository.sourceLangFlow.first()
                val targetLang = repository.targetLangFlow.first()
                val targetDialect = repository.targetDialectFlow.first()
                val volumeRatio = repository.volumeRatioFlow.first()
                val isDubbingMuted = repository.isDubbingMutedFlow.first()
                val originalVolume = repository.originalVolumeFlow.first()
                val isOriginalMuted = repository.isOriginalMutedFlow.first()
                val voicePersona = repository.voicePersonaFlow.first()
                val dubbingMode = repository.dubbingModeFlow.first()
                val showDualSubtitles = repository.showDualSubtitlesFlow.first()
                // OCR is intentionally paused for this release, including for older saved preferences.
                repository.updateScreenSubtitleMode(false)
                val screenSubtitleMode = false

                // Sync repository values into the active session
                DubbingSessionRepository.setDubbingVolume(volumeRatio)
                DubbingSessionRepository.setDubbingMuted(isDubbingMuted)
                DubbingSessionRepository.setOriginalVolumeRatio(originalVolume)
                DubbingSessionRepository.setOriginalMuted(isOriginalMuted)
                DubbingSessionRepository.setShowDualSubtitles(showDualSubtitles)

                if (screenSubtitleMode) {
                    DubbingSessionRepository.setDetectedSourceLanguage("")
                    // OCR mode translates visible game dialogue into Portuguese captions and speech.
                    // The game audio itself may need to be muted in the game's own settings.
                    initializeScreenSubtitleTts("pt-BR", shouldSpeak = true)
                    screenSubtitleCaptureManager = ScreenSubtitleCaptureManager(
                        context = applicationContext,
                        projection = projection,
                        apiKey = apiKey,
                        targetLanguage = "pt-BR",
                        targetDialect = "pt-BR",
                        onSourceText = { _ ->
                            // The requested overlay contains Portuguese text only.
                        },
                        onTranslatedText = { text ->
                            DubbingSessionRepository.setScreenTranslatedText(text)
                            speakScreenSubtitle(text)
                        },
                        onStatus = { status ->
                            DubbingSessionRepository.setConnectionStatus(status)
                            DubbingSessionRepository.setAudioCaptureStatus(status)
                        }
                    ).also { it.start() }
                } else {
                val okHttpClient = OkHttpClient.Builder()
                    .pingInterval(15, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true)
                    .build()

                webSocketManager = ALADWebSocketManager(okHttpClient)

                // Keep the player ready in every mode so switching from subtitles-only to
                // voice dubbing during an active session works without restarting capture.
                audioPlayerManager = AudioPlayerManager(applicationContext)
                audioPlayerManager?.start()
                audioPlayerManager?.setDubbingAudio(volumeRatio, isDubbingMuted)
                if (dubbingMode != UserPreferencesRepository.MODE_SUBTITLE_ONLY) {
                    audioPlayerManager?.setOriginalMediaAudio(originalVolume, isOriginalMuted)
                }

                webSocketManager?.onStatusChanged = { status ->
                    DubbingSessionRepository.setConnectionStatus(status)
                }

                webSocketManager?.onTextMessageReceived = { textDelta ->
                    DubbingSessionRepository.appendSubtitleText(textDelta)
                }

                webSocketManager?.onSourceTextReceived = { sourceChunk ->
                    DubbingSessionRepository.appendSourceSubtitleText(sourceChunk)
                }

                webSocketManager?.onSourceLanguageDetected = { languageCode ->
                    DubbingSessionRepository.setDetectedSourceLanguage(languageCode)
                }

                webSocketManager?.onTurnComplete = {
                    DubbingSessionRepository.onTurnComplete()
                }

                // If Live interrupts a response, flush both audio and its stale caption so they
                // cannot continue out of sync while the next translated turn starts.
                webSocketManager?.onInterrupted = {
                    audioPlayerManager?.flushPlayback()
                    DubbingSessionRepository.clearSubtitle()
                }

                var activeDubbingMode = dubbingMode

                webSocketManager?.onBinaryMessageReceived = { audioChunk ->
                    if (activeDubbingMode != UserPreferencesRepository.MODE_SUBTITLE_ONLY) {
                        audioPlayerManager?.playAudioData(audioChunk)
                    }
                }

                webSocketManager?.connect(
                    apiKey = apiKey,
                    sourceLang = sourceLang,
                    targetLang = targetLang,
                    voicePersona = voicePersona,
                    dubbingMode = dubbingMode,
                    targetDialect = targetDialect
                )

                // Dynamic observation for audio controls and settings
                settingsObservationJob?.cancel()
                settingsObservationJob = serviceScope.launch {
                    // 1. Reactive dubbing volume updates
                    launch {
                        kotlinx.coroutines.flow.combine(
                            DubbingSessionRepository.dubbingVolume,
                            DubbingSessionRepository.isDubbingMuted
                        ) { vol, muted -> Pair(vol, muted) }.collect { (vol, muted) ->
                            audioPlayerManager?.setDubbingAudio(vol, muted)
                        }
                    }

                    // 2. Reactive original media volume & ducking updates
                    launch {
                        kotlinx.coroutines.flow.combine(
                            DubbingSessionRepository.originalVolumeRatio,
                            DubbingSessionRepository.isOriginalMuted
                        ) { ratio, muted -> Pair(ratio, muted) }.collect { (ratio, muted) ->
                            if (activeDubbingMode != UserPreferencesRepository.MODE_SUBTITLE_ONLY) {
                                audioPlayerManager?.setOriginalMediaAudio(ratio, muted)
                            }
                        }
                    }

                    // 3. Real-time dubbing mode switching without dropping WebSocket.
                    // Restore game volume in subtitles-only mode and reapply the mix when
                    // translated voice playback is enabled again.
                    launch {
                        repository.dubbingModeFlow.collect { newMode ->
                            if (newMode != activeDubbingMode) {
                                activeDubbingMode = newMode
                                if (newMode == UserPreferencesRepository.MODE_SUBTITLE_ONLY) {
                                    audioPlayerManager?.flushPlayback()
                                    audioPlayerManager?.restoreOriginalMediaAudio()
                                } else {
                                    audioPlayerManager?.setDubbingAudio(
                                        DubbingSessionRepository.dubbingVolume.value,
                                        DubbingSessionRepository.isDubbingMuted.value
                                    )
                                    audioPlayerManager?.setOriginalMediaAudio(
                                        DubbingSessionRepository.originalVolumeRatio.value,
                                        DubbingSessionRepository.isOriginalMuted.value
                                    )
                                }
                            }
                        }
                    }

                    // 5. Reconnect when the source language, target language, or dialect changes.
                    launch {
                        var activeSourceLang = sourceLang
                        var activeLang = targetLang
                        var activeDialect = targetDialect
                        kotlinx.coroutines.flow.combine(
                            repository.sourceLangFlow,
                            repository.targetLangFlow,
                            repository.targetDialectFlow
                        ) { source, lang, dialect -> Triple(source, lang, dialect) }.collect { (newSourceLang, newLang, newDialect) ->
                            if (newSourceLang != activeSourceLang || newLang != activeLang || newDialect != activeDialect) {
                                activeSourceLang = newSourceLang
                                activeLang = newLang
                                activeDialect = newDialect
                                val currentKey = repository.apiKeyFlow.first()
                                val currentVoice = repository.voicePersonaFlow.first()
                                val currentMode = repository.dubbingModeFlow.first()
                                webSocketManager?.disconnect()
                                webSocketManager?.connect(
                                    apiKey = currentKey,
                                    sourceLang = newSourceLang,
                                    targetLang = newLang,
                                    voicePersona = currentVoice,
                                    dubbingMode = currentMode,
                                    targetDialect = newDialect
                                )
                            }
                        }
                    }
                }

                audioCaptureManager = AudioCaptureManager()
                val appUid = applicationInfo.uid

                mediaProjection?.let { projection ->
                    audioCaptureManager?.startCapture(
                        mediaProjection = projection,
                        appUid = appUid,
                        onAudioData = { pcmData ->
                            webSocketManager?.sendAudioData(pcmData)
                            val currentAmp = DubbingSessionRepository.audioAmplitude.value
                            val newAmp = AudioUtils.processPcmAmplitude(pcmData, currentAmp)
                            DubbingSessionRepository.setAudioAmplitude(newAmp)
                        },
                        onCaptureStatus = { status ->
                            DubbingSessionRepository.setAudioCaptureStatus(status)
                        }
                    )
                }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.i(TAG, "Dubbing startup cancelled")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error during dubbing session startup", e)
                DubbingSessionRepository.setConnectionStatus("Erro ao iniciar: ${e.message ?: e.javaClass.simpleName}")
                stopDubbing()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } finally {
                startupJob = null
            }
        }
    }

    private fun initializeScreenSubtitleTts(targetLang: String, shouldSpeak: Boolean) {
        screenSubtitleTts?.stop()
        screenSubtitleTts?.shutdown()
        screenSubtitleTts = null
        screenSubtitleTtsReady = false
        pendingScreenSpeech = null
        if (!shouldSpeak) return

        screenSubtitleTts = TextToSpeech(applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val locale = if (targetLang.substringBefore('-').equals("pt", ignoreCase = true)) {
                    Locale("pt", "BR")
                } else {
                    Locale.forLanguageTag(targetLang)
                }
                val languageResult = screenSubtitleTts?.setLanguage(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED
                screenSubtitleTtsReady = languageResult != TextToSpeech.LANG_NOT_SUPPORTED &&
                    languageResult != TextToSpeech.LANG_MISSING_DATA
                screenSubtitleTts?.setSpeechRate(1.0f)
                val pending = pendingScreenSpeech
                if (screenSubtitleTtsReady && !pending.isNullOrBlank()) {
                    pendingScreenSpeech = null
                    speakScreenSubtitle(pending)
                }
            } else {
                Log.w(TAG, "Android text-to-speech initialization failed: $status")
            }
        }
    }

    private fun speakScreenSubtitle(text: String) {
        Handler(Looper.getMainLooper()).post {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return@post
            if (!screenSubtitleTtsReady) {
                pendingScreenSpeech = trimmed
                return@post
            }
            screenSubtitleTts?.speak(
                trimmed,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "hs-screen-subtitle-${SystemClock.elapsedRealtime()}"
            )
        }
    }

    @Synchronized
    private fun stopDubbing() {
        isStopping = true
        startupJob?.cancel()
        startupJob = null
        DubbingSessionRepository.resetSession()

        screenSubtitleCaptureManager?.stop()
        screenSubtitleCaptureManager = null

        screenSubtitleTtsReady = false
        pendingScreenSpeech = null
        screenSubtitleTts?.stop()
        screenSubtitleTts?.shutdown()
        screenSubtitleTts = null

        audioCaptureManager?.stopCapture()
        audioCaptureManager = null

        audioPlayerManager?.stop()
        audioPlayerManager = null

        settingsObservationJob?.cancel()
        settingsObservationJob = null

        webSocketManager?.shutdown()
        webSocketManager = null

        val projectionToStop = mediaProjection
        mediaProjection = null
        runCatching { projectionToStop?.stop() }
            .onFailure { Log.w(TAG, "Error releasing MediaProjection", it) }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        stopDubbing()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.notification_channel_name)
            val descriptionText = getString(R.string.notification_channel_description)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, com.alad.app.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, AudioDubbingForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = android.app.PendingIntent.getService(
            this, 1, stopIntent, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_connected))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notification_disconnect), stopPendingIntent)
            .setOngoing(true)
            .build()
        }
}
