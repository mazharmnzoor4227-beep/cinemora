package com.cinemora.app.data

import androidx.compose.ui.graphics.Color

/**
 * One title in the catalogue. Backed by real provider data — there is no
 * sample/demo content in the app. [id] is "providerId|contentId".
 */
data class Movie(
    val id: String,
    val title: String,
    val genre: String,
    val year: String,
    val duration: String,
    val rating: String,
    val description: String,
    val label: String,
    val accentStart: Color,
    val accentEnd: Color,
    val progress: Float = 0f,
    val type: String = "Movie",
    val seasons: Int = 0,
    // Real-content fields
    val posterUrl: String? = null,
    val bannerUrl: String? = null,
    val providerId: String = "",
    val contentId: String = "",
    val kind: String = "Movie", // "Movie" or "Anime"
    val anilistId: Int? = null,
    val malId: Int? = null,
    val genres: List<String> = emptyList(),
)
