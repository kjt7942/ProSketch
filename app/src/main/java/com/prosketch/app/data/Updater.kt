package com.prosketch.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A published GitHub release that carries an installable APK. */
data class ReleaseInfo(val version: String, val notes: String, val apkUrl: String, val apkSize: Long, val pageUrl: String)

/**
 * Self-update through GitHub Releases: reads the latest release of [REPO], downloads its APK asset
 * and hands it to the system package installer. Releases are signed with the same key, so the
 * installer updates in place and keeps all artworks.
 */
object Updater {
    const val REPO = "kjt7942/ProSketch"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases/latest"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"

    fun currentVersion(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "0"

    /** "v1.2.3" / "1.2" → [1, 2, 3] / [1, 2, 0] for numeric comparison. */
    private fun parts(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V").split('.', '-', '+').take(3).map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
            .let { it + List(3 - it.size) { 0 } }

    fun isNewer(remote: String, local: String): Boolean {
        val r = parts(remote); val l = parts(local)
        for (i in 0 until 3) if (r[i] != l[i]) return r[i] > l[i]
        return false
    }

    /** Latest release, or null when there is none or it has no APK. Throws on network errors. */
    suspend fun fetchLatest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val conn = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "ProSketch-Updater")
        }
        try {
            when (conn.responseCode) {
                404 -> return@withContext null
                in 200..299 -> {}
                else -> error("GitHub 응답 오류 ${conn.responseCode}")
            }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val assets = json.optJSONArray("assets") ?: return@withContext null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                    return@withContext ReleaseInfo(
                        version = json.optString("tag_name"),
                        notes = json.optString("body").trim(),
                        apkUrl = a.getString("browser_download_url"),
                        apkSize = a.optLong("size"),
                        pageUrl = json.optString("html_url", RELEASES_PAGE),
                    )
                }
            }
            null
        } finally { conn.disconnect() }
    }

    /** Downloads the release APK into the cache, reporting progress in 0..1. */
    suspend fun download(ctx: Context, rel: ReleaseInfo, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val out = File(dir, "ProSketch-${rel.version}.apk")
        var url = URL(rel.apkUrl)
        // GitHub answers with a redirect to its CDN; follow it manually so https→https hops always work
        repeat(5) {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = false
                setRequestProperty("User-Agent", "ProSketch-Updater")
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) { url = URL(url, conn.getHeaderField("Location")); return@repeat }
                if (code !in 200..299) error("다운로드 실패 ($code)")
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: rel.apkSize
                conn.inputStream.use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            output.write(buf, 0, n); done += n
                            if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
                if (rel.apkSize > 0 && out.length() != rel.apkSize) error("다운로드가 완전하지 않습니다")
                return@withContext out
            } finally { conn.disconnect() }
        }
        error("리디렉션이 너무 많습니다")
    }

    /** True when the user still has to allow "install unknown apps" for ProSketch. */
    fun needsInstallPermission(ctx: Context) =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(ctx: Context) {
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
