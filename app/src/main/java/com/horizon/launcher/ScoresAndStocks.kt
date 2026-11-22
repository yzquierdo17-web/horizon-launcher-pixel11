package com.horizon.launcher

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// ---------- Sports ----------

/** Leagues you can follow (ESPN's public scoreboard paths). */
enum class League(val path: String, val label: String) {
    NFL("football/nfl", "NFL"),
    NBA("basketball/nba", "NBA"),
    MLB("baseball/mlb", "MLB"),
    NHL("hockey/nhl", "NHL"),
    WNBA("basketball/wnba", "WNBA"),
    MLS("soccer/usa.1", "MLS"),
    EPL("soccer/eng.1", "Premier League"),
    LaLiga("soccer/esp.1", "LaLiga"),
    UCL("soccer/uefa.champions", "Champions League"),
    CFB("football/college-football", "College football"),
    CBB("basketball/mens-college-basketball", "College basketball");

    companion object {
        fun of(path: String) = entries.firstOrNull { it.path == path }
    }
}

/** A team you follow: stored as "path|id|abbr|name". */
data class FollowedTeam(val league: String, val id: String, val abbr: String, val name: String) {
    fun encode() = "$league|$id|$abbr|$name"

    companion object {
        fun decode(s: String): FollowedTeam? = s.split('|').takeIf { it.size >= 4 }?.let { FollowedTeam(it[0], it[1], it[2], it[3]) }
    }
}

data class TeamInfo(val id: String, val abbr: String, val name: String, val logo: String?)

data class GameSide(val abbr: String, val name: String, val logo: String?, val score: String?, val home: Boolean, val winner: Boolean)

/** One game for a team you follow. */
data class Game(
    val team: FollowedTeam,
    val us: GameSide,
    val them: GameSide,
    /** "pre", "in" or "post". */
    val state: String,
    /** "Bot 7th", "Final", "Sun 1:00 PM". */
    val detail: String,
    val start: Long,
    val link: String?,
) {
    val live get() = state == "in"
}

class SportsRepository {

    suspend fun teams(league: League): List<TeamInfo> = withContext(Dispatchers.IO) {
        val json = get("https://site.api.espn.com/apis/site/v2/sports/${league.path}/teams?limit=500") ?: return@withContext emptyList()
        runCatching {
            val arr = JSONObject(json).getJSONArray("sports").getJSONObject(0).getJSONArray("leagues").getJSONObject(0).getJSONArray("teams")
            (0 until arr.length()).map { i ->
                val t = arr.getJSONObject(i).getJSONObject("team")
                TeamInfo(
                    id = t.optString("id"),
                    abbr = t.optString("abbreviation"),
                    name = t.optString("displayName"),
                    logo = t.optJSONArray("logos")?.optJSONObject(0)?.optString("href"),
                )
            }.sortedBy { it.name }
        }.getOrDefault(emptyList())
    }

    /** Today's game (live or finished) for each team, otherwise its next one. */
    suspend fun games(followed: List<FollowedTeam>): List<Game> = withContext(Dispatchers.IO) {
        if (followed.isEmpty()) return@withContext emptyList()
        val boards = coroutineScope {
            followed.map { it.league }.distinct().associateWith { path ->
                async { get("https://site.api.espn.com/apis/site/v2/sports/$path/scoreboard") }
            }.mapValues { it.value.await() }
        }
        coroutineScope {
            followed.map { team ->
                async {
                    runCatching {
                        boards[team.league]?.let { findInBoard(it, team) }
                            ?: get("https://site.api.espn.com/apis/site/v2/sports/${team.league}/teams/${team.id}")?.let { nextFromTeam(it, team) }
                    }.getOrNull()
                }
            }.awaitAll().filterNotNull()
        }.sortedWith(compareBy<Game> { if (it.live) 0 else if (it.state == "post") 1 else 2 }.thenBy { it.start })
    }

    private fun findInBoard(json: String, team: FollowedTeam): Game? {
        val events = JSONObject(json).optJSONArray("events") ?: return null
        for (i in 0 until events.length()) {
            val e = events.getJSONObject(i)
            parseEvent(e, team)?.let { return it }
        }
        return null
    }

    private fun nextFromTeam(json: String, team: FollowedTeam): Game? {
        val next = JSONObject(json).optJSONObject("team")?.optJSONArray("nextEvent") ?: return null
        if (next.length() == 0) return null
        return parseEvent(next.getJSONObject(0), team)
    }

    private fun parseEvent(e: JSONObject, team: FollowedTeam): Game? {
        val comp = e.optJSONArray("competitions")?.optJSONObject(0) ?: return null
        val sides = comp.optJSONArray("competitors") ?: return null
        var us: GameSide? = null
        var them: GameSide? = null
        for (j in 0 until sides.length()) {
            val c = sides.getJSONObject(j)
            val t = c.optJSONObject("team") ?: continue
            val score = when (val sc = c.opt("score")) {
                is JSONObject -> sc.optString("displayValue").takeIf { it.isNotBlank() }
                is String -> sc.takeIf { it.isNotBlank() }
                is Number -> sc.toString()
                else -> null
            }
            val side = GameSide(
                abbr = t.optString("abbreviation"),
                name = t.optString("shortDisplayName", t.optString("displayName")),
                logo = t.optString("logo").takeIf { it.isNotBlank() } ?: t.optJSONArray("logos")?.optJSONObject(0)?.optString("href"),
                score = score,
                home = c.optString("homeAway") == "home",
                winner = c.optBoolean("winner", false),
            )
            if (t.optString("id") == team.id) us = side else them = side
        }
        if (us == null || them == null) return null
        val status = (comp.optJSONObject("status") ?: e.optJSONObject("status"))?.optJSONObject("type")
        val state = status?.optString("state") ?: "pre"
        val start = runCatching { OffsetDateTime.parse(e.optString("date").let { if (it.length == 17) it.replace("Z", ":00Z") else it }).toInstant().toEpochMilli() }
            .getOrDefault(0L)
        val detail = when (state) {
            "pre" -> if (start > 0) Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("EEE h:mm a", Locale.getDefault())) else status?.optString("shortDetail").orEmpty()
            else -> status?.optString("shortDetail").orEmpty().ifBlank { status?.optString("detail").orEmpty() }
        }
        val link = e.optJSONArray("links")?.optJSONObject(0)?.optString("href")
        return Game(team, us, them, state, detail, start, link)
    }
}

// ---------- Stocks ----------

data class Quote(
    val symbol: String,
    val name: String,
    val price: Double,
    val change: Double,
    val changePct: Double,
    val currency: String,
    /** Today's prices for the little chart. */
    val points: List<Double>,
)

class StocksRepository {

    suspend fun quotes(symbols: List<String>): List<Quote> = withContext(Dispatchers.IO) {
        coroutineScope {
            symbols.map { s -> async { runCatching { quote(s) }.getOrNull() } }.awaitAll().filterNotNull()
        }
    }

    /** Checks a ticker exists (and finds it from a company name). */
    suspend fun lookup(text: String): String? = withContext(Dispatchers.IO) {
        val t = text.trim().uppercase(Locale.ROOT)
        if (t.isEmpty()) return@withContext null
        runCatching { quote(t) }.getOrNull()?.let { return@withContext it.symbol }
        val json = get("https://query1.finance.yahoo.com/v1/finance/search?quotesCount=1&newsCount=0&q=" + URLEncoder.encode(text.trim(), "UTF-8"))
            ?: return@withContext null
        runCatching { JSONObject(json).getJSONArray("quotes").getJSONObject(0).getString("symbol") }.getOrNull()
    }

    private fun quote(symbol: String): Quote? {
        val enc = URLEncoder.encode(symbol, "UTF-8")
        val json = get("https://query1.finance.yahoo.com/v8/finance/chart/$enc?range=1d&interval=15m")
            ?: get("https://query2.finance.yahoo.com/v8/finance/chart/$enc?range=1d&interval=15m")
            ?: return null
        val r = JSONObject(json).getJSONObject("chart").getJSONArray("result").getJSONObject(0)
        val meta = r.getJSONObject("meta")
        val price = meta.optDouble("regularMarketPrice")
        if (price.isNaN()) return null
        val prev = meta.optDouble("chartPreviousClose").takeIf { !it.isNaN() } ?: meta.optDouble("previousClose", price)
        val closes = r.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0)?.optJSONArray("close")
        val pts = closes?.let { a -> (0 until a.length()).mapNotNull { a.optDouble(it).takeIf { v -> !v.isNaN() } } }.orEmpty()
        return Quote(
            symbol = meta.optString("symbol", symbol),
            name = meta.optString("shortName").ifBlank { meta.optString("longName").ifBlank { symbol } },
            price = price,
            change = price - prev,
            changePct = if (prev != 0.0) (price - prev) / prev * 100 else 0.0,
            currency = meta.optString("currency", "USD"),
            points = pts,
        )
    }
}

private fun get(url: String): String? = runCatching {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 8000
    c.readTimeout = 8000
    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16; Pixel) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36")
    c.setRequestProperty("Accept", "application/json")
    try {
        if (c.responseCode !in 200..299) null else c.inputStream.bufferedReader().use { it.readText() }
    } finally {
        c.disconnect()
    }
}.getOrNull()
