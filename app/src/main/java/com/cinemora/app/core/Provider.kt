package com.cinemora.app.core

/**
 * Contract every built-in source implements. Providers are native in-app code
 * (ported scraping logic) — there is no plugin/extension system in this app.
 *
 * Rules for implementations:
 * - All functions are suspend and run on Dispatchers.IO via the repository.
 * - Never throw out of these functions: return empty lists / null on failure.
 * - Use [Http] for all network access so cookies + user-agent stay consistent.
 * - [id] values are opaque strings the provider itself understands.
 */
interface AnimeProvider {
    /** Stable id, e.g. "animeworldindia". Used in navigation + downloads. */
    val id: String

    /** Display name, e.g. "AnimeWorldIndia". */
    val name: String

    /** Primary language of this source's catalogue. */
    val lang: Lang

    /** What kind of catalogue this source serves (default ANIME). */
    val kind: ContentKind
        get() = ContentKind.ANIME

    /** Current base URL (may change via DomainHelper). */
    val baseUrl: String

    /** Home rows for this source (trending / latest / popular ...). */
    suspend fun homeSections(): List<HomeSection>

    /** Search this source by title. Empty query returns empty list. */
    suspend fun search(query: String): List<AnimeCard>

    /** Full details (seasons + episodes) for [id]. */
    suspend fun loadDetails(id: String): AnimeDetails?

    /** Playable sources + subtitles for one episode [episodeId]. */
    suspend fun loadStreams(episodeId: String): EpisodeStreams
}
