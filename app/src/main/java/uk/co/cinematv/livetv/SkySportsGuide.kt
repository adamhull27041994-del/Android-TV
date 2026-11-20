package uk.co.cinematv.livetv

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Official Sky Sports channel names from NOW's UK sports membership guide
 * (November 2026). These are directory entries, not playable video URLs.
 *
 * https://www.nowtv.com/gb/help/article/sports-memberships
 */
data class SkySportsStation(val name: String, val subject: String)

object SkySportsStations {
    val all = listOf(
        SkySportsStation("Sky Sports Main Event", "Major live events"),
        SkySportsStation("Sky Sports Premier League", "Premier League football"),
        SkySportsStation("Sky Sports Football", "Football"),
        SkySportsStation("Sky Sports Cricket", "Cricket"),
        SkySportsStation("Sky Sports Golf", "Golf"),
        SkySportsStation("Sky Sports F1", "Formula 1"),
        SkySportsStation("Sky Sports Tennis", "Tennis"),
        SkySportsStation("Sky Sports Action", "Multi-sport"),
        SkySportsStation("Sky Sports+", "Extra live events"),
        SkySportsStation("Sky Sports Racing", "Horse racing"),
        SkySportsStation("Sky Sports Mix", "Selected sports"),
        SkySportsStation("Sky Sports News", "Sports news")
    )
    const val NOW_URL = "https://www.nowtv.com/membership/watch-sky-sports"
    const val SKY_GO_URL = "https://www.sky.com/watch/sky-go/android"
}

/**
 * The directory always shows all 12 Sky Sports channels rather than pretending
 * that a few unofficial stream listings represent the full Sky lineup.
 * Official playback opens the provider, which handles account/DRM requirements.
 */
@Composable
fun SkySportsGuide(onFindChannel: (String) -> Unit) {
    val context = LocalContext.current
    fun openOfficial(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1B23))
    ) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.SportsSoccer,
                    contentDescription = null,
                    tint = Color(0xFFE94560),
                    modifier = Modifier.size(26.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Sky Sports · 12 channels", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("Official channel directory", color = Color(0xFFA7A7B5), fontSize = 12.sp)
                }
            }
            Text(
                "Select a channel to search public playlist listings, or use NOW / Sky Go " +
                    "to watch with a valid subscription. A listing alone doesn't prove live playback.",
                color = Color(0xFFA7A7B5), fontSize = 12.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { openOfficial(SkySportsStations.NOW_URL) }) {
                    Text("Open NOW", fontSize = 12.sp)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.OpenInNew, null, Modifier.size(16.dp))
                }
                OutlinedButton(onClick = { openOfficial(SkySportsStations.SKY_GO_URL) }) {
                    Text("Sky Go", fontSize = 12.sp)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.OpenInNew, null, Modifier.size(16.dp))
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(SkySportsStations.all) { station ->
                    Column(
                        Modifier.width(170.dp)
                            .background(Color(0xFF292933), RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            station.name,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            minLines = 2
                        )
                        Text(station.subject, color = Color(0xFFA7A7B5), fontSize = 11.sp)
                        TextButton(onClick = { onFindChannel(station.name) }) {
                            Text("Find listings", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
