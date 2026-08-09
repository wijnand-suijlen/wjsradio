package nl.wijnand.radio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
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

// Today's programme guide. Only NPO and BBC expose clean keyless JSON APIs;
// the other broadcasters would require scraping, so they are not supported.
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
        stationId in npoSites || stationId in bbcIds ||
            (stationId in radioFranceIds && radioFranceKey.isNotEmpty())

    // The CGU require a credit line when showing Open API data
    fun isRadioFrance(stationId: String): Boolean = stationId in radioFranceIds

    suspend fun fetch(station: Station): List<ScheduleItem> = withContext(Dispatchers.IO) {
        npoSites[station.id]?.let { return@withContext fetchNpo(it) }
        bbcIds[station.id]?.let { return@withContext fetchBbc(it) }
        radioFranceIds[station.id]?.let { return@withContext fetchRadioFrance(it) }
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

    private fun fetchBbc(networkId: String): List<ScheduleItem> {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
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

    private fun fetchRadioFrance(stationEnum: String): List<ScheduleItem> {
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val dayStart = cal.timeInMillis / 1000
        val dayEnd = dayStart + 24 * 3600
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

    private fun httpPostJson(url: String, json: String): String {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("User-Agent", "RadioApp/1.0")
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
