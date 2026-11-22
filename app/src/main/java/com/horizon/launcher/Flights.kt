package com.horizon.launcher

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** One aircraft from the live ADS-B feed. */
data class Aircraft(
    val hex: String,
    val callsign: String,
    val registration: String?,
    val type: String?,
    val description: String?,
    val operator: String?,
    val lat: Double,
    val lon: Double,
    /** Feet, null if unknown. */
    val altitude: Int?,
    val onGround: Boolean,
    /** Knots. */
    val speed: Double?,
    /** Degrees from north. */
    val track: Double?,
    /** Feet per minute. */
    val verticalRate: Int?,
    val squawk: String?,
    val category: String?,
    /** When this position was received (epoch millis). */
    val seenAt: Long,
) {
    val emergency: Boolean get() = squawk == "7500" || squawk == "7600" || squawk == "7700"
    val displayName: String get() = callsign.ifBlank { registration ?: hex.uppercase(Locale.ROOT) }
    val isHelicopter: Boolean get() = category == "A7"
}

/** Where a flight is going, from adsb.lol's route database. */
data class FlightRoute(
    val fromCode: String,
    val toCode: String,
    val fromCity: String?,
    val toCity: String?,
    /** False when the plane isn't anywhere near that route (the callsign may be reused). */
    val plausible: Boolean,
)

/**
 * Live aircraft around a point, from the community ADS-B networks adsb.lol (open data) with
 * airplanes.live as a backup. Both are free and need no account.
 */
class FlightRepository {

    private val routes = ConcurrentHashMap<String, FlightRoute?>()
    private val routeFetchedAt = ConcurrentHashMap<String, Long>()

    suspend fun nearby(lat: Double, lon: Double, radiusNm: Int): List<Aircraft>? = withContext(Dispatchers.IO) {
        val r = radiusNm.coerceIn(1, 250)
        val la = "%.3f".format(Locale.ROOT, lat)
        val lo = "%.3f".format(Locale.ROOT, lon)
        val json = get("https://api.adsb.lol/v2/point/$la/$lo/$r")
            ?: get("https://api.airplanes.live/v2/point/$la/$lo/$r")
            ?: return@withContext null
        runCatching { parse(JSONObject(json)) }.getOrNull()
    }

    private fun parse(o: JSONObject): List<Aircraft> {
        val now = o.optLong("now", System.currentTimeMillis()).let { if (it < 10_000_000_000L) it * 1000 else it }
        val ac = o.optJSONArray("ac") ?: o.optJSONArray("aircraft") ?: return emptyList()
        val out = ArrayList<Aircraft>(ac.length())
        for (i in 0 until ac.length()) {
            val a = ac.optJSONObject(i) ?: continue
            if (!a.has("lat") || !a.has("lon")) continue
            val altRaw = a.opt("alt_baro")
            val ground = altRaw == "ground"
            val alt = when {
                ground -> 0
                altRaw is Number -> altRaw.toInt()
                a.has("alt_geom") -> a.optInt("alt_geom")
                else -> null
            }
            val seenPos = a.optDouble("seen_pos", a.optDouble("seen", 0.0))
            if (seenPos > 60) continue // stale
            out += Aircraft(
                hex = a.optString("hex").removePrefix("~"),
                callsign = a.optString("flight").trim(),
                registration = a.optString("r").takeIf { it.isNotBlank() },
                type = a.optString("t").takeIf { it.isNotBlank() },
                description = a.optString("desc").takeIf { it.isNotBlank() },
                operator = a.optString("ownOp").takeIf { it.isNotBlank() },
                lat = a.getDouble("lat"),
                lon = a.getDouble("lon"),
                altitude = alt,
                onGround = ground,
                speed = a.optDouble("gs").takeIf { !it.isNaN() },
                track = a.optDouble("track").takeIf { !it.isNaN() } ?: a.optDouble("true_heading").takeIf { !it.isNaN() },
                verticalRate = (a.opt("baro_rate") ?: a.opt("geom_rate"))?.let { (it as? Number)?.toInt() },
                squawk = a.optString("squawk").takeIf { it.isNotBlank() },
                category = a.optString("category").takeIf { it.isNotBlank() },
                seenAt = now - (seenPos * 1000).toLong(),
            )
        }
        return out
    }

    /** Finds one flight by callsign anywhere in the world. Null if the network couldn't be reached. */
    suspend fun byCallsign(callsign: String): List<Aircraft>? = withContext(Dispatchers.IO) {
        val cs = callsign.trim().uppercase(Locale.ROOT)
        val json = get("https://api.adsb.lol/v2/callsign/$cs") ?: get("https://api.airplanes.live/v2/callsign/$cs")
            ?: return@withContext null
        runCatching { parse(JSONObject(json)) }.getOrNull()
    }

    /** Looks up routes for these flights (cached for 20 minutes). */
    suspend fun routesFor(planes: List<Aircraft>): Map<String, FlightRoute?> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val wanted = planes.filter { it.callsign.length >= 3 && !it.onGround }
            .filter { now - (routeFetchedAt[it.callsign] ?: 0L) > 20 * 60_000L }
            .distinctBy { it.callsign }
            .take(100)
        if (wanted.isNotEmpty()) {
            val body = JSONObject().put(
                "planes",
                JSONArray(wanted.map { JSONObject().put("callsign", it.callsign).put("lat", it.lat).put("lng", it.lon) }),
            ).toString()
            post("https://api.adsb.lol/api/0/routeset", body)?.let { json ->
                runCatching {
                    val arr = JSONArray(json)
                    for (i in 0 until arr.length()) {
                        val r = arr.getJSONObject(i)
                        val cs = r.optString("callsign")
                        routes[cs] = parseRoute(r)
                        routeFetchedAt[cs] = now
                    }
                }
            }
        }
        planes.associate { it.callsign to routes[it.callsign] }
    }

    private fun parseRoute(r: JSONObject): FlightRoute? {
        val codes = r.optString("_airport_codes_iata").takeIf { it.isNotBlank() && it != "unknown" }
            ?: r.optString("airport_codes").takeIf { it.isNotBlank() && it != "unknown" }
            ?: return null
        val parts = codes.split("-")
        if (parts.size < 2) return null
        val airports = r.optJSONArray("_airports")
        fun city(i: Int) = airports?.optJSONObject(i)?.optString("location")?.takeIf { it.isNotBlank() }
        return FlightRoute(
            fromCode = parts.first(),
            toCode = parts.last(),
            fromCity = city(0),
            toCity = city((airports?.length() ?: 1) - 1),
            plausible = r.optBoolean("plausible", true),
        )
    }

    private fun get(url: String): String? = request(url, null)
    private fun post(url: String, body: String): String? = request(url, body)

    private fun request(url: String, body: String?): String? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "Horizon-Launcher (Android)")
        c.setRequestProperty("Accept", "application/json")
        if (body != null) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        try {
            if (c.responseCode !in 200..299) null else c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    companion object {
        private const val EARTH_NM = 3440.065

        /** Distance in nautical miles. */
        fun distanceNm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val dp = p2 - p1
            val dl = Math.toRadians(lon2 - lon1)
            val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
            return 2 * EARTH_NM * atan2(sqrt(a), sqrt(1 - a))
        }

        /** Compass bearing from point 1 to point 2. */
        fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val dl = Math.toRadians(lon2 - lon1)
            val y = sin(dl) * cos(p2)
            val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
            return (Math.toDegrees(atan2(y, x)) + 360) % 360
        }

        /** Moves a position forward along its track, so planes glide between updates. */
        fun project(a: Aircraft, now: Long): Pair<Double, Double> {
            val kn = a.speed ?: return a.lat to a.lon
            val trk = a.track ?: return a.lat to a.lon
            if (a.onGround && kn < 40) return a.lat to a.lon
            val seconds = ((now - a.seenAt) / 1000.0).coerceIn(0.0, 30.0)
            val distNm = kn * seconds / 3600.0
            val dLat = distNm * cos(Math.toRadians(trk)) / 60.0
            val dLon = distNm * sin(Math.toRadians(trk)) / (60.0 * cos(Math.toRadians(a.lat)).coerceAtLeast(0.01))
            return (a.lat + dLat) to (a.lon + dLon)
        }

        fun compass(deg: Double): String {
            val names = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
            return names[(((deg % 360) + 360) % 360 / 45.0).roundToInt() % 8]
        }

        /** Countries that use miles on the road. Altitude is always in feet, as pilots use. */
        fun imperial(): Boolean = Locale.getDefault().country in setOf("US", "GB", "LR", "MM")

        /** The airline behind a callsign like "UAL123". */
        fun airlineOf(callsign: String): String? {
            if (callsign.length < 4 || !callsign.take(3).all { it.isLetter() } || !callsign[3].isDigit()) return null
            return AIRLINES[callsign.take(3).uppercase(Locale.ROOT)]
        }

        /** "UA 123", "ua123" or "UAL123" → "UAL123" (the callsign planes broadcast). */
        fun callsignFor(input: String): String {
            val t = input.uppercase(Locale.ROOT).filter { it.isLetterOrDigit() }
            val m = Regex("^([A-Z0-9]{2})(\\d{1,4}[A-Z]?)$").find(t) ?: return t
            val (iata, num) = m.destructured
            val icao = IATA.entries.firstOrNull { it.value == iata }?.key ?: return t
            return icao + num
        }

        /** Flight number people know, like "UA 123" for "UAL123". */
        fun flightNumber(callsign: String): String? {
            if (airlineOf(callsign) == null) return null
            val iata = IATA[callsign.take(3).uppercase(Locale.ROOT)] ?: return null
            return "$iata ${callsign.drop(3)}"
        }

        private val AIRLINES = mapOf(
            "AAL" to "American Airlines", "UAL" to "United Airlines", "DAL" to "Delta Air Lines",
            "SWA" to "Southwest Airlines", "JBU" to "JetBlue", "ASA" to "Alaska Airlines",
            "NKS" to "Spirit Airlines", "FFT" to "Frontier Airlines", "HAL" to "Hawaiian Airlines",
            "SKW" to "SkyWest", "RPA" to "Republic Airways", "ENY" to "Envoy Air", "EDV" to "Endeavor Air",
            "JIA" to "PSA Airlines", "ASH" to "Mesa Airlines", "PDT" to "Piedmont Airlines", "GJS" to "GoJet",
            "AAY" to "Allegiant Air", "SCX" to "Sun Country", "MXY" to "Breeze Airways", "QXE" to "Horizon Air",
            "FDX" to "FedEx", "UPS" to "UPS Airlines", "GTI" to "Atlas Air", "ABX" to "ABX Air", "ATN" to "ATI",
            "ACA" to "Air Canada", "WJA" to "WestJet", "JZA" to "Jazz", "POE" to "Porter Airlines", "TSC" to "Air Transat",
            "AMX" to "Aeroméxico", "VOI" to "Volaris", "VIV" to "VivaAerobus", "CMP" to "Copa Airlines",
            "AVA" to "Avianca", "LAN" to "LATAM", "TAM" to "LATAM Brasil", "GLO" to "GOL", "AZU" to "Azul",
            "BAW" to "British Airways", "VIR" to "Virgin Atlantic", "EZY" to "easyJet", "RYR" to "Ryanair",
            "EIN" to "Aer Lingus", "AFR" to "Air France", "KLM" to "KLM", "DLH" to "Lufthansa", "SWR" to "Swiss",
            "AUA" to "Austrian", "IBE" to "Iberia", "VLG" to "Vueling", "TAP" to "TAP Air Portugal",
            "ITY" to "ITA Airways", "SAS" to "SAS", "FIN" to "Finnair", "NAX" to "Norwegian", "WZZ" to "Wizz Air",
            "LOT" to "LOT", "THY" to "Turkish Airlines", "ICE" to "Icelandair", "BEL" to "Brussels Airlines",
            "UAE" to "Emirates", "QTR" to "Qatar Airways", "ETD" to "Etihad", "SVA" to "Saudia", "ELY" to "El Al",
            "SIA" to "Singapore Airlines", "CPA" to "Cathay Pacific", "JAL" to "Japan Airlines", "ANA" to "ANA",
            "KAL" to "Korean Air", "AAR" to "Asiana", "CCA" to "Air China", "CES" to "China Eastern",
            "CSN" to "China Southern", "EVA" to "EVA Air", "CAL" to "China Airlines", "QFA" to "Qantas",
            "ANZ" to "Air New Zealand", "VOZ" to "Virgin Australia", "AIC" to "Air India", "IGO" to "IndiGo",
            "ETH" to "Ethiopian Airlines", "KQA" to "Kenya Airways", "SAA" to "South African Airways",
            "RAM" to "Royal Air Maroc", "MSR" to "EgyptAir", "PAL" to "Philippine Airlines", "THA" to "Thai Airways",
            "MAS" to "Malaysia Airlines", "GIA" to "Garuda Indonesia", "VJC" to "VietJet", "HVN" to "Vietnam Airlines",
        )

        private val IATA = mapOf(
            "AAL" to "AA", "UAL" to "UA", "DAL" to "DL", "SWA" to "WN", "JBU" to "B6", "ASA" to "AS", "NKS" to "NK",
            "FFT" to "F9", "HAL" to "HA", "AAY" to "G4", "SCX" to "SY", "MXY" to "MX", "FDX" to "FX", "UPS" to "5X",
            "ACA" to "AC", "WJA" to "WS", "POE" to "PD", "AMX" to "AM", "VOI" to "Y4", "CMP" to "CM", "AVA" to "AV",
            "BAW" to "BA", "VIR" to "VS", "EZY" to "U2", "RYR" to "FR", "EIN" to "EI", "AFR" to "AF", "KLM" to "KL",
            "DLH" to "LH", "SWR" to "LX", "IBE" to "IB", "TAP" to "TP", "THY" to "TK", "ICE" to "FI", "UAE" to "EK",
            "QTR" to "QR", "ETD" to "EY", "SIA" to "SQ", "CPA" to "CX", "JAL" to "JL", "ANA" to "NH", "KAL" to "KE",
            "QFA" to "QF", "ANZ" to "NZ", "AIC" to "AI", "ELY" to "LY", "LAN" to "LA", "AZU" to "AD", "GLO" to "G3",
        )
    }
}
