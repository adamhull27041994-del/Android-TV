package uk.co.cinematv.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import uk.co.cinematv.model.*
import uk.co.cinematv.provider.VoeLinks

private val Context.cinemaStore by preferencesDataStore("cinema_library")

class LibraryStore(private val context: Context) {
    private val favKey = stringPreferencesKey("favourites_json")
    private val streamsKey = stringPreferencesKey("stream_sources_json")
    private val progressKey = stringPreferencesKey("playback_history_json")

    private suspend fun read(key: androidx.datastore.preferences.core.Preferences.Key<String>): List<JSONObject> {
        val raw = context.cinemaStore.data.first()[key] ?: "[]"
        return runCatching { JSONArray(raw).asObjects() }.getOrDefault(emptyList())
    }

    suspend fun favourites(): List<MediaTitle> = read(favKey).mapNotNull { runCatching { MediaTitle.fromJson(it) }.getOrNull() }
    suspend fun toggleFavourite(media: MediaTitle) {
        context.cinemaStore.edit { prefs ->
            val all = runCatching { JSONArray(prefs[favKey] ?: "[]").asObjects().map { MediaTitle.fromJson(it) } }.getOrDefault(emptyList())
            val updated = if (all.any { it.key == media.key }) all.filter { it.key != media.key } else listOf(media) + all
            prefs[favKey] = JSONArray().apply { updated.forEach { put(it.toJson()) } }.toString()
        }
    }

    suspend fun streamsFor(key: StreamKey): List<PlayableStream> = read(streamsKey).mapNotNull {
        runCatching { SavedStream.fromJson(it) }.getOrNull()
    }.filter { it.mediaKey == key.media.key && it.season == key.season && it.episode == key.episode }.map { it.stream }

    suspend fun addSource(key: StreamKey, title: String, url: String, mimeType: String?) {
        val normalized = VoeLinks.embedUrl(url) ?: url.trim()
        require(normalized.startsWith("https://", true)) { "Please use a valid HTTPS video URL" }
        val selectedMime = if (VoeLinks.embedUrl(url) != null) "text/html" else mimeType
        val src = SavedStream(key.media.key, key.season, key.episode, PlayableStream(java.util.UUID.randomUUID().toString(), "Personal sources", title.trim().ifEmpty { "My stream" }, normalized, selectedMime))
        context.cinemaStore.edit { prefs ->
            val old = runCatching { JSONArray(prefs[streamsKey] ?: "[]") }.getOrDefault(JSONArray())
            old.put(src.toJson()); prefs[streamsKey] = old.toString()
        }
    }

    suspend fun removeSource(id: String) {
        context.cinemaStore.edit { prefs ->
            val all = runCatching { JSONArray(prefs[streamsKey] ?: "[]").asObjects() }.getOrDefault(emptyList())
            prefs[streamsKey] = JSONArray().apply {
                all.filterNot { it.optJSONObject("stream")?.optString("id") == id }.forEach { put(it) }
            }.toString()
        }
    }

    suspend fun progress(): List<WatchProgress> = read(progressKey).mapNotNull { runCatching { WatchProgress.fromJson(it) }.getOrNull() }.sortedByDescending { it.updatedAt }

    suspend fun updateProgress(progress: WatchProgress) {
        context.cinemaStore.edit { prefs ->
            val old = runCatching { JSONArray(prefs[progressKey] ?: "[]").asObjects().map { WatchProgress.fromJson(it) } }.getOrDefault(emptyList())
            val updated = (listOf(progress) + old.filterNot { it.media.key == progress.media.key && it.season == progress.season && it.episode == progress.episode }).take(100)
            prefs[progressKey] = JSONArray().apply { updated.forEach { put(it.toJson()) } }.toString()
        }
    }

    suspend fun lastPosition(key: StreamKey): Long {
        val item = progress().firstOrNull {
            it.media.key == key.media.key && it.season == key.season && it.episode == key.episode
        } ?: return 0L
        return if (item.durationMs > 0L && item.fraction >= 0.95f) 0L else item.positionMs
    }
}
