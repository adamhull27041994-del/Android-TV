package uk.co.cinematv.livetv

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import uk.co.cinematv.model.PlayableStream
import uk.co.cinematv.player.PlayerActivity

private val Bg = Color(0xFF101014)
private val Panel = Color(0xFF1B1B23)
private val Accent = Color(0xFFE94560)
private val Muted = Color(0xFFA7A7B5)

@Composable
fun LiveTvScreen() {
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences("cinema_live_tv", Context.MODE_PRIVATE)
    }
    val scope = rememberCoroutineScope()
    var feedUrl by remember { mutableStateOf(preferences.getString("feed_url", "").orEmpty()) }
    var channels by remember { mutableStateOf<List<LiveChannel>>(emptyList()) }
    var category by remember { mutableStateOf("All") }
    var search by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun loadFeed(address: String) {
        if (loading) return
        loading = true
        error = null
        scope.launch {
            runCatching { LiveTvFeed.load(address) }
                .onSuccess { result ->
                    channels = result
                    category = "All"
                    preferences.edit().putString("feed_url", address).apply()
                }
                .onFailure { throwable ->
                    error = throwable.message ?: "Could not read the channel feed"
                }
            loading = false
        }
    }
    LaunchedEffect(Unit) {
        val address = preferences.getString("feed_url", "").orEmpty()
        if (address.isNotBlank()) loadFeed(address)
    }

    val groups = remember(channels) {
        listOf("All") + channels.map { it.group }.distinct().sorted()
    }
    val filtered = remember(channels, category, search) {
        channels.filter { channel ->
            (category == "All" || channel.group == category) &&
                (search.isBlank() || channel.name.contains(search.trim(), ignoreCase = true))
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Bg),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 25.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LiveTv, "Live TV", tint = Accent, modifier = Modifier.size(31.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Live TV", fontSize = 25.sp, fontWeight = FontWeight.Black)
                    Text("Your authorised channels in the Cinema TV player",
                        fontSize = 12.sp, color = Muted)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    Text("Connect a channel feed", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(
                        "Enter an HTTPS M3U playlist or channel JSON feed you are permitted to access. " +
                            "Nothing from AK47 Sports is preconfigured.",
                        fontSize = 12.sp, color = Muted
                    )
                    OutlinedTextField(
                        value = feedUrl,
                        onValueChange = { feedUrl = it },
                        label = { Text("HTTPS playlist or JSON URL") },
                        placeholder = { Text("https://your-provider.example/channels.m3u") },
                        singleLine = false,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { loadFeed(feedUrl.trim()) },
                            enabled = !loading && feedUrl.trim().startsWith("https://", ignoreCase = true)
                        ) {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (channels.isEmpty()) "Load channels" else "Update feed")
                        }
                        if (loading) CircularProgressIndicator(modifier = Modifier.size(25.dp),
                            color = Accent, strokeWidth = 2.dp)
                    }
                    Text(
                        "Feed URL stays on this device. Links requiring logins or DRM may not play. " +
                            "Never paste account credentials into a shared playlist URL.",
                        fontSize = 11.sp, color = Muted
                    )
                }
            }
        }
        if (error != null) {
            item { Text(error.orEmpty(), color = Accent, fontSize = 13.sp) }
        }

        if (channels.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${channels.size} channels", fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    Text("Loaded", color = Muted, fontSize = 12.sp)
                }
            }
            item {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text("Find a channel") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(groups) { group ->
                        FilterChip(
                            selected = category == group,
                            onClick = { category = group },
                            label = { Text(group, maxLines = 1) }
                        )
                    }
                }
            }
            items(filtered, key = { it.name + "|" + it.url }) { item ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Panel),
                    modifier = Modifier.fillMaxWidth().clickable {
                        val parsed = Uri.parse(item.url)
                        val filename = parsed.path.orEmpty().lowercase()
                        val mime = when {
                            filename.endsWith(".m3u8") || filename.endsWith(".m3u") ->
                                "application/x-mpegURL"
                            filename.endsWith(".mpd") -> "application/dash+xml"
                            filename.endsWith(".ts") -> "video/mp2t"
                            filename.endsWith(".mp4") -> "video/mp4"
                            else -> null
                        }
                        PlayerActivity.launch(context, PlayableStream(
                            id = "live:" + item.name,
                            provider = "Live TV",
                            title = item.name,
                            url = item.url,
                            mimeType = mime,
                            headers = item.headers
                        ))
                    }
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (item.logo != null) {
                            AsyncImage(
                                model = item.logo,
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.size(44.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFF282833))
                            )
                        } else {
                            Icon(Icons.Default.LiveTv, null, tint = Muted,
                                modifier = Modifier.size(44.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(item.name, fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(item.group, color = Muted, fontSize = 11.sp)
                        }
                        Icon(Icons.Default.PlayArrow, "Watch", tint = Accent)
                    }
                }
            }
            if (filtered.isEmpty()) {
                item { Text("No channels match the selected filter", color = Muted) }
            }
        } else if (!loading && error == null) {
            item {
                Text("Add a playlist URL to browse live channels.",
                    color = Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 24.dp))
            }
        }
    }
}
