package com.alad.app.core.service

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import com.alad.app.MainActivity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.alad.app.R
import com.alad.app.data.repository.DubbingSessionRepository
import com.alad.app.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class OverlayWidgetService : LifecycleService() {

    companion object {
        val isWidgetActive = MutableStateFlow(false)
    }

    private lateinit var windowManager: WindowManager
    private lateinit var controllerComposeView: ComposeView
    private lateinit var subtitleComposeView: ComposeView

    private var controllerParams: WindowManager.LayoutParams? = null
    private var subtitleParams: WindowManager.LayoutParams? = null
    private var isSubtitleViewAttached = false

    private val savedStateRegistryOwner by lazy { ServiceSavedStateRegistryOwner(this) }
    private val viewModelStoreOwner by lazy { ServiceViewModelStoreOwner() }

    override fun onCreate() {
        super.onCreate()
        isWidgetActive.value = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // 1. Controller Pill Window Params (Top-left draggable)
        controllerParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 220
        }

        // 2. Subtitle Window Params (Bottom-center draggable)
        subtitleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 100
        }

        // Setup Controller ComposeView
        controllerComposeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                ALADTheme {
                    FloatingControllerCapsule(
                        onDrag = { dx, dy ->
                            controllerParams?.let { p ->
                                p.x = (p.x + dx.toInt()).coerceAtLeast(0)
                                p.y = (p.y + dy.toInt()).coerceAtLeast(0)
                                windowManager.updateViewLayout(controllerComposeView, p)
                            }
                        },
                        onClose = { stopSelf() },
                        onToggle = { isCurrentlyRunning ->
                            if (isCurrentlyRunning) {
                                val intent = Intent(this@OverlayWidgetService, AudioDubbingForegroundService::class.java).apply {
                                    action = AudioDubbingForegroundService.ACTION_STOP
                                }
                                startService(intent)
                            } else {
                                val intent = Intent(this@OverlayWidgetService, com.alad.app.TransparentCaptureActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                startActivity(intent)
                            }
                        }
                    )
                }
            }
        }
        controllerComposeView.setViewTreeLifecycleOwner(this)
        controllerComposeView.setViewTreeSavedStateRegistryOwner(savedStateRegistryOwner)
        controllerComposeView.setViewTreeViewModelStoreOwner(viewModelStoreOwner)

        // Setup Subtitle ComposeView
        subtitleComposeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            visibility = View.GONE
            setContent {
                ALADTheme {
                    FloatingCinemaSubtitles(
                        onDrag = { dx, dy ->
                            subtitleParams?.let { p ->
                                p.x = (p.x + dx.toInt())
                                p.y = (p.y - dy.toInt()).coerceAtLeast(20)
                                windowManager.updateViewLayout(subtitleComposeView, p)
                            }
                        },
                        onDismiss = {
                            DubbingSessionRepository.setSubtitleOverlayVisibility(false)
                        }
                    )
                }
            }
        }
        subtitleComposeView.setViewTreeLifecycleOwner(this)
        subtitleComposeView.setViewTreeSavedStateRegistryOwner(savedStateRegistryOwner)
        subtitleComposeView.setViewTreeViewModelStoreOwner(viewModelStoreOwner)

        // Attach controller and subtitle view to WindowManager
        windowManager.addView(controllerComposeView, controllerParams)
        try {
            windowManager.addView(subtitleComposeView, subtitleParams)
            isSubtitleViewAttached = true
        } catch (e: Exception) {
            android.util.Log.e("OverlayWidgetService", "Error initially attaching subtitle view", e)
        }

        // Dynamically toggle subtitle visibility and touchability based on session state
        lifecycleScope.launch {
            combine(
                DubbingSessionRepository.isDubbingRunning,
                DubbingSessionRepository.isSubtitleOverlayVisible
            ) { isRunning, isVisible ->
                isRunning && isVisible
            }.collect { shouldShowSubtitles ->
                if (!isSubtitleViewAttached) return@collect
                subtitleParams?.let { params ->
                    if (shouldShowSubtitles) {
                        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        subtitleComposeView.visibility = View.VISIBLE
                    } else {
                        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        subtitleComposeView.visibility = View.GONE
                    }
                    try {
                        windowManager.updateViewLayout(subtitleComposeView, params)
                    } catch (e: Exception) {
                        android.util.Log.e("OverlayWidgetService", "Error updating subtitle layout", e)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isWidgetActive.value = false
        if (::controllerComposeView.isInitialized) {
            try { windowManager.removeView(controllerComposeView) } catch (_: Exception) {}
        }
        if (isSubtitleViewAttached && ::subtitleComposeView.isInitialized) {
            try { windowManager.removeView(subtitleComposeView) } catch (_: Exception) {}
            isSubtitleViewAttached = false
        }
    }
}

@Composable
fun FloatingControllerCapsule(
    onDrag: (Float, Float) -> Unit,
    onClose: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    val isRunning by DubbingSessionRepository.isDubbingRunning.collectAsState()
    val isSubtitleVisible by DubbingSessionRepository.isSubtitleOverlayVisible.collectAsState()
    val isDubbingMuted by DubbingSessionRepository.isDubbingMuted.collectAsState()
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val pulse = rememberInfiniteTransition(label = "hs_overlay_pulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.18f,
        targetValue = if (isRunning) 0.42f else 0.18f,
        animationSpec = infiniteRepeatable(tween(850), RepeatMode.Reverse),
        label = "hs_overlay_glow"
    )

    Column(
        modifier = Modifier
            .width(224.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xF5091018))
            .border(1.dp, Color(0xFFFF2948).copy(alpha = 0.82f), RoundedCornerShape(22.dp))
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                }
            }
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_hs_logo),
                contentDescription = "HS Voice Translator",
                modifier = Modifier.size(30.dp).clip(RoundedCornerShape(8.dp))
            )
            Spacer(Modifier.width(9.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("HS Voice Translator", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(if (isRunning) "TRADUÇÃO ATIVA" else "CONTROLE FLUTUANTE", color = if (isRunning) Color(0xFF36E59B) else TextSecondary, fontSize = 8.sp, letterSpacing = 1.sp)
            }
            Box(
                modifier = Modifier.size(25.dp).clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.08f))
                    .clickable { onClose() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Close, contentDescription = "Fechar controle", tint = Color.White, modifier = Modifier.size(15.dp))
            }
        }

        Spacer(Modifier.height(16.dp))
        Box(
            modifier = Modifier.size(76.dp).clip(CircleShape)
                .background(Color(0xFFFF1738).copy(alpha = pulseAlpha))
                .border(1.dp, Color(0xFFFF526B), CircleShape)
                .clickable {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggle(isRunning)
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier.size(58.dp).clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Color(0xFFFF526B), Color(0xFFE60026), Color(0xFF8F0018)))),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = if (isRunning) "Parar dublagem" else "Iniciar dublagem",
                    tint = Color.White,
                    modifier = Modifier.size(30.dp)
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF151F2B)).padding(vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, tint = if (isDubbingMuted) TextSecondary else Color(0xFFFF526B), modifier = Modifier.size(19.dp))
                Spacer(Modifier.height(3.dp))
                Text("Dublagem", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                Text(if (isDubbingMuted) "Silenciada" else if (isRunning) "Ativa" else "Pronta", color = TextSecondary, fontSize = 8.sp)
            }
            Column(
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF151F2B))
                    .clickable {
                        context.startActivity(
                            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        )
                    }
                    .padding(vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.Settings, contentDescription = null, tint = Color.White, modifier = Modifier.size(19.dp))
                Spacer(Modifier.height(3.dp))
                Text("Configurações", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                Text("Abrir app", color = TextSecondary, fontSize = 8.sp)
            }
        }
        if (isRunning) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Legendas", color = TextSecondary, fontSize = 10.sp)
                androidx.compose.material3.Switch(
                    checked = isSubtitleVisible,
                    onCheckedChange = { DubbingSessionRepository.setSubtitleOverlayVisibility(it) }
                )
            }
        }
    }
}

@Composable
fun FloatingCinemaSubtitles(
    onDrag: (Float, Float) -> Unit,
    onDismiss: () -> Unit
) {
    val liveSubtitleText by DubbingSessionRepository.liveSubtitleText.collectAsState()
    val liveSourceSubtitleText by DubbingSessionRepository.liveSourceSubtitleText.collectAsState()
    val showDualSubtitles by DubbingSessionRepository.showDualSubtitles.collectAsState()
    val connectionStatus by DubbingSessionRepository.connectionStatus.collectAsState()

    val infiniteTransition = rememberInfiniteTransition(label = stringResource(R.string.overlay_listening))
    val listeningAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(850, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "listeningAlpha"
    )

    Box(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .widthIn(min = 260.dp, max = 360.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xF20D1017),
                        Color(0xF807090E)
                    )
                )
            )
            .border(
                BorderStroke(
                    1.dp,
                    Brush.linearGradient(
                        listOf(
                            NeonCyan.copy(alpha = 0.45f),
                            NeonRose.copy(alpha = 0.25f)
                        )
                    )
                ),
                shape = RoundedCornerShape(20.dp)
            )
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                }
            }
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Column {
            // Header Bar with Status Dot + Drag Handle + Close
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (connectionStatus == "Gemini Ready" || liveSubtitleText.isNotEmpty()) NeonCyan else NeonCoral)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.overlay_subtitles_title),
                        color = NeonCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp
                    )
                }

                // Drag indicator / handle
                Box(
                    modifier = Modifier
                        .width(28.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(Color.White.copy(alpha = 0.20f))
                )

                // Minimize icon
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .clickable { onDismiss() }
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.overlay_hide_subtitles),
                        tint = TextSecondary,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }

            // Subtitle Content (Dual display if available)
            if (liveSubtitleText.isNotEmpty() || (showDualSubtitles && liveSourceSubtitleText.isNotEmpty())) {
                if (showDualSubtitles && liveSourceSubtitleText.isNotEmpty()) {
                    Text(
                        text = liveSourceSubtitleText,
                        color = TextSecondary.copy(alpha = 0.85f),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        fontWeight = FontWeight.Normal,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 3.dp)
                    )
                }

                if (liveSubtitleText.isNotEmpty()) {
                    Text(
                        text = liveSubtitleText,
                        color = Color.White,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        style = TextStyle(
                            shadow = Shadow(
                                color = Color.Black.copy(alpha = 0.85f),
                                offset = Offset(1f, 1f),
                                blurRadius = 3f
                            )
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.subtitles_waiting),
                    color = TextSecondary.copy(alpha = listeningAlpha),
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
