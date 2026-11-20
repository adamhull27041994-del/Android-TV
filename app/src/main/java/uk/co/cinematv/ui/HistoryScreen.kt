package uk.co.cinematv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import uk.co.cinematv.model.MediaType
import uk.co.cinematv.model.StreamKey
import uk.co.cinematv.model.WatchProgress
import uk.co.cinematv.model.posterUrl
import java.text.DateFormat
import java.util.Date

private val Bg = Color(0xFF101014)
private val Panel = Color(0xFF1B1B23)
private val Accent = Color(0xFFE94560)
private val Muted = Color(0xFFA7A7B5)
private val Foreground = Color(0xFFFAFAFB)

/** Native playback history persisted by LibraryStore, independent of favourites. */
@Composable
fun HistoryScreen(vm: CinemaViewModel) {
    var category by remember { mutableStateOf("All") }
    val recorded = vm.history.filter { it.positionMs >= 3_000L }
    val visible = when (category) {
        "Series" -> recorded.filter { it.media.type == MediaType.TV }
        "Movies" -> recorded.filter { it.media.type == MediaType.MOVIE }
        "Finished" -> recorded.filter { it.durationMs > 0L && it.fraction >= 0.95f }
        else -> recorded
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Bg),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.History, "History", tint = Accent)
                        Spacer(Modifier.width(8.dp))
                        Text("Watch history", style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Black)
                    }
                    Text(
                        recorded.size.toString() + " watched movies or episodes · stored on this device",
                        color = Muted, fontSize = 12.sp
                    )
                }
                IconButton(onClick = { vm.refreshLibrary() }) {
                    Icon(Icons.Default.Refresh, "Refresh history", tint = Accent)
                }
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf("All", "Series", "Movies", "Finished")) { option ->
                    FilterChip(
                        selected = category == option,
                        onClick = { category = option },
                        label = { Text(option) }
                    )
                }
            }
        }

        if (visible.isEmpty()) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Default.History, null, tint = Muted,
                        modifier = Modifier.size(44.dp))
                    Text(
                        if (recorded.isEmpty()) "No watch history yet" else "Nothing in this filter",
                        color = Foreground, fontSize = 18.sp, fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (recorded.isEmpty())
                            "Play a film or episode using the Cinema TV video player. It will appear here automatically."
                        else
                            "Try another history category.",
                        color = Muted, fontSize = 13.sp
                    )
                }
            }
        }

        items(visible, key = { it.media.key + ":" + it.season + ":" + it.episode }) { watched ->
            HistoryCard(
                watched = watched,
                onResume = {
                    vm.openSources(
                        StreamKey(watched.media, watched.season, watched.episode, watched.episodeTitle)
                    )
                },
                onDetails = { vm.open(watched.media) }
            )
        }
    }
}

@Composable
private fun HistoryCard(
    watched: WatchProgress,
    onResume: () -> Unit,
    onDetails: () -> Unit
) {
    val finished = watched.durationMs > 0L && watched.fraction >= 0.95f
    val episodeLine = if (watched.media.type == MediaType.TV) {
        val s = (watched.season ?: 0).toString().padStart(2, '0')
        val e = (watched.episode ?: 0).toString().padStart(2, '0')
        "Season " + (watched.season ?: "?") + " · Episode " + (watched.episode ?: "?") +
            "  (S" + s + "E" + e + ")"
    } else "Film"
    val lastViewed = remember(watched.updatedAt) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(watched.updatedAt))
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AsyncImage(
                model = posterUrl(watched.media.poster, "w185"),
                contentDescription = watched.media.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.width(68.dp).height(103.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color(0xFF2B2B33))
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(watched.media.title, maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = Foreground, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(episodeLine, color = Accent, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                watched.episodeTitle?.takeIf { it.isNotBlank() }?.let { name ->
                    Text(name, color = Foreground, fontSize = 12.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    (if (finished) "Watched" else "In progress") + " · " + lastViewed,
                    color = Muted, fontSize = 11.sp, maxLines = 2
                )
                if (watched.durationMs > 0L) {
                    LinearProgressIndicator(
                        progress = { watched.fraction },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = Accent, trackColor = Color(0xFF34343F)
                    )
                    Text(
                        (watched.fraction * 100f).toInt().toString() + "% · " +
                            (watched.positionMs / 60000L) + " of " +
                            (watched.durationMs / 60000L) + " min",
                        color = Muted, fontSize = 10.sp
                    )
                }
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(onClick = onResume) {
                    Icon(
                        if (finished) Icons.Default.Replay else Icons.Default.PlayArrow,
                        if (finished) "Watch again" else "Resume",
                        tint = Accent
                    )
                }
                TextButton(onClick = onDetails, contentPadding = PaddingValues(0.dp)) {
                    Icon(Icons.Default.Info, null, tint = Muted, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("Details", color = Muted, fontSize = 11.sp)
                }
            }
        }
    }
}
