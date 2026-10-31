package com.alad.app.presentation.main

import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.alad.app.R
import com.alad.app.UpdateInstaller
import com.alad.app.ui.theme.*
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onConnectClicked: () -> Unit,
    onDisconnectClicked: () -> Unit
) {
    val isConnected by viewModel.isConnected.collectAsState()
    val amplitude by viewModel.audioAmplitude.collectAsState()
    val connectionStatus by viewModel.statusMessage.collectAsState()
    val audioCaptureStatus by viewModel.audioCaptureStatus.collectAsState()
    val apiReady = connectionStatus.equals("Gemini Ready", ignoreCase = true)
    val apiStatusLabel = when {
        apiReady -> "API Gemini pronta para traduzir"
        connectionStatus.equals("Connected", ignoreCase = true) -> "API conectada; configurando tradução…"
        connectionStatus.startsWith("Error:", ignoreCase = true) -> connectionStatus.replaceFirst("Error:", "Erro:")
        connectionStatus.startsWith("Reconnecting", ignoreCase = true) -> connectionStatus.replaceFirst("Reconnecting", "Reconectando")
        else -> connectionStatus
    }
    val liveSubtitleText by viewModel.liveSubtitleText.collectAsState()
    val liveSourceSubtitleText by viewModel.liveSourceSubtitleText.collectAsState()
    val detectedSourceLanguage by viewModel.detectedSourceLanguage.collectAsState()
    val detectedSourceLanguageLabel = when (detectedSourceLanguage.substringBefore('-').lowercase()) {
        "ja" -> "Japonês"
        "en" -> "Inglês"
        "ko" -> "Coreano"
        "zh" -> "Chinês"
        "pt" -> "Português"
        "es" -> "Espanhol"
        "fr" -> "Francês"
        "de" -> "Alemão"
        "ru" -> "Russo"
        "it" -> "Italiano"
        else -> supportedLanguages
            .find { it.first.equals(detectedSourceLanguage.substringBefore('-'), ignoreCase = true) }
            ?.second ?: detectedSourceLanguage
    }
    val showDualSubtitles by viewModel.showDualSubtitles.collectAsState()
    val selectedVoice by viewModel.voicePersona.collectAsState()
    val selectedMode by viewModel.dubbingMode.collectAsState()
    val sourceLang by viewModel.sourceLang.collectAsState()
    val targetLang by viewModel.targetLang.collectAsState()
    val targetDialect by viewModel.targetDialect.collectAsState()

    val dubbingVol by viewModel.dubbingVolume.collectAsState()
    val isDubbingMuted by viewModel.isDubbingMuted.collectAsState()
    val originalVol by viewModel.originalVolumeRatio.collectAsState()
    val isOriginalMuted by viewModel.isOriginalMuted.collectAsState()

    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var showHelpDialog by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val updateScope = rememberCoroutineScope()
    var isCheckingUpdates by remember { mutableStateOf(false) }
    var updateDialogMessage by remember { mutableStateOf<String?>(null) }
    var updateDownloadUrl by remember { mutableStateOf<String?>(null) }

    // Verifica a versão publicada ao abrir o app. Se houver uma versão mais nova,
    // baixa automaticamente e entrega o APK ao instalador seguro do Android.
    LaunchedEffect(Unit) {
        if (!isCheckingUpdates) {
            isCheckingUpdates = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val connection = URL("https://api.github.com/repos/tryonBrasil/HS-DUBLE/releases?per_page=20")
                        .openConnection() as java.net.HttpURLConnection
                    connection.connectTimeout = 12000
                    connection.readTimeout = 12000
                    connection.setRequestProperty("Accept", "application/vnd.github+json")
                    connection.setRequestProperty("User-Agent", "HS-Voice-Translator-Android")
                    try {
                        if (connection.responseCode !in 200..299) {
                            throw IllegalStateException("Não foi possível consultar as versões.")
                        }
                        val releases = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
                        val release = (0 until releases.length())
                            .mapNotNull { releases.optJSONObject(it) }
                            .firstOrNull { candidate ->
                                !candidate.optBoolean("draft", false) &&
                                    candidate.optString("tag_name").removePrefix("v")
                                        .matches(Regex("\\d+(\\.\\d+)*"))
                            } ?: throw IllegalStateException("Nenhuma versão válida encontrada.")
                        val latestVersion = release.optString("tag_name").removePrefix("v")
                        val currentVersion = context.packageManager
                            .getPackageInfo(context.packageName, 0).versionName ?: "0"
                        val apkUrl = release.optJSONArray("assets")
                            ?.let { assets ->
                                (0 until assets.length()).map { assets.getJSONObject(it) }
                                    .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                                    ?.optString("browser_download_url")
                            }.orEmpty()
                        Triple(currentVersion, latestVersion, apkUrl)
                    } finally {
                        connection.disconnect()
                    }
                }
            }
            result.onSuccess { (current, latest, downloadUrl) ->
                val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }
                val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
                val latestIsNewer = (0 until maxOf(latestParts.size, currentParts.size))
                    .firstNotNullOfOrNull { index ->
                        val comparison = latestParts.getOrElse(index) { 0 }
                            .compareTo(currentParts.getOrElse(index) { 0 })
                        comparison.takeIf { it != 0 }
                    }?.let { it > 0 } ?: false
                if (latestIsNewer && downloadUrl.isNotBlank()) {
                    runCatching {
                        UpdateInstaller.enqueueDownload(context, downloadUrl, latest)
                    }.onFailure {
                        updateDialogMessage = "A versão $latest está disponível, mas não foi possível iniciar o download: ${it.localizedMessage}"
                    }
                }
            }
            isCheckingUpdates = false
        }
    }

        if (showHelpDialog) {
        Dialog(onDismissRequest = { showHelpDialog = false }) {
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(24.dp),
                backgroundColor = DeepSpaceElevated,
                borderColor = NeonCyan,
                borderAlpha = 0.35f
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(NeonCyan.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = NeonCyan,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = stringResource(R.string.help_title),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = stringResource(R.string.help_content),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = TextSecondary,
                            lineHeight = 22.sp
                        )
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = { showHelpDialog = false },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeonCyan
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.close),
                            fontWeight = FontWeight.Bold,
                            color = DeepSpace
                        )
                    }
                }
            }
        }
    }

    AmbientBackground {
        Scaffold(
            topBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(DeepSpaceElevated)
                                .border(
                                    BorderStroke(
                                        1.5.dp,
                                        Brush.linearGradient(
                                            listOf(
                                                NeonCyan.copy(alpha = 0.9f),
                                                NeonRose.copy(alpha = 0.65f)
                                            )
                                        )
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .padding(4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_hs_logo),
                                contentDescription = stringResource(R.string.app_name_full),
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "HS Voice Translator",
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White,
                                fontSize = 17.sp,
                                letterSpacing = 0.35.sp,
                                maxLines = 1,
                                softWrap = false
                            )
                            Text(
                                text = "JOGUE SEM BARREIRAS",
                                color = NeonCyan.copy(alpha = 0.85f),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        GlassIconButton(
                            icon = Icons.Default.Info,
                            contentDescription = stringResource(R.string.help_accessibility),
                            onClick = onNavigateToAbout,
                            tint = NeonCyan
                        )

                        GlassIconButton(
                            icon = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.settings_accessibility),
                            onClick = onNavigateToSettings,
                            tint = Color.White
                        )
                    }
                }
            },
            bottomBar = { HsBottomNavigationBar(selected = "home", onHome = {}, onSettings = onNavigateToSettings, onAbout = onNavigateToAbout) },
            containerColor = Color.Transparent
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(scrollState)
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {

                // 1. Status Indicator — use the real API state, not just the running service flag.
                HomeTranslationHero(
                    isTranslating = isConnected,
                    sourceLabel = supportedLanguages.find { it.first == sourceLang }?.second ?: "Detecção automática",
                    targetLabel = supportedLanguages.find { it.first == targetLang }?.second ?: if (targetLang.startsWith("pt")) "🇧🇷 Português (BR)" else targetLang
                )

                StartStopDubbingButton(
                    isConnected = isConnected,
                    onClick = {
                        if (isConnected) onDisconnectClicked() else onConnectClicked()
                    }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HomeModeTile(
                        modifier = Modifier.weight(1f),
                        title = "Dublagem",
                        subtitle = "Áudio traduzido",
                        selected = selectedMode == com.alad.app.data.repository.UserPreferencesRepository.MODE_AUDIO_ONLY,
                        onClick = { viewModel.updateDubbingMode(com.alad.app.data.repository.UserPreferencesRepository.MODE_AUDIO_ONLY) }
                    )
                    HomeModeTile(
                        modifier = Modifier.weight(1f),
                        title = "Legenda + áudio",
                        subtitle = "Texto e voz",
                        selected = selectedMode == com.alad.app.data.repository.UserPreferencesRepository.MODE_HYBRID,
                        onClick = { viewModel.updateDubbingMode(com.alad.app.data.repository.UserPreferencesRepository.MODE_HYBRID) }
                    )
                }

                // Game capture status card, styled after the reference's game-detection screen.
                val captureColor = when {
                    audioCaptureStatus.contains("Falha", ignoreCase = true) || audioCaptureStatus.contains("Erro", ignoreCase = true) -> Color(0xFFFF7373)
                    isConnected -> NeonEmerald
                    else -> TextSecondary
                }
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    borderColor = NeonCyan,
                    borderAlpha = 0.22f
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.size(38.dp).clip(RoundedCornerShape(11.dp))
                                .background(NeonCyan.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.HeadsetMic, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(21.dp))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Captura do jogo", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text(
                                text = if (isConnected) audioCaptureStatus else "Inicie a dublagem para capturar o áudio do jogo",
                                color = captureColor,
                                fontSize = 11.sp,
                                lineHeight = 16.sp
                            )
                            Text(
                                "Detecção automática do título do jogo: em breve",
                                color = TextMuted,
                                fontSize = 10.sp
                            )
                        }
                        Box(
                            modifier = Modifier.size(8.dp).clip(CircleShape).background(captureColor)
                        )
                    }
                }

                // 2. Animated Audio Waveform
                AnimatedWaveform(
                    isConnected = isConnected,
                    amplitude = amplitude,
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                // 3. Live Dual Subtitles Banner
                AnimatedVisibility(
                    visible = isConnected && (liveSubtitleText.isNotEmpty() || (showDualSubtitles && liveSourceSubtitleText.isNotEmpty())),
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        borderColor = NeonCyan,
                        borderAlpha = 0.35f
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(NeonCyan)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(R.string.live_subtitles).uppercase(),
                                        color = NeonCyan,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp
                                    )
                                }

                                Text(
                                    text = "$selectedVoice • " + when (selectedMode) { com.alad.app.data.repository.UserPreferencesRepository.MODE_HYBRID -> stringResource(R.string.mode_hybrid); com.alad.app.data.repository.UserPreferencesRepository.MODE_AUDIO_ONLY -> stringResource(R.string.mode_audio_only); com.alad.app.data.repository.UserPreferencesRepository.MODE_SUBTITLE_ONLY -> stringResource(R.string.mode_subtitle_only); else -> selectedMode },
                                    color = TextSecondary,
                                    fontSize = 11.sp
                                )
                            }

                            if (detectedSourceLanguage.isNotBlank()) {
                                Text(
                                    text = "Idioma detectado: $detectedSourceLanguageLabel",
                                    color = NeonCyan,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            } else if (isConnected) {
                                Text(
                                    text = "Detectando idioma da fala…",
                                    color = TextMuted,
                                    fontSize = 11.sp
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            if (showDualSubtitles && liveSourceSubtitleText.isNotEmpty()) {
                                Text(
                                    text = liveSourceSubtitleText,
                                    color = TextSecondary,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp,
                                    fontWeight = FontWeight.Normal
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                            }

                            if (liveSubtitleText.isNotEmpty()) {
                                Text(
                                    text = liveSubtitleText,
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    lineHeight = 22.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }

                // Language, voice and translation-mode preferences are centralized in Settings.
                // 6. Floating Overlay Widget Switch Card
                val isWidgetActive by com.alad.app.core.service.OverlayWidgetService.isWidgetActive.collectAsState()
                val toggleFloatingWidget: () -> Unit = {
                    if (!Settings.canDrawOverlays(context)) {
                        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                        context.startActivity(intent)
                    } else {
                        val intent = Intent(context, com.alad.app.core.service.OverlayWidgetService::class.java)
                        if (isWidgetActive) context.stopService(intent) else context.startService(intent)
                    }
                }
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    onClick = toggleFloatingWidget
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.floating_widget),
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                fontSize = 15.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (Settings.canDrawOverlays(context)) {
                                    stringResource(R.string.enable_overlay)
                                } else {
                                    stringResource(R.string.grant_permission_widget)
                                },
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }

                        Switch(
                            checked = isWidgetActive,
                            onCheckedChange = { toggleFloatingWidget() },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = NeonCyan,
                                uncheckedThumbColor = Color.Gray,
                                uncheckedTrackColor = Color.White.copy(alpha = 0.1f)
                            )
                        )
                    }
                }

                OutlinedButton(
                    onClick = {
                        if (!isCheckingUpdates) {
                            isCheckingUpdates = true
                            updateScope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val connection = URL("https://api.github.com/repos/tryonBrasil/HS-DUBLE/releases?per_page=20")
                                            .openConnection() as java.net.HttpURLConnection
                                        connection.connectTimeout = 10000
                                        connection.readTimeout = 10000
                                        connection.setRequestProperty("Accept", "application/vnd.github+json")
                                        connection.setRequestProperty("User-Agent", "HS-Voice-Translator-Android")
                                        try {
                                            if (connection.responseCode !in 200..299) {
                                                throw IllegalStateException("Ainda não há uma versão publicada ou não foi possível consultar.")
                                            }
                                            val releases = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
                                            // Ignore malformed tags such as the accidental release tag "v".
                                            val release = (0 until releases.length())
                                                .mapNotNull { releases.optJSONObject(it) }
                                                .firstOrNull { candidate ->
                                                    candidate.optString("tag_name").removePrefix("v")
                                                        .matches(Regex("\\d+(\\.\\d+)*"))
                                                } ?: throw IllegalStateException("Nenhuma versão válida foi publicada.")
                                            val latestVersion = release.optString("tag_name").removePrefix("v")
                                            val currentVersion = context.packageManager
                                                .getPackageInfo(context.packageName, 0).versionName ?: "1.3.0"
                                            val apkUrl = release.optJSONArray("assets")
                                                ?.let { assets ->
                                                    (0 until assets.length()).map { assets.getJSONObject(it) }
                                                        .firstOrNull {
                                                            it.optString("name").contains("release", ignoreCase = true) &&
                                                                it.optString("name").endsWith(".apk", ignoreCase = true)
                                                        }
                                                        ?.optString("browser_download_url")
                                                        ?: (0 until assets.length()).map { assets.getJSONObject(it) }
                                                            .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                                                            ?.optString("browser_download_url")
                                                }.orEmpty()
                                            if (latestVersion.isBlank()) {
                                                throw IllegalStateException("Não foi possível identificar a versão publicada.")
                                            }
                                            Triple(currentVersion, latestVersion, apkUrl)
                                        } finally {
                                            connection.disconnect()
                                        }
                                    }
                                }
                                result.onSuccess { (current, latest, downloadUrl) ->
                                    val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }
                                    val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
                                    val latestIsNewer = (0 until maxOf(latestParts.size, currentParts.size))
                                        .firstNotNullOfOrNull { index ->
                                            val comparison = (latestParts.getOrElse(index) { 0 })
                                                .compareTo(currentParts.getOrElse(index) { 0 })
                                            comparison.takeIf { it != 0 }
                                        }?.let { it > 0 } ?: false
                                    if (latestIsNewer && downloadUrl.isNotBlank()) {
                                        updateDownloadUrl = downloadUrl
                                        updateDialogMessage = "Nova versão disponível: $latest (instalada: $current). Deseja baixar o APK diretamente? O download ficará nas notificações."
                                    } else {
                                        updateDownloadUrl = null
                                        updateDialogMessage = if (!latestIsNewer) {
                                            "Você já está usando a versão mais recente ($current)."
                                        } else {
                                            "A versão publicada ($latest) foi encontrada, mas o link do APK não está disponível."
                                        }
                                    }
                                }.onFailure {
                                    updateDownloadUrl = null
                                    updateDialogMessage = "Não foi possível verificar agora. Confira sua conexão e tente novamente. Se ainda não houver uma versão publicada no GitHub, essa mensagem também pode aparecer."
                                }
                                isCheckingUpdates = false
                            }
                        }
                    },
                    enabled = !isCheckingUpdates,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonCyan),
                    border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.65f))
                ) {
                    if (isCheckingUpdates) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = NeonCyan
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Verificando atualizações…", fontWeight = FontWeight.Bold)
                    } else {
                        Icon(Icons.Default.SystemUpdate, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Verificar atualizações", fontWeight = FontWeight.Bold)
                    }
                }

                updateDialogMessage?.let { message ->
                    AlertDialog(
                        onDismissRequest = {
                            updateDialogMessage = null
                            updateDownloadUrl = null
                        },
                        title = { Text("Atualização do HS Voice Translator") },
                        text = { Text(message) },
                        confirmButton = {
                            TextButton(onClick = {
                                val url = updateDownloadUrl
                                updateDialogMessage = null
                                updateDownloadUrl = null
                                if (!url.isNullOrBlank()) {
                                    runCatching {
                                        UpdateInstaller.enqueueDownload(context, url, "manual")
                                    }.onFailure {
                                        Toast.makeText(
                                            context,
                                            "Não foi possível iniciar o download: ${it.localizedMessage ?: "erro desconhecido"}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            }) {
                                Text(if (updateDownloadUrl.isNullOrBlank()) "OK" else "Baixar atualização")
                            }
                        },
                        dismissButton = {
                            if (!updateDownloadUrl.isNullOrBlank()) {
                                TextButton(onClick = {
                                    updateDialogMessage = null
                                    updateDownloadUrl = null
                                }) { Text("Cancelar") }
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
fun AnimatedWaveform(
    isConnected: Boolean,
    amplitude: Float,
    modifier: Modifier = Modifier
) {
    val animatedAmplitude by animateFloatAsState(
        targetValue = if (isConnected) amplitude.coerceIn(0.04f, 1f) else 0.03f,
        animationSpec = tween(70, easing = LinearEasing),
        label = "amplitude"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "waveform")

    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(115.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val centerY = height * 0.50f

            // 1. Draw glowing wave fill & wave stroke (matching Figma dashboard wave curves in reference image)
            val wavePath = Path()
            val fillPath = Path()
            fillPath.moveTo(0f, height)

            val stepCount = 55
            val stepX = width / stepCount

            for (i in 0..stepCount) {
                val x = i * stepX
                val normalizedX = i.toFloat() / stepCount
                // Bell curve windowing so wave attenuates gently near edges
                val window = (sin(normalizedX * Math.PI).toFloat()).coerceIn(0.12f, 1f)

                val wave1 = sin(phase + (normalizedX * 4.2f * Math.PI.toFloat())) * 0.65f
                val wave2 = sin((phase * 1.4f) + (normalizedX * 6.5f * Math.PI.toFloat())) * 0.35f
                val combinedWave = (wave1 + wave2) * window

                val maxDisplacement = height * 0.36f * (0.15f + animatedAmplitude * 1.85f)
                val y = centerY - (combinedWave * maxDisplacement).coerceIn(-height * 0.42f, height * 0.42f)

                if (i == 0) {
                    wavePath.moveTo(x, y)
                    fillPath.lineTo(x, y)
                } else {
                    wavePath.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
            }

            fillPath.lineTo(width, height)
            fillPath.close()

            // Area Gradient Fill under wave
            val fillBrush = if (isConnected) {
                Brush.verticalGradient(
                    colors = listOf(
                        NeonCyan.copy(alpha = 0.32f),
                        NeonCyan.copy(alpha = 0.12f),
                        Color.Transparent
                    ),
                    startY = centerY - height * 0.4f,
                    endY = height
                )
            } else {
                Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.08f),
                        Color.Transparent
                    ),
                    startY = centerY - height * 0.2f,
                    endY = height
                )
            }

            drawPath(path = fillPath, brush = fillBrush)

            // Glowing Wave Crest Stroke
            val strokeBrush = if (isConnected) {
                Brush.horizontalGradient(
                    colors = listOf(NeonCyan, NeonCyan, NeonRose)
                )
            } else {
                Brush.horizontalGradient(
                    colors = listOf(Color.White.copy(alpha = 0.35f), Color.White.copy(alpha = 0.12f))
                )
            }

            drawPath(
                path = wavePath,
                brush = strokeBrush,
                style = Stroke(width = if (isConnected) 3.5.dp.toPx() else 1.8.dp.toPx(), cap = StrokeCap.Round)
            )
        }
    }
}

@Composable
private fun HomeTranslationHero(
    isTranslating: Boolean,
    sourceLabel: String,
    targetLabel: String
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        backgroundColor = Color(0xFF0B121B),
        borderColor = Color(0xFFFF1738),
        borderAlpha = 0.35f
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(if (isTranslating) Color(0xFF20D58A) else Color(0xFF778394)))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (isTranslating) "Traduzindo" else "Pronto para traduzir",
                    color = if (isTranslating) Color(0xFF36E59B) else Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    Text(if (sourceLabel.contains("Coreano", true)) "🇰🇷" else "🌐", fontSize = 25.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(sourceLabel, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text("ORIGEM", color = TextMuted, fontSize = 9.sp, letterSpacing = 1.2.sp)
                }
                Icon(Icons.Default.ArrowForward, contentDescription = null, tint = Color(0xFFFF2948), modifier = Modifier.size(24.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    Text(if (targetLabel.contains("Portugu", true)) "🇧🇷" else "🌐", fontSize = 25.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(targetLabel, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text("DESTINO", color = TextMuted, fontSize = 9.sp, letterSpacing = 1.2.sp)
                }
            }
        }
    }
}

@Composable
private fun HomeModeTile(
    modifier: Modifier,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(15.dp))
            .background(if (selected) Color(0xFF45101B) else Color(0xFF101822))
            .border(1.dp, if (selected) Color(0xFFFF1738) else Color(0xFF263342), RoundedCornerShape(15.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 13.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            if (title == "Dublagem") Icons.AutoMirrored.Filled.VolumeUp else Icons.Default.Subtitles,
            contentDescription = null,
            tint = if (selected) Color(0xFFFF526B) else TextSecondary,
            modifier = Modifier.size(21.dp)
        )
        Spacer(Modifier.height(6.dp))
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
        Text(subtitle, color = TextSecondary, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
fun StartStopDubbingButton(
    isConnected: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val haptic = LocalHapticFeedback.current
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "power_button_scale"
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(Color(0xFFFF526B), Color(0xFFE60026), Color(0xFF8F0018))))
                .border(2.dp, Color(0xFFFFA0AE).copy(alpha = 0.65f), CircleShape)
                .clickable(interactionSource = interactionSource, indication = null) {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    onClick()
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isConnected) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = if (isConnected) "Parar tradução" else "Iniciar tradução",
                tint = Color.White,
                modifier = Modifier.size(38.dp)
            )
        }
        Spacer(Modifier.height(9.dp))
        Text(
            if (isConnected) "Toque para parar" else "Toque para iniciar / parar",
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            "TRADUÇÃO EM TEMPO REAL",
            color = Color(0xFFFF526B),
            fontSize = 9.sp,
            letterSpacing = 1.6.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
