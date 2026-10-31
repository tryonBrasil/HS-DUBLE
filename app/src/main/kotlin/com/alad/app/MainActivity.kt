package com.alad.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.alad.app.core.service.AudioDubbingForegroundService
import com.alad.app.data.repository.UserPreferencesRepository
import com.alad.app.presentation.main.AboutScreen
import com.alad.app.presentation.main.MainScreen
import com.alad.app.presentation.main.MainViewModel
import com.alad.app.presentation.main.MainViewModelFactory
import com.alad.app.presentation.settings.SettingsScreen
import com.alad.app.presentation.settings.SettingsViewModel
import com.alad.app.presentation.settings.SettingsViewModelFactory
import java.io.File

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_OPEN_SETTINGS = "com.hstech.voicetranslator.OPEN_SETTINGS"
        @Volatile private var crashHandlerInstalled = false
    }

    private lateinit var mainViewModel: MainViewModel
    private val requestedScreen = mutableStateOf("MAIN")

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startDubbingService(result.resultCode, result.data!!)
            mainViewModel.updateStatus(getString(R.string.status_connected_short))
        } else {
            Toast.makeText(this, getString(R.string.screen_capture_permission_denied), Toast.LENGTH_SHORT).show()
            mainViewModel.updateStatus(getString(R.string.status_disconnected_short))
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        if (audioGranted) {
            launchScreenCapture()
        } else {
            Toast.makeText(this, getString(R.string.audio_permission_required), Toast.LENGTH_SHORT).show()
            mainViewModel.updateStatus("Disconnected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashDiagnostics()
        requestedScreen.value = if (intent?.getBooleanExtra(EXTRA_OPEN_SETTINGS, false) == true) "SETTINGS" else "MAIN"

        val repository = UserPreferencesRepository(applicationContext)
        val previousCrash = runCatching {
            File(filesDir, "last_crash.txt").takeIf { it.exists() }?.readText().orEmpty()
        }.getOrDefault("")

        setContent {
            com.alad.app.ui.theme.ALADTheme {
                var currentScreen by requestedScreen
                var showCrashReport by remember { mutableStateOf(previousCrash.isNotBlank()) }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    when (currentScreen) {
                        "MAIN" -> {
                            mainViewModel = viewModel(factory = MainViewModelFactory(repository))
                            MainScreen(
                                viewModel = mainViewModel,
                                onNavigateToSettings = { currentScreen = "SETTINGS" },
                                onNavigateToAbout = { currentScreen = "ABOUT" },
                                onConnectClicked = { checkPermissionsAndConnect() },
                                onDisconnectClicked = { stopDubbingService() }
                            )
                        }
                        "SETTINGS" -> {
                            val settingsViewModel: SettingsViewModel = viewModel(
                                key = "hs_settings_view_model",
                                factory = SettingsViewModelFactory(repository)
                            )
                            SettingsScreen(
                                viewModel = settingsViewModel,
                                onBack = { currentScreen = "MAIN" },
                                onNavigateToAbout = { currentScreen = "ABOUT" }
                            )
                        }
                        "ABOUT" -> AboutScreen(
                            onBack = { currentScreen = "MAIN" },
                            onNavigateToSettings = { currentScreen = "SETTINGS" }
                        )
                    }

                    if (showCrashReport) {
                        AlertDialog(
                            onDismissRequest = { showCrashReport = false },
                            title = { Text("Diagnóstico de falha") },
                            text = { Text("O aplicativo registrou uma falha na execução anterior. Você pode compartilhar o relatório para identificar e corrigir a causa. Nenhuma configuração ou dado do aplicativo será apagado.") },
                            confirmButton = {
                                TextButton(onClick = {
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_SUBJECT, "HS Voice Translator - relatório de falha")
                                        putExtra(Intent.EXTRA_TEXT, previousCrash)
                                    }
                                    startActivity(Intent.createChooser(shareIntent, "Compartilhar diagnóstico"))
                                    showCrashReport = false
                                    File(filesDir, "last_crash.txt").delete()
                                }) { Text("Compartilhar relatório") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showCrashReport = false }) { Text("Agora não") }
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) {
            requestedScreen.value = "SETTINGS"
        }
    }

    @Synchronized
    private fun installCrashDiagnostics() {
        if (crashHandlerInstalled) return
        crashHandlerInstalled = true
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                File(filesDir, "last_crash.txt").writeText(
                    buildString {
                        appendLine("HS Voice Translator crash report")
                        appendLine("Package: $packageName")
                        appendLine("Thread: ${thread.name}")
                        appendLine("Time: ${System.currentTimeMillis()}")
                        appendLine()
                        append(throwable.stackTraceToString())
                    }
                )
            }
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    private fun checkPermissionsAndConnect() {
        val permissionsToRequest = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missingPermissions = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isEmpty()) {
            launchScreenCapture()
        } else {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun launchScreenCapture() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun startDubbingService(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(this, AudioDubbingForegroundService::class.java).apply {
            action = AudioDubbingForegroundService.ACTION_START
            putExtra(AudioDubbingForegroundService.EXTRA_RESULT_CODE, resultCode)
            putExtra(AudioDubbingForegroundService.EXTRA_RESULT_DATA, data)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun stopDubbingService() {
        val serviceIntent = Intent(this, AudioDubbingForegroundService::class.java).apply {
            action = AudioDubbingForegroundService.ACTION_STOP
        }
        startService(serviceIntent)
        mainViewModel.updateStatus("Disconnected")
    }
}
