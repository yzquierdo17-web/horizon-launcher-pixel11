package com.horizon.launcher

import org.json.JSONArray
import org.json.JSONObject

const val GRID_COLS = 5
const val GRID_ROWS = 5
const val MAX_DOCK = 5

const val TYPE_APP = 0
const val TYPE_FOLDER = 1
const val TYPE_WIDGET = 2

/**
 * One thing on a home screen page. Apps and folders are 1x1; widgets can span cells.
 * App references are stored as "flattenedComponent#userSerial" keys.
 */
data class HomeItem(
    val id: Long,
    val type: Int,
    val page: Int,
    val x: Int,
    val y: Int,
    val spanX: Int = 1,
    val spanY: Int = 1,
    val apps: List<String> = emptyList(),
    val name: String = "",
    val widgetId: Int = -1,
    /** More widgets stacked in the same spot (swipe up and down to switch). */
    val stack: List<Int> = emptyList(),
) {
    fun covers(cx: Int, cy: Int) = cx in x until x + spanX && cy in y until y + spanY
}

sealed interface DragPayload {
    data class FromDrawer(val key: String) : DragPayload
    data class FromHome(val itemId: Long) : DragPayload
    data class FromDock(val index: Int) : DragPayload
    data class FromFolder(val folderId: Long, val index: Int) : DragPayload
}

sealed interface DropTarget {
    data class Home(val page: Int, val x: Int, val y: Int) : DropTarget
    data class Dock(val index: Int) : DropTarget
    data object Remove : DropTarget
    data object None : DropTarget
}

fun encodeItems(items: List<HomeItem>): String {
    val arr = JSONArray()
    items.forEach {
        arr.put(
            JSONObject()
                .put("id", it.id)
                .put("t", it.type)
                .put("p", it.page)
                .put("x", it.x)
                .put("y", it.y)
                .put("sx", it.spanX)
                .put("sy", it.spanY)
                .put("n", it.name)
                .put("w", it.widgetId)
                .put("st", JSONArray(it.stack))
                .put("a", JSONArray(it.apps)),
        )
    }
    return arr.toString()
}

fun decodeItems(json: String?): List<HomeItem> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val a = o.optJSONArray("a") ?: JSONArray()
            HomeItem(
                id = o.getLong("id"),
                type = o.getInt("t"),
                page = o.getInt("p"),
                x = o.getInt("x"),
                y = o.getInt("y"),
                spanX = o.optInt("sx", 1),
                spanY = o.optInt("sy", 1),
                apps = (0 until a.length()).map { a.getString(it) },
                name = o.optString("n", ""),
                widgetId = o.optInt("w", -1),
                stack = o.optJSONArray("st")?.let { s -> (0 until s.length()).map { s.getInt(it) } } ?: emptyList(),
            )
        }
    }.getOrDefault(emptyList())
}

fun encodeKeys(keys: List<String>): String = JSONArray(keys).toString()

fun decodeKeys(json: String?): List<String> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map { arr.getString(it) }
    }.getOrDefault(emptyList())
}

/** Renumber pages so there are no empty pages in between. */
fun compactPages(items: List<HomeItem>): List<HomeItem> {
    val used = items.map { it.page }.distinct().sorted()
    val remap = used.withIndex().associate { (i, p) -> p to i }
    return items.map { it.copy(page = remap.getValue(it.page)) }
}

fun fits(items: List<HomeItem>, page: Int, x: Int, y: Int, sx: Int, sy: Int, excludeId: Long = -1): Boolean {
    if (x < 0 || y < 0 || x + sx > GRID_COLS || y + sy > GRID_ROWS) return false
    return items.none { other ->
        other.id != excludeId && other.page == page &&
            x < other.x + other.spanX && other.x < x + sx &&
            y < other.y + other.spanY && other.y < y + sy
    }
}

/** Closest free spot on a page for an item of the given size, or null if it's full. */
fun findFree(
    items: List<HomeItem>,
    page: Int,
    sx: Int,
    sy: Int,
    nearX: Int = 0,
    nearY: Int = 0,
    excludeId: Long = -1,
): Pair<Int, Int>? {
    var best: Pair<Int, Int>? = null
    var bestDist = Int.MAX_VALUE
    for (y in 0..GRID_ROWS - sy) for (x in 0..GRID_COLS - sx) {
        if (fits(items, page, x, y, sx, sy, excludeId)) {
            val d = (x - nearX) * (x - nearX) + (y - nearY) * (y - nearY)
            if (d < bestDist) {
                bestDist = d
                best = x to y
            }
        }
    }
    return best
}

/** First free spot reading order, starting at [startPage]; adds a new page if needed. */
fun findFreeAnywhere(items: List<HomeItem>, startPage: Int, sx: Int, sy: Int): Triple<Int, Int, Int> {
    val lastPage = items.maxOfOrNull { it.page } ?: 0
    for (page in startPage.coerceAtLeast(0)..lastPage + 1) {
        for (y in 0..GRID_ROWS - sy) for (x in 0..GRID_COLS - sx) {
            if (fits(items, page, x, y, sx, sy)) return Triple(page, x, y)
        }
    }
    return Triple(lastPage + 1, 0, 0)
}

data class LauncherSettings(
    val themedIcons: Boolean = false,
    val iconScale: Float = 1f,
    val homeLabels: Boolean = true,
    val compactClock: Boolean = false,
    /** Rotating dial clock (watch-bezel style). Takes priority over compactClock. */
    val dialClock: Boolean = false,
    val showSearch: Boolean = true,
    val wallpaperBlur: Boolean = true,
    val hidden: Set<String> = emptySet(),
    // Work hours. Minutes since midnight; days are ISO numbers (1 = Monday … 7 = Sunday).
    val workHours: Boolean = false,
    val workStart: Int = 9 * 60,
    val workEnd: Int = 18 * 60,
    val workDays: Set<Int> = setOf(1, 2, 3, 4, 5),
    /** Pause the work profile outside work hours and turn it back on when they start. */
    val pauseWorkAfterHours: Boolean = false,
    val showMeetings: Boolean = true,
    /** Set once the user has answered or dismissed the "show your meetings" prompt. */
    val meetingPromptDone: Boolean = false,
    val suggestions: Boolean = true,
    /** Apps you've put on the Work tab yourself (app keys), on top of any work-profile apps. */
    val workApps: Set<String> = emptySet(),
    /** IconShape id. "system" keeps the phone's own icon shape. */
    val iconShape: String = "system",
    /** Package of the chosen icon pack, or "" for none. */
    val iconPack: String = "",
    /** Dot on app icons with unread notifications (needs notification access). */
    val notificationDots: Boolean = true,
    /** Show the number of notifications instead of a plain dot. */
    val dotCounts: Boolean = false,
    /** Double-tap empty space on Home to lock the screen (uses the island helper). */
    val doubleTapLock: Boolean = true,
    /** Minimal mode: favourite apps as a text list, text-only drawer. */
    val minimalMode: Boolean = false,
    /** Favourites for minimal mode (app keys). Empty = use the dock and first Home page. */
    val minimalFavorites: List<String> = emptyList(),
    /** Hidden apps need fingerprint / screen lock to view. */
    val lockHidden: Boolean = true,
    /** Focus mode: distracting apps greyed out and their dots hidden. */
    val focusOn: Boolean = false,
    val focusDuringWork: Boolean = false,
    val focusApps: Set<String> = emptySet(),
    val showWeather: Boolean = true,
    /** City for weather, or "" to use your approximate location. */
    val weatherCity: String = "",
    /** "auto", "c" or "f". */
    val weatherUnit: String = "auto",
    val weatherPromptDone: Boolean = false,
    val contactsPromptDone: Boolean = false,
    /** "inter" or "system". */
    val appFont: String = "inter",
    /** HorizonLook id: wallpaper, graphite, midnight or amoled. */
    val theme: String = "wallpaper",
    /** Wallpaper glides sideways as you swipe Home pages. */
    val parallax: Boolean = true,
    /** ClockFace id (large, compact, dial, dial_min, dial_bold, dial_outline, stacked, words). */
    val clockFace: String = "large",
    /** App label size: "small", "medium" or "large". */
    val labelSize: String = "medium",
    /** News section on the Today page. */
    val showNews: Boolean = true,
    /** NewsTopic ids. */
    val newsTopics: Set<String> = setOf("top", "tech"),
    /** Extra RSS or Atom feed addresses. */
    val newsFeeds: List<String> = emptyList(),
    /** Sources you chose to hide. */
    val newsHidden: Set<String> = emptySet(),
    /** Show pictures on story cards. */
    val newsImages: Boolean = true,
    /** "Flights overhead" card on the Today page. */
    val showFlights: Boolean = true,
    /** Radar range step, 0..3 (see FlightRadar ranges). */
    val flightRange: Int = 1,
) {
    fun isWorkTime(now: java.time.LocalDateTime): Boolean {
        if (!workHours || now.dayOfWeek.value !in workDays) return false
        val m = now.hour * 60 + now.minute
        return if (workStart <= workEnd) m in workStart until workEnd
        else m >= workStart || m < workEnd // overnight shifts
    }

    fun toJson(): JSONObject = JSONObject()
        .put("themed", themedIcons)
        .put("scale", iconScale.toDouble())
        .put("labels", homeLabels)
        .put("compact", compactClock)
        .put("dialClock", dialClock)
        .put("search", showSearch)
        .put("blur", wallpaperBlur)
        .put("hidden", JSONArray(hidden.toList()))
        .put("workHours", workHours)
        .put("workStart", workStart)
        .put("workEnd", workEnd)
        .put("workDays", JSONArray(workDays.toList()))
        .put("workPause", pauseWorkAfterHours)
        .put("meetings", showMeetings)
        .put("meetingPrompt", meetingPromptDone)
        .put("suggestions", suggestions)
        .put("workApps", JSONArray(workApps.toList()))
        .put("iconShape", iconShape)
        .put("iconPack", iconPack)
        .put("dots", notificationDots)
        .put("dotCounts", dotCounts)
        .put("dtLock", doubleTapLock)
        .put("minimal", minimalMode)
        .put("minFav", JSONArray(minimalFavorites))
        .put("lockHidden", lockHidden)
        .put("focusOn", focusOn)
        .put("focusWork", focusDuringWork)
        .put("focusApps", JSONArray(focusApps.toList()))
        .put("weather", showWeather)
        .put("weatherCity", weatherCity)
        .put("weatherUnit", weatherUnit)
        .put("weatherPrompt", weatherPromptDone)
        .put("contactsPrompt", contactsPromptDone)
        .put("font", appFont)
        .put("theme", theme)
        .put("parallax", parallax)
        .put("clockFace", clockFace)
        .put("labelSize", labelSize)
        .put("news", showNews)
        .put("newsTopics", JSONArray(newsTopics.toList()))
        .put("newsFeeds", JSONArray(newsFeeds))
        .put("newsHidden", JSONArray(newsHidden.toList()))
        .put("newsImages", newsImages)
        .put("flights", showFlights)
        .put("flightRange", flightRange)

    companion object {
        fun fromJson(o: JSONObject?): LauncherSettings {
            if (o == null) return LauncherSettings()
            val h = o.optJSONArray("hidden") ?: JSONArray()
            val d = o.optJSONArray("workDays")
            val wa = o.optJSONArray("workApps") ?: JSONArray()
            return LauncherSettings(
                themedIcons = o.optBoolean("themed", false),
                iconScale = o.optDouble("scale", 1.0).toFloat(),
                homeLabels = o.optBoolean("labels", true),
                compactClock = o.optBoolean("compact", false),
                dialClock = o.optBoolean("dialClock", false),
                showSearch = o.optBoolean("search", true),
                wallpaperBlur = o.optBoolean("blur", true),
                hidden = (0 until h.length()).map { h.getString(it) }.toSet(),
                workHours = o.optBoolean("workHours", false),
                workStart = o.optInt("workStart", 9 * 60),
                workEnd = o.optInt("workEnd", 18 * 60),
                workDays = d?.let { arr -> (0 until arr.length()).map { arr.getInt(it) }.toSet() } ?: setOf(1, 2, 3, 4, 5),
                pauseWorkAfterHours = o.optBoolean("workPause", false),
                showMeetings = o.optBoolean("meetings", true),
                meetingPromptDone = o.optBoolean("meetingPrompt", false),
                suggestions = o.optBoolean("suggestions", true),
                workApps = (0 until wa.length()).map { wa.getString(it) }.toSet(),
                iconShape = o.optString("iconShape", "system"),
                iconPack = o.optString("iconPack", ""),
                notificationDots = o.optBoolean("dots", true),
                dotCounts = o.optBoolean("dotCounts", false),
                doubleTapLock = o.optBoolean("dtLock", true),
                minimalMode = o.optBoolean("minimal", false),
                minimalFavorites = o.optJSONArray("minFav")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                lockHidden = o.optBoolean("lockHidden", true),
                focusOn = o.optBoolean("focusOn", false),
                focusDuringWork = o.optBoolean("focusWork", false),
                focusApps = o.optJSONArray("focusApps")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet(),
                showWeather = o.optBoolean("weather", true),
                weatherCity = o.optString("weatherCity", ""),
                weatherUnit = o.optString("weatherUnit", "auto"),
                weatherPromptDone = o.optBoolean("weatherPrompt", false),
                contactsPromptDone = o.optBoolean("contactsPrompt", false),
                appFont = o.optString("font", "inter"),
                theme = o.optString("theme", "wallpaper"),
                parallax = o.optBoolean("parallax", true),
                // Older settings only had the Large / Compact / Dial switches.
                clockFace = o.optString(
                    "clockFace",
                    when {
                        o.optBoolean("dialClock", false) -> "dial"
                        o.optBoolean("compact", false) -> "compact"
                        else -> "large"
                    },
                ),
                labelSize = o.optString("labelSize", "medium"),
                showNews = o.optBoolean("news", true),
                newsTopics = o.optJSONArray("newsTopics")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: setOf("top", "tech"),
                newsFeeds = o.optJSONArray("newsFeeds")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                newsHidden = o.optJSONArray("newsHidden")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet(),
                newsImages = o.optBoolean("newsImages", true),
                showFlights = o.optBoolean("flights", true),
                flightRange = o.optInt("flightRange", 1).coerceIn(0, 3),
            )
        }
    }
}

/** The managed (work) profile, if this phone has one. */
data class WorkProfile(val user: android.os.UserHandle, val paused: Boolean)

fun formatMinute(minuteOfDay: Int, is24h: Boolean): String {
    val h = minuteOfDay / 60
    val m = minuteOfDay % 60
    return if (is24h) "%02d:%02d".format(h, m)
    else "%d:%02d %s".format(if (h % 12 == 0) 12 else h % 12, m, if (h < 12) "AM" else "PM")
}
