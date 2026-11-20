package uk.co.cinematv.provider

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import uk.co.cinematv.model.*
import java.util.concurrent.TimeUnit

/**
 * Additional adapters reconstructed from request constants in the original APK.
 * Providers are independent of Cinema TV. Availability and playback are unverified.
 */
private object ProviderNetwork {
    val client = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(13, TimeUnit.SECONDS)
        .build()

    suspend fun json(url: String, headers: Map<String, String> = emptyMap()): JSONObject? =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).header("Accept", "application/json, text/plain, */*")
            headers.forEach { (k, v) -> request.header(k, v) }
            client.newCall(request.get().build()).execute().use { response ->
                if (response.code == 404 || response.code == 204 || !response.isSuccessful) {
                    return@withContext null
                }
                val content = response.body?.string()?.takeIf { it.length < 500_000 }
                    ?: return@withContext null
                runCatching { JSONObject(content) }.getOrNull()
            }
        }
}

/** Accept direct HTTPS media candidates; skip third-party embeds and magnet links. */
private object LinkExtractor {
    private data class Candidate(
        val url: String,
        val title: String,
        val mime: String?,
        val headers: Map<String, String>
    )

    fun extract(root: JSONObject?, provider: String, key: StreamKey, baseHeaders: Map<String, String> = emptyMap()): List<PlayableStream> {
        if (root == null) return emptyList()
        val results = mutableListOf<Candidate>()

        fun consider(text: String, label: String, hint: String, headers: Map<String, String>) {
            val url = text.trim()
            val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
            if (uri.scheme != "https" || uri.host.isNullOrBlank() || url.contains('\n') || url.contains('\r')) return
            val path = uri.path.orEmpty().lowercase()
            val kind = hint.lowercase()
            if (kind.contains("embed") || path.contains("/embed/") || path.endsWith(".html")) return
            val mime = when {
                path.endsWith(".m3u8") || kind.contains("hls") || kind.contains("m3u8") ->
                    "application/x-mpegURL"
                path.endsWith(".mpd") || kind.contains("dash") -> "application/dash+xml"
                path.endsWith(".mp4") || kind.contains("mp4") -> "video/mp4"
                path.contains("/stream/") || path.contains("/hls/") -> null
                else -> return
            }
            results.add(Candidate(url, label.take(90), mime, headers))
        }

        fun mergeHeaders(value: JSONObject, previous: Map<String, String>): Map<String, String> {
            val request = value.optJSONObject("behaviorHints")
                ?.optJSONObject("proxyHeaders")
                ?.optJSONObject("request") ?: return previous
            val headers = previous.toMutableMap()
            request.keys().forEach { name ->
                if (name.equals("Referer", true) || name.equals("Origin", true) ||
                    name.equals("User-Agent", true)) {
                    request.optString(name).takeIf { it.isNotBlank() }?.let { headers[name] = it }
                }
            }
            return headers
        }

        fun walk(data: Any?, depth: Int, label: String, headers: Map<String, String>) {
            if (depth > 5 || results.size > 30) return
            when (data) {
                is JSONArray -> (0 until minOf(data.length(), 40)).forEach {
                    walk(data.opt(it), depth + 1, label, headers)
                }
                is JSONObject -> {
                    val title = data.optString("name").ifBlank {
                        data.optString("label").ifBlank { data.optString("server", label) }
                    }
                    val hint = listOf("type", "kind", "quality", "mimeType").joinToString(" ") {
                        data.optString(it)
                    }
                    val nextHeaders = mergeHeaders(data, headers)
                    listOf("url", "file", "src", "stream_url", "stream").forEach { field ->
                        when (val item = data.opt(field)) {
                            is String -> consider(item, title, hint, nextHeaders)
                            is JSONObject, is JSONArray -> walk(item, depth + 1, title, nextHeaders)
                        }
                    }
                    listOf("streams", "sources", "data", "result", "items", "links", "videos").forEach { field ->
                        walk(data.opt(field), depth + 1, title, nextHeaders)
                    }
                }
            }
        }

        walk(root, 0, provider, baseHeaders)
        return results.distinctBy { it.url }.take(15).mapIndexed { i, candidate ->
            PlayableStream(
                id = provider.lowercase().replace(" ", "") + ":" + key.key + ":" + i,
                provider = provider,
                title = provider + " · " + candidate.title + " (unverified)",
                url = candidate.url,
                mimeType = candidate.mime,
                headers = candidate.headers
            )
        }
    }
}

/** Stremio-style JSON endpoint found in DynYaStreamProvider in the APK. */
class YaStreamProvider : StreamProvider {
    override val name = "YaStream"

    override suspend fun resolve(key: StreamKey): List<PlayableStream> {
        if (key.media.id <= 0) return emptyList()
        val path = if (key.media.type == MediaType.MOVIE) {
            "/stream/movie/tmdb:" + key.media.id + ".json"
        } else {
            val season = key.season ?: return emptyList()
            val episode = key.episode ?: return emptyList()
            if (season < 0 || episode <= 0) return emptyList()
            "/stream/series/tmdb:" + key.media.id + ":" + season + ":" + episode + ".json"
        }
        return LinkExtractor.extract(ProviderNetwork.json("https://yastream.tamthai.de" + path), name, key)
    }
}

/** The APK's VidBolt /api/resolve movie/TV route. */
class VidBoltProvider : StreamProvider {
    override val name = "VidBolt"

    override suspend fun resolve(key: StreamKey): List<PlayableStream> {
        if (key.media.id <= 0) return emptyList()
        val base = "https://vidbolt.xyz/api/resolve?type=" +
            (if (key.media.type == MediaType.TV) "tv" else "movie") + "&id=" + key.media.id
        val url = if (key.media.type == MediaType.TV) {
            val season = key.season ?: return emptyList()
            val episode = key.episode ?: return emptyList()
            if (season < 0 || episode <= 0) return emptyList()
            base + "&season=" + season + "&episode=" + episode
        } else base
        val headers = mapOf("Origin" to "https://vidbolt.xyz", "Referer" to "https://vidbolt.xyz/")
        return LinkExtractor.extract(ProviderNetwork.json(url, headers), name, key, headers)
    }
}

/** The APK's ThugTV /api/stream/movie and /api/stream/tv routes. */
class ThugTvProvider : StreamProvider {
    override val name = "ThugTV"

    override suspend fun resolve(key: StreamKey): List<PlayableStream> {
        if (key.media.id <= 0) return emptyList()
        val path = if (key.media.type == MediaType.MOVIE) {
            "/stream/movie/" + key.media.id
        } else {
            val season = key.season ?: return emptyList()
            val episode = key.episode ?: return emptyList()
            if (season < 0 || episode <= 0) return emptyList()
            "/stream/tv/" + key.media.id + "/" + season + "/" + episode
        }
        val headers = mapOf("Origin" to "https://thugtv.online", "Referer" to "https://thugtv.online/")
        return LinkExtractor.extract(
            ProviderNetwork.json("https://api.thugtv.online/api" + path, headers),
            name, key, headers
        )
    }
}

/** HTML embed, not a Media3 stream; this URL pattern requires live testing. */
class TwoEmbedWebProvider : StreamProvider {
    override val name = "2Embed web player"

    override suspend fun resolve(key: StreamKey): List<PlayableStream> {
        if (key.media.id <= 0) return emptyList()
        val path = if (key.media.type == MediaType.MOVIE) {
            "movie/" + key.media.id
        } else {
            val season = key.season ?: return emptyList()
            val episode = key.episode ?: return emptyList()
            if (season < 0 || episode <= 0) return emptyList()
            "tv/" + key.media.id + "/" + season + "/" + episode
        }
        return listOf(PlayableStream(
            id = "twoembed:" + key.key,
            provider = name,
            title = "Open 2Embed web player (unverified)",
            url = "https://www.2embed.cc/embed/" + path,
            mimeType = "text/html"
        ))
    }
}
