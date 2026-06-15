package ru.bqd.iptv

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
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream

object Net {

    fun open(urlStr: String): InputStream {
        var url = URL(urlStr)
        var conn: HttpURLConnection
        var redirects = 0
        while (true) {
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 20000
            conn.readTimeout = 40000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android TV) BQDiptv/1.0")
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
    // увеличенный кэш в памяти, чтобы логотипы не вымывались
    private val mem = LruCache<String, Bitmap>(300)
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun load(url: String, into: ImageView) {
        if (url.isEmpty()) { into.tag = null; into.setImageDrawable(null); return }
        into.tag = url
        val cached = mem.get(url)
        if (cached != null) { into.setImageBitmap(cached); return }
        // не сбрасываем картинку, если она уже стоит — уменьшает мигание
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

/**
 * EPG с двойной буферизацией: новые данные парсятся в фоне во временные структуры
 * и подменяются атомарно только когда полностью готовы. Старые данные не стираются
 * до момента готовности новых, поэтому программа и иконки не пропадают из интерфейса.
 */
object EpgManager {

    data class Prog(val start: Long, val stop: Long, val title: String, val desc: String)

    @Volatile private var progs: Map<String, List<Prog>> = emptyMap()
    @Volatile private var nameToId: Map<String, String> = emptyMap()
    private val icons = ConcurrentHashMap<String, String>()   // постоянный, только пополняется

    @Volatile var loaded = false; private set
    @Volatile var loading = false; private set
    @Volatile var lastError = ""; private set
    @Volatile var programCount = 0; private set
    @Volatile var lastLoadedAt = 0L; private set

    fun status(): String = when {
        loading && programCount > 0 -> "Обновление программы… (показаны прежние данные)"
        loading -> "Программа загружается…"
        programCount > 0 -> "Загружено передач: $programCount"
        lastError.isNotEmpty() -> "Ошибка EPG: $lastError"
        else -> "EPG не настроен"
    }

    fun clearAll() {
        progs = emptyMap(); nameToId = emptyMap(); icons.clear()
        loaded = false; programCount = 0; lastError = ""; lastLoadedAt = 0L
    }

    fun loadAsync(urls: List<String>, onDone: () -> Unit) {
        val list = urls.map { it.trim() }.filter { it.startsWith("http") }
        if (list.isEmpty() || loading) { onDone(); return }
        loading = true
        Thread {
            val newProgs = HashMap<String, MutableList<Prog>>()
            val newNames = HashMap<String, String>()
            val newIcons = HashMap<String, String>()
            var err = ""
            var okCount = 0
            for (u in list) {
                try { parseOne(u, newProgs, newNames, newIcons); okCount++ }
                catch (e: Exception) { err = (e.message ?: "ошибка") }
            }
            val total = newProgs.values.sumOf { it.size }
            if (total > 0) {
                for (l in newProgs.values) l.sortBy { it.start }
                // атомарная подмена готовых данных
                progs = newProgs
                nameToId = newNames
                for ((k, v) in newIcons) icons[k] = v
                programCount = total
                loaded = true
                lastError = ""
                lastLoadedAt = System.currentTimeMillis()
            } else {
                // ничего не распарсилось — оставляем прежние данные, фиксируем ошибку
                if (err.isNotEmpty()) lastError = err
            }
            loading = false
            Handler(Looper.getMainLooper()).post { onDone() }
        }.start()
    }

    private fun parseOne(
        url: String,
        outProgs: HashMap<String, MutableList<Prog>>,
        outNames: HashMap<String, String>,
        outIcons: HashMap<String, String>
    ) {
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
                            if (chId.isNotEmpty() && src.isNotEmpty() && !outIcons.containsKey(chId.lowercase()))
                                outIcons[chId.lowercase()] = src
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
                    XmlPullParser.TEXT -> when {
                        inTitle -> title.append(parser.text)
                        inDesc -> desc.append(parser.text)
                        inChannelName -> chName.append(parser.text)
                    }
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "title" -> inTitle = false
                        "desc" -> inDesc = false
                        "display-name" -> {
                            if (inChannelName && chId.isNotEmpty()) {
                                val key = normalize(chName.toString())
                                if (key.isNotEmpty() && !outNames.containsKey(key)) outNames[key] = chId.lowercase()
                            }
                            inChannelName = false; chName.setLength(0)
                        }
                        "channel" -> chId = ""
                        "programme" -> {
                            if (curChannel.isNotEmpty() && curStart > 0 && curStop in from..to) {
                                outProgs.getOrPut(curChannel) { ArrayList() }
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

    // ---------- поиск передач по названию и описанию (нестрогий) ----------

    enum class HitState { NOW, FUTURE, ARCHIVE }

    data class SearchHit(
        val channel: Channel,
        val prog: Prog,
        val state: HitState,
        val inTitle: Boolean
    )

    /** Нормализация: нижний регистр, ё→е, всё кроме букв/цифр → пробел, схлопывание пробелов. */
    private fun normLoose(s: String): String {
        val low = s.lowercase().replace('ё', 'е')
        val sb = StringBuilder()
        var prevSpace = true
        for (c in low) {
            val ok = (c in 'a'..'z') || (c in 'а'..'я') || (c in '0'..'9')
            if (ok) { sb.append(c); prevSpace = false }
            else if (!prevSpace) { sb.append(' '); prevSpace = true }
        }
        return sb.toString().trim()
    }

    /** Расстояние Левенштейна с ранним выходом, если превышает max. */
    private fun levenshtein(a: String, b: String, max: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > max) return max + 1
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (cur[j] < rowMin) rowMin = cur[j]
            }
            if (rowMin > max) return max + 1
            System.arraycopy(cur, 0, prev, 0, b.length + 1)
        }
        return prev[b.length]
    }

    private fun looseMatch(text: String, q: String, qNoSpace: String, single: Boolean, maxDist: Int): Boolean {
        if (text.isEmpty()) return false
        val nt = normLoose(text)
        if (nt.isEmpty()) return false
        if (nt.contains(q)) return true
        if (qNoSpace.length >= 3 && nt.replace(" ", "").contains(qNoSpace)) return true
        if (single && q.length in 3..16) {
            for (w in nt.split(' ')) {
                if (w.isEmpty()) continue
                if (kotlin.math.abs(w.length - q.length) <= maxDist && levenshtein(w, q, maxDist) <= maxDist) return true
            }
        }
        return false
    }

    /**
     * Ищет по уже загруженной в память программе. Возвращает совпадения,
     * отсортированные: сейчас → будущее → архив; внутри — по времени.
     * inTitle=true — совпало в названии, false — только в описании.
     */
    fun search(query: String, channels: List<Channel>): List<SearchHit> {
        val q = normLoose(query)
        if (q.length < 2) return emptyList()
        val qNoSpace = q.replace(" ", "")
        val single = !q.contains(' ')
        val maxDist = if (q.length <= 4) 1 else 2
        val now = System.currentTimeMillis()

        val idToCh = LinkedHashMap<String, Channel>()
        for (c in channels) {
            val id = resolveId(c)
            if (id.isNotEmpty() && progs.containsKey(id) && !idToCh.containsKey(id)) idToCh[id] = c
        }

        val hits = ArrayList<SearchHit>()
        for ((id, list) in progs) {
            val ch = idToCh[id] ?: continue
            val canArc = CatchupHelper.canCatchup(ch)
            val arcDays = if (ch.catchupDays > 0) ch.catchupDays else 7
            val arcFrom = now - arcDays.toLong() * 24 * 3600 * 1000
            for (p in list) {
                val inTitle = looseMatch(p.title, q, qNoSpace, single, maxDist)
                val inDesc = if (inTitle) false else looseMatch(p.desc, q, qNoSpace, single, maxDist)
                if (!inTitle && !inDesc) continue
                val state = when {
                    now in p.start until p.stop -> HitState.NOW
                    p.start > now -> HitState.FUTURE
                    else -> if (canArc && p.start >= arcFrom && p.stop <= now) HitState.ARCHIVE else continue
                }
                hits.add(SearchHit(ch, p, state, inTitle))
            }
        }
        hits.sortWith(Comparator { a, b ->
            val sa = a.state.ordinal; val sb = b.state.ordinal
            when {
                sa != sb -> sa - sb
                a.state == HitState.ARCHIVE -> b.prog.start.compareTo(a.prog.start)
                else -> a.prog.start.compareTo(b.prog.start)
            }
        })
        return if (hits.size > 300) hits.subList(0, 300).toList() else hits
    }
}
