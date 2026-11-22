package com.horizon.launcher

import android.content.pm.ApplicationInfo

/** The groups the app drawer sorts apps into. */
enum class AppGroup(val id: String, val label: String) {
    Social("social", "Social"),
    Media("media", "Media"),
    Games("games", "Games"),
    Productivity("productivity", "Productivity"),
    Travel("travel", "Travel"),
    News("news", "News"),
    Shopping("shopping", "Shopping & money"),
    Tools("other", "Tools"),
    Work("work", "Work");

    companion object {
        fun byId(id: String) = entries.firstOrNull { it.id == id } ?: Tools

        /** Uses the category the app declares, then a list of well-known apps. */
        fun of(info: ApplicationInfo, workProfile: Boolean): AppGroup {
            if (workProfile) return Work
            val pkg = info.packageName.lowercase()
            known.entries.firstOrNull { (frag, _) -> pkg.contains(frag) }?.let { return it.value }
            return when (info.category) {
                ApplicationInfo.CATEGORY_GAME -> Games
                ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO, ApplicationInfo.CATEGORY_IMAGE -> Media
                ApplicationInfo.CATEGORY_SOCIAL -> Social
                ApplicationInfo.CATEGORY_NEWS -> News
                ApplicationInfo.CATEGORY_MAPS -> Travel
                ApplicationInfo.CATEGORY_PRODUCTIVITY -> Productivity
                else -> if (info.flags and ApplicationInfo.FLAG_IS_GAME != 0) Games else Tools
            }
        }

        private val known: Map<String, AppGroup> = linkedMapOf(
            // Chat and social
            "whatsapp" to Social, "facebook" to Social, "instagram" to Social, "messenger" to Social,
            "telegram" to Social, "thoughtcrime.securesms" to Social, "discord" to Social, "snapchat" to Social,
            "zhiliaoapp" to Social, "twitter" to Social, "reddit" to Social, "threads" to Social, "bsky" to Social,
            "android.apps.messaging" to Social, "android.dialer" to Social, "contacts" to Social, "linkedin" to Social,
            "pinterest" to Social, "tumblr" to Social, "viber" to Social, "kakao" to Social, "line.android" to Social,
            // Media
            "spotify" to Media, "youtube" to Media, "netflix" to Media, "disney" to Media, "hulu" to Media,
            "primevideo" to Media, "amazon.avod" to Media, "hbo" to Media, "wbd.stream" to Media, "twitch" to Media,
            "soundcloud" to Media, "pandora" to Media, "tidal" to Media, "deezer" to Media, "plex" to Media,
            "apps.photos" to Media, "camera" to Media, "podcast" to Media, "audible" to Media, "vlc" to Media,
            "music" to Media, "peacock" to Media, "paramount" to Media, "crunchyroll" to Media,
            // Work and getting things done
            "android.gm" to Productivity, "apps.docs" to Productivity, "apps.drive" to Productivity,
            "calendar" to Productivity, "android.keep" to Productivity, "office" to Productivity,
            "microsoft.teams" to Productivity, "outlook" to Productivity, "slack" to Productivity,
            "notion" to Productivity, "zoom" to Productivity, "todoist" to Productivity, "evernote" to Productivity,
            "dropbox" to Productivity, "trello" to Productivity, "asana" to Productivity, "apps.meetings" to Productivity,
            "apps.tasks" to Productivity, "onenote" to Productivity, "anthropic" to Productivity, "openai" to Productivity,
            // Getting around
            "apps.maps" to Travel, "waze" to Travel, "uber" to Travel, "lyft" to Travel, "airbnb" to Travel,
            "booking" to Travel, "expedia" to Travel, "tripadvisor" to Travel, "delta" to Travel, "united" to Travel,
            "aa.android" to Travel, "jetblue" to Travel, "southwest" to Travel, "flightradar" to Travel, "transit" to Travel,
            // News and reading
            "magazines" to News, "googlenews" to News, "nytimes" to News, "cnn" to News, "bbc" to News,
            "flipboard" to News, "kindle" to News, "apps.books" to News, "medium" to News, "feedly" to News,
            // Shopping and money
            "amazon.mshop" to Shopping, "ebay" to Shopping, "etsy" to Shopping, "walmart" to Shopping, "target" to Shopping,
            "paypal" to Shopping, "venmo" to Shopping, "cashapp" to Shopping, "squareup.cash" to Shopping,
            "walletnfcrel" to Shopping, "chase" to Shopping, "bankofamerica" to Shopping, "wellsfargo" to Shopping,
            "capitalone" to Shopping, "robinhood" to Shopping, "coinbase" to Shopping, "doordash" to Shopping,
            "ubercab.eats" to Shopping, "grubhub" to Shopping, "instacart" to Shopping, "starbucks" to Shopping,
            "shein" to Shopping, "temu" to Shopping, "costco" to Shopping, "bestbuy" to Shopping,
        )
    }
}

/** Times of day, for suggestions that change from morning to night. */
fun hourBucket(hour: Int): Int = hour / 3

/**
 * Ranks apps for the "Suggested" row: what you open at this time of day counts most,
 * then what you open the hour before or after, then overall use.
 */
fun suggestionScore(key: String, total: Map<String, Int>, hourly: Map<String, Int>, hour: Int): Double {
    val b = hourBucket(hour)
    val now = hourly["$key@$b"] ?: 0
    val near = (hourly["$key@${(b + 7) % 8}"] ?: 0) + (hourly["$key@${(b + 1) % 8}"] ?: 0)
    return now * 4.0 + near * 1.0 + (total[key] ?: 0) * 0.4
}
