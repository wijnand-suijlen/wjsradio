package nl.wijnand.radio

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface EpisodesState {
    data object Loading : EpisodesState
    data class Error(val message: String) : EpisodesState
    data class Ready(val episodes: List<Episode>) : EpisodesState
}

sealed interface ScheduleState {
    data object Loading : ScheduleState
    data class Error(val message: String) : ScheduleState
    data class Ready(val items: List<ScheduleItem>) : ScheduleState
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("radio", Context.MODE_PRIVATE)

    private val _customFeeds = MutableStateFlow(loadCustomFeeds())
    val customFeeds: StateFlow<List<Podcast>> = _customFeeds

    private val _episodes = MutableStateFlow<Map<String, EpisodesState>>(emptyMap())
    val episodes: StateFlow<Map<String, EpisodesState>> = _episodes

    // One bookmark per feed: feedUrl -> audioUrl of the bookmarked episode
    private val _bookmarks = MutableStateFlow(loadBookmarks())
    val bookmarks: StateFlow<Map<String, String>> = _bookmarks

    // Keyed on "stationId|dayOffset"
    private val _schedules = MutableStateFlow<Map<String, ScheduleState>>(emptyMap())
    val schedules: StateFlow<Map<String, ScheduleState>> = _schedules

    fun scheduleKey(station: Station, dayOffset: Int) = "${station.id}|$dayOffset"

    fun loadSchedule(station: Station, dayOffset: Int = 0, force: Boolean = false) {
        val key = scheduleKey(station, dayOffset)
        val current = _schedules.value[key]
        if (!force && (current is ScheduleState.Ready || current is ScheduleState.Loading)) return
        _schedules.update { it + (key to ScheduleState.Loading) }
        viewModelScope.launch {
            val state = try {
                val items = ScheduleFetcher.fetch(station, dayOffset)
                if (items.isEmpty()) ScheduleState.Error("Geen programmering gevonden")
                else ScheduleState.Ready(items)
            } catch (e: Exception) {
                ScheduleState.Error(e.message ?: "Programmering laden mislukt")
            }
            _schedules.update { it + (key to state) }
        }
    }

    val allPodcasts: List<Podcast>
        get() = CuratedPodcasts.all + _customFeeds.value

    fun loadEpisodes(podcast: Podcast, force: Boolean = false) {
        val current = _episodes.value[podcast.feedUrl]
        if (!force && (current is EpisodesState.Ready || current is EpisodesState.Loading)) return
        _episodes.update { it + (podcast.feedUrl to EpisodesState.Loading) }
        viewModelScope.launch {
            val state = try {
                val (_, eps) = RssFetcher.fetchEpisodes(podcast.feedUrl)
                if (eps.isEmpty()) EpisodesState.Error("Geen afleveringen gevonden in deze feed")
                else EpisodesState.Ready(eps)
            } catch (e: Exception) {
                EpisodesState.Error(e.message ?: "Feed laden mislukt")
            }
            _episodes.update { it + (podcast.feedUrl to state) }
        }
    }

    /** Returns an error message, or null on success. */
    suspend fun addCustomFeed(url: String): String? {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http")) return "Voer een geldige feed-URL in"
        if (allPodcasts.any { it.feedUrl == trimmed }) return "Deze feed staat er al in"
        return try {
            val (title, eps) = RssFetcher.fetchEpisodes(trimmed)
            if (eps.isEmpty()) return "Geen afleveringen gevonden in deze feed"
            val podcast = Podcast("Eigen feed", title.ifEmpty { trimmed }, trimmed, custom = true)
            _customFeeds.update { it + podcast }
            _episodes.update { it + (trimmed to EpisodesState.Ready(eps)) }
            saveCustomFeeds()
            null
        } catch (e: Exception) {
            e.message ?: "Feed laden mislukt"
        }
    }

    fun removeCustomFeed(podcast: Podcast) {
        _customFeeds.update { feeds -> feeds.filter { it.feedUrl != podcast.feedUrl } }
        saveCustomFeeds()
    }

    /** Persist an already-loaded feed (e.g. found via the programme guide) without re-fetching. */
    fun addCuratedFeed(podcast: Podcast) {
        if (allPodcasts.any { it.feedUrl == podcast.feedUrl }) return
        _customFeeds.update { it + podcast.copy(custom = true) }
        saveCustomFeeds()
    }

    fun toggleBookmark(feedUrl: String, audioUrl: String) {
        _bookmarks.update { current ->
            if (current[feedUrl] == audioUrl) current - feedUrl else current + (feedUrl to audioUrl)
        }
        prefs.edit()
            .putStringSet("bookmarks", _bookmarks.value.map { "${it.key}\t${it.value}" }.toSet())
            .apply()
    }

    private fun loadBookmarks(): Map<String, String> =
        prefs.getStringSet("bookmarks", emptySet())!!.mapNotNull { entry ->
            val parts = entry.split('\t', limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else null
        }.toMap()

    private fun loadCustomFeeds(): List<Podcast> =
        prefs.getStringSet("custom_feeds", emptySet())!!.mapNotNull { entry ->
            val parts = entry.split('\t', limit = 2)
            if (parts.size == 2) Podcast("Eigen feed", parts[0], parts[1], custom = true) else null
        }.sortedBy { it.title }

    private fun saveCustomFeeds() {
        prefs.edit()
            .putStringSet("custom_feeds", _customFeeds.value.map { "${it.title}\t${it.feedUrl}" }.toSet())
            .apply()
    }
}
