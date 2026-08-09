package nl.wijnand.radio

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------- playback helpers ----------

private fun playStation(controller: MediaController, station: Station) {
    val item = MediaItem.Builder()
        .setMediaId("station:${station.id}")
        .setUri(station.url)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(station.name)
                .setArtist(station.broadcaster)
                .build()
        )
        .build()
    controller.setMediaItem(item)
    controller.prepare()
    controller.play()
}

private fun playEpisode(controller: MediaController, podcast: Podcast, episode: Episode) {
    val item = MediaItem.Builder()
        .setMediaId("episode:${episode.audioUrl}")
        .setUri(episode.audioUrl)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(episode.title)
                .setArtist(podcast.title)
                .build()
        )
        .build()
    controller.setMediaItem(item)
    controller.prepare()
    controller.play()
}

// ---------- player state ----------

class PlayerUiState {
    var mediaId by mutableStateOf<String?>(null)
    var title by mutableStateOf("")
    var subtitle by mutableStateOf("")
    var isPlaying by mutableStateOf(false)
    var isBuffering by mutableStateOf(false)
    var isLive by mutableStateOf(false)
    var durationMs by mutableLongStateOf(0L)
    var positionMs by mutableLongStateOf(0L)
    var error by mutableStateOf<String?>(null)
    var errorCount by mutableIntStateOf(0)
}

@Composable
fun rememberPlayerState(controller: MediaController?): PlayerUiState {
    val state = remember { PlayerUiState() }

    DisposableEffect(controller) {
        if (controller == null) return@DisposableEffect onDispose { }

        fun sync() {
            state.mediaId = controller.currentMediaItem?.mediaId
            state.title = controller.mediaMetadata.title?.toString() ?: ""
            state.subtitle = controller.mediaMetadata.artist?.toString() ?: ""
            state.isPlaying = controller.isPlaying
            state.isBuffering = controller.playbackState == Player.STATE_BUFFERING
            state.isLive = controller.isCurrentMediaItemLive
            state.durationMs = controller.duration.coerceAtLeast(0L)
            state.positionMs = controller.currentPosition.coerceAtLeast(0L)
        }

        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = sync()
            override fun onPlayerError(e: PlaybackException) {
                state.error = e.localizedMessage ?: "Afspelen mislukt"
                state.errorCount++
            }
        }
        controller.addListener(listener)
        sync()
        onDispose { controller.removeListener(listener) }
    }

    LaunchedEffect(controller, state.isPlaying) {
        while (controller != null && state.isPlaying) {
            state.positionMs = controller.currentPosition.coerceAtLeast(0L)
            state.durationMs = controller.duration.coerceAtLeast(0L)
            delay(500)
        }
    }

    return state
}

// ---------- app scaffold ----------

@Composable
fun RadioApp(controller: MediaController?, icyTitle: String = "", vm: AppViewModel = viewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var scheduleStationId by rememberSaveable { mutableStateOf<String?>(null) }
    var guidePodcast by remember { mutableStateOf<Podcast?>(null) }
    var guideQuery by remember { mutableStateOf("") }
    val player = rememberPlayerState(controller)
    val context = LocalContext.current

    LaunchedEffect(player.errorCount) {
        if (player.errorCount > 0) {
            Toast.makeText(context, player.error, Toast.LENGTH_LONG).show()
        }
    }

    // Sides must always clear the navigation bar / camera cutout (landscape);
    // the bottom only when the player bar isn't there to do it.
    val sideInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
    Scaffold(
        contentWindowInsets = if (player.mediaId != null) sideInsets
        else sideInsets.union(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
        topBar = {
            TabRow(
                selectedTabIndex = tab,
                modifier = Modifier.windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)
                )
            ) {
                Tab(selected = tab == 0, onClick = { tab = 0 },
                    text = { Text("Radio") },
                    icon = { Icon(Icons.Default.Radio, null) })
                Tab(selected = tab == 1, onClick = { tab = 1 },
                    text = { Text("Podcasts") },
                    icon = { Icon(Icons.Default.Podcasts, null) })
            }
        },
        bottomBar = {
            if (player.mediaId != null) {
                PlayerBar(controller, player, icyTitle)
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                0 -> {
                    val scheduleStation = scheduleStationId?.let { Stations.byId(it) }
                    val podcast = guidePodcast
                    when {
                        scheduleStation == null ->
                            StationsTab(controller, player, onOpenSchedule = { scheduleStationId = it.id })

                        podcast != null -> {
                            BackHandler { guidePodcast = null }
                            EpisodeList(
                                controller, vm, podcast, player,
                                onBack = { guidePodcast = null },
                                initialQuery = guideQuery,
                                showSaveFeed = true,
                            )
                        }

                        else -> {
                            BackHandler { scheduleStationId = null }
                            ScheduleScreen(
                                vm, scheduleStation,
                                onBack = { scheduleStationId = null },
                                onOpenPodcast = { p, query ->
                                    guidePodcast = p
                                    guideQuery = query
                                },
                            )
                        }
                    }
                }
                1 -> PodcastsTab(controller, vm, player)
            }
        }
    }
}

// ---------- stations ----------

@Composable
fun StationsTab(
    controller: MediaController?,
    player: PlayerUiState,
    onOpenSchedule: (Station) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        Stations.byCountry.forEach { (country, stations) ->
            item(key = "header-$country") { SectionHeader(country) }
            items(stations, key = { it.id }) { station ->
                val active = player.mediaId == "station:${station.id}"
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = controller != null) {
                            controller?.let { playStation(it, station) }
                        }
                        .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(vertical = 8.dp)
                    ) {
                        Text(
                            station.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            color = if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            station.broadcaster,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (active) {
                        Icon(
                            Icons.Default.GraphicEq, contentDescription = "Speelt nu",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (ScheduleFetcher.supports(station.id)) {
                        IconButton(onClick = { onOpenSchedule(station) }) {
                            Icon(
                                Icons.Default.CalendarMonth,
                                contentDescription = "Programmering",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
        item { Box(Modifier.height(8.dp)) }
    }
}

private fun dayLabel(dayOffset: Int): String = when (dayOffset) {
    -1 -> "gisteren"
    0 -> "vandaag"
    1 -> "morgen"
    else -> SimpleDateFormat("EEE d MMM", Locale("nl"))
        .format(Date(ScheduleFetcher.dayStartMillis(dayOffset)))
}

@Composable
private fun ScheduleScreen(
    vm: AppViewModel,
    station: Station,
    onBack: () -> Unit,
    onOpenPodcast: (Podcast, String) -> Unit,
) {
    val schedules by vm.schedules.collectAsState()
    var dayOffset by rememberSaveable { mutableIntStateOf(0) }
    val browsable = ScheduleFetcher.supportsDateBrowsing(station.id)
    val state = schedules[vm.scheduleKey(station, dayOffset)]
    val listState = rememberLazyListState()
    var searchItem by remember { mutableStateOf<ScheduleItem?>(null) }

    LaunchedEffect(station.id, dayOffset) { vm.loadSchedule(station, dayOffset) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Terug")
            }
            Text(
                if (browsable) station.name else "Vandaag op ${station.name}",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { vm.loadSchedule(station, dayOffset, force = true) }) {
                Icon(Icons.Default.Refresh, contentDescription = "Vernieuwen")
            }
        }
        if (browsable) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                IconButton(enabled = dayOffset > -30, onClick = { dayOffset-- }) {
                    Icon(Icons.Default.ChevronLeft, contentDescription = "Vorige dag")
                }
                Text(
                    dayLabel(dayOffset),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier
                        .clickable { dayOffset = 0 }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
                IconButton(enabled = dayOffset < 30, onClick = { dayOffset++ }) {
                    Icon(Icons.Default.ChevronRight, contentDescription = "Volgende dag")
                }
            }
        }
        HorizontalDivider()

        when (state) {
            null, ScheduleState.Loading -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            is ScheduleState.Error -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(state.message, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { vm.loadSchedule(station, dayOffset, force = true) }) {
                    Text("Opnieuw proberen")
                }
            }

            is ScheduleState.Ready -> {
                val now = System.currentTimeMillis()
                val currentIndex = if (dayOffset == 0)
                    state.items.indexOfFirst { now >= it.startMillis && now < it.endMillis }
                else -1

                LaunchedEffect(state) {
                    if (currentIndex > 0) listState.scrollToItem(currentIndex)
                    else listState.scrollToItem(0)
                }

                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    itemsIndexed(state.items) { index, item ->
                        val current = index == currentIndex
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { searchItem = item }
                                .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                item.timeLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                                color = if (current) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 12.dp)
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    item.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                                    color = if (current) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                                if (item.subtitle.isNotEmpty()) {
                                    Text(
                                        item.subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (current) {
                                Text(
                                    "NU",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Icon(
                                Icons.Default.Podcasts,
                                contentDescription = "Podcast zoeken",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(horizontal = 12.dp)
                                    .size(18.dp)
                            )
                        }
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    }
                    if (ScheduleFetcher.isRadioFrance(station.id)) {
                        item {
                            Text(
                                "Données fournies par l'Open API Radio France",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    searchItem?.let { item ->
        // The episode of a broadcast carries the broadcast's date; prefill the
        // episode-list search with it in the same Dutch format the list uses.
        val dateQuery = SimpleDateFormat("d MMM yyyy", Locale("nl"))
            .format(Date(item.startMillis))
        PodcastSearchDialog(
            programmeTitle = item.title,
            broadcaster = station.broadcaster,
            country = station.country,
            onDismiss = { searchItem = null },
            onOpen = { candidate ->
                searchItem = null
                onOpenPodcast(
                    Podcast(candidate.author.ifEmpty { station.broadcaster }, candidate.title, candidate.feedUrl, custom = true),
                    dateQuery,
                )
            },
        )
    }
}

@Composable
private fun PodcastSearchDialog(
    programmeTitle: String,
    broadcaster: String,
    country: String,
    onDismiss: () -> Unit,
    onOpen: (PodcastCandidate) -> Unit,
) {
    var busy by remember { mutableStateOf(true) }
    var results by remember { mutableStateOf<List<PodcastCandidate>>(emptyList()) }

    LaunchedEffect(programmeTitle) {
        results = try {
            PodcastFinder.search(programmeTitle, broadcaster, country)
        } catch (e: Exception) {
            emptyList()
        }
        busy = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Podcast van \"$programmeTitle\"") },
        text = {
            when {
                busy -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                results.isEmpty() -> Text("Geen podcast gevonden voor deze uitzending.")
                else -> LazyColumn {
                    items(results, key = { it.feedUrl }) { candidate ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onOpen(candidate) }
                                .padding(vertical = 10.dp)
                        ) {
                            Text(candidate.title, style = MaterialTheme.typography.bodyLarge)
                            if (candidate.author.isNotEmpty()) {
                                Text(
                                    candidate.author,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Sluiten") } }
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp)
    )
}

// ---------- podcasts ----------

@Composable
fun PodcastsTab(controller: MediaController?, vm: AppViewModel, player: PlayerUiState) {
    val customFeeds by vm.customFeeds.collectAsState()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val allPodcasts = CuratedPodcasts.all + customFeeds
    val selectedPodcast = allPodcasts.find { it.feedUrl == selected }

    if (selectedPodcast == null) {
        PodcastList(vm, allPodcasts) { selected = it.feedUrl }
    } else {
        BackHandler { selected = null }
        EpisodeList(controller, vm, selectedPodcast, player, onBack = { selected = null })
    }
}

@Composable
private fun PodcastList(vm: AppViewModel, podcasts: List<Podcast>, onOpen: (Podcast) -> Unit) {
    var showAddDialog by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize()) {
        podcasts.groupBy { it.broadcaster }.forEach { (broadcaster, feeds) ->
            item(key = "header-$broadcaster") { SectionHeader(broadcaster) }
            items(feeds, key = { it.feedUrl }) { podcast ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(podcast) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        podcast.title,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    if (podcast.custom) {
                        IconButton(onClick = { vm.removeCustomFeed(podcast) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Verwijder feed")
                        }
                    }
                }
            }
        }
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { showAddDialog = true }
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    "  RSS-feed toevoegen",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }

    if (showAddDialog) {
        AddFeedDialog(vm, onDismiss = { showAddDialog = false })
    }
}

@Composable
private fun AddFeedDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Podcast-feed toevoegen") },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("RSS-feed URL") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            ElevatedButton(
                enabled = !busy && url.isNotBlank(),
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        val result = vm.addCustomFeed(url)
                        busy = false
                        if (result == null) onDismiss() else error = result
                    }
                }
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp)) else Text("Toevoegen")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Annuleren") }
        }
    )
}

@Composable
private fun EpisodeList(
    controller: MediaController?,
    vm: AppViewModel,
    podcast: Podcast,
    player: PlayerUiState,
    onBack: () -> Unit,
    initialQuery: String = "",
    showSaveFeed: Boolean = false,
) {
    val episodesMap by vm.episodes.collectAsState()
    val bookmarks by vm.bookmarks.collectAsState()
    val customFeeds by vm.customFeeds.collectAsState()
    val state = episodesMap[podcast.feedUrl]
    val bookmarkUrl = bookmarks[podcast.feedUrl]
    val context = LocalContext.current
    val alreadySaved = customFeeds.any { it.feedUrl == podcast.feedUrl }

    var query by rememberSaveable(podcast.feedUrl) { mutableStateOf(initialQuery) }
    var newestFirst by rememberSaveable { mutableStateOf(true) }
    var expandedIds by remember { mutableStateOf(setOf<String>()) }
    val listState = rememberLazyListState()
    var pendingScroll by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(podcast.feedUrl) { vm.loadEpisodes(podcast) }

    val sorted = remember(state, newestFirst) {
        val episodes = (state as? EpisodesState.Ready)?.episodes ?: emptyList()
        if (newestFirst) episodes.sortedByDescending { it.pubDateMillis }
        else episodes.sortedBy { it.pubDateMillis }
    }
    val shown = remember(sorted, query) {
        if (query.isBlank()) sorted
        else sorted.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.description.contains(query, ignoreCase = true) ||
                it.pubDate.contains(query, ignoreCase = true)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Terug")
            }
            Text(
                podcast.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                enabled = bookmarkUrl != null && sorted.any { it.audioUrl == bookmarkUrl },
                onClick = {
                    query = ""
                    pendingScroll = sorted.indexOfFirst { it.audioUrl == bookmarkUrl }
                }
            ) {
                Icon(
                    Icons.Default.Bookmark, contentDescription = "Spring naar bladwijzer",
                    tint = if (bookmarkUrl != null) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { newestFirst = !newestFirst }) {
                Icon(
                    if (newestFirst) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                    contentDescription = if (newestFirst) "Nieuwste eerst" else "Oudste eerst"
                )
            }
            if (showSaveFeed && !alreadySaved) {
                IconButton(onClick = {
                    vm.addCuratedFeed(podcast)
                    Toast.makeText(context, "Podcast bewaard", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Default.Add, contentDescription = "Feed bewaren")
                }
            }
            IconButton(onClick = { vm.loadEpisodes(podcast, force = true) }) {
                Icon(Icons.Default.Refresh, contentDescription = "Vernieuwen")
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Zoek op titel, tekst of datum (bv. 5 mei 2003)") },
            singleLine = true,
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Wissen")
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        )
        HorizontalDivider()

        when (state) {
            null, EpisodesState.Loading -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            is EpisodesState.Error -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(state.message, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { vm.loadEpisodes(podcast, force = true) }) {
                    Text("Opnieuw proberen")
                }
            }

            is EpisodesState.Ready -> {
                // Jump after the unfiltered list has actually been (re)composed
                LaunchedEffect(pendingScroll, shown) {
                    val target = pendingScroll
                    if (target != null && target in shown.indices) {
                        listState.scrollToItem(target)
                        pendingScroll = null
                    }
                }

                if (shown.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Geen afleveringen gevonden", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize(), state = listState) {
                        itemsIndexed(shown, key = { _, it -> it.audioUrl }) { _, episode ->
                            EpisodeRow(
                                episode = episode,
                                active = player.mediaId == "episode:${episode.audioUrl}",
                                bookmarked = bookmarkUrl == episode.audioUrl,
                                expanded = episode.audioUrl in expandedIds,
                                onPlay = { controller?.let { playEpisode(it, podcast, episode) } },
                                onToggleBookmark = { vm.toggleBookmark(podcast.feedUrl, episode.audioUrl) },
                                onToggleExpanded = {
                                    expandedIds = if (episode.audioUrl in expandedIds)
                                        expandedIds - episode.audioUrl
                                    else expandedIds + episode.audioUrl
                                }
                            )
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: Episode,
    active: Boolean,
    bookmarked: Boolean,
    expanded: Boolean,
    onPlay: () -> Unit,
    onToggleBookmark: () -> Unit,
    onToggleExpanded: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onPlay() }
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                episode.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                color = if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface
            )
            Text(
                listOf(episode.pubDate, episode.duration)
                    .filter { it.isNotEmpty() }
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (episode.description.isNotEmpty()) {
                // Tapping the description toggles between 2 lines and the full text;
                // the rest of the row still plays the episode
                Text(
                    episode.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clickable { onToggleExpanded() }
                        .animateContentSize()
                        .fillMaxWidth()
                )
            }
        }
        IconButton(onClick = onToggleBookmark) {
            Icon(
                if (bookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                contentDescription = if (bookmarked) "Bladwijzer verwijderen" else "Bladwijzer toevoegen",
                tint = if (bookmarked) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------- player bar ----------

private fun formatMs(ms: Long): String {
    val totalSecs = ms / 1000
    val h = totalSecs / 3600
    val m = (totalSecs % 3600) / 60
    val s = totalSecs % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
fun PlayerBar(controller: MediaController?, player: PlayerUiState, icyTitle: String = "") {
    Surface(
        tonalElevation = 8.dp,
        modifier = Modifier.windowInsetsPadding(
            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
        )
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        player.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        when {
                            player.isLive && icyTitle.isNotBlank() -> "$icyTitle · LIVE"
                            player.isLive -> "${player.subtitle} · LIVE"
                            else -> player.subtitle
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (player.isBuffering) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                } else {
                    IconButton(onClick = {
                        controller?.let {
                            if (it.isPlaying) {
                                // For live radio, pause == stop; for podcasts keep position
                                if (player.isLive) it.stop() else it.pause()
                            } else {
                                it.prepare()
                                it.play()
                            }
                        }
                    }) {
                        Icon(
                            when {
                                !player.isPlaying -> Icons.Default.PlayArrow
                                player.isLive -> Icons.Default.Stop
                                else -> Icons.Default.Pause
                            },
                            contentDescription = if (player.isPlaying) "Stop" else "Afspelen",
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }

            if (!player.isLive && player.durationMs > 0) {
                var dragging by remember { mutableStateOf(false) }
                var dragPosition by remember { mutableLongStateOf(0L) }
                val shown = if (dragging) dragPosition else player.positionMs

                Slider(
                    value = shown.toFloat().coerceIn(0f, player.durationMs.toFloat()),
                    valueRange = 0f..player.durationMs.toFloat(),
                    onValueChange = {
                        dragging = true
                        dragPosition = it.toLong()
                    },
                    onValueChangeFinished = {
                        controller?.seekTo(dragPosition)
                        dragging = false
                    }
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatMs(shown), style = MaterialTheme.typography.labelSmall)
                    Text(formatMs(player.durationMs), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
