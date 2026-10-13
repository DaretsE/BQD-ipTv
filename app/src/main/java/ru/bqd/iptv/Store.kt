package ru.bqd.iptv

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

object Store {

    private lateinit var prefs: SharedPreferences
    private lateinit var cacheDir: File

    const val DEFAULT_REPO = "https://github.com/DaretsE/BQD-ipTv"

    fun init(c: Context) {
        prefs = c.getSharedPreferences("bqd_iptv", Context.MODE_PRIVATE)
        cacheDir = File(c.filesDir, "cache")
        cacheDir.mkdirs()
        EpgManager.cacheFile = File(cacheDir, "epg_cache.bin")
        installCrashGuard()
        migrate()
    }

    // При аварии можно удалить только незавершённый временный файл. Готовые
    // epg_cache.bin и .bak должны пережить сбой и установку новой версии APK.
    private var crashGuardInstalled = false
    private fun installCrashGuard() {
        if (crashGuardInstalled) return
        crashGuardInstalled = true
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                File(cacheDir, "epg_cache.bin.tmp").delete()
            } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }

    private fun migrate() {
        // Разовая нормализация после проверки настроек качества: прежнее меню
        // предлагало одинаковые «Авто»/«Максимум» и размеры буфера ниже 30 сек,
        // которые плеер всё равно превращал в 30. Сразу включаем понятный
        // безопасный набор; последующие изменения пользователя сохраняются.
        if (!prefs.getBoolean("quality_defaults_reviewed_v1", false)) {
            prefs.edit()
                .putString("quality", "auto")
                .putInt("buffer_sec", 30)
                .putInt("live_offset_sec", 0)
                .putString("retry_mode", "normal")
                .putString("decoder", "hw")
                .putBoolean("afr", false)
                .putBoolean("quality_defaults_reviewed_v1", true)
                .apply()
        }
        // старый одиночный EPG -> список источников
        val old = prefs.getString("epg_url", "") ?: ""
        if (old.isNotEmpty() && getEpgSources().isEmpty()) { addEpgSource("", old); prefs.edit().remove("epg_url").apply() }
        // старый список строк epg_sources -> объекты с именем
        val oldArr = prefs.getString("epg_sources", null)
        if (oldArr != null && getEpgSources().isEmpty()) {
            try {
                val a = JSONArray(oldArr)
                for (i in 0 until a.length()) addEpgSource("", a.getString(i))
            } catch (_: Exception) {}
            prefs.edit().remove("epg_sources").apply()
        }
    }

    fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    // ---------- Режим ----------
    var mode: String
        get() = prefs.getString("ui_mode", "") ?: ""
        set(v) { prefs.edit().putString("ui_mode", v).apply() }

    var setupDeferred: Boolean
        get() = prefs.getBoolean("setup_deferred", false)
        set(v) { prefs.edit().putBoolean("setup_deferred", v).apply() }

    var clockWidgetEnabled: Boolean
        get() = prefs.getBoolean("clock_widget_enabled", true)
        set(v) { prefs.edit().putBoolean("clock_widget_enabled", v).apply() }

    var weatherWidgetEnabled: Boolean
        get() = prefs.getBoolean("weather_widget_enabled", true)
        set(v) { prefs.edit().putBoolean("weather_widget_enabled", v).apply() }

    var clockWidgetSize: String
        get() = prefs.getString("clock_widget_size", "M") ?: "M"
        set(v) { prefs.edit().putString("clock_widget_size", v).apply() }

    var weatherWidgetSize: String
        get() = prefs.getString("weather_widget_size", "M") ?: "M"
        set(v) { prefs.edit().putString("weather_widget_size", v).apply() }

    var widgetSide: String
        get() = prefs.getString("widget_side", "left") ?: "left"
        set(v) { prefs.edit().putString("widget_side", v).apply() }

    /** Empty means the detected network timezone, then the device timezone. */
    var clockWidgetTimeZone: String
        get() = prefs.getString("clock_widget_timezone", "") ?: ""
        set(v) { prefs.edit().putString("clock_widget_timezone", v).apply() }

    /** Empty means detected network city. */
    var weatherWidgetCity: String
        get() = prefs.getString("weather_widget_city", "") ?: ""
        set(v) { prefs.edit().putString("weather_widget_city", v).apply() }

    var weatherWidgetLatitude: Float
        get() = prefs.getFloat("weather_widget_latitude", Float.NaN)
        set(v) { prefs.edit().putFloat("weather_widget_latitude", v).apply() }

    var weatherWidgetLongitude: Float
        get() = prefs.getFloat("weather_widget_longitude", Float.NaN)
        set(v) { prefs.edit().putFloat("weather_widget_longitude", v).apply() }

    var weatherWidgetTemp: Int
        get() = prefs.getInt("weather_widget_temp", Int.MIN_VALUE)
        set(v) { prefs.edit().putInt("weather_widget_temp", v).apply() }

    var weatherWidgetCode: Int
        get() = prefs.getInt("weather_widget_code", -1)
        set(v) { prefs.edit().putInt("weather_widget_code", v).apply() }

    var weatherWidgetDay: Boolean
        get() = prefs.getBoolean("weather_widget_day", true)
        set(v) { prefs.edit().putBoolean("weather_widget_day", v).apply() }

    var weatherWidgetUpdatedAt: Long
        get() = prefs.getLong("weather_widget_updated_at", 0L)
        set(v) { prefs.edit().putLong("weather_widget_updated_at", v).apply() }

    /** Optional personal key, entered on the device; never included in the APK or repository. */
    var weatherCompanyApiKey: String
        get() = prefs.getString("weather_company_api_key", "") ?: ""
        set(v) { prefs.edit().putString("weather_company_api_key", v.trim()).apply() }

    fun savedWeatherNetworkPlace(): WidgetWeather.Place? {
        val latitude = prefs.getFloat("weather_network_latitude", Float.NaN)
        val longitude = prefs.getFloat("weather_network_longitude", Float.NaN)
        if (!latitude.isFinite() || !longitude.isFinite()) return null
        return WidgetWeather.Place(
            prefs.getString("weather_network_city", "") ?: "",
            latitude.toDouble(), longitude.toDouble(),
            prefs.getString("weather_network_timezone", "") ?: ""
        )
    }

    var weatherNetworkDetectedAt: Long
        get() = prefs.getLong("weather_network_detected_at", 0L)
        set(v) { prefs.edit().putLong("weather_network_detected_at", v).apply() }

    fun saveWeatherNetworkPlace(place: WidgetWeather.Place) {
        prefs.edit()
            .putString("weather_network_city", place.name)
            .putFloat("weather_network_latitude", place.latitude.toFloat())
            .putFloat("weather_network_longitude", place.longitude.toFloat())
            .putString("weather_network_timezone", place.timeZone)
            .putLong("weather_network_detected_at", System.currentTimeMillis())
            .apply()
    }

    var livePreview: Boolean
        get() = prefs.getBoolean("live_preview", false)
        set(v) { prefs.edit().putBoolean("live_preview", v).apply() }

    // ---------- Качество трансляции: auto / stable (лимит 720p) ----------
    var quality: String
        get() = prefs.getString("quality", "auto") ?: "auto"
        set(v) { prefs.edit().putString("quality", v).apply() }

    // ---------- Репозиторий для обновлений ----------
    var updateRepo: String
        get() = prefs.getString("update_repo", DEFAULT_REPO) ?: DEFAULT_REPO
        set(v) { prefs.edit().putString("update_repo", v.trim().trimEnd('/')).apply() }

    var autoUpdate: Boolean
        get() = prefs.getBoolean("auto_update", true)
        set(v) { prefs.edit().putBoolean("auto_update", v).apply() }

    // ---------- Плейлисты ----------
    fun getPlaylistCfgs(): MutableList<PlaylistCfg> {
        val out = ArrayList<PlaylistCfg>()
        try {
            val arr = JSONArray(prefs.getString("playlists", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(PlaylistCfg(o.optString("name"), o.optString("url"), o.optBoolean("hidden")))
            }
        } catch (_: Exception) {}
        return out
    }

    fun savePlaylistCfgs(list: List<PlaylistCfg>) {
        val arr = JSONArray()
        for (p in list) arr.put(JSONObject().put("name", p.name).put("url", p.url).put("hidden", p.hidden))
        prefs.edit().putString("playlists", arr.toString()).apply()
    }

    fun addPlaylist(name: String, url: String) {
        val list = getPlaylistCfgs()
        if (list.any { it.url == url }) return
        list.add(PlaylistCfg(name.ifEmpty { "Плейлист ${list.size + 1}" }, url, false))
        savePlaylistCfgs(list)
    }

    fun renamePlaylist(url: String, name: String) {
        val list = getPlaylistCfgs()
        list.find { it.url == url }?.let { it.name = name }
        savePlaylistCfgs(list)
    }

    fun removePlaylist(url: String) {
        savePlaylistCfgs(getPlaylistCfgs().filter { it.url != url })
        cachedFile(url).delete()
    }

    fun cachedFile(url: String): File = File(cacheDir, "pl_" + md5(url) + ".m3u")
    fun logoFile(url: String): File = File(cacheDir, "logo_" + md5(url))
    fun clearPlaylistCaches() { for (c in getPlaylistCfgs()) cachedFile(c.url).delete() }

    // ---------- EPG источники (имя + ссылка) ----------
    fun getEpgSources(): MutableList<EpgSrc> {
        val out = ArrayList<EpgSrc>()
        try {
            val arr = JSONArray(prefs.getString("epg_srcs", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(EpgSrc(o.optString("name"), o.optString("url")))
            }
        } catch (_: Exception) {}
        return out
    }

    fun saveEpgSources(list: List<EpgSrc>) {
        val arr = JSONArray()
        val seen = HashSet<String>()
        for (s in list) {
            val u = s.url.trim()
            if (u.isBlank() || !seen.add(u)) continue
            arr.put(JSONObject().put("name", s.name).put("url", u))
        }
        prefs.edit().putString("epg_srcs", arr.toString()).apply()
    }

    fun addEpgSource(name: String, url: String) {
        val u = url.trim()
        if (!u.startsWith("http")) return
        val list = getEpgSources()
        if (list.none { it.url == u }) {
            list.add(EpgSrc(name.ifEmpty { "Источник ${list.size + 1}" }, u))
            saveEpgSources(list)
        }
    }

    fun renameEpgSource(url: String, name: String) {
        val list = getEpgSources()
        list.find { it.url == url }?.let { it.name = name }
        saveEpgSources(list)
    }

    fun removeEpgSource(url: String) = saveEpgSources(getEpgSources().filter { it.url != url })
    fun getEpgUrls(): List<String> = getEpgSources().map { it.url }

    // ---------- Последний канал / буфер ----------
    var lastChannelUrl: String
        get() = prefs.getString("last_channel", "") ?: ""
        set(v) { prefs.edit().putString("last_channel", v).apply() }

    /**
     * Индекс плейлиста, в контексте которого смотрели последний канал.
     * -1 — «Избранное». Значение -2 означает «не сохранено» (первый запуск).
     */
    var lastPlaylistIdx: Int
        get() = prefs.getInt("last_playlist_idx", -2)
        set(v) { prefs.edit().putInt("last_playlist_idx", v).apply() }

    /**
     * Группа (категория), в контексте которой смотрели последний канал.
     * Пустая строка — «Все каналы» (curCategory == null).
     */
    var lastCategory: String
        get() = prefs.getString("last_category", "") ?: ""
        set(v) { prefs.edit().putString("last_category", v).apply() }

    /** Сохранить весь контекст просмотра одной операцией. */
    fun saveWatchContext(url: String, plIdx: Int, category: String?) {
        prefs.edit()
            .putString("last_channel", url)
            .putInt("last_playlist_idx", plIdx)
            .putString("last_category", category ?: "")
            .apply()
    }

    var bufferSec: Int
        get() = prefs.getInt("buffer_sec", 30)
        set(v) { prefs.edit().putInt("buffer_sec", v).apply() }

    // ---------- Качество трансляции (реальные параметры воспроизведения) ----------

    /** Отставание от live-края для поддерживаемых потоков; 0 оставляет настройки источника. */
    var liveOffsetSec: Int
        get() = prefs.getInt("live_offset_sec", 0)
        set(v) { prefs.edit().putInt("live_offset_sec", v).apply() }

    /** Тип декодера: "hw" — аппаратный (по умолчанию), "sw" — программный. */
    var decoder: String
        get() = prefs.getString("decoder", "hw") ?: "hw"
        set(v) { prefs.edit().putString("decoder", v).apply() }

    /** Автофреймрейт: подстройка частоты экрана под частоту кадров потока. */
    var afr: Boolean
        get() = prefs.getBoolean("afr", false)
        set(v) { prefs.edit().putBoolean("afr", v).apply() }

    /** Поведение при обрыве связи: "fast" | "normal" | "persistent". */
    var retryMode: String
        get() = prefs.getString("retry_mode", "normal") ?: "normal"
        set(v) { prefs.edit().putString("retry_mode", v).apply() }

    /** Сбалансированный набор для старта; на конкретном потоке эффект может отличаться. */
    fun applyRecommendedQuality() {
        prefs.edit()
            .putInt("buffer_sec", 30)
            .putInt("live_offset_sec", 0)
            .putString("retry_mode", "normal")
            .putString("quality", "auto")
            .putString("decoder", "hw")
            .putBoolean("afr", false)
            .apply()
    }

    // ---------- Избранное ----------
    fun getFavorites(): MutableList<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        try {
            val arr = JSONArray(prefs.getString("favorites", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Pair(o.optString("url"), o.optString("name")))
            }
        } catch (_: Exception) {}
        return out
    }

    fun saveFavorites(list: List<Pair<String, String>>) {
        val arr = JSONArray()
        for (p in list) arr.put(JSONObject().put("url", p.first).put("name", p.second))
        prefs.edit().putString("favorites", arr.toString()).apply()
    }

    fun isFavorite(url: String): Boolean = getFavorites().any { it.first == url }

    fun toggleFavorite(url: String, name: String): Boolean {
        val list = getFavorites()
        val idx = list.indexOfFirst { it.first == url }
        return if (idx >= 0) { list.removeAt(idx); saveFavorites(list); false }
        else { list.add(Pair(url, name)); saveFavorites(list); true }
    }

    var favManualOrder: Boolean
        get() = prefs.getBoolean("fav_manual", false)
        set(v) { prefs.edit().putBoolean("fav_manual", v).apply() }

    // ---------- Счётчики ----------
    fun bumpCount(url: String) {
        try {
            val o = JSONObject(prefs.getString("counts", "{}") ?: "{}")
            val k = md5(url)
            o.put(k, o.optInt(k, 0) + 1)
            prefs.edit().putString("counts", o.toString()).apply()
        } catch (_: Exception) {}
    }

    fun countFor(url: String): Int = try {
        JSONObject(prefs.getString("counts", "{}") ?: "{}").optInt(md5(url), 0)
    } catch (_: Exception) { 0 }
}
