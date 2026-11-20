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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import uk.co.cinematv.model.PlayableStream
import uk.co.cinematv.player.PlayerActivity

private val Bg = Color(0xFF101014)
private val Panel = Color(0xFF1B1B23)
private val Accent = Color(0xFFE94560)
private val Muted = Color(0xFFA7A7B5)
private val StreamGood = Color(0xFF78D6A1)
private val StreamWarning = Color(0xFFEEC16A)

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
    var autoLoading by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var manualExpanded by remember { mutableStateOf(false) }
    var sourceLabel by remember { mutableStateOf("") }
    var scraperQuery by remember { mutableStateOf("Sky Sports") }
    var scraperLoading by remember { mutableStateOf(false) }
    var scraperResultMessage by remember { mutableStateOf<String?>(null) }
    val streamStatus = remember {
        mutableStateMapOf<String, UkChannelDiscovery.ProbeResult>()
    }

    fun loadFeed(address: String) {
        if (loading) return
        loading = true
        error = null
        scope.launch {
            runCatching { LiveTvFeed.load(address) }
                .onSuccess { result ->
                    channels = result
                    category = "All"
                    search = ""
                    sourceLabel = "Your playlist"
                    streamStatus.clear()
                    preferences.edit().putString("feed_url", address).apply()
                }
                .onFailure { throwable ->
                    error = throwable.message ?: "Could not read the channel feed"
                }
            loading = false
        }
    }
    fun checkChannels(itemsToCheck: List<LiveChannel>) {
        if (checking || itemsToCheck.isEmpty()) return
        val chosen = itemsToCheck.distinctBy { it.url }.take(10)
        checking = true
        chosen.forEach { channel ->
            streamStatus[channel.url] = UkChannelDiscovery.ProbeResult(
                UkChannelDiscovery.ProbeStatus.CHECKING, "Checking"
            )
        }
        scope.launch {
            try {
                val limiter = Semaphore(3)
                coroutineScope {
                    chosen.map { channel ->
                        async {
                            val result = limiter.withPermit {
                                UkChannelDiscovery.check(channel)
                            }
                            streamStatus[channel.url] = result
                        }
                    }.awaitAll()
                }
            } finally {
                checking = false
            }
        }
    }

    fun scanUK(force: Boolean = false) {
        if (autoLoading || loading) return
        autoLoading = true
        error = null
        scope.launch {
            try {
                val result = UkChannelDiscovery.discover(force)
                if (result.channels.isEmpty()) {
                    error = result.warnings.joinToString("; ")
                        .ifBlank { "No UK listings could be downloaded." }
                } else {
                    channels = result.channels
                    category = "All"
                    search = ""
                    scraperResultMessage = null
                    sourceLabel = "Public UK lists · " + result.sourcesLoaded + "/" +
                        result.sourcesAttempted + " sources"
                    streamStatus.clear()
                    if (result.warnings.isNotEmpty()) {
                        error = result.warnings.joinToString("; ")
                    }
                    val bbcOne = result.channels.filter { UkChannelDiscovery.isBbcOne(it) }
                        .take(4)
                    val popular = result.channels.filter {
                        UkChannelDiscovery.priority(it.name) in 1..8
                    }.distinctBy { UkChannelDiscovery.priority(it.name) }.take(5)
                    checkChannels(bbcOne + popular)
                }
            } catch (e: Exception) {
                error = e.message ?: "Unable to scan public UK channels."
            } finally {
                autoLoading = false
            }
        }
    }


    fun scrapePlaylists(term: String) {
        if (scraperLoading || autoLoading || loading) return
        if (term.trim().length < 2) {
            error = "Enter at least two characters to search."
            return
        }
        scraperLoading = true
        error = null
        scraperResultMessage = null
        scope.launch {
            try {
                val result = UkChannelDiscovery.searchPublic(term.trim())
                channels = result.channels
                streamStatus.clear()
                category = "All"
                search = ""
                sourceLabel = "Search: " + term.trim() + " · " +
                    result.sourcesLoaded + "/" + result.sourcesAttempted + " lists checked"
                scraperResultMessage = if (result.channels.isEmpty()) {
                    "No matching HTTPS channel listings found in the public sources."
                } else {
                    result.channels.size.toString() + " matching channel listings found. " +
                        "These are not necessarily playable or licensed."
                }
                if (result.warnings.isNotEmpty()) {
                    error = result.warnings.take(3).joinToString("; ")
                }
                if (result.channels.isNotEmpty()) {
                    checkChannels(result.channels.take(10))
                }
            } catch (e: Exception) {
                error = e.message ?: "Channel search failed"
            } finally {
                scraperLoading = false
            }
        }
    }

    // Automatically show public UK channel listings without asking for a URL.
    LaunchedEffect(Unit) { scanUK() }

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
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Automatic UK channel discovery",
                        fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        "Searches IPTV-org UK and Free-TV UK for channels including BBC One. " +
                            "Availability tests check responses only; they do not prove playback.",
                        color = Muted, fontSize = 12.sp
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { scanUK(force = true) },
                            enabled = !autoLoading && !loading
                        ) {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Scan UK")
                        }
                        OutlinedButton(
                            enabled = channels.isNotEmpty(),
                            onClick = {
                                category = "All"
                                search = "BBC One"
                                checkChannels(
                                    channels.filter { UkChannelDiscovery.isBbcOne(it) }.take(6)
                                )
                            }
                        ) { Text("BBC One") }
                    }
                    if (autoLoading) {
                        LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
                        Text("Downloading public channel lists...",
                            color = Muted, fontSize = 12.sp)
                    } else if (sourceLabel.isNotBlank()) {
                        Text(sourceLabel, color = Muted, fontSize = 12.sp)
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Search more channels", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        "Searches " + UkChannelDiscovery.searchFeeds.size +
                            " public IPTV playlists for a channel name. Includes " +
                            "international and sports listings; links may not be authorised or available.",
                        color = Muted, fontSize = 12.sp
                    )
                    OutlinedTextField(
                        value = scraperQuery,
                        onValueChange = { scraperQuery = it },
                        label = { Text("Channel name (e.g. Sky Sports)") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 1,
                        singleLine = true
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            enabled = !scraperLoading && !autoLoading && !loading &&
                                scraperQuery.trim().length >= 2,
                            onClick = { scrapePlaylists(scraperQuery) }
                        ) { Text("Find channels") }
                        OutlinedButton(
                            enabled = !scraperLoading && !autoLoading && !loading,
                            onClick = {
                                scraperQuery = "Sky Sports"
                                scrapePlaylists("Sky Sports")
                            }
                        ) { Text("Sky Sports") }
                    }
                    if (scraperLoading) {
                        LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
                        Text("Searching public playlists...", color = Muted, fontSize = 12.sp)
                    }
                    scraperResultMessage?.let { Text(it, color = Muted, fontSize = 12.sp) }
                }
            }
        }
        item {
            TextButton(onClick = { manualExpanded = !manualExpanded }) {
                Text(if (manualExpanded) "Hide custom playlist" else "Use my own IPTV playlist")
            }
        }
        if (manualExpanded) {
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
        }
        if (error != null) {
            item { Text(error.orEmpty(), color = Accent, fontSize = 13.sp) }
        }

        if (channels.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${channels.size} channels", fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    Text(if (checking) "Checking streams..." else "Loaded",
                        color = Muted, fontSize = 12.sp)
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
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { checkChannels(filtered.take(10)) },
                        enabled = !checking && filtered.isNotEmpty()
                    ) {
                        Text(if (checking) "Checking..." else "Check first 10 streams")
                    }
                    Text("HTTP / manifest check only",
                        color = Muted, fontSize = 11.sp)
                }
            }
            items(filtered, key = { it.name + "|" + it.url }) { item ->
                val probe = streamStatus[item.url]
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
                            Text(item.group + " · " + item.source,
                                color = Muted, fontSize = 11.sp, maxLines = 1)
                            val statusLabel = when (probe?.status) {
                                UkChannelDiscovery.ProbeStatus.CHECKING -> "Checking..."
                                UkChannelDiscovery.ProbeStatus.RESPONDING -> probe.description
                                UkChannelDiscovery.ProbeStatus.RESTRICTED -> probe.description
                                UkChannelDiscovery.ProbeStatus.UNAVAILABLE -> probe.description
                                UkChannelDiscovery.ProbeStatus.UNKNOWN -> probe.description
                                else -> "Not tested"
                            }
                            val statusColor = when (probe?.status) {
                                UkChannelDiscovery.ProbeStatus.RESPONDING -> StreamGood
                                UkChannelDiscovery.ProbeStatus.RESTRICTED,
                                UkChannelDiscovery.ProbeStatus.UNAVAILABLE -> Accent
                                UkChannelDiscovery.ProbeStatus.UNKNOWN,
                                UkChannelDiscovery.ProbeStatus.CHECKING -> StreamWarning
                                else -> Muted
                            }
                            Text(statusLabel, color = statusColor,
                                fontSize = 11.sp, maxLines = 2)
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
                Text(
                    if (autoLoading) "Searching public UK lists..."
                    else if (scraperLoading) "Searching channel directories..."
                    else if (scraperResultMessage != null) scraperResultMessage.orEmpty()
                    else "No channels loaded. Select Scan UK to retry, or add your own playlist.",
                    color = Muted, fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            }
        }
    }
}
