package ru.bqd.iptv

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import kotlin.math.roundToInt

/** Small network adapter for the on-screen clock and weather widgets. Call off the UI thread. */
object WidgetWeather {
    data class Place(val name: String, val latitude: Double, val longitude: Double, val timeZone: String)
    data class Conditions(val temperature: Int, val code: Int, val isDay: Boolean)

    private fun getJson(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 6000
        connection.readTimeout = 8000
        connection.setRequestProperty("User-Agent", "BQDiptv/2.0 (Android TV)")
        try {
            if (connection.responseCode !in 200..299) throw IllegalStateException("Weather HTTP ${connection.responseCode}")
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (output.size() + read > 64 * 1024) throw IllegalStateException("Weather response too large")
                    output.write(buffer, 0, read)
                }
                return JSONObject(output.toString("UTF-8"))
            }
        } finally {
            connection.disconnect()
        }
    }

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

    fun current(place: Place): Conditions {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${place.latitude}" +
            "&longitude=${place.longitude}&current=temperature_2m,weather_code,is_day&forecast_days=1"
        val now = getJson(url).getJSONObject("current")
        return Conditions(now.getDouble("temperature_2m").roundToInt(),
            now.getInt("weather_code"), now.optInt("is_day", 1) == 1)
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
