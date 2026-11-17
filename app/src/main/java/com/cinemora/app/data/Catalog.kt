package com.cinemora.app.data

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cinemora.app.CinemoraApplication
import com.cinemora.app.core.AnimeCard
import com.cinemora.app.core.AnimeDetails
import com.cinemora.app.core.ContentKind
import com.cinemora.app.core.EpisodeItem
import com.cinemora.app.core.EpisodeStreams
import com.cinemora.app.core.HomeSection
import com.cinemora.app.core.Lang
import com.cinemora.app.core.Repository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Language filter for home / search. null = all languages. */
enum class LanguageFilter(val label: String, val lang: Lang?) {
    ALL("All", null),
    HINDI_DUB("Hindi Dub", Lang.HINDI),
    ENGLISH("English", Lang.ENGLISH),
}

/** Map a provider card to the UI Movie model. */
fun AnimeCard.toMovie(): Movie {
    val kind = if (kind == ContentKind.MOVIE) "Movie" else "Anime"
    return Movie(
        id = "$providerId|$id",
        title = title,
        genre = when (lang) {
            Lang.HINDI -> "Hindi Dub"
            Lang.ENGLISH -> "English"
            Lang.JAPANESE -> "Japanese"
        },
        year = year?.toString() ?: "",
        duration = "",
        rating = rating?.let { "%.1f".format(it) } ?: "",
        description = "",
        label = when (lang) {
            Lang.HINDI -> "HINDI DUB"
            Lang.ENGLISH -> "ENGLISH"
            Lang.JAPANESE -> "JAPANESE"
        },
        accentStart = Color(0xFF2A1B5E),
        accentEnd = Color(0xFF6D3BB8),
        type = kind,
        posterUrl = posterUrl,
        providerId = providerId,
        contentId = id,
        kind = kind,
    )
}

/** Map full details to the UI Movie model (fills description/genres/seasons). */
fun AnimeDetails.toMovie(): Movie {
    val kind = if (kind == ContentKind.MOVIE) "Movie" else "Anime"
    val episodeCount = seasons.sumOf { it.episodes.size }
    return Movie(
        id = "$providerId|$id",
        title = title,
        genre = genres.joinToString(" · ").ifBlank {
            when (lang) {
                Lang.HINDI -> "Hindi Dub"
                Lang.ENGLISH -> "English"
                Lang.JAPANESE -> "Japanese"
            }
        },
        year = year?.toString() ?: "",
        duration = if (kind == "Movie") "" else "$episodeCount Episodes",
        rating = rating?.let { "%.1f".format(it) } ?: "",
        description = description ?: "",
        label = when (lang) {
            Lang.HINDI -> "HINDI DUB"
            Lang.ENGLISH -> "ENGLISH"
            Lang.JAPANESE -> "JAPANESE"
        },
        accentStart = Color(0xFF2A1B5E),
        accentEnd = Color(0xFF6D3BB8),
        type = kind,
        seasons = seasons.size,
        posterUrl = posterUrl,
        bannerUrl = bannerUrl,
        providerId = providerId,
        contentId = id,
        kind = kind,
        anilistId = anilistId,
        malId = malId,
        genres = genres,
    )
}

/** Simple watch-progress store (SharedPreferences) for Continue Watching. */
object WatchProgress {
    private const val PREFS = "cinemora_progress"

    data class Entry(
        val movieId: String,
        val title: String,
        val posterUrl: String?,
        val providerId: String,
        val contentId: String,
        val episodeNumber: Int,
        val positionMs: Long,
        val durationMs: Long,
    ) {
        val progress: Float
            get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(context: Context, movie: Movie, episode: EpisodeItem, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0 || positionMs <= 0) return
        // Don't keep finished items in Continue Watching.
        if (positionMs >= durationMs - 10_000) {
            prefs(context).edit().remove(movie.id).apply()
            return
        }
        val value = listOf(
            movie.title, movie.posterUrl ?: "", movie.providerId, movie.contentId,
            episode.number.toString(), positionMs.toString(), durationMs.toString(),
        ).joinToString("\u001F")
        prefs(context).edit().putString(movie.id, value).apply()
    }

    fun loadAll(context: Context): List<Entry> {
        val out = mutableListOf<Entry>()
        for ((key, value) in prefs(context).all) {
            val raw = value as? String ?: continue
            val parts = raw.split("\u001F")
            if (parts.size < 7) continue
            out.add(
                Entry(
                    movieId = key,
                    title = parts[0],
                    posterUrl = parts[1].ifBlank { null },
                    providerId = parts[2],
                    contentId = parts[3],
                    episodeNumber = parts[4].toIntOrNull() ?: 1,
                    positionMs = parts[5].toLongOrNull() ?: 0L,
                    durationMs = parts[6].toLongOrNull() ?: 0L,
                ),
            )
        }
        return out
    }

    fun toMovie(entry: Entry): Movie = Movie(
        id = entry.movieId,
        title = entry.title,
        genre = "",
        year = "",
        duration = "",
        rating = "",
        description = "",
        label = "",
        accentStart = Color(0xFF2A1B5E),
        accentEnd = Color(0xFF6D3BB8),
        progress = entry.progress,
        type = "Anime",
        posterUrl = entry.posterUrl,
        providerId = entry.providerId,
        contentId = entry.contentId,
    )
}

/** Shared catalogue ViewModel: home rails + search, backed by real providers. */
class CatalogViewModel : ViewModel() {
    private val repository: Repository
        get() = CinemoraApplication.repository

    private val _languageFilter = MutableStateFlow(LanguageFilter.ALL)
    val languageFilter: StateFlow<LanguageFilter> = _languageFilter.asStateFlow()

    private val _homeSections = MutableStateFlow<List<HomeSection>>(emptyList())
    val homeSections: StateFlow<List<HomeSection>> = _homeSections.asStateFlow()

    private val _homeLoading = MutableStateFlow(true)
    val homeLoading: StateFlow<Boolean> = _homeLoading.asStateFlow()

    private val _searchResults = MutableStateFlow<List<AnimeCard>>(emptyList())
    val searchResults: StateFlow<List<AnimeCard>> = _searchResults.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private var searchJob: Job? = null

    init {
        refreshHome()
    }

    fun setLanguageFilter(filter: LanguageFilter) {
        if (_languageFilter.value == filter) return
        _languageFilter.value = filter
        refreshHome()
    }

    fun refreshHome() {
        viewModelScope.launch {
            _homeLoading.value = true
            _homeSections.value = repository.home(_languageFilter.value.lang)
            _homeLoading.value = false
        }
    }

    fun search(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            _searching.value = false
            return
        }
        searchJob = viewModelScope.launch {
            delay(400)
            _searching.value = true
            _searchResults.value = repository.search(query, _languageFilter.value.lang)
            _searching.value = false
        }
    }
}

/** Details ViewModel: seasons + episodes for one title. */
class DetailsViewModel : ViewModel() {
    private val repository: Repository
        get() = CinemoraApplication.repository

    private val _details = MutableStateFlow<AnimeDetails?>(null)
    val details: StateFlow<AnimeDetails?> = _details.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    fun load(providerId: String, contentId: String) {
        _details.value = null
        viewModelScope.launch {
            _loading.value = true
            _details.value = repository.details(providerId, contentId)
            _loading.value = false
        }
    }
}

/** Loads streams for one episode (used by the player + downloads). */
suspend fun loadEpisodeStreams(
    providerId: String,
    episodeId: String,
): EpisodeStreams = CinemoraApplication.repository.streams(providerId, episodeId)
