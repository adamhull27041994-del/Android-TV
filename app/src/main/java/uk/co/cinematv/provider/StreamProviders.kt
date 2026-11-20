package uk.co.cinematv.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import uk.co.cinematv.data.LibraryStore
import uk.co.cinematv.model.*
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Every provider resolves a TMDb title (and a season/episode for TV) into candidate links. */
interface StreamProvider {
    val name: String
    suspend fun resolve(key: StreamKey): List<PlayableStream>
}

/** HTTPS streams explicitly added by the user for content they may access. */
class PersonalStreamProvider(private val store: LibraryStore) : StreamProvider {
    override val name = "Personal sources"
    override suspend fun resolve(key: StreamKey) = store.streamsFor(key)
}

/**
 * Cinema HQ's WatchOut adapter points at a public collection of per-season JSON files.
 * A season file is an object mapping episode numbers ("1", "2", ...) to HTTPS links.
 * Movies instead use a plain text file. This is an external, unaffiliated provider;
 * its availability, uptime and rights to any material are not guaranteed.
 *
 * See the original APK's DynWatchOutProvider.tvEpisode and getStreams.
 */
class WatchOutProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) : StreamProvider {
    override val name = "WatchOut"

    override suspend fun resolve(key: StreamKey): List<PlayableStream> = withContext(Dispatchers.IO) {
        if (key.media.id <= 0) return@withContext emptyList()
        val base = "https://raw.githubusercontent.com/Watchout2025/api/refs/heads/main/hls/"
        val requestUrl = if (key.media.type == MediaType.TV) {
            val season = key.season ?: return@withContext emptyList()
            val episode = key.episode ?: return@withContext emptyList()
            if (season < 0 || episode <= 0) return@withContext emptyList()
            "${base}tv/${key.media.id}/S$season.json"
        } else "${base}movie/${key.media.id}"

        val text = client.newCall(Request.Builder().url(requestUrl)
            .header("Accept", "application/json, text/plain, */*")
            .get().build()).execute().use { response ->
            // A missing title / season is a normal result, not a playback error.
            if (response.code == 404) return@withContext emptyList()
            if (!response.isSuccessful) throw IOException("WatchOut HTTP ${response.code}")
            response.body?.string()?.takeIf { it.length <= 250_000 } ?: return@withContext emptyList()
        }

        val rawUrl = if (key.media.type == MediaType.TV) {
            runCatching { JSONObject(text).optString(key.episode.toString()) }.getOrNull()
        } else {
            text.trim().trim('"').takeIf { it.startsWith("https://") }
        }
        val videoUrl = rawUrl?.trim()?.takeIf { it.startsWith("https://", ignoreCase = true) && !it.contains('\n') && !it.contains('\r') }
            ?: return@withContext emptyList()
        val path = android.net.Uri.parse(videoUrl).path.orEmpty().lowercase()
        val mime = when {
            path.endsWith(".mp4") -> "video/mp4"
            path.endsWith(".mpd") -> "application/dash+xml"
            // Most WatchOut HLS paths end in .txt, not .m3u8.
            else -> "application/x-mpegURL"
        }
        listOf(PlayableStream(
            id = "watchout:${key.key}",
            provider = name,
            title = "WatchOut · Direct ${if (mime == "video/mp4") "MP4" else "HLS"} (unverified)",
            url = videoUrl,
            mimeType = mime,
            headers = mapOf("Referer" to "https://vidout.pages.dev/", "Origin" to "https://vidout.pages.dev")
        ))
    }
}

/**
 * VidLink documents an HTML player by TMDb ID. This is a web embed, NOT a direct
 * Media3-compatible video URL; playback is delegated to an isolated in-app WebView.
 * Availability is unknown until the site actually loads and finds the episode.
 */
class VidLinkEmbedProvider : StreamProvider {
    override val name = "VidLink web player"
    override suspend fun resolve(key: StreamKey): List<PlayableStream> {
        if (key.media.id <= 0) return emptyList()
        val path = if (key.media.type == MediaType.MOVIE) {
            "movie/${key.media.id}"
        } else {
            val season = key.season ?: return emptyList()
            val episode = key.episode ?: return emptyList()
            if (season < 0 || episode <= 0) return emptyList()
            "tv/${key.media.id}/$season/$episode"
        }
        return listOf(PlayableStream(
            id = "vidlink:${key.key}",
            provider = name,
            title = "Open VidLink web player (availability unverified)",
            url = "https://vidlink.pro/$path",
            mimeType = "text/html"
        ))
    }
}

/** Each APK provider has a visible diagnostic status; names without an adapter are not queried. */
class ProviderRegistry(private val providers: List<StreamProvider>) {
    suspend fun scan(key: StreamKey): SourceScan = supervisorScope {
        val batches = providers.map { provider ->
            async(Dispatchers.IO) {
                try {
                    val links = withTimeoutOrNull(13_500L) { provider.resolve(key) }
                    val report = when {
                        links == null -> ProviderReport(provider.name,"Timeout","Provider did not respond")
                        links.isEmpty() -> ProviderReport(provider.name,"No links","No directly playable URL returned")
                        links.all { it.mimeType == "text/html" } ->
                            ProviderReport(provider.name,"Web option","Unverified browser player, not a direct video",links.size)
                        else -> ProviderReport(provider.name,"Links returned","Playback is not yet verified",links.size)
                    }
                    links.orEmpty() to report
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    emptyList<PlayableStream>() to ProviderReport(provider.name,"Request failed",error.message?.take(100) ?: "Network or parser error")
                }
            }
        }.awaitAll()
        val sources = batches.flatMap { it.first }.distinctBy { it.url }
            .sortedWith(compareBy<PlayableStream> { it.mimeType == "text/html" }.thenBy { it.provider })
        val reported = batches.map { it.second }
        val inventory = ApkProviderCatalog.names.map { name ->
            val matched = reported.filter { ApkProviderCatalog.canonical(it.name) == name }
            if (matched.isEmpty()) ProviderReport(name,"Not integrated",ApkProviderCatalog.unavailableReason(name))
            else {
                val best = matched.firstOrNull { it.status == "Links returned" }
                    ?: matched.firstOrNull { it.status == "Web option" }
                    ?: matched.firstOrNull { it.status == "No links" }
                    ?: matched.first()
                best.copy(name=name,count=matched.sumOf { it.count })
            }
        } + reported.filter { ApkProviderCatalog.canonical(it.name) !in ApkProviderCatalog.names }
        SourceScan(sources,inventory)
    }
    suspend fun resolve(key: StreamKey): List<PlayableStream> = scan(key).streams
}
