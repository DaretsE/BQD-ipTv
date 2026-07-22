package ru.bqd.iptv

/**
 * Подбор тематической иконки для ЛЮБОЙ группы каналов из ЛЮБОГО плейлиста.
 *
 * Проблема: заранее неизвестно, какой плейлист загрузит пользователь и как там
 * называются группы. Названия у разных провайдеров пишутся по-разному:
 * «Кино», «КИНО И СЕРИАЛЫ», "Movies", «Фильмы HD», «• Кино •», «18+ Эротика».
 *
 * Решение: не точное совпадение имени, а поиск ключевых слов в нормализованном
 * названии. Правила отсортированы от частного к общему (сначала «футбол»,
 * потом «спорт»; сначала «зарубежное кино», потом «кино»), первое совпадение
 * побеждает. Если не подошло ни одно правило — универсальная иконка live_tv.
 *
 * Возвращается имя лигатуры шрифта Material Symbols (как в макете интерфейса).
 */
object GroupIcons {

    const val DEFAULT = "live_tv"

    /** Нормализация: нижний регистр, ё→е, латиница/кириллица/цифры, схлопывание пробелов. */
    private fun norm(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s.lowercase()) {
            val ch = if (c == 'ё') 'е' else c
            if (ch.isLetterOrDigit()) sb.append(ch)
            else if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
        }
        return sb.toString().trim()
    }

    /**
     * Правила: иконка + список ключей. Порядок важен — сверху более частные темы.
     * Ключи ищутся как подстроки нормализованного имени, поэтому «кино» поймает
     * и «Кинозал», и «КИНО HD», и «Фильмы и кино».
     */
    private val RULES: List<Pair<String, List<String>>> = listOf(
        // --- A. Особые/составные названия (должны проверяться раньше общих) ---
        "public"              to listOf("мультиплекс", "федеральн", "эфирн", "общероссийск", "обязательн"),
        "theaters"            to listOf("зарубежное кино", "зарубежные фильм", "мировое кино", "foreign movie"),
        "local_movies"        to listOf("наше кино", "русское кино", "русские фильм", "отечествен", "советск"),
        "workspace_premium"   to listOf("vip", "premium", "премиум", "амедиа", "amedia", "эксклюзив"),

        // --- B. Детское (раньше «кино», иначе «Мультфильмы» уйдёт в кино) ---
        "animation"           to listOf("мультф", "мульт", "cartoon", "аниме", "anime", "animation"),
        "auto_stories"        to listOf("сказк", "fairy", "tale"),
        "child_care"          to listOf("детск", "дети", "kids", "child", "малыш", "junior", "baby",
                                        "семейн", "семь", "family"),

        // --- C. Спорт (частное раньше общего, киберспорт раньше спорта) ---
        "sports_soccer"       to listOf("футбол", "soccer", "football", "лига чемпионов", "рпл", "апл"),
        "sports_hockey"       to listOf("хоккей", "hockey", "нхл", "khl", "nhl"),
        "sports_basketball"   to listOf("баскетбол", "basketball", "нба", "nba"),
        "sports_tennis"       to listOf("теннис", "tennis"),
        "sports_martial_arts" to listOf("единоборств", "бокс", "боев искус", "mma", "ufc", "boxing", "борьб"),
        "sports_motorsports"  to listOf("автоспорт", "формула", "motorsport", "racing", "ралли", "nascar"),
        "sports_esports"      to listOf("киберспорт", "esport", "игров", "gaming", "game"),
        "fitness_center"      to listOf("фитнес", "fitness", "здоров", "workout", "йога"),
        "sports"              to listOf("спорт", "sport", "матч", "олимп", "olympic"),

        // --- D. Кино и сериалы (жанры тоже сюда) ---
        "smart_display"       to listOf("сериал", "serial", "series", "тв шоу", "tv show"),
        "movie"               to listOf("кино", "фильм", "movie", "cinema", "film", "премьер",
                                        "ужас", "хоррор", "horror", "триллер", "фантастик", "детектив",
                                        "боевик", "драм", "комеди", "приключен"),

        // --- E. Познавательное ---
        "history_edu"         to listOf("истор", "history", "археолог"),
        "biotech"             to listOf("наука", "science", "научн"),
        "school"              to listOf("образоват", "обучен", "education", "учебн"),
        "science"             to listOf("познават", "документал", "docum", "discovery", "nat geo",
                                        "national geographic", "знание", "техник"),

        // --- F. Природа, животные, путешествия ---
        "pets"                to listOf("животн", "animal", "питом", "zoo", "зоо", "кошк", "собак", "cat", "dog"),
        "forest"              to listOf("природ", "nature", "эколог"),
        "phishing"            to listOf("охот", "рыбалк", "fishing", "hunting"),
        "luggage"             to listOf("путешеств", "travel", "туризм", "tourism", "страны мира"),

        // --- G. Музыка ---
        "music_video"         to listOf("клип", "music video"),
        "radio"               to listOf("радио", "radio"),
        "music_note"          to listOf("музык", "music", "муз тв", "шансон", "рок", "хит",
                                        "dance", "танцевальн", "концерт", "джаз", "поп"),

        // --- H. Новости, политика, бизнес ---
        "newspaper"           to listOf("новост", "news", "информацион"),
        "account_balance"     to listOf("политик", "politic", "парламент", "дума", "власть"),
        "trending_up"         to listOf("бизнес", "business", "эконом", "финанс", "рбк", "forex", "крипто"),

        // --- I. Вера, регионы, страны ---
        "church"              to listOf("вера", "религ", "правосл", "христ", "ислам", "religion",
                                        "спас", "союз", "духовн", "церк"),
        "location_city"       to listOf("местн", "регион", "городск", "local", "област", "краев"),
        "flag"                to listOf("снг", "беларус", "казах", "украин", "армен", "азербайдж",
                                        "узбек", "киргиз", "молдав", "грузи", "cis"),
        "language"            to listOf("зарубежн", "иностран", "foreign", "world", "международ",
                                        "интернацион", "english", "англ"),

        // --- J. Образ жизни ---
        "restaurant"          to listOf("кулинар", "еда", "food", "cooking", "кухн"),
        "yard"                to listOf("дом и сад", "сад", "дача", "garden", "интерьер", "ремонт", "усадьб"),
        "directions_car"      to listOf("авто", "мото", "car", "транспорт"),
        "shopping_cart"       to listOf("покупк", "магазин", "shop", "telemarket", "распродаж"),
        "female"              to listOf("женск", "women", "female"),
        "male"                to listOf("мужск", "men s", "мужчин"),
        "favorite"            to listOf("романтик", "romance", "любов", "мелодрам"),
        "interests"           to listOf("хобби", "hobby", "рукодел", "diy", "сделай сам"),
        "mood"                to listOf("юмор", "humor", "смех", "прикол", "сатир"),
        "theater_comedy"      to listOf("шоу", "show", "развлекат", "entertain", "театр"),
        "cloud"               to listOf("погод", "weather"),
        "checkroom"           to listOf("мода", "fashion", "стиль", "красот", "beauty"),
        "nightlight"          to listOf("ночн", "night"),
        "celebration"         to listOf("праздник", "новогодн", "festive"),

        // --- K. Взрослое (эротика раньше, чем общий маркер 18+) ---
        "local_fire_department" to listOf("эротик", "erotic", "sex", "секс", "night club"),
        "explicit"            to listOf("18", "adult", "xxx", "для взрослых"),

        // --- L. Технические метки качества ---
        "4k_plus"             to listOf("uhd", "ultra hd", "8k"),
        "4k"                  to listOf("4k"),
        "hd"                  to listOf("full hd", "fhd", "hd"),
        "3d_rotation"         to listOf("3d"),
        "bug_report"          to listOf("test", "тест", "проверк", "debug"),
        "hourglass_empty"     to listOf("временн", "резерв", "backup"),

        // --- M. Общее ---
        "star"                to listOf("избранн", "favorite", "favourite", "любим"),
        "podcasts"            to listOf("подкаст", "podcast"),
        "videocam"            to listOf("веб камер", "webcam"),
        "apps"                to listOf("все канал", "общие", "разное", "прочее", "misc", "другие", "all"),
        "live_tv"             to listOf("тв", "tv", "канал", "channel", "эфир")
    )

    /** Кэш, чтобы не пересчитывать одно и то же имя при каждой перерисовке списка. */
    private val cache = HashMap<String, String>()

    /**
     * Главная функция: имя группы -> имя иконки (лигатура Material Symbols).
     * Работает с любым названием, регистр и знаки препинания не важны.
     */
    @JvmStatic
    fun iconFor(groupName: String?): String {
        val raw = groupName ?: return DEFAULT
        cache[raw]?.let { return it }

        val n = norm(raw)
        var result = DEFAULT
        if (n.isNotEmpty()) {
            outer@ for ((icon, keys) in RULES) {
                for (k in keys) {
                    if (n.contains(k)) { result = icon; break@outer }
                }
            }
        }
        if (cache.size > 512) cache.clear()
        cache[raw] = result
        return result
    }

    /** Сброс кэша при смене плейлиста (не обязателен, но дешёвый). */
    @JvmStatic
    fun clearCache() = cache.clear()
}
