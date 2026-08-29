package ru.bqd.iptv

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.Display
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.LivePlaybackSpeedControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo

/**
 * Реальные (не декоративные) настройки качества трансляции.
 *
 * Здесь собрано всё, что влияет на устойчивость картинки при слабом интернете:
 *  - размер буфера (LoadControl);
 *  - задержка от прямого эфира (LiveConfiguration) — самый действенный параметр;
 *  - тип декодера: аппаратный / программный (MediaCodecSelector);
 *  - таймауты сети и политика повторов при обрыве (LoadErrorHandlingPolicy);
 *  - потолок качества (применяется в PlayerActivity через TrackSelectionParameters);
 *  - автофреймрейт (переключение режима экрана под частоту кадров потока).
 *
 * Все параметры читаются из Store в момент создания плеера, поэтому после их
 * изменения PlayerActivity пересоздаёт плеер (см. rebuildPlayerKeepingChannel).
 */
object Quality {

    // ------------------------------------------------------------------ буфер

    /**
     * Буфер.
     *
     * ГЛАВНОЕ ИСПРАВЛЕНИЕ ЗАВИСАНИЙ. Раньше нижний порог был вдвое меньше буфера
     * (minMs = maxMs / 2). Из-за этого ExoPlayer, набрав буфер, ПОЛНОСТЬЮ
     * останавливал докачку и не возобновлял её, пока запас не просядет вдвое.
     * Для живого эфира это губительно: пока загрузчик молчит, сервер продолжает
     * гнать поток, TCP-окно закрывается, udpxy/HLS-раздача теряет куски. После
     * возобновления плеер получает разрыв, докачивается уже с отставанием — и с
     * каждым таким циклом отставание растёт. Через несколько минут это выглядит
     * ровно как «буфер забился»: картинка дёргается и встаёт.
     *
     * Теперь minMs == maxMs — загрузчик простаивает только на самой верхушке
     * буфера и возобновляет докачку в ту же секунду, то есть тянет поток
     * практически непрерывно.
     *
     * Второе: раньше targetBufferBytes не задавался. По умолчанию ExoPlayer
     * считает его сам (~13 МБ на видео+звук), поэтому на HD-канале 8–12 Мбит/с
     * буфер упирался в байты примерно на 10 секундах и настройка «60 сек»
     * не работала вообще. Задаём лимит явно — настройка становится настоящей,
     * а память при этом остаётся ограниченной.
     */
    fun loadControl(): LoadControl {
        val sec = Store.bufferSec.coerceIn(5, 120)
        val maxMs = sec * 1000
        // непрерывная докачка: загрузчик не «отпускает» живой источник
        val minMs = maxMs
        // порог начала воспроизведения и порог после провала связи
        val startMs = (maxMs / 6).coerceIn(1500, 4000)
        val restartMs = (maxMs / 3).coerceIn(2500, 8000)
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(minMs, maxMs, startMs, restartMs)
            // при слабом канале важнее набрать время, чем уложиться в лимит байт
            .setPrioritizeTimeOverSizeThresholds(true)
            .setTargetBufferBytes(TARGET_BUFFER_BYTES)
            // назад по живому эфиру не перематываем — хранить проигранное незачем
            .setBackBuffer(0, false)
            .build()
    }

    /** Потолок памяти под буфер. Больше «настоящего» буфера в секундах, но без риска OOM. */
    private const val TARGET_BUFFER_BYTES = 40 * 1024 * 1024

    // ------------------------------------------------- задержка от прямого эфира

    /**
     * Живая конфигурация потока.
     *
     * ВТОРОЕ ИСПРАВЛЕНИЕ ЗАВИСАНИЙ. Раньше при настройке «Как в потоке» (значение
     * по умолчанию!) сюда возвращался голый MediaItem.fromUri() вообще без
     * LiveConfiguration. Без неё ExoPlayer не имеет права подстраивать скорость
     * воспроизведения, а значит НИКОГДА не отыгрывает накопленное отставание:
     * каждый провал связи навсегда отодвигает картинку от живого края. Именно
     * это копится «несколько минут», после чего смотреть невозможно.
     *
     * Теперь коридор скорости (0.97–1.05) задаётся всегда, а целевое отставание —
     * только если пользователь выставил его явно. Для не-живых источников
     * (архив, VOD) LiveConfiguration просто игнорируется, так что это безопасно.
     */
    fun mediaItem(url: String): MediaItem {
        val offsetSec = Store.liveOffsetSec
        val live = MediaItem.LiveConfiguration.Builder()
            // мягкая подстройка скорости, чтобы держаться живого края
            .setMinPlaybackSpeed(0.97f)
            .setMaxPlaybackSpeed(1.05f)
        if (offsetSec > 0) {
            live.setTargetOffsetMs(offsetSec * 1000L)
            live.setMaxOffsetMs(offsetSec * 1000L + 30_000L)
        }
        return MediaItem.Builder()
            .setUri(url)
            .setLiveConfiguration(live.build())
            .build()
    }

    /**
     * ТРЕТЬЕ ИСПРАВЛЕНИЕ ЗАВИСАНИЙ. У ExoPlayer по умолчанию включён механизм,
     * который после КАЖДОГО перебуфера увеличивает целевое отставание от эфира
     * на 500 мс и возвращает его обратно крайне медленно. На нестабильном канале
     * десяток мелких провалов за пару минут наращивает отставание на секунды,
     * буфер раздувается — и это и есть то самое «буфер забился, смотреть нельзя».
     * Отключаем наращивание: targetLiveOffsetIncrementOnRebuffer = 0.
     */
    fun livePlaybackSpeedControl(): LivePlaybackSpeedControl =
        DefaultLivePlaybackSpeedControl.Builder()
            .setFallbackMinPlaybackSpeed(0.97f)
            .setFallbackMaxPlaybackSpeed(1.05f)
            .setTargetLiveOffsetIncrementOnRebufferMs(0)
            .build()

    /**
     * Допустимое отставание от живого края. Если плеер уехал дальше — значит
     * буфер «раздулся» после серии провалов, и PlayerActivity вернёт его к эфиру.
     */
    fun maxLiveOffsetMs(): Long {
        val target = if (Store.liveOffsetSec > 0) Store.liveOffsetSec * 1000L else 15_000L
        return target + Store.bufferSec.coerceIn(5, 120) * 1000L + 30_000L
    }

    // ------------------------------------------------------------- декодер HW/SW

    /**
     * Аппаратный декодер быстрее и не греет процессор, но на части боксов
     * «сыпется» или зависает на проблемных потоках. Программный — медленнее,
     * зато устойчивее. Здесь мы не запрещаем один из типов, а меняем ПОРЯДОК
     * предпочтения: ExoPlayer возьмёт первый подходящий, а при сбое сможет
     * откатиться на следующий (setEnableDecoderFallback).
     */
    private fun codecSelector(): MediaCodecSelector {
        val preferSoftware = Store.decoder == "sw"
        return MediaCodecSelector { mimeType, requiresSecure, requiresTunneling ->
            val base: List<MediaCodecInfo> =
                MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecure, requiresTunneling)
            try {
                if (preferSoftware) base.sortedByDescending { it.softwareOnly }
                else base.sortedByDescending { it.hardwareAccelerated }
            } catch (_: Throwable) {
                base
            }
        }
    }

    fun renderersFactory(ctx: Context): RenderersFactory =
        DefaultRenderersFactory(ctx)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(codecSelector())

    // ------------------------------------------- сеть: таймауты и повторы при обрыве

    /** Таймауты подрастают вместе с «упорством» переподключения. */
    private fun timeoutsMs(): Pair<Int, Int> = when (Store.retryMode) {
        "fast" -> 6_000 to 6_000
        "persistent" -> 20_000 to 20_000
        else -> 12_000 to 12_000
    }

    private fun retryCount(): Int = when (Store.retryMode) {
        "fast" -> 2
        "persistent" -> 12
        else -> 5
    }

    /**
     * Политика повторов. Раньше первый же сбой сети показывал ошибку и уводил
     * на другой источник. Теперь плеер несколько раз тихо переподключается
     * с нарастающей паузой — на нестабильном канале это спасает большинство
     * обрывов без единого видимого «моргания».
     */
    private fun errorPolicy(): LoadErrorHandlingPolicy {
        val retries = retryCount()
        val step = when (Store.retryMode) {
            "fast" -> 400L
            "persistent" -> 1200L
            else -> 800L
        }
        return object : DefaultLoadErrorHandlingPolicy(retries) {
            override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorInfo): Long {
                val n: Long = loadErrorInfo.errorCount.coerceAtLeast(1).toLong()
                val delay: Long = step * n
                return if (delay > 8_000L) 8_000L else delay
            }
        }
    }

    fun mediaSourceFactory(ctx: Context): MediaSource.Factory {
        val (connectMs, readMs) = timeoutsMs()
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("BQDiptv")
            .setConnectTimeoutMs(connectMs)
            .setReadTimeoutMs(readMs)
            .setAllowCrossProtocolRedirects(true)
            .setKeepPostFor302Redirects(true)
        val ds = DefaultDataSource.Factory(ctx, http)
        return DefaultMediaSourceFactory(ds)
            .setLoadErrorHandlingPolicy(errorPolicy())
    }

    // --------------------------------------------------------------- автофреймрейт

    /**
     * Переключает режим экрана под частоту кадров потока (24/25/30/50/60).
     * Убирает рывки при 25 fps на 60-герцовой панели.
     * Работает только если устройство отдаёт несколько режимов экрана.
     * Возвращает true, если режим действительно был изменён.
     */
    fun applyFrameRate(act: Activity, fps: Float): Boolean {
        if (!Store.afr) return false
        if (Build.VERSION.SDK_INT < 23) return false
        if (fps <= 1f || fps > 200f) return false
        return try {
            val display: Display = act.windowManager.defaultDisplay ?: return false
            val current = display.mode ?: return false
            val modes = display.supportedModes ?: return false
            if (modes.size < 2) return false

            var best: Display.Mode? = null
            var bestErr = Float.MAX_VALUE
            for (m in modes) {
                // разрешение не меняем — только частоту
                if (m.physicalWidth != current.physicalWidth) continue
                if (m.physicalHeight != current.physicalHeight) continue
                // ищем частоту, кратную частоте кадров (25 -> 50, 30 -> 60, 24 -> 24/48)
                var err = Float.MAX_VALUE
                for (k in 1..4) {
                    val target = fps * k
                    if (target > m.refreshRate + 1.5f) break
                    val e = kotlin.math.abs(m.refreshRate - target)
                    if (e < err) err = e
                }
                // допускаем небольшой разбег (23.976 vs 24.0 и т.п.)
                if (err < 0.6f && err < bestErr) { bestErr = err; best = m }
            }
            val target = best ?: return false
            if (target.modeId == current.modeId) return false

            val lp = act.window.attributes
            lp.preferredDisplayModeId = target.modeId
            act.window.attributes = lp
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** Сброс принудительного режима экрана (при выходе из приложения). */
    fun resetFrameRate(act: Activity) {
        if (Build.VERSION.SDK_INT < 23) return
        try {
            val lp = act.window.attributes
            if (lp.preferredDisplayModeId != 0) {
                lp.preferredDisplayModeId = 0
                act.window.attributes = lp
            }
        } catch (_: Throwable) { }
    }

    // ------------------------------------------------------------ подписи для меню

    fun bufferLabel(): String = "${Store.bufferSec} сек"

    fun liveOffsetLabel(): String =
        if (Store.liveOffsetSec <= 0) "как в потоке" else "${Store.liveOffsetSec} сек"

    fun decoderLabel(): String =
        if (Store.decoder == "sw") "программный (SW)" else "аппаратный (HW)"

    fun retryLabel(): String = when (Store.retryMode) {
        "fast" -> "быстро сдаваться"
        "persistent" -> "упорно (слабый интернет)"
        else -> "обычно"
    }

    fun qualityLabel(): String = when (Store.quality) {
        "stable" -> "стабильность (до 720p)"
        "max" -> "максимум"
        else -> "авто"
    }
}
