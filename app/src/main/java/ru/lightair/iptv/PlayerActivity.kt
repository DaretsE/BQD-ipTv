package ru.lightair.iptv

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class PlayerActivity : Activity() {

    private enum class Panel { NONE, BROWSER, LEFT, RIGHT }

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView

    private lateinit var osdPanel: View
    private lateinit var osdLogo: ImageView
    private lateinit var osdChannel: TextView
    private lateinit var osdProgram: TextView
    private lateinit var osdProgress: ProgressBar
    private lateinit var osdBadge: TextView
    private lateinit var osdClock: TextView
    private lateinit var favStar: TextView
    private lateinit var errorMsg: TextView

    private lateinit var browserOverlay: View
    private lateinit var browserHeader: TextView
    private lateinit var browserListView: ListView
    private lateinit var prevLogo: ImageView
    private lateinit var prevName: TextView
    private lateinit var prevNow: TextView
    private lateinit var prevProgress: ProgressBar
    private lateinit var prevNext: TextView

    private lateinit var leftMenu: View
    private lateinit var plSelector: TextView
    private lateinit var catList: ListView

    private lateinit var rightPanel: View
    private lateinit var epgHeader: TextView
    private lateinit var nowTitle: TextView
    private lateinit var nowTime: TextView
    private lateinit var nowProgress: ProgressBar
    private lateinit var nowDesc: TextView
    private lateinit var epgList: ListView

    private lateinit var setupOverlay: View
    private lateinit var setupUrl: TextView
    private lateinit var modePicker: View
    private lateinit var phoneBar: View
    private lateinit var screensaver: FrameLayout
    private lateinit var ssClock: TextView

    private val handler = Handler(Looper.getMainLooper())

    private var playlists: List<Playlist> = emptyList()
    private var zapList: List<Channel> = emptyList()
    private var zapIndex = 0
    private var currentChannel: Channel? = null

    private var curPlaylistIdx = 0      // -1 = Избранное
    private var curCategory: String? = null
    private var menuPlaylistIdx = 0

    private var panel = Panel.NONE
    private var webServer: WebConfigServer? = null
    private var isPhone = false

    private var longPressFired = false
    private var okPressed = false
    private var errorStreak = 0
    private var lastBackTs = 0L
    private var pausedSince = 0L
    private var isCatchupPlayback = false
    private var browserChannels: List<Channel> = emptyList()
    private var channelBeforeBrowse: Channel? = null

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    // ------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        Store.init(this)
        bindViews()
        buildPlayer()
        startWebServer()
        setupPhoneControls()

        when (Store.mode) {
            "tv" -> { isPhone = false; applyMode(); reloadFromStore(firstRun = true) }
            "phone" -> { isPhone = true; applyMode(); reloadFromStore(firstRun = true) }
            else -> showModePicker()
        }
        handler.postDelayed(screensaverTick, 30000)
        handler.postDelayed(clockTick, 1000)
    }

    private fun bindViews() {
        playerView = findViewById(R.id.playerView)
        osdPanel = findViewById(R.id.osdPanel)
        osdLogo = findViewById(R.id.osdLogo)
        osdChannel = findViewById(R.id.osdChannel)
        osdProgram = findViewById(R.id.osdProgram)
        osdProgress = findViewById(R.id.osdProgress)
        osdBadge = findViewById(R.id.osdBadge)
        osdClock = findViewById(R.id.osdClock)
        favStar = findViewById(R.id.favStar)
        errorMsg = findViewById(R.id.errorMsg)
        browserOverlay = findViewById(R.id.browserOverlay)
        browserHeader = findViewById(R.id.browserHeader)
        browserListView = findViewById(R.id.browserListView)
        prevLogo = findViewById(R.id.prevLogo)
        prevName = findViewById(R.id.prevName)
        prevNow = findViewById(R.id.prevNow)
        prevProgress = findViewById(R.id.prevProgress)
        prevNext = findViewById(R.id.prevNext)
        leftMenu = findViewById(R.id.leftMenu)
        plSelector = findViewById(R.id.plSelector)
        catList = findViewById(R.id.catList)
        rightPanel = findViewById(R.id.rightPanel)
        epgHeader = findViewById(R.id.epgHeader)
        nowTitle = findViewById(R.id.nowTitle)
        nowTime = findViewById(R.id.nowTime)
        nowProgress = findViewById(R.id.nowProgress)
        nowDesc = findViewById(R.id.nowDesc)
        epgList = findViewById(R.id.epgList)
        setupOverlay = findViewById(R.id.setupOverlay)
        setupUrl = findViewById(R.id.setupUrl)
        modePicker = findViewById(R.id.modePicker)
        phoneBar = findViewById(R.id.phoneBar)
        screensaver = findViewById(R.id.screensaver)
        ssClock = findViewById(R.id.ssClock)
    }

    private fun buildPlayer() {
        val bufMs = Store.bufferSec * 1000
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(maxOf(5000, bufMs / 3), maxOf(15000, bufMs * 2), 1500, 3000)
            .build()
        player = ExoPlayer.Builder(this).setLoadControl(loadControl).build()
        playerView.player = player
        player.playWhenReady = true
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { handleStreamError() }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) { errorStreak = 0; errorMsg.visibility = View.GONE }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                pausedSince = if (isPlaying) 0L else System.currentTimeMillis()
            }
        })
    }

    private fun startWebServer() {
        try {
            webServer = WebConfigServer { runOnUiThread { reloadFromStore(firstRun = false) } }
            webServer?.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        } catch (_: Exception) { }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        try { webServer?.stop() } catch (_: Exception) { }
        player.release()
    }

    override fun onResume() {
        super.onResume()
        if (currentChannel != null && !player.isPlaying) player.play()
    }

    // ------------------------------------------------------------ режим TV / телефон

    private fun showModePicker() {
        modePicker.visibility = View.VISIBLE
        findViewById<View>(R.id.modeTv).setOnClickListener { chooseMode("tv") }
        findViewById<View>(R.id.modePhone).setOnClickListener { chooseMode("phone") }
        findViewById<View>(R.id.modeTv).requestFocus()
    }

    private fun chooseMode(m: String) {
        Store.mode = m
        isPhone = m == "phone"
        modePicker.visibility = View.GONE
        applyMode()
        reloadFromStore(firstRun = true)
    }

    private fun applyMode() {
        requestedOrientation = if (isPhone) ActivityInfo.SCREEN_ORIENTATION_USER
        else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        phoneBar.visibility = if (isPhone && panel == Panel.NONE && currentChannel != null) View.VISIBLE else View.GONE
    }

    private fun setupPhoneControls() {
        findViewById<View>(R.id.pbMenu).setOnClickListener { openLeftMenu() }
        findViewById<View>(R.id.pbList).setOnClickListener { openChannelBrowser() }
        findViewById<View>(R.id.pbPrev).setOnClickListener { zap(-1) }
        findViewById<View>(R.id.pbNext).setOnClickListener { zap(1) }
        findViewById<View>(R.id.pbFav).setOnClickListener { toggleFavoriteCurrent() }
        findViewById<View>(R.id.pbEpg).setOnClickListener { openEpgPanel() }
        playerView.setOnClickListener {
            if (isPhone && panel == Panel.NONE) {
                phoneBar.visibility = if (phoneBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                currentChannel?.let { showOsd(it) }
            }
        }
    }

    // ------------------------------------------------------------ загрузка

    private fun reloadFromStore(firstRun: Boolean) {
        Thread {
            val cfgs = Store.getPlaylistCfgs().filter { !it.hidden }
            val loaded = ArrayList<Playlist>()
            val epgUrls = ArrayList<String>(Store.getEpgSources())
            for (cfg in cfgs) {
                try {
                    val file = Store.cachedFile(cfg.url)
                    val text: String = if (file.exists() && file.length() > 0 && !firstRun) file.readText()
                    else try { val t = Net.downloadText(cfg.url); file.writeText(t); t }
                    catch (e: Exception) { if (file.exists()) file.readText() else throw e }
                    val (channels, epg) = M3uParser.parse(text, cfg.name)
                    if (epg.isNotEmpty() && epg !in epgUrls) epgUrls.add(epg)
                    if (channels.isNotEmpty()) loaded.add(Playlist(cfg.name, cfg.url, channels, epg))
                } catch (_: Exception) { }
            }
            runOnUiThread {
                playlists = loaded
                if (playlists.isEmpty()) showSetupOverlay()
                else {
                    setupOverlay.visibility = View.GONE
                    if (currentChannel == null) restoreLastChannel()
                    else rebuildZapList(keepCurrent = true)
                    applyMode()
                }
                if (epgUrls.isNotEmpty()) {
                    EpgManager.loadAsync(epgUrls) {
                        currentChannel?.let { if (panel == Panel.NONE) updateOsdProgram(it) }
                        if (panel == Panel.BROWSER) (browserListView.adapter as? ChannelAdapter)?.notifyDataSetChanged()
                    }
                }
            }
        }.start()
    }

    private fun showSetupOverlay() {
        setupUrl.text = "http://${IpUtil.localIp()}:${WebConfigServer.PORT}"
        setupOverlay.visibility = View.VISIBLE
        phoneBar.visibility = View.GONE
    }

    private fun restoreLastChannel() {
        val last = Store.lastChannelUrl
        var found: Channel? = null
        var plIdx = 0
        for ((i, pl) in playlists.withIndex()) {
            val c = pl.channels.firstOrNull { it.url == last }
            if (c != null) { found = c; plIdx = i; break }
        }
        curPlaylistIdx = plIdx
        curCategory = null
        rebuildZapList(keepCurrent = false)
        val target = found ?: zapList.firstOrNull() ?: return
        zapIndex = zapList.indexOf(target).coerceAtLeast(0)
        play(target)
    }

    private fun favoriteChannels(): List<Channel> {
        val favs = Store.getFavorites()
        val all = HashMap<String, Channel>()
        for (pl in playlists) for (c in pl.channels) if (!all.containsKey(c.url)) all[c.url] = c
        var list = favs.map { (url, name) -> all[url] ?: Channel(name = name, url = url, playlistName = "Избранное") }
        if (!Store.favManualOrder) list = list.sortedByDescending { Store.countFor(it.url) }
        return list
    }

    private fun rebuildZapList(keepCurrent: Boolean) {
        zapList = if (curPlaylistIdx == -1) favoriteChannels()
        else {
            val pl = playlists.getOrNull(curPlaylistIdx) ?: return
            if (curCategory == null) pl.channels else pl.channels.filter { it.group == curCategory }
        }
        if (keepCurrent) {
            val idx = zapList.indexOfFirst { it.url == currentChannel?.url }
            zapIndex = if (idx >= 0) idx else 0
        }
    }

    // ------------------------------------------------------------ воспроизведение

    private fun play(ch: Channel) {
        isCatchupPlayback = false
        currentChannel = ch
        errorMsg.visibility = View.GONE
        handler.removeCallbacks(errorZapRunnable)
        player.setMediaItem(MediaItem.fromUri(ch.url))
        player.prepare()
        player.play()
        Store.lastChannelUrl = ch.url
        Store.bumpCount(ch.url)
        showOsd(ch)
    }

    private fun playCatchup(ch: Channel, url: String, title: String) {
        isCatchupPlayback = true
        errorMsg.visibility = View.GONE
        handler.removeCallbacks(errorZapRunnable)
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.play()
        toast("Архив: $title")
    }

    private fun zap(dir: Int) {
        if (isCatchupPlayback) { currentChannel?.let { play(it) }; return }
        if (zapList.isEmpty()) return
        zapIndex = ((zapIndex + dir) % zapList.size + zapList.size) % zapList.size
        play(zapList[zapIndex])
    }

    private fun toggleFavoriteCurrent() {
        val ch = currentChannel ?: return
        val added = Store.toggleFavorite(ch.url, ch.name)
        animateStar(added)
        toast(if (added) "Добавлено в Избранное" else "Удалено из Избранного")
        if (curPlaylistIdx == -1) rebuildZapList(keepCurrent = true)
    }

    private fun animateStar(added: Boolean) {
        favStar.text = if (added) "★" else "☆"
        favStar.alpha = 1f; favStar.scaleX = 0.4f; favStar.scaleY = 0.4f
        favStar.visibility = View.VISIBLE
        favStar.animate().scaleX(1.2f).scaleY(1.2f).setDuration(250).withEndAction {
            favStar.animate().alpha(0f).setDuration(700).withEndAction { favStar.visibility = View.GONE }.start()
        }.start()
    }

    private fun handleStreamError() {
        errorStreak++
        if (errorStreak > maxOf(6, zapList.size)) {
            errorMsg.text = "Не удаётся воспроизвести каналы.\nПроверьте подключение к интернету."
            errorMsg.visibility = View.VISIBLE
            return
        }
        errorMsg.text = "Сигнал канала временно недоступен.\nПереключаем на следующий..."
        errorMsg.visibility = View.VISIBLE
        handler.removeCallbacks(errorZapRunnable)
        handler.postDelayed(errorZapRunnable, 3000)
    }

    private val errorZapRunnable = Runnable {
        errorMsg.visibility = View.GONE
        if (panel == Panel.NONE && zapList.isNotEmpty()) zap(1)
    }

    // ------------------------------------------------------------ OSD

    private val hideOsdRunnable = Runnable { osdPanel.visibility = View.GONE }

    private fun showOsd(ch: Channel) {
        val num = if (ch.chno.isNotEmpty()) ch.chno else (zapIndex + 1).toString()
        osdChannel.text = "$num • ${ch.name}"
        osdClock.text = timeFmt.format(Date())
        val up = ch.name.uppercase()
        when {
            up.contains("4K") || up.contains("UHD") -> { osdBadge.text = "4K"; osdBadge.visibility = View.VISIBLE }
            up.contains("HD") -> { osdBadge.text = "HD"; osdBadge.visibility = View.VISIBLE }
            else -> osdBadge.visibility = View.GONE
        }
        ImageLoader.load(if (ch.logo.isNotEmpty()) ch.logo else EpgManager.iconFor(ch), osdLogo)
        updateOsdProgram(ch)
        osdPanel.visibility = View.VISIBLE
        handler.removeCallbacks(hideOsdRunnable)
        handler.postDelayed(hideOsdRunnable, 4000)
    }

    private fun updateOsdProgram(ch: Channel) {
        val prog = EpgManager.currentFor(ch)
        if (prog != null) {
            osdProgram.text = prog.title
            val pct = ((System.currentTimeMillis() - prog.start) * 100 / maxOf(1, prog.stop - prog.start)).toInt()
            osdProgress.progress = pct.coerceIn(0, 100)
            osdProgress.visibility = View.VISIBLE
        } else {
            osdProgram.text = if (Store.isFavorite(ch.url)) "★ В избранном" else ""
            osdProgress.visibility = View.INVISIBLE
        }
    }

    private val clockTick = object : Runnable {
        override fun run() {
            if (osdPanel.visibility == View.VISIBLE) osdClock.text = timeFmt.format(Date())
            handler.postDelayed(this, 1000)
        }
    }

    // ------------------------------------------------------------ клавиши

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (modePicker.visibility == View.VISIBLE) return super.dispatchKeyEvent(event)
        if (screensaver.visibility == View.VISIBLE) {
            if (event.action == KeyEvent.ACTION_DOWN) hideScreensaver()
            return true
        }
        if (setupOverlay.visibility == View.VISIBLE) {
            if (event.action == KeyEvent.ACTION_DOWN &&
                (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)) {
                showManualUrlDialog(); return true
            }
            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK && playlists.isNotEmpty()) {
                setupOverlay.visibility = View.GONE; return true
            }
            return super.dispatchKeyEvent(event)
        }

        return when (panel) {
            Panel.NONE -> handleFullscreenKey(event)
            Panel.LEFT -> {
                if (event.action == KeyEvent.ACTION_DOWN) when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { cycleMenuPlaylist(-1); return true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { cycleMenuPlaylist(1); return true }
                    KeyEvent.KEYCODE_BACK -> { closePanels(); return true }
                }
                super.dispatchKeyEvent(event)
            }
            Panel.BROWSER -> {
                if (event.action == KeyEvent.ACTION_DOWN &&
                    (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT)) {
                    cancelBrowse(); return true
                }
                super.dispatchKeyEvent(event)
            }
            Panel.RIGHT -> {
                if (event.action == KeyEvent.ACTION_DOWN &&
                    (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                    closePanels(); return true
                }
                super.dispatchKeyEvent(event)
            }
        }
    }

    private fun handleFullscreenKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { zap(1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(-1); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { openLeftMenu(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { openEpgPanel(); return true }
                KeyEvent.KEYCODE_MENU -> { showQuickMenu(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { player.playWhenReady = !player.playWhenReady; return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (event.repeatCount == 0) {
                        okPressed = true; longPressFired = false
                        handler.postDelayed(longPressRunnable, 900)
                    }
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    val now = System.currentTimeMillis()
                    if (now - lastBackTs < 2500) finish()
                    else { lastBackTs = now; toast("Нажмите НАЗАД ещё раз для выхода") }
                    return true
                }
            }
        } else if (event.action == KeyEvent.ACTION_UP &&
            (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)) {
            if (okPressed) {
                okPressed = false
                handler.removeCallbacks(longPressRunnable)
                if (!longPressFired) openChannelBrowser()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private val longPressRunnable = Runnable { longPressFired = true; toggleFavoriteCurrent() }

    // ------------------------------------------------------------ панели

    private fun closePanels() {
        browserOverlay.visibility = View.GONE
        leftMenu.visibility = View.GONE
        rightPanel.visibility = View.GONE
        panel = Panel.NONE
        if (isPhone && currentChannel != null) phoneBar.visibility = View.VISIBLE
    }

    // --- Браузер каналов с превью ---

    private fun openChannelBrowser() {
        if (zapList.isEmpty()) { openLeftMenu(); return }
        panel = Panel.BROWSER
        phoneBar.visibility = View.GONE
        channelBeforeBrowse = currentChannel
        browserChannels = zapList
        browserHeader.text = currentZapTitle()
        val adapter = ChannelAdapter(this, browserChannels, showNow = true)
        browserListView.adapter = adapter
        browserListView.setOnItemClickListener { _, _, pos, _ ->
            zapIndex = pos
            play(browserChannels[pos])
            closePanels()
        }
        browserListView.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                updatePreview(browserChannels.getOrNull(pos))
                if (Store.livePreview) scheduleLivePreview(browserChannels.getOrNull(pos))
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        })
        browserOverlay.visibility = View.VISIBLE
        browserListView.requestFocus()
        val start = browserChannels.indexOfFirst { it.url == currentChannel?.url }.coerceAtLeast(0)
        browserListView.setSelection(start)
        updatePreview(browserChannels.getOrNull(start))
    }

    private fun cancelBrowse() {
        handler.removeCallbacks(livePreviewRunnable)
        if (Store.livePreview && isCatchupPlayback.not() &&
            channelBeforeBrowse != null && currentChannel?.url != channelBeforeBrowse?.url) {
            channelBeforeBrowse?.let { play(it) }
        }
        closePanels()
    }

    private fun updatePreview(ch: Channel?) {
        if (ch == null) return
        prevName.text = ch.name
        ImageLoader.load(if (ch.logo.isNotEmpty()) ch.logo else EpgManager.iconFor(ch), prevLogo)
        val cur = EpgManager.currentFor(ch)
        val next = EpgManager.nextFor(ch)
        if (cur != null) {
            prevNow.text = "${timeFmt.format(Date(cur.start))} ${cur.title}"
            val pct = ((System.currentTimeMillis() - cur.start) * 100 / maxOf(1, cur.stop - cur.start)).toInt()
            prevProgress.progress = pct.coerceIn(0, 100)
            prevProgress.visibility = View.VISIBLE
        } else {
            prevNow.text = if (EpgManager.loading) "Программа загружается…" else "Нет данных о передаче"
            prevProgress.visibility = View.INVISIBLE
        }
        prevNext.text = if (next != null) "Далее: ${timeFmt.format(Date(next.start))} ${next.title}" else ""
    }

    private fun scheduleLivePreview(ch: Channel?) {
        handler.removeCallbacks(livePreviewRunnable)
        pendingPreview = ch
        handler.postDelayed(livePreviewRunnable, 900)
    }

    private var pendingPreview: Channel? = null
    private val livePreviewRunnable = Runnable {
        val ch = pendingPreview ?: return@Runnable
        if (panel == Panel.BROWSER && currentChannel?.url != ch.url) {
            player.setMediaItem(MediaItem.fromUri(ch.url)); player.prepare(); player.play()
            currentChannel = ch
        }
    }

    private fun currentZapTitle(): String = if (curPlaylistIdx == -1) "★ Избранное"
    else {
        val pl = playlists.getOrNull(curPlaylistIdx)?.name ?: ""
        if (curCategory == null) pl else "$pl • $curCategory"
    }

    // --- Левое меню ---

    private fun openLeftMenu() {
        panel = Panel.LEFT
        phoneBar.visibility = View.GONE
        menuPlaylistIdx = curPlaylistIdx
        refreshLeftMenu()
        leftMenu.visibility = View.VISIBLE
        catList.requestFocus()
    }

    private fun cycleMenuPlaylist(dir: Int) {
        val max = playlists.size - 1
        if (max < -1) return
        menuPlaylistIdx += dir
        if (menuPlaylistIdx < -1) menuPlaylistIdx = max
        if (menuPlaylistIdx > max) menuPlaylistIdx = -1
        refreshLeftMenu()
    }

    private fun refreshLeftMenu() {
        val title = if (menuPlaylistIdx == -1) "★ Избранное" else playlists.getOrNull(menuPlaylistIdx)?.name ?: "—"
        plSelector.text = "◀   $title   ▶"
        val items = ArrayList<String>()
        items.add("Все каналы")
        if (menuPlaylistIdx >= 0) playlists.getOrNull(menuPlaylistIdx)?.let { pl ->
            items.addAll(pl.channels.map { it.group }.distinct())
        }
        items.add("⚙  Настройки")
        catList.adapter = ArrayAdapter(this, R.layout.item_row, items)
        catList.setOnItemClickListener { _, _, pos, _ ->
            if (pos == items.size - 1) { showSettingsDialog(); return@setOnItemClickListener }
            curPlaylistIdx = menuPlaylistIdx
            curCategory = if (pos == 0) null else items[pos]
            rebuildZapList(keepCurrent = false)
            leftMenu.visibility = View.GONE
            if (zapList.isNotEmpty()) {
                val keep = zapList.indexOfFirst { it.url == currentChannel?.url }
                zapIndex = if (keep >= 0) keep else 0
                openChannelBrowser()
            } else { panel = Panel.NONE; toast("В этом разделе нет каналов") }
        }
    }

    // --- Правая панель: текущая передача + таймлайн ---

    private fun openEpgPanel() {
        val ch = currentChannel ?: return
        panel = Panel.RIGHT
        phoneBar.visibility = View.GONE
        epgHeader.text = ch.name
        val progs = EpgManager.programsFor(ch)
        val now = System.currentTimeMillis()

        val cur = progs.firstOrNull { now in it.start until it.stop }
        if (cur != null) {
            nowTitle.text = cur.title
            nowTime.text = "${timeFmt.format(Date(cur.start))} – ${timeFmt.format(Date(cur.stop))}"
            nowProgress.progress = ((now - cur.start) * 100 / maxOf(1, cur.stop - cur.start)).toInt().coerceIn(0, 100)
            nowProgress.visibility = View.VISIBLE
            nowDesc.text = cur.desc
            nowDesc.visibility = if (cur.desc.isEmpty()) View.GONE else View.VISIBLE
        } else {
            nowTitle.text = if (EpgManager.loading) "Программа загружается…" else "Нет данных о текущей передаче"
            nowTime.text = ""; nowProgress.visibility = View.INVISIBLE; nowDesc.visibility = View.GONE
        }

        val canArc = CatchupHelper.canCatchup(ch)
        if (progs.isEmpty()) {
            val hint = if (EpgManager.loaded) listOf("Для этого канала нет программы передач")
            else listOf(EpgManager.status(), "Добавьте источник EPG с телефона", "или в Настройках на ТВ")
            epgList.adapter = ArrayAdapter(this, R.layout.item_row, hint)
            epgList.setOnItemClickListener { _, _, _, _ -> closePanels() }
        } else {
            val labels = progs.map { p ->
                val t = timeFmt.format(Date(p.start))
                when {
                    p.stop <= now -> (if (canArc) "▶ " else "   ") + "$t  ${p.title}"
                    p.start > now -> "⏰ $t  ${p.title}"
                    else -> "● $t  ${p.title}  (сейчас)"
                }
            }
            epgList.adapter = ArrayAdapter(this, R.layout.item_row, labels)
            epgList.setOnItemClickListener { _, _, pos, _ ->
                val p = progs[pos]
                when {
                    p.stop <= now -> {
                        if (canArc) { closePanels(); playCatchup(ch, CatchupHelper.buildUrl(ch, p.start / 1000, p.stop / 1000), p.title) }
                        else toast("Архив для этого канала недоступен")
                    }
                    p.start > now -> {
                        handler.postDelayed({ toast("Сейчас начинается: ${p.title} (${ch.name})") }, p.start - now)
                        toast("Напоминание установлено: ${timeFmt.format(Date(p.start))}")
                    }
                    else -> closePanels()
                }
            }
        }
        rightPanel.visibility = View.VISIBLE
        epgList.requestFocus()
        val curIdx = progs.indexOfFirst { now in it.start until it.stop }
        if (curIdx >= 0) epgList.setSelection(maxOf(0, curIdx - 2))
    }

    // ------------------------------------------------------------ быстрое меню

    private fun showQuickMenu() {
        AlertDialog.Builder(this).setTitle("Быстрые настройки")
            .setItems(arrayOf("🎵 Аудиодорожка", "💬 Субтитры", "⚙ Формат кадра")) { _, which ->
                when (which) {
                    0 -> showTrackDialog(C.TRACK_TYPE_AUDIO, "Аудиодорожка")
                    1 -> showTrackDialog(C.TRACK_TYPE_TEXT, "Субтитры")
                    2 -> cycleAspect()
                }
            }.show()
    }

    private fun showTrackDialog(type: Int, title: String) {
        val groups = player.currentTracks.groups.filter { it.type == type }
        val names = ArrayList<String>()
        if (type == C.TRACK_TYPE_TEXT) names.add("Выключить")
        for ((i, g) in groups.withIndex()) {
            val f = g.getTrackFormat(0)
            names.add(f.label ?: f.language ?: "Дорожка ${i + 1}")
        }
        if (names.isEmpty()) { toast("Нет доступных дорожек"); return }
        AlertDialog.Builder(this).setTitle(title)
            .setItems(names.toTypedArray()) { _, which ->
                val params = player.trackSelectionParameters.buildUpon()
                if (type == C.TRACK_TYPE_TEXT && which == 0) params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                else {
                    val idx = if (type == C.TRACK_TYPE_TEXT) which - 1 else which
                    val g = groups.getOrNull(idx) ?: return@setItems
                    params.setTrackTypeDisabled(type, false)
                    params.setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0))
                }
                player.trackSelectionParameters = params.build()
            }.show()
    }

    private fun cycleAspect() {
        playerView.resizeMode = when (playerView.resizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        toast("Формат: " + when (playerView.resizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Растянуть"
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Увеличить"
            else -> "По размеру"
        })
    }

    // ------------------------------------------------------------ настройки на ТВ

    private fun showSettingsDialog() {
        val items = arrayOf(
            "📱 Настроить с телефона (показать адрес)",
            "⌨ Ввести ссылку на плейлист вручную",
            "📺 Добавить источник EPG вручную",
            "🗑 Удалить плейлист",
            "⏱ Размер буфера: ${Store.bufferSec} сек",
            "🖼 Живое превью в списке: ${if (Store.livePreview) "вкл" else "выкл"}",
            "🔁 Сменить режим (ТВ/Телефон)",
            "🔄 Обновить плейлисты и EPG"
        )
        AlertDialog.Builder(this).setTitle("Настройки")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> { closePanels(); showSetupOverlay() }
                    1 -> showManualUrlDialog()
                    2 -> showManualEpgDialog()
                    3 -> showDeletePlaylistDialog()
                    4 -> showBufferDialog()
                    5 -> { Store.livePreview = !Store.livePreview; toast("Живое превью: ${if (Store.livePreview) "вкл" else "выкл"}") }
                    6 -> { Store.mode = ""; recreate() }
                    7 -> { for (c in Store.getPlaylistCfgs()) Store.cachedFile(c.url).delete(); EpgManager.clear(); closePanels(); reloadFromStore(true); toast("Обновляем…") }
                }
            }.show()
    }

    private fun showManualUrlDialog() {
        val input = EditText(this)
        input.hint = "https://… ссылка на m3u"
        input.inputType = InputType.TYPE_TEXT_VARIATION_URI
        AlertDialog.Builder(this).setTitle("Ссылка на плейлист").setView(input)
            .setPositiveButton("Добавить") { _, _ ->
                val url = input.text.toString().trim()
                if (url.startsWith("http")) { Store.addPlaylist("", url); closePanels(); reloadFromStore(true) }
                else toast("Ссылка должна начинаться с http")
            }.setNegativeButton("Отмена", null).show()
    }

    private fun showManualEpgDialog() {
        val input = EditText(this)
        input.hint = "https://… xmltv (.xml.gz)"
        input.inputType = InputType.TYPE_TEXT_VARIATION_URI
        AlertDialog.Builder(this).setTitle("Источник программы (EPG)").setView(input)
            .setPositiveButton("Добавить") { _, _ ->
                val url = input.text.toString().trim()
                if (url.startsWith("http")) {
                    Store.addEpgSource(url); EpgManager.clear(); closePanels(); reloadFromStore(false); toast("Загружаем программу…")
                } else toast("Ссылка должна начинаться с http")
            }.setNegativeButton("Отмена", null).show()
    }

    private fun showDeletePlaylistDialog() {
        val cfgs = Store.getPlaylistCfgs()
        if (cfgs.isEmpty()) { toast("Плейлистов нет"); return }
        AlertDialog.Builder(this).setTitle("Удалить плейлист")
            .setItems(cfgs.map { it.name }.toTypedArray()) { _, which ->
                Store.removePlaylist(cfgs[which].url); closePanels(); currentChannel = null; reloadFromStore(false)
            }.show()
    }

    private fun showBufferDialog() {
        val options = arrayOf("5 сек", "10 сек", "15 сек", "30 сек", "60 сек")
        val values = intArrayOf(5, 10, 15, 30, 60)
        AlertDialog.Builder(this).setTitle("Размер буфера")
            .setItems(options) { _, which ->
                Store.bufferSec = values[which]
                toast("Буфер: ${values[which]} сек. Применится после перезапуска.")
            }.show()
    }

    // ------------------------------------------------------------ скринсейвер

    private val screensaverTick = object : Runnable {
        override fun run() {
            val pausedLong = pausedSince > 0 && System.currentTimeMillis() - pausedSince > 5 * 60 * 1000
            if (pausedLong && panel == Panel.NONE && screensaver.visibility != View.VISIBLE) showScreensaver()
            if (screensaver.visibility == View.VISIBLE) moveSsClock()
            handler.postDelayed(this, 30000)
        }
    }

    private fun showScreensaver() { screensaver.visibility = View.VISIBLE; moveSsClock() }

    private fun moveSsClock() {
        ssClock.text = timeFmt.format(Date())
        val w = screensaver.width; val h = screensaver.height
        if (w > 0 && h > 0) {
            ssClock.x = Random.nextInt(0, maxOf(1, w - 300)).toFloat()
            ssClock.y = Random.nextInt(0, maxOf(1, h - 120)).toFloat()
        }
    }

    private fun hideScreensaver() {
        screensaver.visibility = View.GONE
        pausedSince = if (player.isPlaying) 0L else System.currentTimeMillis()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
