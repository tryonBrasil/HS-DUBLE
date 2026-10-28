package com.alad.app.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for the real-time dubbing session state.
 * Decouples the Android Service lifecycle from UI components (MainScreen, OverlayWidget).
 */
object DubbingSessionRepository {

    private val repositoryScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var silenceJob: Job? = null
    private var isNewTurn = true
    private var isNewSourceTurn = true

    private val _isDubbingRunning = MutableStateFlow(false)
    val isDubbingRunning: StateFlow<Boolean> = _isDubbingRunning.asStateFlow()

    private val _audioAmplitude = MutableStateFlow(0f)
    val audioAmplitude: StateFlow<Float> = _audioAmplitude.asStateFlow()

    private val _audioCaptureStatus = MutableStateFlow("Não iniciada")
    val audioCaptureStatus: StateFlow<String> = _audioCaptureStatus.asStateFlow()

    private val _connectionStatus = MutableStateFlow("Disconnected")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

    // Live translated subtitle text
    private val _liveSubtitleText = MutableStateFlow("")
    val liveSubtitleText: StateFlow<String> = _liveSubtitleText.asStateFlow()

    // Live source audio transcript text (for dual subtitle display)
    private val _liveSourceSubtitleText = MutableStateFlow("")
    val liveSourceSubtitleText: StateFlow<String> = _liveSourceSubtitleText.asStateFlow()

    // Source language reported by Gemini Live Translate (for example, "ja" or "ja-JP").
    private val _detectedSourceLanguage = MutableStateFlow("")
    val detectedSourceLanguage: StateFlow<String> = _detectedSourceLanguage.asStateFlow()

    private val _isSubtitleOverlayVisible = MutableStateFlow(true)
    val isSubtitleOverlayVisible: StateFlow<Boolean> = _isSubtitleOverlayVisible.asStateFlow()

    // Real-time audio mixer session controls
    private val _dubbingVolume = MutableStateFlow(1.0f)
    val dubbingVolume: StateFlow<Float> = _dubbingVolume.asStateFlow()

    private val _isDubbingMuted = MutableStateFlow(false)
    val isDubbingMuted: StateFlow<Boolean> = _isDubbingMuted.asStateFlow()

    private val _originalVolumeRatio = MutableStateFlow(0.30f)
    val originalVolumeRatio: StateFlow<Float> = _originalVolumeRatio.asStateFlow()

    private val _isOriginalMuted = MutableStateFlow(false)
    val isOriginalMuted: StateFlow<Boolean> = _isOriginalMuted.asStateFlow()

    private val _showDualSubtitles = MutableStateFlow(true)
    val showDualSubtitles: StateFlow<Boolean> = _showDualSubtitles.asStateFlow()

    fun setDubbingRunning(running: Boolean) {
        _isDubbingRunning.value = running
        if (!running) {
            _audioAmplitude.value = 0f
            _audioCaptureStatus.value = "Não iniciada"
            _connectionStatus.value = "Disconnected"
            _detectedSourceLanguage.value = ""
            clearSubtitle()
        }
    }

    fun setAudioAmplitude(amplitude: Float) {
        _audioAmplitude.value = amplitude
    }

    fun setAudioCaptureStatus(status: String) {
        _audioCaptureStatus.value = status.trim().ifBlank { "Status desconhecido" }
    }

    fun setConnectionStatus(status: String) {
        _connectionStatus.value = status
    }

    fun setDetectedSourceLanguage(languageCode: String) {
        _detectedSourceLanguage.value = languageCode.trim()
    }

    fun appendSubtitleText(textChunk: String) {
        val trimmed = textChunk.trim()
        if (trimmed.isEmpty()) return

        silenceJob?.cancel()

        if (isNewTurn) {
            isNewTurn = false
            _liveSubtitleText.value = trimmed
            return
        }

        _liveSubtitleText.value = limitSubtitleText(
            mergeStreamingText(_liveSubtitleText.value, trimmed)
        )
    }

    fun appendSourceSubtitleText(textChunk: String) {
        val trimmed = textChunk.trim()
        if (trimmed.isEmpty()) return

        silenceJob?.cancel()

        if (isNewSourceTurn) {
            isNewSourceTurn = false
            _liveSourceSubtitleText.value = trimmed
            return
        }

        _liveSourceSubtitleText.value = limitSubtitleText(
            mergeStreamingText(_liveSourceSubtitleText.value, trimmed)
        )
    }

    /**
     * Merges streaming caption updates without repeating words when the API emits
     * overlapping partial/final transcripts during the same turn.
     */
    private fun mergeStreamingText(currentText: String, incomingText: String): String {
        val current = currentText.trim()
        val incoming = incomingText.trim()
        if (incoming.isEmpty()) return currentText
        if (current.isEmpty()) return incoming

        // Some updates contain the full accumulated sentence; others contain only a delta.
        if (incoming.startsWith(current, ignoreCase = true)) return incoming
        if (current.startsWith(incoming, ignoreCase = true) ||
            current.endsWith(incoming, ignoreCase = true)
        ) return current

        // Remove the largest whole-word overlap, e.g. "오늘은" in
        // "안녕하세요 오늘은" + "오늘은 게임을 시작하겠습니다".
        val currentWords = current.split(Regex("\\s+"))
        val incomingWords = incoming.split(Regex("\\s+"))
        val maxOverlap = minOf(currentWords.size, incomingWords.size)
        for (overlap in maxOverlap downTo 1) {
            val currentTail = currentWords.takeLast(overlap).joinToString(" ")
            val incomingHead = incomingWords.take(overlap).joinToString(" ")
            if (currentTail.equals(incomingHead, ignoreCase = true)) {
                val remainder = incomingWords.drop(overlap).joinToString(" ")
                if (remainder.isEmpty()) return current
                return appendCaptionText(current, remainder)
            }
        }

        return appendCaptionText(current, incoming)
    }

    private fun appendCaptionText(current: String, next: String): String {
        if (next.isBlank()) return current
        val punctuation = setOf(',', '.', '!', '?', ':', ';', '…', '。', '！', '？', '،')
        val needsSpace = current.isNotEmpty() &&
            !current.last().isWhitespace() &&
            !next.first().isWhitespace() &&
            next.first() !in punctuation
        return if (needsSpace) "$current $next" else current + next
    }

    private fun limitSubtitleText(updated: String): String {
        if (updated.length <= 180) return updated
        val spaceIndex = updated.indexOf(' ', updated.length - 130)
        return if (spaceIndex != -1) updated.substring(spaceIndex + 1) else updated.takeLast(130)
    }

    /**
     * OCR-mode captions replace the previous line instead of appending streaming audio deltas.
     */
    fun setScreenSourceText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        silenceJob?.cancel()
        isNewTurn = true
        isNewSourceTurn = true
        _liveSourceSubtitleText.value = trimmed
        _liveSubtitleText.value = ""
        silenceJob = repositoryScope.launch {
            delay(8000)
            if (_liveSourceSubtitleText.value == trimmed && _liveSubtitleText.value.isBlank()) {
                _liveSourceSubtitleText.value = ""
            }
        }
    }

    fun setScreenTranslatedText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        silenceJob?.cancel()
        isNewTurn = true
        isNewSourceTurn = true
        _liveSubtitleText.value = trimmed
        val expectedSource = _liveSourceSubtitleText.value
        silenceJob = repositoryScope.launch {
            delay(6500)
            if (_liveSourceSubtitleText.value == expectedSource && _liveSubtitleText.value == trimmed) {
                _liveSubtitleText.value = ""
                _liveSourceSubtitleText.value = ""
            }
        }
    }

    fun onTurnComplete() {
        isNewTurn = true
        isNewSourceTurn = true
        silenceJob?.cancel()
        silenceJob = repositoryScope.launch {
            delay(4500)
            _liveSubtitleText.value = ""
            _liveSourceSubtitleText.value = ""
        }
    }

    fun clearSubtitle() {
        silenceJob?.cancel()
        _liveSubtitleText.value = ""
        _liveSourceSubtitleText.value = ""
        isNewTurn = true
        isNewSourceTurn = true
    }

    fun toggleSubtitleOverlayVisibility() {
        val newVisibility = !_isSubtitleOverlayVisible.value
        _isSubtitleOverlayVisible.value = newVisibility
        if (newVisibility) {
            clearSubtitle()
        }
    }

    fun setSubtitleOverlayVisibility(visible: Boolean) {
        _isSubtitleOverlayVisible.value = visible
        if (visible) {
            clearSubtitle()
        }
    }

    fun setDubbingVolume(volume: Float) {
        _dubbingVolume.value = volume.coerceIn(0f, 1f)
    }

    fun toggleDubbingMute() {
        _isDubbingMuted.value = !_isDubbingMuted.value
    }

    fun setDubbingMuted(muted: Boolean) {
        _isDubbingMuted.value = muted
    }

    fun setOriginalVolumeRatio(ratio: Float) {
        _originalVolumeRatio.value = ratio.coerceIn(0f, 1f)
    }

    fun toggleOriginalMute() {
        _isOriginalMuted.value = !_isOriginalMuted.value
    }

    fun setOriginalMuted(muted: Boolean) {
        _isOriginalMuted.value = muted
    }

    fun setShowDualSubtitles(show: Boolean) {
        _showDualSubtitles.value = show
    }

    fun resetSession() {
        _isDubbingRunning.value = false
        _audioAmplitude.value = 0f
        _audioCaptureStatus.value = "Não iniciada"
        _connectionStatus.value = "Disconnected"
        _detectedSourceLanguage.value = ""
        clearSubtitle()
    }
}
