package ru.lightair.iptv

import java.util.regex.Pattern

data class Channel(
    val name: String,
    val url: String,
    val logo: String = "",
    val group: String = "",
    val tvgId: String = "",
    val chno: String = "",
    val catchup: String = "",
    val catchupSource: String = "",
    val catchupDays: Int = 0,
    val playlistName: String = ""
)

data class Playlist(
    val name: String,
    val url: String,
    val channels: List<Channel>,
    val epgUrl: String = ""
)

data class PlaylistCfg(
    var name: String,
    var url: String,
    var hidden: Boolean = false
)

object M3uParser {

    private val attrPattern = Pattern.compile("([a-zA-Z0-9_-]+)=\"(.*?)\"")

    fun parse(text: String, playlistName: String): Pair<List<Channel>, String> {
        val channels = ArrayList<Channel>()
        var epgUrl = ""
        var pendingName = ""
        var pendingAttrs = HashMap<String, String>()
        var pendingGroup = ""

        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            if (line.startsWith("#EXTM3U")) {
                val m = attrPattern.matcher(line)
                while (m.find()) {
                    val key = m.group(1)!!.lowercase()
                    if (key == "url-tvg" || key == "x-tvg-url") {
                        val raw = m.group(2) ?: ""
                        epgUrl = raw.split(",", " ").firstOrNull { it.startsWith("http") } ?: raw
                    }
                }
            } else if (line.startsWith("#EXTINF")) {
                pendingAttrs = HashMap()
                val m = attrPattern.matcher(line)
                while (m.find()) {
                    pendingAttrs[m.group(1)!!.lowercase()] = m.group(2) ?: ""
                }
                val comma = line.lastIndexOf(',')
                pendingName = if (comma >= 0 && comma < line.length - 1)
                    line.substring(comma + 1).trim() else "Без названия"
            } else if (line.startsWith("#EXTGRP:")) {
                pendingGroup = line.substringAfter(":").trim()
            } else if (!line.startsWith("#")) {
                if (pendingName.isNotEmpty() || pendingAttrs.isNotEmpty()) {
                    val group = pendingAttrs["group-title"] ?: pendingGroup
                    channels.add(
                        Channel(
                            name = pendingName.ifEmpty { "Канал ${channels.size + 1}" },
                            url = line,
                            logo = pendingAttrs["tvg-logo"] ?: "",
                            group = group.ifEmpty { "Без категории" },
                            tvgId = (pendingAttrs["tvg-id"] ?: pendingAttrs["tvg-name"] ?: ""),
                            chno = (pendingAttrs["tvg-chno"] ?: pendingAttrs["channel-number"] ?: ""),
                            catchup = (pendingAttrs["catchup"] ?: pendingAttrs["catchup-type"] ?: "").lowercase(),
                            catchupSource = pendingAttrs["catchup-source"] ?: "",
                            catchupDays = (pendingAttrs["catchup-days"] ?: "0").toIntOrNull() ?: 0,
                            playlistName = playlistName
                        )
                    )
                }
                pendingName = ""
                pendingAttrs = HashMap()
                pendingGroup = ""
            }
        }
        return Pair(channels, epgUrl)
    }
}

object CatchupHelper {

    fun canCatchup(ch: Channel): Boolean =
        ch.catchup.isNotEmpty() || ch.catchupSource.isNotEmpty()

    fun buildUrl(ch: Channel, startSec: Long, stopSec: Long): String {
        val now = System.currentTimeMillis() / 1000
        val duration = stopSec - startSec
        val offset = now - startSec

        if (ch.catchupSource.isNotEmpty()) {
            var t = ch.catchupSource
            if (!t.startsWith("http")) {
                t = ch.url + (if (ch.url.contains("?")) "&" else "?") + t.removePrefix("?").removePrefix("&")
            }
            return t
                .replace("\${start}", startSec.toString()).replace("{start}", startSec.toString())
                .replace("\${timestamp}", startSec.toString()).replace("{timestamp}", startSec.toString())
                .replace("\${utc}", startSec.toString()).replace("{utc}", startSec.toString())
                .replace("\${end}", stopSec.toString()).replace("{end}", stopSec.toString())
                .replace("\${stop}", stopSec.toString()).replace("{stop}", stopSec.toString())
                .replace("\${now}", now.toString()).replace("{now}", now.toString())
                .replace("\${lutc}", now.toString()).replace("{lutc}", now.toString())
                .replace("\${duration}", duration.toString()).replace("{duration}", duration.toString())
                .replace("\${offset}", offset.toString()).replace("{offset}", offset.toString())
        }

        return when (ch.catchup) {
            "flussonic", "flussonic-hls", "fs" -> {
                val u = ch.url
                when {
                    u.contains("/index.m3u8") -> u.replace("/index.m3u8", "/archive-$startSec-$duration.m3u8")
                    u.contains("/video.m3u8") -> u.replace("/video.m3u8", "/video-$startSec-$duration.m3u8")
                    u.contains("/mono.m3u8") -> u.replace("/mono.m3u8", "/mono-$startSec-$duration.m3u8")
                    else -> u + (if (u.contains("?")) "&" else "?") + "utc=$startSec&lutc=$now"
                }
            }
            else -> ch.url + (if (ch.url.contains("?")) "&" else "?") + "utc=$startSec&lutc=$now"
        }
    }
}
