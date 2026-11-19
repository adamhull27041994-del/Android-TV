package uk.co.cinematv.player

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import org.json.JSONObject
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.launch
import uk.co.cinematv.data.LibraryStore
import uk.co.cinematv.model.*

class PlayerActivity : Activity() {
    private var player: ExoPlayer? = null
    private var details: StreamKey? = null
    private var previousSaveElapsed = 0L
    private val store by lazy { LibraryStore(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (!url.startsWith("https://", true)) { Toast.makeText(this, "Invalid video URL", Toast.LENGTH_LONG).show(); finish(); return }
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val type = if (intent.getStringExtra(EXTRA_TYPE) == "movie") MediaType.MOVIE else MediaType.TV
        val tmdbId = intent.getIntExtra(EXTRA_TMDB_ID, 0)
        val season = intent.getIntExtra(EXTRA_SEASON, -1).takeIf { it >= 0 }
        val episode = intent.getIntExtra(EXTRA_EPISODE, -1).takeIf { it >= 0 }
        if (tmdbId > 0) details = StreamKey(MediaTitle(tmdbId, type, title, poster = intent.getStringExtra(EXTRA_POSTER)), season, episode)

        val item = MediaItem.Builder().setUri(url).apply {
            val requestedMime = intent.getStringExtra(EXTRA_MIME)
            val inferred = requestedMime ?: when {
                Uri.parse(url).path?.endsWith(".m3u8", true) == true -> MimeTypes.APPLICATION_M3U8
                Uri.parse(url).path?.endsWith(".mpd", true) == true -> MimeTypes.APPLICATION_MPD
                else -> null
            }
            if (inferred != null) setMimeType(inferred)
        }.build()
        val headers = runCatching {
            JSONObject(intent.getStringExtra(EXTRA_HEADERS) ?: "{}").let { obj ->
                obj.keys().asSequence().associateWith { obj.optString(it) }
            }
        }.getOrDefault(emptyMap())
        val dataSource = DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers)
        player = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dataSource)).build().apply {
            setMediaItem(item)
            addListener(object : Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    Toast.makeText(this@PlayerActivity, "Unable to play source: ${error.errorCodeName}", Toast.LENGTH_LONG).show()
                }
            })
        }
        val view = PlayerView(this).apply {
            player = this@PlayerActivity.player
            useController = true
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        }
        setContentView(FrameLayout(this).apply { addView(view) })
        lifecycleScope.launch {
            val start = details?.let { store.lastPosition(it) } ?: 0L
            player?.seekTo(start)
            player?.prepare()
            player?.playWhenReady = true
        }
    }

    private fun saveProgress() {
        val p = player ?: return
        val key = details ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - previousSaveElapsed < 1500L) return
        previousSaveElapsed = now
        val position = p.currentPosition.coerceAtLeast(0)
        val duration = p.duration.takeIf { it > 0 } ?: 0L
        lifecycleScope.launch {
            store.updateProgress(WatchProgress(key.media, key.season, key.episode, position, duration, System.currentTimeMillis()))
        }
    }
    override fun onPause() { saveProgress(); player?.pause(); super.onPause() }
    override fun onStop() { saveProgress(); super.onStop() }
    override fun onDestroy() { player?.release(); player = null; super.onDestroy() }

    companion object {
        private const val EXTRA_HEADERS = "request_headers"
        private const val EXTRA_URL = "stream_url"
        private const val EXTRA_MIME = "stream_mime"
        private const val EXTRA_TMDB_ID = "media_id"
        private const val EXTRA_TYPE = "media_type"
        private const val EXTRA_TITLE = "media_title"
        private const val EXTRA_POSTER = "media_poster"
        private const val EXTRA_SEASON = "season"
        private const val EXTRA_EPISODE = "episode"

        fun launch(context: android.content.Context, stream: PlayableStream, key: StreamKey? = null) {
            val intent = Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_URL, stream.url); putExtra(EXTRA_MIME, stream.mimeType)
                putExtra(EXTRA_HEADERS, JSONObject(stream.headers).toString())
                if (key != null) {
                    putExtra(EXTRA_TMDB_ID, key.media.id); putExtra(EXTRA_TYPE, key.media.type.api)
                    putExtra(EXTRA_TITLE, key.media.title); putExtra(EXTRA_POSTER, key.media.poster)
                    putExtra(EXTRA_SEASON, key.season ?: -1); putExtra(EXTRA_EPISODE, key.episode ?: -1)
                }
            }
            context.startActivity(intent)
        }
    }
}
