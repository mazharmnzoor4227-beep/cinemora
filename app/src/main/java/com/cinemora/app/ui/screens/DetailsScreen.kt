package com.cinemora.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import coil.compose.AsyncImage
import com.cinemora.app.core.EpisodeItem
import com.cinemora.app.data.DetailsViewModel
import com.cinemora.app.data.Movie
import com.cinemora.app.data.loadEpisodeStreams
import com.cinemora.app.download.DownloadHelper
import com.cinemora.app.ui.components.PosterArtwork
import com.cinemora.app.ui.components.SectionHeading
import com.cinemora.app.ui.components.SmallTag
import com.cinemora.app.ui.theme.*
import kotlinx.coroutines.launch

@UnstableApi
@Composable
fun DetailsScreen(
    movie: Movie,
    isSaved: Boolean,
    onBack: () -> Unit,
    onPlay: (EpisodeItem) -> Unit,
    onSave: () -> Unit,
    detailsViewModel: DetailsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val details by detailsViewModel.details.collectAsState()
    val loading by detailsViewModel.loading.collectAsState()
    var selectedSeason by remember { mutableStateOf(0) }
    var downloadMessage by remember { mutableStateOf<String?>(null) }
    val downloadStates = remember { mutableStateMapOf<String, Int>() }

    LaunchedEffect(movie.id) {
        detailsViewModel.load(movie.providerId, movie.contentId)
        selectedSeason = 0
    }
    LaunchedEffect(episodes) {
        episodes.forEach { ep ->
            val s = DownloadHelper.stateOf(context, DownloadHelper.downloadId(movie, ep))
            if (s != null) downloadStates[ep.id] = s
        }
    }

    val seasons = details?.seasons ?: emptyList()
    val currentSeason = seasons.getOrNull(selectedSeason) ?: seasons.firstOrNull()
    val episodes = currentSeason?.episodes ?: emptyList()
    val firstEpisode = seasons.firstOrNull()?.episodes?.firstOrNull()

    Column(Modifier.fillMaxSize().background(Night).verticalScroll(rememberScrollState())) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(SurfaceElevated).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
            }
            Spacer(Modifier.weight(1f))
            Text("TITLE DETAILS", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(SurfaceElevated).clickable(onClick = onSave), contentAlignment = Alignment.Center) {
                Icon(if (isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, contentDescription = "Save", tint = PurpleLight)
            }
        }

        Box(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp).height(255.dp).clip(RoundedCornerShape(22.dp))
                .background(Brush.linearGradient(listOf(movie.accentStart, movie.accentEnd, Night))),
        ) {
            val artUrl = movie.bannerUrl ?: movie.posterUrl
            if (artUrl != null) {
                AsyncImage(
                    model = artUrl,
                    contentDescription = movie.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f)))))
            } else {
                Box(Modifier.align(Alignment.TopEnd).padding(24.dp).size(150.dp).clip(RoundedCornerShape(100.dp)).background(Color.White.copy(alpha = 0.07f)))
            }
            Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                SmallTag(movie.label)
                Spacer(Modifier.height(8.dp))
                Text(movie.title.uppercase(), color = Color.White, fontSize = 30.sp, lineHeight = 32.sp, fontWeight = FontWeight.Black)
                Text(movie.genre.uppercase(), color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp, letterSpacing = 1.sp)
            }
        }

        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (movie.rating.isNotBlank()) SmallTag("★ ${movie.rating}", Color(0xFFFFC857))
                if (movie.year.isNotBlank()) Text(movie.year, color = TextMuted, fontSize = 12.sp)
                if (movie.duration.isNotBlank()) {
                    Text("·", color = TextMuted)
                    Text(movie.duration, color = TextMuted, fontSize = 12.sp)
                }
            }
            if (movie.description.isNotBlank()) {
                Text(movie.description, color = Color(0xFFD2D9E5), fontSize = 13.sp, lineHeight = 20.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { firstEpisode?.let { onPlay(it) } },
                    enabled = firstEpisode != null,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(13.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Purple),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(5.dp))
                    Text(if (movie.kind == "Movie") "Watch Movie" else "Watch Now", fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = onSave,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(13.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceElevated, contentColor = TextPrimary),
                ) {
                    Icon(if (isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, contentDescription = null)
                    Spacer(Modifier.width(5.dp))
                    Text(if (isSaved) "Saved" else "My List")
                }
            }

            val audioChips = buildList {
                when (details?.lang) {
                    com.cinemora.app.core.Lang.HINDI -> add("Hindi Dub")
                    com.cinemora.app.core.Lang.ENGLISH -> add("English")
                    com.cinemora.app.core.Lang.JAPANESE -> add("Japanese")
                    null -> {}
                }
                if (details?.audioKinds?.contains(com.cinemora.app.core.AudioKind.DUB) == true) add("Dubbed")
                if (details?.audioKinds?.contains(com.cinemora.app.core.AudioKind.SUB) == true) add("Subbed")
            }
            if (audioChips.isNotEmpty()) {
                Text("Audio languages", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    audioChips.forEach { language ->
                        Box(Modifier.clip(RoundedCornerShape(50)).background(SurfaceElevated).padding(horizontal = 11.dp, vertical = 7.dp)) {
                            Text(language, color = TextMuted, fontSize = 10.sp)
                        }
                    }
                }
            }

            if (loading) {
                Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = PurpleLight)
                }
            } else if (details == null) {
                Text("Couldn't load details. Check your connection and try again.", color = TextMuted, fontSize = 12.sp)
                Button(
                    onClick = { detailsViewModel.load(movie.providerId, movie.contentId) },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceElevated, contentColor = TextPrimary),
                ) { Text("Retry") }
            } else if (episodes.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                SectionHeading(if (movie.kind == "Movie") "Movie" else "Episodes", if (seasons.size > 1) "Season ${currentSeason?.season ?: 1}" else null)
                if (seasons.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        seasons.forEachIndexed { index, season ->
                            Box(
                                Modifier.clip(RoundedCornerShape(50))
                                    .background(if (index == selectedSeason) Purple else SurfaceElevated)
                                    .clickable { selectedSeason = index }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    season.name,
                                    color = if (index == selectedSeason) Color.White else TextMuted,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                if (downloadMessage != null) {
                    Text(downloadMessage!!, color = PurpleLight, fontSize = 11.sp)
                }
                episodes.forEach { episode ->
                    val state = downloadStates[episode.id]
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(SurfaceNight).clickable { onPlay(episode) }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PosterArtwork(movie, Modifier.width(88.dp).height(62.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                if (movie.kind == "Movie") movie.title else "Episode ${episode.number}",
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                            )
                            if (!episode.title.isNullOrBlank() && episode.title != "Episode ${episode.number}") {
                                Text(episode.title!!, color = TextMuted, fontSize = 10.sp)
                            }
                            Text(
                                when (state) {
                                    Download.STATE_COMPLETED -> "Downloaded"
                                    Download.STATE_DOWNLOADING, Download.STATE_QUEUED, Download.STATE_RESTARTING -> "Downloading…"
                                    Download.STATE_FAILED -> "Download failed — tap to retry"
                                    else -> if (movie.kind == "Movie") "Tap to play" else "Tap to play"
                                },
                                color = TextMuted,
                                fontSize = 9.sp,
                            )
                        }
                        Box(
                            Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(SurfaceElevated)
                                .clickable {
                                    scope.launch {
                                        if (state == Download.STATE_COMPLETED) {
                                            downloadMessage = "Already downloaded — find it in Downloads."
                                            return@launch
                                        }
                                        downloadMessage = "Finding best stream for download…"
                                        val streams = loadEpisodeStreams(movie.providerId, episode.id)
                                        val best = streams.sources.maxByOrNull { it.quality }
                                        if (best == null) {
                                            downloadMessage = "No stream available to download."
                                            return@launch
                                        }
                                        DownloadHelper.enqueue(context, movie, episode, best)
                                        downloadStates[episode.id] = Download.STATE_QUEUED
                                        downloadMessage = "Download started — see Downloads tab."
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (state == Download.STATE_COMPLETED) Icons.Default.Check else Icons.Default.Download,
                                contentDescription = "Download episode",
                                tint = if (state == Download.STATE_COMPLETED) Mint else PurpleLight,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Default.PlayArrow, contentDescription = "Play episode", tint = PurpleLight)
                    }
                }
            }
            Spacer(Modifier.height(15.dp))
        }
    }
}
