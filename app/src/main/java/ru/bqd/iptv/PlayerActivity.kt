package ru.bqd.iptv

import android.app.Activity
import android.app.AlertDialog
import android.app.UiModeManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.Gravity
import android.view.animation.PathInterpolator
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class PlayerActivity : Activity() {

    private enum class Panel { NONE, BROWSER, LEFT, RIGHT, SETTINGS }

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private var playerReleased = false

    private lateinit var osdPanel: View
    private lateinit var osdLogo: ImageView
    private lateinit var osdChannel: TextView
    private lateinit var osdProgram: TextView
    private lateinit var osdProgress: ProgressBar
    private lateinit var osdBadge: TextView
    private lateinit var osdClock: TextView
    private lateinit var osdLive: TextView
    private lateinit var osdRemain: TextView
    private lateinit var osdNext: TextView
    private lateinit var osdButtons: View
    private lateinit var osdBtnFav: TextView
    private lateinit var osdBtnPrev: TextView
    private lateinit var osdBtnNext: TextView
    private lateinit var osdBtnLast: TextView
    private lateinit var favStar: TextView
    private lateinit var errorMsg: TextView

    private lateinit var browserOverlay: View
    private lateinit var browserList: View
    private lateinit var browserHeader: TextView
    private lateinit var browserPlName: TextView
    private lateinit var browserListView: ListView
    private lateinit var previewCard: View
    private lateinit var prevLogo: ImageView
    private lateinit var prevName: TextView
    private lateinit var prevNow: TextView
    private lateinit var prevProgress: ProgressBar
    private lateinit var prevRemain: TextView
    private lateinit var prevNext: TextView

    private lateinit var leftMenu: View
    private lateinit var catList: ListView
    /** Верхний неподвижный блок левого меню (Настройки/Поиск/Избранное + селектор плейлиста). */
    private lateinit var leftFixedTop: View
    private lateinit var settingsRow: View
    private lateinit var searchRow: View
    private lateinit var favRow: View
    private lateinit var favRowCount: TextView
    private lateinit var plSelFixed: View
    private lateinit var plSelFixedName: TextView
    /** Категории (без служебных пунктов) — то, что реально лежит в прокручиваемом catList. */
    private var catOnlyList: List<CatItem> = emptyList()

    private lateinit var rightPanel: View
    private lateinit var epgHeader: TextView
    private lateinit var nowTitle: TextView
    private lateinit var nowTime: TextView
    private lateinit var nowProgress: ProgressBar
    private lateinit var nowRemain: TextView
    private lateinit var nowDesc: TextView
    private lateinit var epgList: ListView

    private lateinit var setupOverlay: View
    private lateinit var setupUrl: TextView
    private lateinit var qrImage: ImageView
    private lateinit var phoneSetup: View
    private lateinit var phoneSetupStatus: TextView
    private lateinit var modePicker: View
    private lateinit var modeTv: TextView
    private lateinit var modePhone: TextView
    private lateinit var phoneBar: View
    private lateinit var screensaver: FrameLayout
    private lateinit var ssClock: TextView

    private lateinit var searchOverlay: View
    private lateinit var searchContent: View
    private lateinit var searchInput: EditText
    private lateinit var searchStatus: TextView
    private lateinit var searchResults: ListView
    private var searchRows: List<SearchRow> = emptyList()
    private var searchSeq = 0
    private var focusResultsAfterSearch = false
    private val searchRunnable = Runnable { doSearch() }

    private val handler = Handler(Looper.getMainLooper())

    private var playlists: List<Playlist> = emptyList()
    private var zapList: List<Channel> = emptyList()
    private var zapIndex = 0
    private var currentChannel: Channel? = null

    /**
     * Предыдущий просмотренный канал вместе с источником запуска.
     * Хранится не только индекс, но и плейлист с группой — иначе возврат
     * «на прошлый канал» ломался при переходе между разными списками.
     */
    private data class ChannelRef(val plIdx: Int, val category: String?, val url: String)
    private var prevRef: ChannelRef? = null

    private var curPlaylistIdx = 0
    private var curCategory: String? = null
    private var menuPlaylistIdx = 0
    /** Последний реальный плейлист (нужен, когда смотрим «Избранное», curPlaylistIdx = -1). */
    private var lastRealPlaylistIdx = 0

    /** Текущие пункты левого меню — нужны для прыжка на «Настройки». */
    private var catItemsList: List<CatItem> = emptyList()

    private lateinit var toastView: TextView

    // панель настроек
    private lateinit var settingsPanel: View
    private lateinit var setListPanel: View
    private lateinit var setListVersion: TextView
    private lateinit var setList: ListView
    private lateinit var setDetail: LinearLayout
    private lateinit var setDetailScroll: android.widget.ScrollView
    private var setDetailActive = false
    private var setSelected = 0

    private var panel = Panel.NONE
    private var webServer: WebConfigServer? = null
    private var isPhone = false
    private var isTvDevice = false

    private var longPressFired = false
    private var okPressed = false
    private var errorStreak = 0
    private var lastBackTs = 0L
    private var pausedSince = 0L
    private var isCatchupPlayback = false
    private var awaitingInstall = false
    private var browserChannels: List<Channel> = emptyList()

    /** Фокус переведён на кнопку действия у выделенного канала (п.4 спецификации). */
    private var browserActionFocused = false

    // свёрнутая рейка рядом со списком каналов (только иконки категорий)
    // Ширины меню из прототипа: #leftMenu 392px / .collapsed 88px,
    // отступы 16px / 10px.
    private val MENU_W_FULL = 392
    private val MENU_W_RAIL = 88
    private val MENU_PAD_FULL = 16
    private val MENU_PAD_RAIL = 10

    /** Свёрнуто ли левое меню в рейку (класс .collapsed в прототипе). */
    private var menuCollapsed = false
    private var menuWidthAnim: android.animation.ValueAnimator? = null
    private var channelBeforeBrowse: Channel? = null
    private var modeSelection = "tv"

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dayFmt = SimpleDateFormat("d MMM HH:mm", Locale.getDefault())

    // если программа в кэше моложе этого срока — при старте не перегружаем её по сети
    private val EPG_FRESH_MS = 6L * 3600 * 1000

    /** Плашка канала скрывается целиком через это время бездействия. */
    private val OSD_TIMEOUT_MS = 3500L

    // ------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        Store.init(this)
        EpgManager.loadDiskCache()   // п.2: показать прошлую программу/иконки сразу, до сетевой загрузки
        detectDevice()
        bindViews()
        buildPlayer()
        startWebServer()
        setupPhoneControls()
        setupModePicker()

        // Экран выбора режима убран: приложение работает только в ТВ-режиме (пульт).
        if (Store.mode != "tv") Store.mode = "tv"
        isPhone = false
        applyMode()
        reloadFromStore(firstRun = false)
        handler.postDelayed(screensaverTick, 30000)
        handler.postDelayed(clockTick, 1000)
        handler.postDelayed(epgRefreshTick, 3L * 3600 * 1000)
        if (Store.autoUpdate) handler.postDelayed({ checkUpdates(silent = true) }, 4000)
    }

    private fun detectDevice() {
        val ui = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        isTvDevice = (ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            !packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        modeSelection = if (isTvDevice) "tv" else "phone"
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
        osdLive = findViewById(R.id.osdLive)
        osdRemain = findViewById(R.id.osdRemain)
        osdNext = findViewById(R.id.osdNext)
        osdButtons = findViewById(R.id.osdButtons)
        osdBtnFav = findViewById(R.id.osdBtnFav)
        osdBtnPrev = findViewById(R.id.osdBtnPrev)
        osdBtnNext = findViewById(R.id.osdBtnNext)
        osdBtnLast = findViewById(R.id.osdBtnLast)
        setupOsdButtons()
        // рейка — визуальный указатель категории, фокус на неё не переводится
        settingsPanel = findViewById(R.id.settingsPanel)
        setListPanel = findViewById(R.id.setListPanel)
        setListVersion = findViewById(R.id.setListVersion)
        setList = findViewById(R.id.setList)
        setDetail = findViewById(R.id.setDetail)
        setDetailScroll = findViewById(R.id.setDetailScroll)
        toastView = findViewById(R.id.toastView)
        favStar = findViewById(R.id.favStar)
        errorMsg = findViewById(R.id.errorMsg)
        browserOverlay = findViewById(R.id.browserOverlay)
        browserList = findViewById(R.id.browserList)
        browserHeader = findViewById(R.id.browserHeader)
        browserPlName = findViewById(R.id.browserPlName)
        browserListView = findViewById(R.id.browserListView)
        previewCard = findViewById(R.id.previewCard)
        prevLogo = findViewById(R.id.prevLogo)
        prevName = findViewById(R.id.prevName)
        prevNow = findViewById(R.id.prevNow)
        prevProgress = findViewById(R.id.prevProgress)
        prevRemain = findViewById(R.id.prevRemain)
        prevNext = findViewById(R.id.prevNext)
        leftMenu = findViewById(R.id.leftMenu)
        catList = findViewById(R.id.catList)
        leftFixedTop = findViewById(R.id.leftFixedTop)
        settingsRow = findViewById(R.id.settingsRow)
        searchRow = findViewById(R.id.searchRow)
        favRow = findViewById(R.id.favRow)
        favRowCount = favRow.findViewById(R.id.catCount)
        plSelFixed = findViewById(R.id.plSelFixed)
        plSelFixedName = findViewById(R.id.plSelName)
        setupLeftFixedRows()
        rightPanel = findViewById(R.id.rightPanel)
        epgHeader = findViewById(R.id.epgHeader)
        nowTitle = findViewById(R.id.nowTitle)
        nowTime = findViewById(R.id.nowTime)
        nowProgress = findViewById(R.id.nowProgress)
        nowRemain = findViewById(R.id.nowRemain)
        nowDesc = findViewById(R.id.nowDesc)
        epgList = findViewById(R.id.epgList)
        setupOverlay = findViewById(R.id.setupOverlay)
        setupUrl = findViewById(R.id.setupUrl)
        qrImage = findViewById(R.id.qrImage)
        phoneSetup = findViewById(R.id.phoneSetup)
        phoneSetupStatus = findViewById(R.id.phoneSetupStatus)
        modePicker = findViewById(R.id.modePicker)
        modeTv = findViewById(R.id.modeTv)
        modePhone = findViewById(R.id.modePhone)
        phoneBar = findViewById(R.id.phoneBar)
        screensaver = findViewById(R.id.screensaver)
        ssClock = findViewById(R.id.ssClock)
        searchOverlay = findViewById(R.id.searchOverlay)
        searchContent = findViewById(R.id.searchContent)
        searchInput = findViewById(R.id.searchInput)
        searchStatus = findViewById(R.id.searchStatus)
        searchResults = findViewById(R.id.searchResults)
        setupSearch()
        clampPanels()
    }

    /**
     * Размеры панелей — из прототипа: меню 392, список каналов 540, рейка 88,
     * правая панель 520. На узких экранах ужимаем, чтобы ничего не уезжало за край.
     * Карточка предпросмотра сдвигается на ширину рейки + ширину списка.
     */
    private fun clampPanels() {
        val w = resources.displayMetrics.widthPixels
        fun dpx(v: Int) = (v * resources.displayMetrics.density).toInt()
        val railW = dpx(88)
        val listW = minOf(dpx(540), (w * 0.62).toInt()).coerceAtLeast(dpx(240))
        browserList.layoutParams = browserList.layoutParams.apply { width = listW }
        (previewCard.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.leftMargin = railW + listW
            previewCard.layoutParams = it
        }
        if (!menuCollapsed) {
            leftMenu.layoutParams = leftMenu.layoutParams.apply {
                width = minOf(dpx(MENU_W_FULL), (w * 0.85).toInt())
            }
        }
        rightPanel.layoutParams = rightPanel.layoutParams.apply { width = minOf(dpx(520), (w * 0.9).toInt()) }

        val setListW = minOf(dpx(400), (w * 0.55).toInt()).coerceAtLeast(dpx(260))
        (setListPanel.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.leftMargin = railW
            it.width = setListW
            setListPanel.layoutParams = it
        }
        (setDetailScroll.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.leftMargin = railW + setListW
            setDetailScroll.layoutParams = it
        }
    }

    /**
     * ВАЖНО: раньше здесь создавался новый ExoPlayer, а старый экземпляр никто
     * не освобождал. Настройки буфера/декодера задаются только при создании
     * плеера, поэтому при их смене оставался «осиротевший» плеер: поверхность
     * у него отбирали, а звук он продолжал играть — отсюда был баг «звук от
     * прошлого канала, новый канал висит». Теперь старый плеер гарантированно
     * освобождается перед созданием нового.
     */
    private fun buildPlayer() {
        releasePlayerInternal()
        player = ExoPlayer.Builder(this)
            .setLoadControl(Quality.loadControl())
            .setRenderersFactory(Quality.renderersFactory(this))
            .setMediaSourceFactory(Quality.mediaSourceFactory(this))
            .build()
        playerView.player = player
        player.playWhenReady = true
        applyQuality()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { handleStreamError() }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    errorStreak = 0
                    errorMsg.visibility = View.GONE
                    applyAutoFrameRate()
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                pausedSince = if (isPlaying) 0L else System.currentTimeMillis()
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {}
        })
        playerReleased = false
    }

    /** Полное освобождение текущего плеера (без него настройки ломали звук). */
    private fun releasePlayerInternal() {
        if (!::player.isInitialized) return
        try {
            playerView.player = null
            player.playWhenReady = false
            player.stop()
            player.clearMediaItems()
            player.release()
        } catch (_: Throwable) { }
        playerReleased = true
    }

    /** Пересоздать плеер после смены настроек и вернуться на текущий канал. */
    private fun rebuildPlayerKeepingChannel() {
        val ch = currentChannel
        buildPlayer()
        ch?.let { play(it) }
    }

    /** Автофреймрейт: подстройка частоты экрана под частоту кадров потока. */
    private fun applyAutoFrameRate() {
        if (!Store.afr) return
        try {
            val fps = player.videoFormat?.frameRate ?: return
            Quality.applyFrameRate(this, fps)
        } catch (_: Throwable) { }
    }

    private fun applyQuality() {
        val p = player.trackSelectionParameters.buildUpon()
        when (Store.quality) {
            "stable" -> { p.setMaxVideoSize(1280, 720); p.setMaxVideoBitrate(2_800_000) }
            "max" -> { p.clearVideoSizeConstraints(); p.setMaxVideoBitrate(Int.MAX_VALUE) }
            else -> { p.clearVideoSizeConstraints(); p.setMaxVideoBitrate(Int.MAX_VALUE) } // auto
        }
        player.trackSelectionParameters = p.build()
    }

    private fun startWebServer() {
        try {
            webServer = WebConfigServer { action -> runOnUiThread { onWebAction(action) } }
            webServer?.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        } catch (_: Exception) { }
    }

    private fun onWebAction(action: String) {
        when (action) {
            "refresh_pl" -> forceRefreshPlaylists()
            "refresh_epg" -> forceRefreshEpg()
            else -> reloadFromStore(firstRun = false)
        }
    }

    override fun onStart() {
        super.onStart()
        if (playerReleased) {
            buildPlayer()
            currentChannel?.let { play(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        // п.1: пользователь вернулся после выдачи разрешения на установку — ставим сразу, без повтора скачивания
        if (awaitingInstall && UpdateManager.canInstall(this)) {
            awaitingInstall = false
            handler.postDelayed({
                UpdateManager.installDownloaded(this) { msg -> toast(msg) }
            }, 400)
        }
    }

    /** п.14: при сворачивании останавливаем звук и освобождаем видео-движок (приложение «засыпает»). */
    override fun onStop() {
        super.onStop()
        if (!isFinishing && !playerReleased) {
            try { player.release() } catch (_: Exception) {}
            playerReleased = true
        }
    }

    override fun onDestroy() {
        Quality.resetFrameRate(this)
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        try { webServer?.stop() } catch (_: Exception) { }
        if (!playerReleased) try { player.release() } catch (_: Exception) {}
    }

    // ------------------------------------------------------------ режим

    private fun setupModePicker() {
        val onFocus = View.OnFocusChangeListener { v, has -> if (has) { modeSelection = if (v.id == R.id.modeTv) "tv" else "phone"; highlightMode() } }
        modeTv.onFocusChangeListener = onFocus
        modePhone.onFocusChangeListener = onFocus
        modeTv.setOnClickListener { modeSelection = "tv"; highlightMode(); chooseMode("tv") }
        modePhone.setOnClickListener { modeSelection = "phone"; highlightMode(); chooseMode("phone") }
    }

    private fun showModePicker() {
        // адаптивное расположение: вертикально в портрете, горизонтально в альбоме
        val container = findViewById<LinearLayout>(R.id.modeButtons)
        container.orientation = if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT)
            LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        modePicker.visibility = View.VISIBLE
        highlightMode()
        (if (modeSelection == "tv") modeTv else modePhone).requestFocus()
    }

    private fun highlightMode() {
        modeTv.setBackgroundColor(if (modeSelection == "tv") Color.parseColor("#7aa61f") else Color.parseColor("#1D2530"))
        modePhone.setBackgroundColor(if (modeSelection == "phone") Color.parseColor("#7aa61f") else Color.parseColor("#1D2530"))
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
        // п.5: экран первичной настройки на телефоне
        findViewById<View>(R.id.psAddPlaylist).setOnClickListener { showManualUrlDialog(fromPhoneSetup = true) }
        findViewById<View>(R.id.psAddEpg).setOnClickListener { showManualEpgDialog(fromPhoneSetup = true) }
        findViewById<View>(R.id.psStart).setOnClickListener {
            if (playlists.isEmpty()) toast("Сначала добавьте рабочий плейлист")
            else { phoneSetup.visibility = View.GONE; if (currentChannel == null) restoreLastChannel(); applyMode() }
        }
    }

    // ------------------------------------------------------------ загрузка

    private fun forceRefreshPlaylists() {
        Store.clearPlaylistCaches()
        toast("Обновляю плейлисты…")
        reloadFromStore(firstRun = true)
    }

    private fun forceRefreshEpg() {
        toast("Обновляю программу…")
        val urls = ArrayList(Store.getEpgUrls())
        for (pl in playlists) if (pl.epgUrl.isNotEmpty() && pl.epgUrl !in urls) urls.add(pl.epgUrl)
        EpgManager.loadAsync(urls) { afterEpgLoaded() }
    }

    private fun reloadFromStore(firstRun: Boolean) {
        Thread {
            try {
                val cfgs = Store.getPlaylistCfgs().filter { !it.hidden }
                val loaded = ArrayList<Playlist>()
                val failed = ArrayList<String>()
                val epgUrls = ArrayList(Store.getEpgUrls())
                for (cfg in cfgs) {
                    try {
                        val file = Store.cachedFile(cfg.url)
                        val text: String = if (file.exists() && file.length() > 0 && !firstRun) file.readText()
                        else try { val t = Net.downloadText(cfg.url, 40 * 1024 * 1024); file.writeText(t); t }
                        catch (e: Throwable) { if (file.exists() && file.length() > 0) file.readText() else throw e }
                        val (channels, epg) = M3uParser.parse(text, cfg.name)
                        if (epg.isNotEmpty() && epg !in epgUrls) epgUrls.add(epg)
                        if (channels.isNotEmpty()) loaded.add(Playlist(cfg.name, cfg.url, channels, epg))
                        else failed.add(cfg.name)
                    } catch (_: Throwable) { failed.add(cfg.name) }
                }
                runOnUiThread {
                    playlists = loaded
                    if (playlists.isEmpty()) {
                        if (isPhone) showPhoneSetup() else showSetupOverlay()
                        if (failed.isNotEmpty()) toast("Не удалось загрузить: ${failed.joinToString(", ")}")
                    } else {
                        setupOverlay.visibility = View.GONE
                        phoneSetup.visibility = View.GONE
                        if (currentChannel == null) restoreLastChannel()
                        else rebuildZapList(keepCurrent = true)
                        applyMode()
                        if (failed.isNotEmpty()) toast("Не загрузились: ${failed.joinToString(", ")}")
                    }
                    if (epgUrls.isNotEmpty()) {
                        // если программа в кэше ещё свежая — не перегружаем по сети при обычном старте
                        // (иначе значки и EPG каждый раз пере-подкачиваются по 5 минут).
                        val fresh = EpgManager.loaded && EpgManager.cacheAgeMs() < EPG_FRESH_MS
                        if (fresh && !firstRun) afterEpgLoaded()
                        else EpgManager.loadAsync(epgUrls) { afterEpgLoaded() }
                    }
                }
            } catch (_: Throwable) {
                runOnUiThread { toast("Ошибка загрузки данных") }
            }
        }.start()
    }

    private fun afterEpgLoaded() {
        currentChannel?.let { if (panel == Panel.NONE && !playerReleased) updateOsdProgram(it) }
        if (panel == Panel.BROWSER) (browserListView.adapter as? ChannelAdapter)?.notifyDataSetChanged()
        if (searchOverlay.visibility == View.VISIBLE && searchInput.text.toString().trim().length >= 2) doSearch()
    }

    private fun showSetupOverlay() {
        val url = "http://${IpUtil.localIp()}:${WebConfigServer.PORT}"
        setupUrl.text = url
        showQr(url)
        setupOverlay.visibility = View.VISIBLE
        phoneBar.visibility = View.GONE
    }

    private fun showPhoneSetup() {
        updatePhoneSetupStatus()
        phoneSetup.visibility = View.VISIBLE
        phoneBar.visibility = View.GONE
    }

    private fun updatePhoneSetupStatus() {
        val pls = Store.getPlaylistCfgs().size
        val epg = Store.getEpgSources().size
        phoneSetupStatus.text = "Плейлистов: $pls   •   Источников EPG: $epg"
        findViewById<View>(R.id.psStart).alpha = if (playlists.isEmpty()) 0.5f else 1f
    }

    private fun showQr(text: String) {
        try {
            val size = 480
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) for (y in 0 until size)
                bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            qrImage.setImageBitmap(bmp)
            qrImage.visibility = View.VISIBLE
        } catch (_: Exception) { qrImage.visibility = View.GONE }
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
        if (playerReleased) buildPlayer()
        // запоминаем, откуда и что смотрели, до смены канала
        val prev = currentChannel
        if (prev != null && prev.url != ch.url) {
            prevRef = ChannelRef(curPlaylistIdx, curCategory, prev.url)
        }
        isCatchupPlayback = false
        currentChannel = ch
        errorMsg.visibility = View.GONE
        handler.removeCallbacks(errorZapRunnable)
        player.setMediaItem(Quality.mediaItem(ch.url))
        player.prepare()
        player.play()
        Store.lastChannelUrl = ch.url
        Store.bumpCount(ch.url)
        showOsd(ch)
    }

    private fun playCatchup(ch: Channel, url: String, title: String) {
        if (playerReleased) buildPlayer()
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

    /** Плашка целиком (информация + кнопки) прячется одним таймером. */
    private val hideOsdRunnable = Runnable { hideOsd() }

    private fun hideOsd() {
        osdPanel.visibility = View.GONE
        osdButtons.visibility = View.GONE
        osdButtonsShown = false
    }

    private var osdButtonsShown = false

    /** Сброс таймера авто-скрытия: любое действие продлевает показ. */
    private fun restartOsdTimer() {
        handler.removeCallbacks(hideOsdRunnable)
        handler.postDelayed(hideOsdRunnable, OSD_TIMEOUT_MS)
    }

    private fun showOsd(ch: Channel) = showOsd(ch, withButtons = false)

    /**
     * Единая плашка канала: одинаковая и при OK, и при перелистывании.
     * Отличие только в том, показывать ли кнопки действий.
     */
    private fun showOsd(ch: Channel, withButtons: Boolean) {
        val num = if (ch.chno.isNotEmpty()) ch.chno else (zapIndex + 1).toString()
        osdChannel.text = "$num • ${ch.name}"
        osdClock.text = timeFmt.format(Date())

        val up = ch.name.uppercase()
        when {
            up.contains("4K") || up.contains("UHD") -> { osdBadge.text = "4K"; osdBadge.visibility = View.VISIBLE }
            up.contains("HD") -> { osdBadge.text = "HD"; osdBadge.visibility = View.VISIBLE }
            else -> osdBadge.visibility = View.GONE
        }
        // индикатор эфира живёт только внутри плашки и только для живого потока
        osdLive.visibility = if (isCatchupPlayback) View.GONE else View.VISIBLE

        ImageLoader.load(if (ch.logo.isNotEmpty()) ch.logo else EpgManager.iconFor(ch), osdLogo)
        updateOsdProgram(ch)

        if (osdPanel.visibility != View.VISIBLE) {
            osdPanel.visibility = View.VISIBLE
            animOsdUp(osdPanel)
        }
        if (withButtons) showOsdButtons() else { osdButtons.visibility = View.GONE; osdButtonsShown = false }
        restartOsdTimer()
    }

    private fun showOsdButtons() {
        updateFavButtonText()
        osdButtons.visibility = View.VISIBLE
        osdButtonsShown = true
        osdBtnFav.requestFocus()
    }

    /** Текст кнопки избранного; ширина кнопки фиксирована, соседние не смещаются. */
    private fun updateFavButtonText() {
        val ch = currentChannel
        osdBtnFav.text = if (ch != null && Store.isFavorite(ch.url)) "Из избранного" else "В избранное"
    }

    private fun setupOsdButtons() {
        osdBtnFav.setOnClickListener {
            toggleFavoriteCurrent(); updateFavButtonText(); restartOsdTimer()
        }
        osdBtnPrev.setOnClickListener { zap(-1); restartOsdTimer() }
        osdBtnNext.setOnClickListener { zap(1); restartOsdTimer() }
        osdBtnLast.setOnClickListener { gotoPrevChannel(); restartOsdTimer() }
        // навигация по кнопкам продлевает показ плашки
        val keepAlive = View.OnFocusChangeListener { _, hasFocus -> if (hasFocus) restartOsdTimer() }
        osdBtnFav.onFocusChangeListener = keepAlive
        osdBtnPrev.onFocusChangeListener = keepAlive
        osdBtnNext.onFocusChangeListener = keepAlive
        osdBtnLast.onFocusChangeListener = keepAlive
    }

    /** Переход на прошлый просмотренный канал — вместе с его плейлистом и группой. */
    private fun gotoPrevChannel() {
        val ref = prevRef
        if (ref == null) { toast("Прошлый канал не запомнен"); return }
        val backTo = currentChannel?.let { ChannelRef(curPlaylistIdx, curCategory, it.url) }

        curPlaylistIdx = ref.plIdx
        curCategory = ref.category
        rebuildZapList(keepCurrent = false)
        val idx = zapList.indexOfFirst { it.url == ref.url }
        if (idx < 0) { toast("Прошлый канал недоступен"); return }
        zapIndex = idx
        play(zapList[idx])
        // меняем местами: следующий вызов вернёт туда, откуда пришли
        prevRef = backTo
        showOsd(zapList[idx], withButtons = osdButtonsShown)
    }

    private fun updateOsdProgram(ch: Channel) {
        val prog = EpgManager.currentFor(ch)
        if (prog != null) {
            osdProgram.text = "Сейчас: ${prog.title}"
            val pct = ((System.currentTimeMillis() - prog.start) * 100 / maxOf(1, prog.stop - prog.start)).toInt()
            osdProgress.progress = pct.coerceIn(0, 100)
            osdProgress.visibility = View.VISIBLE
            osdRemain.text = remainText(prog.stop)
            osdRemain.visibility = View.VISIBLE
            val next = EpgManager.nextFor(ch)
            if (next != null) {
                osdNext.text = "Далее: ${next.title} · ${timeFmt.format(Date(next.start))}"
                osdNext.visibility = View.VISIBLE
            } else osdNext.visibility = View.GONE
        } else {
            osdProgram.text = if (Store.isFavorite(ch.url)) "★ В избранном" else ""
            osdProgress.visibility = View.INVISIBLE
            osdRemain.visibility = View.GONE
            osdNext.visibility = View.GONE
        }
    }

    private val clockTick = object : Runnable {
        override fun run() {
            if (osdPanel.visibility == View.VISIBLE) osdClock.text = timeFmt.format(Date())
            handler.postDelayed(this, 1000)
        }
    }

    private val epgRefreshTick = object : Runnable {
        override fun run() {
            val urls = ArrayList(Store.getEpgUrls())
            for (pl in playlists) if (pl.epgUrl.isNotEmpty() && pl.epgUrl !in urls) urls.add(pl.epgUrl)
            if (urls.isNotEmpty()) EpgManager.loadAsync(urls) { afterEpgLoaded() }
            handler.postDelayed(this, 3L * 3600 * 1000)
        }
    }

    private fun remainText(stop: Long): String {
        val mins = ((stop - System.currentTimeMillis()) / 60000).toInt()
        return if (mins > 0) "осталось $mins мин" else "заканчивается"
    }

    // ------------------------------------------------------------ клавиши

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (modePicker.visibility == View.VISIBLE) {
            if (event.action == KeyEvent.ACTION_DOWN &&
                (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)) {
                chooseMode(modeSelection); return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (phoneSetup.visibility == View.VISIBLE) return super.dispatchKeyEvent(event)
        if (searchOverlay.visibility == View.VISIBLE) {
            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
                closeSearch(); return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (screensaver.visibility == View.VISIBLE) {
            if (event.action == KeyEvent.ACTION_DOWN) hideScreensaver()
            return true
        }
        if (setupOverlay.visibility == View.VISIBLE) {
            if (event.action == KeyEvent.ACTION_DOWN &&
                (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)) {
                showManualUrlDialog(fromPhoneSetup = false); return true
            }
            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK && playlists.isNotEmpty()) {
                setupOverlay.visibility = View.GONE; return true
            }
            return super.dispatchKeyEvent(event)
        }

        return when (panel) {
            Panel.NONE -> handleFullscreenKey(event)
            Panel.LEFT -> {
                // Верхний неподвижный блок (Настройки/Поиск/Избранное + плейлист)
                // обрабатывает свои клавиши сам через setOnKeyListener в setupLeftFixedRows,
                // здесь остаётся только логика для catList (категории каналов) и общее.
                val focus = currentFocus
                val onCatList = focus === catList || focus?.parent === catList
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (onCatList) { closePanels(); return true }
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                            if (onCatList) {
                                val pos = catList.selectedItemPosition
                                if (pos >= 0) { activateCatOnly(pos); return true }
                            }
                        }
                        KeyEvent.KEYCODE_BACK -> { closePanels(); return true }
                        // п.3: удержание «Вверх» ~1 сек — прыжок на пункт «Настройки»
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            if (event.repeatCount == 0) {
                                upHeldFired = false
                                handler.postDelayed(upHoldRunnable, 1000)
                            }
                            return super.dispatchKeyEvent(event)
                        }
                    }
                }
                if (event.action == KeyEvent.ACTION_UP && event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    handler.removeCallbacks(upHoldRunnable)
                }
                super.dispatchKeyEvent(event)
            }
            Panel.BROWSER -> handleBrowserKey(event)
            Panel.RIGHT -> {
                if (event.action == KeyEvent.ACTION_DOWN &&
                    (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                    closePanels(); return true
                }
                super.dispatchKeyEvent(event)
            }
            Panel.SETTINGS -> handleSettingsKey(event)
        }
    }

    private fun handleFullscreenKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_DOWN) {
            // пока кнопки плашки на экране — стрелки ходят по ним, а не переключают каналы
            if (osdButtonsShown && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                restartOsdTimer()
                return super.dispatchKeyEvent(event)
            }
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { zap(1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(-1); return true }
                // п.2: влево открывает тот список, откуда запущен канал
                KeyEvent.KEYCODE_DPAD_LEFT -> { hideOsd(); openChannelBrowser(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { hideOsd(); openEpgPanel(); return true }
                KeyEvent.KEYCODE_MENU -> { openSettingsPanel(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { player.playWhenReady = !player.playWhenReady; return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    // если фокус уже на кнопке плашки — обычное нажатие кнопки
                    if (osdButtonsShown && isOsdButtonFocused()) return super.dispatchKeyEvent(event)
                    if (event.repeatCount == 0) {
                        okPressed = true; longPressFired = false
                        handler.postDelayed(longPressRunnable, 900)
                    }
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    // «Назад» при открытой плашке скрывает её целиком: и кнопки, и информацию
                    if (osdPanel.visibility == View.VISIBLE) {
                        handler.removeCallbacks(hideOsdRunnable)
                        hideOsd()
                        return true
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastBackTs < 2500) finish()
                    else { lastBackTs = now; toast("Нажмите НАЗАД ещё раз для выхода") }
                    return true
                }
            }
        } else if (event.action == KeyEvent.ACTION_UP &&
            (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)) {
            if (osdButtonsShown && isOsdButtonFocused()) return super.dispatchKeyEvent(event)
            if (okPressed) {
                okPressed = false
                handler.removeCallbacks(longPressRunnable)
                // короткое нажатие OK — показать плашку с кнопками действий
                if (!longPressFired) currentChannel?.let { showOsd(it, withButtons = true) }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private val OSD_BUTTON_IDS = setOf(R.id.osdBtnFav, R.id.osdBtnPrev, R.id.osdBtnNext, R.id.osdBtnLast)

    /** Фокус сейчас на одной из кнопок плашки? */
    private fun isOsdButtonFocused(): Boolean {
        val id = currentFocus?.id ?: return false
        return id in OSD_BUTTON_IDS
    }

    private var upHeldFired = false

    /** Прыжок к «Настройкам» из любого места списка категорий. */
    private val upHoldRunnable = Runnable {
        upHeldFired = true
        if (panel == Panel.LEFT && !menuCollapsed) {
            settingsRow.requestFocus()
        }
    }

    /** Удержание OK ~1 сек — переход к прошлому просмотренному каналу. */
    private val longPressRunnable = Runnable { longPressFired = true; gotoPrevChannel() }

    // ------------------------------------------------------------ панели

    private fun closePanels() {
        browserOverlay.visibility = View.GONE
        leftMenu.visibility = View.GONE
        rightPanel.visibility = View.GONE
        if (::settingsPanel.isInitialized) settingsPanel.visibility = View.GONE
        if (::leftMenu.isInitialized) leftMenu.visibility = View.GONE
        setDetailActive = false
        panel = Panel.NONE
        if (isPhone && currentChannel != null) phoneBar.visibility = View.VISIBLE
    }

    /**
     * Свернуть левое меню в рейку — класс .collapsed из прототипа.
     * Рейка и меню это ОДИН элемент: при сворачивании ширина плавно едет
     * 392dp -> 88dp, отступы 16dp -> 10dp, строки переключаются в режим
     * «только иконка». Тот же свёрнутый вид используется поверх браузера
     * каналов, поиска и настроек.
     */
    private fun showRail(activeOverrideType: String? = null) {
        val wasVisible = leftMenu.visibility == View.VISIBLE
        leftMenu.visibility = View.VISIBLE
        menuCollapsed = true
        // в рейке верхний неподвижный блок не показывается — там только полоска иконок
        if (::leftFixedTop.isInitialized) leftFixedTop.visibility = View.GONE
        refreshRail(activeOverrideType)
        // фокус рейка не забирает — это индикатор, а не зона навигации
        catList.isFocusable = false
        catList.isFocusableInTouchMode = false
        catList.isEnabled = false
        if (wasVisible) {
            // меню уже на экране (открыли раздел из него) — плавно схлопываем
            animateMenuWidth(dp(MENU_W_RAIL), dp(MENU_PAD_RAIL))
        } else {
            setMenuWidth(dp(MENU_W_RAIL), dp(MENU_PAD_RAIL))
            animPanelInLeft(leftMenu)
        }
    }

    /** Развернуть меню обратно в полный вид. */
    private fun expandMenu(animate: Boolean) {
        menuCollapsed = false
        if (::leftFixedTop.isInitialized) leftFixedTop.visibility = View.VISIBLE
        catList.isFocusable = true
        catList.isFocusableInTouchMode = true
        catList.isEnabled = true
        if (animate) animateMenuWidth(dp(MENU_W_FULL), dp(MENU_PAD_FULL))
        else setMenuWidth(dp(MENU_W_FULL), dp(MENU_PAD_FULL))
    }

    private fun setMenuWidth(w: Int, pad: Int) {
        menuWidthAnim?.cancel()
        leftMenu.layoutParams = leftMenu.layoutParams.apply { width = w }
        leftMenu.setPadding(pad, leftMenu.paddingTop, pad, leftMenu.paddingBottom)
        leftMenu.requestLayout()
    }

    /** Плавное изменение ширины и отступов — transition:width .36s из прототипа. */
    private fun animateMenuWidth(toW: Int, toPad: Int) {
        menuWidthAnim?.cancel()
        val fromW = leftMenu.width.takeIf { it > 0 } ?: leftMenu.layoutParams.width
        val fromPad = leftMenu.paddingLeft
        if (fromW == toW && fromPad == toPad) return
        val a = android.animation.ValueAnimator.ofFloat(0f, 1f)
        a.duration = 360
        a.interpolator = easeOut
        a.addUpdateListener { anim ->
            val t = anim.animatedValue as Float
            val w = (fromW + (toW - fromW) * t).toInt()
            val pad = (fromPad + (toPad - fromPad) * t).toInt()
            leftMenu.layoutParams = leftMenu.layoutParams.apply { width = w }
            leftMenu.setPadding(pad, leftMenu.paddingTop, pad, leftMenu.paddingBottom)
            leftMenu.requestLayout()
        }
        menuWidthAnim = a
        a.start()
    }

    private fun openChannelBrowser() {
        if (zapList.isEmpty()) { openLeftMenu(); return }
        panel = Panel.BROWSER
        phoneBar.visibility = View.GONE
        channelBeforeBrowse = currentChannel
        browserChannels = zapList
        browserHeader.text = currentZapTitle()
        // имя плейлиста над заголовком — в верхнем регистре, как в прототипе
        browserPlName.text = (if (curPlaylistIdx == -1) "Избранное"
            else playlists.getOrNull(curPlaylistIdx)?.name ?: "").uppercase()
        val adapter = ChannelAdapter(this, browserChannels, showNow = true)
        browserListView.adapter = adapter
        browserListView.setOnItemClickListener { _, _, pos, _ ->
            zapIndex = pos
            play(browserChannels[pos])
            closePanels()
        }
        browserListView.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                (browserListView.adapter as? ChannelAdapter)?.selectedPos = pos
                updatePreview(browserChannels.getOrNull(pos))
                if (Store.livePreview) scheduleLivePreview(browserChannels.getOrNull(pos))
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        })
        browserActionFocused = false
        adapter.actionFocused = false
        browserListView.setSelector(R.drawable.list_focus)
        browserOverlay.visibility = View.VISIBLE
        animFadeIn(browserOverlay)
        showRail()
        animRailIn(browserList)
        animOsdUp(previewCard)
        browserListView.requestFocus()
        val start = browserChannels.indexOfFirst { it.url == currentChannel?.url }.coerceAtLeast(0)
        adapter.selectedPos = start
        browserListView.setSelection(start)
        updatePreview(browserChannels.getOrNull(start))
    }

    /**
     * Свёрнутая рейка слева от списка каналов: только иконки категорий,
     * текущая подсвечена. Фокус на рейку не переводится — она показывает,
     * где мы находимся; чтобы сменить категорию, нажимаем «влево» и
     * попадаем в развёрнутое меню.
     */
    private fun refreshRail(activeOverrideType: String? = null) {
        // -1 = «Избранное»: это не плейлист, поэтому категории берём
        // из последнего реального плейлиста, иначе рейка осталась бы пустой
        val plIdx = if (curPlaylistIdx >= 0) curPlaylistIdx else lastRealPlaylistIdx
        val items = buildCatItems(plIdx)
        val adapter = CategoryAdapter(this, items, compact = true)
        // подсвечиваем текущую категорию: либо служебный пункт (настройки/поиск),
        // либо активная группа каналов
        val active = when {
            activeOverrideType != null -> items.indexOfFirst { it.type == activeOverrideType }
            curPlaylistIdx == -1 -> items.indexOfFirst { it.type == "FAV" }
            curCategory == null -> items.indexOfFirst { it.type == "ALL" }
            else -> items.indexOfFirst { it.type == "GROUP" && it.group == curCategory }
        }
        adapter.activePos = active
        catItemsList = items
        catList.adapter = adapter
        if (active >= 0) catList.setSelection(active)
    }

    /**
     * Клавиши в списке каналов (п.4 и п.5 спецификации).
     * На канале: вправо — фокус на кнопку действия, OK — смотреть.
     * На кнопке: вправо — смотреть, влево — назад на канал, OK — избранное.
     */
    /**
     * Переключить фокус на кнопку действия строки (звезда/корзина) и обратно.
     * Синхронно с этим строка гасит своё обычное свечение (состояние
     * "delactive" из прототипа) — иначе выглядело бы так, будто подсвечены
     * сразу два элемента (строка целиком и кнопка на ней).
     */
    private fun setBrowserActionFocused(focused: Boolean) {
        browserActionFocused = focused
        (browserListView.adapter as? ChannelAdapter)?.actionFocused = focused
        browserListView.setSelector(if (focused) R.drawable.list_focus_delactive else R.drawable.list_focus)
    }

    private fun handleBrowserKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val pos = browserListView.selectedItemPosition

        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!browserActionFocused) {
                    if (pos >= 0 && pos < browserChannels.size) {
                        setBrowserActionFocused(true)
                    }
                } else {
                    // с кнопки вправо — запуск просмотра
                    if (pos >= 0 && pos < browserChannels.size) {
                        zapIndex = pos
                        play(browserChannels[pos])
                        closePanels()
                    }
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (browserActionFocused) {
                    setBrowserActionFocused(false)
                    return true
                }
                handler.removeCallbacks(livePreviewRunnable)
                browserOverlay.visibility = View.GONE
                openLeftMenu()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (browserActionFocused) {
                    if (pos >= 0 && pos < browserChannels.size) toggleFavoriteAt(pos)
                    return true
                }
            }
            KeyEvent.KEYCODE_BACK -> {
                if (browserActionFocused) {
                    setBrowserActionFocused(false)
                    return true
                }
                cancelBrowse()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                // при движении по списку фокус кнопки снимается
                if (browserActionFocused) {
                    setBrowserActionFocused(false)
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Добавить/убрать канал из избранного прямо из списка. */
    private fun toggleFavoriteAt(pos: Int) {
        val ch = browserChannels.getOrNull(pos) ?: return
        val added = Store.toggleFavorite(ch.url, ch.name)
        toast(if (added) "Добавлено в Избранное" else "Удалено из Избранного")

        if (curPlaylistIdx == -1) {
            // в разделе «Избранное» удалённый канал исчезает из списка
            rebuildZapList(keepCurrent = true)
            browserChannels = zapList
            val adapter = ChannelAdapter(this, browserChannels, showNow = true)
            browserListView.adapter = adapter
            if (browserChannels.isEmpty()) {
                browserActionFocused = false
                toast("В избранном пусто")
                cancelBrowse()
                return
            }
            val np = pos.coerceAtMost(browserChannels.size - 1)
            adapter.selectedPos = np
            adapter.actionFocused = browserActionFocused
            browserListView.setSelection(np)
            updatePreview(browserChannels.getOrNull(np))
        } else {
            (browserListView.adapter as? ChannelAdapter)?.notifyDataSetChanged()
        }
    }

    private fun cancelBrowse() {
        handler.removeCallbacks(livePreviewRunnable)
        if (Store.livePreview && !isCatchupPlayback &&
            channelBeforeBrowse != null && currentChannel?.url != channelBeforeBrowse?.url) {
            channelBeforeBrowse?.let { play(it) }
        }
        // как в прототипе: список и карточка превью уезжают под рейку, потом панель гаснет
        closeWithRailOut(browserList, previewCard) { closePanels() }
    }

    private fun updatePreview(ch: Channel?) {
        if (ch == null) return
        prevName.text = ch.name
        ImageLoader.load(if (ch.logo.isNotEmpty()) ch.logo else EpgManager.iconFor(ch), prevLogo)
        val cur = EpgManager.currentFor(ch)
        val next = EpgManager.nextFor(ch)
        if (cur != null) {
            prevNow.text = cur.title
            val pct = ((System.currentTimeMillis() - cur.start) * 100 / maxOf(1, cur.stop - cur.start)).toInt()
            prevProgress.progress = pct.coerceIn(0, 100)
            prevProgress.visibility = View.VISIBLE
            prevRemain.text = "${timeFmt.format(Date(cur.start))}–${timeFmt.format(Date(cur.stop))}  •  ${remainText(cur.stop)}"
            prevRemain.visibility = View.VISIBLE
        } else {
            prevNow.text = if (EpgManager.loading) "Программа загружается…" else "Нет данных о передаче"
            prevProgress.visibility = View.INVISIBLE
            prevRemain.visibility = View.GONE
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
            if (playerReleased) buildPlayer()
            player.setMediaItem(MediaItem.fromUri(ch.url)); player.prepare(); player.play()
            currentChannel = ch
        }
    }

    private fun currentZapTitle(): String = when {
        curPlaylistIdx == -1 -> "Избранное"
        curCategory == null -> "Все каналы"
        else -> curCategory ?: "Все каналы"
    }

    // --- Левое меню ---

    private fun openLeftMenu() {
        panel = Panel.LEFT
        phoneBar.visibility = View.GONE
        val wasCollapsed = menuCollapsed && leftMenu.visibility == View.VISIBLE
        // -1 = сейчас смотрим «Избранное»; это не плейлист, поэтому в меню
        // показываем последний реальный плейлист
        menuPlaylistIdx = if (curPlaylistIdx >= 0) curPlaylistIdx else lastRealPlaylistIdx
        if (menuPlaylistIdx > playlists.size - 1) menuPlaylistIdx = 0
        if (menuPlaylistIdx < 0) menuPlaylistIdx = 0
        refreshLeftMenu()
        leftMenu.visibility = View.VISIBLE
        // если меню уже висело рейкой — плавно разворачиваем, иначе въезжаем слева
        expandMenu(animate = wasCollapsed)
        if (!wasCollapsed) animPanelInLeft(leftMenu)
        // Фокус — на текущем разделе. Верхний блок теперь неподвижен, поэтому
        // Избранное фокусируется прямо на favRow, а категории — в catList.
        when {
            curPlaylistIdx == -1 -> favRow.requestFocus()
            curCategory == null -> {
                catList.requestFocus()
                val idx = catOnlyList.indexOfFirst { it.type == "ALL" }
                if (idx >= 0) catList.setSelection(idx)
            }
            else -> {
                catList.requestFocus()
                val idx = catOnlyList.indexOfFirst { it.type == "GROUP" && it.group == curCategory }
                if (idx >= 0) catList.setSelection(idx)
            }
        }
    }

    private fun cycleMenuPlaylist(dir: Int) {
        val max = playlists.size - 1
        if (max < 0) return
        menuPlaylistIdx += dir
        if (menuPlaylistIdx < 0) menuPlaylistIdx = max
        if (menuPlaylistIdx > max) menuPlaylistIdx = 0
        // Обновляем содержимое: имя плейлиста в таблетке + список категорий.
        // Явно НЕ трогаем фокус — иначе после ←/→ на плейлисте курсор бы
        // прыгал в catList и приходилось бы возвращаться стрелкой вверх.
        refreshLeftMenu()
        if (panel == Panel.LEFT && !menuCollapsed) plSelFixed.requestFocus()
    }

    /**
     * Пункты левого меню — порядок ровно как в прототипе (catItems()):
     *   верхний блок: Настройки, Поиск передачи, Избранное (со счётчиком);
     *   затем строка-«таблетка» выбора плейлиста (PLSEL);
     *   затем Все каналы + группы выбранного плейлиста.
     *
     * Используется рейкой (compact-адаптер показывает все иконки в один столбец)
     * и как источник данных для `activateCat(pos)` через `catItemsList`. В самом
     * же catList (полное меню) остаются ТОЛЬКО категории — верхний блок теперь
     * неподвижен при скролле (см. left-menu-pro-mark.jpg).
     */
    private fun buildCatItems(plIdx: Int): List<CatItem> {
        val items = ArrayList<CatItem>()
        items.add(CatItem("Настройки", 0, "SETTINGS"))
        items.add(CatItem("Поиск передачи", 0, "SEARCH"))
        items.add(CatItem("Избранное", favoriteChannels().size, "FAV"))
        val plName = playlists.getOrNull(plIdx)?.name ?: "—"
        items.add(CatItem(plName, 0, "PLSEL"))
        val pl = playlists.getOrNull(plIdx)
        if (pl != null) {
            items.add(CatItem("Все каналы", pl.channels.size, "ALL"))
            val counts = LinkedHashMap<String, Int>()
            for (c in pl.channels) counts[c.group] = (counts[c.group] ?: 0) + 1
            for ((g, n) in counts) items.add(CatItem(g, n, "GROUP", g))
        }
        return items
    }

    /** Только категории каналов — то, что реально попадает в прокручиваемый catList. */
    private fun buildCatOnlyList(plIdx: Int): List<CatItem> {
        val pl = playlists.getOrNull(plIdx) ?: return emptyList()
        val list = ArrayList<CatItem>()
        list.add(CatItem("Все каналы", pl.channels.size, "ALL"))
        val counts = LinkedHashMap<String, Int>()
        for (c in pl.channels) counts[c.group] = (counts[c.group] ?: 0) + 1
        for ((g, n) in counts) list.add(CatItem(g, n, "GROUP", g))
        return list
    }

    /**
     * Настройка неподвижных строк верхнего блока левого меню. Вызывается один
     * раз из onCreate: заполняет иконки и подписи, включает focusable, вешает
     * обработчики клика (OK/тап) и стрелок (←/→/BACK). В `refreshLeftMenu()`
     * потом обновляем только динамические данные (счётчик избранного, имя
     * плейлиста).
     */
    private fun setupLeftFixedRows() {
        // Настройки
        IconFont.apply(settingsRow.findViewById(R.id.catIcon), "settings")
        settingsRow.findViewById<TextView>(R.id.catName).text = "Настройки"
        settingsRow.findViewById<TextView>(R.id.catCount).visibility = View.GONE
        prepareFixedRow(settingsRow) { openSettingsPanel() }

        // Поиск передачи
        IconFont.apply(searchRow.findViewById(R.id.catIcon), "search")
        searchRow.findViewById<TextView>(R.id.catName).text = "Поиск передачи"
        searchRow.findViewById<TextView>(R.id.catCount).visibility = View.GONE
        prepareFixedRow(searchRow) { openSearch() }

        // Избранное (счётчик обновляется в updateFixedTop)
        IconFont.apply(favRow.findViewById(R.id.catIcon), "star")
        favRow.findViewById<TextView>(R.id.catName).text = "Избранное"
        prepareFixedRow(favRow) { selectFavorites() }

        // Таблетка «Мой плейлист» — фокусируемая, ←/→ листают плейлист,
        // OK тоже листает вперёд (как в прототипе)
        plSelFixed.isFocusable = true
        plSelFixed.isFocusableInTouchMode = false
        plSelFixed.setOnClickListener { cycleMenuPlaylist(1) }
        plSelFixed.setOnFocusChangeListener { _, hasFocus ->
            // визуально подсветка живёт на внутренней таблетке (drawable/plsel_bg),
            // чтобы разделитель и подпись «смена плейлиста» не подсвечивались
            plSelFixed.findViewById<View>(R.id.plSelRow)?.isSelected = hasFocus
        }
        plSelFixed.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { cycleMenuPlaylist(-1); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { cycleMenuPlaylist(1); true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { cycleMenuPlaylist(1); true }
                KeyEvent.KEYCODE_BACK -> { closePanels(); true }
                else -> false
            }
        }
    }

    /**
     * Общая настройка неподвижной строки верхнего блока (Настройки/Поиск/Избранное):
     * фокусируемость, подложка list_focus (те же halo-обводки, что у строк catList),
     * клик по OK/тапу и обработка стрелок ←/BACK — закрывают меню, →/OK — открывают.
     */
    private fun prepareFixedRow(row: View, action: () -> Unit) {
        row.isFocusable = true
        row.isFocusableInTouchMode = false
        row.isClickable = true
        row.setBackgroundResource(R.drawable.list_focus)
        row.setOnClickListener { action() }
        row.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { closePanels(); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { action(); true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { action(); true }
                KeyEvent.KEYCODE_BACK -> { closePanels(); true }
                else -> false
            }
        }
    }

    /** Обновление динамических данных верхнего блока (счётчик избранного, имя плейлиста). */
    private fun updateFixedTop() {
        val favCnt = favoriteChannels().size
        val favCountView = favRow.findViewById<TextView>(R.id.catCount)
        if (favCnt > 0) {
            favCountView.text = favCnt.toString()
            favCountView.visibility = View.VISIBLE
        } else {
            favCountView.visibility = View.GONE
        }
        val plName = playlists.getOrNull(menuPlaylistIdx)?.name ?: "—"
        plSelFixedName.text = plName
    }

    private fun refreshLeftMenu() {
        // catItemsList — полный список, нужен рейке и activateCat(pos) при кликах
        catItemsList = buildCatItems(menuPlaylistIdx)
        // catList теперь содержит ТОЛЬКО категории (верхний блок неподвижен)
        catOnlyList = buildCatOnlyList(menuPlaylistIdx)
        catList.adapter = CategoryAdapter(this, catOnlyList)
        catList.setOnItemClickListener { _, _, pos, _ -> activateCatOnly(pos) }
        updateFixedTop()
    }

    /** Клик по строке catList (в нём остались только категории каналов). */
    private fun activateCatOnly(pos: Int) {
        val item = catOnlyList.getOrNull(pos) ?: return
        when (item.type) {
            "ALL" -> selectCategory(null)
            "GROUP" -> selectCategory(item.group)
        }
    }

    /** Открыть пункт левого меню (клик мышью или OK/вправо с пульта). */
    private fun activateCat(pos: Int) {
        val item = catItemsList.getOrNull(pos) ?: return
        when (item.type) {
            "SETTINGS" -> openSettingsPanel()
            "SEARCH" -> openSearch()
            "FAV" -> selectFavorites()
            "PLSEL" -> cycleMenuPlaylist(1)
            "ALL" -> selectCategory(null)
            "GROUP" -> selectCategory(item.group)
        }
    }

    /** Избранное — отдельный пункт верхнего блока, как в прототипе. */
    private fun selectFavorites() {
        curPlaylistIdx = -1
        curCategory = null
        rebuildZapList(keepCurrent = false)
        if (zapList.isNotEmpty()) {
            val keep = zapList.indexOfFirst { it.url == currentChannel?.url }
            zapIndex = if (keep >= 0) keep else 0
            openChannelBrowser()
        } else { panel = Panel.NONE; toast("В избранном пока пусто") }
    }

    private fun selectCategory(group: String?) {
        curPlaylistIdx = menuPlaylistIdx
        lastRealPlaylistIdx = menuPlaylistIdx
        curCategory = group
        rebuildZapList(keepCurrent = false)
        // меню НЕ прячем: openChannelBrowser() свернёт его в рейку с анимацией,
        // как переход .collapsed в прототипе
        if (zapList.isNotEmpty()) {
            val keep = zapList.indexOfFirst { it.url == currentChannel?.url }
            zapIndex = if (keep >= 0) keep else 0
            openChannelBrowser()
        } else { panel = Panel.NONE; toast("В этом разделе нет каналов") }
    }

    // --- Правая панель EPG (п.4, п.11, п.16 без напоминаний) ---

    private fun openEpgPanel(targetStart: Long = -1L) {
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
            nowRemain.text = remainText(cur.stop)
            nowRemain.visibility = View.VISIBLE
            nowDesc.text = cur.desc
            nowDesc.visibility = if (cur.desc.isEmpty()) View.GONE else View.VISIBLE
        } else {
            nowTitle.text = if (EpgManager.loading) "Программа загружается…" else "Нет данных о текущей передаче"
            nowTime.text = ""; nowProgress.visibility = View.INVISIBLE; nowRemain.visibility = View.GONE; nowDesc.visibility = View.GONE
        }

        val canArc = CatchupHelper.canCatchup(ch)
        val arcDays = if (ch.catchupDays > 0) ch.catchupDays else 7
        val arcFrom = now - arcDays.toLong() * 24 * 3600 * 1000
        if (progs.isEmpty()) {
            val hint = if (EpgManager.loaded) listOf("Для этого канала нет программы передач")
            else listOf(EpgManager.status(), "Добавьте источник EPG с телефона", "или в Настройках на ТВ")
            epgList.adapter = ArrayAdapter(this, R.layout.item_row, hint)
            epgList.setOnItemClickListener { _, _, _, _ -> closePanels() }
        } else {
            // п.5: помечаем «▶ … · архив» только те прошедшие передачи, что реально доступны
            // в архиве канала (попадают в окно catchup-days).
            val labels = progs.map { p ->
                val t = timeFmt.format(Date(p.start))
                when {
                    now in p.start until p.stop -> "● $t  ${p.title}   · эфир"
                    p.start > now -> "    $t  ${p.title}"
                    else -> {
                        val inArc = canArc && p.start >= arcFrom
                        (if (inArc) "▶ " else "    ") + "$t  ${p.title}" + (if (inArc) "   · архив" else "")
                    }
                }
            }
            epgList.adapter = ArrayAdapter(this, R.layout.item_row, labels)
            epgList.setOnItemClickListener { _, _, pos, _ ->
                val p = progs[pos]
                when {
                    now in p.start until p.stop -> closePanels()
                    p.start > now -> closePanels()   // для будущих передач — никаких напоминаний
                    else -> {
                        val inArc = canArc && p.start >= arcFrom
                        if (inArc) { closePanels(); playCatchup(ch, CatchupHelper.buildUrl(ch, p.start / 1000, p.stop / 1000), p.title) }
                        else toast(if (canArc) "Передача вне доступного архива (хранится $arcDays дн.)" else "Архив для этого канала недоступен")
                    }
                }
            }
        }
        rightPanel.visibility = View.VISIBLE
        animPanelInRight(rightPanel)
        epgList.requestFocus()
        val sel = if (targetStart > 0) progs.indexOfFirst { it.start == targetStart }
                  else progs.indexOfFirst { now in it.start until it.stop }
        if (sel >= 0) epgList.setSelection(maxOf(0, sel - 2))
    }

    // ------------------------------------------------------------ поиск передач (п.17)

    private fun setupSearch() {
        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                handler.removeCallbacks(searchRunnable)
                handler.postDelayed(searchRunnable, 350)
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        // п.4: по «лупе»/Enter на ТВ прячем полноэкранную клавиатуру (она перекрывала
        // результаты) и переводим фокус на список найденного.
        searchInput.setOnEditorActionListener { _, _, _ ->
            hideSearchKeyboard()
            focusResultsAfterSearch = true
            handler.removeCallbacks(searchRunnable)
            doSearch()
            true
        }
    }

    private fun hideSearchKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    private fun openSearch() {
        // не через closePanels(): меню должно остаться и свернуться в рейку
        browserOverlay.visibility = View.GONE
        rightPanel.visibility = View.GONE
        if (::settingsPanel.isInitialized) settingsPanel.visibility = View.GONE
        panel = Panel.NONE
        phoneBar.visibility = View.GONE
        focusResultsAfterSearch = false
        searchInput.setText("")
        searchRows = emptyList()
        searchResults.adapter = null
        searchStatus.text = "Введите название. Поиск идёт по идущим сейчас, будущим и доступным в архиве передачам."
        searchOverlay.visibility = View.VISIBLE
        animFadeIn(searchOverlay)
        animRailIn(searchContent)
        showRail("SEARCH")
        searchInput.requestFocus()
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.showSoftInput(searchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeSearch() {
        handler.removeCallbacks(searchRunnable)
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(searchInput.windowToken, 0)
        // rail-closing из прототипа: содержимое уходит под рейку, затем всё гаснет
        closeWithRailOut(searchContent) {
            searchOverlay.visibility = View.GONE
            if (::leftMenu.isInitialized) leftMenu.visibility = View.GONE
            if (isPhone && currentChannel != null) phoneBar.visibility = View.VISIBLE
        }
    }

    private fun allChannels(): List<Channel> {
        val all = ArrayList<Channel>()
        val seen = HashSet<String>()
        for (pl in playlists) for (c in pl.channels) if (seen.add(c.url)) all.add(c)
        return all
    }

    private fun doSearch() {
        val q = searchInput.text.toString().trim()
        if (q.length < 2) {
            searchStatus.text = "Введите хотя бы 2 символа"; searchResults.adapter = null; return
        }
        if (EpgManager.programCount == 0) {
            searchStatus.text = "Телепрограмма ещё не загружена (${EpgManager.status()}). Поиск запустится сам, как только она загрузится."
            searchResults.adapter = null
            return
        }
        searchStatus.text = "Поиск…"
        val channels = allChannels()
        val seq = ++searchSeq
        Thread {
            val rows = ArrayList<SearchRow>()
            var count = 0
            var matched = 0
            var err: String? = null
            try {
                matched = EpgManager.matchedChannelCount(channels)
                val hits = EpgManager.search(q, channels)
                count = hits.size
                for (h in hits) if (h.inTitle) rows.add(SearchItem(h))
                val descHits = hits.filter { !it.inTitle }
                if (descHits.isNotEmpty()) {
                    rows.add(SearchHeader("Найдено в описании"))
                    for (h in descHits) rows.add(SearchItem(h))
                }
            } catch (e: Throwable) {
                err = e.message ?: "ошибка поиска"
            }
            handler.post {
                if (seq != searchSeq || searchOverlay.visibility != View.VISIBLE) return@post
                searchRows = rows
                when {
                    err != null -> { searchStatus.text = "Не удалось выполнить поиск: $err"; searchResults.adapter = null }
                    rows.isEmpty() -> {
                        searchStatus.text = when {
                            channels.isEmpty() -> "Нет загруженных каналов."
                            matched == 0 -> "Каналы не сопоставлены с телепрограммой (0 из ${channels.size}). Проверьте, что EPG добавлен, а в плейлисте указаны tvg-id или имена каналов, совпадающие с программой."
                            else -> "Ничего не нашлось по запросу «$q» (каналов с программой: $matched, передач в базе: ${EpgManager.programCount})."
                        }
                        searchResults.adapter = null
                        focusResultsAfterSearch = false
                    }
                    else -> {
                        searchStatus.text = "Найдено: $count"
                        searchResults.adapter = SearchAdapter(this, rows)
                        searchResults.setOnItemClickListener { _, _, pos, _ ->
                            (searchRows.getOrNull(pos) as? SearchItem)?.let { onSearchPick(it.hit) }
                        }
                        if (focusResultsAfterSearch) {
                            focusResultsAfterSearch = false
                            searchResults.requestFocus()
                            searchResults.setSelection(0)
                        }
                    }
                }
            }
        }.start()
    }

    private fun focusChannelContext(ch: Channel) {
        val plIdx = playlists.indexOfFirst { pl -> pl.channels.any { it.url == ch.url } }
        if (plIdx >= 0) {
            curPlaylistIdx = plIdx
            curCategory = null
            rebuildZapList(keepCurrent = false)
            val i = zapList.indexOfFirst { it.url == ch.url }
            if (i >= 0) zapIndex = i
        }
        currentChannel = ch
    }

    private fun onSearchPick(hit: EpgManager.SearchHit) {
        val ch = hit.channel
        focusChannelContext(ch)
        when (hit.state) {
            EpgManager.HitState.NOW -> { closeSearch(); play(ch) }
            EpgManager.HitState.ARCHIVE -> {
                closeSearch()
                if (CatchupHelper.canCatchup(ch)) {
                    currentChannel = ch
                    playCatchup(ch, CatchupHelper.buildUrl(ch, hit.prog.start / 1000, hit.prog.stop / 1000), hit.prog.title)
                } else { play(ch); toast("Архив для этого канала недоступен") }
            }
            EpgManager.HitState.FUTURE -> {
                closeSearch()
                play(ch)
                openEpgPanel(hit.prog.start)
                toast("«${hit.prog.title}» — ${dayFmt.format(Date(hit.prog.start))}")
            }
        }
    }

    // ------------------------------------------------------------ быстрое меню

    private fun showQuickMenu() {
        AlertDialog.Builder(this).setTitle("Быстрые настройки")
            .setItems(arrayOf("🎵 Аудиодорожка", "💬 Субтитры", "⚙ Формат кадра", "📶 Качество трансляции")) { _, which ->
                when (which) {
                    0 -> showTrackDialog(C.TRACK_TYPE_AUDIO, "Аудиодорожка")
                    1 -> showTrackDialog(C.TRACK_TYPE_TEXT, "Субтитры")
                    2 -> cycleAspect()
                    3 -> showQualityDialog()
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

    private fun showQualityDialog() {
        val opts = arrayOf("Авто (рекомендуется)", "Стабильность (до 720p, больше буфер)", "Максимум")
        val vals = arrayOf("auto", "stable", "max")
        AlertDialog.Builder(this).setTitle("Качество трансляции")
            .setItems(opts) { _, which ->
                Store.quality = vals[which]
                applyQuality()   // потолок качества применяется на лету, без перезапуска канала
                toast("Качество: ${opts[which]}")
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

    // ------------------------------------------------------------ настройки

    private fun showSettingsDialog() {
        val items = arrayOf(
            "📱 Показать QR-код для настройки с телефона",
            "⌨ Добавить плейлист (ввести ссылку)",
            "📺 Добавить источник EPG",
            "📂 Плейлисты: список и удаление",
            "🗂 Источники EPG: список и удаление",
            "🔄 Обновить плейлисты",
            "🔄 Обновить программу (EPG)",
            "📶 Качество трансляции",
            "🖼 Живое превью в списке: ${if (Store.livePreview) "вкл" else "выкл"}",
            "⬆ Проверить обновление",
            "⬇ Вернуться на предыдущую версию"
        )
        AlertDialog.Builder(this).setTitle("Настройки BQDiptv • версия ${UpdateManager.currentName(this)}")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> { closePanels(); showSetupOverlay() }
                    1 -> showManualUrlDialog(fromPhoneSetup = false)
                    2 -> showManualEpgDialog(fromPhoneSetup = false)
                    3 -> showManagePlaylists()
                    4 -> showManageEpg()
                    5 -> { closePanels(); forceRefreshPlaylists() }
                    6 -> { closePanels(); forceRefreshEpg() }
                    7 -> showQualitySettings()
                    8 -> { Store.livePreview = !Store.livePreview; toast("Живое превью: ${if (Store.livePreview) "вкл" else "выкл"}") }
                    9 -> { closePanels(); checkUpdates(silent = false) }
                    10 -> { closePanels(); rollbackVersion() }
                }
            }.show()
    }

    /**
     * Качество трансляции — все параметры, реально влияющие на воспроизведение.
     * Особенно важно для слабого интернета (дача): задержка от эфира и
     * упорное переподключение дают больше, чем размер буфера.
     */
    private fun showQualitySettings() {
        val items = arrayOf(
            "🎚 Потолок качества: ${Quality.qualityLabel()}",
            "⏱ Размер буфера: ${Quality.bufferLabel()}",
            "🛰 Задержка от эфира: ${Quality.liveOffsetLabel()}",
            "🔌 При обрыве связи: ${Quality.retryLabel()}",
            "🧩 Тип декодера: ${Quality.decoderLabel()}",
            "🖥 Автофреймрейт: ${if (Store.afr) "вкл" else "выкл"}"
        )
        AlertDialog.Builder(this).setTitle("Качество трансляции")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showQualityDialog()
                    1 -> showBufferDialog()
                    2 -> showLiveOffsetDialog()
                    3 -> showRetryDialog()
                    4 -> showDecoderDialog()
                    5 -> {
                        Store.afr = !Store.afr
                        if (!Store.afr) Quality.resetFrameRate(this)
                        toast("Автофреймрейт: ${if (Store.afr) "вкл" else "выкл"}")
                        if (Store.afr) applyAutoFrameRate()
                    }
                }
            }.show()
    }

    /** Отставание от прямого эфира — главный параметр против зависаний. */
    private fun showLiveOffsetDialog() {
        val options = arrayOf(
            "Как в потоке (минимальная)",
            "10 сек",
            "20 сек — слабый интернет",
            "30 сек — очень слабый интернет",
            "60 сек"
        )
        val values = intArrayOf(0, 10, 20, 30, 60)
        AlertDialog.Builder(this).setTitle("Задержка от эфира")
            .setItems(options) { _, which ->
                Store.liveOffsetSec = values[which]
                rebuildPlayerKeepingChannel()
                toast("Задержка от эфира: ${Quality.liveOffsetLabel()}")
            }.show()
    }

    /** Поведение при обрыве: сколько раз и как настойчиво переподключаться. */
    private fun showRetryDialog() {
        val options = arrayOf(
            "Быстро сдаваться (сразу к другому источнику)",
            "Обычно",
            "Упорно — для слабого интернета"
        )
        val values = arrayOf("fast", "normal", "persistent")
        AlertDialog.Builder(this).setTitle("При обрыве связи")
            .setItems(options) { _, which ->
                Store.retryMode = values[which]
                rebuildPlayerKeepingChannel()
                toast("При обрыве: ${Quality.retryLabel()}")
            }.show()
    }

    /** Тип декодера: аппаратный быстрее, программный устойчивее. */
    private fun showDecoderDialog() {
        val options = arrayOf(
            "Аппаратный (HW) — быстрый, по умолчанию",
            "Программный (SW) — если картинка сыпется"
        )
        val values = arrayOf("hw", "sw")
        AlertDialog.Builder(this).setTitle("Тип декодера")
            .setItems(options) { _, which ->
                Store.decoder = values[which]
                rebuildPlayerKeepingChannel()
                toast("Декодер: ${Quality.decoderLabel()}")
            }.show()
    }

    private fun showManualUrlDialog(fromPhoneSetup: Boolean) {
        val input = EditText(this)
        input.hint = "https://… ссылка на m3u"
        input.inputType = InputType.TYPE_TEXT_VARIATION_URI
        AlertDialog.Builder(this).setTitle("Ссылка на плейлист").setView(input)
            .setPositiveButton("Проверить и добавить") { _, _ ->
                val url = input.text.toString().trim()
                if (url.startsWith("http")) validateAndAddPlaylist(url, fromPhoneSetup)
                else toast("Ссылка должна начинаться с http")
            }.setNegativeButton("Отмена", null).show()
    }

    /** п.5: проверка плейлиста на работоспособность перед добавлением. */
    private fun validateAndAddPlaylist(url: String, fromPhoneSetup: Boolean) {
        toast("Проверяю плейлист…")
        Thread {
            val result = try {
                val text = Net.downloadText(url)
                val (channels, _) = M3uParser.parse(text, "")
                if (channels.isEmpty()) "В плейлисте не найдено каналов" else ""
            } catch (e: Exception) { "Не удалось загрузить: ${e.message}" }
            runOnUiThread {
                if (result.isEmpty()) {
                    Store.addPlaylist("", url)
                    closePanels()
                    reloadFromStore(firstRun = true)
                    if (fromPhoneSetup) updatePhoneSetupStatus()
                    toast("✔ Плейлист добавлен")
                } else toast("Ошибка: $result")
            }
        }.start()
    }

    private fun showManualEpgDialog(fromPhoneSetup: Boolean) {
        val name = EditText(this); name.hint = "Название (необязательно)"
        val url = EditText(this); url.hint = "https://… xmltv (.xml.gz)"; url.inputType = InputType.TYPE_TEXT_VARIATION_URI
        val box = LinearLayout(this); box.orientation = LinearLayout.VERTICAL
        val pad = (16 * resources.displayMetrics.density).toInt(); box.setPadding(pad, 0, pad, 0)
        box.addView(name); box.addView(url)
        AlertDialog.Builder(this).setTitle("Источник программы (EPG)").setView(box)
            .setPositiveButton("Добавить") { _, _ ->
                val u = url.text.toString().trim()
                if (u.startsWith("http")) {
                    Store.addEpgSource(name.text.toString().trim(), u)
                    closePanels(); forceRefreshEpg()
                    if (fromPhoneSetup) updatePhoneSetupStatus()
                } else toast("Ссылка должна начинаться с http")
            }.setNegativeButton("Отмена", null).show()
    }

    private fun showManagePlaylists() {
        val cfgs = Store.getPlaylistCfgs()
        if (cfgs.isEmpty()) { toast("Плейлистов нет"); return }
        val names = cfgs.map { it.name }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Плейлисты")
            .setItems(names) { _, which ->
                val cfg = cfgs[which]
                AlertDialog.Builder(this).setTitle(cfg.name)
                    .setItems(arrayOf("✏️ Переименовать", "🗑 Удалить")) { _, act ->
                        if (act == 0) {
                            val inp = EditText(this); inp.setText(cfg.name)
                            AlertDialog.Builder(this).setTitle("Новое название").setView(inp)
                                .setPositiveButton("OK") { _, _ -> Store.renamePlaylist(cfg.url, inp.text.toString().trim()); reloadFromStore(false) }
                                .setNegativeButton("Отмена", null).show()
                        } else {
                            Store.removePlaylist(cfg.url); currentChannel = null; closePanels(); reloadFromStore(false)
                        }
                    }.show()
            }.show()
    }

    private fun showManageEpg() {
        val srcs = Store.getEpgSources()
        if (srcs.isEmpty()) { toast("Источников EPG нет"); return }
        val names = srcs.map { it.name }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Источники EPG")
            .setItems(names) { _, which ->
                val s = srcs[which]
                AlertDialog.Builder(this).setTitle(s.name)
                    .setItems(arrayOf("✏️ Переименовать", "🗑 Удалить")) { _, act ->
                        if (act == 0) {
                            val inp = EditText(this); inp.setText(s.name)
                            AlertDialog.Builder(this).setTitle("Новое название").setView(inp)
                                .setPositiveButton("OK") { _, _ -> Store.renameEpgSource(s.url, inp.text.toString().trim()) }
                                .setNegativeButton("Отмена", null).show()
                        } else { Store.removeEpgSource(s.url); forceRefreshEpg() }
                    }.show()
            }.show()
    }

    private fun showBufferDialog() {
        val options = arrayOf("5 сек", "10 сек", "15 сек", "30 сек", "60 сек")
        val values = intArrayOf(5, 10, 15, 30, 60)
        AlertDialog.Builder(this).setTitle("Размер буфера")
            .setItems(options) { _, which ->
                Store.bufferSec = values[which]
                rebuildPlayerKeepingChannel()
                toast("Буфер: ${values[which]} сек")
            }.show()
    }

    // ------------------------------------------------------------ обновления (п.13, п.15)

    private fun checkUpdates(silent: Boolean) {
        UpdateManager.checkAsync(this, Store.updateRepo) { info, cur ->
            if (info == null) { if (!silent) toast("Не удалось проверить обновления"); return@checkAsync }
            if (info.versionCode > cur) {
                AlertDialog.Builder(this)
                    .setTitle("Доступно обновление")
                    .setMessage("Новая версия ${info.versionName}\nТекущая: ${UpdateManager.currentName(this)}\n\n${info.notes}".trim())
                    .setPositiveButton("Обновить") { _, _ -> startDownload(info.apkUrl, info.versionCode) }
                    .setNegativeButton("Позже", null).show()
            } else if (!silent) toast("У вас последняя версия (${UpdateManager.currentName(this)})")
        }
    }

    private fun rollbackVersion() {
        UpdateManager.checkAsync(this, Store.updateRepo) { info, _ ->
            if (info == null || info.prevUrl.isEmpty()) { toast("Предыдущая версия не найдена"); return@checkAsync }
            AlertDialog.Builder(this)
                .setTitle("Вернуться на предыдущую версию")
                .setMessage("Будет установлена версия ${info.prevName}.\nНастройки и данные сохранятся.\n\nНа некоторых ТВ может потребоваться подтвердить установку вручную.")
                .setPositiveButton("Вернуться") { _, _ -> startDownload(info.prevUrl, info.prevCode) }
                .setNegativeButton("Отмена", null).show()
        }
    }

    private fun startDownload(url: String, versionCode: Int) {
        // если файл ИМЕННО этой версии уже скачан — не качаем повторно, сразу ставим
        if (UpdateManager.hasDownloaded(this, versionCode)) {
            UpdateManager.installDownloaded(this) { msg -> toast(msg) }
            return
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("Загрузка обновления").setMessage("0%").setCancelable(false).create()
        dlg.show()
        val closeDlg = { if (dlg.isShowing) dlg.dismiss() }
        UpdateManager.downloadAndInstall(this, url, versionCode,
            onError = { msg -> awaitingInstall = false; toast(msg) },
            onProgress = { pct -> if (dlg.isShowing) dlg.setMessage("Скачано $pct%") },
            onNeedPermission = {
                awaitingInstall = true
                toast("Разрешите установку и вернитесь в приложение — установка продолжится сама")
                UpdateManager.openInstallPermission(this)
            },
            onDone = { closeDlg() }
        )
    }

    // ------------------------------------------------------------ скринсейвер

    private val screensaverTick = object : Runnable {
        override fun run() {
            val pausedLong = pausedSince > 0 && System.currentTimeMillis() - pausedSince > 5 * 60 * 1000
            if (pausedLong && panel == Panel.NONE && screensaver.visibility != View.VISIBLE && !playerReleased) showScreensaver()
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
        pausedSince = if (!playerReleased && player.isPlaying) 0L else System.currentTimeMillis()
    }

    // ------------------------------------------------------------ анимации

    /*
     * Кривые и длительности перенесены один в один из прототипа:
     *   panelInLeft  .30s cubic-bezier(.2,.70,.30,1)  — панель выезжает слева
     *   panelInRight .30s cubic-bezier(.2,.70,.30,1)  — панель выезжает справа
     *   railIn       .36s cubic-bezier(.2,.72,.28,1)  — содержимое из-под рейки
     *   railOut      .30s cubic-bezier(.4,0,.70,1)    — уход содержимого под рейку
     *   osdUp        .34s cubic-bezier(.2,.70,.30,1)  — плашка снизу
     *   fadeIn       .25s
     */
    private val easeOut = PathInterpolator(0.2f, 0.7f, 0.3f, 1f)
    private val easeRailIn = PathInterpolator(0.2f, 0.72f, 0.28f, 1f)
    private val easeRailOut = PathInterpolator(0.4f, 0f, 0.7f, 1f)

    private fun animPanelInLeft(v: View) {
        v.animate().cancel()
        v.translationX = -dp(34).toFloat(); v.alpha = 0f
        v.animate().translationX(0f).alpha(1f).setDuration(300).setInterpolator(easeOut).start()
    }

    private fun animPanelInRight(v: View) {
        v.animate().cancel()
        v.translationX = dp(34).toFloat(); v.alpha = 0f
        v.animate().translationX(0f).alpha(1f).setDuration(300).setInterpolator(easeOut).start()
    }

    /** Содержимое выезжает из-под рейки. */
    private fun animRailIn(v: View) {
        v.animate().cancel()
        v.translationX = -dp(160).toFloat(); v.alpha = 0.15f
        v.animate().translationX(0f).alpha(1f).setDuration(360).setInterpolator(easeRailIn).start()
    }

    /** Содержимое уходит под рейку (используется при закрытии списка). */
    private fun animRailOut(v: View, then: () -> Unit) {
        v.animate().cancel()
        v.animate().translationX(-dp(160).toFloat()).alpha(0f)
            .setDuration(300).setInterpolator(easeRailOut)
            .withEndAction {
                v.translationX = 0f; v.alpha = 1f
                then()
            }.start()
    }

    private fun animOsdUp(v: View) = animUp(v, 340)

    /** toastUp в прототипе чуть быстрее плашки (.28s против .34s). */
    private fun animToastUp(v: View) = animUp(v, 280)

    private fun animUp(v: View, ms: Long) {
        v.animate().cancel()
        v.translationY = dp(30).toFloat(); v.alpha = 0f
        v.animate().translationY(0f).alpha(1f).setDuration(ms).setInterpolator(easeOut).start()
    }

    private fun animFadeIn(v: View) {
        v.animate().cancel()
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(250).start()
    }

    /**
     * Закрытие панели «под рейку» — состояние rail-closing из прототипа:
     * содержимое уезжает влево и гаснет, и только потом панель прячется.
     */
    private fun closeWithRailOut(vararg views: View, then: () -> Unit) {
        val list = views.filter { it.visibility == View.VISIBLE }
        if (list.isEmpty()) { then(); return }
        var done = 0
        for (v in list) {
            animRailOut(v) {
                done++
                if (done == list.size) then()
            }
        }
    }

    // ------------------------------------------------------------ панель настроек

    private val setItems = listOf(
        SetItem("qr_code_2", "Настройка через смартфон", "qr"),
        SetItem("playlist_add", "Плейлисты и ТВ-программа", "sources"),
        SetItem("high_quality", "Качество трансляции", "quality"),
        SetItem("info", "О программе", "about")
    )

    /**
     * В прототипе строки списка разделов настроек показывают только название
     * и шеврон — без подписи-значения под ним (см. #setList .setrow).
     * Сводка вынесена в саму карточку раздела справа.
     */
    private fun setValueFor(item: SetItem): String = ""

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun openSettingsPanel() {
        panel = Panel.SETTINGS
        // меню не прячем — showRail("SETTINGS") свернёт его в рейку с анимацией
        browserOverlay.visibility = View.GONE
        rightPanel.visibility = View.GONE
        phoneBar.visibility = View.GONE
        setDetailActive = false
        setList.isFocusable = true
        setListVersion.text = "BQDiptv · ${UpdateManager.currentName(this)}"
        settingsPanel.visibility = View.VISIBLE
        animFadeIn(settingsPanel)
        showRail("SETTINGS")
        animRailIn(setListPanel)
        animPanelInRight(setDetailScroll)

        val adapter = SettingsAdapter(this, setItems) { setValueFor(it) }
        setList.adapter = adapter
        setList.setOnItemClickListener { _, _, pos, _ -> enterSetDetail(pos) }
        setList.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                setSelected = pos
                if (!setDetailActive) renderSetDetail(pos, active = false)
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        })
        setList.requestFocus()
        setList.setSelection(setSelected.coerceIn(0, setItems.size - 1))
        renderSetDetail(setSelected, active = false)
    }

    private fun closeSettingsPanel() {
        setDetailActive = false
        if (::setList.isInitialized) setList.isFocusable = true
        closeWithRailOut(setListPanel) {
            settingsPanel.visibility = View.GONE
            closePanels()
        }
    }

    private fun refreshSetList() {
        (setList.adapter as? SettingsAdapter)?.refresh()
    }

    private fun enterSetDetail(pos: Int) {
        setSelected = pos
        setRow = 0; setCol = 0
        setDetailActive = true
        renderSetDetail(pos, active = true)
        if (sdCellViews.isEmpty()) {
            // в разделе нет управляемых элементов — остаёмся в списке
            setDetailActive = false
            renderSetDetail(pos, active = false)
            setList.isFocusable = true
            setList.requestFocus()
            setList.setSelection(pos)
            return
        }
        // пока работаем в деталях, список не забирает фокус — навигация предсказуема
        setList.isFocusable = false
        applySdFocus()
    }

    private fun leaveSetDetail() {
        setDetailActive = false
        renderSetDetail(setSelected, active = false)
        setList.isFocusable = true
        setList.requestFocus()
        setList.setSelection(setSelected)
    }

    // ================= правая колонка настроек =================
    //
    // Модель повторяет прототип: панель раздела — это список строк (panelRows),
    // часть строк оформительские (заголовок секции, разделитель, карточка),
    // часть — фокусируемые, состоящие из ячеек (cellHtml в прототипе).
    // Навигация двумерная: ↑↓ по строкам, ←→ по ячейкам внутри строки.
    // Фокус ведём сами (isActivated), а не системным focusSearch — так поведение
    // в точности совпадает с прототипом и предсказуемо на пульте.

    /** Ячейка строки: field / btn / name / ic / chip / toggle. */
    private class SdCell(
        val type: String,
        val label: String = "",
        val icon: String = "",
        val primary: Boolean = false,
        val wide: Boolean = false,
        val lime: Boolean = false,
        val red: Boolean = false,
        val del: Boolean = false,
        val refr: Boolean = false,
        val sel: Boolean = false,
        val on: Boolean = false,
        /** Плейсхолдер для ячейки типа field. */
        val label2: String = "",
        val action: () -> Unit = {}
    )

    /** Строка панели раздела. */
    private class SdRow(
        val secIcon: String? = null,
        val secTitle: String = "",
        val secDesc: String = "",
        val divider: Boolean = false,
        val about: Boolean = false,
        val qr: Boolean = false,
        val label: String = "",
        val cells: List<SdCell>? = null
    )

    // текущая позиция в сетке деталей
    private var setRow = 0
    private var setCol = 0
    /** Вьюхи ячеек по фокусируемым строкам — для подсветки без перерисовки. */
    private val sdCellViews = ArrayList<List<View>>()
    private val sdCellModels = ArrayList<List<SdCell>>()

    private fun sectionDesc(kind: String): String = when (kind) {
        "qr" -> "Отсканируйте QR-код телефоном или откройте адрес в браузере. Телефон и ТВ должны быть в одной Wi-Fi сети. На странице настройки добавьте плейлист M3U — эфир запустится сам."
        "sources" -> "Добавляйте, редактируйте и удаляйте плейлисты (M3U / M3U8) и источники телепрограммы (EPG, XMLTV). Здесь же можно обновить все подключённые списки и источники — телепрограмма подтянется к каналам автоматически по их именам."
        "quality" -> "Параметры воспроизведения. При слабом интернете сильнее всего помогают «задержка от эфира» и упорное переподключение при обрыве."
        "about" -> "Версия приложения, проверка обновлений и откат на предыдущую версию."
        else -> ""
    }

    /** Строки раздела — прямой аналог panelRows() из прототипа. */
    private fun panelRows(kind: String): List<SdRow> {
        val rows = ArrayList<SdRow>()
        when (kind) {
            "qr" -> {
                rows.add(SdRow(qr = true))
                rows.add(SdRow(cells = listOf(
                    SdCell("btn", "Ввести ссылку вручную", "add", primary = true, wide = true) {
                        showManualUrlDialog(fromPhoneSetup = false)
                    }
                )))
            }
            "sources" -> {
                rows.add(SdRow(secIcon = "playlist_play", secTitle = "Плейлисты",
                    secDesc = "Вставьте прямую ссылку на плейлист в формате M3U / M3U8. После добавления он появится в списке плейлистов."))
                rows.add(SdRow(cells = listOf(
                    SdCell("field", "", "", label2 = "Поле ввода для ссылки") { showManualUrlDialog(fromPhoneSetup = false) },
                    SdCell("btn", "Добавить", "add", primary = true) { showManualUrlDialog(fromPhoneSetup = false) }
                )))
                for (cfg in Store.getPlaylistCfgs()) {
                    rows.add(SdRow(cells = listOf(
                        SdCell("name", cfg.name) { renamePlaylistDialog(cfg.name, cfg.url) },
                        SdCell("ic", "", "edit") { renamePlaylistDialog(cfg.name, cfg.url) },
                        SdCell("ic", "", "sync", refr = true) {
                            toast("Обновляю плейлист…"); forceRefreshPlaylists()
                        },
                        SdCell("ic", "", "delete", del = true) { deletePlaylistDialog(cfg.name, cfg.url) }
                    )))
                }
                rows.add(SdRow(cells = listOf(
                    SdCell("btn", "Обновить все плейлисты", "sync", wide = true, lime = true) {
                        toast("Обновляю плейлисты…"); forceRefreshPlaylists()
                    }
                )))
                rows.add(SdRow(divider = true))
                rows.add(SdRow(secIcon = "event_note", secTitle = "Источники ТВ-программы (EPG)",
                    secDesc = "Ссылка на телепрограмму в формате XMLTV. Программа подтянется к каналам автоматически по их именам."))
                rows.add(SdRow(cells = listOf(
                    SdCell("field", "", "", label2 = "Поле ввода для ссылки") { showManualEpgDialog(fromPhoneSetup = false) },
                    SdCell("btn", "Добавить", "add", primary = true) { showManualEpgDialog(fromPhoneSetup = false) }
                )))
                for (src in Store.getEpgSources()) {
                    rows.add(SdRow(cells = listOf(
                        SdCell("name", src.name) { renameEpgDialog(src.name, src.url) },
                        SdCell("ic", "", "edit") { renameEpgDialog(src.name, src.url) },
                        SdCell("ic", "", "sync", refr = true) { toast("Обновляю телепрограмму…"); forceRefreshEpg() },
                        SdCell("ic", "", "delete", del = true) { deleteEpgDialog(src.name, src.url) }
                    )))
                }
                rows.add(SdRow(cells = listOf(
                    SdCell("btn", "Обновить все источники", "sync", wide = true, lime = true) {
                        toast("Обновляю телепрограмму…"); forceRefreshEpg()
                    }
                )))
            }
            "quality" -> {
                val qVals = listOf("auto", "stable", "max")
                rows.add(SdRow(label = "Потолок качества", cells =
                    listOf("Авто", "Стабильность", "Максимум").mapIndexed { i, o ->
                        SdCell("chip", o, sel = Store.quality == qVals[i]) {
                            Store.quality = qVals[i]; applyQuality(); refreshSetList()
                            toast("Качество: ${Quality.qualityLabel()}"); rerenderDetail()
                        }
                    }))
                val bufVals = listOf(5, 10, 15, 30, 60)
                rows.add(SdRow(label = "Размер буфера", cells = bufVals.map { v ->
                    SdCell("chip", "$v сек", sel = Store.bufferSec == v) {
                        Store.bufferSec = v; rebuildPlayerKeepingChannel(); refreshSetList()
                        toast("Буфер: $v сек"); rerenderDetail()
                    }
                }))
                val offVals = listOf(0, 10, 20, 30, 60)
                val offLabels = listOf("Как в потоке", "10 сек", "20 сек", "30 сек", "60 сек")
                rows.add(SdRow(label = "Задержка от эфира", cells = offVals.mapIndexed { i, v ->
                    SdCell("chip", offLabels[i], sel = Store.liveOffsetSec == v) {
                        Store.liveOffsetSec = v; rebuildPlayerKeepingChannel()
                        toast("Задержка: ${Quality.liveOffsetLabel()}"); rerenderDetail()
                    }
                }))
                val retryVals = listOf("fast", "normal", "persistent")
                rows.add(SdRow(label = "При обрыве связи", cells =
                    listOf("Быстро сдаваться", "Обычно", "Упорно").mapIndexed { i, o ->
                        SdCell("chip", o, sel = Store.retryMode == retryVals[i]) {
                            Store.retryMode = retryVals[i]; rebuildPlayerKeepingChannel()
                            toast("При обрыве: ${Quality.retryLabel()}"); rerenderDetail()
                        }
                    }))
                val decVals = listOf("hw", "sw")
                rows.add(SdRow(label = "Тип декодера", cells =
                    listOf("Аппаратный (HW)", "Программный (SW)").mapIndexed { i, o ->
                        SdCell("chip", o, sel = Store.decoder == decVals[i]) {
                            Store.decoder = decVals[i]; rebuildPlayerKeepingChannel()
                            toast("Декодер: ${Quality.decoderLabel()}"); rerenderDetail()
                        }
                    }))
                rows.add(SdRow(label = "Автофреймрейт (AFR)", cells = listOf(
                    SdCell("toggle", "", on = Store.afr) {
                        Store.afr = !Store.afr
                        if (!Store.afr) Quality.resetFrameRate(this) else applyAutoFrameRate()
                        toast("Автофреймрейт: ${if (Store.afr) "вкл" else "выкл"}"); rerenderDetail()
                    }
                )))
            }
            "about" -> {
                rows.add(SdRow(about = true))
                rows.add(SdRow(cells = listOf(
                    SdCell("btn", "Проверить обновление", "system_update", lime = true) {
                        checkUpdates(silent = false)
                    },
                    SdCell("btn", "Откат на предыдущую версию", "settings_backup_restore", red = true) {
                        rollbackVersion()
                    }
                )))
            }
        }
        return rows
    }

    private fun rerenderDetail() {
        val r = setRow; val c = setCol
        renderSetDetail(setSelected, setDetailActive)
        setRow = r.coerceIn(0, (sdCellViews.size - 1).coerceAtLeast(0))
        setCol = c
        applySdFocus()
    }

    // ---------- диалоги источников ----------

    private fun renamePlaylistDialog(name: String, url: String) {
        val inp = EditText(this); inp.setText(name)
        AlertDialog.Builder(this).setTitle("Новое название").setView(inp)
            .setPositiveButton("OK") { _, _ ->
                Store.renamePlaylist(url, inp.text.toString().trim())
                reloadFromStore(false); refreshSetList(); rerenderDetail()
            }.setNegativeButton("Отмена", null).show()
    }

    private fun deletePlaylistDialog(name: String, url: String) {
        AlertDialog.Builder(this).setTitle(name).setMessage("Удалить этот плейлист?")
            .setPositiveButton("Удалить") { _, _ ->
                Store.removePlaylist(url); currentChannel = null
                reloadFromStore(false); refreshSetList(); rerenderDetail()
                toast("Плейлист удалён")
            }.setNegativeButton("Отмена", null).show()
    }

    private fun renameEpgDialog(name: String, url: String) {
        val inp = EditText(this); inp.setText(name)
        AlertDialog.Builder(this).setTitle("Новое название").setView(inp)
            .setPositiveButton("OK") { _, _ ->
                Store.renameEpgSource(url, inp.text.toString().trim())
                refreshSetList(); rerenderDetail()
            }.setNegativeButton("Отмена", null).show()
    }

    private fun deleteEpgDialog(name: String, url: String) {
        AlertDialog.Builder(this).setTitle(name).setMessage("Удалить этот источник EPG?")
            .setPositiveButton("Удалить") { _, _ ->
                Store.removeEpgSource(url); forceRefreshEpg()
                refreshSetList(); rerenderDetail()
                toast("Источник EPG удалён")
            }.setNegativeButton("Отмена", null).show()
    }

    // ---------- отрисовка ----------

    private fun renderSetDetail(pos: Int, active: Boolean) {
        setDetail.removeAllViews()
        sdCellViews.clear()
        sdCellModels.clear()
        val item = setItems.getOrNull(pos) ?: return

        // .sd-title: иконка + название раздела
        val titleRow = LinearLayout(this)
        titleRow.orientation = LinearLayout.HORIZONTAL
        titleRow.gravity = Gravity.CENTER_VERTICAL
        val tIcon = TextView(this)
        IconFont.apply(tIcon, item.icon)
        tIcon.setTextColor(0xFF63D4E2.toInt()); tIcon.textSize = 26f
        titleRow.addView(tIcon)
        val tText = TextView(this)
        tText.text = item.label
        tText.setTextColor(0xFFFFFFFF.toInt()); tText.textSize = 25f
        tText.setTypeface(tText.typeface, android.graphics.Typeface.BOLD)
        val tlp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        tlp.leftMargin = dp(12)
        titleRow.addView(tText, tlp)
        setDetail.addView(titleRow, mlp(bottom = 10))

        // .sd-desc
        val desc = sectionDesc(item.kind)
        if (desc.isNotEmpty()) setDetail.addView(sdText(desc, 15f, 0xFFC6D7DD.toInt()), mlp(bottom = 22, maxW = 620))

        for (r in panelRows(item.kind)) {
            when {
                r.secIcon != null -> setDetail.addView(sdSection(r), mlp(top = 18, bottom = 8))
                r.divider -> {
                    val v = View(this)
                    v.setBackgroundColor(0x1AFFFFFF)
                    setDetail.addView(v, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                        topMargin = dp(22); bottomMargin = dp(4) })
                }
                r.about -> setDetail.addView(sdAboutCard(), mlp(bottom = 8).apply { gravity = Gravity.CENTER_HORIZONTAL })
                r.qr -> setDetail.addView(sdQrBlock(), mlp(bottom = 8).apply { gravity = Gravity.CENTER_HORIZONTAL })
                r.cells != null -> {
                    val (view, cellViews) = sdCellRow(r)
                    setDetail.addView(view, mlp(bottom = if (r.label.isNotEmpty()) 16 else 9))
                    sdCellViews.add(cellViews)
                    sdCellModels.add(r.cells)
                }
            }
        }

        if (setRow >= sdCellViews.size) setRow = (sdCellViews.size - 1).coerceAtLeast(0)
        applySdFocus()
    }

    private fun mlp(top: Int = 0, bottom: Int = 0, maxW: Int = 0): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(
            if (maxW > 0) dp(maxW) else LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(top); lp.bottomMargin = dp(bottom)
        return lp
    }

    private fun sdText(t: String, size: Float, color: Int, bold: Boolean = false): TextView {
        val tv = TextView(this)
        tv.text = t; tv.textSize = size; tv.setTextColor(color)
        tv.setLineSpacing(dp(4).toFloat(), 1f)
        if (bold) tv.setTypeface(tv.typeface, android.graphics.Typeface.BOLD)
        return tv
    }

    /** .sd-sec — заголовок секции с иконкой и пояснением. */
    private fun sdSection(r: SdRow): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val ic = TextView(this)
        IconFont.apply(ic, r.secIcon ?: "")
        ic.setTextColor(0xFF63D4E2.toInt()); ic.textSize = 20f
        head.addView(ic)
        val t = sdText(r.secTitle, 20f, 0xFFFFFFFF.toInt(), bold = true)
        head.addView(t, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(9) })
        box.addView(head)
        if (r.secDesc.isNotEmpty()) {
            box.addView(sdText(r.secDesc, 13.5f, 0xFFA9C0C8.toInt()),
                LinearLayout.LayoutParams(dp(600), LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) })
        }
        return box
    }

    /** .sd-about — карточка с версией. */
    private fun sdAboutCard(): View {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.gravity = Gravity.CENTER_HORIZONTAL
        card.setBackgroundResource(R.drawable.sd_about)
        card.setPadding(dp(30), dp(22), dp(30), dp(22))

        val appRow = LinearLayout(this)
        appRow.orientation = LinearLayout.HORIZONTAL
        appRow.gravity = Gravity.CENTER_VERTICAL
        val ic = TextView(this)
        IconFont.apply(ic, "live_tv")
        ic.setTextColor(0xFF63D4E2.toInt()); ic.textSize = 24f
        appRow.addView(ic)
        appRow.addView(sdText("BQDiptv", 22f, 0xFFFFFFFF.toInt(), bold = true),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(11) })
        card.addView(appRow)

        val ver = sdText("Версия: ${UpdateManager.currentName(this)}\nСборка: ${UpdateManager.currentCode(this)} · Android TV",
            14f, 0xFFA9C0C8.toInt())
        ver.gravity = Gravity.CENTER
        card.addView(ver, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(9) })
        return card
    }

    /** QR-блок раздела «Настройка через смартфон». */
    private fun sdQrBlock(): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER_HORIZONTAL
        val url = "http://${IpUtil.localIp()}:${WebConfigServer.PORT}"
        val img = ImageView(this)
        try {
            val size = 480
            val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, size, size)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) for (y in 0 until size)
                bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            img.setImageBitmap(bmp)
        } catch (_: Exception) { }
        img.setBackgroundColor(0xFFFFFFFF.toInt())
        img.setPadding(dp(10), dp(10), dp(10), dp(10))
        box.addView(img, LinearLayout.LayoutParams(dp(260), dp(260)))
        val tv = sdText(url, 24f, 0xFF63D4E2.toInt(), bold = true)
        tv.gravity = Gravity.CENTER
        box.addView(tv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        return box
    }

    /** Фокусируемая строка: подпись (для «Качества») + ячейки. */
    private fun sdCellRow(r: SdRow): Pair<View, List<View>> {
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        if (r.label.isNotEmpty()) {
            wrap.addView(sdText(r.label, 14f, 0xFF9FB6BF.toInt(), bold = true),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(9) })
        }
        val line = LinearLayout(this)
        line.orientation = LinearLayout.HORIZONTAL
        line.gravity = Gravity.CENTER_VERTICAL
        val views = ArrayList<View>()
        val cells = r.cells ?: emptyList()
        for ((i, c) in cells.withIndex()) {
            val v = sdCellView(c)
            val lp = when {
                c.type == "field" || (c.type == "name") ->
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                c.wide -> LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
                else -> LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            if (i > 0) lp.leftMargin = dp(10)
            line.addView(v, lp)
            views.add(v)
        }
        wrap.addView(line, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return Pair(wrap, views)
    }

    /** Одна ячейка — аналог cellHtml() прототипа. */
    private fun sdCellView(c: SdCell): View = when (c.type) {
        "toggle" -> {
            val box = LinearLayout(this)
            box.orientation = LinearLayout.HORIZONTAL
            box.gravity = Gravity.CENTER_VERTICAL
            box.addView(sdText("выкл", 13f, if (c.on) 0xFF7F93AC.toInt() else 0xFFEAF4F7.toInt(), bold = true))
            val track = FrameLayout(this)
            track.setBackgroundResource(if (c.on) R.drawable.sd_track_on else R.drawable.sd_track_off)
            val knob = View(this)
            knob.setBackgroundResource(if (c.on) R.drawable.sd_knob_on else R.drawable.sd_knob)
            val klp = FrameLayout.LayoutParams(dp(22), dp(22))
            klp.topMargin = dp(4)
            klp.leftMargin = if (c.on) dp(30) else dp(4)
            track.addView(knob, klp)
            box.addView(track, LinearLayout.LayoutParams(dp(56), dp(30)).apply {
                leftMargin = dp(11); rightMargin = dp(11) })
            box.addView(sdText("вкл", 13f, if (c.on) 0xFF63D4E2.toInt() else 0xFF7F93AC.toInt(), bold = true))
            box.tag = track   // подсвечиваем именно дорожку
            box
        }
        "chip" -> {
            val tv = TextView(this)
            tv.text = if (c.sel) "${c.label}  ✓" else c.label
            tv.textSize = 14f
            tv.setTypeface(tv.typeface, android.graphics.Typeface.BOLD)
            tv.setTextColor(if (c.sel) 0xFFFFFFFF.toInt() else 0xFFC0D3DA.toInt())
            tv.setBackgroundResource(if (c.sel) R.drawable.sd_chip_sel else R.drawable.sd_chip)
            tv.setPadding(dp(20), dp(11), dp(20), dp(11))
            tv
        }
        "ic" -> {
            val tv = TextView(this)
            IconFont.apply(tv, c.icon)
            tv.textSize = 19f
            tv.gravity = Gravity.CENTER
            tv.setTextColor(when {
                c.del -> 0xFFFF8A8A.toInt()
                c.refr -> 0xFFA3E05F.toInt()
                else -> 0xFFC0D3DA.toInt()
            })
            tv.setBackgroundResource(when {
                c.del -> R.drawable.sd_ic_del
                c.refr -> R.drawable.sd_ic_lime
                else -> R.drawable.sd_ic
            })
            tv.layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            tv.setPadding(0, dp(11), 0, 0)
            tv
        }
        "name" -> {
            val tv = TextView(this)
            tv.text = c.label
            tv.textSize = 15f
            tv.setTypeface(tv.typeface, android.graphics.Typeface.BOLD)
            tv.setTextColor(0xFFEAF4F7.toInt())
            tv.setBackgroundResource(R.drawable.sd_name)
            tv.setPadding(dp(16), dp(13), dp(16), dp(13))
            tv.isSingleLine = true
            tv.ellipsize = android.text.TextUtils.TruncateAt.END
            tv
        }
        "field" -> {
            val tv = TextView(this)
            tv.text = c.label2
            tv.textSize = 15f
            tv.setTextColor(0xFF6F8494.toInt())
            tv.setBackgroundResource(R.drawable.sd_card)
            tv.setPadding(dp(16), dp(14), dp(16), dp(14))
            tv.isSingleLine = true
            tv.ellipsize = android.text.TextUtils.TruncateAt.END
            tv
        }
        else -> {  // btn
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER
            row.setBackgroundResource(if (c.primary) R.drawable.sd_card_primary else R.drawable.sd_card)
            row.setPadding(dp(16), dp(13), dp(16), dp(13))
            val ic = TextView(this)
            IconFont.apply(ic, c.icon)
            ic.textSize = 18f
            ic.setTextColor(when {
                c.lime -> 0xFFA3E05F.toInt()
                c.red -> 0xFFFF8A8A.toInt()
                c.primary -> 0xFFDFF6FA.toInt()
                else -> 0xFF63D4E2.toInt()
            })
            row.addView(ic)
            val t = sdText(c.label, 14f, 0xFFDBE8ED.toInt(), bold = true)
            row.addView(t, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
            row
        }
    }

    /** Подсветить текущую ячейку (аналог класса .focused в прототипе). */
    private fun applySdFocus() {
        for ((ri, row) in sdCellViews.withIndex()) {
            for ((ci, v) in row.withIndex()) {
                val on = setDetailActive && ri == setRow && ci == setCol
                val target = (v.tag as? View) ?: v
                target.isActivated = on
            }
        }
        // подкрутить прокрутку так, чтобы активная строка была видна
        if (setDetailActive) sdCellViews.getOrNull(setRow)?.firstOrNull()?.let { v ->
            setDetailScroll.post {
                // абсолютная позиция ячейки внутри прокручиваемого контейнера
                var y = 0
                var node: View? = v
                while (node != null && node !== setDetail) {
                    y += node.top
                    node = node.parent as? View
                }
                setDetailScroll.smoothScrollTo(0, (y - dp(140)).coerceAtLeast(0))
            }
        }
    }

    /** Обработка клавиш в панели настроек — двумерная сетка, как в прототипе. */
    private fun handleSettingsKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (setDetailActive) leaveSetDetail() else closeSettingsPanel()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (!setDetailActive) { closeSettingsPanel(); return true }
                // влево внутри строки; с первой ячейки — назад в список разделов
                if (setCol > 0) { setCol--; applySdFocus() } else leaveSetDetail()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!setDetailActive) { enterSetDetail(setList.selectedItemPosition.coerceAtLeast(0)); return true }
                val n = sdCellModels.getOrNull(setRow)?.size ?: 0
                if (setCol < n - 1) { setCol++; applySdFocus() }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (!setDetailActive) return super.dispatchKeyEvent(event)
                if (setRow > 0) {
                    setRow--
                    setCol = setCol.coerceAtMost((sdCellModels.getOrNull(setRow)?.size ?: 1) - 1).coerceAtLeast(0)
                    applySdFocus()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (!setDetailActive) return super.dispatchKeyEvent(event)
                if (setRow < sdCellViews.size - 1) {
                    setRow++
                    setCol = setCol.coerceAtMost((sdCellModels.getOrNull(setRow)?.size ?: 1) - 1).coerceAtLeast(0)
                    applySdFocus()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (!setDetailActive) { enterSetDetail(setList.selectedItemPosition.coerceAtLeast(0)); return true }
                sdCellModels.getOrNull(setRow)?.getOrNull(setCol)?.action?.invoke()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ------------------------------------------------------------ уведомления

    private val hideToastRunnable = Runnable { toastView.visibility = View.GONE }

    /** Тост строго по центру по горизонтали (позиция задана в разметке). */
    private fun toast(msg: String) {
        // страховка: если сообщение пришло до того, как разметка создана
        if (!::toastView.isInitialized) {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            return
        }
        toastView.text = msg
        val wasHidden = toastView.visibility != View.VISIBLE
        toastView.visibility = View.VISIBLE
        if (wasHidden) animToastUp(toastView)
        handler.removeCallbacks(hideToastRunnable)
        handler.postDelayed(hideToastRunnable, 2200)
    }
}
