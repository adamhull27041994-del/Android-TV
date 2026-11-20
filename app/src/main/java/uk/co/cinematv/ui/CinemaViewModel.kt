package uk.co.cinematv.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uk.co.cinematv.data.LibraryStore
import uk.co.cinematv.data.TmdbApi
import uk.co.cinematv.model.*
import uk.co.cinematv.provider.PersonalStreamProvider
import uk.co.cinematv.provider.*
import uk.co.cinematv.provider.ProviderRegistry
import uk.co.cinematv.provider.ProviderReport
import uk.co.cinematv.provider.WatchOutProvider
import uk.co.cinematv.provider.VidLinkEmbedProvider
import uk.co.cinematv.provider.YaStreamProvider
import uk.co.cinematv.provider.VidBoltProvider
import uk.co.cinematv.provider.ThugTvProvider
import uk.co.cinematv.provider.TwoEmbedWebProvider

class CinemaViewModel(app: Application) : AndroidViewModel(app) {
    private val api = TmdbApi()
    private val store = LibraryStore(app)
    private val providerRegistry = ProviderRegistry(listOf(
        PersonalStreamProvider(store), WatchOutProvider(), YaStreamProvider(),
        VidBoltProvider(), ThugTvProvider(), MovieBoxProvider(), FrameXtvProvider(),
        PurStreamProvider(), LMScriptProvider(), VidQueenProvider(), VixSrcProvider(),
        VidLinkApiProvider(), VuflixProvider(),
        StremioTorrentProvider("Torrentio","https://torrentio.strem.fun/sort=seeders/stream"),
        StremioTorrentProvider("TorrentClaw","https://torrentclaw.com/api/stremio/stream"),
        VidLinkEmbedProvider(), TwoEmbedWebProvider(), RiveStreamWebProvider()
    ))

    var tab by mutableStateOf(0)
    var homeLoading by mutableStateOf(true)
    var homeError by mutableStateOf<String?>(null)
    var trending by mutableStateOf<List<MediaTitle>>(emptyList())
    var airing by mutableStateOf<List<MediaTitle>>(emptyList())
    var onAir by mutableStateOf<List<MediaTitle>>(emptyList())
    var movies by mutableStateOf<List<MediaTitle>>(emptyList())
    var nowPlaying by mutableStateOf<List<MediaTitle>>(emptyList())

    var searchText by mutableStateOf("")
    var searching by mutableStateOf(false)
    var searchResults by mutableStateOf<List<MediaTitle>>(emptyList())
    private var searchJob: Job? = null

    var selected by mutableStateOf<MediaTitle?>(null)
    var detail by mutableStateOf<ShowDetails?>(null)
    var detailError by mutableStateOf<String?>(null)
    var detailLoading by mutableStateOf(false)
    var season by mutableStateOf(1)
    var episodes by mutableStateOf<List<EpisodeInfo>>(emptyList())
    var episodesLoading by mutableStateOf(false)
    var watchInfo by mutableStateOf<WatchInfo?>(null)

    var sourceKey by mutableStateOf<StreamKey?>(null)
    var sourceResults by mutableStateOf<List<PlayableStream>>(emptyList())
    var providerReports by mutableStateOf<List<ProviderReport>>(emptyList())
    var sourcesLoading by mutableStateOf(false)
    var addSourceOpen by mutableStateOf(false)
    var sourceError by mutableStateOf<String?>(null)

    var favourites by mutableStateOf<List<MediaTitle>>(emptyList())
    var history by mutableStateOf<List<WatchProgress>>(emptyList())

    init { refreshHome(); refreshLibrary() }

    fun refreshHome() {
        viewModelScope.launch {
            homeLoading = true; homeError = null
            coroutineScope {
                val t = async { runCatching { api.trending() } }
                val a = async { runCatching { api.airingToday() } }
                val on = async { runCatching { api.onAir() } }
                val m = async { runCatching { api.popularMovies() } }
                val n = async { runCatching { api.nowPlayingMovies() } }
                trending = t.await().getOrDefault(emptyList())
                airing = a.await().getOrDefault(emptyList())
                onAir = on.await().getOrDefault(emptyList())
                movies = m.await().getOrDefault(emptyList())
                nowPlaying = n.await().getOrDefault(emptyList())
            }
            if (trending.isEmpty() && airing.isEmpty() && onAir.isEmpty() && movies.isEmpty()) homeError = "Couldn't fetch TMDb titles. Check your internet connection and API credentials."
            homeLoading = false
        }
    }

    fun updateSearch(query: String) {
        searchText = query
        searchJob?.cancel()
        if (query.trim().length < 2) { searchResults = emptyList(); searching = false; return }
        searchJob = viewModelScope.launch {
            delay(350)
            searching = true
            searchResults = runCatching { api.search(query.trim()) }.getOrDefault(emptyList())
            searching = false
        }
    }

    fun open(media: MediaTitle) {
        selected = media
        detail = null; detailError = null; episodes = emptyList(); watchInfo = null; season = 1
        viewModelScope.launch {
            detailLoading = true
            val result = runCatching { api.details(media) }
            result.onSuccess { info ->
                detail = info
                if (media.type == MediaType.TV) {
                    season = info.seasons.firstOrNull { it.number == 1 }?.number
                        ?: info.seasons.firstOrNull { it.number > 0 }?.number
                        ?: info.seasons.firstOrNull()?.number ?: 1
                    loadEpisodes(season)
                }
            }.onFailure { detailError = it.message ?: "Unable to load details" }
            detailLoading = false
            watchInfo = runCatching { api.watchProviders(media) }.getOrNull()
        }
    }

    fun closeDetail() { selected = null; detail = null; episodes = emptyList(); sourceKey = null }

    fun loadEpisodes(n: Int) {
        val media = selected ?: return
        if (media.type != MediaType.TV) return
        season = n
        viewModelScope.launch {
            episodesLoading = true; episodes = emptyList()
            episodes = runCatching { api.episodes(media.id, n) }.getOrDefault(emptyList())
            episodesLoading = false
        }
    }

    fun refreshLibrary() {
        viewModelScope.launch {
            favourites = runCatching { store.favourites() }.getOrDefault(emptyList())
            history = runCatching { store.progress() }.getOrDefault(emptyList())
        }
    }

    fun toggleFavourite(media: MediaTitle) {
        viewModelScope.launch { store.toggleFavourite(media); refreshLibrary() }
    }
    fun isFavourite(media: MediaTitle) = favourites.any { it.key == media.key }

    fun openSources(key: StreamKey) {
        sourceKey = key; sourceResults = emptyList(); providerReports = emptyList(); sourceError = null; addSourceOpen = false
        loadSources()
    }

    fun loadSources() {
        val key = sourceKey ?: return
        viewModelScope.launch {
            sourcesLoading = true
            val result = runCatching { providerRegistry.scan(key) }
            result.onSuccess {
                if (sourceKey == key) { sourceResults = it.streams; providerReports = it.reports }
            }.onFailure { sourceError = it.message }
            sourcesLoading = false
        }
    }

    fun addSource(name: String, url: String, mimeType: String?) {
        val key = sourceKey ?: return
        sourceError = null
        viewModelScope.launch {
            runCatching { store.addSource(key, name, url, mimeType) }.onSuccess {
                addSourceOpen = false; loadSources()
            }.onFailure { sourceError = it.message ?: "Could not add source" }
        }
    }

    fun deleteSource(id: String) {
        viewModelScope.launch { store.removeSource(id); loadSources() }
    }
}
