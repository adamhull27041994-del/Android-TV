package uk.co.cinematv.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import uk.co.cinematv.model.*
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/** These APIs are inferred from APK constants; response shapes are not guaranteed. */
class PurStreamProvider : StreamProvider {
    override val name = "PurStream"
    override suspend fun resolve(key: StreamKey): List<PlayableStream> {
        if (key.media.id <= 0) return emptyList()
        val base = "https://api.purstream.ad/api/v1/"
        val headers = mapOf("Referer" to "https://purstream.ad/",
            "Origin" to "https://purstream.ad","X-Requested-With" to "XMLHttpRequest")
        val title = URLEncoder.encode(key.media.title,"UTF-8")
        val search = ProviderNetwork.json(base + "search-bar/search/" + title, headers)
            ?: return emptyList()
        val buckets = listOfNotNull(
            search.optJSONArray("items"), search.optJSONArray("movies"),
            search.optJSONObject("data")?.optJSONArray("items"),
            search.optJSONObject("data")?.optJSONArray("movies"))
        val candidates = buckets.flatMap { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        }
        val nameNorm = key.media.title.lowercase().replace(Regex("[^a-z0-9]"),"")
        val chosen = candidates.firstOrNull { item ->
            val candidate = item.optString("title").lowercase()
                .replace(Regex("[^a-z0-9]"),"")
            candidate.isNotBlank() && candidate == nameNorm
        } ?: return emptyList()
        val contentId = chosen.optString("id").takeIf { it.matches(Regex("[0-9]+")) }
            ?: return emptyList()
        val path = if (key.media.type == MediaType.MOVIE) contentId else {
            val s = key.season ?: return emptyList()
            val e = key.episode ?: return emptyList()
            contentId + "/episode?season=" + s + "&episode=" + e
        }
        return LinkExtractor.extract(ProviderNetwork.json(base + "stream/" + path, headers),name,key,headers)
    }
}

/** Movie-oriented lmscript.xyz search; avoids returning stream links for unrelated titles. */
class LMScriptProvider : StreamProvider {
    override val name = "LMScript"
    override suspend fun resolve(key: StreamKey): List<PlayableStream> {
        if (key.media.type != MediaType.MOVIE || key.media.id <= 0) return emptyList()
        val url = "https://lmscript.xyz/v1/movies?filters[q]=" +
            URLEncoder.encode(key.media.title,"UTF-8") + "&expand=streams"
        val json = ProviderNetwork.json(url,mapOf("Referer" to "https://lookmovie2.to/"))
            ?: return emptyList()
        val items = json.optJSONArray("items")
            ?: json.optJSONObject("data")?.optJSONArray("items") ?: return emptyList()
        val matches = (0 until items.length()).mapNotNull { items.optJSONObject(it) }.filter {
            it.optInt("tmdb_id") == key.media.id ||
                it.optString("tmdb_id") == key.media.id.toString()
        }
        return matches.flatMap { LinkExtractor.extract(it,name,key) }.distinctBy { it.url }
    }
}

/** VidQueen /movie and /tv JSON routes; variants are inferred from APK strings. */
class VidQueenProvider : StreamProvider {
    override val name = "VidQueen"
    override suspend fun resolve(key: StreamKey): List<PlayableStream> = coroutineScope {
        if (key.media.id <= 0) return@coroutineScope emptyList()
        val path = if (key.media.type == MediaType.MOVIE) "movie?id=" + key.media.id else {
            val s = key.season ?: return@coroutineScope emptyList()
            val e = key.episode ?: return@coroutineScope emptyList()
            "tv?id=" + key.media.id + "&season=" + s + "&episode=" + e
        }
        val headers = mapOf("Referer" to "https://vidqueen.st/",
            "Origin" to "https://vidqueen.st")
        listOf("","&sources=s1&hevc=1","&sources=s2&hevc=1&vh=1").map { variant ->
            async(Dispatchers.IO) {
                LinkExtractor.extract(
                    ProviderNetwork.json("https://api.vidqueen.st/" + path + "&mode=json" + variant,headers),
                    name,key,headers)
            }
        }.awaitAll().flatten().distinctBy { it.url }
    }
}

/** VixSrc's /api/movie and /api/tv prefixes are in the APK, but signed URLs are not forged. */
class VixSrcProvider : StreamProvider {
    override val name = "VixSrc"
    override suspend fun resolve(key: StreamKey): List<PlayableStream> {
        if (key.media.id <= 0) return emptyList()
        val path = if (key.media.type == MediaType.MOVIE) "movie/" + key.media.id else {
            val s = key.season ?: return emptyList()
            val e = key.episode ?: return emptyList()
            "tv/" + key.media.id + "/" + s + "/" + e
        }
        val headers = mapOf("Referer" to "https://vixsrc.to/",
            "Origin" to "https://vixsrc.to")
        return LinkExtractor.extract(ProviderNetwork.json("https://vixsrc.to/api/" + path,headers),
            name,key,headers)
    }
}
