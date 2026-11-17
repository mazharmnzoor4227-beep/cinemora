// Ported from the Multimovies CloudStream plugin in dipender98/Indflix
// (MultimoviesPlugin.kt + SharedServices.kt — site search, V2 watch config,
// server chips, Dooplay search JSON).
// GPL-3.0, based on dipender98/Indflix. No CloudStream code is used here:
// the scraping logic is re-implemented against the app's own Http layer
// and the AnimeProvider contract.
//
// What was ported (essential paths only):
//  - home rows + search from the site's own /api/search JSON and the
//    /search?q= HTML listing (article.poster-card)
//  - details from the movie/series page (title, poster, plot, genres, year,
//    rating) plus the V2 server list (watchConfig.initialServers +
//    button.server-chip[data-url])
//  - streams by resolving each server embed URL: generic registered
//    extractors first, then direct .m3u8/.mp4 regex + one iframe hop.
// Skipped (plugin-only machinery, not needed for a working port):
//  - TMDB/IMDb metadata enrichment (needs the plugin's embedded TMDB key),
//    FastStart/EmbedPrefetch/Link caches, the ExternalSources server farm,
//    Dooplay nonce search (superseded by /api/search), settings UI.
// The provider therefore uses the site's own titles/posters, not TMDB's.
//
// Live status (verified 2026-11-18 from this sandbox):
//  - https://m1.multimovies.courses/ returns 200; /api/search?q=avengers
//    returns real JSON (Avengers: Endgame, 2019, tmdb poster); the search
//    page has 14 poster-cards. DomainHelper "multimovies" agrees
//    (m1.multimovies.courses).
package com.cinemora.app.providers

import com.cinemora.app.core.AnimeCard
import com.cinemora.app.core.AnimeDetails
import com.cinemora.app.core.AnimeProvider
import com.cinemora.app.core.AudioKind
import com.cinemora.app.core.ContentKind
import com.cinemora.app.core.DomainHelper
import com.cinemora.app.core.EpisodeItem
import com.cinemora.app.core.EpisodeStreams
import com.cinemora.app.core.Extractors
import com.cinemora.app.core.HomeSection
import com.cinemora.app.core.Http
import com.cinemora.app.core.Lang
import com.cinemora.app.core.SeasonGroup
import com.cinemora.app.core.StreamSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class MultimoviesProvider : AnimeProvider {
    override val id: String = "multimovies"
    override val name: String = "Multimovies"
    override val lang: Lang = Lang.HINDI
    override val kind: ContentKind = ContentKind.MOVIE

    companion object {
        const val DEFAULT_BASE = "https://m1.multimovies.courses"
        const val LANDING_URL = "https://multimovies.wtf/"
        private const val SEARCH_MAX_RESULTS = 10
    }

    @Volatile
    private var currentBase: String = DEFAULT_BASE

    override val baseUrl: String
        get() = currentBase

    /** Resolve the live base once (DomainHelper Firebase doc, else default). */
    private suspend fun resolveBase(): String {
        if (currentBase != DEFAULT_BASE) return currentBase
        return try {
            DomainHelper.getDomain("multimovies")?.trimEnd('/')?.takeIf { it.isNotBlank() }
                ?.also { currentBase = it } ?: currentBase
        } catch (_: Exception) {
            currentBase
        }
    }

    private fun liveUrl(url: String): String {
        if (url.startsWith("/")) return currentBase.trimEnd('/') + url
        return url
    }

    private fun isMultimoviesUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return url.contains("/movie/") || url.contains("/series/") || url.contains("/watch/")
    }

    // ---- home ----

    override suspend fun homeSections(): List<HomeSection> {
        return try {
            val base = resolveBase()
            val rows = listOf(
                "$base/" to "Trending",
                "$base/genre/bollywood-movies" to "Bollywood Movies",
                "$base/genre/hollywood" to "Hollywood",
                "$base/genre/south-indian" to "South Indian",
                "$base/genre/action" to "Action",
            )
            coroutineScope {
                rows.map { (url, title) ->
                    async {
                        val doc = runCatching { Http.getDoc(url) }.getOrNull()
                        val items = doc?.let { parseCards(it) } ?: emptyList()
                        if (items.isEmpty()) null else HomeSection(title, id, items)
                    }
                }.awaitAll().filterNotNull()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Parse article.poster-card cards (shared by home rows + search page). */
    private fun parseCards(doc: Document): List<AnimeCard> {
        return doc.select("article.poster-card").mapNotNull { el ->
            el.toCard()
        }.distinctBy { it.id }
    }

    private fun Element.toCard(): AnimeCard? {
        val a = selectFirst("a.poster-art[href], div.poster-caption a[href]")
            ?: return null
        val href = liveUrl(a.attr("href").trim())
        if (!isMultimoviesUrl(href) && !href.contains("multimovies")) return null
        val title = selectFirst("div.poster-caption a")?.text()?.trim()
            ?: selectFirst("img[alt]")?.attr("alt")?.trim()
            ?: return null
        if (title.isBlank()) return null
        val poster = sanitizePoster(
            selectFirst("a.poster-art img")?.attr("src")
                ?: selectFirst("img")?.attr("src"),
        )
        val meta = selectFirst("p.poster-meta")?.text().orEmpty()
        val year = Regex("""\d{4}""").find(meta)?.value?.toIntOrNull()
        val rating = selectFirst("span.poster-rating")?.text()
            ?.replace(Regex("""[^\d.]"""), "")?.toDoubleOrNull()?.takeIf { it > 0 }
            ?.toFloat()
        return AnimeCard(
            id = href,
            title = stripSiteAffix(title),
            posterUrl = poster,
            providerId = id,
            lang = lang,
            kind = kind,
            year = year,
            rating = rating,
        )
    }

    // ---- search: /api/search JSON first, HTML listing fallback ----

    override suspend fun search(query: String): List<AnimeCard> {
        if (query.isBlank()) return emptyList()
        return try {
            val base = resolveBase()
            val q = URLEncoder.encode(query.trim(), "UTF-8")
            val apiHits = runCatching {
                Http.get("$base/api/search?q=$q&limit=12")
            }.getOrNull()?.let { parseV2SearchApi(it) }.orEmpty()
            if (apiHits.isNotEmpty()) {
                return apiHits.filter { it.kind == ContentKind.MOVIE }
                    .distinctBy { it.id }.take(SEARCH_MAX_RESULTS)
            }
            val doc = runCatching {
                Http.getDoc("$base/search?q=$q")
            }.getOrNull() ?: return emptyList()
            parseCards(doc).filter { it.kind == ContentKind.MOVIE }
                .distinctBy { it.id }.take(SEARCH_MAX_RESULTS)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseV2SearchApi(raw: String?): List<AnimeCard> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val root = JSONObject(raw)
            val out = ArrayList<AnimeCard>()
            fun push(o: JSONObject?) {
                if (o == null) return
                val kind = o.optString("type")
                if (kind != "movie" && kind != "tv") return
                val title = o.optString("title").ifBlank { o.optString("name") }.trim()
                if (title.isEmpty()) return
                val url = liveUrl(o.optString("url").trim())
                if (url.isEmpty() || !isMultimoviesUrl(url)) return
                val poster = sanitizePoster(
                    o.optString("poster").ifBlank { o.optString("poster_path") },
                )
                val year = o.optInt("year", -1).takeIf { it > 0 }?.toString()
                    ?: Regex("""\d{4}""")
                        .find(o.optString("release_date") + o.optString("first_air_date"))?.value
                val rating = o.optDouble("rating", -1.0).takeIf { it > 0 }
                    ?: o.optDouble("vote_average", -1.0).takeIf { it > 0 }
                out += AnimeCard(
                    id = url,
                    title = stripSiteAffix(title),
                    posterUrl = poster,
                    providerId = id,
                    lang = lang,
                    kind = if (kind == "movie") ContentKind.MOVIE else ContentKind.ANIME,
                    year = year?.toIntOrNull(),
                    rating = rating?.toFloat(),
                )
            }
            val arr = root.optJSONArray("results")
            if (arr != null) {
                for (i in 0 until arr.length()) push(arr.optJSONObject(i))
            } else {
                val movies = root.optJSONArray("movies")
                val shows = root.optJSONArray("tv_shows") ?: root.optJSONArray("tv")
                if (movies != null) for (i in 0 until movies.length()) push(movies.optJSONObject(i))
                if (shows != null) for (i in 0 until shows.length()) push(shows.optJSONObject(i))
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ---- details ----

    private data class V2Server(val name: String, val url: String)

    /** watchConfig.initialServers + button.server-chip[data-url] (plugin's
     * parseV2Servers / parseV2WatchConfig). */
    private fun parseV2Servers(doc: Document): List<V2Server> {
        val fromConfig = parseV2WatchConfig(doc.html())
        val chips = doc.select("button.server-chip[data-url]").mapNotNull { b ->
            val raw = b.attr("data-url").trim().ifEmpty { return@mapNotNull null }
            val url = raw.replace("&amp;", "&")
            if (!url.startsWith("http")) return@mapNotNull null
            val name = b.selectFirst(".server-name")?.text()?.trim()
                ?: b.attr("data-id").trim().ifEmpty { "Server" }
            V2Server(name, url)
        }
        val tmdb = (fromConfig + chips).firstNotNullOfOrNull { extractTmdbId(it.url) }
        return (fromConfig + chips).distinctBy { it.url }.map { s ->
            s.copy(url = fixServerPlaceholder(s.url, tmdb ?: extractTmdbId(s.url)))
        }
    }

    private fun parseV2WatchConfig(html: String): List<V2Server> {
        if (html.isBlank()) return emptyList()
        return try {
            val m = Regex("""watchConfig\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL)
                .find(html) ?: return emptyList()
            val servers = JSONObject(m.groupValues[1]).optJSONArray("initialServers")
                ?: return emptyList()
            (0 until servers.length()).mapNotNull { i ->
                val o = servers.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("url").replace("\\/", "/").trim()
                if (!url.startsWith("http")) return@mapNotNull null
                V2Server(o.optString("name").trim().ifEmpty { "Server" }, url)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun extractTmdbId(url: String): String? {
        Regex("""[?&]id=(\d{2,10})""").find(url)?.groupValues?.get(1)?.let { return it }
        Regex("""/(?:movie|tv)/(\d{2,10})""").find(url)?.groupValues?.get(1)?.let { return it }
        Regex("""/embed/(?:movie|tv)/(\d{2,10})""").find(url)?.groupValues?.get(1)
            ?.let { return it }
        return null
    }

    private fun fixServerPlaceholder(url: String, tmdbId: String?): String {
        if (tmdbId.isNullOrBlank() || !url.contains("{tmdbId}")) return url
        return url.replace("{tmdbId}", tmdbId)
    }

    private fun stripSiteAffix(raw: String): String {
        val t = raw.trim()
        return Regex("""(?i)\s*[|:-]\s*Multimovies\s*$""").replace(t, "").trim()
            .ifEmpty { t }
    }

    private fun sanitizePoster(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val t = raw.trim()
        val idx = t.lastIndexOf("http", startIndex = maxOf(0, t.length - 8), ignoreCase = true)
        val cut = if (idx > 0) t.substring(idx) else t
        val fixed = if (cut.startsWith("//")) "https:$cut" else cut
        return fixed.takeIf { it.startsWith("http") }
    }

    private fun serversToJson(servers: List<V2Server>): JSONArray =
        JSONArray().apply {
            servers.forEach { put(JSONObject().put("n", it.name).put("u", it.url)) }
        }

    private fun serversFromJson(arr: JSONArray?): List<V2Server> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val u = o.optString("u").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            V2Server(o.optString("n").ifBlank { "Server" }, u)
        }
    }

    private fun episodeId(pageUrl: String, servers: List<V2Server>): String =
        JSONObject()
            .put("page", pageUrl)
            .put("servers", serversToJson(servers))
            .toString()

    override suspend fun loadDetails(id: String): AnimeDetails? {
        return try {
            val doc = runCatching { Http.getDoc(id) }.getOrNull() ?: return null
            val realUrl = doc.location().takeIf { it.isNotBlank() } ?: id
            val isMovie = realUrl.contains("/movie/") || realUrl.contains("/watch/movie") ||
                (!realUrl.contains("/series/") && !realUrl.contains("/watch/tv") &&
                    realUrl.contains("/movies/"))
            val pageTitle = doc.selectFirst(
                "h1.cinejoy-detail-title, div.sheader h1, h1, meta[property=og:title]",
            )?.let { if (it.tagName() == "meta") it.attr("content") else it.text() }
                ?.trim()?.let { stripSiteAffix(it.replace(Regex("""\s*-\s*MultiMovies.*$""", RegexOption.IGNORE_CASE), "")) }
                ?: return null
            val poster = sanitizePoster(
                doc.selectFirst("meta[property=og:image]")?.attr("content")
                    ?: doc.selectFirst("div.watch-bg img")?.attr("src")
                    ?: doc.selectFirst("div.poster img, img.wp-post-image")?.attr("src"),
            )
            val banner = doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst("img.cinema-backdrop")?.attr("src")
            val year = doc.selectFirst(".cinejoy-meta-pill")?.parent()?.text()
                ?.let { Regex("""\d{4}""").find(it)?.value?.toIntOrNull() }
                ?: doc.selectFirst("span.date, .year, .extra span")?.text()
                    ?.let { Regex("""\d{4}""").find(it)?.value?.toIntOrNull() }
            val plot = doc.selectFirst("p.cinejoy-detail-overview")?.text()?.trim()
                ?: doc.selectFirst("div.wp-content, div.description, .wp-content p")?.text()
                    ?.replace("Overview:", "")?.trim()
            val genres = doc.selectFirst("div.cinejoy-detail-genres")?.text()
                ?.split("•", ",", "|")?.map { it.trim() }?.filter { it.isNotBlank() }
                ?: doc.select("div.sgeneros a, .genre a").map { it.text().trim() }
                    .filter { it.isNotBlank() }
            val rating = doc.selectFirst(".cinejoy-score-pill")?.text()
                ?.replace(Regex("""[^\d.]"""), "")?.toDoubleOrNull()
                ?: doc.selectFirst("span.dt_rating_vgs, .imdb, .rating span")?.text()
                    ?.removePrefix("IMDb:")?.trim()?.toDoubleOrNull()
            val servers = parseV2Servers(doc)

            val seasons: List<SeasonGroup>
            if (isMovie) {
                val episode = EpisodeItem(
                    id = episodeId(realUrl, servers),
                    number = 1,
                    title = pageTitle,
                    season = 1,
                )
                seasons = listOf(SeasonGroup(1, "Movie", listOf(episode)))
            } else {
                seasons = parseSeriesSeasons(doc, servers)
                if (seasons.isEmpty()) return null
            }
            AnimeDetails(
                id = id,
                title = pageTitle,
                posterUrl = poster,
                bannerUrl = banner,
                description = plot,
                genres = genres,
                status = null,
                year = year,
                rating = rating?.toFloat(),
                seasons = seasons,
                providerId = id,
                lang = lang,
                kind = kind,
                audioKinds = setOf(AudioKind.SUB, AudioKind.DUB),
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Series episode cards (div.cinejoy-ep-card) grouped by season. */
    private fun parseSeriesSeasons(doc: Document, pageServers: List<V2Server>): List<SeasonGroup> {
        val bySeason = linkedMapOf<Int, MutableList<EpisodeItem>>()
        doc.select("div.cinejoy-ep-card").forEachIndexed { i, card ->
            val link = card.selectFirst(
                "a.cinejoy-ep-thumb-link[href], a.cinejoy-ep-name[href]",
            )?.attr("href")?.trim()?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
            val epUrl = liveUrl(link)
            if (!isMultimoviesUrl(epUrl)) return@forEachIndexed
            val seasonNum = Regex("""/season/(\d+)""").find(epUrl)
                ?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val epNum = Regex("""/episode/(\d+)""").find(epUrl)
                ?.groupValues?.get(1)?.toIntOrNull()
                ?: card.selectFirst("span.cinejoy-ep-badge-num")?.text()
                    ?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }
                ?: (i + 1)
            val epTitle = card.selectFirst("a.cinejoy-ep-name")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
            val epThumb = card.selectFirst("img.cinejoy-ep-img")?.attr("src")
                ?.takeIf { it.isNotBlank() && !it.contains("default-col") }
            bySeason.getOrPut(seasonNum) { mutableListOf() } += EpisodeItem(
                id = episodeId(epUrl, pageServers),
                number = epNum,
                title = epTitle ?: "Episode $epNum",
                season = seasonNum,
                thumbUrl = epThumb,
            )
        }
        return bySeason.entries.sortedBy { it.key }.map { (season, eps) ->
            SeasonGroup(season, "Season $season", eps.sortedBy { it.number })
        }
    }

    // ---- streams ----

    override suspend fun loadStreams(episodeId: String): EpisodeStreams {
        return try {
            val obj = runCatching { JSONObject(episodeId) }.getOrNull()
                ?: return EpisodeStreams(emptyList(), emptyList())
            val pageUrl = obj.optString("page")
            var servers = serversFromJson(obj.optJSONArray("servers"))
            if (servers.isEmpty() && pageUrl.isNotBlank()) {
                val doc = runCatching { Http.getDoc(pageUrl) }.getOrNull()
                if (doc != null) servers = parseV2Servers(doc)
            }
            if (servers.isEmpty()) return EpisodeStreams(emptyList(), emptyList())
            val sources = coroutineScope {
                val sem = Semaphore(4)
                servers.take(6).map { server ->
                    async {
                        sem.acquire()
                        try {
                            withTimeoutOrNull(20_000L) {
                                resolveServer(server, pageUrl)
                            }.orEmpty()
                        } finally {
                            sem.release()
                        }
                    }
                }.awaitAll().flatten()
            }
            EpisodeStreams(sources.distinctBy { it.url }, emptyList())
        } catch (_: Exception) {
            EpisodeStreams(emptyList(), emptyList())
        }
    }

    /** Resolve one server embed URL to playable sources. */
    private suspend fun resolveServer(server: V2Server, pageUrl: String): List<StreamSource> {
        val url = server.url
        if (url.contains(".m3u8", ignoreCase = true) || url.endsWith(".mp4", ignoreCase = true)) {
            return listOf(
                StreamSource(
                    url = url,
                    quality = qualityFromLabel(url),
                    isM3u8 = url.contains(".m3u8"),
                    serverName = server.name,
                    headers = mapOf("Referer" to pageUrl),
                ),
            )
        }
        // 1) Generic registered extractors (StreamWish/Filemoon/VidHide/...)
        val viaExtractor = runCatching { Extractors.resolve(url, pageUrl) }.getOrNull()
        if (viaExtractor != null && viaExtractor.sources.isNotEmpty()) {
            return viaExtractor.sources.map {
                if (it.serverName.isBlank()) it.copy(serverName = server.name) else it
            }
        }
        // 2) Raw embed page: direct file links + one iframe hop.
        val html = runCatching { Http.get(url, mapOf("Referer" to pageUrl)) }.getOrNull()
            ?: return emptyList()
        val found = extractFileLinks(html, url)
        if (found.isNotEmpty()) {
            return found.map { (fileUrl, quality) ->
                StreamSource(
                    url = fileUrl,
                    quality = quality,
                    isM3u8 = fileUrl.contains(".m3u8"),
                    serverName = server.name,
                    headers = mapOf("Referer" to url),
                )
            }
        }
        val doc = runCatching { org.jsoup.Jsoup.parse(html, url) }.getOrNull()
        val iframe = doc?.selectFirst("iframe[src]")?.attr("abs:src")
            ?.takeIf { it.isNotBlank() && it != url } ?: return emptyList()
        val inner = runCatching { Http.get(iframe, mapOf("Referer" to url)) }.getOrNull()
            ?: return emptyList()
        return extractFileLinks(inner, iframe).map { (fileUrl, quality) ->
            StreamSource(
                url = fileUrl,
                quality = quality,
                isM3u8 = fileUrl.contains(".m3u8"),
                serverName = server.name,
                headers = mapOf("Referer" to iframe),
            )
        }
    }

    private val FILE_URL_REGEX = Regex(
        """https?://[^"'<>\s\\]+\.(?:m3u8|mp4)(?:\?[^"'<>\s\\]*)?""",
        RegexOption.IGNORE_CASE,
    )

    private fun extractFileLinks(html: String, base: String): List<Pair<String, Int>> {
        val text = html.replace("\\/", "/")
        return FILE_URL_REGEX.findAll(text).map { it.value.trim() }
            .filter { it.startsWith("http") }
            .distinct()
            .map { it to qualityFromLabel(it) }
            .toList()
    }

    private fun qualityFromLabel(label: String?): Int {
        if (label.isNullOrBlank()) return 0
        Regex("""(?i)\b(\d{3,4})p\b""").find(label)?.groupValues?.get(1)
            ?.toIntOrNull()?.let { return it }
        if (Regex("""(?i)\b4K\b""").containsMatchIn(label)) return 2160
        return 0
    }
}
