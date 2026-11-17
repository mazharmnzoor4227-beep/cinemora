// MINIMAL port of the IndStream CloudStream plugin in dipender98/Indflix
// (IndStreamPlugin.kt + ServerRegistry.kt + StreamEngine.kt).
// GPL-3.0, based on dipender98/Indflix. No CloudStream code is used here:
// the logic below is re-implemented against the app's own Http layer and
// the AnimeProvider contract.
//
// Why minimal (honest scope note — read before extending):
//  - The full federated StreamEngine (233KB, ~19 servers) cannot be ported
//    faithfully in one pass. Its flagship server, VidLink, needs a
//    libsodium secretbox token built with a rotating 32-byte key extracted
//    from VidLink's player JS — the app has no sodium dependency and the
//    key is not a stable public constant. VidLink is therefore SKIPPED.
//  - TMDB search/catalog/detail in the plugin runs on embedded TMDB API
//    keys. Those keys are credentials and are deliberately NOT copied into
//    this app, so TMDB is not used at all here. Instead this provider uses
//    two keyless public APIs that cover the same ground:
//      * search: IMDb suggestion API (v2.sg.media-imdb.com, no key)
//      * details + home catalogs: Cinemeta (v3-cinemeta.strem.io, no key;
//        its meta responses also carry the TMDB id as "moviedb_id")
//  - Streams come from the 2 simplest ServerRegistry servers that need
//    only a TMDB id and a plain embed-page fetch: Vidly (verified working
//    2026-11-18: embed page contains a playable .m3u8 master playlist) and
//    Vidrift (same embed pattern, best-effort — its embed page returned no
//    .m3u8 for the probe title). Vidy/Movy/NetMirror/Bingr/Atlantic and the
//    rest of the farm are not ported.
//  - Subtitles: Vidly master playlists can carry subtitle renditions; they
//    are parsed when present. No Wyzie/OpenSubtitles lookup is included.
//
// Live status (verified 2026-11-18 from this sandbox):
//  - IMDb suggestion: 200, real results (Avengers: Doomsday, tt21357150)
//  - Cinemeta catalog top: 200 (redirects to cinemeta-catalogs.strem.io)
//  - Cinemeta meta tt4154796: 200, moviedb_id 299534, poster, cast, rating
//  - Vidly embed movie/299534: 200, contains playable .m3u8
//  - Vidrift embed movie/299534: 200, no .m3u8 found (best-effort only)
//  - vidlink.pro home: 200 (API itself not attempted — see above)
package com.cinemora.app.providers

import com.cinemora.app.core.AnimeCard
import com.cinemora.app.core.AnimeDetails
import com.cinemora.app.core.AnimeProvider
import com.cinemora.app.core.AudioKind
import com.cinemora.app.core.ContentKind
import com.cinemora.app.core.EpisodeItem
import com.cinemora.app.core.EpisodeStreams
import com.cinemora.app.core.HomeSection
import com.cinemora.app.core.Http
import com.cinemora.app.core.Lang
import com.cinemora.app.core.SeasonGroup
import com.cinemora.app.core.StreamSource
import com.cinemora.app.core.SubtitleTrack
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class IndStreamProvider : AnimeProvider {
    override val id: String = "indstream"
    override val name: String = "IndStream"
    override val lang: Lang = Lang.HINDI
    override val kind: ContentKind = ContentKind.MOVIE

    // baseUrl is only an identity for this provider: all data comes from the
    // keyless IMDb-suggest + Cinemeta APIs and the Vidly/Vidrift embeds.
    override val baseUrl: String = "https://www.themoviedb.org"

    companion object {
        private const val IMDB_SUGGEST = "https://v2.sg.media-imdb.com/suggestion"
        private const val CINEMETA = "https://v3-cinemeta.strem.io"
        private const val VIDLY_ORIGIN = "https://vidly.pro"
        private const val VIDRIFT_ORIGIN = "https://embed.vidrift.net"

        private val EMPTY_RESULT: Pair<List<StreamSource>, List<SubtitleTrack>> =
            emptyList<StreamSource>() to emptyList<SubtitleTrack>()

        private val M3U8_REGEX = Regex(
            """https?://[^"'<>\s\\]+\.m3u8[^"'<>\s\\]*""",
            RegexOption.IGNORE_CASE,
        )
        private val STREAM_INF_REGEX = Regex(
            """#EXT-X-STREAM-INF:[^\n]*RESOLUTION=\d+x(\d+)[^\n]*\n([^\n#][^\n]*)""",
        )
        private val SUB_MEDIA_REGEX = Regex(
            """#EXT-X-MEDIA:TYPE=SUBTITLES[^\n]*""",
        )
    }

    /** Small cache of cards seen via search/home, so loadDetails can still
     * build a page when the Cinemeta meta call fails. */
    private val cardCache = ConcurrentHashMap<String, AnimeCard>()

    private fun remember(card: AnimeCard) {
        val imdb = imdbOf(card.id) ?: return
        if (cardCache.size < 200) cardCache[imdb] = card
    }

    private fun imdbOf(cardId: String): String? =
        Regex("""tt\d{7,8}""").find(cardId)?.value

    // ---- home: Cinemeta catalogs (keyless) ----

    override suspend fun homeSections(): List<HomeSection> {
        return try {
            val rows = listOf(
                "$CINEMETA/catalog/movie/top.json" to "Top Movies",
                "$CINEMETA/catalog/movie/popular.json" to "Popular Movies",
            )
            coroutineScope {
                rows.map { (url, title) ->
                    async {
                        val cards = runCatching { Http.get(url) }.getOrNull()
                            ?.let { parseCinemetaCatalog(it) }.orEmpty()
                        cards.forEach { remember(it) }
                        if (cards.isEmpty()) null else HomeSection(title, id, cards)
                    }
                }.awaitAll().filterNotNull()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseCinemetaCatalog(json: String): List<AnimeCard> {
        return try {
            val metas = JSONObject(json).optJSONArray("metas") ?: return emptyList()
            (0 until metas.length()).mapNotNull { i ->
                val m = metas.optJSONObject(i) ?: return@mapNotNull null
                val imdb = m.optString("id").takeIf { it.startsWith("tt") }
                    ?: return@mapNotNull null
                val title = m.optString("name").takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                AnimeCard(
                    id = "imdb:$imdb",
                    title = title,
                    posterUrl = m.optString("poster").takeIf { it.isNotBlank() },
                    providerId = id,
                    lang = lang,
                    kind = kind,
                    year = m.optString("year").take(4).toIntOrNull(),
                    rating = m.optString("imdbRating").toFloatOrNull(),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ---- search: IMDb suggestion API (keyless) ----

    override suspend fun search(query: String): List<AnimeCard> {
        if (query.isBlank()) return emptyList()
        return try {
            val q = query.trim()
            val first = q.firstOrNull()?.lowercaseChar()?.takeIf { it in 'a'..'z' } ?: 'a'
            val url = "$IMDB_SUGGEST/$first/${URLEncoder.encode(q, "UTF-8")}.json"
            val body = runCatching { Http.get(url) }.getOrNull() ?: return emptyList()
            val arr = JSONObject(body).optJSONArray("d") ?: return emptyList()
            val out = ArrayList<AnimeCard>()
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                val type = m.optString("q")
                // Movies only: "feature". (TV series/mini-series are skipped;
                // this provider serves ContentKind.MOVIE.)
                if (type.isNotBlank() && type != "feature") continue
                val imdb = m.optString("id").takeIf { it.startsWith("tt") } ?: continue
                val title = m.optString("l").takeIf { it.isNotBlank() } ?: continue
                val poster = m.optJSONObject("i")?.optString("imageUrl")
                    ?.takeIf { it.isNotBlank() }
                val year = m.optInt("y", -1).takeIf { it > 0 }
                out += AnimeCard(
                    id = "imdb:$imdb",
                    title = title,
                    posterUrl = poster,
                    providerId = id,
                    lang = lang,
                    kind = kind,
                    year = year,
                )
            }
            out.forEach { remember(it) }
            out.distinctBy { it.id }.take(12)
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ---- details: Cinemeta meta (keyless; carries moviedb_id) ----

    override suspend fun loadDetails(id: String): AnimeDetails? {
        return try {
            val imdb = imdbOf(id) ?: return null
            val cached = cardCache[imdb]
            val meta = runCatching {
                Http.get("$CINEMETA/meta/movie/$imdb.json")
            }.getOrNull()?.let { body ->
                runCatching { JSONObject(body).optJSONObject("meta") }.getOrNull()
            }
            val title = meta?.optString("name")?.takeIf { it.isNotBlank() }
                ?: cached?.title ?: return null
            val tmdbId = meta?.optInt("moviedb_id", -1)?.takeIf { it > 0 }
            val year = meta?.optString("year")?.take(4)?.toIntOrNull() ?: cached?.year
            val poster = meta?.optString("poster")?.takeIf { it.isNotBlank() }
                ?: cached?.posterUrl
            val banner = meta?.optString("background")?.takeIf { it.isNotBlank() }
            val description = meta?.optString("description")?.takeIf { it.isNotBlank() }
            val genres = meta?.optJSONArray("genre")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
            }.orEmpty()
            val rating = meta?.optString("imdbRating")?.toFloatOrNull() ?: cached?.rating
            val episodeId = JSONObject()
                .put("tmdb", tmdbId ?: JSONObject.NULL)
                .put("imdb", imdb)
                .put("title", title)
                .toString()
            val episode = EpisodeItem(
                id = episodeId,
                number = 1,
                title = title,
                season = 1,
            )
            AnimeDetails(
                id = id,
                title = title,
                posterUrl = poster,
                bannerUrl = banner,
                description = description,
                genres = genres,
                status = null,
                year = year,
                rating = rating,
                seasons = listOf(SeasonGroup(1, "Movie", listOf(episode))),
                providerId = id,
                lang = lang,
                kind = kind,
                audioKinds = setOf(AudioKind.SUB, AudioKind.DUB),
            )
        } catch (_: Exception) {
            null
        }
    }

    // ---- streams: Vidly + Vidrift embed pages (ServerRegistry) ----

    override suspend fun loadStreams(episodeId: String): EpisodeStreams {
        return try {
            val obj = runCatching { JSONObject(episodeId) }.getOrNull()
                ?: return EpisodeStreams(emptyList(), emptyList())
            val tmdbId = obj.optInt("tmdb", -1).takeIf { it > 0 } ?: return EpisodeStreams(emptyList(), emptyList())
            val results: List<Pair<List<StreamSource>, List<SubtitleTrack>>> = coroutineScope {
                listOf(
                    async { withTimeoutOrNull(15_000L) { resolveEmbedServer("Vidly", "$VIDLY_ORIGIN/embed/movie/$tmdbId", VIDLY_ORIGIN) } ?: EMPTY_RESULT },
                    async { withTimeoutOrNull(15_000L) { resolveEmbedServer("Vidrift", "$VIDRIFT_ORIGIN/embed/movie/$tmdbId", VIDRIFT_ORIGIN) } ?: EMPTY_RESULT },
                ).awaitAll()
            }
            val sources = results.flatMap { it.first }
            val subtitles = results.flatMap { it.second }
            EpisodeStreams(
                sources = sources.distinctBy { it.url },
                subtitles = subtitles.distinctBy { it.url },
            )
        } catch (_: Exception) {
            EpisodeStreams(emptyList(), emptyList())
        }
    }

    /**
     * Port of StreamEngine.resolveVidly / vidlyStreams: fetch the embed page,
     * scrape .m3u8 URLs out of it, then fetch each master playlist to learn
     * the best variant height (quality) and any subtitle renditions.
     * Masters below 720p are dropped, matching the plugin's quality floor.
     */
    private suspend fun resolveEmbedServer(
        serverName: String,
        embedUrl: String,
        origin: String,
    ): Pair<List<StreamSource>, List<SubtitleTrack>> {
        val html = runCatching {
            Http.get(embedUrl, mapOf("Referer" to "$origin/"))
        }.getOrNull() ?: return emptyList<StreamSource>() to emptyList()
        val urls = vidlyStreams(html)
        if (urls.isEmpty()) return emptyList<StreamSource>() to emptyList()
        val sources = ArrayList<StreamSource>()
        val subtitles = ArrayList<SubtitleTrack>()
        for (url in urls.take(2)) {
            val masterText = runCatching {
                Http.get(url, mapOf("Referer" to "$origin/"))
            }.getOrNull()
            val master = masterText?.let { parseMaster(it, url) }
            val height = master?.bestHeight ?: 0
            if (master != null && height in 1..719) continue
            sources += StreamSource(
                url = url,
                quality = height,
                isM3u8 = true,
                serverName = serverName,
                headers = mapOf(
                    "Referer" to "$origin/",
                    "User-Agent" to Http.USER_AGENT,
                ),
            )
            master?.subtitles?.let { subtitles += it }
        }
        return sources to subtitles
    }

    private fun vidlyStreams(html: String): List<String> {
        if (html.isBlank()) return emptyList()
        val text = html.replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u003f", "?")
        return M3U8_REGEX.findAll(text)
            .map { it.value.trim() }
            .filter { it.startsWith("http") }
            .distinct()
            .toList()
    }

    private data class ParsedMaster(val bestHeight: Int, val subtitles: List<SubtitleTrack>)

    private fun parseMaster(text: String, masterUrl: String): ParsedMaster? {
        if (!text.contains("#EXTM3U")) return null
        var best = 0
        STREAM_INF_REGEX.findAll(text).forEach { m ->
            m.groupValues[1].toIntOrNull()?.let { if (it > best) best = it }
        }
        val subtitles = ArrayList<SubtitleTrack>()
        SUB_MEDIA_REGEX.findAll(text).forEach { m ->
            val line = m.value
            val uri = Regex("""URI="([^"]+)"""").find(line)?.groupValues?.get(1)
                ?: return@forEach
            val lang = Regex("""LANGUAGE="([^"]+)"""").find(line)?.groupValues?.get(1)
                ?: "und"
            val label = Regex("""NAME="([^"]+)"""").find(line)?.groupValues?.get(1)
                ?: lang
            subtitles += SubtitleTrack(
                url = Http.absUrl(masterUrl, uri),
                language = lang,
                label = label,
            )
        }
        return ParsedMaster(best, subtitles)
    }
}
