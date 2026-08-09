package nl.wijnand.radio

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.text.SimpleDateFormat
import java.time.Duration
import java.time.OffsetDateTime
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class ScheduleItem(
    val startMillis: Long,
    val endMillis: Long,
    val timeLabel: String,
    val title: String,
    val subtitle: String,
)

// Today's programme guide. NPO and BBC expose keyless JSON APIs, the German
// broadcasters publish RadioDNS/SPI XML, VRT MAX and RTBF have open internal
// JSON APIs, BNR comes via the VPRO gids, RTL France via its grille HTML,
// and Radio France needs an API key.
object ScheduleFetcher {

    private val npoSites = mapOf(
        "npo1" to "www.nporadio1.nl",
        "npo2" to "www.nporadio2.nl",
        "npo3" to "www.npo3fm.nl",
        "npo4" to "www.npoklassiek.nl",
        "npo5" to "www.nporadio5.nl",
    )

    private val bbcIds = mapOf(
        "bbcws" to "bbc_world_service",
        "bbc3" to "bbc_radio_three",
        "bbc4" to "bbc_radio_fourfm",
    )

    // RadioDNS/SPI programme files (worlddab spi XML), one per station per day.
    // Endpoints verified 2026-08: WDR via the ARD server (fm bearer path),
    // Deutschlandradio via its own server (dab bearer path). %s = yyyyMMdd.
    private val spiUrls = mapOf(
        "wdr2" to "https://dewdr.radiodns.ard.de/radiodns/spi/3.1/fm/de0/d392/10040/%s_PI.xml",
        "wdr3" to "https://dewdr.radiodns.ard.de/radiodns/spi/3.1/fm/de0/d393/09510/%s_PI.xml",
        "wdr5" to "https://dewdr.radiodns.ard.de/radiodns/spi/3.1/fm/de0/d395/08880/%s_PI.xml",
        "dlf" to "https://rdns.deutschlandradio.de/radiodns/spi/3.1/dab/de0/10bc/d210/0/%s_PI.xml",
        "dlfkultur" to "https://rdns.deutschlandradio.de/radiodns/spi/3.1/dab/de0/10bc/d220/0/%s_PI.xml",
    )

    // VRT MAX programme-guide pages, fetched via their GraphQL API. This endpoint
    // accepts anonymous requests with just the client headers (verified 2026-08),
    // but it is an undocumented internal API and may break without warning.
    // Klara Continuo has no guide page (non-stop music).
    private val vrtSlugs = mapOf(
        "vrt1" to "radio1",
        "klara" to "klara",
    )

    // RTBF's open scheduling API behind www.rtbf.be/grille-des-programmes;
    // channel ids: La Première = 6, Musiq3 = 7.
    private val rtbfChannels = mapOf(
        "rtbf1" to 6,
        "musiq3" to 7,
    )

    // Radio France needs a personal API key in local.properties (radiofrance.api.key=...);
    // without one these stations simply don't offer a schedule.
    private val radioFranceIds = mapOf(
        "finter" to "FRANCEINTER",
        "fculture" to "FRANCECULTURE",
        "fmusique" to "FRANCEMUSIQUE",
        "finfo" to "FRANCEINFO",
        "fip" to "FIP",
    )

    private val radioFranceKey: String get() = BuildConfig.RADIOFRANCE_API_KEY

    fun supports(stationId: String): Boolean =
        stationId in npoSites || stationId in bbcIds || stationId in spiUrls ||
            stationId in vrtSlugs || stationId in rtbfChannels ||
            stationId == "bnr" || stationId == "rtl" ||
            (stationId in radioFranceIds && radioFranceKey.isNotEmpty())

    // The CGU require a credit line when showing Open API data
    fun isRadioFrance(stationId: String): Boolean = stationId in radioFranceIds

    // NPO's broadcasts API ignores every date parameter and only serves today
    fun supportsDateBrowsing(stationId: String): Boolean = stationId !in npoSites

    /** Local midnight of today + [dayOffset] days. */
    fun dayStartMillis(dayOffset: Int): Long {
        val cal = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_YEAR, dayOffset)
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    private fun formatDay(dayOffset: Int, pattern: String): String =
        SimpleDateFormat(pattern, Locale.US).format(Date(dayStartMillis(dayOffset)))

    suspend fun fetch(station: Station, dayOffset: Int = 0): List<ScheduleItem> = withContext(Dispatchers.IO) {
        npoSites[station.id]?.let { return@withContext fetchNpo(it) }
        bbcIds[station.id]?.let { return@withContext fetchBbc(it, dayOffset) }
        spiUrls[station.id]?.let { return@withContext fetchSpi(it, dayOffset) }
        vrtSlugs[station.id]?.let { return@withContext fetchVrt(it, dayOffset) }
        rtbfChannels[station.id]?.let { return@withContext fetchRtbf(it, dayOffset) }
        if (station.id == "bnr") return@withContext fetchBnr(dayOffset)
        if (station.id == "rtl") return@withContext fetchRtl(dayOffset)
        radioFranceIds[station.id]?.let { return@withContext fetchRadioFrance(it, dayOffset) }
        emptyList()
    }

    private fun fetchNpo(site: String): List<ScheduleItem> {
        val arr = JSONObject(RssFetcher.httpGet("https://$site/api/broadcasts")).getJSONArray("data")
        // NPO datetimes are local Dutch time without a zone designator
        val parse = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("Europe/Amsterdam") }
        val items = mutableListOf<ScheduleItem>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val start = parse.parse(o.getString("startdatetime"))?.time ?: continue
            val end = parse.parse(o.getString("stopdatetime"))?.time ?: continue
            val presenters = o.optString("presenters").takeIf { it.isNotEmpty() && it != "null" } ?: ""
            items.add(ScheduleItem(start, end, timeLabel(start, end), o.getString("title"), presenters))
        }
        return items.sortedBy { it.startMillis }
    }

    private fun fetchBbc(networkId: String, dayOffset: Int): List<ScheduleItem> {
        val day = formatDay(dayOffset, "yyyy-MM-dd")
        val root = JSONObject(
            RssFetcher.httpGet("https://rms.api.bbc.co.uk/v2/experience/inline/schedules/$networkId/$day")
        )
        val parse = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
        val items = mutableListOf<ScheduleItem>()
        val modules = root.getJSONArray("data")
        for (m in 0 until modules.length()) {
            val moduleData = modules.getJSONObject(m).optJSONArray("data") ?: continue
            for (i in 0 until moduleData.length()) {
                val o = moduleData.getJSONObject(i)
                if (o.optString("type") != "broadcast_summary") continue
                val start = parse.parse(o.getString("start"))?.time ?: continue
                val end = parse.parse(o.getString("end"))?.time ?: continue
                val titles = o.getJSONObject("titles")
                val secondary = titles.optString("secondary").takeIf { it.isNotEmpty() && it != "null" } ?: ""
                items.add(ScheduleItem(start, end, timeLabel(start, end), titles.optString("primary"), secondary))
            }
        }
        return items.sortedBy { it.startMillis }
    }

    // Parses a worlddab SPI PI.xml: <programme> with mediumName (16-char cap),
    // optional longName, <time time="ISO" duration="PT..M"/> and shortDescription.
    private fun fetchSpi(urlTemplate: String, dayOffset: Int): List<ScheduleItem> {
        val day = formatDay(dayOffset, "yyyyMMdd")
        val xml = RssFetcher.httpGet(urlTemplate.format(day))

        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(xml.reader())

        val items = mutableListOf<ScheduleItem>()
        var inProgramme = false
        var mediumName = ""
        var longName = ""
        var description = ""
        var start = -1L
        var end = -1L

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "programme" -> {
                        inProgramme = true
                        mediumName = ""; longName = ""; description = ""; start = -1L; end = -1L
                    }
                    "mediumName" -> if (inProgramme && mediumName.isEmpty()) mediumName = parser.nextText().trim()
                    "longName" -> if (inProgramme && longName.isEmpty()) longName = parser.nextText().trim()
                    "time" -> if (inProgramme && start < 0) {
                        try {
                            start = OffsetDateTime.parse(parser.getAttributeValue(null, "time"))
                                .toInstant().toEpochMilli()
                            end = start + Duration.parse(parser.getAttributeValue(null, "duration")).toMillis()
                        } catch (_: Exception) {
                            start = -1L
                        }
                    }
                    "shortDescription" -> if (inProgramme && description.isEmpty()) {
                        description = parser.nextText().trim()
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "programme") {
                    inProgramme = false
                    if (start >= 0 && end > start) {
                        items.add(
                            ScheduleItem(
                                start, end, timeLabel(start, end),
                                longName.ifEmpty { mediumName },
                                description.take(200)
                            )
                        )
                    }
                }
            }
            event = parser.next()
        }
        return items.sortedBy { it.startMillis }
    }

    // RTBF uses a broadcast day of 04:00 UTC to 03:59:59 UTC the next day.
    private fun fetchRtbf(channelId: Int, dayOffset: Int): List<ScheduleItem> {
        val today = formatDay(dayOffset, "yyyy-MM-dd")
        val tomorrow = formatDay(dayOffset + 1, "yyyy-MM-dd")
        val body = RssFetcher.httpGet(
            "https://bff-service.rtbf.be/oaos/v1.6/schedulings" +
                "?scheduledAfter=${today}T04:00:00.000Z&scheduledBefore=${tomorrow}T03:59:59.999Z" +
                "&channelIds=$channelId&_limit=500&platform=WEB"
        )
        val arr = JSONObject(body).getJSONArray("data")
        val items = mutableListOf<ScheduleItem>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val start = try {
                OffsetDateTime.parse(o.getString("scheduledAt")).toInstant().toEpochMilli()
            } catch (_: Exception) {
                continue
            }
            val end = start + o.optLong("duration", 3600) * 1000
            val subtitle = o.optString("subtitle").takeIf { it.isNotEmpty() && it != "null" }
                ?: o.optString("description").takeIf { it != "null" }?.take(200) ?: ""
            items.add(ScheduleItem(start, end, timeLabel(start, end), o.optString("title"), subtitle))
        }
        return items.sortedBy { it.startMillis }
    }

    // BNR's own site is Cloudflare-walled; the VPRO programme guide carries the
    // same (Bindinc-licensed) schedule via an open JSON endpoint.
    private fun fetchBnr(dayOffset: Int): List<ScheduleItem> {
        val today = formatDay(dayOffset, "yyyy-MM-dd")
        val body = RssFetcher.httpGet(
            "https://digitale.vprogids.nl/programmagids/api/schedules/$today?channel=bnr-nieuwsradio&fill=true"
        )
        val parse = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("Europe/Amsterdam") }
        val days = JSONObject(body).getJSONArray("data")
        val items = mutableListOf<ScheduleItem>()
        for (d in 0 until days.length()) {
            val dayItems = days.getJSONObject(d).optJSONArray("items") ?: continue
            for (i in 0 until dayItems.length()) {
                val o = dayItems.getJSONObject(i)
                val start = parse.parse(o.getString("startDateTime"))?.time ?: continue
                val end = start + o.optLong("durationMinutes", 60) * 60_000
                val subtitle = o.optString("shortDescription").takeIf { it.isNotEmpty() && it != "null" }
                    ?: o.optString("description").takeIf { it != "null" }?.take(200) ?: ""
                items.add(ScheduleItem(start, end, timeLabel(start, end), o.optString("title"), subtitle))
            }
        }
        return items.sortedBy { it.startMillis }
    }

    // RTL France has no JSON API; the grille page is server-side rendered HTML
    // with one programme-card per broadcast ("06h00 - 09h15" + title).
    private fun fetchRtl(dayOffset: Int): List<ScheduleItem> {
        val day = formatDay(dayOffset, "dd-MM-yyyy")
        val html = RssFetcher.httpGet("https://www.rtl.fr/grille/$day")
        val cardRegex = Regex(
            "<h2 class=\"programme-card__time[^\"]*\"[^>]*>\\s*(\\d{1,2})h(\\d{2})\\s*-\\s*(\\d{1,2})h(\\d{2})\\s*<" +
                ".*?programme-card__title[^\"]*\"[^>]*>([^<]+)<",
            RegexOption.DOT_MATCHES_ALL
        )
        // Times are Europe/Paris local; same UTC offset as the Low Countries year-round
        val midnight = dayStartMillis(dayOffset)
        val items = mutableListOf<ScheduleItem>()
        for (m in cardRegex.findAll(html)) {
            val (sh, sm, eh, em) = m.groupValues.drop(1).take(4).map { it.toInt() }
            val startMin = sh * 60 + sm
            var endMin = eh * 60 + em
            if (endMin <= startMin) endMin += 24 * 60
            val start = midnight + startMin * 60_000L
            val end = midnight + endMin * 60_000L
            items.add(ScheduleItem(start, end, timeLabel(start, end), decodeEntities(m.groupValues[5].trim()), ""))
        }
        return items.sortedBy { it.startMillis }
    }

    private fun decodeEntities(s: String): String = s
        .replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")

    // VRT MAX guide page via GraphQL: `previous` and `next` tiles carry a start
    // time ("13:00u") and duration ("60 min"); the live tile carries the current
    // programme with remaining minutes ("Nog 123 min").
    private fun fetchVrt(slug: String, dayOffset: Int): List<ScheduleItem> {
        val day = formatDay(dayOffset, "yyyy-MM-dd")
        val tiles = "title description indexMeta { value } statusMeta { value }"
        val query = """
            query Epg(${'$'}pageId: ID!) { page(id: ${'$'}pageId) { ... on ElectronicProgramGuidePage {
                current { ... on ElectronicProgramGuidePageLiveTile { tile { ... on AudioLivestreamTile { $tiles } } } }
                previous { paginatedItems(first: 100) { edges { node { ... on RadioEpisodeTile { $tiles } } } } }
                next { paginatedItems(first: 100) { edges { node { ... on RadioEpisodeTile { $tiles } } } } }
            } } }
        """.trimIndent()
        val body = httpPostJson(
            "https://www.vrt.be/vrtnu-api/graphql/v1",
            JSONObject()
                .put("query", query)
                .put("variables", JSONObject().put("pageId", "/vrtmax/tv-gids/$slug/$day/"))
                .toString(),
            mapOf("x-vrt-client-name" to "WEB", "x-vrt-client-version" to "1.5.15"),
        )
        val page = JSONObject(body).getJSONObject("data").optJSONObject("page")
            ?: throw IllegalStateException("Geen gidspagina gevonden")

        // (title, description, startMinutesOfDay, durationMinutes or -1 for the live tile)
        data class Raw(val title: String, val desc: String, val startMin: Int, val durMin: Int, val live: Boolean)

        fun parseTile(o: JSONObject, live: Boolean): Raw? {
            val title = o.optString("title").takeIf { it.isNotEmpty() } ?: return null
            val startText = o.optJSONArray("indexMeta")?.optJSONObject(0)?.optString("value") ?: return null
            val m = Regex("(\\d{1,2}):(\\d{2})").find(startText) ?: return null
            val dur = o.optJSONArray("statusMeta")?.optJSONObject(0)?.optString("value")
                ?.let { Regex("(\\d+)\\s*min").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: -1
            return Raw(
                title, o.optString("description").takeIf { it != "null" } ?: "",
                m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt(), dur, live
            )
        }

        val raw = mutableListOf<Raw>()
        fun addEdges(section: String) {
            val edges = page.optJSONObject(section)?.optJSONObject("paginatedItems")?.optJSONArray("edges")
            for (i in 0 until (edges?.length() ?: 0)) {
                edges!!.optJSONObject(i)?.optJSONObject("node")?.let { n -> parseTile(n, live = false)?.let(raw::add) }
            }
        }
        addEdges("previous")
        // The live tile is only meaningful when looking at today
        if (dayOffset == 0) {
            page.optJSONObject("current")?.optJSONObject("tile")?.let { t -> parseTile(t, live = true)?.let(raw::add) }
        }
        addEdges("next")

        // Convert minutes-of-day to epoch millis; a start earlier than its
        // predecessor means the guide rolled past midnight.
        val midnight = dayStartMillis(dayOffset)
        val items = mutableListOf<ScheduleItem>()
        var dayOffset = 0L
        var prevStartMin = -1
        raw.forEachIndexed { i, r ->
            if (r.startMin < prevStartMin) dayOffset += 24 * 60
            prevStartMin = r.startMin
            val start = midnight + (r.startMin + dayOffset) * 60_000L
            val end = when {
                !r.live && r.durMin > 0 -> start + r.durMin * 60_000L
                // live tile: ends where the next programme starts, else now + remaining
                i + 1 < raw.size -> midnight + (raw[i + 1].startMin + dayOffset +
                    (if (raw[i + 1].startMin < r.startMin) 24 * 60 else 0)) * 60_000L
                r.durMin > 0 -> System.currentTimeMillis() + r.durMin * 60_000L
                else -> start + 60 * 60_000L
            }
            items.add(ScheduleItem(start, end, timeLabel(start, end), r.title, r.desc))
        }
        return items.sortedBy { it.startMillis }
    }

    private fun fetchRadioFrance(stationEnum: String, dayOffset: Int): List<ScheduleItem> {
        val dayStart = dayStartMillis(dayOffset) / 1000
        val dayEnd = dayStartMillis(dayOffset + 1) / 1000
        val query = """
            { grid(start: $dayStart, end: $dayEnd, station: $stationEnum) {
                ... on DiffusionStep { start end diffusion { title show { title } } }
                ... on BlankStep { start end title }
                ... on TrackStep { start end track { title albumTitle } }
            } }
        """.trimIndent()
        val body = httpPostJson(
            "https://openapi.radiofrance.fr/v1/graphql?x-token=$radioFranceKey",
            JSONObject().put("query", query).toString()
        )
        val root = JSONObject(body)
        if (root.has("errors")) {
            throw IllegalStateException(root.getJSONArray("errors").optJSONObject(0)?.optString("message") ?: "API-fout")
        }
        val grid = root.getJSONObject("data").getJSONArray("grid")
        val items = mutableListOf<ScheduleItem>()
        for (i in 0 until grid.length()) {
            val o = grid.optJSONObject(i) ?: continue
            val start = o.optLong("start", -1) * 1000
            val end = o.optLong("end", -1) * 1000
            if (start < 0 || end < 0) continue
            val diffusion = o.optJSONObject("diffusion")
            val track = o.optJSONObject("track")
            val (title, subtitle) = when {
                diffusion != null -> {
                    val show = diffusion.optJSONObject("show")?.optString("title") ?: ""
                    if (show.isNotEmpty()) show to diffusion.optString("title")
                    else diffusion.optString("title") to ""
                }
                track != null -> track.optString("title") to track.optString("albumTitle")
                else -> (o.optString("title").takeIf { it.isNotEmpty() && it != "null" } ?: "Programma") to ""
            }
            items.add(ScheduleItem(start, end, timeLabel(start, end), title, subtitle))
        }
        return items.sortedBy { it.startMillis }
    }

    private fun httpPostJson(url: String, json: String, headers: Map<String, String> = emptyMap()): String {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("User-Agent", "RadioApp/1.0")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        try {
            conn.outputStream.use { it.write(json.toByteArray()) }
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode} voor $url")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // Shown in the device's own timezone
    private fun timeLabel(start: Long, end: Long): String {
        val f = SimpleDateFormat("HH:mm", Locale.US)
        return "${f.format(Date(start))} – ${f.format(Date(end))}"
    }
}
