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
        migrate()
    }

    private fun migrate() {
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

    var livePreview: Boolean
        get() = prefs.getBoolean("live_preview", false)
        set(v) { prefs.edit().putBoolean("live_preview", v).apply() }

    // ---------- Качество трансляции: auto / stable / max ----------
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

    var bufferSec: Int
        get() = prefs.getInt("buffer_sec", 15)
        set(v) { prefs.edit().putInt("buffer_sec", v).apply() }

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
