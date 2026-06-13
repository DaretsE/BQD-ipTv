package ru.lightair.iptv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream

object Net {

    /**
     * Открывает поток. Сам определяет gzip по сигнатуре файла (1f 8b),
     * поэтому корректно читает и .gz-файлы, и Content-Encoding: gzip.
     */
    fun open(urlStr: String): InputStream {
        var url = URL(urlStr)
        var conn: HttpURLConnection
        var redirects = 0
        while (true) {
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 20000
            conn.readTimeout = 40000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android TV) LegkiyEfir/2.0")
            conn.setRequestProperty("Accept", "*/*")
            val code = conn.responseCode
            if (code in 300..399 && redirects < 6) {
                val loc = conn.getHeaderField("Location") ?: throw Exception("Redirect without Location")
                url = URL(url, loc)
                redirects++
                conn.disconnect()
                continue
            }
            if (code !in 200..299) throw Exception("HTTP $code")
            break
        }
        val raw: InputStream = BufferedInputStream(conn.inputStream, 1 shl 16)
        val pb = PushbackInputStream(raw, 2)
        val sig = ByteArray(2)
        val n = pb.read(sig, 0, 2)
        if (n > 0) pb.unread(sig, 0, n)
        val isGzip = n == 2 && sig[0] == 0x1f.toByte() && (sig[1].toInt() and 0xff) == 0x8b
        return if (isGzip) GZIPInputStream(pb) else pb
    }

    fun downloadText(urlStr: String): String =
        open(urlStr).bufferedReader(Charsets.UTF_8).use { it.readText() }
}

object ImageLoader {
    private val mem = LruCache<String, Bitmap>(60)
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun load(url: String, into: ImageView) {
        if (url.isEmpty()) { into.tag = null; into.setImageDrawable(null); return }
        into.tag = url
        val cached = mem.get(url)
        if (cached != null) { into.setImageBitmap(cached); return }
        into.setImageDrawable(null)
        pool.execute {
            try {
                val file = Store.logoFile(url)
                if (!file.exists() || file.length() == 0L) {
                    Net.open(url).use { ins -> file.outputStream().use { ins.copyTo(it) } }
                }
                val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return@execute
                mem.put(url, bmp)
                main.post { if (into.tag == url) into.setImageBitmap(bmp) }
            } catch (_: Exception) { }
        }
    }
}

object EpgManager {

    data class Prog(val start: Long, val stop: Long, val title: String, val desc: String)

    private val progs = ConcurrentHashMap<String, MutableList<Prog>>()
    private val icons = ConcurrentHashMap<String, String>()
    private val nameToId = ConcurrentHashMap<String, String>()

    @Volatile var loaded = false; private set
    @Volatile var loading = false; private set
    @Volatile var lastError = ""; private set
    @Volatile var programCount = 0; private set

    fun status(): String = when {
        loading -> "Программа загружается…"
        programCount > 0 -> "Загружено передач: $programCount"
        lastError.isNotEmpty() -> "Ошибка EPG: $lastError"
        else -> "EPG не настроен"
    }

    fun clear() {
        progs.clear(); icons.clear(); nameToId.clear()
        loaded = false; programCount = 0; lastError = ""
    }

    /** Загружает и объединяет несколько источников XMLTV в фоне. */
    fun loadAsync(urls: List<String>, onDone: () -> Unit) {
        val list = urls.map { it.trim() }.filter { it.startsWith("http") }
        if (list.isEmpty() || loading) { onDone(); return }
        loading = true
        Thread {
            clearData()
            var err = ""
            var ok = 0
            for (u in list) {
                try { parseOne(u); ok++ } catch (e: Exception) { err = (e.message ?: "ошибка") }
            }
            for (l in progs.values) l.sortBy { it.start }
            programCount = progs.values.sumOf { it.size }
            loaded = programCount > 0
            lastError = if (programCount == 0 && err.isNotEmpty()) err else ""
            loading = false
            Handler(Looper.getMainLooper()).post { onDone() }
        }.start()
    }

    private fun clearData() {
        progs.clear(); icons.clear(); nameToId.clear(); programCount = 0
    }

    private fun parseOne(url: String) {
        val now = System.currentTimeMillis()
        val from = now - 2L * 24 * 3600 * 1000
        val to = now + 3L * 24 * 3600 * 1000
        Net.open(url).use { ins ->
            val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }.newPullParser()
            parser.setInput(ins, null)
            var event = parser.eventType
            var curChannel = ""
            var curStart = 0L
            var curStop = 0L
            val title = StringBuilder()
            val desc = StringBuilder()
            var inTitle = false
            var inDesc = false
            // channel block
            var chId = ""
            var inChannelName = false
            val chName = StringBuilder()
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "channel" -> { chId = parser.getAttributeValue(null, "id") ?: ""; chName.setLength(0) }
                        "display-name" -> if (chId.isNotEmpty()) inChannelName = true
                        "icon" -> {
                            val src = parser.getAttributeValue(null, "src") ?: ""
                            if (chId.isNotEmpty() && src.isNotEmpty() && !icons.containsKey(chId.lowercase()))
                                icons[chId.lowercase()] = src
                        }
                        "programme" -> {
                            curChannel = (parser.getAttributeValue(null, "channel") ?: "").lowercase()
                            curStart = parseTime(parser.getAttributeValue(null, "start") ?: "")
                            curStop = parseTime(parser.getAttributeValue(null, "stop") ?: "")
                            title.setLength(0); desc.setLength(0)
                        }
                        "title" -> inTitle = true
                        "desc" -> inDesc = true
                    }
                    XmlPullParser.TEXT -> {
                        when {
                            inTitle -> title.append(parser.text)
                            inDesc -> desc.append(parser.text)
                            inChannelName -> chName.append(parser.text)
                        }
                    }
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "title" -> inTitle = false
                        "desc" -> inDesc = false
                        "display-name" -> {
                            if (inChannelName && chId.isNotEmpty()) {
                                val key = normalize(chName.toString())
                                if (key.isNotEmpty() && !nameToId.containsKey(key)) nameToId[key] = chId.lowercase()
                            }
                            inChannelName = false; chName.setLength(0)
                        }
                        "channel" -> chId = ""
                        "programme" -> {
                            if (curChannel.isNotEmpty() && curStart > 0 && curStop in from..to) {
                                progs.getOrPut(curChannel) { ArrayList() }
                                    .add(Prog(curStart, curStop,
                                        title.toString().trim().ifEmpty { "Без названия" },
                                        desc.toString().trim()))
                            }
                        }
                    }
                }
                event = parser.next()
            }
        }
    }

    private fun parseTime(s: String): Long {
        val t = s.trim()
        if (t.length < 14) return 0
        return try {
            if (t.length > 14 && t.contains(' ')) {
                SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).parse(t)?.time ?: 0
            } else {
                val df = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
                df.timeZone = TimeZone.getDefault()
                df.parse(t.substring(0, 14))?.time ?: 0
            }
        } catch (_: Exception) { 0 }
    }

    private fun normalize(s: String): String =
        s.lowercase()
            .replace(Regex("\\b(hd|fhd|uhd|4k|sd|\\+\\d+|канал)\\b"), "")
            .replace(Regex("[^a-zа-я0-9]"), "")
            .trim()

    /** Подбирает id передач для канала: по tvg-id, иначе по имени. */
    fun resolveId(ch: Channel): String {
        val tid = ch.tvgId.lowercase()
        if (tid.isNotEmpty() && progs.containsKey(tid)) return tid
        val byName = nameToId[normalize(ch.name)]
        if (byName != null && progs.containsKey(byName)) return byName
        return tid
    }

    fun programsFor(ch: Channel): List<Prog> = progs[resolveId(ch)] ?: emptyList()

    fun currentFor(ch: Channel): Prog? {
        val now = System.currentTimeMillis()
        return programsFor(ch).firstOrNull { now in it.start until it.stop }
    }

    fun nextFor(ch: Channel): Prog? {
        val now = System.currentTimeMillis()
        return programsFor(ch).firstOrNull { it.start > now }
    }

    fun iconFor(ch: Channel): String {
        val id = resolveId(ch)
        if (id.isNotEmpty()) icons[id]?.let { return it }
        return ""
    }
}
