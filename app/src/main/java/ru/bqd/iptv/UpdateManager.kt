package ru.bqd.iptv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File

/** Проверка обновлений, скачивание APK, установка и откат на предыдущую версию. */
object UpdateManager {

    data class Info(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val notes: String,
        val prevCode: Int,
        val prevName: String,
        val prevUrl: String
    )

    private val main = Handler(Looper.getMainLooper())

    fun currentCode(ctx: Context): Int = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
    } catch (_: Exception) { 0 }

    fun currentName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }

    /** Скачивает version.json из последнего релиза и возвращает Info (или null). */
    fun fetchInfo(repo: String): Info? {
        return try {
            val url = repo.trim().trimEnd('/') + "/releases/latest/download/version.json"
            val txt = Net.downloadText(url)
            val o = JSONObject(txt)
            val prev = o.optJSONObject("previous")
            Info(
                versionCode = o.optInt("versionCode", 0),
                versionName = o.optString("versionName", "?"),
                apkUrl = o.optString("apkUrl", ""),
                notes = o.optString("notes", ""),
                prevCode = prev?.optInt("versionCode", 0) ?: 0,
                prevName = prev?.optString("versionName", "") ?: "",
                prevUrl = prev?.optString("apkUrl", "") ?: ""
            )
        } catch (_: Exception) { null }
    }

    fun checkAsync(ctx: Context, repo: String, onResult: (Info?, Int) -> Unit) {
        Thread {
            val info = fetchInfo(repo)
            val cur = currentCode(ctx)
            main.post { onResult(info, cur) }
        }.start()
    }

    /** Скачивает APK по ссылке во внутреннюю папку и запускает установку. */
    fun downloadAndInstall(act: Activity, apkUrl: String, onError: (String) -> Unit, onProgress: (Int) -> Unit, onNeedPermission: () -> Unit) {
        if (apkUrl.isEmpty()) { onError("Нет ссылки на файл обновления"); return }
        Thread {
            try {
                val dir = File(act.filesDir, "update").apply { mkdirs() }
                val apk = File(dir, "BQDiptv.apk")
                if (apk.exists()) apk.delete()
                val conn = (java.net.URL(apkUrl).openConnection() as java.net.HttpURLConnection)
                conn.connectTimeout = 20000; conn.readTimeout = 60000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "BQDiptv")
                val total = conn.contentLength
                conn.inputStream.use { ins ->
                    apk.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16); var read: Int; var done = 0L
                        while (ins.read(buf).also { read = it } != -1) {
                            out.write(buf, 0, read); done += read
                            if (total > 0) main.post { onProgress((done * 100 / total).toInt()) }
                        }
                    }
                }
                main.post { install(act, apk, onError, onNeedPermission) }
            } catch (e: Exception) {
                main.post { onError(e.message ?: "Ошибка скачивания") }
            }
        }.start()
    }

    /** Установка уже скачанного APK (вызывается при возврате после выдачи разрешения). */
    fun installDownloaded(act: Activity, onError: (String) -> Unit) {
        val apk = File(File(act.filesDir, "update"), "BQDiptv.apk")
        if (!apk.exists() || apk.length() == 0L) { onError("Файл обновления не найден — скачайте заново"); return }
        install(act, apk, onError) { onError("Нужно разрешить установку из этого приложения") }
    }

    /** Есть ли разрешение на установку пакетов (для Android 8+). */
    fun canInstall(act: Activity): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || act.packageManager.canRequestPackageInstalls()

    private fun install(act: Activity, apk: File, onError: (String) -> Unit, onNeedPermission: () -> Unit) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !act.packageManager.canRequestPackageInstalls()) {
                // просим разрешение на установку из этого приложения
                try {
                    act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + act.packageName)))
                    onNeedPermission()
                    return
                } catch (_: Exception) {}
            }
            val uri = FileProvider.getUriForFile(act, act.packageName + ".fileprovider", apk)
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(uri, "application/vnd.android.package-archive")
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            act.startActivity(i)
        } catch (e: Exception) {
            onError(e.message ?: "Не удалось запустить установку")
        }
    }
}
