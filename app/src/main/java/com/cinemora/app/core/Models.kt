package com.cinemora.app.core

/** Language of the content a provider serves. */
enum class Lang { HINDI, ENGLISH, JAPANESE }

/** What kind of catalogue a provider serves. */
enum class ContentKind { ANIME, MOVIE }

/** Audio track kind of an episode list. */
enum class AudioKind { SUB, DUB }

/** One anime card shown in home rows / search results. */
data class AnimeCard(
    val id: String, // opaque id understood by the provider (usually the details URL)
    val title: String,
    val posterUrl: String?,
    val providerId: String,
    val lang: Lang,
    val kind: ContentKind = ContentKind.ANIME,
    val year: Int? = null,
    val rating: Float? = null,
)

/** One horizontal row on the home screen. */
data class HomeSection(
    val title: String,
    val providerId: String,
    val items: List<AnimeCard>,
)

/** One episode inside a season. [id] is opaque data passed to loadStreams. */
data class EpisodeItem(
    val id: String,
    val number: Int,
    val title: String?,
    val season: Int,
    val thumbUrl: String? = null,
)

/** One season on the detail page. */
data class SeasonGroup(
    val season: Int,
    val name: String,
    val episodes: List<EpisodeItem>,
)

/** Full anime details for the detail page. */
data class AnimeDetails(
    val id: String,
    val title: String,
    val posterUrl: String?,
    val bannerUrl: String?,
    val description: String?,
    val genres: List<String>,
    val status: String?,
    val year: Int?,
    val rating: Float?,
    val anilistId: Int? = null,
    val malId: Int? = null,
    val seasons: List<SeasonGroup>,
    val providerId: String,
    val lang: Lang,
    val kind: ContentKind = ContentKind.ANIME,
    val audioKinds: Set<AudioKind> = setOf(AudioKind.SUB),
)

/** One playable video source for an episode. */
data class StreamSource(
    val url: String,
    val quality: Int, // 1080 / 720 / 480 / 0 = unknown
    val isM3u8: Boolean,
    val serverName: String,
    val headers: Map<String, String> = emptyMap(),
)

/** One subtitle track for an episode. */
data class SubtitleTrack(
    val url: String,
    val language: String,
    val label: String,
)

/** Everything the player needs for one episode. */
data class EpisodeStreams(
    val sources: List<StreamSource>,
    val subtitles: List<SubtitleTrack>,
)
