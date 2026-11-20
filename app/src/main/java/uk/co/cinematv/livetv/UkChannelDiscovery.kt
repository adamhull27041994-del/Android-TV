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

    /** Public playlist URLs checked against the original scraper's source list.
     * Some URLs listed in that project now return 404, so they are omitted.
     * Entries here are public indexes, not guarantees of broadcasting rights.
     */
    val searchFeeds = publicFeeds + listOf(
        PublicFeed(
            "Free-TV worldwide",
            "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8"
        ),
        PublicFeed(
            "IPTV-org USA",
            "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/us.m3u"
        ),
        PublicFeed(
            "IPTV-org Canada",
            "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/ca.m3u"
        ),
        PublicFeed(
            "IPTV-org Australia",
            "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/au.m3u"
        ),
        PublicFeed(
            "IPTV-org New Zealand",
            "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/nz.m3u"
        ),
        PublicFeed(
            "IPTV-org India",
            "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/in.m3u"
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
        MISMATCH,
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
    @Volatile private var searchCache: DiscoveryResult? = null
    @Volatile private var searchCacheTime: Long = 0L

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


    /**
     * Search channel *names* across multiple public IPTV directories, using
     * the GitHub Python scraper's collection/deduplication/search pattern.
     * Searching for Sky Sports can return listings, but not paid access.
     */
    suspend fun searchPublic(query: String, refresh: Boolean = false): DiscoveryResult {
        require(query.trim().length >= 2) { "Enter at least two characters to search" }
        val now = System.currentTimeMillis()
        val cached = searchCache?.takeIf { !refresh && now - searchCacheTime < CACHE_MS }
        val all = if (cached != null) cached else {
            val fetched = coroutineScope {
                searchFeeds.map { feed ->
                    async {
                        feed to runCatching { LiveTvFeed.load(feed.url) }
                    }
                }.awaitAll()
            }
            val combined = mutableListOf<LiveChannel>()
            val warnings = mutableListOf<String>()
            var successes = 0
            fetched.forEach { (feed, result) ->
                result.onSuccess { channels ->
                    successes++
                    combined.addAll(channels.map { it.copy(source = feed.name) })
                }.onFailure { error ->
                    warnings.add(feed.name + ": " + (error.message ?: "unavailable"))
                }
            }
            val collected = DiscoveryResult(
                combined.distinctBy { it.url + "|" + it.name.lowercase() },
                successes,
                searchFeeds.size,
                warnings
            )
            if (collected.channels.isNotEmpty()) {
                searchCache = collected
                searchCacheTime = System.currentTimeMillis()
            }
            collected
        }
        return all.copy(
            channels = all.channels.filter { matchesChannelName(it.name, query) }
                .sortedWith(
                    compareBy<LiveChannel> { if (skySportsLabelMismatch(it) == null) 0 else 1 }
                        .thenBy { priority(it.name) }
                        .thenBy { it.name.lowercase() }
                        .thenBy { it.source }
                )
                .take(250)
        )
    }

    /** Match words in channel name, avoiding matches solely in hostnames or URL tokens. */
    fun matchesChannelName(name: String, query: String): Boolean {
        val normalizedName = name.lowercase()
            .replace(Regex("[^a-z0-9]+"), " ").trim()
        val terms = query.lowercase()
            .replace(Regex("[^a-z0-9]+"), " ").trim()
            .split(' ').filter { it.isNotBlank() }
        if (terms.isEmpty()) return false
        val words = normalizedName.split(' ')
        return terms.all { token -> words.any { it == token } } ||
            normalizedName.replace(" ", "").contains(terms.joinToString(""))
    }

    fun isSkySports(channel: LiveChannel): Boolean =
        matchesChannelName(channel.name, "Sky Sports")

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
     * Some unofficial IPTV entries have misleading names. For example, a
     * channel listed as Sky Sports News currently points to an HLS path
     * explicitly labelled SKY_SPORTS_PREMIER_LEAGUE. Do not claim it is News,
     * or mark it working, even if the HTTP manifest responds.
     *
     * This only flags clear, contradictory Sky-channel labels in URLs. An
     * unrecognisable URL remains unverified; it is not evidence of a match.
     */
    fun skySportsLabelMismatch(channel: LiveChannel): String? {
        val labelled = channel.name.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
        if (!labelled.startsWith("sky sports ")) return null
        val expected = when {
            "main event" in labelled -> "Main Event"
            "premier league" in labelled -> "Premier League"
            Regex("""\bfootball\b""").containsMatchIn(labelled) -> "Football"
            Regex("""\bcricket\b""").containsMatchIn(labelled) -> "Cricket"
            Regex("""\bgolf\b""").containsMatchIn(labelled) -> "Golf"
            Regex("""\bf1\b""").containsMatchIn(labelled) -> "F1"
            Regex("""\btennis\b""").containsMatchIn(labelled) -> "Tennis"
            Regex("""\baction\b""").containsMatchIn(labelled) -> "Action"
            Regex("""\bracing\b""").containsMatchIn(labelled) -> "Racing"
            Regex("""\bnews\b""").containsMatchIn(labelled) -> "News"
            Regex("""\bmix\b""").containsMatchIn(labelled) -> "Mix"
            Regex("""\bnfl\b""").containsMatchIn(labelled) -> "NFL"
            else -> null
        } ?: return null
        val path = Uri.parse(channel.url).path.orEmpty().uppercase()
            .replace('-', '_').replace(' ', '_')
        val actual = when {
            "SKY_SPORTS_MAIN_EVENT" in path -> "Main Event"
            "SKY_SPORTS_PREMIER_LEAGUE" in path -> "Premier League"
            "SKY_SPORTS_FOOTBALL" in path -> "Football"
            "SKY_SPORTS_CRICKET" in path -> "Cricket"
            "SKY_SPORTS_GOLF" in path -> "Golf"
            "SKY_SPORTS_F1" in path -> "F1"
            "SKY_SPORTS_TENNIS" in path -> "Tennis"
            "SKY_SPORTS_ACTION" in path -> "Action"
            "SKY_SPORTS_RACING" in path -> "Racing"
            "SKY_SPORTS_NEWS" in path -> "News"
            "SKY_SPORTS_MIX" in path -> "Mix"
            "SKY_SPORTS_NFL" in path -> "NFL"
            else -> null
        } ?: return null
        return if (expected == actual) null
        else "Label mismatch: $expected listing links to $actual"
    }

    /**
     * Small, non-invasive HTTP probe. It reads at most 8 KB of the response,
     * checks HLS/DASH manifest signatures, and never downloads full videos.
     * RESPONDING means that a manifest responded, not that viewing is licensed,
     * geo-unblocked or guaranteed to play.
     */
    suspend fun check(channel: LiveChannel): ProbeResult = withContext(Dispatchers.IO) {
        skySportsLabelMismatch(channel)?.let {
            return@withContext ProbeResult(ProbeStatus.MISMATCH, it)
        }
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
