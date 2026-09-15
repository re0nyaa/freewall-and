package com.renyaa.freewall_and

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseInfo(
    val tagName: String,
    val versionName: String,
    val releaseNotes: String,
    val apkDownloadUrl: String?,
    val htmlUrl: String
)

class UpdateManager(private val context: Context) {

    companion object {
        private const val GITHUB_REPO = "re0nyaa/freewall-and"
        private const val API_LATEST_RELEASE = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
    }

    /**
     * GitHub 최신 릴리즈 정보 확인
     */
    suspend fun checkLatestRelease(): ReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            val url = URL(API_LATEST_RELEASE)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                connectTimeout = 8000
                readTimeout = 8000
            }

            if (conn.responseCode != 200) {
                return@withContext null
            }

            val response = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)

            val tagName = json.getString("tag_name")
            val versionName = tagName.removePrefix("v").removePrefix("V")
            val body = json.optString("body", "최신 버전이 출시되었습니다.")
            val htmlUrl = json.getString("html_url")

            var apkUrl: String? = null
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.getString("name")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.getString("browser_download_url")
                        break
                    }
                }
            }

            ReleaseInfo(
                tagName = tagName,
                versionName = versionName,
                releaseNotes = body,
                apkDownloadUrl = apkUrl,
                htmlUrl = htmlUrl
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 신규 버전 여부 비교 (예: current "1.0", remote "1.1")
     */
    fun isUpdateAvailable(currentVersion: String, remoteVersion: String): Boolean {
        try {
            val currParts = currentVersion.split(".").map { it.toIntOrNull() ?: 0 }
            val remoteParts = remoteVersion.split(".").map { it.toIntOrNull() ?: 0 }

            val maxLen = maxOf(currParts.size, remoteParts.size)
            for (i in 0 until maxLen) {
                val c = currParts.getOrElse(i) { 0 }
                val r = remoteParts.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }
        } catch (_: Exception) {}
        return false
    }

    /**
     * APK 파일 다운로드
     */
    suspend fun downloadApk(downloadUrl: String, onProgress: (Int) -> Unit): File? = withContext(Dispatchers.IO) {
        try {
            val url = URL(downloadUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15000
                readTimeout = 15000
            }

            val contentLength = conn.contentLength
            val apkFile = File(context.cacheDir, "freewall_update.apk")
            if (apkFile.exists()) apkFile.delete()

            conn.inputStream.use { input ->
                FileOutputStream(apkFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalRead = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (contentLength > 0) {
                            val progress = ((totalRead * 100) / contentLength).toInt()
                            withContext(Dispatchers.Main) {
                                onProgress(progress)
                            }
                        }
                    }
                    output.flush()
                }
            }
            apkFile
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 다운로드한 APK 설치 패키지 인텐트 실행
     */
    fun installApk(apkFile: File) {
        val authority = "${context.packageName}.fileprovider"
        val apkUri: Uri = FileProvider.getUriForFile(context, authority, apkFile)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
