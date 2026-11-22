package com.horizon.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** One story in the Today page's news feed. */
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

/** The real article address and its main picture, worked out when a card first shows. */
data class ResolvedStory(val url: String, val image: String?)

/** News topics. */
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
 * Stories from Google News (for your country and language), well-known publishers' own feeds
 * (which come with pictures) and any feeds you add. No account needed.
 */
class NewsRepository(private val context: Context) {

    suspend fun load(
        topics: Set<String>,
        customFeeds: List<String>,
        hiddenSources: Set<String>,
        dismissed: Set<String>,
        likedSources: Set<String>,
    ): List<NewsStory> = withContext(Dispatchers.IO) {
        val locale = Locale.getDefault()
        val lang = locale.language.ifBlank { "en" }
        val country = locale.country.ifBlank { "US" }
        val region = "hl=$lang-$country&gl=$country&ceid=$country:$lang"
        val english = lang == "en"
        val chosen = topics.mapNotNull { NewsTopic.of(it) }
        val sources = chosen.flatMap { t ->
            val google = if (t.section == null) "https://news.google.com/rss?$region"
            else "https://news.google.com/rss/headlines/section/topic/${t.section}?$region"
            listOf(google to t.label) + (if (english) PUBLISHERS[t].orEmpty() else emptyList()).map { it to t.label }
        } + customFeeds.filter { it.isNotBlank() }.map { it.trim().replaceFirst("http://", "https://") to "Your feeds" }

        val all = coroutineScope {
            sources.map { (url, topic) -> async { runCatching { fetchFeed(url, topic) }.getOrDefault(emptyList()) } }
                .awaitAll()
        }.flatten()

        val dayAgo = System.currentTimeMillis() - 3 * 24 * 3600_000L
        val stories = all
            .filter { it.source !in hiddenSources && it.link !in dismissed }
            .filter { it.published == 0L || it.published > dayAgo }
            // Same story from two places: keep the one with a picture.
            .sortedByDescending { it.imageUrl != null }
            .distinctBy { it.title.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit).take(50) }
        rank(stories, likedSources).take(150)
    }

    /** Newest first, a lift for sources you hearted, and no long runs from one site. */
    private fun rank(stories: List<NewsStory>, liked: Set<String>): List<NewsStory> {
        val now = System.currentTimeMillis()
        val scored = stories.sortedByDescending { s ->
            val age = if (s.published > 0) (now - s.published).coerceAtLeast(0) else 12 * 3600_000L
            var score = -age.toDouble()
            if (s.source in liked) score += 5 * 3600_000.0
            if (s.imageUrl != null) score += 1800_000.0
            score
        }.toMutableList()
        val out = ArrayList<NewsStory>(scored.size)
        while (scored.isNotEmpty()) {
            val recent = out.takeLast(3).map { it.source }.toSet()
            val i = scored.take(6).indexOfFirst { it.source !in recent }.takeIf { it >= 0 } ?: 0
            out += scored.removeAt(i)
        }
        return out
    }

    private fun fetchFeed(url: String, topic: String): List<NewsStory> {
        val xml = get(url) ?: return emptyList()
        return parse(xml, topic, siteOf(url))
    }

    /** Reads RSS 2.0 and Atom, with pictures from media tags, enclosures or the first image in the text. */
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
        var imageWidth = 0
        var html = StringBuilder()
        var tag = ""
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    tag = p.name
                    when (tag) {
                        "item", "entry" -> {
                            inItem = true
                            title = ""; link = ""; date = ""; source = null; sourceUrl = null
                            image = null; imageWidth = 0; html = StringBuilder()
                        }
                        "link" -> if (inItem) {
                            val rel = p.getAttributeValue(null, "rel")
                            val href = p.getAttributeValue(null, "href")
                            if (href != null && (rel == null || rel == "alternate") && link.isEmpty()) link = href
                        }
                        "source" -> if (inItem) sourceUrl = p.getAttributeValue(null, "url")
                        "media:content", "media:thumbnail" -> if (inItem) {
                            val u = p.getAttributeValue(null, "url")
                            val medium = p.getAttributeValue(null, "medium")
                            val type = p.getAttributeValue(null, "type").orEmpty()
                            val w = p.getAttributeValue(null, "width")?.toIntOrNull() ?: 1
                            val isImage = (medium == null || medium == "image") && (type.isEmpty() || type.startsWith("image"))
                            if (u != null && isImage && (image == null || w > imageWidth)) {
                                image = u; imageWidth = w
                            }
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
                        "description", "content:encoded", "content", "summary" -> if (html.length < 20_000) html.append(text)
                    }
                }
                XmlPullParser.END_TAG -> {
                    if ((p.name == "item" || p.name == "entry") && inItem) {
                        inItem = false
                        val name = source ?: feedTitle?.let(::cleanFeedName) ?: siteOf(link) ?: "News"
                        // Google News titles end with " - Source"; drop the repeat.
                        val clean = unescape(title.removeSuffix(" - $name").trim())
                        val pic = (image ?: firstImage(html))?.let(::betterImage)
                        if (clean.isNotEmpty() && link.isNotEmpty()) {
                            out += NewsStory(
                                title = clean,
                                source = name,
                                link = link.trim(),
                                published = parseDate(date),
                                imageUrl = pic,
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

    // ---------- Real links and pictures ----------

    private val resolved = ConcurrentHashMap<String, ResolvedStory>()
    private val gate = Semaphore(3)

    /** Cached answer, if this story was already worked out. */
    fun cached(story: NewsStory): ResolvedStory? = resolved[story.link]

    /**
     * Finds the publisher's own address for Google News links, and the article's main picture
     * (the one sites use when shared) when the feed didn't include one.
     */
    suspend fun resolve(story: NewsStory): ResolvedStory = resolved[story.link] ?: withContext(Dispatchers.IO) {
        gate.withPermit {
            resolved[story.link]?.let { return@withPermit it }
            val url = if (isGoogleNews(story.link)) decodeGoogleNews(story.link) ?: story.link else story.link
            val image = story.imageUrl ?: if (!isGoogleNews(url)) pageImage(url) else null
            ResolvedStory(url, image).also { resolved[story.link] = it }
        }
    }

    private fun isGoogleNews(url: String) = runCatching { URL(url).host == "news.google.com" }.getOrDefault(false)

    /** Google News hides the real address behind a signed id; trade it for the real one. */
    private fun decodeGoogleNews(link: String): String? = runCatching {
        val uri = Uri.parse(link)
        val segs = uri.pathSegments
        val idx = segs.indexOfFirst { it == "articles" || it == "read" }
        val id = segs.getOrNull(idx + 1) ?: return null
        val hl = uri.getQueryParameter("hl") ?: "en-US"
        val gl = uri.getQueryParameter("gl") ?: "US"
        val ceid = uri.getQueryParameter("ceid") ?: "US:en"
        val page = get("https://news.google.com/rss/articles/$id?hl=${enc(hl)}&gl=${enc(gl)}&ceid=${enc(ceid)}") ?: return null
        val sig = Regex("data-n-a-sg=\"([^\"]+)\"").find(page)?.groupValues?.get(1) ?: return null
        val ts = Regex("data-n-a-ts=\"([^\"]+)\"").find(page)?.groupValues?.get(1) ?: return null

        val ctx = JSONArray(
            """[["X","X",["X","X"],null,null,1,1,"US:en",null,1,null,null,null,null,null,0,1],"X","X",1,[1,1,1],1,1,null,0,0,null,0]""",
        )
        val inner = JSONArray().put("garturlreq").put(ctx).put(id).put(ts.toLongOrNull() ?: ts).put(sig)
        val envelope = JSONArray().put("Fbv4je").put(inner.toString()).put(org.json.JSONObject.NULL).put("1")
        val body = "f.req=" + enc(JSONArray().put(JSONArray().put(envelope)).toString())
        val res = request(
            "https://news.google.com/_/DotsSplashUi/data/batchexecute",
            body,
            "application/x-www-form-urlencoded;charset=UTF-8",
        ) ?: return null
        parseBatch(res)
    }.getOrNull()?.takeIf { it.startsWith("http") }

    private fun parseBatch(text: String): String? {
        runCatching {
            var body = text.substringAfter("\n\n", text).trimStart()
            if (body.startsWith(")]}'")) body = body.substringAfter('\n').trimStart()
            val rows = JSONArray(body)
            for (i in 0 until rows.length()) {
                val row = rows.optJSONArray(i) ?: continue
                if (row.optString(0) != "wrb.fr") continue
                val payload = JSONArray(row.optString(2))
                if (payload.optString(0) == "garturlres") return payload.optString(1)
            }
        }
        // Fallback: pull it straight out of the text.
        return Regex("""garturlres\\",\\"(https?:[^\\"]+)""").find(text)?.groupValues?.get(1)
            ?.replace("\\\\u003d", "=")?.replace("\\\\u0026", "&")
    }

    /** The page's sharing picture (og:image or twitter:image). */
    private fun pageImage(url: String): String? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", BROWSER_UA)
        c.setRequestProperty("Accept", "text/html")
        try {
            if (c.responseCode !in 200..299) return null
            // The picture is in the page's head, so the first part is enough.
            val buf = CharArray(160_000)
            val n = c.inputStream.bufferedReader().use { r ->
                var total = 0
                while (total < buf.size) {
                    val k = r.read(buf, total, buf.size - total)
                    if (k < 0) break
                    total += k
                    if (String(buf, maxOf(0, total - k - 10), k + minOf(10, total - k)).contains("</head>", true)) break
                }
                total
            }
            val head = String(buf, 0, n)
            val metas = Regex("<meta\\s[^>]*>", RegexOption.IGNORE_CASE).findAll(head).map { it.value }
            val keys = listOf("og:image", "og:image:url", "og:image:secure_url", "twitter:image", "twitter:image:src")
            val byKey = metas.mapNotNull { m ->
                val k = Regex("(?:property|name)\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(m)?.groupValues?.get(1)?.lowercase()
                val v = Regex("content\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(m)?.groupValues?.get(1)
                if (k != null && v != null && k in keys) k to v else null
            }.toMap()
            val raw = keys.firstNotNullOfOrNull { byKey[it] } ?: return null
            val abs = URL(URL(url), unescape(raw)).toString()
            if (abs.startsWith("https://")) abs else abs.replaceFirst("http://", "https://")
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    private fun parseDate(s: String): Long {
        if (s.isBlank()) return 0
        return runCatching { ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }
            .recoverCatching { ZonedDateTime.parse(s.replace(" GMT", " +0000").replace(" UTC", " +0000"), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
            .getOrDefault(0)
    }

    private fun get(url: String): String? = request(url, null, null)

    private fun request(url: String, body: String?, contentType: String?): String? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", BROWSER_UA)
        if (body != null) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", contentType)
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        try {
            if (c.responseCode !in 200..299) null else c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 16; Pixel) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"

        /** Publishers' own feeds, which carry pictures. English only. */
        private val PUBLISHERS: Map<NewsTopic, List<String>> = mapOf(
            NewsTopic.Top to listOf(
                "https://rss.nytimes.com/services/xml/rss/nyt/HomePage.xml",
                "https://feeds.bbci.co.uk/news/rss.xml",
                "https://feeds.npr.org/1001/rss.xml",
                "https://www.theguardian.com/us-news/rss",
            ),
            NewsTopic.World to listOf(
                "https://rss.nytimes.com/services/xml/rss/nyt/World.xml",
                "https://feeds.bbci.co.uk/news/world/rss.xml",
                "https://www.theguardian.com/world/rss",
                "https://www.aljazeera.com/xml/rss/all.xml",
            ),
            NewsTopic.Nation to listOf(
                "https://rss.nytimes.com/services/xml/rss/nyt/US.xml",
                "https://feeds.npr.org/1003/rss.xml",
                "https://www.cbsnews.com/latest/rss/us",
            ),
            NewsTopic.Business to listOf(
                "https://rss.nytimes.com/services/xml/rss/nyt/Business.xml",
                "https://feeds.bbci.co.uk/news/business/rss.xml",
                "https://search.cnbc.com/rs/search/combinedcms/view.xml?partnerId=wrss01&id=10001147",
            ),
            NewsTopic.Technology to listOf(
                "https://www.theverge.com/rss/index.xml",
                "https://feeds.arstechnica.com/arstechnica/index",
                "https://www.engadget.com/rss.xml",
                "https://9to5google.com/feed/",
                "https://techcrunch.com/feed/",
            ),
            NewsTopic.Science to listOf(
                "https://rss.nytimes.com/services/xml/rss/nyt/Science.xml",
                "https://feeds.bbci.co.uk/news/science_and_environment/rss.xml",
                "https://www.space.com/feeds/all",
            ),
            NewsTopic.Health to listOf(
                "https://rss.nytimes.com/services/xml/rss/nyt/Health.xml",
                "https://feeds.bbci.co.uk/news/health/rss.xml",
                "https://feeds.npr.org/1128/rss.xml",
            ),
            NewsTopic.Sports to listOf(
                "https://www.espn.com/espn/rss/news",
                "https://www.cbssports.com/rss/headlines/",
                "https://feeds.bbci.co.uk/sport/rss.xml",
            ),
            NewsTopic.Entertainment to listOf(
                "https://variety.com/feed/",
                "https://rss.nytimes.com/services/xml/rss/nyt/Arts.xml",
                "https://feeds.bbci.co.uk/news/entertainment_and_arts/rss.xml",
            ),
        )

        fun siteOf(url: String): String? = runCatching { URL(url).host.removePrefix("www.") }.getOrNull()?.takeIf { it.isNotBlank() }

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        /** "NYT > Top Stories" → "The New York Times", "BBC News - Home" → "BBC News". */
        private fun cleanFeedName(t: String): String {
            val n = t.trim()
            return when {
                n.startsWith("NYT", true) -> "The New York Times"
                n.startsWith("BBC Sport", true) -> "BBC Sport"
                n.startsWith("BBC", true) -> "BBC News"
                n.contains("Guardian", true) -> "The Guardian"
                n.contains("NPR") -> "NPR"
                n.startsWith("Ars Technica", true) -> "Ars Technica"
                n.startsWith("ESPN", true) -> "ESPN"
                n.contains("CBSSports", true) || n.startsWith("CBS Sports", true) -> "CBS Sports"
                n.startsWith("CBS News", true) -> "CBS News"
                n.startsWith("CNBC", true) || n.contains("CNBC") -> "CNBC"
                n.startsWith("Al Jazeera", true) -> "Al Jazeera"
                n.startsWith("Space", true) -> "Space.com"
                n.startsWith("TechCrunch", true) -> "TechCrunch"
                n.startsWith("Engadget", true) -> "Engadget"
                n.startsWith("9to5Google", true) -> "9to5Google"
                n.startsWith("Variety", true) -> "Variety"
                else -> n.substringBefore(" - ").substringBefore(" | ").substringBefore(" > ").trim().ifEmpty { n }
            }
        }

        private fun firstImage(html: CharSequence): String? =
            Regex("<img[^>]+src\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
                ?.let(::unescape)
                ?.takeIf { it.startsWith("http") && !it.contains("feeds.feedburner") && !it.contains("pixel") && !it.endsWith(".gif") }

        /** Asks for a bigger version of small feed pictures where the site allows it. */
        private fun betterImage(u: String): String {
            var s = u.replaceFirst("http://", "https://")
            if ("ichef.bbci.co.uk" in s) s = s.replace(Regex("/(\\d{2,3})/cpsprodpb"), "/976/cpsprodpb").replace("/standard/240/", "/standard/976/")
            return s
        }

        private fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&#039;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&#8217;", "’").replace("&#8216;", "‘")
            .replace("&#8220;", "“").replace("&#8221;", "”").replace("&#8211;", "–").replace("&#8212;", "—").replace("&nbsp;", " ")

        /** "5m", "3h", "2d" like Discover. */
        fun ago(published: Long, now: Long = System.currentTimeMillis()): String {
            if (published <= 0) return ""
            val mins = (now - published) / 60_000
            return when {
                mins < 1 -> "Now"
                mins < 60 -> "${mins}m"
                mins < 24 * 60 -> "${mins / 60}h"
                else -> "${mins / (24 * 60)}d"
            }
        }

        private val images = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        }

        fun cachedImage(url: String): Bitmap? = images.get(url)

        /** Downloads and shrinks a picture for a story card (kept in memory). */
        fun loadImage(url: String, maxPx: Int): Bitmap? {
            images.get(url)?.let { return it }
            return runCatching {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 10_000
                c.instanceFollowRedirects = true
                c.setRequestProperty("User-Agent", BROWSER_UA)
                val bytes = try { c.inputStream.use { it.readBytes() } } finally { c.disconnect() }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth in 1..59) return null // tracking pixels and icons
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= maxPx) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()?.also { images.put(url, it) }
        }

        /** The site's small icon, from Google's public favicon service. */
        fun faviconUrl(site: String) = "https://www.google.com/s2/favicons?sz=64&domain=$site"
    }
}
