package uk.co.cinematv.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import uk.co.cinematv.data.LibraryStore
import uk.co.cinematv.model.*

/**
 * Cinema TV's own controls over Media3's video surface.
 * Only direct HTTP media is routed here; HTML embeds keep their own web player.
 */
class PlayerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private var details: StreamKey? = null
    private var previousSaveElapsed = 0L
    private val errorState = mutableStateOf<String?>(null)
    private val store by lazy { LibraryStore(applicationContext) }
    private val screenAwake by lazy { PlaybackScreenAwake(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screenAwake.activate()
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val uri = Uri.parse(url)
        if (uri.scheme != "https" || uri.host.isNullOrBlank()) {
            Toast.makeText(this, "Invalid video URL", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val mediaTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Cinema TV" }
        val kind = if (intent.getStringExtra(EXTRA_TYPE) == "movie") MediaType.MOVIE else MediaType.TV
        val tmdbId = intent.getIntExtra(EXTRA_TMDB_ID, 0)
        val season = intent.getIntExtra(EXTRA_SEASON, -1).takeIf { it >= 0 }
        val episode = intent.getIntExtra(EXTRA_EPISODE, -1).takeIf { it >= 0 }
        val episodeTitle = intent.getStringExtra(EXTRA_EPISODE_TITLE)
        if (tmdbId > 0) {
            details = StreamKey(MediaTitle(tmdbId, kind, mediaTitle,
                poster = intent.getStringExtra(EXTRA_POSTER)), season, episode, episodeTitle)
        }

        val sourceMime = intent.getStringExtra(EXTRA_MIME)
        if (sourceMime == "text/html") {
            Toast.makeText(this, "This source requires its web player", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val item = MediaItem.Builder().setUri(uri).apply {
            val inferred = sourceMime ?: when {
                uri.path.orEmpty().endsWith(".m3u8", true) -> MimeTypes.APPLICATION_M3U8
                uri.path.orEmpty().endsWith(".mpd", true) -> MimeTypes.APPLICATION_MPD
                else -> null
            }
            if (inferred != null) setMimeType(inferred)
        }.build()
        val headers = runCatching {
            JSONObject(intent.getStringExtra(EXTRA_HEADERS) ?: "{}").let { obj ->
                obj.keys().asSequence().associateWith { obj.optString(it) }
            }
        }.getOrDefault(emptyMap())
        val httpFactory = DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers)
        val exo = ExoPlayer.Builder(this).setMediaSourceFactory(
            DefaultMediaSourceFactory(this).setDataSourceFactory(httpFactory)
        ).build()
        player = exo
        exo.setMediaItem(item)
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                errorState.value = "Playback failed (" + error.errorCodeName + "). Try another source."
            }
        })

        val subTitle = when {
            season != null && episode != null ->
                "Season " + season + " · Episode " + episode
            else -> "Movie"
        }
        setContent {
            CinemaPlayerUI(
                player = exo,
                title = mediaTitle,
                subtitle = subTitle,
                errorMessage = errorState.value,
                onClose = { finish() },
                onRetry = {
                    errorState.value = null
                    exo.prepare()
                    exo.playWhenReady = true
                }
            )
        }
        lifecycleScope.launch {
            val start = details?.let { store.lastPosition(it) } ?: 0L
            if (start > 0L) exo.seekTo(start)
            exo.prepare()
            exo.playWhenReady = true
        }
        lifecycleScope.launch {
            while (isActive) {
                delay(15_000L)
                if (player?.isPlaying == true) saveProgress()
            }
        }
    }

    private fun saveProgress() {
        val active = player ?: return
        val key = details ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - previousSaveElapsed < 1500L) return
        previousSaveElapsed = now
        val pos = active.currentPosition.coerceAtLeast(0L)
        if (pos < 3_000L) return // Do not create a watched record by opening a video.
        val duration = active.duration.takeIf { it > 0L } ?: 0L
        lifecycleScope.launch {
            store.updateProgress(WatchProgress(key.media, key.season, key.episode,
                pos, duration, System.currentTimeMillis(), key.episodeTitle))
        }
    }

    override fun onResume() {
        super.onResume()
        screenAwake.activate()
    }

    override fun onPause() {
        saveProgress()
        player?.pause()
        screenAwake.deactivate()
        super.onPause()
    }

    override fun onStop() {
        saveProgress()
        super.onStop()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        screenAwake.deactivate()
        super.onDestroy()
    }

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
        private const val EXTRA_EPISODE_TITLE = "episode_title"

        fun launch(context: Context, stream: PlayableStream, key: StreamKey? = null) {
            val intent = Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_URL, stream.url)
                putExtra(EXTRA_TITLE, stream.title)
                putExtra(EXTRA_MIME, stream.mimeType)
                putExtra(EXTRA_HEADERS, JSONObject(stream.headers).toString())
                if (key != null) {
                    putExtra(EXTRA_TMDB_ID, key.media.id)
                    putExtra(EXTRA_TYPE, key.media.type.api)
                    putExtra(EXTRA_TITLE, key.media.title)
                    putExtra(EXTRA_POSTER, key.media.poster)
                    putExtra(EXTRA_SEASON, key.season ?: -1)
                    putExtra(EXTRA_EPISODE, key.episode ?: -1)
                    putExtra(EXTRA_EPISODE_TITLE, key.episodeTitle)
                }
            }
            context.startActivity(intent)
        }
    }
}

private val PlayerWhite = Color(0xFFF8F8FB)
private val PlayerMuted = Color(0xFFB7B9C9)
private val PlayerAccent = Color(0xFFE94560)

private fun clock(ms: Long): String {
    val secs = (ms.coerceAtLeast(0L) / 1000L)
    val hours = secs / 3600L
    val minutes = (secs % 3600L) / 60L
    val seconds = secs % 60L
    return if (hours > 0L)
        "%d:%02d:%02d".format(hours, minutes, seconds)
    else
        "%02d:%02d".format(minutes, seconds)
}

@Composable
private fun CinemaPlayerUI(
    player: ExoPlayer,
    title: String,
    subtitle: String,
    errorMessage: String?,
    onClose: () -> Unit,
    onRetry: () -> Unit
) {
    // The Compose overlay is a separate view hierarchy from PlayerView.
    // Keep that root awake too, while this composable is visible.
    val composeRoot = LocalView.current
    DisposableEffect(composeRoot) {
        val previous = composeRoot.keepScreenOn
        composeRoot.keepScreenOn = true
        onDispose { composeRoot.keepScreenOn = previous }
    }
    var controlsVisible by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var seeking by remember { mutableStateOf<Float?>(null) }
    var speedMenu by remember { mutableStateOf(false) }
    var qualityMenu by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableFloatStateOf(1f) }
    var quality by remember { mutableStateOf("Auto") }
    var captionsEnabled by remember { mutableStateOf(true) }
    var fitVideo by remember { mutableStateOf(true) }

    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.takeIf { it > 0L } ?: 0L
            isPlaying = player.isPlaying
            buffering = player.playbackState == Player.STATE_BUFFERING
            delay(350L)
        }
    }
    LaunchedEffect(controlsVisible, isPlaying) {
        if (controlsVisible && isPlaying && !speedMenu && !qualityMenu && seeking == null) {
            delay(6000L)
            controlsVisible = false
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = PlayerAccent,
            onPrimary = PlayerWhite,
            surface = Color(0xFF101015),
            onSurface = PlayerWhite
        )
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        this.player = player
                        keepScreenOn = true
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    }
                },
                update = { view ->
                    if (view.player !== player) view.player = player
                    view.resizeMode = if (fitVideo)
                        AspectRatioFrameLayout.RESIZE_MODE_FIT
                    else
                        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                },
                modifier = Modifier.fillMaxSize()
            )

            // Transparent tap surface: the video is the Media3 surface, controls
            // are authored entirely by Cinema TV instead of Media3's stock UI.
            Box(Modifier.fillMaxSize().clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { controlsVisible = !controlsVisible }
            ))

            if (buffering && errorMessage == null) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center).size(50.dp),
                    color = PlayerAccent
                )
            }

            if (controlsVisible) {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = .75f),
                                Color.Transparent,
                                Color.Black.copy(alpha = .76f))
                        )
                    )
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .align(Alignment.TopCenter)
                            .padding(horizontal = 22.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onClose) {
                            Icon(Icons.Default.ArrowBack, "Exit playback", tint = PlayerWhite)
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(title, fontWeight = FontWeight.Bold, fontSize = 19.sp,
                                color = PlayerWhite, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text(subtitle, color = PlayerMuted, fontSize = 12.sp)
                        }
                        Text("CINEMA TV", color = PlayerAccent,
                            fontWeight = FontWeight.Black, fontSize = 14.sp)
                    }

                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(34.dp)
                    ) {
                        IconButton(
                            onClick = {
                                player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L))
                                controlsVisible = true
                            },
                            modifier = Modifier.size(58.dp)
                        ) {
                            Icon(Icons.Default.Replay10, "Back ten seconds",
                                tint = PlayerWhite, modifier = Modifier.size(42.dp))
                        }
                        IconButton(
                            onClick = {
                                if (player.isPlaying) player.pause() else player.play()
                                controlsVisible = true
                            },
                            modifier = Modifier.size(72.dp)
                        ) {
                            Icon(
                                if (isPlaying) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                                if (isPlaying) "Pause" else "Play",
                                tint = PlayerWhite, modifier = Modifier.size(68.dp)
                            )
                        }
                        IconButton(
                            onClick = {
                                val max = duration.takeIf { it > 0L } ?: Long.MAX_VALUE
                                player.seekTo((player.currentPosition + 10_000L).coerceAtMost(max))
                                controlsVisible = true
                            },
                            modifier = Modifier.size(58.dp)
                        ) {
                            Icon(Icons.Default.Forward10, "Forward ten seconds",
                                tint = PlayerWhite, modifier = Modifier.size(42.dp))
                        }
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                            .padding(horizontal = 24.dp, vertical = 12.dp)
                    ) {
                        val fraction = if (duration > 0L)
                            (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(clock(if (seeking != null && duration > 0L)
                                (duration * seeking!!).toLong() else position),
                                color = PlayerWhite, fontSize = 12.sp)
                            Slider(
                                value = seeking ?: fraction,
                                onValueChange = { seeking = it },
                                onValueChangeFinished = {
                                    val fractionChosen = seeking
                                    if (fractionChosen != null && duration > 0L)
                                        player.seekTo((duration * fractionChosen).toLong())
                                    seeking = null
                                    controlsVisible = true
                                },
                                enabled = duration > 0L,
                                modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
                            )
                            Text(clock(duration), color = PlayerWhite, fontSize = 12.sp)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(9.dp)
                        ) {
                            TextButton(onClick = {
                                captionsEnabled = !captionsEnabled
                                player.trackSelectionParameters = player.trackSelectionParameters
                                    .buildUpon()
                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !captionsEnabled)
                                    .build()
                            }) {
                                Icon(Icons.Default.Subtitles, "Toggle subtitles",
                                    tint = if (captionsEnabled) PlayerWhite else PlayerMuted)
                                Spacer(Modifier.width(6.dp))
                                Text(if (captionsEnabled) "CC on" else "CC off",
                                    color = PlayerWhite, fontSize = 12.sp)
                            }
                            TextButton(onClick = { fitVideo = !fitVideo }) {
                                Icon(Icons.Default.AspectRatio, "Toggle fit or crop", tint = PlayerWhite)
                                Spacer(Modifier.width(5.dp))
                                Text(if (fitVideo) "Fit" else "Fill",
                                    fontSize = 12.sp, color = PlayerWhite)
                            }

                            Box {
                                TextButton(onClick = { speedMenu = true }) {
                                    Text("Speed " + playbackSpeed + "×",
                                        color = PlayerWhite, fontSize = 12.sp)
                                }
                                DropdownMenu(
                                    expanded = speedMenu,
                                    onDismissRequest = { speedMenu = false }
                                ) {
                                    listOf(.75f, 1f, 1.25f, 1.5f, 2f).forEach { chosen ->
                                        DropdownMenuItem(
                                            text = { Text(chosen.toString() + "×") },
                                            onClick = {
                                                playbackSpeed = chosen
                                                player.setPlaybackSpeed(chosen)
                                                speedMenu = false
                                                controlsVisible = true
                                            }
                                        )
                                    }
                                }
                            }

                            Box {
                                TextButton(onClick = { qualityMenu = true }) {
                                    Text("Quality " + quality, color = PlayerWhite, fontSize = 12.sp)
                                }
                                DropdownMenu(
                                    expanded = qualityMenu,
                                    onDismissRequest = { qualityMenu = false }
                                ) {
                                    listOf("Auto", "1080p", "720p", "480p").forEach { setting ->
                                        DropdownMenuItem(
                                            text = { Text(setting) },
                                            onClick = {
                                                quality = setting
                                                val maxHeight = when (setting) {
                                                    "1080p" -> 1080
                                                    "720p" -> 720
                                                    "480p" -> 480
                                                    else -> Int.MAX_VALUE
                                                }
                                                val maxWidth = when (setting) {
                                                    "1080p" -> 1920
                                                    "720p" -> 1280
                                                    "480p" -> 854
                                                    else -> Int.MAX_VALUE
                                                }
                                                player.trackSelectionParameters =
                                                    player.trackSelectionParameters.buildUpon()
                                                        .setMaxVideoSize(maxWidth, maxHeight)
                                                        .build()
                                                qualityMenu = false
                                                controlsVisible = true
                                            }
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.weight(1f))
                            Text("Native player · Media3", color = PlayerMuted,
                                fontSize = 11.sp, maxLines = 1)
                        }
                    }
                }
            }

            if (errorMessage != null) {
                Surface(
                    modifier = Modifier.align(Alignment.Center)
                        .padding(30.dp),
                    color = Color(0xFF24242E),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Unable to play this source",
                            color = PlayerWhite, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(errorMessage, color = PlayerMuted, fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = onClose) { Text("Choose another source") }
                            Button(onClick = onRetry) { Text("Retry") }
                        }
                    }
                }
            }
        }
    }
}
