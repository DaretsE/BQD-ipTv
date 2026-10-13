package ru.bqd.iptv

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.Locale
import kotlin.math.roundToInt

/** Small network adapter for the on-screen clock and weather widgets. Call off the UI thread. */
object WidgetWeather {
    data class Place(val name: String, val latitude: Double, val longitude: Double, val timeZone: String)
    data class Conditions(val temperature: Int, val code: Int, val isDay: Boolean)

    private fun getJsonText(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 6000
        connection.readTimeout = 8000
        connection.setRequestProperty("User-Agent", "BQDiptv/2.0 (https://github.com/DaretsE/BQD-ipTv)")
        try {
            if (connection.responseCode !in 200..299) throw IllegalStateException("Weather HTTP ${connection.responseCode}")
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (output.size() + read > 1024 * 1024) throw IllegalStateException("Weather response too large")
                    output.write(buffer, 0, read)
                }
                return output.toString("UTF-8")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun getJson(url: String): JSONObject = JSONObject(getJsonText(url))

    fun detectPlace(): Place {
        return runCatching { detectGeoJs() }.getOrElse {
            detectIpWhoIs()
        }
    }

    private fun detectGeoJs(): Place {
        val json = getJson("https://get.geojs.io/v1/ip/geo.json")
        val lat = json.getString("latitude").toDouble()
        val lon = json.getString("longitude").toDouble()
        require(lat in -90.0..90.0 && lon in -180.0..180.0)
        val city = json.optString("city").takeUnless { it.isBlank() || it == "null" }
            ?: json.optString("region").takeUnless { it.isBlank() || it == "null" }
            ?: json.optString("country")
        return Place(city, lat, lon, json.optString("timezone"))
    }

    private fun detectIpWhoIs(): Place {
        val json = getJson("https://ipwho.is/")
        require(json.optBoolean("success", false)) { "IP location unavailable" }
        val lat = json.getDouble("latitude")
        val lon = json.getDouble("longitude")
        require(lat in -90.0..90.0 && lon in -180.0..180.0)
        val city = json.optString("city").takeUnless { it.isBlank() || it == "null" }
            ?: json.optString("region").takeUnless { it.isBlank() || it == "null" }
            ?: json.optString("country")
        return Place(city, lat, lon, json.optJSONObject("timezone")?.optString("id").orEmpty())
    }

    fun findCity(query: String): Place? {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=1&language=ru&format=json"
        val item = getJson(url)
            .optJSONArray("results")?.optJSONObject(0) ?: return null
        val lat = item.getDouble("latitude")
        val lon = item.getDouble("longitude")
        val city = item.getString("name")
        val country = item.optString("country")
        return Place(if (country.isBlank()) city else "$city, $country", lat, lon,
            item.optString("timezone"))
    }

    fun current(place: Place, weatherCompanyApiKey: String = ""): Conditions {
        val weatherCompany = if (weatherCompanyApiKey.isNotBlank())
            runCatching { currentWeatherCompany(place, weatherCompanyApiKey) } else null
        weatherCompany?.getOrNull()?.let { return it }
        val primary = runCatching { currentOpenMeteo(place) }
        primary.getOrNull()?.let { return it }
        return try {
            currentMetNorway(place)
        } catch (secondary: Exception) {
            val first = primary.exceptionOrNull()
            throw IllegalStateException(
                (weatherCompany?.exceptionOrNull()?.let { "Weather.com: ${failureReason(it)}; " } ?: "") +
                    "Open-Meteo: ${failureReason(first)}; MET Norway: ${failureReason(secondary)}",
                secondary
            )
        }
    }

    private fun currentWeatherCompany(place: Place, apiKey: String): Conditions {
        val lat = String.format(Locale.US, "%.4f", place.latitude)
        val lon = String.format(Locale.US, "%.4f", place.longitude)
        val key = URLEncoder.encode(apiKey.trim(), "UTF-8")
        val url = "https://api.weather.com/v3/wx/observations/current?geocode=$lat,$lon" +
            "&units=m&language=ru-RU&format=json&apiKey=$key"
        val response = JSONTokener(getJsonText(url)).nextValue()
        val observation = when (response) {
            is JSONObject -> response
            is JSONArray -> response.getJSONObject(0)
            else -> throw IllegalStateException("Weather.com: unexpected response")
        }
        val icon = observation.optInt("iconCode", -1)
        val code = when (icon) {
            31, 32, 36 -> 0
            29, 30, 33, 34 -> 2
            26, 27, 28 -> 3
            19, 20, 21, 22 -> 45
            0, 3, 4, 37, 38, 47 -> 95
            13, 14, 15, 16, 41, 42, 43, 46 -> 71
            5, 6, 7, 8, 9, 10, 11, 12, 35, 39, 40, 45 -> 61
            else -> -1
        }
        return Conditions(observation.getDouble("temperature").roundToInt(), code,
            observation.optString("dayOrNight", "D") != "N")
    }

    private fun currentOpenMeteo(place: Place): Conditions {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${place.latitude}" +
            "&longitude=${place.longitude}&current=temperature_2m,weather_code,is_day&forecast_days=1"
        val now = getJson(url).getJSONObject("current")
        return Conditions(now.getDouble("temperature_2m").roundToInt(),
            now.getInt("weather_code"), now.optInt("is_day", 1) == 1)
    }

    private fun currentMetNorway(place: Place): Conditions {
        // The provider asks clients to round coordinates so identical locations share its cache.
        val lat = String.format(Locale.US, "%.4f", place.latitude)
        val lon = String.format(Locale.US, "%.4f", place.longitude)
        val url = "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=$lat&lon=$lon"
        val data = getJson(url).getJSONObject("properties").getJSONArray("timeseries")
            .getJSONObject(0).getJSONObject("data")
        val temperature = data.getJSONObject("instant").getJSONObject("details")
            .getDouble("air_temperature").roundToInt()
        val symbol = (data.optJSONObject("next_1_hours")
            ?: data.optJSONObject("next_6_hours")
            ?: data.optJSONObject("next_12_hours"))
            ?.optJSONObject("summary")?.optString("symbol_code").orEmpty()
        val code = when {
            symbol.startsWith("clearsky") -> 0
            symbol.startsWith("fair") -> 1
            symbol.startsWith("partlycloudy") -> 2
            symbol.startsWith("cloudy") -> 3
            symbol.contains("fog") -> 45
            symbol.contains("thunder") -> 95
            symbol.contains("snow") || symbol.contains("sleet") -> 71
            symbol.contains("rain") -> 61
            else -> -1
        }
        return Conditions(temperature, code, !symbol.endsWith("_night"))
    }

    private fun failureReason(error: Throwable?): String {
        if (error == null) return "неизвестная ошибка"
        return when (error) {
            is java.net.UnknownHostException -> "ошибка DNS"
            is java.net.SocketTimeoutException -> "тайм-аут"
            is javax.net.ssl.SSLException -> "ошибка TLS/сертификата"
            else -> error.message?.take(90) ?: error.javaClass.simpleName
        }
    }

    fun symbol(code: Int, isDay: Boolean): String = when (code) {
        0 -> if (isDay) "☀" else "☾"
        1, 2 -> if (isDay) "⛅" else "☁"
        3, 45, 48 -> "☁"
        in 51..67, in 80..82 -> "☂"
        in 71..77, in 85..86 -> "❄"
        in 95..99 -> "ϟ"
        else -> "°"
    }
}
