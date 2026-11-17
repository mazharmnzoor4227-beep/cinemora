package com.cinemora.app.player

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.cinemora.app.CinemoraApplication
import com.cinemora.app.core.AnimeDetails
import com.cinemora.app.core.EpisodeItem
import com.cinemora.app.core.EpisodeStreams
import com.cinemora.app.core.Lang
import com.cinemora.app.core.StreamSource
import com.cinemora.app.core.SubtitleTrack
import com.cinemora.app.data.Movie
import com.cinemora.app.data.WatchProgress
import com.cinemora.app.download.DownloadCache
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Real player state, backed by Media3 ExoPlayer. */
@UnstableApi
class PlayerViewModel : ViewModel() {

    private var exoPlayer: ExoPlayer? = null
    val player: ExoPlayer?
        get() = exoPlayer

    private val _sources = MutableStateFlow<List<StreamSource>>(emptyList())
    val sources: StateFlow<List<StreamSource>> = _sources.asStateFlow()

    private val _currentSourceIndex = MutableStateFlow(0)
    val currentSourceIndex: StateFlow<Int> = _currentSourceIndex.asStateFlow()

    private val _subtitles = MutableStateFlow<List<SubtitleTrack>>(emptyList())
    val subtitles: StateFlow<List<SubtitleTrack>> = _subtitles.asStateFlow()

    private val _captionsEnabled = MutableStateFlow(true)
    val captionsEnabled: StateFlow<Boolean> = _captionsEnabled.asStateFlow()

    private val _preferredSubtitleLang = MutableStateFlow<String?>(null)
    val preferredSubtitleLang: StateFlow<String?> = _preferredSubtitleLang.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _opRange = MutableStateFlow<SkipIntro.OpRange?>(null)
    val opRange: StateFlow<SkipIntro.OpRange?> = _opRange.asStateFlow()

    private val _speed = MutableStateFlow(1f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val _currentMovieFlow = MutableStateFlow<Movie?>(null)
    val currentMovie: StateFlow<Movie?> = _currentMovieFlow.asStateFlow()
    private var currentEpisode: EpisodeItem? = null
    private var currentDetails: AnimeDetails? = null
    private var positionJob: Job? = null
    private var skipIntroFetchedFor: Long = -1L

    fun showMessage(text: String) {
        _message.value = text
    }

    fun clearMessage() {
        _message.value = null
    }

    /** Start playing this episode (player is built in buildAndPlay). */
    fun play(context: Context, movie: Movie, episode: EpisodeItem, details: AnimeDetails?) {
        _currentMovieFlow.value = movie
        currentEpisode = episode
        currentDetails = details
        if (positionJob?.isActive != true) {
            startPositionPolling()
        }
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            _opRange.value = null
            skipIntroFetchedFor = -1L
            val streams = loadStreams(movie, episode)
            if (streams.sources.isEmpty()) {
                _error.value = "No playable stream found for this episode."
                _isLoading.value = false
                return@launch
            }
            _sources.value = streams.sources.sortedByDescending { it.quality }
            _subtitles.value = streams.subtitles
            _currentSourceIndex.value = 0
            buildAndPlay(context, positionMs = 0L)
            _isLoading.value = false
        }
    }

    private suspend fun loadStreams(movie: Movie, episode: EpisodeItem): EpisodeStreams {
        return try {
            CinemoraApplication.repository.streams(movie.providerId, episode.id)
        } catch (_: Exception) {
            EpisodeStreams(emptyList(), emptyList())
        }
    }

    private fun buildAndPlay(context: Context, positionMs: Long) {
        val source = _sources.value.getOrNull(_currentSourceIndex.value) ?: return
        val httpFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(source.headers)
        val cacheFactory = CacheDataSource.Factory()
            .setCache(DownloadCache.get(context))
            .setUpstreamDataSourceFactory(httpFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        // setMediaSourceFactory exists only on ExoPlayer.Builder, not on the
        // player instance — and the factory carries this source's headers, so
        // rebuild the player here (quality / audio switches re-enter here).
        exoPlayer?.release()
        exoPlayer = ExoPlayer.Builder(context.applicationContext)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheFactory))
            .build()
        val player = exoPlayer ?: return

        val subtitleConfigs = _subtitles.value.map { sub ->
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.url))
                .setMimeType(
                    if (sub.url.contains(".vtt", ignoreCase = true)) MimeTypes.TEXT_VTT
                    else MimeTypes.APPLICATION_SUBRIP,
                )
                .setLanguage(sub.language)
                .setLabel(sub.label)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
        }
        val item = MediaItem.Builder()
            .setUri(source.url)
            .setMimeType(if (source.isM3u8) MimeTypes.APPLICATION_M3U8 else MimeTypes.VIDEO_MP4)
            .setSubtitleConfigurations(subtitleConfigs)
            .build()
        applyCaptionSettings()
        player.setMediaItem(item)
        player.prepare()
        if (positionMs > 0) player.seekTo(positionMs)
        player.playWhenReady = true
        player.playbackParameters = PlaybackParameters(_speed.value)
    }

    /** Switch to another source (quality) keeping the current position. */
    fun selectSource(context: Context, index: Int) {
        if (index == _currentSourceIndex.value) return
        if (index !in _sources.value.indices) return
        val pos = exoPlayer?.currentPosition ?: 0L
        _currentSourceIndex.value = index
        buildAndPlay(context, pos)
    }

    /** Switch audio language: find the same episode on another provider. */
    fun switchAudio(context: Context, target: Lang) {
        val movie = _currentMovieFlow.value ?: return
        val episode = currentEpisode ?: return
        viewModelScope.launch {
            _isLoading.value = true
            val found = try {
                CinemoraApplication.repository.findSameEpisode(movie.title, episode.number, target)
            } catch (_: Exception) {
                null
            }
            if (found == null) {
                _message.value = "Not available in this language."
                _isLoading.value = false
                return@launch
            }
            val (providerId, details, newEpisode) = found
            val newMovie = movie.copy(
                id = "$providerId|${details.id}",
                providerId = providerId,
                contentId = details.id,
            )
            _currentMovieFlow.value = newMovie
            currentEpisode = newEpisode
            currentDetails = details
            val streams = loadStreams(newMovie, newEpisode)
            if (streams.sources.isEmpty()) {
                _message.value = "No playable stream in this language."
                _isLoading.value = false
                return@launch
            }
            val pos = exoPlayer?.currentPosition ?: 0L
            _sources.value = streams.sources.sortedByDescending { it.quality }
            _subtitles.value = streams.subtitles
            _currentSourceIndex.value = 0
            buildAndPlay(context, pos)
            _isLoading.value = false
        }
    }

    fun setCaptionsEnabled(enabled: Boolean) {
        _captionsEnabled.value = enabled
        applyCaptionSettings()
    }

    fun setPreferredSubtitleLang(language: String?) {
        _preferredSubtitleLang.value = language
        applyCaptionSettings()
    }

    private fun applyCaptionSettings() {
        val player = exoPlayer ?: return
        val lang = _preferredSubtitleLang.value
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !_captionsEnabled.value)
            .apply {
                if (lang != null) setPreferredTextLanguage(lang)
            }
            .build()
    }

    fun setSpeed(speed: Float) {
        _speed.value = speed
        exoPlayer?.playbackParameters = PlaybackParameters(speed)
    }

    /** Hold-to-2x: call with true while held, false on release. */
    fun setHold2x(holding: Boolean) {
        exoPlayer?.playbackParameters = PlaybackParameters(if (holding) 2f else _speed.value)
    }

    fun togglePlay() {
        val player = exoPlayer ?: return
        player.playWhenReady = !player.playWhenReady
        _isPlaying.value = player.playWhenReady
    }

    fun seekTo(ms: Long) {
        exoPlayer?.seekTo(ms.coerceIn(0L, exoPlayer?.duration ?: 0L))
    }

    fun seekBy(deltaMs: Long) {
        val player = exoPlayer ?: return
        player.seekTo((player.currentPosition + deltaMs).coerceIn(0L, player.duration.coerceAtLeast(0L)))
    }

    /** Skip the opening: exact range when known, else +85s from the start. */
    fun skipIntro() {
        val player = exoPlayer ?: return
        val range = _opRange.value
        if (range != null) {
            player.seekTo(range.endMs)
        } else {
            player.seekTo((player.currentPosition + 85_000).coerceAtMost(player.duration.coerceAtLeast(0L)))
        }
    }

    private fun startPositionPolling() {
        positionJob?.cancel()
        positionJob = viewModelScope.launch {
            while (true) {
                delay(500)
                // continue (not break) on a null player: buildAndPlay briefly
                // releases the old player before assigning the rebuilt one.
                val player = exoPlayer ?: continue
                _positionMs.value = player.currentPosition
                _durationMs.value = player.duration.coerceAtLeast(0L)
                _isPlaying.value = player.isPlaying
                // Fetch skip-intro timestamps once the duration is known.
                val duration = _durationMs.value
                if (duration > 0 && skipIntroFetchedFor != duration) {
                    skipIntroFetchedFor = duration
                    val malId = currentDetails?.malId
                    val epNum = currentEpisode?.number ?: 1
                    launch {
                        _opRange.value = SkipIntro.fetchOpRange(malId, epNum, duration)
                    }
                }
            }
        }
    }

    /** Persist progress (Continue Watching) and release the player. */
    fun releaseAndSave() {
        val movie = _currentMovieFlow.value
        val episode = currentEpisode
        val player = exoPlayer
        if (movie != null && episode != null && player != null) {
            val pos = player.currentPosition
            val dur = player.duration.coerceAtLeast(0L)
            if (pos > 0 && dur > 0) {
                WatchProgress.save(CinemoraApplication.context, movie, episode, pos, dur)
            }
        }
        positionJob?.cancel()
        exoPlayer?.release()
        exoPlayer = null
    }

    override fun onCleared() {
        releaseAndSave()
        super.onCleared()
    }
}
