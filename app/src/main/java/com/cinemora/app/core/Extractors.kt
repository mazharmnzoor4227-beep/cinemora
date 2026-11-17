package com.cinemora.app.core

/**
 * A video-host extractor: turns an embed/page URL into playable sources.
 * Provider-specific extractors (MegaPlay, Vidmoly, KiwiK, AniSuge resolver,
 * FlixDownload ...) implement this interface in their provider files.
 * Generic host extractors (StreamWish, Filemoon, VidHide, Voe ...) live in
 * [GenericExtractors] and are registered at app start.
 */
interface VideoExtractor {
    /** True when this extractor knows how to handle [url]. */
    fun canHandle(url: String): Boolean

    /** Resolve [url] (with [referer] as the page that embedded it) to sources. */
    suspend fun extract(url: String, referer: String): EpisodeStreams
}

object Extractors {
    private val registered = mutableListOf<VideoExtractor>()

    fun register(extractor: VideoExtractor) {
        if (registered.none { it::class == extractor::class }) {
            registered.add(extractor)
        }
    }

    /** Try the registered extractors in order; empty result when none matches. */
    suspend fun resolve(url: String, referer: String): EpisodeStreams {
        val ext = registered.firstOrNull { it.canHandle(url) }
            ?: return EpisodeStreams(emptyList(), emptyList())
        return try {
            ext.extract(url, referer)
        } catch (_: Exception) {
            EpisodeStreams(emptyList(), emptyList())
        }
    }

    /** Convenience: a direct file link as a single source. */
    fun direct(
        url: String,
        serverName: String,
        quality: Int = 0,
        headers: Map<String, String> = emptyMap(),
    ): EpisodeStreams = EpisodeStreams(
        sources = listOf(
            StreamSource(
                url = url,
                quality = quality,
                isM3u8 = url.contains(".m3u8"),
                serverName = serverName,
                headers = headers,
            ),
        ),
        subtitles = emptyList(),
    )
}
