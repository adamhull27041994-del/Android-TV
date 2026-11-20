package uk.co.cinematv.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import uk.co.cinematv.BuildConfig
import uk.co.cinematv.model.*
import java.net.URLEncoder

/** Experimental, APK-derived JSON endpoint; returns only recognisable HTTPS video links. */
class MovieBoxProvider : StreamProvider {
    override val name = "MovieBox"
    override suspend fun resolve(key: StreamKey): List<PlayableStream> {
        if (key.media.id <= 0) return emptyList()
        val query = if (key.media.type == MediaType.MOVIE) "?type=movie" else {
            val s = key.season ?: return emptyList()
            val e = key.episode ?: return emptyList()
            "?type=tv&se=" + s + "&ep=" + e
        }
        val h = mapOf("Referer" to "https://net79.cc/")
        return LinkExtractor.extract(
            ProviderNetwork.json("https://net79.cc/api/embed-tmdb/" + key.media.id + query,h),
            name,key,h)
    }
}

/** These api.framextv.cc variants are present in the original APK. */
class FrameXtvProvider : StreamProvider {
    override val name = "FrameXTV"
    override suspend fun resolve(key: StreamKey): List<PlayableStream> = coroutineScope {
        if (key.media.id <= 0) return@coroutineScope emptyList()
        val params = if (key.media.type == MediaType.MOVIE)
            "type=movie&id=" + key.media.id else {
            val s = key.season ?: return@coroutineScope emptyList()
            val e = key.episode ?: return@coroutineScope emptyList()
            "type=tv&id=" + key.media.id + "&season=" + s + "&episode=" + e
        }
        val h = mapOf("Referer" to "https://framextv.cc/", "Origin" to "https://framextv.cc")
        listOf("","&provider=sparky","&provider=titan").map { variant ->
            async(Dispatchers.IO) {
                LinkExtractor.extract(
                    ProviderNetwork.json("https://api.framextv.cc/api/stream?" + params + variant,h),
                    name,key,h)
            }
        }.awaitAll().flatten().distinctBy { it.url }
    }
}

/** Encoded responses are ignored rather than bypassed. */
class VidLinkApiProvider : StreamProvider {
    override val name = "VidLink API"
    override suspend fun resolve(key: StreamKey): List<PlayableStream> {
        if (key.media.id <= 0) return emptyList()
        val path = if (key.media.type == MediaType.MOVIE) "movie/" + key.media.id else {
            val s = key.season ?: return emptyList()
            val e = key.episode ?: return emptyList()
            "tv/" + key.media.id + "/" + s + "/" + e
        }
        val h = mapOf("Referer" to "https://vidlink.pro/", "Origin" to "https://vidlink.pro")
        return LinkExtractor.extract(ProviderNetwork.json("https://vidlink.pro/api/b/" + path,h),name,key,h)
    }
}

/** Resolves up to four backend IDs from the APK's Vuflix discovery endpoint. */
class VuflixProvider : StreamProvider {
    override val name = "Vuflix"
    override suspend fun resolve(key: StreamKey): List<PlayableStream> = coroutineScope {
        if (key.media.id <= 0) return@coroutineScope emptyList()
        val h = mapOf("Referer" to "https://vuflix.co/", "Origin" to "https://vuflix.co")
        val root = ProviderNetwork.json("https://vuflix.co/api/player/providers",h)
            ?: return@coroutineScope emptyList()
        val entries = root.optJSONArray("providers")
            ?: root.optJSONObject("data")?.optJSONArray("providers")
            ?: return@coroutineScope emptyList()
        val ids = (0 until entries.length()).mapNotNull {
            entries.optJSONObject(it)?.optString("id")?.takeIf { x -> x.isNotBlank() }
        }.distinct().take(4)
        val params = if (key.media.type == MediaType.MOVIE) "type=movie&tmdbId=" + key.media.id else {
            val s = key.season ?: return@coroutineScope emptyList()
            val e = key.episode ?: return@coroutineScope emptyList()
            "type=tv&tmdbId=" + key.media.id + "&season=" + s + "&episode=" + e
        }
        ids.map { id ->
            async(Dispatchers.IO) {
                val url = "https://vuflix.co/api/player/sources?" + params +
                    "&provider=" + URLEncoder.encode(id,"UTF-8")
                LinkExtractor.extract(ProviderNetwork.json(url,h),name,key,h)
            }
        }.awaitAll().flatten().distinctBy { it.url }
    }
}

/** Additional public TMDb external-IDs lookup required by Stremio-compatible torrent services. */
private object ImdbLookup {
    suspend fun lookup(key: StreamKey): String? = withContext(Dispatchers.IO) {
        if (BuildConfig.TMDB_BEARER.isBlank() && BuildConfig.TMDB_API_KEY.isBlank()) return@withContext null
        val base = "https://api.themoviedb.org/3/" +
            key.media.type.api + "/" + key.media.id + "/external_ids"
        val url = if (BuildConfig.TMDB_BEARER.isBlank())
            base + "?api_key=" + BuildConfig.TMDB_API_KEY else base
        val req = Request.Builder().url(url).header("Accept","application/json")
        if (BuildConfig.TMDB_BEARER.isNotBlank())
            req.header("Authorization","Bearer " + BuildConfig.TMDB_BEARER)
        ProviderNetwork.client.newCall(req.build()).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val data = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull()
            data?.optString("imdb_id")?.takeIf { it.matches(Regex("tt[0-9]{7,}")) }
        }
    }
}

/** Torrent metadata is not itself a stream; return only compatible direct HTTPS video links. */
class StremioTorrentProvider(
    override val name: String,
    private val base: String
) : StreamProvider {
    override suspend fun resolve(key: StreamKey): List<PlayableStream> {
        val id = ImdbLookup.lookup(key) ?: return emptyList()
        val suffix = if (key.media.type == MediaType.MOVIE) id else {
            val s = key.season ?: return emptyList()
            val e = key.episode ?: return emptyList()
            id + ":" + s + ":" + e
        }
        val type = if (key.media.type == MediaType.MOVIE) "movie" else "series"
        return LinkExtractor.extract(ProviderNetwork.json(base + "/" + type + "/" + suffix + ".json"),name,key)
    }
}

/** A browser-only fallback for the APK's RiveStream watch URL. */
class RiveStreamWebProvider : StreamProvider {
    override val name = "RiveStream web player"
    override suspend fun resolve(key: StreamKey): List<PlayableStream> {
        if (key.media.id <= 0) return emptyList()
        val tail = if (key.media.type == MediaType.MOVIE) "" else {
            val s = key.season ?: return emptyList()
            val e = key.episode ?: return emptyList()
            "&season=" + s + "&episode=" + e
        }
        return listOf(PlayableStream(
            id="riveweb:" + key.key, provider=name,
            title="Open RiveStream web page (unverified)",
            url="https://www.rivestream.app/watch?type=" + key.media.type.api +
                "&id=" + key.media.id + tail,
            mimeType="text/html"))
    }
}
