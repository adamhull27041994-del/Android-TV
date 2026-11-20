package uk.co.cinematv.livetv

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Android-native equivalent of the safe playlist-search portions of
 * MohamedAminGrami/IPTV-SCRAPPER: collect, match, deduplicate and probe.
 *
 * No Python interpreter, arbitrary web scraping, IP-range scanning, token
 * extraction, SSL-verification bypass, or DRM circumvention is necessary.
 */
object UkChannelDiscovery {
    data class PublicFeed(val name: String, val url: String)

    val publicFeeds = listOf(
        PublicFeed(
            "IPTV-org UK",
            "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/uk.m3u"
        ),
        PublicFeed(
            "Free-TV UK",
            "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlists/playlist_uk.m3u8"
        )
    )

    data class DiscoveryResult(
        val channels: List<LiveChannel>,
        val sourcesLoaded: Int,
        val sourcesAttempted: Int,
        val warnings: List<String>
    )

    enum class ProbeStatus {
        NOT_TESTED,
        CHECKING,
        RESPONDING,
        RESTRICTED,
        UNAVAILABLE,
        UNKNOWN
    }

    data class ProbeResult(val status: ProbeStatus, val description: String)

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .callTimeout(9, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(false)
        .build()

    @Volatile private var cache: DiscoveryResult? = null
    @Volatile private var cacheTime: Long = 0L
    private const val CACHE_MS = 10L * 60L * 1000L

    suspend fun discover(refresh: Boolean = false): DiscoveryResult {
        val now = System.currentTimeMillis()
        if (!refresh) {
            cache?.takeIf { now - cacheTime < CACHE_MS }?.let { return it }
        }

        val fetched = coroutineScope {
            publicFeeds.map { feed ->
                async {
                    feed to runCatching { LiveTvFeed.load(feed.url) }
                }
            }.awaitAll()
        }
        val all = mutableListOf<LiveChannel>()
        val warnings = mutableListOf<String>()
        var succeeded = 0
        for ((source, result) in fetched) {
            result.onSuccess { channels ->
                succeeded += 1
                all.addAll(channels.map { it.copy(source = source.name) })
            }.onFailure {
                warnings.add(source.name + ": " + (it.message ?: "could not fetch playlist"))
            }
        }

        val found = all.distinctBy { it.url }
            .sortedWith(compareBy<LiveChannel> { priority(it.name) }
                .thenBy { it.name.lowercase() }
                .thenBy { it.source })

        val report = DiscoveryResult(found, succeeded, publicFeeds.size, warnings)
        if (found.isNotEmpty()) {
            cache = report
            cacheTime = System.currentTimeMillis()
        }
        return report
    }

    /** Matching is based on the channel name, not on an unauthenticated URL. */
    fun isBbcOne(channel: LiveChannel): Boolean =
        Regex("""^BBC\s*(?:One|1)\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(channel.name.trim())

    fun priority(name: String): Int {
        val clean = name.trim().lowercase()
        return when {
            Regex("""^bbc\s*(one|1)\b""").containsMatchIn(clean) -> 0
            Regex("""^bbc\s*(two|2)\b""").containsMatchIn(clean) -> 1
            Regex("""^bbc\s*(three|3)\b""").containsMatchIn(clean) -> 2
            Regex("""^bbc\s*(four|4)\b""").containsMatchIn(clean) -> 3
            clean.startsWith("bbc news") -> 4
            Regex("""^itv\s*1\b""").containsMatchIn(clean) -> 5
            clean.startsWith("channel 4") -> 6
            clean.startsWith("channel 5") -> 7
            clean.startsWith("sky news") -> 8
            else -> 100
        }
    }

    /**
     * Small, non-invasive HTTP probe. It reads at most 8 KB of the response,
     * checks HLS/DASH manifest signatures, and never downloads full videos.
     * RESPONDING means that a manifest responded, not that viewing is licensed,
     * geo-unblocked or guaranteed to play.
     */
    suspend fun check(channel: LiveChannel): ProbeResult = withContext(Dispatchers.IO) {
        val url = channel.url
        val parsed = Uri.parse(url)
        if (parsed.scheme != "https" || parsed.host.isNullOrBlank()) {
            return@withContext ProbeResult(ProbeStatus.UNAVAILABLE, "Not a supported HTTPS stream")
        }

        try {
            val builder = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.apple.mpegurl, application/dash+xml, */*")
                .header("Range", "bytes=0-8191")
            for ((name, value) in channel.headers) {
                if (name.equals("User-Agent", true) ||
                    name.equals("Referer", true) ||
                    name.equals("Origin", true)) builder.header(name, value)
            }

            probeClient.newCall(builder.get().build()).execute().use { response ->
                when (response.code) {
                    401, 403, 451 ->
                        return@withContext ProbeResult(
                            ProbeStatus.RESTRICTED, "HTTP " + response.code + " — restricted"
                        )
                    404, 410 ->
                        return@withContext ProbeResult(
                            ProbeStatus.UNAVAILABLE, "HTTP " + response.code + " — unavailable"
                        )
                }
                if (!response.isSuccessful) {
                    return@withContext ProbeResult(
                        ProbeStatus.UNKNOWN, "HTTP " + response.code
                    )
                }

                val contentType = response.header("Content-Type").orEmpty().lowercase()
                if ("text/html" in contentType) {
                    return@withContext ProbeResult(
                        ProbeStatus.RESTRICTED, "HTML response instead of a video stream"
                    )
                }
                val sample = ByteArray(8192)
                val bytesRead = response.body?.byteStream()?.read(sample) ?: -1
                if (bytesRead <= 0) {
                    return@withContext ProbeResult(
                        ProbeStatus.UNKNOWN, "Server responded but sent no video data"
                    )
                }
                val preview = String(sample, 0, bytesRead, Charsets.UTF_8)
                    .trimStart('\uFEFF', ' ', '\n', '\r', '\t')
                if (preview.startsWith("<!doctype html", true) ||
                    preview.startsWith("<html", true)) {
                    return@withContext ProbeResult(
                        ProbeStatus.RESTRICTED, "A web page was returned, not a stream"
                    )
                }

                val path = parsed.path.orEmpty().lowercase()
                when {
                    preview.startsWith("#EXTM3U") && preview.contains("#EXT-X-") ->
                        ProbeResult(ProbeStatus.RESPONDING, "HLS manifest responds")
                    (path.endsWith(".mpd") || "dash+xml" in contentType) &&
                        preview.contains("<MPD") ->
                        ProbeResult(ProbeStatus.RESPONDING, "DASH manifest responds")
                    path.endsWith(".m3u8") ->
                        ProbeResult(ProbeStatus.UNKNOWN, "HTTP OK, HLS manifest not recognised")
                    contentType.startsWith("video/") ||
                        "application/octet-stream" in contentType ->
                        ProbeResult(ProbeStatus.RESPONDING, "Video endpoint responds")
                    else -> ProbeResult(
                        ProbeStatus.UNKNOWN, "HTTP OK, format or playback not verified"
                    )
                }
            }
        } catch (_: java.net.SocketTimeoutException) {
            ProbeResult(ProbeStatus.UNKNOWN, "Timed out")
        } catch (_: Exception) {
            ProbeResult(ProbeStatus.UNKNOWN, "Connection or network check failed")
        }
    }
}
