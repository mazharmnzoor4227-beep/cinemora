package com.cinemora.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import com.cinemora.app.CinemoraApplication
import com.cinemora.app.core.EpisodeItem
import com.cinemora.app.data.Movie
import com.cinemora.app.data.toMovie
import com.cinemora.app.download.DownloadHelper
import com.cinemora.app.ui.components.PosterCard
import com.cinemora.app.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun MyListScreen(movies: List<Movie>, onMovieClick: (Movie) -> Unit) {
    Column(Modifier.fillMaxSize().background(Night).padding(top = 22.dp)) {
        Text("My List", color = TextPrimary, fontSize = 27.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp))
        Text("Your saved movies and series", color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 18.dp))
        if (movies.isEmpty()) {
            EmptyState(Icons.Default.BookmarkBorder, "Your list is waiting", "Save movies and series to find them here later.")
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2), modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                items(movies, key = { it.id }) { movie -> PosterCard(movie, { onMovieClick(movie) }, Modifier.fillMaxWidth()) }
            }
        }
    }
}

@UnstableApi
@Composable
fun DownloadsScreen(onPlayOffline: (Movie, EpisodeItem) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloads by remember { mutableStateOf<List<DownloadHelper.DownloadItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var resolving by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        downloads = DownloadHelper.list(context)
        loading = false
    }

    Column(Modifier.fillMaxSize().background(Night).padding(top = 22.dp)) {
        Text("Downloads", color = TextPrimary, fontSize = 27.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp))
        Text("Watch your saved titles offline", color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 18.dp))
        when {
            loading -> {
                Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(color = PurpleLight)
                }
            }
            downloads.isEmpty() -> {
                EmptyState(Icons.Default.DownloadForOffline, "No downloads yet", "Download any episode from its details page and it will appear here.")
            }
            else -> {
                Column(
                    Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    downloads.forEach { item ->
                        val stateLabel = when (item.state) {
                            Download.STATE_COMPLETED -> "Downloaded"
                            Download.STATE_DOWNLOADING -> "Downloading ${(item.progress * 100).toInt()}%"
                            Download.STATE_QUEUED -> "Queued"
                            Download.STATE_FAILED -> "Failed"
                            else -> "Paused"
                        }
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(SurfaceNight)
                                .clickable {
                                    if (item.state != Download.STATE_COMPLETED) return@clickable
                                    if (resolving != null) return@clickable
                                    resolving = item.id
                                    scope.launch {
                                        val details = CinemoraApplication.repository.details(item.providerId, item.contentId)
                                        val episode = details?.seasons?.flatMap { it.episodes }
                                            ?.firstOrNull { it.number == item.episodeNumber }
                                        resolving = null
                                        if (details != null && episode != null) {
                                            onPlayOffline(details.toMovie(), episode)
                                        }
                                    }
                                }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(item.title, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                                Text(stateLabel, color = TextMuted, fontSize = 11.sp)
                                if (item.state == Download.STATE_DOWNLOADING || item.state == Download.STATE_QUEUED) {
                                    LinearProgressIndicator(
                                        progress = { item.progress },
                                        modifier = Modifier.fillMaxWidth().height(4.dp),
                                        color = PurpleLight,
                                        trackColor = DividerColor,
                                    )
                                }
                            }
                            if (resolving == item.id) {
                                CircularProgressIndicator(color = PurpleLight, modifier = Modifier.size(22.dp))
                            } else if (item.state == Download.STATE_COMPLETED) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Play offline", tint = PurpleLight, modifier = Modifier.size(26.dp))
                            }
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Remove download",
                                tint = TextMuted,
                                modifier = Modifier.size(22.dp).clickable {
                                    DownloadHelper.remove(context, item.id)
                                    downloads = downloads.filter { it.id != item.id }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ProfileScreen() {
    Column(Modifier.fillMaxSize().background(Night).verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Profile", color = TextPrimary, fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Box(
                Modifier.padding(end = 13.dp).background(SurfaceElevated, androidx.compose.foundation.shape.CircleShape).padding(18.dp),
            ) { Text("C", color = PurpleLight, fontSize = 25.sp, fontWeight = FontWeight.Black) }
            Column {
                Text("Guest Viewer", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("Sign-in and account features can be connected later", color = TextMuted, fontSize = 10.sp)
            }
        }
        listOf("App preferences", "Playback settings", "Language preferences", "Notifications", "Help & support", "About CINEMORA").forEach { item ->
            Row(Modifier.fillMaxWidth().background(SurfaceNight, androidx.compose.foundation.shape.RoundedCornerShape(13.dp)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(item, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text("›", color = TextMuted, fontSize = 22.sp)
            }
        }
        Text("CINEMORA v1.0.0", color = TextMuted, fontSize = 10.sp)
    }
}

@Composable
private fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, message: String) {
    Column(Modifier.fillMaxSize().padding(horizontal = 34.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, contentDescription = null, tint = PurpleLight, modifier = Modifier.padding(bottom = 18.dp).size(52.dp))
        Text(title, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(message, color = TextMuted, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 7.dp))
    }
}
