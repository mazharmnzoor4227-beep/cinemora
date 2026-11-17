package com.cinemora.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import coil.compose.AsyncImage
import com.cinemora.app.CinemoraApplication
import com.cinemora.app.core.ContentKind
import com.cinemora.app.data.CatalogViewModel
import com.cinemora.app.data.LanguageFilter
import com.cinemora.app.data.Movie
import com.cinemora.app.data.WatchProgress
import com.cinemora.app.data.toMovie
import com.cinemora.app.ui.components.*
import com.cinemora.app.ui.theme.*

@Composable
fun HomeScreen(
    onSearchClick: () -> Unit,
    onProfileClick: () -> Unit,
    onMovieClick: (Movie) -> Unit,
    onExploreClick: () -> Unit,
    savedIds: List<String>,
    onSaveMovie: (Movie) -> Unit,
    catalog: CatalogViewModel,
) {
    val context = LocalContext.current
    val sections by catalog.homeSections.collectAsState()
    val homeLoading by catalog.homeLoading.collectAsState()
    val languageFilter by catalog.languageFilter.collectAsState()
    var selectedCategory by remember { mutableStateOf("For You") }

    val visibleSections = when (selectedCategory) {
        "Movies" -> sections.filter {
            CinemoraApplication.repository.providerById(it.providerId)?.kind == ContentKind.MOVIE
        }
        "Anime" -> sections.filter {
            CinemoraApplication.repository.providerById(it.providerId)?.kind == ContentKind.ANIME
        }
        "Trending" -> sections.filter {
            it.title.contains("trend", ignoreCase = true) ||
                it.title.contains("popular", ignoreCase = true) ||
                it.title.contains("latest", ignoreCase = true)
        }
        else -> sections
    }
    val featured = visibleSections.firstOrNull()?.items?.firstOrNull()?.toMovie()
    val continueWatching = remember { WatchProgress.loadAll(context) }

    Column(
        modifier = Modifier.fillMaxSize().background(Night).verticalScroll(rememberScrollState()).padding(bottom = 22.dp),
    ) {
        BrandHeader(onSearchClick = onSearchClick, onProfileClick = onProfileClick)

        // Language filter: Hindi Dub / English / All
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LanguageFilter.entries.forEach { filter ->
                val active = filter == languageFilter
                Box(
                    Modifier.clip(RoundedCornerShape(50))
                        .background(if (active) Purple else SurfaceElevated)
                        .clickable { catalog.setLanguageFilter(filter) }
                        .padding(horizontal = 15.dp, vertical = 9.dp),
                ) {
                    Text(filter.label, color = if (active) Color.White else TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        if (featured != null) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).height(340.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF152F58), Color(0xFF41266F), Color(0xFF111827)))),
            ) {
                if (featured.posterUrl != null) {
                    AsyncImage(
                        model = featured.posterUrl,
                        contentDescription = featured.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)))))
                } else {
                    Box(Modifier.align(Alignment.TopEnd).padding(top = 25.dp, end = 12.dp).size(180.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.055f)))
                    Box(Modifier.align(Alignment.CenterEnd).padding(end = 48.dp).size(92.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.045f)))
                }
                Column(
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    SmallTag(featured.label.ifBlank { "CINEMORA" }, Color(0xFF9C7BFF))
                    Text(featured.title.uppercase(), color = Color.White, fontSize = 36.sp, lineHeight = 36.sp, fontWeight = FontWeight.Black, letterSpacing = 0.6.sp, maxLines = 3)
                    Text(
                        listOf(featured.year, featured.genre.uppercase(), featured.type.uppercase()).filter { it.isNotBlank() }.joinToString("   ·   "),
                        color = Color.White.copy(alpha = 0.78f), fontSize = 9.sp, letterSpacing = 0.7.sp,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { onMovieClick(featured) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Night),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Text("Play Now", fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { onMovieClick(featured) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.14f), contentColor = Color.White),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Details", fontWeight = FontWeight.SemiBold)
                        }
                        Box(
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.14f))
                                .clickable { onSaveMovie(featured) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Add to My List", tint = Color.White)
                        }
                    }
                }
            }
        } else if (homeLoading) {
            Box(Modifier.fillMaxWidth().height(340.dp).padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = PurpleLight)
            }
        }

        Column(Modifier.padding(top = 22.dp)) {
            Text("DISCOVER", color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 5.dp))
            CategoryChips(listOf("For You", "Movies", "Anime", "Trending"), selectedCategory) { selectedCategory = it }
        }

        if (continueWatching.isNotEmpty()) {
            Column(Modifier.padding(top = 24.dp)) {
                Row(Modifier.padding(horizontal = 20.dp)) {
                    SectionHeading("Continue Watching", "See all", onExploreClick)
                }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    continueWatching.forEach { entry ->
                        ContinueCard(WatchProgress.toMovie(entry)) { onMovieClick(WatchProgress.toMovie(entry)) }
                    }
                }
            }
        }

        if (!homeLoading && visibleSections.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Couldn't load content.", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("Check your connection and try again.", color = TextMuted, fontSize = 12.sp)
                Button(
                    onClick = { catalog.refreshHome() },
                    colors = ButtonDefaults.buttonColors(containerColor = Purple),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("Retry", fontWeight = FontWeight.Bold) }
            }
        }

        visibleSections.forEach { section ->
            Column(Modifier.padding(top = 25.dp)) {
                Row(Modifier.padding(horizontal = 20.dp)) {
                    SectionHeading(section.title, "View all", onExploreClick)
                }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    section.items.forEach { card ->
                        PosterCard(card.toMovie(), { onMovieClick(card.toMovie()) })
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}
