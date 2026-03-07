package com.iptvplayer.tv.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

@Serializable
data class UpdateInfo(
    @SerialName("versionCode") val versionCode: Int,
    @SerialName("versionName") val versionName: String,
    @SerialName("downloadUrl") val downloadUrl: String,
    @SerialName("releaseNotes") val releaseNotes: String = ""
)

class UpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "UpdateManager"
        const val VERSION_CHECK_URL = "https://raw.githubusercontent.com/django1m/IPTV-RELEASE/main/version.json"
        private const val APK_FILE_NAME = "iptv-player-update.apk"
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * Check if an update is available.
     * Returns UpdateInfo if a newer version exists, null otherwise.
     */
    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Checking for update at: $VERSION_CHECK_URL")
            val request = Request.Builder()
                .url(VERSION_CHECK_URL)
                .header("Cache-Control", "no-cache, no-store")
                .build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.e(TAG, "Update check failed: HTTP ${response.code}")
                return@withContext null
            }

            val body = response.body?.string() ?: return@withContext null
            Log.d(TAG, "Response: $body")
            val updateInfo = json.decodeFromString<UpdateInfo>(body)

            val currentVersionCode = getCurrentVersionCode()
            Log.d(TAG, "Current: $currentVersionCode, Remote: ${updateInfo.versionCode}")
            if (updateInfo.versionCode > currentVersionCode) {
                Log.d(TAG, "Update available: ${updateInfo.versionName}")
                updateInfo
            } else {
                Log.d(TAG, "App is up to date")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Update check error", e)
            null
        }
    }

    /**
     * Check if the app has permission to install APKs.
     */
    fun canInstallPackages(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * Open system settings to allow installing from this app.
     */
    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }

    /**
     * Download the APK using OkHttp and install when complete.
     * Uses coroutines instead of DownloadManager to avoid BroadcastReceiver issues.
     */
    suspend fun downloadAndInstall(
        updateInfo: UpdateInfo,
        onProgress: ((Int) -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
        onDownloadComplete: (() -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        try {
            // Clean up old APK if exists
            val apkFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK_FILE_NAME)
            if (apkFile.exists()) apkFile.delete()

            Log.d(TAG, "Downloading APK from: ${updateInfo.downloadUrl}")

            val request = Request.Builder()
                .url(updateInfo.downloadUrl)
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorMsg = "Erreur de telechargement: HTTP ${response.code}"
                Log.e(TAG, errorMsg)
                withContext(Dispatchers.Main) { onError?.invoke(errorMsg) }
                return@withContext
            }

            val body = response.body
            if (body == null) {
                val errorMsg = "Reponse vide du serveur"
                Log.e(TAG, errorMsg)
                withContext(Dispatchers.Main) { onError?.invoke(errorMsg) }
                return@withContext
            }

            val contentLength = body.contentLength()
            Log.d(TAG, "APK size: $contentLength bytes")

            // Download with progress
            body.byteStream().use { input ->
                FileOutputStream(apkFile).use { output ->
                    val buffer = ByteArray(8192)
                    var totalRead = 0L
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead

                        if (contentLength > 0) {
                            val progress = ((totalRead * 100) / contentLength).toInt()
                            withContext(Dispatchers.Main) { onProgress?.invoke(progress) }
                        }
                    }
                    output.flush()
                }
            }

            Log.d(TAG, "Download complete: ${apkFile.absolutePath} (${apkFile.length()} bytes)")
            withContext(Dispatchers.Main) {
                onDownloadComplete?.invoke()
                installApk(apkFile)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Download error", e)
            val errorMsg = "Erreur: ${e.message}"
            withContext(Dispatchers.Main) { onError?.invoke(errorMsg) }
        }
    }

    private fun installApk(apkFile: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            Log.d(TAG, "Installing APK from URI: $uri")

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Install error", e)
        }
    }

    private fun getCurrentVersionCode(): Int {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
        } catch (e: Exception) {
            0
        }
    }

    fun getCurrentVersionName(): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }
}
