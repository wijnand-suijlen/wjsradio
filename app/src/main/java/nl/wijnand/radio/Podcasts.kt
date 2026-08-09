package nl.wijnand.radio

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Podcast(
    val broadcaster: String,
    val title: String,
    val feedUrl: String,
    val custom: Boolean = false,
)

data class Episode(
    val title: String,
    val audioUrl: String,
    val pubDate: String,       // display string in Dutch, e.g. "5 mei 2003"
    val pubDateMillis: Long,   // 0 when the feed date could not be parsed
    val duration: String,
    val description: String,
)

// Curated podcast feeds from the same broadcasters. Feeds verified 2026-08.
object CuratedPodcasts {
    val all = listOf(
        Podcast("VPRO", "Radio Bergeijk", "https://podcast.npo.nl/feed/radio-bergeijk.xml"),
        Podcast("VARA", "Spijkers met Koppen", "https://podcast.npo.nl/feed/spijkers-met-koppen.xml"),
        Podcast("VPRO", "OVT", "https://podcast.npo.nl/feed/ovt.xml"),
        Podcast("MAX", "Nieuwsweekend", "https://podcast.npo.nl/feed/nieuwsweekend.xml"),
        Podcast("VARA", "Vroege Vogels", "https://podcast.npo.nl/feed/vroegevogels.xml"),
        Podcast("NPO / NOS", "Met het Oog op Morgen", "https://podcast.npo.nl/feed/met-het-oog-op-morgen.xml"),
        Podcast("NPO / NOS", "De Dag", "https://podcast.npo.nl/feed/de-dag.xml"),
        Podcast("BNR", "De Strateeg", "https://www.omnycontent.com/d/playlist/8257a063-6be9-42fa-b892-acd4013b1255/3b46ddb7-dbf9-4035-8eb3-adde00a34fee/8d36352e-775b-4790-9e0f-adde00a37bdd/podcast.rss"),
        Podcast("BNR", "Boekestijn en De Wijk", "https://www.omnycontent.com/d/playlist/8257a063-6be9-42fa-b892-acd4013b1255/183dc0a5-9b77-48f3-9970-acef00cf8471/790dd6fa-cb55-4956-ba88-acef00cfc0f6/podcast.rss"),
        Podcast("VRT", "De Wereld van Sofie", "https://podcasts.vrt.be/v1/program-8e873646-d513-4880-ab62-f19f31990d78"),
        Podcast("VRT", "De afspraak", "https://podcasts.vrt.be/v1/program-a5d31f10-e64e-4658-8b25-e45eaa8756da"),
        Podcast("RTBF", "Un Jour dans l'Histoire", "https://feeds.audiomeans.fr/feed/6fb5b06a-59ac-473f-a5a7-e4ed3b568346.xml"),
        Podcast("RTBF", "Matin Première", "https://feeds.audiomeans.fr/feed/693a4d43-7914-4b19-b011-b74f09ee4517.xml"),
        Podcast("WDR", "WDR Zeitzeichen", "https://www1.wdr.de/mediathek/audio/zeitzeichen/zeitzeichen-podcast-100.podcast"),
        Podcast("Deutschlandfunk", "Der Tag", "https://www.deutschlandfunk.de/podcast-104.xml"),
        Podcast("Deutschlandfunk", "Hintergrund", "https://www.deutschlandfunk.de/hintergrund-102.xml"),
        Podcast("Radio France", "Avec philosophie", "https://radiofrance-podcast.net/podcast09/rss_10467.xml"),
        Podcast("Radio France", "Affaires sensibles", "https://radiofrance-podcast.net/podcast09/rss_13940.xml"),
        Podcast("Radio France", "Le Masque et la Plume", "https://radiofrance-podcast.net/podcast09/rss_14007.xml"),
        Podcast("BBC", "Global News Podcast", "https://podcasts.files.bbci.co.uk/p02nq0gn.rss"),
        Podcast("BBC", "In Our Time", "https://podcasts.files.bbci.co.uk/b006qykl.rss"),
        Podcast("BBC", "Desert Island Discs", "https://podcasts.files.bbci.co.uk/b006qnmr.rss"),
        Podcast("BBC", "Newscast", "https://podcasts.files.bbci.co.uk/p05299nl.rss"),
    )
}

data class PodcastCandidate(
    val title: String,
    val author: String,
    val feedUrl: String,
)

// Finds the podcast belonging to a broadcast programme via the iTunes Search
// API (keyless). The country store matters: French/German/Belgian podcasts are
// often missing from the default (US) store.
object PodcastFinder {

    private val countryCodes = mapOf(
        "Nederland" to "nl",
        "België" to "be",
        "Duitsland" to "de",
        "Frankrijk" to "fr",
        "Verenigd Koninkrijk" to "gb",
    )

    suspend fun search(programmeTitle: String, broadcaster: String, country: String): List<PodcastCandidate> =
        withContext(Dispatchers.IO) {
            val cc = countryCodes[country] ?: "nl"
            // iTunes can list the same feed under several collections; the feedUrl
            // is the podcast list's key, so it must be unique.
            query(programmeTitle, cc).ifEmpty { query("$programmeTitle $broadcaster", cc) }
                .distinctBy { it.feedUrl }
        }

    private fun query(term: String, countryCode: String): List<PodcastCandidate> {
        val url = "https://itunes.apple.com/search?media=podcast&limit=8&country=$countryCode" +
            "&term=" + java.net.URLEncoder.encode(term, "UTF-8")
        val results = org.json.JSONObject(RssFetcher.httpGet(url)).optJSONArray("results") ?: return emptyList()
        val candidates = mutableListOf<PodcastCandidate>()
        for (i in 0 until results.length()) {
            val o = results.getJSONObject(i)
            val feed = o.optString("feedUrl")
            if (feed.startsWith("http")) {
                candidates.add(PodcastCandidate(o.optString("collectionName"), o.optString("artistName"), feed))
            }
        }
        return candidates
    }
}

object RssFetcher {

    suspend fun fetchEpisodes(feedUrl: String): Pair<String, List<Episode>> = withContext(Dispatchers.IO) {
        val body = httpGet(feedUrl)
        parseFeed(body)
    }

    // Also used by ScheduleFetcher
    internal fun httpGet(url: String, redirectsLeft: Int = 5): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "RadioApp/1.0")
        try {
            val code = conn.responseCode
            // HttpURLConnection does not follow cross-protocol redirects itself
            if (code in 300..399 && redirectsLeft > 0) {
                val location = conn.getHeaderField("Location") ?: throw IllegalStateException("Redirect zonder Location")
                return httpGet(URL(URL(url), location).toString(), redirectsLeft - 1)
            }
            if (code != 200) throw IllegalStateException("HTTP $code voor $url")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Returns channel title + episodes. */
    fun parseFeed(xml: String): Pair<String, List<Episode>> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(xml.reader())

        var channelTitle = ""
        val episodes = mutableListOf<Episode>()

        var inItem = false
        var title = ""
        var audioUrl = ""
        var pubDate = ""
        var duration = ""
        var description = ""

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "item" -> {
                        inItem = true
                        title = ""; audioUrl = ""; pubDate = ""; duration = ""; description = ""
                    }
                    "title" -> {
                        val text = parser.nextText().trim()
                        if (inItem) title = text else if (channelTitle.isEmpty()) channelTitle = text
                    }
                    "enclosure" -> if (inItem && audioUrl.isEmpty()) {
                        audioUrl = parser.getAttributeValue(null, "url") ?: ""
                    }
                    "pubDate" -> if (inItem) pubDate = parser.nextText().trim()
                    "duration" -> if (inItem) duration = parser.nextText().trim()
                    "description" -> if (inItem && description.isEmpty()) description = stripHtml(parser.nextText())
                }
                XmlPullParser.END_TAG -> if (parser.name == "item") {
                    inItem = false
                    if (audioUrl.isNotEmpty()) {
                        val millis = parseDate(pubDate)
                        episodes.add(
                            Episode(title, audioUrl, displayDate(millis, pubDate), millis, formatDuration(duration), description)
                        )
                    }
                }
            }
            event = parser.next()
        }
        // Duplicate enclosure URLs would violate LazyColumn's unique-key requirement
        return channelTitle to episodes.distinctBy { it.audioUrl }
    }

    private fun stripHtml(s: String): String =
        s.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()

    // RSS pubDate variants seen in the wild ("Fri, 07 Aug 2026 22:00:00 GMT", "+0200", no weekday, no seconds)
    private val dateFormats = listOf(
        "EEE, d MMM yyyy HH:mm:ss Z",
        "EEE, d MMM yyyy HH:mm:ss zzz",
        "EEE, d MMM yyyy HH:mm Z",
        "d MMM yyyy HH:mm:ss Z",
        "d MMM yyyy HH:mm:ss zzz",
    )

    private fun parseDate(pubDate: String): Long {
        if (pubDate.isEmpty()) return 0L
        for (format in dateFormats) {
            try {
                return SimpleDateFormat(format, Locale.ENGLISH).parse(pubDate)!!.time
            } catch (_: Exception) {
            }
        }
        return 0L
    }

    private fun displayDate(millis: Long, raw: String): String =
        if (millis > 0) SimpleDateFormat("d MMM yyyy", Locale("nl")).format(Date(millis))
        else shortDate(raw)

    // Fallback when parsing failed: "Fri, 07 Aug 2026 22:00:00 GMT" -> "07 Aug 2026"
    private fun shortDate(pubDate: String): String {
        val m = Regex("\\d{1,2} \\w{3} \\d{4}").find(pubDate)
        return m?.value ?: pubDate
    }

    // Normalizes "3723", "1:02:03" and "62:03" to h:mm:ss / m:ss
    private fun formatDuration(d: String): String {
        if (d.isEmpty()) return ""
        if (d.contains(":")) return d
        val secs = d.toLongOrNull() ?: return d
        val h = secs / 3600
        val m = (secs % 3600) / 60
        val s = secs % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
