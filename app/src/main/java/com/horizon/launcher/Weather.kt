package com.horizon.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/** Current conditions for the line next to the date. */
data class Weather(val temp: Int, val unit: String, val label: String, val isDay: Boolean, val place: String?) {
    /** "41° Clear" */
    val short: String get() = "$temp° $label"
}

/**
 * Weather from Open-Meteo (free, no account, no API key).
 * Uses your approximate location if allowed, otherwise a city you type in Settings.
 */
class WeatherRepository(private val context: Context) {

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Network call: run on a background thread. Null if offline or nothing to go on. */
    suspend fun fetch(city: String, fahrenheit: Boolean): Weather? {
        val (lat, lon, place) = coordinates(city) ?: return null
        val unit = if (fahrenheit) "fahrenheit" else "celsius"
        val json = get(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,weather_code,is_day&temperature_unit=$unit",
        ) ?: return null
        val cur = JSONObject(json).optJSONObject("current") ?: return null
        return Weather(
            temp = cur.optDouble("temperature_2m").roundToInt(),
            unit = if (fahrenheit) "°F" else "°C",
            label = describe(cur.optInt("weather_code"), cur.optInt("is_day", 1) == 1),
            isDay = cur.optInt("is_day", 1) == 1,
            place = place,
        )
    }

    private suspend fun coordinates(city: String): Triple<Double, Double, String?>? {
        if (city.isNotBlank()) {
            val json = get(
                "https://geocoding-api.open-meteo.com/v1/search?count=1&language=" +
                    Locale.getDefault().language + "&name=" + URLEncoder.encode(city.trim(), "UTF-8"),
            ) ?: return null
            val r = JSONObject(json).optJSONArray("results")?.optJSONObject(0) ?: return null
            return Triple(r.optDouble("latitude"), r.optDouble("longitude"), r.optString("name"))
        }
        val loc = lastLocation() ?: return null
        return Triple(round(loc.latitude), round(loc.longitude), null)
    }

    /** Rounded to ~1 km: plenty for weather, and less precise than your real position. */
    private fun round(v: Double) = (v * 100).roundToInt() / 100.0

    @SuppressLint("MissingPermission")
    private suspend fun lastLocation(): Location? {
        if (!hasLocationPermission()) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val known = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
        if (known != null) return known
        if (Build.VERSION.SDK_INT < 30) return null
        // Nothing cached: ask once for a fresh network fix.
        return withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine { cont ->
                runCatching {
                    lm.getCurrentLocation(LocationManager.NETWORK_PROVIDER, null, context.mainExecutor) { cont.resume(it) }
                }.onFailure { cont.resume(null) }
            }
        }
    }

    private fun get(url: String): String? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "Horizon-Launcher")
        try {
            if (c.responseCode != 200) null else c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    companion object {
        /** US, Liberia and Myanmar use Fahrenheit; everyone else Celsius. */
        fun defaultFahrenheit(): Boolean = Locale.getDefault().country in setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW")

        /** WMO weather codes → a short word or two. */
        fun describe(code: Int, day: Boolean): String = when (code) {
            0 -> if (day) "Sunny" else "Clear"
            1 -> if (day) "Mostly sunny" else "Mostly clear"
            2 -> "Partly cloudy"
            3 -> "Cloudy"
            45, 48 -> "Fog"
            51, 53, 55, 56, 57 -> "Drizzle"
            61, 63, 66 -> "Rain"
            65, 67 -> "Heavy rain"
            71, 73, 77 -> "Snow"
            75 -> "Heavy snow"
            80, 81 -> "Showers"
            82 -> "Heavy showers"
            85, 86 -> "Snow showers"
            95 -> "Thunderstorms"
            96, 99 -> "Hail storms"
            else -> "—"
        }
    }
}
