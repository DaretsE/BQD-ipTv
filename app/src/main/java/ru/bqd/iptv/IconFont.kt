package ru.bqd.iptv

import android.content.Context
import android.graphics.Typeface
import android.widget.TextView

/**
 * Иконочный шрифт интерфейса (Material Icons Outlined).
 *
 * Файл шрифта НЕ хранится в репозитории: он скачивается автоматически
 * на этапе сборки в GitHub Actions в app/src/main/assets/icons.otf
 * (см. шаг "Download icon font" в .github/workflows/build.yml).
 *
 * Иконки выводятся не лигатурами, а прямыми кодами символов — так надёжнее:
 * на части Android TV лигатуры в шрифте отрабатывают через раз.
 * Таблица кодов взята из официального файла codepoints этого же шрифта.
 */
object IconFont {

    private var typeface: Typeface? = null
    private var triedLoad = false

    /** Шрифт иконок; null, если файл почему-то отсутствует. */
    fun typeface(ctx: Context): Typeface? {
        if (!triedLoad) {
            triedLoad = true
            typeface = try {
                Typeface.createFromAsset(ctx.assets, "icons.otf")
            } catch (_: Throwable) {
                null
            }
        }
        return typeface
    }

    /** Символ иконки по имени. Если имя неизвестно — иконка телевизора. */
    fun char(name: String): String {
        val code = CODES[name] ?: CODES["live_tv"] ?: return ""
        return String(Character.toChars(code))
    }

    /** Проставить в TextView шрифт и символ иконки. */
    fun apply(tv: TextView, name: String) {
        typeface(tv.context)?.let { tv.typeface = it }
        tv.text = char(name)
    }

    /** Иконка группы каналов (тема подбирается автоматически). */
    fun applyGroup(tv: TextView, groupName: String?) {
        apply(tv, GroupIcons.iconFor(groupName))
    }

    private val CODES: Map<String, Int> = mapOf(
        "3d_rotation" to 0xE84D,
        "4k" to 0xE072,
        "4k_plus" to 0xE969,
        "account_balance" to 0xE84F,
        "add" to 0xE145,
        "animation" to 0xE71C,
        "apps" to 0xE5C3,
        "arrow_back" to 0xE5C4,
        "auto_stories" to 0xE666,
        "biotech" to 0xEA3A,
        "bug_report" to 0xE868,
        "celebration" to 0xEA65,
        "check" to 0xE5CA,
        "checkroom" to 0xF19E,
        "child_care" to 0xEB41,
        "church" to 0xEAAE,
        "cloud" to 0xE2BD,
        "delete" to 0xE872,
        // добавлено для правой колонки настроек (см. codepoints шрифта)
        "sync" to 0xE627,
        "edit" to 0xE3C9,
        "event_note" to 0xE616,
        "directions_car" to 0xE531,
        "explicit" to 0xE01E,
        "favorite" to 0xE87D,
        "female" to 0xE590,
        "fitness_center" to 0xEB43,
        "flag" to 0xE153,
        "forest" to 0xEA99,
        "hd" to 0xE052,
        "high_quality" to 0xE024,
        "history" to 0xE889,
        "history_edu" to 0xEA3E,
        "hourglass_empty" to 0xE88B,
        "info" to 0xE88E,
        "interests" to 0xE7C8,
        "language" to 0xE894,
        "live_tv" to 0xE639,
        "local_fire_department" to 0xEF55,
        "local_movies" to 0xE54D,
        "location_city" to 0xE7F1,
        "luggage" to 0xF235,
        "male" to 0xE58E,
        "mood" to 0xE7F2,
        "movie" to 0xE02C,
        "music_note" to 0xE405,
        "music_video" to 0xE063,
        "newspaper" to 0xEB81,
        "nightlight" to 0xF03D,
        "pets" to 0xE91D,
        "phishing" to 0xEAD7,
        "playlist_add" to 0xE03B,
        "playlist_play" to 0xE05F,
        "podcasts" to 0xF048,
        "preview" to 0xF1C5,
        "public" to 0xE80B,
        "qr_code_2" to 0xE00A,
        "radio" to 0xE03E,
        "restaurant" to 0xE56C,
        "school" to 0xE80C,
        "science" to 0xEA4B,
        "search" to 0xE8B6,
        "settings" to 0xE8B8,
        "settings_backup_restore" to 0xE8BA,
        "shopping_cart" to 0xE8CC,
        "skip_next" to 0xE044,
        "skip_previous" to 0xE045,
        "smart_display" to 0xF06A,
        "speed" to 0xE9E4,
        "sports" to 0xEA30,
        "sports_basketball" to 0xEA26,
        "sports_esports" to 0xEA28,
        "sports_hockey" to 0xEA2B,
        "sports_martial_arts" to 0xEAE9,
        "sports_motorsports" to 0xEA2D,
        "sports_soccer" to 0xEA2F,
        "sports_tennis" to 0xEA32,
        "star" to 0xE838,
        "system_update" to 0xE62A,
        "theater_comedy" to 0xEA66,
        "theaters" to 0xE8DA,
        "timer" to 0xE425,
        "trending_up" to 0xE8E5,
        "tune" to 0xE429,
        "videocam" to 0xE04B,
        "workspace_premium" to 0xE7AF,
        "yard" to 0xF089,
    )
}
