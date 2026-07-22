package ru.bqd.iptv

/**
 * Подбор тематической иконки для ЛЮБОЙ группы каналов из ЛЮБОГО плейлиста.
 *
 * Заранее неизвестно, какой плейлист загрузит пользователь и как там названы
 * группы: «Кино», «КИНО И СЕРИАЛЫ», "Movies", «Фильмы HD», «• Кино •».
 *
 * Сопоставление идёт в два прохода:
 *  1) ТОЧНЫЙ — ключ должен совпасть с началом отдельного слова названия.
 *     Это главный проход, он не даёт ложных срабатываний вроде
 *     «Популярное» → «поп» (музыка), «Уроки» → «рок», «Победа» → «еда».
 *  2) ЗАПАСНОЙ — если точный проход ничего не нашёл, ищем ключ как подстроку
 *     (так ловятся слитные названия вроде «Телесериалы»). В этом проходе
 *     участвуют только длинные ключи (от 4 символов), чтобы короткие
 *     не давали случайных совпадений.
 *
 * Правила отсортированы от частного к общему: сначала «футбол», потом «спорт»;
 * сначала «мультфильмы», потом «кино». Первое совпадение побеждает.
 * Если не подошло ничего — универсальная иконка live_tv.
 *
 * Возвращается имя иконки Material Icons (см. IconFont).
 */
object GroupIcons {

    const val DEFAULT = "live_tv"

    /** Нижний регистр, ё→е, только буквы и цифры, слова через пробел. */
    private fun norm(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s.lowercase()) {
            val ch = if (c == 'ё') 'е' else c
            if (ch.isLetterOrDigit()) sb.append(ch)
            else if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
        }
        return sb.toString().trim()
    }

    private val RULES: List<Pair<String, List<String>>> = listOf(
        // --- A. Особые и составные названия (проверяются раньше общих) ---
        "public"              to listOf("мультиплекс", "федеральн", "эфирн", "общероссийск", "обязательн"),
        "theaters"            to listOf("зарубежное кино", "зарубежные фильм", "мировое кино", "foreign movie"),
        "local_movies"        to listOf("наше кино", "русское кино", "русские фильм", "отечествен", "советск"),
        "workspace_premium"   to listOf("vip", "premium", "премиум", "амедиа", "amedia", "эксклюзив"),

        // --- B. Детское (раньше «кино», иначе «Мультфильмы» уйдёт в кино) ---
        "animation"           to listOf("мультф", "мульт", "cartoon", "аниме", "anime", "animation"),
        "auto_stories"        to listOf("сказк", "fairy", "tale"),
        "child_care"          to listOf("детск", "дети", "детей", "детям", "ребен", "дитя",
                                        "kids", "child", "малыш", "junior", "baby",
                                        "семейн", "семь", "family"),

        // --- C. Спорт (частное раньше общего) ---
        "sports_soccer"       to listOf("футбол", "soccer", "football", "лига чемпионов", "рпл", "апл"),
        "sports_hockey"       to listOf("хоккей", "hockey", "нхл", "khl", "nhl"),
        "sports_basketball"   to listOf("баскетбол", "basketball", "нба", "nba"),
        "sports_tennis"       to listOf("теннис", "tennis"),
        "sports_martial_arts" to listOf("единоборств", "бокс", "боевые искус", "mma", "ufc", "boxing", "борьб"),
        "sports_motorsports"  to listOf("автоспорт", "формула", "motorsport", "racing", "ралли",
                                        "nascar", "гонк", "мотоспорт"),
        "sports_esports"      to listOf("киберспорт", "esport", "игров", "gaming", "game", "games",
                                        "шахмат", "бильярд", "настольн"),
        "fitness_center"      to listOf("фитнес", "fitness", "здоров", "workout", "йога"),
        "sports"              to listOf("спорт", "sport", "sports", "матч", "олимп", "olympic"),

        // --- D. Кино и сериалы (жанры сюда же) ---
        "science"             to listOf("документал", "docum"),
        "smart_display"       to listOf("сериал", "телесериал", "serial", "series", "тв шоу", "tv show"),
        "movie"               to listOf("кино", "фильм", "movie", "cinema", "film", "премьер",
                                        "ужас", "хоррор", "horror", "триллер", "фантастик", "детектив",
                                        "боевик", "драм", "комеди", "приключен", "мелодрам"),

        // --- E. Познавательное ---
        "history_edu"         to listOf("истор", "history", "археолог", "ретро"),
        "biotech"             to listOf("наука", "science", "научн"),
        "school"              to listOf("образоват", "обучен", "education", "учебн", "урок"),
        "science"             to listOf("познават", "познан", "знани", "документал", "docum",
                                        "discovery", "nat geo", "national geographic",
                                        "техник", "техно", "технолог"),

        // --- F. Природа, животные, путешествия ---
        "pets"                to listOf("животн", "animal", "питом", "zoo", "зоо", "кошк", "собак", "cat", "dog"),
        "forest"              to listOf("природ", "nature", "эколог"),
        "phishing"            to listOf("охот", "рыбалк", "fishing", "hunting"),
        "luggage"             to listOf("путешеств", "travel", "туризм", "tourism", "страны мира"),

        // --- G. Музыка ---
        "music_video"         to listOf("клип", "music video"),
        "radio"               to listOf("радио", "radio"),
        "music_note"          to listOf("музык", "music", "муз тв", "шансон", "хит",
                                        "dance", "танцевальн", "концерт", "джаз", "jazz",
                                        "караоке", "karaoke", "классик", "рок", "поп музык"),

        // --- H. Новости, политика, бизнес ---
        "newspaper"           to listOf("новост", "news", "информацион", "инфо"),
        "account_balance"     to listOf("политик", "politic", "парламент", "дума", "власть"),
        "trending_up"         to listOf("бизнес", "business", "эконом", "финанс", "рбк", "forex", "крипто"),

        // --- I. Вера, регионы, страны ---
        "church"              to listOf("вера", "религ", "правосл", "христ", "ислам", "religion",
                                        "спас", "духовн", "церк", "мусульман", "буддий", "иуде"),
        "location_city"       to listOf("местн", "регион", "regional", "город", "городск",
                                        "local", "област", "краев", "москв", "питер", "спб",
                                        "крым", "урал", "сибир", "поволж"),
        "flag"                to listOf("снг", "беларус", "казах", "украин", "армен", "азербайдж",
                                        "узбек", "киргиз", "молдав", "грузи", "cis", "белорус",
                                        "росси", "российск", "наши"),
        "language"            to listOf("зарубежн", "иностран", "foreign", "world", "международ",
                                        "интернацион", "english"),

        // --- J. Образ жизни ---
        "restaurant"          to listOf("кулинар", "еда", "food", "cooking", "кухн"),
        "yard"                to listOf("дом и сад", "сад", "дача", "garden", "интерьер", "ремонт", "усадьб"),
        "directions_car"      to listOf("авто", "мото", "car", "транспорт"),
        "shopping_cart"       to listOf("покупк", "магазин", "shop", "shopping", "telemarket", "распродаж"),
        "female"              to listOf("женск", "women", "female"),
        "male"                to listOf("мужск", "men s", "мужчин"),
        "favorite"            to listOf("романтик", "romance", "любов"),
        "interests"           to listOf("хобби", "hobby", "рукодел", "diy", "сделай сам"),
        "mood"                to listOf("юмор", "humor", "смех", "прикол", "сатир"),
        "theater_comedy"      to listOf("шоу", "show", "развлекат", "развлеч", "entertain",
                                        "театр", "комед", "comedy", "юмористич"),
        "cloud"               to listOf("погод", "weather"),
        "checkroom"           to listOf("мода", "fashion", "стиль", "красот", "beauty"),
        "celebration"         to listOf("праздник", "новогодн", "festive"),

        // --- K. Взрослое (эротика раньше общего маркера 18+) ---
        "local_fire_department" to listOf("эротик", "erotic", "sex", "секс", "night club", "ночной клуб"),
        "explicit"            to listOf("18", "adult", "xxx", "для взрослых", "взросл"),
        "nightlight"          to listOf("ночн", "night"),

        // --- L. Технические метки качества ---
        "4k_plus"             to listOf("uhd", "ultra hd", "8k"),
        "4k"                  to listOf("4k"),
        "hd"                  to listOf("full hd", "fhd", "hd"),
        "3d_rotation"         to listOf("3d"),
        "bug_report"          to listOf("test", "тест", "проверк", "debug", "техническ", "служебн"),
        "hourglass_empty"     to listOf("временн", "резерв", "backup"),

        // --- M. Общее ---
        "star"                to listOf("избранн", "favorite", "favourite", "любим"),
        "podcasts"            to listOf("подкаст", "podcast"),
        "videocam"            to listOf("веб камер", "webcam"),
        "apps"                to listOf("все канал", "общ", "разн", "проч", "misc", "другие",
                                        "остальн", "без группы", "без категор",
                                        "популярн", "popular", "all"),
        "live_tv"             to listOf("тв", "tv", "канал", "channel", "эфир")
    )

    /** Точное совпадение: ключ с пробелом ищем в строке, иначе — начало слова. */
    private fun matchStrict(words: List<String>, full: String, key: String): Boolean {
        if (key.indexOf(' ') >= 0) return full.contains(key)
        for (w in words) if (w.startsWith(key)) return true
        return false
    }

    private val cache = HashMap<String, String>()

    /** Имя группы -> имя иконки. Регистр и знаки препинания не важны. */
    @JvmStatic
    fun iconFor(groupName: String?): String {
        val raw = groupName ?: return DEFAULT
        cache[raw]?.let { return it }

        val full = norm(raw)
        var result = DEFAULT
        if (full.isNotEmpty()) {
            val words = full.split(' ').filter { it.isNotEmpty() }

            // проход 1 — точный
            outer@ for ((icon, keys) in RULES) {
                for (k in keys) {
                    if (matchStrict(words, full, k)) { result = icon; break@outer }
                }
            }

            // проход 2 — запасной, только длинные ключи
            if (result == DEFAULT) {
                outer2@ for ((icon, keys) in RULES) {
                    for (k in keys) {
                        if (k.length >= 4 && full.contains(k)) { result = icon; break@outer2 }
                    }
                }
            }
        }
        if (cache.size > 512) cache.clear()
        cache[raw] = result
        return result
    }

    /** Сброс кэша при смене плейлиста. */
    @JvmStatic
    fun clearCache() = cache.clear()
}
