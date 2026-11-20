package uk.co.cinematv.model

import org.json.JSONArray
import org.json.JSONObject

enum class MediaType(val api: String) { TV("tv"), MOVIE("movie") }

data class MediaTitle(
    val id: Int,
    val type: MediaType,
    val title: String,
    val overview: String = "",
    val poster: String? = null,
    val backdrop: String? = null,
    val rating: Double = 0.0,
    val date: String = ""
) {
    val key: String get() = "${type.api}:$id"
    fun toJson() = JSONObject().apply {
        put("id", id); put("type", type.api); put("title", title); put("overview", overview)
        put("poster", poster); put("backdrop", backdrop); put("rating", rating); put("date", date)
    }
    companion object {
        fun fromJson(obj: JSONObject, forcedType: MediaType? = null): MediaTitle {
            val type = forcedType ?: if (obj.optString("media_type", obj.optString("type", "tv")) == "movie") MediaType.MOVIE else MediaType.TV
            return MediaTitle(
                obj.optInt("id"), type,
                obj.optString("name", obj.optString("title", "Untitled")),
                obj.optString("overview"),
                obj.optString("poster_path", obj.optString("poster")).takeIf { it.isNotBlank() && it != "null" },
                obj.optString("backdrop_path", obj.optString("backdrop")).takeIf { it.isNotBlank() && it != "null" },
                obj.optDouble("vote_average", obj.optDouble("rating", 0.0)),
                obj.optString("first_air_date", obj.optString("release_date", obj.optString("date")))
            )
        }
    }
}

data class SeasonInfo(val number: Int, val title: String, val episodeCount: Int, val poster: String?, val airDate: String)
data class EpisodeInfo(val number: Int, val title: String, val overview: String, val still: String?, val airDate: String, val runtime: Int)
data class WatchProvider(val name: String, val logo: String?)
data class WatchInfo(val link: String?, val providers: List<WatchProvider>)
data class ShowDetails(val media: MediaTitle, val tagline: String, val seasons: List<SeasonInfo>, val runtimeMinutes: Int)

data class PlayableStream(
    val id: String,
    val provider: String,
    val title: String,
    val url: String,
    val mimeType: String? = null,
    val headers: Map<String, String> = emptyMap()
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("provider", provider); put("title", title); put("url", url)
        put("mimeType", mimeType)
        put("headers", JSONObject(headers))
    }
    companion object {
        fun fromJson(obj: JSONObject): PlayableStream {
            val h = mutableMapOf<String,String>()
            obj.optJSONObject("headers")?.let { hdr -> hdr.keys().forEach { k -> h[k] = hdr.optString(k) } }
            return PlayableStream(obj.optString("id"), obj.optString("provider", "Personal library"), obj.optString("title", "Source"), obj.optString("url"), obj.optString("mimeType").takeIf { it.isNotBlank() }, h)
        }
    }
}

data class StreamKey(val media: MediaTitle, val season: Int? = null, val episode: Int? = null, val episodeTitle: String? = null) {
    val key get() = "${media.key}:${season ?: 0}:${episode ?: 0}"
}

data class SavedStream(val mediaKey: String, val season: Int?, val episode: Int?, val stream: PlayableStream) {
    fun toJson() = JSONObject().apply {
        put("mediaKey", mediaKey); put("season", season); put("episode", episode); put("stream", stream.toJson())
    }
    companion object {
        fun fromJson(o: JSONObject) = SavedStream(o.optString("mediaKey"), if (o.isNull("season")) null else o.optInt("season"), if (o.isNull("episode")) null else o.optInt("episode"), PlayableStream.fromJson(o.getJSONObject("stream")))
    }
}

data class WatchProgress(
    val media: MediaTitle,
    val season: Int?,
    val episode: Int?,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val episodeTitle: String? = null
) {
    val fraction: Float get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    fun toJson() = JSONObject().apply {
        put("media", media.toJson()); put("season", season); put("episode", episode)
        put("positionMs", positionMs); put("durationMs", durationMs); put("updatedAt", updatedAt)
        if (!episodeTitle.isNullOrBlank()) put("episodeTitle", episodeTitle)
    }
    companion object {
        fun fromJson(o: JSONObject) = WatchProgress(
            MediaTitle.fromJson(o.getJSONObject("media")),
            if (o.isNull("season")) null else o.optInt("season"),
            if (o.isNull("episode")) null else o.optInt("episode"),
            o.optLong("positionMs"), o.optLong("durationMs"), o.optLong("updatedAt"),
            o.optString("episodeTitle").takeIf { it.isNotBlank() && it != "null" }
        )
    }
}

fun JSONArray.asObjects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
fun posterUrl(path: String?, size: String = "w342"): String? = path?.let { if (it.startsWith("https://")) it else "https://image.tmdb.org/t/p/$size$it" }
