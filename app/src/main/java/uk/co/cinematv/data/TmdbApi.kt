package uk.co.cinematv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import uk.co.cinematv.BuildConfig
import uk.co.cinematv.model.*
import java.io.IOException
import java.util.concurrent.TimeUnit

class TmdbApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private suspend fun request(path: String, parameters: Map<String, String> = emptyMap()): JSONObject = withContext(Dispatchers.IO) {
        require(BuildConfig.TMDB_BEARER.isNotBlank() || BuildConfig.TMDB_API_KEY.isNotBlank()) { "Add your TMDb credentials to tmdb.properties" }
        val builder = "https://api.themoviedb.org/3$path".toHttpUrl().newBuilder()
        builder.addQueryParameter("language", "en-GB")
        parameters.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        if (BuildConfig.TMDB_BEARER.isBlank()) builder.addQueryParameter("api_key", BuildConfig.TMDB_API_KEY)
        val req = Request.Builder().url(builder.build()).apply {
            if (BuildConfig.TMDB_BEARER.isNotBlank()) header("Authorization", "Bearer ${BuildConfig.TMDB_BEARER}")
            header("Accept", "application/json")
        }.build()
        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) throw IOException("TMDb HTTP ${response.code}: ${response.message}")
            val raw = response.body?.string() ?: throw IOException("Empty TMDb response")
            JSONObject(raw)
        }
    }

    private suspend fun mediaList(path: String, type: MediaType? = null, params: Map<String,String> = emptyMap()): List<MediaTitle> =
        request(path, params).optJSONArray("results")?.asObjects()?.mapNotNull {
            val actualType = type ?: when (it.optString("media_type")) {
                "movie" -> MediaType.MOVIE; "tv" -> MediaType.TV; else -> null
            }
            actualType?.let { t -> MediaTitle.fromJson(it, t) }
        }?.filter { it.id > 0 } ?: emptyList()

    suspend fun trending(): List<MediaTitle> = mediaList("/trending/tv/week", MediaType.TV)
    suspend fun airingToday(): List<MediaTitle> = mediaList("/tv/airing_today", MediaType.TV, mapOf("timezone" to "Europe/London"))
    suspend fun onAir(): List<MediaTitle> = mediaList("/tv/on_the_air", MediaType.TV)
    suspend fun popularMovies(): List<MediaTitle> = mediaList("/movie/popular", MediaType.MOVIE)
    suspend fun nowPlayingMovies(): List<MediaTitle> = mediaList("/movie/now_playing", MediaType.MOVIE, mapOf("region" to "GB"))
    suspend fun search(query: String): List<MediaTitle> = if (query.isBlank()) emptyList() else mediaList("/search/multi", params = mapOf("query" to query, "include_adult" to "false"))

    suspend fun details(media: MediaTitle): ShowDetails {
        val obj = request("/${media.type.api}/${media.id}")
        val seasons = obj.optJSONArray("seasons")?.asObjects()?.map { s ->
            SeasonInfo(s.optInt("season_number"), s.optString("name", "Season"), s.optInt("episode_count"), s.optString("poster_path").takeIf { it.isNotBlank() }, s.optString("air_date"))
        } ?: emptyList()
        val runtime = if (media.type == MediaType.MOVIE) obj.optInt("runtime") else obj.optJSONArray("episode_run_time")?.optInt(0) ?: 0
        return ShowDetails(MediaTitle.fromJson(obj, media.type), obj.optString("tagline"), seasons, runtime)
    }

    suspend fun episodes(tvId: Int, season: Int): List<EpisodeInfo> {
        val result = request("/tv/$tvId/season/$season")
        return result.optJSONArray("episodes")?.asObjects()?.map { ep ->
            EpisodeInfo(ep.optInt("episode_number"), ep.optString("name", "Episode"), ep.optString("overview"), ep.optString("still_path").takeIf { it.isNotBlank() }, ep.optString("air_date"), ep.optInt("runtime"))
        } ?: emptyList()
    }

    suspend fun watchProviders(media: MediaTitle): WatchInfo {
        val gb = request("/${media.type.api}/${media.id}/watch/providers").optJSONObject("results")?.optJSONObject("GB")
        val providers = (gb?.optJSONArray("flatrate")?.asObjects().orEmpty() + gb?.optJSONArray("free")?.asObjects().orEmpty() + gb?.optJSONArray("ads")?.asObjects().orEmpty())
            .distinctBy { it.optInt("provider_id") }
            .map { WatchProvider(it.optString("provider_name"), it.optString("logo_path").takeIf { x -> x.isNotBlank() }) }
        return WatchInfo(gb?.optString("link")?.takeIf { it.isNotBlank() }, providers)
    }
}
