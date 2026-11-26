package com.jarvis.assistant.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.jarvis.assistant.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class ReleaseInfo(
    val versionTag: String,
    val apkUrl: String,
    val notes: String,
    val isNewer: Boolean
)

/**
 * Checks GitHub Releases for a newer ANNI-AI.apk and triggers in-place Android update.
 */
object AppUpdater {

    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/bhaveshbisani222-bit/anni-android/releases/latest"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun checkLatestRelease(): ReleaseInfo = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(LATEST_RELEASE_API)
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "ANNI-Android-Updater")
            .build()

        client.newCall(request).execute().use { res ->
            if (!res.isSuccessful) {
                throw IllegalStateException("GitHub check failed (HTTP ${res.code})")
            }
            val json = JSONObject(res.body?.string().orEmpty())
            val tag = json.optString("tag_name", "").trim()
            val notes = json.optString("body", "")
            val assets = json.optJSONArray("assets")
            var apkUrl = ""
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.optString("browser_download_url", "")
                        break
                    }
                }
            }
            if (apkUrl.isBlank()) {
                throw IllegalStateException("Release mein APK file nahi mili.")
            }

            val remoteVer = tag.removePrefix("v").trim()
            val localVer = BuildConfig.VERSION_NAME.removePrefix("v").trim()
            val newer = isRemoteNewer(remoteVer, localVer)

            ReleaseInfo(
                versionTag = tag.ifBlank { "v$remoteVer" },
                apkUrl = apkUrl,
                notes = notes,
                isNewer = newer
            )
        }
    }

    suspend fun downloadAndInstallApk(
        context: Context,
        apkUrl: String,
        onProgress: (Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val outDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val outFile = File(outDir, "ANNI-AI-update.apk")
        if (outFile.exists()) outFile.delete()

        val request = Request.Builder()
            .url(apkUrl)
            .header("User-Agent", "ANNI-Android-Updater")
            .build()

        client.newCall(request).execute().use { res ->
            if (!res.isSuccessful) {
                throw IllegalStateException("Download failed (HTTP ${res.code})")
            }
            val body = res.body ?: throw IllegalStateException("Empty APK response")
            val totalBytes = body.contentLength()
            var downloaded = 0L

            body.byteStream().use { input ->
                FileOutputStream(outFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var read: Int
                    var lastPct = -1
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (totalBytes > 0) {
                            val pct = ((downloaded * 100) / totalBytes).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                withContext(Dispatchers.Main) { onProgress(pct) }
                            }
                        }
                    }
                }
            }
        }
        outFile
    }

    fun launchApkInstaller(context: Context, apkFile: File): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            return try {
                val permIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(permIntent)
                "Permission chahiye: 'Install unknown apps' ON karein, phir dubara Install Update dabayein!"
            } catch (e: Exception) {
                "Settings → Apps → A.N.N.I. AI → Install unknown apps allow karein."
            }
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        return try {
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(installIntent)
            "Installer khul gaya hai — Update pe tap karein!"
        } catch (e: Exception) {
            try {
                @Suppress("DEPRECATION")
                val fallback = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                    data = uri
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(fallback)
                "Installer khul gaya hai — Update pe tap karein!"
            } catch (e2: Exception) {
                "Installer error: ${e2.message}. Chrome se download karke install karein."
            }
        }
    }

    private fun isRemoteNewer(remote: String, local: String): Boolean {
        val rParts = remote.split(".").mapNotNull { it.toIntOrNull() }
        val lParts = local.split(".").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(rParts.size, lParts.size)
        for (i in 0 until maxLen) {
            val r = rParts.getOrElse(i) { 0 }
            val l = lParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }
}
