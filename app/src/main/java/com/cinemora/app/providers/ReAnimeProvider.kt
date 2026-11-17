// Ported from ReAnimeProvider.kt + ReAnimeApi.kt + ReAnimeModels.kt +
// FlixDownload.kt in KSHITIJ8473/raghav (GPL-3.0, based on
// KSHITIJ8473/raghav). CloudStream APIs replaced with the app's own core
// (Http / Models); Jackson replaced with kotlinx.serialization
// (Http.json). No com.lagradost imports.
package com.cinemora.app.providers

import com.cinemora.app.core.AnimeCard
import com.cinemora.app.core.AnimeDetails
import com.cinemora.app.core.AnimeProvider
import com.cinemora.app.core.AudioKind
import com.cinemora.app.core.DomainHelper
import com.cinemora.app.core.EpisodeItem
import com.cinemora.app.core.EpisodeStreams
import com.cinemora.app.core.HomeSection
import com.cinemora.app.core.Http
import com.cinemora.app.core.Lang
import com.cinemora.app.core.SeasonGroup
import com.cinemora.app.core.StreamSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

// ---------------------------------------------------------------------------
// API models (ported from ReAnimeModels.kt, Jackson -> kotlinx.serialization)
// ---------------------------------------------------------------------------

@Serializable
data class SearchEnvelope(
    @SerialName("results") val results: List<SearchItem>? = null,
)

@Serializable
data class SearchItem(
    @SerialName("anime_id") val animeId: String? = null,
    @SerialName("title") val title: Title? = null,
    @SerialName("cover_image") val coverImage: CoverImage? = null,
    @SerialName("season_year") val seasonYear: Int? = null,
    @SerialName("subbed") val subbed: Int? = null,
    @SerialName("dubbed") val dubbed: Int? = null,
    @SerialName("average_score") val averageScore: Int? = null,
)

@Serializable
data class Title(
    @SerialName("english") val english: String? = null,
    @SerialName("romaji") val romaji: String? = null,
    @SerialName("native") val native: String? = null,
    @SerialName("user_preferred") val userPreferred: String? = null,
) {
    fun display(): String? =
        english?.takeIf { it.isNotBlank() } ?: romaji?.takeIf { it.isNotBlank() }
            ?: native?.takeIf { it.isNotBlank() } ?: userPreferred?.takeIf { it.isNotBlank() }
}

@Serializable
data class CoverImage(
    @SerialName("extra_large") val extraLarge: String? = null,
    @SerialName("large") val large: String? = null,
    @SerialName("medium") val medium: String? = null,
) {
    fun best(): String? = extraLarge ?: large ?: medium
}

@Serializable
data class HomeEnvelope(
    @SerialName("data") val data: List<SearchItem>? = null,
    @SerialName("has_more") val hasMore: Boolean? = null,
    @SerialName("next_cursor") val nextCursor: String? = null,
)

@Serializable
data class AnimeDetail(
    @SerialName("anilist_id") val anilistId: Int? = null,
    @SerialName("mal_id") val malId: Int? = null,
    @SerialName("themoviedb_id") val themoviedbId: Int? = null,
    @SerialName("external_seasons") val externalSeasons: ExternalSeasons? = null,
    @SerialName("title") val title: Title? = null,
    @SerialName("cover_image") val coverImage: CoverImage? = null,
    @SerialName("banner_image") val bannerImage: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("format") val format: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("genres") val genres: List<String>? = null,
    @SerialName("tags") val tags: List<Tag>? = null,
    @SerialName("season_year") val seasonYear: Int? = null,
    @SerialName("duration") val duration: Int? = null,
    @SerialName("average_score") val averageScore: Int? = null,
)

@Serializable
data class ExternalSeasons(
    @SerialName("tmdb") val tmdb: Int? = null,
)

@Serializable
data class Tag(
    @SerialName("name") val name: String? = null,
)

@Serializable
data class EpisodesEnvelope(
    @SerialName("data") val data: List<EpisodeEntry>? = null,
)

@Serializable
data class EpisodeEntry(
    @SerialName("episode_number") val episodeNumber: Int? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("thumbnail") val thumbnail: String? = null,
    @SerialName("aired") val aired: String? = null,
    @SerialName("subbed") val subbed: Boolean? = null,
    @SerialName("dubbed") val dubbed: Boolean? = null,
)

@Serializable
data class FlixResponse(
    @SerialName("success") val success: Boolean? = null,
    @SerialName("servers") val servers: List<FlixServer>? = null,
)

@Serializable
data class FlixServer(
    @SerialName("serverName") val serverName: String? = null,
    @SerialName("dataLink") val dataLink: String? = null,
)

/** Opaque episode reference carried in EpisodeItem.id for loadStreams. */
@Serializable
private data class EpisodeRef(
    val slug: String,
    val ep: Int,
    val lang: String,
    val anilistId: Int? = null,
    val tmdbId: Int? = null,
    val season: Int? = null,
)

// ---------------------------------------------------------------------------
// API client (ported from ReAnimeApi.kt)
// ---------------------------------------------------------------------------

internal object ReAnimeApi {
    private const val DEFAULT_URL = "https://reanime.to"
    const val FLIX_EMBED_BASE = "https://flixcloud.cc"

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    @Volatile
    private var mainUrl = DEFAULT_URL

    suspend fun refreshDomain() {
        DomainHelper.getDomain("reanime")?.let { mainUrl = it }
    }

    fun url(): String = mainUrl

    val BROWSER_HEADERS: Map<String, String>
        get() = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "application/json",
            "Referer" to "$mainUrl/",
        )

    private val homeMutex = Mutex()
    private val homeCursors = mutableMapOf<String, String?>()

    private inline fun <reified T> parse(text: String): T? =
        try {
            Http.json.decodeFromString<T>(text)
        } catch (_: Exception) {
            null
        }

    private suspend fun getJson(
        url: String,
        headers: Map<String, String> = BROWSER_HEADERS,
    ): String? = try {
        val (code, body) = Http.getWithCode(url, headers)
        if (code in 200..299) body else null
    } catch (_: Exception) {
        null
    }

    suspend fun search(query: String, page: Int, limit: Int = 40): List<SearchItem> {
        val offset = (page - 1) * limit
        val body = getJson("$mainUrl/api/v1/search?q=${urlEncode(query)}&limit=$limit&offset=$offset")
            ?: return emptyList()
        return parse<SearchEnvelope>(body)?.results ?: emptyList()
    }

    suspend fun searchSorted(sort: String, page: Int, limit: Int = 40): List<SearchItem> {
        val offset = (page - 1) * limit
        val body = getJson("$mainUrl/api/v1/search?limit=$limit&offset=$offset&sort=$sort")
            ?: return emptyList()
        return parse<SearchEnvelope>(body)?.results ?: emptyList()
    }

    suspend fun homeSection(
        section: String,
        page: Int,
        limit: Int = 40,
    ): Pair<List<SearchItem>, Boolean> {
        val cursor = homeMutex.withLock {
            if (page <= 1) null else homeCursors["$section-$page"]
        }
        val sep = if (cursor == null) "?" else "&cursor=${urlEncode(cursor)}"
        val body = getJson("$mainUrl/api/v1/home/$section?limit=$limit$sep")
            ?: return Pair(emptyList(), false)
        val env = parse<HomeEnvelope>(body) ?: return Pair(emptyList(), false)
        val items = env.data ?: emptyList()
        if (page >= 1 && env.nextCursor != null) {
            homeMutex.withLock {
                homeCursors["$section-${page + 1}"] = env.nextCursor
            }
        }
        return items to (env.hasMore == true)
    }

    suspend fun animeDetail(slug: String): AnimeDetail? {
        val body = getJson("$mainUrl/api/v1/anime/${urlEncode(slug)}") ?: return null
        return parse<AnimeDetail>(body)
    }

    suspend fun episodes(slug: String): List<EpisodeEntry> {
        val body = getJson("$mainUrl/api/v1/anime/${urlEncode(slug)}/episodes?limit=2000")
            ?: return emptyList()
        return parse<EpisodesEnvelope>(body)?.data ?: emptyList()
    }

    suspend fun flixServers(
        anilistId: Int?,
        tmdbId: Int?,
        season: Int?,
        episode: Int,
    ): List<FlixServer> {
        val url = when {
            anilistId != null && anilistId > 0 ->
                "$mainUrl/api/flix/$anilistId/$episode"
            tmdbId != null && tmdbId > 0 ->
                "$mainUrl/api/flix/0/$episode?tmdb=$tmdbId&season=${season ?: 1}"
            else -> return emptyList()
        }
        val body = getJson(url, BROWSER_HEADERS + mapOf("Referer" to "$mainUrl/watch/"))
            ?: return emptyList()
        val res = parse<FlixResponse>(body) ?: return emptyList()
        if (res.success != true) return emptyList()
        return res.servers ?: emptyList()
    }

    fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")
}

// ---------------------------------------------------------------------------
// flixcloud direct-file resolver (ported from FlixDownload.kt)
// ---------------------------------------------------------------------------

/**
 * flixcloud.cc serves every episode as one plain MKV download (both audio
 * tracks, all subtitles inside), so no local proxy is needed to decrypt the
 * HLS stream:
 *   /d/{id}/__data.json -> file id + signed token -> /download/{file}/progress
 *   -> /download/{file}
 * The token is bound to the IP that asked for it, which is the device that
 * plays the file.
 */
private object FlixDownload {
    data class File(
        val url: String,
        val quality: Int?,
        val size: String?,
        val headers: Map<String, String>,
    )

    private val idRegex = Regex("""/e/([a-z0-9]+)""", RegexOption.IGNORE_CASE)
    private val fileIdRegex =
        Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""", RegexOption.IGNORE_CASE)
    private val tokenRegex = Regex("""eyJ[\w-]+\.[\w-]+\.[\w-]+""")
    private val hostRegex = Regex("""https://[a-z0-9-]+\.flixcloud\.cc""", RegexOption.IGNORE_CASE)
    private val qualityRegex = Regex("""(\d{3,4})p""")
    private val sizeRegex = Regex(""""(\d+(?:\.\d+)?\s*[KMG]B)"""", RegexOption.IGNORE_CASE)

    /** The embed id ("dtpx2rezr4mb" of https://flixcloud.cc/e/dtpx2rezr4mb?v=1):
     * every server of an episode shares it. */
    fun embedId(embedUrl: String): String? = idRegex.find(embedUrl)?.groupValues?.get(1)

    suspend fun resolve(embedUrl: String): File? {
        val id = embedId(embedUrl) ?: return null
        val headers = mapOf(
            "User-Agent" to ReAnimeApi.BROWSER_HEADERS["User-Agent"]!!,
            "Referer" to "${ReAnimeApi.FLIX_EMBED_BASE}/",
            "Accept" to "*/*",
        )
        return try {
            val (code, body) = Http.getWithCode(
                "${ReAnimeApi.FLIX_EMBED_BASE}/d/$id/__data.json",
                headers,
            )
            if (code !in 200..299) return null
            val fileId = fileIdRegex.find(body)?.value ?: return null
            val token = tokenRegex.find(body)?.value ?: return null
            val host = hostRegex.find(body)?.value ?: ReAnimeApi.FLIX_EMBED_BASE

            // Only a file the host has finished preparing can be played.
            val (progressCode, progressBody) = Http.getWithCode(
                "$host/download/$fileId/progress?token=$token",
                headers,
            )
            if (progressCode !in 200..299 || !progressBody.contains("\"ready\"")) return null

            File(
                url = "$host/download/$fileId?token=$token",
                quality = qualityRegex.find(body)?.groupValues?.get(1)?.toIntOrNull(),
                size = sizeRegex.find(body)?.groupValues?.get(1),
                headers = headers - "Accept",
            )
        } catch (_: Exception) {
            null
        }
    }
}

// ---------------------------------------------------------------------------
// Provider (ported from ReAnimeProvider.kt)
// ---------------------------------------------------------------------------

class ReAnimeProvider : AnimeProvider {
    override val id: String = "reanime"
    override val name: String = "ReAnime"
    override val lang: Lang = Lang.ENGLISH
    override var baseUrl: String
        get() = ReAnimeApi.url()
        set(_) {}

    private suspend fun refreshBase(): String {
        ReAnimeApi.refreshDomain()
        return ReAnimeApi.url()
    }

    override suspend fun homeSections(): List<HomeSection> {
        val rows = listOf(
            "popular" to "Popular",
            "latest-aired" to "Latest Episodes",
            "new-on-site" to "Recently Added",
            "upcoming" to "Upcoming",
        )
        return try {
            refreshBase()
            coroutineScope {
                rows.map { (section, title) ->
                    async {
                        try {
                            val items = when (section) {
                                "popular" -> ReAnimeApi.searchSorted("popularity_desc", 1)
                                else -> ReAnimeApi.homeSection(section, 1).first
                            }.mapNotNull { it.toSearchResponse() }
                            HomeSection(title, id, items)
                        } catch (_: Exception) {
                            HomeSection(title, id, emptyList())
                        }
                    }
                }.awaitAll().filter { it.items.isNotEmpty() }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun search(query: String): List<AnimeCard> {
        if (query.isBlank()) return emptyList()
        return try {
            refreshBase()
            ReAnimeApi.search(query, 1).mapNotNull { it.toSearchResponse() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun SearchItem.toSearchResponse(): AnimeCard? {
        val slug = animeId ?: return null
        val displayTitle = title?.display() ?: return null
        val poster = coverImage?.best()
        val year = seasonYear
        return AnimeCard(
            id = "${ReAnimeApi.url()}/anime/$slug",
            title = displayTitle,
            posterUrl = poster,
            providerId = id,
            lang = lang,
            year = year,
            rating = averageScore?.toFloat()?.div(10f),
        )
    }

    override suspend fun loadDetails(id: String): AnimeDetails? {
        return try {
            val base = refreshBase()
            val slug = id.removePrefix("$base/").removePrefix("/").removePrefix("anime/")
                .substringBefore('?').substringBefore('#')
            if (slug.isBlank()) return null
            val anime = ReAnimeApi.animeDetail(slug) ?: return null
            val eps = ReAnimeApi.episodes(slug)
            val title = anime.title?.display() ?: return null
            val showStatus = when (anime.status) {
                "RELEASING" -> "Ongoing"
                "FINISHED" -> "Completed"
                else -> null
            }

            val subEps = mutableListOf<EpisodeItem>()
            val dubEps = mutableListOf<EpisodeItem>()
            for (ep in eps) {
                val num = ep.episodeNumber ?: continue
                if (ep.subbed == true) subEps.add(ep.toEpisode(slug, num, "sub", anime))
                if (ep.dubbed == true) dubEps.add(ep.toEpisode(slug, num, "dub", anime))
            }
            if (subEps.isEmpty() && dubEps.isEmpty()) {
                for (ep in eps) {
                    val num = ep.episodeNumber ?: continue
                    subEps.add(ep.toEpisode(slug, num, "sub", anime))
                }
            }

            val seasons = mutableListOf<SeasonGroup>()
            if (subEps.isNotEmpty()) seasons.add(SeasonGroup(1, "Season 1", subEps))
            if (dubEps.isNotEmpty()) seasons.add(SeasonGroup(2, "Season 1 (Dub)", dubEps))
            val audioKinds = mutableSetOf<AudioKind>()
            if (subEps.isNotEmpty()) audioKinds.add(AudioKind.SUB)
            if (dubEps.isNotEmpty()) audioKinds.add(AudioKind.DUB)

            AnimeDetails(
                id = "$base/anime/$slug",
                title = title,
                posterUrl = anime.coverImage?.best(),
                bannerUrl = anime.bannerImage,
                description = anime.description?.let(::stripHtml),
                genres = anime.genres ?: anime.tags?.mapNotNull { it.name } ?: emptyList(),
                status = showStatus,
                year = anime.seasonYear,
                rating = anime.averageScore?.toFloat()?.div(10f),
                anilistId = anime.anilistId,
                malId = anime.malId,
                seasons = seasons,
                providerId = id,
                lang = lang,
                audioKinds = audioKinds.ifEmpty { setOf(AudioKind.SUB) },
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun EpisodeEntry.toEpisode(
        slug: String,
        num: Int,
        lang: String,
        anime: AnimeDetail,
    ): EpisodeItem {
        val ref = EpisodeRef(
            slug = slug,
            ep = num,
            lang = lang,
            anilistId = anime.anilistId,
            tmdbId = anime.themoviedbId,
            season = anime.externalSeasons?.tmdb,
        )
        return EpisodeItem(
            id = Http.json.encodeToString(ref),
            number = num,
            title = title?.takeIf { it.isNotBlank() && !it.startsWith("Episode ") },
            season = if (lang == "dub") 2 else 1,
            thumbUrl = thumbnail?.takeIf { it.startsWith("http") },
        )
    }

    private fun stripHtml(html: String): String =
        html.replace(Regex("<br\\s*/?>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .trim()

    override suspend fun loadStreams(episodeId: String): EpisodeStreams {
        return try {
            val ref = try {
                Http.json.decodeFromString<EpisodeRef>(episodeId)
            } catch (_: Exception) {
                null
            } ?: return EpisodeStreams(emptyList(), emptyList())
            if (ref.ep <= 0) return EpisodeStreams(emptyList(), emptyList())

            refreshBase()
            val servers = ReAnimeApi.flixServers(ref.anilistId, ref.tmdbId, ref.season, ref.ep)
            if (servers.isEmpty()) return EpisodeStreams(emptyList(), emptyList())

            // Every server of an episode (HD-1/HD-2, sub/dub) points at the
            // same flixcloud file: one MKV with both the Japanese and English
            // audio and all subtitles inside.
            val embeds = servers.mapNotNull { s ->
                s.dataLink?.takeIf { it.startsWith("http") && FlixDownload.embedId(it) != null }
            }.distinctBy { FlixDownload.embedId(it) }

            val any = AtomicBoolean(false)
            val sources = coroutineScope {
                embeds.map { embedUrl ->
                    async {
                        val file = FlixDownload.resolve(embedUrl) ?: return@async null
                        any.set(true)
                        val label = "Re:ANIME - Sub & Dub" +
                            (file.size?.let { " ($it)" } ?: "")
                        StreamSource(
                            url = file.url,
                            quality = file.quality ?: 0,
                            isM3u8 = false,
                            serverName = label,
                            headers = file.headers,
                        )
                    }
                }.awaitAll().filterNotNull()
            }
            if (!any.get()) return EpisodeStreams(emptyList(), emptyList())
            EpisodeStreams(sources, emptyList())
        } catch (_: Exception) {
            EpisodeStreams(emptyList(), emptyList())
        }
    }
}
