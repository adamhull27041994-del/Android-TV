package uk.co.cinematv.provider

import kotlinx.coroutines.Dispatchers
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

/** Concurrent resolution prevents one unavailable API from stalling the source list. */
class ProviderRegistry(private val providers: List<StreamProvider>) {
    suspend fun resolve(key: StreamKey): List<PlayableStream> = kotlinx.coroutines.supervisorScope {
        providers.map { provider ->
            kotlinx.coroutines.async(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    kotlinx.coroutines.withTimeoutOrNull(14_000L) { provider.resolve(key) }.orEmpty()
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }.let { tasks ->
            kotlinx.coroutines.awaitAll(*tasks.toTypedArray()).flatten()
                .distinctBy { it.url }
                .sortedWith(compareBy<PlayableStream> { it.mimeType == "text/html" }.thenBy { it.provider })
        }
    }
}
