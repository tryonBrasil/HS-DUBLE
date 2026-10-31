package com.alad.app.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.alad.app.data.repository.UserPreferencesRepository
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsViewModel(private val repository: UserPreferencesRepository) : ViewModel() {
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e("SettingsViewModel", "Unhandled coroutine error", throwable)
    }

    private val _userApiKey = MutableStateFlow("")
    val userApiKey: StateFlow<String> = _userApiKey.asStateFlow()

    private val _volumeRatio = MutableStateFlow(1.0f)
    val volumeRatio: StateFlow<Float> = _volumeRatio.asStateFlow()

    private val _originalVolume = MutableStateFlow(0.30f)
    val originalVolume: StateFlow<Float> = _originalVolume.asStateFlow()

    private val _isOriginalMuted = MutableStateFlow(true)
    val isOriginalMuted: StateFlow<Boolean> = _isOriginalMuted.asStateFlow()

    private val _voicePersona = MutableStateFlow("Puck")
    val voicePersona: StateFlow<String> = _voicePersona.asStateFlow()

    private val _dubbingMode = MutableStateFlow(UserPreferencesRepository.MODE_HYBRID)
    val dubbingMode: StateFlow<String> = _dubbingMode.asStateFlow()

    private val _showDualSubtitles = MutableStateFlow(true)
    val showDualSubtitles: StateFlow<Boolean> = _showDualSubtitles.asStateFlow()

    private val _sourceLang = MutableStateFlow("auto")
    val sourceLang: StateFlow<String> = _sourceLang.asStateFlow()

    private val _targetLang = MutableStateFlow("pt-BR")
    val targetLang: StateFlow<String> = _targetLang.asStateFlow()

    init {
        viewModelScope.launch(exceptionHandler) {
            try {
                _userApiKey.value = repository.apiKeyFlow.first()
                _volumeRatio.value = repository.volumeRatioFlow.first()
                _originalVolume.value = repository.originalVolumeFlow.first()
                _isOriginalMuted.value = repository.isOriginalMutedFlow.first()
                _voicePersona.value = repository.voicePersonaFlow.first()
                _dubbingMode.value = repository.dubbingModeFlow.first()
                _showDualSubtitles.value = repository.showDualSubtitlesFlow.first()
                _sourceLang.value = repository.sourceLangFlow.first()
                _targetLang.value = repository.targetLangFlow.first()
            } catch (e: Exception) {
                Log.e("SettingsViewModel", "Failed to load preferences", e)
            }
        }
    }

    fun updateVolumeRatio(value: Float) {
        _volumeRatio.value = value
        viewModelScope.launch(exceptionHandler) { repository.updateVolumeRatio(value) }
    }

    fun updateOriginalVolume(value: Float) {
        _originalVolume.value = value
        viewModelScope.launch(exceptionHandler) { repository.updateOriginalVolume(value) }
    }

    fun updateOriginalMuted(muted: Boolean) {
        _isOriginalMuted.value = muted
        viewModelScope.launch(exceptionHandler) { repository.updateOriginalMuted(muted) }
    }

    fun updateApiKey(key: String) {
        _userApiKey.value = key
        viewModelScope.launch(exceptionHandler) {
            repository.updateApiKey(key)
        }
    }

    fun updateVoicePersona(voice: String) {
        _voicePersona.value = voice
        viewModelScope.launch(exceptionHandler) {
            repository.updateVoicePersona(voice)
        }
    }

    fun updateDubbingMode(mode: String) {
        _dubbingMode.value = mode
        viewModelScope.launch(exceptionHandler) {
            repository.updateDubbingMode(mode)
        }
    }

    fun updateSourceLang(lang: String) {
        _sourceLang.value = lang
        viewModelScope.launch(exceptionHandler) { repository.updateSourceLang(lang) }
    }

    fun updateTargetLang(lang: String) {
        _targetLang.value = lang
        viewModelScope.launch(exceptionHandler) { repository.updateTargetLang(lang) }
    }

    fun updateShowDualSubtitles(show: Boolean) {
        _showDualSubtitles.value = show
        viewModelScope.launch(exceptionHandler) {
            repository.updateShowDualSubtitles(show)
            com.alad.app.data.repository.DubbingSessionRepository.setShowDualSubtitles(show)
        }
    }

    fun saveSettings() {
        viewModelScope.launch(exceptionHandler) {
            try {
                repository.updateApiKey(_userApiKey.value)
                repository.updateVolumeRatio(_volumeRatio.value)
                repository.updateOriginalVolume(_originalVolume.value)
                repository.updateOriginalMuted(_isOriginalMuted.value)
                repository.updateVoicePersona(_voicePersona.value)
                repository.updateDubbingMode(_dubbingMode.value)
                repository.updateShowDualSubtitles(_showDualSubtitles.value)
                repository.updateSourceLang(_sourceLang.value)
                repository.updateTargetLang(_targetLang.value)
            } catch (e: Exception) {
                Log.e("SettingsViewModel", "Failed to save preferences", e)
            }
        }
    }
}

class SettingsViewModelFactory(private val repository: UserPreferencesRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SettingsViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
