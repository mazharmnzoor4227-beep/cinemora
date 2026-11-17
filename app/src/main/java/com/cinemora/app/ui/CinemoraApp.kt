package com.cinemora.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import com.cinemora.app.core.EpisodeItem
import com.cinemora.app.data.CatalogViewModel
import com.cinemora.app.data.DetailsViewModel
import com.cinemora.app.data.Movie
import com.cinemora.app.ui.screens.*
import com.cinemora.app.ui.theme.*

enum class MainTab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    EXPLORE("Explore", Icons.Default.Search),
    MY_LIST("My List", Icons.Default.BookmarkBorder),
    DOWNLOADS("Downloads", Icons.Default.Download),
    PROFILE("Profile", Icons.Default.Person),
}

@OptIn(UnstableApi::class)
@Composable
fun CinemoraApp() {
    val catalog: CatalogViewModel = viewModel()
    val detailsViewModel: DetailsViewModel = viewModel()
    var activeTab by remember { mutableStateOf(MainTab.HOME) }
    var selectedMovie by remember { mutableStateOf<Movie?>(null) }
    var selectedEpisode by remember { mutableStateOf<EpisodeItem?>(null) }
    var isPlayer by remember { mutableStateOf(false) }
    val savedMovies = remember { mutableStateListOf<Movie>() }
    val savedIds = savedMovies.map { it.id }
    val playerDetails by detailsViewModel.details.collectAsState()

    val screenKey = when {
        isPlayer -> "player"
        selectedMovie != null -> "details"
        else -> activeTab.name
    }

    fun toggleSaved(movie: Movie) {
        val existing = savedMovies.firstOrNull { it.id == movie.id }
        if (existing != null) savedMovies.remove(existing) else savedMovies.add(movie)
    }

    BackHandler(enabled = isPlayer || selectedMovie != null) {
        if (isPlayer) isPlayer = false else selectedMovie = null
    }

    Scaffold(
        containerColor = Night,
        bottomBar = {
            if (!isPlayer && selectedMovie == null) {
                BottomNavigationBar(activeTab = activeTab, onTabSelected = { activeTab = it })
            }
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding).background(Night)) {
            AnimatedContent(
                targetState = screenKey,
                label = "cinemora-screen-transition",
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(150)) },
            ) { target ->
                when (target) {
                    "player" -> {
                        val movie = selectedMovie
                        val episode = selectedEpisode
                        if (movie != null && episode != null) {
                            PlayerScreen(
                                movie = movie,
                                episode = episode,
                                details = playerDetails,
                                onBack = { isPlayer = false },
                            )
                        }
                    }
                    "details" -> selectedMovie?.let { movie ->
                        DetailsScreen(
                            movie = movie,
                            isSaved = savedIds.contains(movie.id),
                            onBack = { selectedMovie = null },
                            onPlay = { episode ->
                                selectedEpisode = episode
                                isPlayer = true
                            },
                            onSave = { toggleSaved(movie) },
                            detailsViewModel = detailsViewModel,
                        )
                    }
                    MainTab.HOME.name -> HomeScreen(
                        onSearchClick = { activeTab = MainTab.EXPLORE },
                        onProfileClick = { activeTab = MainTab.PROFILE },
                        onMovieClick = { selectedMovie = it },
                        onExploreClick = { activeTab = MainTab.EXPLORE },
                        savedIds = savedIds,
                        onSaveMovie = { toggleSaved(it) },
                        catalog = catalog,
                    )
                    MainTab.EXPLORE.name -> ExploreScreen(
                        onMovieClick = { selectedMovie = it },
                        catalog = catalog,
                    )
                    MainTab.MY_LIST.name -> MyListScreen(
                        movies = savedMovies,
                        onMovieClick = { selectedMovie = it },
                    )
                    MainTab.DOWNLOADS.name -> DownloadsScreen(
                        onPlayOffline = { movie, episode ->
                            selectedMovie = movie
                            selectedEpisode = episode
                            detailsViewModel.load(movie.providerId, movie.contentId)
                            isPlayer = true
                        },
                    )
                    MainTab.PROFILE.name -> ProfileScreen()
                    else -> HomeScreen(
                        onSearchClick = { activeTab = MainTab.EXPLORE },
                        onProfileClick = { activeTab = MainTab.PROFILE },
                        onMovieClick = { selectedMovie = it },
                        onExploreClick = { activeTab = MainTab.EXPLORE },
                        savedIds = savedIds,
                        onSaveMovie = { toggleSaved(it) },
                        catalog = catalog,
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomNavigationBar(activeTab: MainTab, onTabSelected: (MainTab) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(Color(0xFF101725)).navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MainTab.entries.forEach { tab ->
            val selected = tab == activeTab
            Column(
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(13.dp))
                    .clickable { onTabSelected(tab) }.padding(vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Icon(tab.icon, contentDescription = tab.label, tint = if (selected) PurpleLight else TextMuted, modifier = Modifier.size(20.dp))
                Text(tab.label, color = if (selected) PurpleLight else TextMuted, fontSize = 9.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}
