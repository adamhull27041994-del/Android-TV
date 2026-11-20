package uk.co.cinematv.livetv

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * A playlist and channel-feed reader for sources the user is authorised to use.
 *
 * The structure mirrors the observed AK47Sports channel schema where possible,
 * but does NOT contact, impersonate or include AK47's private Firebase backend.
 */
data class LiveChannel(
    val name: String,
    val group: String,
    val url: String,
    val logo: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val source: String = "Custom playlist"
)

object LiveTvFeed {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private const val MAX_FEED_BYTES = 3_000_000
    private val attrPattern = Regex("""([\w-]+)="([^"]*)"""")

    /** The URL is stored only on the device and is never committed to GitHub. */
    suspend fun load(url: String): List<LiveChannel> = withContext(Dispatchers.IO) {
        val parsed = Uri.parse(url.trim())
        if (parsed.scheme != "https" || parsed.host.isNullOrBlank()) {
            throw IllegalArgumentException("Enter a valid HTTPS M3U or JSON feed URL")
        }
        val request = Request.Builder().url(url.trim())
            .header("Accept", "application/json, audio/x-mpegurl, text/plain, */*")
            .get().build()

        val contents = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Playlist request failed (HTTP " + response.code + ")")
            val body = response.body ?: throw IOException("Playlist server returned no data")
            if (body.contentLength() > MAX_FEED_BYTES) throw IOException("Playlist is too large")
            val bytes = body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                val output = java.io.ByteArrayOutputStream()
                while (true) {
                    val count = input.read(buffer)
                    if (count <= 0) break
                    if (output.size() + count > MAX_FEED_BYTES) throw IOException("Playlist is too large")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            bytes.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        }

        val result = if (contents.startsWith("{") || contents.startsWith("[")) {
            parseChannelJson(contents)
        } else parseM3u(contents)
        if (result.isEmpty()) {
            throw IOException("No supported HTTPS channels found. Check the playlist format and stream URLs.")
        }
        result.distinctBy { it.url + "|" + it.name }.take(2500)
    }

    fun parseM3u(data: String): List<LiveChannel> {
        val channels = mutableListOf<LiveChannel>()
        var pendingName: String? = null
        var pendingGroup = "Other"
        var pendingLogo: String? = null
        var pendingHeaders = mutableMapOf<String, String>()

        data.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    pendingName = line.substringAfter(",", "").trim()
                        .ifBlank { "Unnamed channel" }
                    val meta = line.substringBefore(",")
                    val fields = attrPattern.findAll(meta).associate {
                        it.groupValues[1].lowercase() to it.groupValues[2]
                    }
                    pendingName = fields["tvg-name"]?.takeIf { it.isNotBlank() } ?: pendingName
                    pendingGroup = fields["group-title"]?.takeIf { it.isNotBlank() } ?: "Other"
                    pendingLogo = fields["tvg-logo"]?.takeIf { it.startsWith("https://") }
                    pendingHeaders = mutableMapOf()
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    pendingGroup = line.substringAfter(":").trim().ifBlank { "Other" }
                }
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val pair = line.substringAfter(":")
                    val value = pair.substringAfter("=", "").trim()
                    when (pair.substringBefore("=").lowercase()) {
                        "http-referrer", "http-referer" ->
                            if (value.startsWith("https://")) pendingHeaders["Referer"] = value
                        "http-user-agent" ->
                            if (value.isNotEmpty()) pendingHeaders["User-Agent"] = value.take(180)
                        "http-origin" ->
                            if (value.startsWith("https://")) pendingHeaders["Origin"] = value
                    }
                }
                line.startsWith("#") || line.isEmpty() -> Unit
                else -> {
                    if (line.startsWith("https://", ignoreCase = true) &&
                        Uri.parse(line).host?.isNotBlank() == true) {
                        val name = pendingName ?: Uri.parse(line).lastPathSegment ?: "Live channel"
                        channels.add(LiveChannel(name.take(120), pendingGroup.take(80), line,
                            pendingLogo, pendingHeaders.toMap()))
                    }
                    pendingName = null
                    pendingLogo = null
                    pendingGroup = "Other"
                    pendingHeaders = mutableMapOf()
                }
            }
        }
        return channels
    }

    /**
     * Supports AK47-style {"channel":{"name","logo","links", "is_playlist"}}
     * objects and JSON channel arrays. This is shape compatibility, not a
     * verified working integration with AK47's remotely configured server.
     */
    fun parseChannelJson(text: String): List<LiveChannel> {
        val root = runCatching { JSONObject(text) }.getOrNull()
        val entries = when {
            root != null && root.optJSONArray("channels") != null -> root.optJSONArray("channels")
            root != null && root.optJSONArray("items") != null -> root.optJSONArray("items")
            root != null && root.optJSONArray("data") != null -> root.optJSONArray("data")
            root != null && root.optJSONObject("channel") != null ->
                JSONArray().put(root)
            else -> runCatching { JSONArray(text) }.getOrNull()
        } ?: return emptyList()

        val result = mutableListOf<LiveChannel>()
        for (i in 0 until minOf(entries.length(), 2500)) {
            val item = entries.optJSONObject(i) ?: continue
            if (item.has("visible") && !item.optBoolean("visible", true)) continue
            val channel = item.optJSONObject("channel") ?: item
            val name = channel.optString("name").ifBlank { channel.optString("title") }
            if (name.isBlank()) continue
            val group = channel.optString("group").ifBlank {
                channel.optString("category").ifBlank { "Other" }
            }
            val logo = channel.optString("logo").takeIf { it.startsWith("https://") }
            val links = channel.opt("links") ?: channel.opt("url")
            val values = when (links) {
                is String -> listOf(links)
                is JSONArray -> (0 until links.length()).mapNotNull { index ->
                    when (val link = links.opt(index)) {
                        is String -> link
                        is JSONObject -> link.optString("url").ifBlank { link.optString("link") }
                        else -> null
                    }
                }
                else -> emptyList()
            }
            for (value in values) {
                // 'is_playlist' can indicate the link is another playlist rather
                // than a single channel; don't pretend that is a video stream.
                if (channel.optBoolean("is_playlist", false)) continue
                if (value.startsWith("https://", true) && Uri.parse(value).host?.isNotBlank() == true) {
                    result.add(LiveChannel(name.take(120), group.take(80), value, logo))
                }
            }
        }
        return result
    }
}
