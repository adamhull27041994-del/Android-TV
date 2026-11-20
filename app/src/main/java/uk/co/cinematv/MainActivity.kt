package uk.co.cinematv

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import uk.co.cinematv.model.*
import uk.co.cinematv.player.PlayerActivity
import uk.co.cinematv.provider.VoeLinks
import uk.co.cinematv.player.WebPlayerActivity
import uk.co.cinematv.ui.CinemaViewModel

private val Background = Color(0xFF101014)
private val Surface = Color(0xFF1B1B23)
private val Crimson = Color(0xFFE94560)
private val Secondary = Color(0xFFA7A7B5)
private val White = Color(0xFFFAFAFB)

private val CinemaScheme = darkColorScheme(
    primary = Crimson, onPrimary = White,
    background = Background, onBackground = White,
    surface = Surface, onSurface = White,
    surfaceVariant = Color(0xFF282833), onSurfaceVariant = Secondary
)

class MainActivity : ComponentActivity() {
    private val vm: CinemaViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = CinemaScheme) { CinemaApp(vm) } }
    }
    override fun onResume() { super.onResume(); vm.refreshLibrary() }
}

@Composable
private fun CinemaApp(vm: CinemaViewModel) {
    val context = LocalContext.current
    Scaffold(
        containerColor = Background,
        topBar = {
            Row(
                Modifier.fillMaxWidth().background(Background).statusBarsPadding().padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (vm.selected != null) {
                    IconButton(onClick = vm::closeDetail) { Icon(Icons.Default.ArrowBack, "Back") }
                } else {
                    Icon(Icons.Default.Theaters, null, tint = Crimson, modifier = Modifier.size(29.dp))
                    Spacer(Modifier.width(9.dp))
                    Text("CINEMA", fontWeight = FontWeight.Black, fontSize = 23.sp, letterSpacing = 1.sp)
                    Text(" TV", color = Crimson, fontWeight = FontWeight.Black, fontSize = 23.sp)
                }
                Spacer(Modifier.weight(1f))
                if (vm.selected == null) {
                    IconButton(onClick = { vm.tab = 2 }) { Icon(Icons.Default.Search, "Search") }
                }
            }
        },
        bottomBar = {
            if (vm.selected == null) {
                NavigationBar(containerColor = Surface) {
                    val labels = listOf("Shows", "Movies", "Search", "My List", "Settings")
                    val icons = listOf(Icons.Default.Tv, Icons.Default.Movie, Icons.Default.Search, Icons.Default.Bookmark, Icons.Default.Settings)
                    labels.forEachIndexed { index, label ->
                        NavigationBarItem(
                            selected = vm.tab == index,
                            onClick = { vm.tab = index },
                            icon = { Icon(icons[index], label) },
                            label = { Text(label, fontSize = 10.sp, maxLines = 1) },
                            colors = NavigationBarItemDefaults.colors(selectedIconColor = Crimson, selectedTextColor = Crimson, indicatorColor = Crimson.copy(alpha = 0.12f))
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val selected = vm.selected
            when {
                selected != null -> DetailsScreen(vm, selected)
                vm.tab == 0 -> ShowHome(vm)
                vm.tab == 1 -> MoviesHome(vm)
                vm.tab == 2 -> SearchScreen(vm)
                vm.tab == 3 -> LibraryScreen(vm)
                else -> SettingsScreen()
            }
        }
    }
    SourceDialogs(vm, onPlay = { stream, key ->
        val voeUrl = VoeLinks.embedUrl(stream.url)
        when {
            voeUrl != null -> {
                // VOE's own player keeps hosting controls and notices intact.
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(voeUrl))) }
                    .onFailure { android.widget.Toast.makeText(context, "No browser available for VOE", android.widget.Toast.LENGTH_LONG).show() }
            }
            stream.mimeType == "text/html" -> WebPlayerActivity.launch(context, stream.url)
            else -> PlayerActivity.launch(context, stream, key)
        }
    })
}

@Composable
private fun LoadingIndicator() {
    Box(Modifier.fillMaxWidth().padding(35.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Crimson) }
}

@Composable
private fun EmptyMessage(message: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Text(message, color = Secondary)
        if (action != null && onAction != null) OutlinedButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun ShowHome(vm: CinemaViewModel) {
    if (vm.homeLoading && vm.trending.isEmpty()) { LoadingIndicator(); return }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (vm.homeError != null) item { EmptyMessage(vm.homeError!!, "Retry", vm::refreshHome) }
        val hero = vm.trending.firstOrNull() ?: vm.onAir.firstOrNull()
        if (hero != null) item { HeroTile(hero, vm::open) }
        item { MediaRow("Airing today", vm.airing, vm::open) }
        item { MediaRow("Currently on air", vm.onAir, vm::open) }
        item { MediaRow("Trending this week", vm.trending, vm::open) }
        if (vm.history.isNotEmpty()) item { ContinueRow(vm.history, vm::open) }
    }
}

@Composable
private fun MoviesHome(vm: CinemaViewModel) {
    if (vm.homeLoading && vm.movies.isEmpty()) { LoadingIndicator(); return }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        val hero = vm.nowPlaying.firstOrNull() ?: vm.movies.firstOrNull()
        if (hero != null) item { HeroTile(hero, vm::open) }
        if (vm.homeError != null) item { EmptyMessage(vm.homeError!!, "Retry", vm::refreshHome) }
        item { MediaRow("In cinemas", vm.nowPlaying, vm::open) }
        item { MediaRow("Popular movies", vm.movies, vm::open) }
    }
}

@Composable
private fun HeroTile(media: MediaTitle, onClick: (MediaTitle) -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(285.dp).clickable { onClick(media) }.background(Surface)
    ) {
        AsyncImage(
            model = posterUrl(media.backdrop ?: media.poster, "w1280"),
            contentDescription = media.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Background.copy(alpha = 0.4f), Background), startY = 80f)))
        Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
            Text(if (media.type == MediaType.TV) "FEATURED SERIES" else "FEATURED MOVIE", color = Crimson, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            Text(media.title, fontSize = 28.sp, fontWeight = FontWeight.Black, maxLines = 2, lineHeight = 32.sp)
            Spacer(Modifier.height(6.dp))
            Text(media.overview, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = White.copy(alpha = .85f))
            Spacer(Modifier.height(12.dp))
            Button(onClick = { onClick(media) }) {
                Icon(Icons.Default.Info, null); Spacer(Modifier.width(6.dp)); Text("View details")
            }
        }
    }
}

@Composable
private fun MediaRow(title: String, media: List<MediaTitle>, onClick: (MediaTitle) -> Unit) {
    if (media.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("${media.size} titles", color = Secondary, fontSize = 12.sp)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(media, key = { it.key }) { item -> PosterCard(item, onClick) }
        }
    }
}

@Composable
private fun PosterCard(media: MediaTitle, onClick: (MediaTitle) -> Unit) {
    Column(Modifier.width(128.dp).clickable { onClick(media) }) {
        AsyncImage(
            model = posterUrl(media.poster), contentDescription = media.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().height(184.dp).clip(RoundedCornerShape(10.dp)).background(Surface)
        )
        Spacer(Modifier.height(7.dp))
        Text(media.title, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 17.sp)
        Text("★ ${"%.1f".format(media.rating)}   ${media.date.take(4)}", fontSize = 11.sp, color = Secondary)
    }
}

@Composable
private fun ContinueRow(progress: List<WatchProgress>, onClick: (MediaTitle) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Continue watching", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(progress.take(12), key = { "${it.media.key}:${it.season}:${it.episode}" }) { p ->
                Column(Modifier.width(155.dp).clickable { onClick(p.media) }) {
                    Box {
                        AsyncImage(model = posterUrl(p.media.backdrop ?: p.media.poster, "w500"), contentDescription = p.media.title,
                            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(95.dp).clip(RoundedCornerShape(9.dp)).background(Surface))
                        LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter), color = Crimson, trackColor = Secondary.copy(alpha = .5f))
                    }
                    Text(p.media.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                    Text(if (p.season != null) "S${p.season} E${p.episode}" else "Movie", color = Secondary, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun SearchScreen(vm: CinemaViewModel) {
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = vm.searchText,
            onValueChange = vm::updateSearch,
            label = { Text("Search movies and TV shows") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        )
        if (vm.searching) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Crimson)
        if (vm.searchText.length < 2) EmptyMessage("Enter at least two characters to search the TMDb catalogue.")
        else if (!vm.searching && vm.searchResults.isEmpty()) EmptyMessage("No matching movies or TV shows found.")
        else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(vm.searchResults, key = { it.key }) { media -> MediaListItem(media, vm::open) }
        }
    }
}

@Composable
private fun MediaListItem(media: MediaTitle, onClick: (MediaTitle) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick(media) }, verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(model = posterUrl(media.poster, "w185"), contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(width = 65.dp, height = 94.dp).clip(RoundedCornerShape(8.dp)).background(Surface))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(media.title, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${if (media.type == MediaType.TV) "Series" else "Film"} · ${media.date.take(4)} · ★ ${"%.1f".format(media.rating)}", fontSize = 12.sp, color = Secondary)
            Text(media.overview, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = Secondary)
        }
        Icon(Icons.Default.ChevronRight, null, tint = Secondary)
    }
}

@Composable
private fun LibraryScreen(vm: CinemaViewModel) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("My List", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black) }
        if (vm.favourites.isEmpty()) item { EmptyMessage("No favourites saved yet. Open a movie or series and tap the bookmark icon.") }
        items(vm.favourites, key = { it.key }) { MediaListItem(it, vm::open) }
        if (vm.history.isNotEmpty()) {
            item { Spacer(Modifier.height(12.dp)); Text("Recently watched", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            items(vm.history.take(15), key = { "history:${it.media.key}:${it.season}:${it.episode}" }) { MediaListItem(it.media, vm::open) }
        }
    }
}

@Composable
private fun DetailsScreen(vm: CinemaViewModel, media: MediaTitle) {
    val details = vm.detail
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 25.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item {
            Box(Modifier.height(225.dp).fillMaxWidth().background(Surface)) {
                AsyncImage(model = posterUrl(details?.media?.backdrop ?: media.backdrop ?: media.poster, "w1280"),
                    contentDescription = media.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Background))))
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(details?.media?.title ?: media.title, fontWeight = FontWeight.Black, fontSize = 27.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { vm.toggleFavourite(media) }) {
                        Icon(if (vm.isFavourite(media)) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            "Save to my list", tint = Crimson)
                    }
                }
                Text("★ ${"%.1f".format(media.rating)}   ${media.date.take(4)}   ${if (media.type == MediaType.TV) "TV Series" else "Movie"}", color = Secondary, fontSize = 13.sp)
                if (details?.tagline?.isNotBlank() == true) Text(details.tagline, color = Crimson)
                Text(details?.media?.overview?.takeIf { it.isNotBlank() } ?: media.overview, fontSize = 14.sp, lineHeight = 21.sp)
                if (media.type == MediaType.MOVIE) {
                    Button(onClick = { vm.openSources(StreamKey(details?.media ?: media)) }) {
                        Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(5.dp)); Text("Choose streaming source")
                    }
                }
            }
        }
        if (vm.detailLoading) item { LoadingIndicator() }
        if (vm.detailError != null) item { EmptyMessage(vm.detailError!!, "Try again") { vm.open(media) } }
        if (media.type == MediaType.TV && details != null) {
            item { Text("Episodes", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(details.seasons, key = { it.number }) { s ->
                        FilterChip(selected = vm.season == s.number, onClick = { vm.loadEpisodes(s.number) }, label = { Text(s.title.ifBlank { "Season ${s.number}" }) })
                    }
                }
            }
            if (vm.episodesLoading) item { LoadingIndicator() }
            if (vm.episodes.isEmpty() && !vm.episodesLoading) item { EmptyMessage("No episodes found for this season.") }
            items(vm.episodes, key = { it.number }) { ep ->
                EpisodeRow(ep, onClick = { vm.openSources(StreamKey(details.media, vm.season, ep.number)) })
            }
        }
        item {
            val watch = vm.watchInfo
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HorizontalDivider(color = Color(0xFF30303B))
                Text("Where to watch in the UK", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (watch == null || watch.providers.isEmpty()) Text("No streaming availability listed for the UK.", color = Secondary, fontSize = 13.sp)
                else {
                    watch.providers.forEach { provider ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(model = posterUrl(provider.logo, "w92"), contentDescription = null,
                                modifier = Modifier.size(35.dp).clip(RoundedCornerShape(8.dp)).background(Surface))
                            Spacer(Modifier.width(10.dp))
                            Text(provider.name, fontSize = 14.sp)
                        }
                    }
                    val ctx = LocalContext.current
                    if (watch.link != null) TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(watch.link))) }) {
                        Text("View streaming options"); Icon(Icons.Default.OpenInNew, null, modifier = Modifier.size(16.dp))
                    }
                }
                Text("Watch availability supplied by TMDb / JustWatch; it is not itself a playable stream.", color = Secondary, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun EpisodeRow(ep: EpisodeInfo, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(125.dp).height(78.dp).clip(RoundedCornerShape(8.dp)).background(Surface)) {
            AsyncImage(model = posterUrl(ep.still, "w300"), contentDescription = ep.title,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Icon(Icons.Default.PlayCircle, null, tint = White, modifier = Modifier.align(Alignment.Center).size(29.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("${ep.number}. ${ep.title}", fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
            Text(ep.airDate + if (ep.runtime > 0) "  ·  ${ep.runtime}m" else "", color = Secondary, fontSize = 11.sp)
            Text(ep.overview.ifBlank { "Choose a source to watch" }, color = Secondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SettingsScreen() {
    val ctx = LocalContext.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black) }
        item { Text("Cinema TV · Version 1.0.0", color = Secondary) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Surface)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("Video playback test", fontWeight = FontWeight.Bold)
                    Text("Try the player with an authorised public-domain demonstration video. This is not a source for any movie or TV show in the catalogue.", color = Secondary, fontSize = 13.sp)
                    Button(onClick = {
                        PlayerActivity.launch(ctx, PlayableStream("demo", "Google sample", "Big Buck Bunny (demo)", "https://storage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"))
                    }) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Test video player") }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Surface)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Streaming sources", fontWeight = FontWeight.Bold)
                    Text("Episode source lookup searches WatchOut season files for direct HLS links and offers a separate VidLink web player fallback. You can also add your own HTTPS streams.", color = Secondary, fontSize = 13.sp)
                    Text("External sources are unaffiliated and may be unavailable, unreliable or not licensed for your use. Only watch material you have permission to access. Personal sources remain on your device.", color = Secondary, fontSize = 12.sp)
                }
            }
        }
        item {
            Text("Data & attribution", fontWeight = FontWeight.Bold)
            Text("This product uses the TMDb API but is not endorsed or certified by TMDb. Watch availability sourced from TMDb's JustWatch data. Catalogue artwork and descriptions © respective rights holders.", fontSize = 12.sp, color = Secondary)
        }
    }
}

@Composable
private fun SourceDialogs(vm: CinemaViewModel, onPlay: (PlayableStream, StreamKey) -> Unit) {
    val key = vm.sourceKey ?: return
    val heading = if (key.season != null) "${key.media.title} · S${key.season} E${key.episode}" else key.media.title
    AlertDialog(
        onDismissRequest = { vm.sourceKey = null; vm.addSourceOpen = false },
        title = { Text("Streaming sources") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(heading, fontWeight = FontWeight.Bold)
                if (vm.sourcesLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (vm.sourceResults.isEmpty() && !vm.sourcesLoading) Text("No direct stream found for this title or episode. The web player may still be able to find one.", color = Secondary, fontSize = 13.sp)
                vm.sourceResults.forEach { stream ->
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF272732))) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stream.title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                Text(stream.provider + when {
                                    VoeLinks.embedUrl(stream.url) != null -> " · VOE (opens browser)"
                                    stream.mimeType == "text/html" -> " · Web embed"
                                    else -> " · Direct stream"
                                }, fontSize = 11.sp, color = Secondary)
                            }
                            IconButton(onClick = { onPlay(stream, key) }) { Icon(Icons.Default.PlayArrow, "Play", tint = Crimson) }
                            if (stream.provider == "Personal sources") {
                                IconButton(onClick = { vm.deleteSource(stream.id) }) { Icon(Icons.Default.DeleteOutline, "Remove source", tint = Secondary) }
                            }
                        }
                    }
                }
                if (vm.sourceError != null) Text(vm.sourceError!!, color = Crimson, fontSize = 12.sp)
                TextButton(onClick = vm::loadSources, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Check sources again")
                }
                OutlinedButton(onClick = { vm.addSourceOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Add an authorised stream URL")
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.sourceKey = null }) { Text("Close") } }
    )
    if (vm.addSourceOpen) AddSourceDialog(vm)
}

@Composable
private fun AddSourceDialog(vm: CinemaViewModel) {
    var name by remember { mutableStateOf("My source") }
    var url by remember { mutableStateOf("") }
    var format by remember { mutableStateOf("Auto-detect") }
    AlertDialog(
        onDismissRequest = { vm.addSourceOpen = false },
        title = { Text("Add streaming source") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Source name") }, singleLine = true)
                OutlinedTextField(url, { url = it }, label = { Text("HTTPS video URL") }, minLines = 2, maxLines = 4)
                Text("Format", fontSize = 12.sp, color = Secondary)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Auto-detect", "HLS", "DASH").forEach { value ->
                        FilterChip(selected = format == value, onClick = { format = value }, label = { Text(value, fontSize = 11.sp) })
                    }
                }
                if (vm.sourceError != null) Text(vm.sourceError!!, color = Crimson, fontSize = 12.sp)
                Text("Add an authorised direct video link or a VOE share/embed link. VOE opens in your browser.", color = Secondary, fontSize = 11.sp)
            }
        },
        confirmButton = {
            Button(onClick = {
                val mime = when (format) {
                    "HLS" -> "application/x-mpegURL"
                    "DASH" -> "application/dash+xml"
                    else -> null
                }
                vm.addSource(name, url, mime)
            }, enabled = url.trim().startsWith("https://", true)) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = { vm.addSourceOpen = false }) { Text("Cancel") } }
    )
}
