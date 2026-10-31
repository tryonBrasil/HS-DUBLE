package com.alad.app.presentation.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alad.app.R
import com.alad.app.presentation.main.supportedLanguages
import com.alad.app.data.repository.UserPreferencesRepository
import com.alad.app.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit = {}, onNavigateToAbout: () -> Unit = {}) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val apiKey by viewModel.userApiKey.collectAsState()
    val selectedVoice by viewModel.voicePersona.collectAsState()
    val selectedMode by viewModel.dubbingMode.collectAsState()
    val showDualSubtitles by viewModel.showDualSubtitles.collectAsState()
    val sourceLang by viewModel.sourceLang.collectAsState()
    val targetLang by viewModel.targetLang.collectAsState()
    val dubbingVolume by viewModel.volumeRatio.collectAsState()
    val originalVolume by viewModel.originalVolume.collectAsState()
    val isOriginalMuted by viewModel.isOriginalMuted.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    var apiKeyVisible by remember { mutableStateOf(false) }

    AmbientBackground {
        Scaffold(
            topBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlassIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        onClick = onBack,
                        tint = Color.White
                    )

                    Spacer(modifier = Modifier.width(16.dp))

                    Text(
                        text = stringResource(R.string.settings),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    )
                }
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            bottomBar = { HsBottomNavigationBar(selected = "settings", onHome = onBack, onSettings = {}, onAbout = onNavigateToAbout) },
            containerColor = Color.Transparent
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(scrollState)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    borderColor = NeonCyan,
                    borderAlpha = 0.30f
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_hs_logo),
                            contentDescription = "HS Voice Translator",
                            modifier = Modifier.size(58.dp).clip(RoundedCornerShape(15.dp))
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "HS VOICE TRANSLATOR",
                                maxLines = 1,
                                softWrap = false,
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 16.sp,
                                letterSpacing = 0.6.sp
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = "Personalize sua experiência de jogo",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                            Text(
                                text = "JOGUE SEM BARREIRAS",
                                color = NeonCyan,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                letterSpacing = 1.5.sp
                            )
                        }
                    }
                }

                Text(
                    "IDIOMA",
                    color = Color(0xFFFF526B),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.8.sp,
                    modifier = Modifier.padding(start = 4.dp, bottom = (-10).dp)
                )
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    backgroundColor = Color(0xFF0B121B),
                    borderColor = Color(0xFFFF1738),
                    borderAlpha = 0.32f
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        LanguagePreferenceDropdown(
                            title = "Idioma de origem",
                            selectedCode = sourceLang,
                            options = listOf("auto" to "Detecção automática") + supportedLanguages,
                            onSelected = viewModel::updateSourceLang
                        )
                        LanguagePreferenceDropdown(
                            title = "Idioma de destino",
                            selectedCode = targetLang,
                            options = supportedLanguages.filterNot { it.first == "pt" } + ("pt-BR" to "🇧🇷 Português (BR)"),
                            onSelected = viewModel::updateTargetLang
                        )
                    }
                }

                Text(
                    "ÁUDIO E VOZ",
                    color = Color(0xFFFF526B),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.8.sp,
                    modifier = Modifier.padding(start = 4.dp, bottom = (-10).dp)
                )

                // 1. API Key Configuration
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    borderColor = NeonCyan,
                    borderAlpha = 0.25f
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(NeonCyan.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Key,
                                    contentDescription = null,
                                    tint = NeonCyan,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column {
                                Text(
                                    text = stringResource(R.string.gemini_api_key),
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    fontSize = 16.sp
                                )
                                Text(
                                    text = stringResource(R.string.gemini_live_api),
                                    color = TextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = viewModel::updateApiKey,
                            placeholder = { Text(stringResource(R.string.api_key_placeholder), color = TextSecondary.copy(alpha = 0.6f)) },
                            visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color(0x25FFFFFF),
                                unfocusedContainerColor = Color(0x12FFFFFF),
                                focusedBorderColor = NeonCyan,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            trailingIcon = {
                                IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                                    Icon(
                                        imageVector = if (apiKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (apiKeyVisible) stringResource(R.string.hide) else stringResource(R.string.show),
                                        tint = if (apiKeyVisible) NeonCyan else TextSecondary
                                    )
                                }
                            }
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = stringResource(R.string.api_key_guide),
                            color = TextSecondary,
                            fontSize = 13.sp,
                            lineHeight = 20.sp
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(
                                            NeonCyan.copy(alpha = 0.15f),
                                            NeonRose.copy(alpha = 0.15f)
                                        )
                                    )
                                )
                                .border(
                                    BorderStroke(
                                        1.dp,
                                        Brush.horizontalGradient(
                                            listOf(
                                                NeonCyan.copy(alpha = 0.4f),
                                                NeonRose.copy(alpha = 0.4f)
                                            )
                                        )
                                    ),
                                    shape = RoundedCornerShape(14.dp)
                                )
                                .clickable {
                                    uriHandler.openUri("https://aistudio.google.com/app/apikey")
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.get_api_key),
                                    color = NeonCyan,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )

                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = null,
                                    tint = NeonCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                Text(
                    "AJUSTES DE ÁUDIO",
                    color = Color(0xFFFF526B),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.8.sp,
                    modifier = Modifier.padding(start = 4.dp, bottom = (-10).dp)
                )
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    backgroundColor = Color(0xFF0B121B),
                    borderColor = Color(0xFFFF1738),
                    borderAlpha = 0.32f
                ) {
                    Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Volume da dublagem", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Spacer(Modifier.weight(1f))
                            Text("${(dubbingVolume * 100).toInt()}%", color = Color(0xFFFF526B), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        Slider(
                            value = dubbingVolume.coerceIn(0f, 1f),
                            onValueChange = viewModel::updateVolumeRatio,
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFFFF1738),
                                activeTrackColor = Color(0xFFFF1738),
                                inactiveTrackColor = Color(0xFF354253)
                            )
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Volume do áudio original", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Spacer(Modifier.weight(1f))
                            Text("${(originalVolume * 100).toInt()}%", color = Color(0xFFFF526B), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        Slider(
                            value = originalVolume.coerceIn(0f, 1f),
                            onValueChange = viewModel::updateOriginalVolume,
                            enabled = !isOriginalMuted,
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFFFF1738),
                                activeTrackColor = Color(0xFFFF1738),
                                inactiveTrackColor = Color(0xFF354253),
                                disabledThumbColor = Color(0xFF667080),
                                disabledActiveTrackColor = Color(0xFF354253)
                            )
                        )
                        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Silenciar áudio original", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text("Remove o som original do jogo durante a dublagem.", color = TextSecondary, fontSize = 11.sp)
                            }
                            Switch(
                                checked = isOriginalMuted,
                                onCheckedChange = viewModel::updateOriginalMuted,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = Color(0xFFFF1738),
                                    uncheckedThumbColor = Color.Gray,
                                    uncheckedTrackColor = Color(0xFF354253)
                                )
                            )
                        }
                    }
                }

                // 2. AI Voice Persona Selection Card
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    borderColor = NeonRose,
                    borderAlpha = 0.25f
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(NeonRose.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.RecordVoiceOver,
                                    contentDescription = null,
                                    tint = NeonRose,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column {
                                Text(
                                    text = stringResource(R.string.voice_persona),
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    fontSize = 16.sp
                                )
                                Text(
                                    text = stringResource(R.string.voice_persona_desc),
                                    color = TextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        val voices = listOf(
                            Triple("Puck", stringResource(R.string.voice_puck), stringResource(R.string.voice_energetic)),
                            Triple("Charon", stringResource(R.string.voice_charon), stringResource(R.string.voice_deep)),
                            Triple("Aoede", stringResource(R.string.voice_aoede), stringResource(R.string.voice_melodic)),
                            Triple("Fenrir", stringResource(R.string.voice_fenrir), stringResource(R.string.voice_authoritative)),
                            Triple("Kore", stringResource(R.string.voice_kore), stringResource(R.string.voice_calm))
                        )

                        voices.forEach { (voiceId, voiceLabel, badge) ->
                            val isSelected = selectedVoice == voiceId
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(
                                        if (isSelected) {
                                            Brush.horizontalGradient(
                                                listOf(NeonRose.copy(alpha = 0.25f), NeonCyan.copy(alpha = 0.15f))
                                            )
                                        } else {
                                            Brush.horizontalGradient(
                                                listOf(Color.White.copy(alpha = 0.04f), Color.White.copy(alpha = 0.02f))
                                            )
                                        }
                                    )
                                    .border(
                                        BorderStroke(
                                            1.dp,
                                            if (isSelected) NeonRose else Color.White.copy(alpha = 0.08f)
                                        ),
                                        shape = RoundedCornerShape(14.dp)
                                    )
                                    .clickable { viewModel.updateVoicePersona(voiceId) }
                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = voiceLabel,
                                            color = if (isSelected) Color.White else Color.White.copy(alpha = 0.85f),
                                            fontSize = 14.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }

                                    if (isSelected) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(NeonRose.copy(alpha = 0.3f))
                                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = badge.uppercase(),
                                                color = NeonCyan,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 4. Dual Subtitles Option Card
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    borderColor = NeonCyan,
                    borderAlpha = 0.20f,
                    onClick = { viewModel.updateShowDualSubtitles(!showDualSubtitles) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.dual_subtitles),
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                fontSize = 15.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.dual_subtitles_desc),
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }

                        Switch(
                            checked = showDualSubtitles,
                            onCheckedChange = { viewModel.updateShowDualSubtitles(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = NeonCyan,
                                uncheckedThumbColor = Color.Gray,
                                uncheckedTrackColor = Color.White.copy(alpha = 0.1f)
                            )
                        )
                    }
                }

                // 5. Dubbing Mode Card
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    borderColor = NeonCyan,
                    borderAlpha = 0.25f
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(NeonCyan.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = null,
                                    tint = NeonCyan,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column {
                                Text(
                                    text = stringResource(R.string.dubbing_mode),
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    fontSize = 16.sp
                                )
                                Text(
                                    text = stringResource(R.string.choose_audio_subtitles_both),
                                    color = TextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        val modes = listOf(
                            Triple(UserPreferencesRepository.MODE_HYBRID, stringResource(R.string.mode_hybrid), Icons.Default.GraphicEq),
                            Triple(UserPreferencesRepository.MODE_AUDIO_ONLY, stringResource(R.string.mode_audio_only), Icons.AutoMirrored.Filled.VolumeUp),
                            Triple(UserPreferencesRepository.MODE_SUBTITLE_ONLY, stringResource(R.string.mode_subtitle_only), Icons.Default.Subtitles)
                        )

                        modes.forEach { (modeId, modeLabel, icon) ->
                            val isSelected = selectedMode == modeId
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(
                                        if (isSelected) {
                                            Brush.horizontalGradient(
                                                listOf(NeonCyan.copy(alpha = 0.25f), NeonCyan.copy(alpha = 0.15f))
                                            )
                                        } else {
                                            Brush.horizontalGradient(
                                                listOf(Color.White.copy(alpha = 0.04f), Color.White.copy(alpha = 0.02f))
                                            )
                                        }
                                    )
                                    .border(
                                        BorderStroke(
                                            1.dp,
                                            if (isSelected) NeonCyan else Color.White.copy(alpha = 0.08f)
                                        ),
                                        shape = RoundedCornerShape(14.dp)
                                    )
                                    .clickable { viewModel.updateDubbingMode(modeId) }
                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        tint = if (isSelected) NeonCyan else TextSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = modeLabel,
                                        color = if (isSelected) Color.White else Color.White.copy(alpha = 0.85f),
                                        fontSize = 14.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = NeonCyan,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Save Settings Button
                val interactionSource = remember { MutableInteractionSource() }
                val isPressed by interactionSource.collectIsPressedAsState()
                val scale by animateFloatAsState(
                    targetValue = if (isPressed) 0.98f else 1.0f,
                    label = "save_btn_scale"
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .scale(scale)
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(NeonCyan, NeonCyan, NeonCoral)
                            )
                        )
                        .border(
                            BorderStroke(1.dp, Color.White.copy(alpha = 0.4f)),
                            shape = RoundedCornerShape(18.dp)
                        )
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = {
                                viewModel.saveSettings()
                                val savedMessage = context.getString(R.string.settings_saved)
                                scope.launch {
                                    try {
                                        snackbarHostState.showSnackbar(savedMessage)
                                    } catch (e: Exception) {
                                        android.util.Log.e("SettingsScreen", "Failed to display snackbar", e)
                                    }
                                }
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Save,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.save_settings),
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            fontSize = 16.sp
                        )
                    }
                }

                // Branded footer: keeps the settings screen visually complete without
                // adding controls or changing any preference behavior.
                Spacer(modifier = Modifier.height(4.dp))
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    color = Color.White.copy(alpha = 0.08f),
                    thickness = 1.dp
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(DeepSpaceElevated)
                                .border(1.dp, NeonCyan.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                                .padding(4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_hs_logo),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        Spacer(modifier = Modifier.width(9.dp))
                        Column {
                            Text(
                                text = "HS TECH",
                                color = Color.White.copy(alpha = 0.88f),
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 10.sp,
                                letterSpacing = 1.2.sp
                            )
                            Text(
                                text = "JOGUE SEM BARREIRAS",
                                color = TextMuted,
                                fontSize = 9.sp,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                    Text(
                        text = "v${runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "1.3.22"}",
                        color = TextMuted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguagePreferenceDropdown(
    title: String,
    selectedCode: String,
    options: List<Pair<String, String>>,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first.equals(selectedCode, true) }?.second ?: selectedCode
    Column {
        Text(title, color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(5.dp))
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = selectedLabel,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF111B27),
                    unfocusedContainerColor = Color(0xFF111B27),
                    focusedBorderColor = Color(0xFFFF1738),
                    unfocusedBorderColor = Color(0xFF293747),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(Color(0xFF0D131D))
            ) {
                options.forEach { (code, label) ->
                    DropdownMenuItem(
                        text = { Text(label, color = if (code == selectedCode) Color(0xFFFF526B) else Color.White) },
                        onClick = {
                            onSelected(code)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

