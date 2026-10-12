package ru.bqd.iptv

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.Display
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
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
 * Настройки воспроизведения. Их эффект зависит от возможностей потока:
 * ограничение качества требует нескольких видеодорожек, отставание от эфира —
 * live-потока с временной шкалой. Буфер, сеть и декодер читаются при создании
 * плеера; после изменения этих параметров плеер пересоздаётся.
 */
object Quality {

    // ------------------------------------------------------------------ буфер

    /** Целевой верхний порог 30/60 сек; фактический запас ограничен потоком. */
    fun loadControl(): LoadControl {
        val maxMs = if (Store.bufferSec == 60) 60_000 else 30_000
        val minMs = if (maxMs == 60_000) 30_000 else 20_000

        val startMs = 2_500
        val restartMs = 5_000

        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(minMs, maxMs, startMs, restartMs)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(0, false)
            .build()
    }

    // ------------------------------------------------- задержка от прямого эфира

    /**
     * Задаём отставание только по выбору пользователя. Скорость оставляем 1.0x:
     * догоняющее ускорение может исчерпать буфер нестабильного IPTV-потока.
     */
    fun mediaItem(url: String): MediaItem {
        val offsetSec = Store.liveOffsetSec
        if (offsetSec <= 0) return MediaItem.fromUri(url)
        return MediaItem.Builder()
            .setUri(url)
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(offsetSec * 1000L)
                    .setMinPlaybackSpeed(1.0f)
                    .setMaxPlaybackSpeed(1.0f)
                    .build()
            )
            .build()
    }

    // ------------------------------------------------------------- декодер HW/SW

    /**
     * Меняем порядок предпочтения декодеров, не запрещая запасной вариант.
     * Программный декодер бывает полезен при сбоях аппаратного, но требует
     * больше ресурсов и не гарантирует улучшения на каждом устройстве.
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
        "stable" -> "до 720p"
        else -> "авто"
    }
}
