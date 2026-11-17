package com.cinemora.app.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Aggregates the built-in providers. Every call isolates provider failures:
 * one dead source never breaks the whole home/search.
 */
class Repository(private val providers: List<AnimeProvider>) {

    fun allProviders(): List<AnimeProvider> = providers

    fun providerById(id: String): AnimeProvider? = providers.firstOrNull { it.id == id }

    fun providersFor(lang: Lang?): List<AnimeProvider> =
        if (lang == null) providers else providers.filter { it.lang == lang }

    /** Home rows from all (or language-filtered) providers, in parallel. */
    suspend fun home(lang: Lang?): List<HomeSection> = coroutineScope {
        providersFor(lang).map { provider ->
            async {
                try {
                    provider.homeSections()
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }.awaitAll().flatten()
    }

    /** Search across providers in parallel, merged, provider order kept. */
    suspend fun search(query: String, lang: Lang?): List<AnimeCard> {
        if (query.isBlank()) return emptyList()
        return coroutineScope {
            providersFor(lang).map { provider ->
                async {
                    try {
                        provider.search(query)
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }
    }

    suspend fun details(providerId: String, id: String): AnimeDetails? {
        val provider = providerById(providerId) ?: return null
        return try {
            provider.loadDetails(id)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun streams(providerId: String, episodeId: String): EpisodeStreams {
        val provider = providerById(providerId) ?: return EpisodeStreams(emptyList(), emptyList())
        return try {
            provider.loadStreams(episodeId)
        } catch (_: Exception) {
            EpisodeStreams(emptyList(), emptyList())
        }
    }

    /**
     * In-player language switch: find the same title on providers of [target]
     * language, load details, and return the episode with the same number.
     * Returns (providerId, details, episode) or null when no match is found.
     */
    suspend fun findSameEpisode(
        title: String,
        episodeNumber: Int,
        target: Lang,
    ): Triple<String, AnimeDetails, EpisodeItem>? {
        val candidates = search(title, target)
        for (card in candidates) {
            val details = details(card.providerId, card.id) ?: continue
            val episode = details.seasons
                .flatMap { it.episodes }
                .firstOrNull { it.number == episodeNumber }
            if (episode != null) return Triple(card.providerId, details, episode)
        }
        return null
    }
}
