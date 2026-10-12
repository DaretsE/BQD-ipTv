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
import org.json.JSONArray
import java.io.File

/**
 * Проверка обновлений, скачивание APK, установка и откат на предыдущую версию.
 *
 * История бага (исправлено): при обновлении «на ходу», без перезапуска приложения,
 * прогресс доходил до 100% и дальше ничего не происходило. Причин было три:
 *  1) диалог загрузки не закрывался при успешном завершении — он оставался
 *     висеть на 100%, создавая впечатление, что установка не началась;
 *  2) сбой запуска установщика молча проглатывался пустым catch — пользователь
 *     не видел причины;
 *  3) разрешение на установку проверялось только ПОСЛЕ полной загрузки.
 * Дополнительно теперь проверяется, что скачан действительно APK (а не страница
 * с ошибкой при неудачном редиректе), и редиректы обрабатываются вручную,
 * включая переход между http и https, который HttpURLConnection сам не проходит.
 */
object UpdateManager {

    data class Release(val versionCode: Int, val versionName: String, val apkUrl: String)

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

    /** Последние три доступные сборки старше установленной. Старые релизы ищем через GitHub API. */
    fun fetchRollbackVersions(repo: String, currentCode: Int): List<Release> {
        val found = LinkedHashMap<Int, Release>()
        val base = repo.trim().trimEnd('/')
        try {
            val root = JSONObject(Net.downloadText("$base/releases/latest/download/version.json", 256 * 1024))
            fun add(o: JSONObject?) {
                if (o == null) return
                val code = o.optInt("versionCode")
                val name = o.optString("versionName")
                val url = o.optString("apkUrl")
                if (code > 0 && code < currentCode && name.isNotBlank() && url.startsWith("https://"))
                    found[code] = Release(code, name, url)
            }
            add(root)
            val history = root.optJSONArray("history")
            if (history != null) for (i in 0 until history.length()) add(history.optJSONObject(i))
            add(root.optJSONObject("previous"))
            // Старые version.json образуют цепочку через previous.
            var previous = root.optJSONObject("previous")
            var hops = 0
            while (found.size < 3 && previous != null && hops++ < 8) {
                val code = previous.optInt("versionCode")
                if (code <= 0) break
                previous = try {
                    val old = JSONObject(Net.downloadText("$base/releases/download/v$code/version.json", 256 * 1024))
                    val next = old.optJSONObject("previous")
                    add(next)
                    next
                } catch (_: Exception) { null }
            }
        } catch (_: Exception) { }

        // До появления history в version.json релизы содержали только поле previous.
        if (found.size < 3) {
            try {
                val path = java.net.URI(base).path.trim('/').split('/')
                if (java.net.URI(base).host.equals("github.com", ignoreCase = true) && path.size == 2) {
                    val api = "https://api.github.com/repos/${path[0]}/${path[1]}/releases?per_page=30"
                    val releases = JSONArray(Net.downloadText(api, 512 * 1024))
                    for (i in 0 until releases.length()) {
                        val release = releases.optJSONObject(i) ?: continue
                        if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
                        val code = release.optString("tag_name").removePrefix("v").toIntOrNull() ?: continue
                        if (code >= currentCode || code <= 0 || found.containsKey(code)) continue
                        val assets = release.optJSONArray("assets") ?: continue
                        for (j in 0 until assets.length()) {
                            val asset = assets.optJSONObject(j) ?: continue
                            if (!asset.optString("name").endsWith(".apk", ignoreCase = true)) continue
                            val url = asset.optString("browser_download_url")
                            if (!url.startsWith("https://")) continue
                            val title = release.optString("name").removePrefix("BQDiptv").trim()
                            val name = title.takeIf { it.matches(Regex("[0-9]+(\\.[0-9]+)+")) } ?: "2.0.$code"
                            found[code] = Release(code, name, url)
                            break
                        }
                    }
                }
            } catch (_: Exception) { }
        }
        return found.values.sortedByDescending { it.versionCode }.take(3)
    }

    fun checkAsync(ctx: Context, repo: String, onResult: (Info?, Int) -> Unit) {
        Thread {
            val info = fetchInfo(repo)
            val cur = currentCode(ctx)
            main.post { onResult(info, cur) }
        }.start()
    }

    /** Есть ли разрешение на установку пакетов (Android 8+). */
    fun canInstall(act: Activity): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || act.packageManager.canRequestPackageInstalls()

    /**
     * Открывает системный экран выдачи разрешения на установку.
     * Возвращает false, если такого экрана на устройстве нет (бывает на ТВ-приставках).
     */
    fun openInstallPermission(act: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return try {
            act.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + act.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (_: Exception) {
            // на части ТВ-прошивок экрана нет — пробуем общий список настроек безопасности
            try {
                act.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (_: Exception) { false }
        }
    }

    private fun updateFile(act: Context): File = File(File(act.filesDir, "update"), "BQDiptv.apk")

    /** Рядом с APK храним versionCode, для которого он скачан. */
    private fun stampFile(act: Context): File = File(File(act.filesDir, "update"), "version.txt")

    private fun writeStamp(act: Context, versionCode: Int) {
        try { stampFile(act).writeText(versionCode.toString()) } catch (_: Exception) { }
    }

    private fun readStamp(act: Context): Int = try {
        stampFile(act).readText().trim().toInt()
    } catch (_: Exception) { 0 }

    /**
     * Скачан ли готовый к установке файл ИМЕННО той версии, которую ставим.
     * Проверка версии обязательна: APK от прошлого обновления остаётся на диске,
     * и без сверки мы бы установили старую версию вместо новой.
     */
    fun hasDownloaded(act: Context, versionCode: Int): Boolean {
        val f = updateFile(act)
        if (!f.exists() || f.length() == 0L || !isApk(f)) return false
        return versionCode > 0 && readStamp(act) == versionCode
    }

    /** Удалить скачанный файл (например, после успешной установки). */
    fun clearDownloaded(act: Context) {
        try { updateFile(act).delete(); stampFile(act).delete() } catch (_: Exception) { }
    }

    /** Проверка, что скачан действительно APK (ZIP-архив), а не HTML с ошибкой. */
    private fun isApk(f: File): Boolean = try {
        f.inputStream().use { ins ->
            val b = ByteArray(2)
            ins.read(b) == 2 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()
        }
    } catch (_: Exception) { false }

    /**
     * Скачивает APK и по завершении запускает установку.
     * onDone вызывается в любом случае — успех или ошибка, чтобы вызывающая
     * сторона гарантированно закрыла диалог прогресса.
     */
    fun downloadAndInstall(
        act: Activity,
        apkUrl: String,
        versionCode: Int,
        onError: (String) -> Unit,
        onProgress: (Int) -> Unit,
        onNeedPermission: () -> Unit,
        onDone: () -> Unit
    ) {
        if (apkUrl.isEmpty()) { onError("Нет ссылки на файл обновления"); onDone(); return }

        // разрешение спрашиваем ДО загрузки, чтобы не упереться в него на 100%
        if (!canInstall(act)) {
            onDone()
            onNeedPermission()
            return
        }

        Thread {
            try {
                val dir = File(act.filesDir, "update").apply { mkdirs() }
                val apk = File(dir, "BQDiptv.apk")
                if (apk.exists()) apk.delete()

                var url = apkUrl
                var conn: java.net.HttpURLConnection? = null
                var redirects = 0
                while (true) {
                    conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection)
                    conn.connectTimeout = 20000
                    conn.readTimeout = 60000
                    conn.instanceFollowRedirects = false
                    conn.setRequestProperty("User-Agent", "BQDiptv")
                    conn.setRequestProperty("Accept", "*/*")
                    val code = conn.responseCode
                    if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                        val loc = conn.getHeaderField("Location")
                        conn.disconnect()
                        if (loc.isNullOrEmpty() || ++redirects > 5) throw Exception("Слишком много переадресаций")
                        url = java.net.URL(java.net.URL(url), loc).toString()
                        continue
                    }
                    if (code !in 200..299) throw Exception("Сервер ответил $code")
                    break
                }

                val c = conn ?: throw Exception("Нет соединения")
                val total = c.contentLength
                c.inputStream.use { ins ->
                    apk.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16); var read: Int; var done = 0L; var lastPct = -1
                        while (ins.read(buf).also { read = it } != -1) {
                            out.write(buf, 0, read); done += read
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct) { lastPct = pct; main.post { onProgress(pct) } }
                            }
                        }
                    }
                }
                c.disconnect()

                if (!apk.exists() || apk.length() == 0L) throw Exception("Файл обновления пуст")
                if (!isApk(apk)) throw Exception("Скачан не APK — проверьте ссылку в релизе")
                writeStamp(act, versionCode)

                main.post {
                    onDone()
                    install(act, apk, onError, onNeedPermission)
                }
            } catch (e: Exception) {
                try { stampFile(act).delete() } catch (_: Exception) { }
                main.post { onDone(); onError(e.message ?: "Ошибка скачивания") }
            }
        }.start()
    }

    /** Установка уже скачанного APK (после выдачи разрешения или при повторном входе). */
    fun installDownloaded(act: Activity, onError: (String) -> Unit) {
        val apk = updateFile(act)
        if (!apk.exists() || apk.length() == 0L) { onError("Файл обновления не найден — скачайте заново"); return }
        if (!isApk(apk)) { apk.delete(); onError("Файл обновления повреждён — скачайте заново"); return }
        install(act, apk, onError) { onError("Разрешите установку из этого приложения и повторите") }
    }

    /**
     * Запуск установки. Никаких «молчаливых» сбоев: любая проблема
     * возвращается через onError и показывается пользователю.
     */
    private fun install(act: Activity, apk: File, onError: (String) -> Unit, onNeedPermission: () -> Unit) {
        if (!canInstall(act)) { onNeedPermission(); return }
        try {
            val uri = FileProvider.getUriForFile(act, act.packageName + ".fileprovider", apk)
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(uri, "application/vnd.android.package-archive")
            i.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
            // на части прошивок нужно явно выдать доступ установщику
            try {
                val resolved = act.packageManager.queryIntentActivities(i, 0)
                for (r in resolved) {
                    act.grantUriPermission(r.activityInfo.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } catch (_: Exception) { }

            if (act.packageManager.resolveActivity(i, 0) == null) {
                onError("На устройстве нет установщика пакетов")
                return
            }
            act.startActivity(i)
        } catch (e: Exception) {
            onError("Не удалось запустить установку: ${e.message ?: "неизвестная ошибка"}")
        }
    }
}
