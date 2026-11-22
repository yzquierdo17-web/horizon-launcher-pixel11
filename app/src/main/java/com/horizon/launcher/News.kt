package com.horizon.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One headline in the Today page's news feed. */
data class NewsStory(
    val title: String,
    val source: String,
    val link: String,
    /** Epoch millis, 0 if the feed didn't say. */
    val published: Long,
    val imageUrl: String?,
    /** Site address, used for its small icon. */
    val sourceSite: String?,
    val topic: String,
)

/** Google News sections. */
enum class NewsTopic(val id: String, val label: String, val section: String?) {
    Top("top", "Top stories", null),
    World("world", "World", "WORLD"),
    Nation("nation", "Your country", "NATION"),
    Business("business", "Business", "BUSINESS"),
    Technology("tech", "Technology", "TECHNOLOGY"),
    Science("science", "Science", "SCIENCE"),
    Health("health", "Health", "HEALTH"),
    Sports("sports", "Sports", "SPORTS"),
    Entertainment("entertainment", "Entertainment", "ENTERTAINMENT");

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id }
    }
}

/**
 * Headlines from Google News (by topic, for your country and language) plus any RSS or Atom
 * feeds you add. No account needed; nothing about you is sent beyond a normal web request.
 */
class NewsRepository(private val context: Context) {

    suspend fun load(topics: Set<String>, customFeeds: List<String>, hiddenSources: Set<String>): List<NewsStory> =
        withContext(Dispatchers.IO) {
            val locale = Locale.getDefault()
            val lang = locale.language.ifBlank { "en" }
            val country = locale.country.ifBlank { "US" }
            val region = "hl=$lang-$country&gl=$country&ceid=$country:$lang"
            val sources = topics.mapNotNull { NewsTopic.of(it) }.map { t ->
                val url = if (t.section == null) "https://news.google.com/rss?$region"
                else "https://news.google.com/rss/headlines/section/topic/${t.section}?$region"
                url to t.label
            } + customFeeds.filter { it.isNotBlank() }.map { it.trim().replaceFirst("http://", "https://") to "Your feeds" }

            coroutineScope {
                sources.map { (url, topic) -> async { runCatching { fetchFeed(url, topic) }.getOrDefault(emptyList()) } }
                    .awaitAll()
            }
                .flatten()
                .filter { it.source !in hiddenSources }
                .distinctBy { it.title.lowercase(Locale.ROOT).take(60) }
                .sortedByDescending { it.published }
                .take(40)
        }

    private fun fetchFeed(url: String, topic: String): List<NewsStory> {
        val xml = get(url) ?: return emptyList()
        return parse(xml, topic, siteOf(url))
    }

    /** Reads RSS 2.0 and Atom, including the usual image tags (media:content, media:thumbnail, enclosure). */
    private fun parse(xml: String, topic: String, feedSite: String?): List<NewsStory> {
        val p = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }.newPullParser()
        p.setInput(StringReader(xml))
        val out = mutableListOf<NewsStory>()
        var feedTitle: String? = null
        var inItem = false
        var title = ""
        var link = ""
        var date = ""
        var source: String? = null
        var sourceUrl: String? = null
        var image: String? = null
        var tag = ""
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    tag = p.name
                    when (tag) {
                        "item", "entry" -> {
                            inItem = true
                            title = ""; link = ""; date = ""; source = null; sourceUrl = null; image = null
                        }
                        "link" -> if (inItem) p.getAttributeValue(null, "href")?.let { if (link.isEmpty()) link = it }
                        "source" -> if (inItem) sourceUrl = p.getAttributeValue(null, "url")
                        "media:content", "media:thumbnail" -> if (inItem && image == null) {
                            val u = p.getAttributeValue(null, "url")
                            val medium = p.getAttributeValue(null, "medium")
                            if (u != null && (medium == null || medium == "image")) image = u
                        }
                        "enclosure" -> if (inItem && image == null) {
                            val type = p.getAttributeValue(null, "type").orEmpty()
                            if (type.startsWith("image")) image = p.getAttributeValue(null, "url")
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = p.text?.trim().orEmpty()
                    if (text.isEmpty()) continue
                    if (!inItem) {
                        if (tag == "title" && feedTitle == null) feedTitle = text
                        continue
                    }
                    when (tag) {
                        "title" -> title += text
                        "link" -> if (link.isEmpty()) link = text
                        "pubDate", "published", "updated", "dc:date" -> if (date.isEmpty()) date = text
                        "source" -> source = text
                    }
                }
                XmlPullParser.END_TAG -> {
                    if ((p.name == "item" || p.name == "entry") && inItem) {
                        inItem = false
                        val name = source ?: feedTitle ?: siteOf(link) ?: "News"
                        // Google News titles end with " - Source"; drop the repeat.
                        val clean = title.removeSuffix(" - $name").trim()
                        if (clean.isNotEmpty() && link.isNotEmpty()) {
                            out += NewsStory(
                                title = clean,
                                source = name,
                                link = link,
                                published = parseDate(date),
                                imageUrl = image,
                                sourceSite = sourceUrl?.let(::siteOf) ?: feedSite?.takeIf { source == null } ?: siteOf(link),
                                topic = topic,
                            )
                        }
                    }
                    tag = ""
                }
            }
        }
        return out
    }

    private fun parseDate(s: String): Long {
        if (s.isBlank()) return 0
        return runCatching { ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }
            .getOrDefault(0)
    }

    private fun get(url: String): String? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Horizon-Launcher")
        try {
            if (c.responseCode !in 200..299) null else c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    companion object {
        fun siteOf(url: String): String? = runCatching { URL(url).host.removePrefix("www.") }.getOrNull()?.takeIf { it.isNotBlank() }

        /** "5 min ago", "3 h ago", "Yesterday", "Nov 18". */
        fun ago(published: Long, now: Long = System.currentTimeMillis()): String {
            if (published <= 0) return ""
            val mins = (now - published) / 60_000
            return when {
                mins < 1 -> "Just now"
                mins < 60 -> "$mins min ago"
                mins < 24 * 60 -> "${mins / 60} h ago"
                mins < 48 * 60 -> "Yesterday"
                else -> java.time.Instant.ofEpochMilli(published).atZone(java.time.ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()))
            }
        }

        private val images = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        }

        /** Downloads and shrinks a picture for a story card (kept in memory). */
        fun loadImage(url: String, maxPx: Int): Bitmap? {
            images.get(url)?.let { return it }
            return runCatching {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                c.instanceFollowRedirects = true
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Horizon-Launcher")
                val bytes = try { c.inputStream.use { it.readBytes() } } finally { c.disconnect() }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx / 2) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()?.also { images.put(url, it) }
        }

        /** The site's small icon, from Google's public favicon service. */
        fun faviconUrl(site: String) = "https://www.google.com/s2/favicons?sz=64&domain=$site"
    }
}
