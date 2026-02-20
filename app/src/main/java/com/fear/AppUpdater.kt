package com.fear

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class AppUpdater(private val context: Context) {

    data class UpdateInfo(
        val latestVersion: String,
        val downloadUrl: String,
        val hasUpdate: Boolean
    )

    suspend fun checkForUpdate(): UpdateInfo = withContext(Dispatchers.IO) {
        val url = URL("https://api.github.com/repos/shchuchkin-pkims/fear-mobile/releases/latest")
        val conn = url.openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000

        try {
            val json = conn.inputStream.bufferedReader().readText()
            val release = JSONObject(json)
            val tagName = release.getString("tag_name").removePrefix("v")
            val assets = release.getJSONArray("assets")

            var apkUrl = ""
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.getString("name")
                if (name.endsWith(".apk")) {
                    apkUrl = asset.getString("browser_download_url")
                    break
                }
            }

            val currentVersion = BuildConfig.VERSION_NAME
            val hasUpdate = compareVersions(tagName, currentVersion) > 0

            UpdateInfo(tagName, apkUrl, hasUpdate)
        } finally {
            conn.disconnect()
        }
    }

    suspend fun downloadApk(
        downloadUrl: String,
        onProgress: (Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val apkFile = File(context.cacheDir, "fear-update.apk")
        if (apkFile.exists()) apkFile.delete()

        val url = URL(downloadUrl)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000

        try {
            val totalSize = conn.contentLength
            val input = conn.inputStream
            val output = apkFile.outputStream()

            val buffer = ByteArray(8192)
            var downloaded = 0L
            var bytesRead: Int

            while (input.read(buffer).also { bytesRead = it } != -1) {
                output.write(buffer, 0, bytesRead)
                downloaded += bytesRead
                if (totalSize > 0) {
                    onProgress(((downloaded * 100) / totalSize).toInt())
                }
            }

            output.flush()
            output.close()
            input.close()
            apkFile
        } finally {
            conn.disconnect()
        }
    }

    fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                val settingsIntent = Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    android.net.Uri.parse("package:${context.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(settingsIntent)
                return
            }
        }

        context.startActivity(intent)
    }

    companion object {
        fun compareVersions(v1: String, v2: String): Int {
            val parts1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
            val parts2 = v2.split(".").map { it.toIntOrNull() ?: 0 }
            val maxLen = maxOf(parts1.size, parts2.size)
            for (i in 0 until maxLen) {
                val p1 = parts1.getOrElse(i) { 0 }
                val p2 = parts2.getOrElse(i) { 0 }
                if (p1 != p2) return p1.compareTo(p2)
            }
            return 0
        }
    }
}
