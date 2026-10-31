package com.alad.app

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast

/**
 * Downloads signed releases and hands the APK to Android's package installer.
 * Android always owns the final installation confirmation on ordinary devices.
 */
object UpdateInstaller {
    private const val PREFS = "hs_update_install"
    private const val KEY_DOWNLOAD_ID = "download_id"
    private const val KEY_PENDING_URI = "pending_uri"

    fun enqueueDownload(context: Context, url: String, version: String): Long {
        val fileName = "HS-Voice-Translator-$version.apk"
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("HS Voice Translator")
            .setDescription("Baixando atualização $version")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)

        val manager = context.getSystemService(DownloadManager::class.java)
            ?: throw IllegalStateException("Gerenciador de downloads indisponível.")
        val id = manager.enqueue(request)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_DOWNLOAD_ID, id)
            .remove(KEY_PENDING_URI)
            .apply()
        Toast.makeText(context, "Atualização $version baixando. A instalação será aberta ao terminar.", Toast.LENGTH_LONG).show()
        return id
    }

    fun onDownloadCompleted(context: Context, downloadId: Long) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getLong(KEY_DOWNLOAD_ID, -1L) != downloadId) return

        val manager = context.getSystemService(DownloadManager::class.java) ?: return
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor: Cursor = manager.query(query) ?: return
        cursor.use {
            if (!it.moveToFirst()) return
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                val reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                Toast.makeText(context, "Falha ao baixar a atualização (código $reason).", Toast.LENGTH_LONG).show()
                return
            }
        }

        val apkUri = manager.getUriForDownloadedFile(downloadId) ?: run {
            Toast.makeText(context, "Download concluído, mas não foi possível abrir o APK.", Toast.LENGTH_LONG).show()
            return
        }
        prefs.edit().putString(KEY_PENDING_URI, apkUri.toString()).apply()
        installPending(context)
    }

    fun installPending(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val uriText = prefs.getString(KEY_PENDING_URI, null) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(settingsIntent) }
            Toast.makeText(context, "Permita instalar apps desta fonte. Ao voltar, a instalação continuará.", Toast.LENGTH_LONG).show()
            return
        }

        val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = Uri.parse(uriText)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_RETURN_RESULT, false)
        }
        runCatching {
            context.startActivity(installIntent)
            prefs.edit().remove(KEY_PENDING_URI).apply()
        }.onFailure {
            Toast.makeText(context, "Não foi possível abrir o instalador: ${it.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }
}

class UpdateDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (id >= 0L) UpdateInstaller.onDownloadCompleted(context, id)
    }
}
