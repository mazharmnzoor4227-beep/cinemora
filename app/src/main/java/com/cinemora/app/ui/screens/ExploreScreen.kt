package com.cinemora.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cinemora.app.core.ContentKind
import com.cinemora.app.core.Lang
import com.cinemora.app.data.CatalogViewModel
import com.cinemora.app.data.Movie
import com.cinemora.app.data.toMovie
import com.cinemora.app.ui.components.CategoryChips
import com.cinemora.app.ui.components.PosterCard
import com.cinemora.app.ui.components.SectionHeading
import com.cinemora.app.ui.theme.*

@Composable
fun ExploreScreen(
    onMovieClick: (Movie) -> Unit,
    catalog: CatalogViewModel,
) {
    var query by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("For You") }
    val searchResults by catalog.searchResults.collectAsState()
    val searching by catalog.searching.collectAsState()
    val homeSections by catalog.homeSections.collectAsState()

    val allCards = if (query.isBlank()) {
        homeSections.flatMap { it.items }
    } else {
        searchResults
    }
    val filtered = allCards.filter { card ->
        when (selectedCategory) {
            "For You" -> true
            "Movies" -> card.kind == ContentKind.MOVIE
            "Anime" -> card.kind == ContentKind.ANIME
            "Hindi Dub" -> card.lang == Lang.HINDI
            "English Dub" -> card.lang == Lang.ENGLISH
            else -> true
        }
    }.map { it.toMovie() }

    Column(Modifier.fillMaxSize().background(Night).padding(top = 18.dp)) {
        Text("Explore", color = TextPrimary, fontSize = 27.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp))
        Text("Find your next favorite story", color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 18.dp))
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                catalog.search(it)
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            placeholder = { Text("Search movies, anime, genres...", color = TextMuted, fontSize = 12.sp) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextMuted) },
            singleLine = true,
            shape = RoundedCornerShape(15.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Purple,
                unfocusedBorderColor = DividerColor,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = PurpleLight,
                focusedContainerColor = SurfaceNight,
                unfocusedContainerColor = SurfaceNight,
            ),
        )
        Spacer(Modifier.width(1.dp))
        CategoryChips(
            items = listOf("For You", "Movies", "Anime", "Hindi Dub", "English Dub"),
            selected = selectedCategory,
            onSelected = { selectedCategory = it },
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp)) {
            SectionHeading(if (query.isBlank()) "Popular on CINEMORA" else "Search results")
        }
        if (searching) {
            Row(Modifier.fillMaxWidth().padding(22.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(color = PurpleLight, modifier = Modifier.size(28.dp))
            }
        } else if (filtered.isEmpty()) {
            Text(
                if (query.isBlank()) "Nothing loaded yet. Check your connection." else "No titles found. Try another search.",
                color = TextMuted, fontSize = 13.sp, modifier = Modifier.padding(22.dp),
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                items(filtered, key = { it.id }) { movie ->
                    PosterCard(movie, { onMovieClick(movie) }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
