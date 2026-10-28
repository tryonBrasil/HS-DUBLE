package com.alad.app.presentation.main

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val RELEASES_API = "https://api.github.com/repos/tryonBrasil/HS-DUBLE/releases/latest"
private const val APK_MIME = "application/vnd.android.package-archive"

private data class AppRelease(val version: String, val apkUrl: String)

@Composable
fun UpdateAppCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isChecking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Confira se existe uma nova versão do HS Voice Translator.") }
    var pendingRelease by remember { mutableStateOf<AppRelease?>(null) }

    if (pendingRelease != null) {
        AlertDialog(
            onDismissRequest = { pendingRelease = null },
            title = { Text("Atualização disponível") },
            text = {
                Text("A versão ${pendingRelease!!.version} está disponível. Deseja baixar e instalar agora? O Android manterá os dados do aplicativo se a assinatura for a mesma.")
            },
            confirmButton = {
                TextButton(onClick = {
                    val release = pendingRelease ?: return@TextButton
                    pendingRelease = null
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
                        !context.packageManager.canRequestPackageInstalls()
                    ) {
                        status = "Permita instalar atualizações desta fonte e toque novamente."
                        val settingsIntent = Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${context.packageName}")
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(settingsIntent)
                    } else {
                        startApkDownload(context, release.apkUrl) { message -> status = message }
                    }
                }) { Text("Baixar e instalar") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRelease = null }) { Text("Agora não") }
            }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Atualização do aplicativo", style = MaterialTheme.typography.titleMedium)
            Text(status, style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = {
                    if (isChecking) return@Button
                    isChecking = true
                    status = "Verificando a versão mais recente..."
                    scope.launch {
                        try {
                            val release = withContext(Dispatchers.IO) { fetchLatestRelease() }
                            val current = context.packageManager
                                .getPackageInfo(context.packageName, 0).versionName.orEmpty()
                            if (compareVersions(release.version, current) > 0) {
                                pendingRelease = release
                                status = "Nova versão encontrada: ${release.version}."
                            } else {
                                status = "Seu aplicativo já está atualizado (versão $current)."
                            }
                        } catch (error: Exception) {
                            status = error.message ?: "Não foi possível verificar atualizações. Tente novamente."
                        } finally {
                            isChecking = false
                        }
                    }
                },
                enabled = !isChecking,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isChecking) "Verificando..." else "Verificar atualizações")
            }
        }
    }
}

private fun fetchLatestRelease(): AppRelease {
    val connection = (URL(RELEASES_API).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 10000
        readTimeout = 10000
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("User-Agent", "HS-Voice-Translator-Updater")
    }
    try {
        if (connection.responseCode !in 200..299) {
            throw IllegalStateException(
                if (connection.responseCode == 404) "Ainda não há uma versão publicada para atualização."
                else "Não foi possível consultar as atualizações (HTTP ${connection.responseCode})."
            )
        }
        val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        val version = json.optString("tag_name").removePrefix("v").removePrefix("V")
        val assets = json.optJSONArray("assets")
            ?: throw IllegalStateException("A publicação não contém o APK.")
        var apkUrl: String? = null
        for (index in 0 until assets.length()) {
            val asset = assets.getJSONObject(index)
            if (asset.optString("name").equals("hs-duble.apk", ignoreCase = true)) {
                apkUrl = asset.optString("browser_download_url")
                break
            }
        }
        if (version.isBlank() || apkUrl.isNullOrBlank()) {
            throw IllegalStateException("A versão publicada ainda não contém o arquivo hs-duble.apk.")
        }
        return AppRelease(version, apkUrl)
    } finally {
        connection.disconnect()
    }
}

private fun compareVersions(left: String, right: String): Int {
    val a = left.split(".", "-", "+").map { it.toIntOrNull() ?: 0 }
    val b = right.split(".", "-", "+").map { it.toIntOrNull() ?: 0 }
    for (index in 0 until maxOf(a.size, b.size)) {
        val result = (a.getOrElse(index) { 0 }).compareTo(b.getOrElse(index) { 0 })
        if (result != 0) return result
    }
    return 0
}

private fun startApkDownload(context: Context, apkUrl: String, updateStatus: (String) -> Unit) {
    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    val request = DownloadManager.Request(Uri.parse(apkUrl))
        .setTitle("Atualização do HS Voice Translator")
        .setDescription("Baixando o APK da nova versão")
        .setMimeType(APK_MIME)
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setAllowedOverMetered(true)
        .setAllowedOverRoaming(false)
        .setDestinationInExternalFilesDir(
            context,
            Environment.DIRECTORY_DOWNLOADS,
            "hs-duble-update.apk"
        )

    var downloadId = -1L
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            if (intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != downloadId) return
            try {
                context.unregisterReceiver(this)
            } catch (_: Exception) {
                // The receiver may already have been unregistered by Android.
            }

            val uri = manager.getUriForDownloadedFile(downloadId)
            if (uri == null) {
                updateStatus("O download falhou. Verifique a internet e tente novamente.")
                Toast.makeText(context, "Falha ao baixar a atualização.", Toast.LENGTH_LONG).show()
                return
            }

            try {
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, APK_MIME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(installIntent)
                updateStatus("Download concluído. Siga as instruções do Android para instalar.")
            } catch (_: Exception) {
                updateStatus("APK baixado. Abra a notificação de download para instalar.")
            }
        }
    }

    ContextCompat.registerReceiver(
        context,
        receiver,
        IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
        ContextCompat.RECEIVER_NOT_EXPORTED
    )
    try {
        downloadId = manager.enqueue(request)
        updateStatus("Download iniciado. Você será avisado quando estiver pronto.")
    } catch (error: Exception) {
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {
            // Ignore cleanup errors.
        }
        updateStatus(error.message ?: "Não foi possível iniciar o download.")
    }
}
