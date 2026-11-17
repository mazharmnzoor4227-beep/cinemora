// Ported from the Vegamovies CloudStream plugin in dipender98/Indflix
// (VegamoviesPlugin.kt, NexdriveResolver.kt, VcloudResolver.kt, LinkNaming.kt).
// GPL-3.0, based on dipender98/Indflix. No CloudStream code is used here:
// the scraping/resolving logic is re-implemented against the app's own
// Http layer and the AnimeProvider contract.
//
// Live status (verified 2026-11-18 from this sandbox):
//  - https://vegamovies.lease serves the real WordPress site: the
//    ts-search.php index works, home has poster-cards, detail pages carry
//    nexdrive genxfm gateways, and gateways expand to fastdl + vcloud links.
//  - The plugin's older seeds (new1.vegamovies.lease = dead, new2...
//    .futbol -> vegamovies.free stub page, vegamovies.lol = for-sale) no
//    longer serve the site, so vegamovies.lease (also the DomainHelper
//    "vegamovies" value) is the default base and the seed list is probed
//    with the index endpoint whenever a call comes back empty.
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
import java.util.concurrent.ConcurrentHashMap

class VegamoviesProvider : AnimeProvider {
    override val id: String = "vegamovies"
    override val name: String = "Vegamovies"
    override val lang: Lang = Lang.HINDI
    override val kind: ContentKind = ContentKind.MOVIE

    companion object {
        const val DEFAULT_BASE = "https://vegamovies.lease"
        val SEEDS = listOf(
            DEFAULT_BASE,
            "https://new1.vegamovies.lease",
            "https://new2.vegamovies.futbol",
            "https://vegamovies.free",
        )
        private const val SEARCH_MAX_RESULTS = 10
        private const val HEAL_COOLDOWN_MS = 60_000L
        private const val INDEX_PROBE = "film"

        val GENXFM_REGEX = Regex(
            """https?://[a-z0-9.\-]*nexdrive\.[a-z]{2,10}/genxfm[^\s"'<>\\]+""",
            RegexOption.IGNORE_CASE,
        )
        val DOWNLOAD_HEADING = Regex(
            """(?i)(\d{3,4}p\b|4K|WEB[\s-]?DL|WEBRip|Blu\s?Ray|BD-?Rip|HD-?Rip|DVDRip|x26[45]|HEVC|AVC\b|\d+(?:\.\d+)?\s?(?:GB|MB)(?:/|\b)|/ZiP|/ZIP|\bZIP\b|Batch|Season\s*\d|\bPack\b|Complete|DUAL\s*[- ]?AUDIO|HINDI|TAMIL|TELUGU|\bEP(?:\.|ISODE)?\s*\d|S\d{1,2}[\s._-]?E\d{1,3})""",
        )
        val NOISE_HEADING = Regex(
            """(?i)(leave a comment|search movies|recent updates|related|screenshot|official portal|vegamovies 20|comment )""",
        )
    }

    @Volatile
    private var currentBase: String = DEFAULT_BASE

    @Volatile
    private var lastHealMs: Long = 0L

    override val baseUrl: String
        get() = currentBase

    // ---- domain healing (port of verifiedBase / DomainResolver) ----

    private suspend fun refreshBase() {
        val now = System.currentTimeMillis()
        if (now - lastHealMs < HEAL_COOLDOWN_MS) return
        lastHealMs = now
        try {
            DomainHelper.getDomain("vegamovies")?.let { helper ->
                if (searchIndexJson(helper, INDEX_PROBE) != null) {
                    currentBase = helper.trimEnd('/')
                    return
                }
            }
        } catch (_: Exception) {
        }
        for (seed in SEEDS) {
            val live = verifiedBase(seed) ?: continue
            currentBase = live
            return
        }
    }

    /** Fetch a seed; if it is a landing page, harvest linked vegamovies.* hosts
     * from it and return the first one whose search index answers JSON. */
    private suspend fun verifiedBase(seed: String): String? {
        val doc = runCatching { Http.getDoc(seed) }.getOrNull() ?: return null
        val candidates = linkedSetOf<String>()
        Regex("""^https?://[^/]+""").find(seed)?.value?.let { candidates += it }
        doc.select("a[href]").forEach { a ->
            Regex(
                """https?://[a-z0-9.\-]*vegamovies\.[a-z]{2,10}""",
                RegexOption.IGNORE_CASE,
            ).find(a.attr("abs:href"))?.value?.lowercase()?.let { candidates += it }
        }
        for (candidate in candidates) {
            if (searchIndexJson(candidate, INDEX_PROBE) != null) return candidate.trimEnd('/')
        }
        return null
    }

    private fun candidateBases(): List<String> =
        (listOf(currentBase) + SEEDS).distinct()

    // ---- search index (ts-search.php Typesense JSON) ----

    private suspend fun searchIndexJson(base: String, query: String): String? {
        val url = "$base/ts-search.php?q=${URLEncoder.encode(query.trim(), "UTF-8")}&page=1"
        return try {
            val text = Http.get(url, mapOf("Referer" to "$base/"))
            if (text.trimStart().startsWith("{")) text else null
        } catch (_: Exception) {
            null
        }
    }

    private fun parseSearchHits(json: String, baseUrl: String): List<AnimeCard> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val hits = root.optJSONArray("hits") ?: return emptyList()
        return (0 until hits.length()).mapNotNull { i ->
            val doc = hits.optJSONObject(i)?.optJSONObject("document") ?: return@mapNotNull null
            val permalink = doc.optString("permalink").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val rawTitle = doc.optString("post_title").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val title = cleanSearchTitle(rawTitle)
            if (title.isBlank()) return@mapNotNull null
            val absolute = if (permalink.startsWith("http")) permalink
            else baseUrl.trimEnd('/') + permalink
            val poster = doc.optString("post_thumbnail").takeIf { it.isNotBlank() }
            val year = Regex("""\((\d{4})""").find(rawTitle)
                ?.groupValues?.get(1)?.toIntOrNull()
            AnimeCard(
                id = absolute,
                title = title,
                posterUrl = poster,
                providerId = id,
                lang = lang,
                kind = kind,
                year = year,
            )
        }
    }

    private suspend fun siteSearch(query: String): List<AnimeCard> {
        for (base in candidateBases()) {
            val json = searchIndexJson(base, query) ?: continue
            val hits = parseSearchHits(json, base)
            if (hits.isNotEmpty()) return hits
        }
        // Fallback: plain WordPress search page.
        for (base in candidateBases()) {
            val doc = runCatching {
                Http.getDoc(
                    "$base/?s=${URLEncoder.encode(query.trim(), "UTF-8")}",
                    mapOf("Referer" to "$base/"),
                )
            }.getOrNull() ?: continue
            val hits = parseListing(doc)
            if (hits.isNotEmpty()) return hits
        }
        return emptyList()
    }

    // ---- titles ----

    internal fun cleanSearchTitle(raw: String): String {
        var t = raw.trim().removePrefix("Download").trim()
        val until = Regex("""(.*?\(\d{4}[^)]*\))""").find(t)
        if (until != null) {
            return until.groupValues[1].trim().trimEnd(' ', '-', '|', ':', ',')
        }
        t = t.replace(
            Regex(
                """(?i)\)\s*(\d{3,4}p[\s\S]*|WEB[\s-]?DL.*|BluRay.*|HDTS.*|HDTV.*|4K.*|Dual Audio.*|Hindi.*|S\d{2}E\d{2,}.*)$""",
            ),
            "",
        ).trim()
        return t.trim(' ', '-', '|', ':', ',').ifBlank { raw.trim().take(80) }
    }

    private fun isSeries(permalink: String, categories: List<String>, rawTitle: String): Boolean {
        val hay = (permalink + " " + categories.joinToString(" ") + " " + rawTitle).lowercase()
        return Regex(
            """season|series|-series|drama|anime|s\d{1,2}e\d{1,3}|web-?show|tv-show|\beps\b|complete""",
        ).containsMatchIn(hay) && !Regex("""full movie|-movie\b""").containsMatchIn(hay)
    }

    // ---- home / listing ----

    override suspend fun homeSections(): List<HomeSection> {
        return try {
            homeSectionsInternal(healed = false)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun homeSectionsInternal(healed: Boolean): List<HomeSection> {
            val rows = listOf(
                "$currentBase/" to "Latest Releases",
                "$currentBase/dual-audio-movies/" to "Dual Audio Movies",
                "$currentBase/hindi-dubbed-movies/" to "Hindi Dubbed",
                "$currentBase/movies-by-genres/action/" to "Action",
                "$currentBase/movies-by-quality/2160p/" to "4K Ultra HD",
            )
            val sections = coroutineScope {
                rows.map { (url, title) ->
                    async {
                        val doc = runCatching { Http.getDoc(url) }.getOrNull()
                        val items = doc?.let { parseListing(it) } ?: emptyList()
                        if (items.isEmpty()) null
                        else HomeSection(title, id, items)
                    }
                }.awaitAll().filterNotNull()
            }
            if (sections.isEmpty() && !healed) {
                refreshBase()
                return homeSectionsInternal(healed = true)
            }
            return sections
    }

    internal fun parseListing(doc: Document): List<AnimeCard> {
        val cards = doc.select("div.poster-card").mapNotNull { cardToCard(it) }
            .ifEmpty {
                doc.select("a[href*=\"/download-\"]").mapNotNull { a ->
                    cardToCard(a.parent() ?: return@mapNotNull null, a)
                }
            }
        return cards.distinctBy { it.id }
    }

    private fun cardToCard(card: Element, link: Element? = null): AnimeCard? {
        val href = card.selectFirst("meta[itemprop=url]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: link?.attr("abs:href")
            ?: card.selectFirst("""a[href*="/download-"]""")?.attr("abs:href")
            ?: return null
        if (!GENXFM_REGEX.containsMatchIn(href) && !href.contains("/download-")) return null
        val img = card.selectFirst("img")
        val rawTitle = img?.attr("alt").orEmpty()
            .ifBlank { card.selectFirst(".poster-title, h2, h3")?.text().orEmpty() }
            .ifBlank { return null }
        val poster = img?.let { i ->
            listOf(i.attr("abs:src"), i.attr("abs:data-src"), i.attr("abs:data-lazy-src"))
                .firstOrNull { it.isNotBlank() }
        }
        val title = cleanSearchTitle(rawTitle)
        val year = Regex("""\((\d{4})""").find(rawTitle)
            ?.groupValues?.get(1)?.toIntOrNull()
        return AnimeCard(
            id = href,
            title = title,
            posterUrl = poster,
            providerId = id,
            lang = lang,
            kind = kind,
            year = year,
        )
    }

    // ---- search ----

    override suspend fun search(query: String): List<AnimeCard> {
        if (query.isBlank()) return emptyList()
        return try {
            var hits = siteSearch(query)
            if (hits.isEmpty()) {
                refreshBase()
                hits = siteSearch(query)
            }
            hits.distinctBy { it.id }.take(SEARCH_MAX_RESULTS)
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ---- details ----

    private data class DlLink(val gatewayUrl: String, val chip: String, val heading: String)

    private data class Group(
        val heading: String,
        val season: Int?,
        val episode: Int?,
        val links: List<DlLink>,
    )

    private data class Scraped(val imdbId: String?, val groups: List<Group>)

    private fun parseDetail(doc: Document): Scraped {
        val html = doc.body().html()
        val imdbId = Regex("""imdb\.com/title/(tt\d+)""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)
            ?: Regex("""\[imdb[^]]*](tt\d+)\[/imdb]""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1)
        val groups = ArrayList<Group>()
        var heading = ""
        var anchors = ArrayList<DlLink>()

        fun closeGroup() {
            if (anchors.isNotEmpty() && heading.isNotBlank()) {
                val (season, episode) = LinkNaming.seasonEpisodeFrom(heading)
                groups += Group(heading, season, episode, anchors.toList())
            }
            anchors = ArrayList()
        }

        val root = doc.selectFirst("div.entry-content, article, main") ?: doc.body()
        for (el in root.allElements) {
            when {
                el.tagName().matches(Regex("h[1-6]")) -> {
                    val t = el.text().trim()
                    if (t.isNotBlank() && t.length < 220) {
                        if (anchors.isNotEmpty()) closeGroup()
                        heading = if (DOWNLOAD_HEADING.containsMatchIn(t)) t else ""
                    }
                }
                el.tagName() == "a" -> {
                    val href = el.attr("abs:href")
                    if (GENXFM_REGEX.containsMatchIn(href)) {
                        anchors += DlLink(
                            href.trimEnd('/') + "/",
                            LinkNaming.kindFromChip(el.text()),
                            heading,
                        )
                    }
                }
            }
        }
        closeGroup()
        val merged = groups.groupBy { Triple(it.heading, it.season ?: 1, it.episode) }
            .map { (_, gs) ->
                gs.first().copy(links = gs.flatMap { it.links }.distinctBy { it.gatewayUrl })
            }
            .filter { it.links.isNotEmpty() }
        return Scraped(imdbId, merged)
    }

    /** Expand every gateway link of every group into concrete (kind, url) links. */
    private suspend fun expandAll(scraped: Scraped, referer: String): List<PayloadLink> =
        coroutineScope {
            val sem = Semaphore(8)
            val perGroup = scraped.groups.map { g ->
                async {
                    sem.acquire()
                    try {
                        g.links.flatMap { dl ->
                            val chipKind = LinkNaming.kindFromChip(dl.chip)
                            val exp = NexdriveResolver.expand(dl.gatewayUrl, referer)
                            if (exp.links.isEmpty()) {
                                listOf(
                                    PayloadLink(
                                        url = dl.gatewayUrl,
                                        kind = chipKind.ifBlank { Servers.GATE },
                                        heading = g.heading,
                                        idx = 0,
                                        isGateway = true,
                                        season = g.season,
                                        episode = g.episode,
                                    ),
                                )
                            } else {
                                exp.links.map { c ->
                                    PayloadLink(
                                        url = c.url,
                                        kind = if (chipKind == Servers.ZIP) Servers.ZIP else c.kind,
                                        heading = g.heading,
                                        idx = c.idx,
                                        isGateway = false,
                                        season = g.season,
                                        episode = g.episode,
                                    )
                                }
                            }
                        }.distinctBy { it.url }
                    } finally {
                        sem.release()
                    }
                }
            }.awaitAll()
            perGroup.flatten()
        }

    override suspend fun loadDetails(id: String): AnimeDetails? {
        return try {
            var doc = runCatching { Http.getDoc(id) }.getOrNull()
            if (doc == null) {
                refreshBase()
                val rebased = rebase(id, currentBase)
                doc = runCatching { Http.getDoc(rebased) }.getOrNull()
                    ?: runCatching { Http.getDoc(id) }.getOrNull()
            }
            doc ?: return null
            val h1 = doc.selectFirst("h1, .entry-title, .post-title")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                ?: return null
            val scraped = parseDetail(doc)
            if (scraped.groups.isEmpty()) return null
            val title = cleanSearchTitle(h1.removePrefix("Download").trim())
            val year = Regex("""\((\d{4})""").find(h1)
                ?.groupValues?.get(1)?.toIntOrNull()
            val poster = doc.selectFirst("meta[property=og:image]")?.attr("abs:content")
                ?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst(".entry-content img, article img")?.absUrl("src")
            val body = doc.body().text()
            val imdbRating = Regex("""(?i)IMDb Rating:?\s*[-–]?\s*([\d.]+)""").find(body)
                ?.groupValues?.get(1)?.toDoubleOrNull()
            val plot = Regex("""(?i)SYNOPSIS|\bPLOT:|\bPlot\b""").find(body)?.let { m ->
                val after = body.substring(m.range.last + 1)
                val cut = after.indexOf("Screenshots", ignoreCase = true)
                (if (cut >= 0) after.substring(0, cut) else after)
                    .trim(':', '-', ' ', '\n').take(1200).takeIf { it.isNotBlank() }
            }
            val language = Regex("""(?im)^\s*Language:\s*(.+)$""").find(body)
                ?.groupValues?.get(1)?.trim()
            val genres = buildList {
                language?.takeIf { it.isNotBlank() }?.let { add(it.trim()) }
            }

            val links = withTimeoutOrNull(45_000L) { expandAll(scraped, id) }.orEmpty()
            if (links.isEmpty()) return null
            val payload = LinkPayload(id, links, scraped.imdbId).toJson()
            val episode = EpisodeItem(
                id = payload,
                number = 1,
                title = title,
                season = 1,
            )
            AnimeDetails(
                id = id,
                title = title,
                posterUrl = poster,
                bannerUrl = poster,
                description = plot,
                genres = genres,
                status = null,
                year = year,
                rating = imdbRating?.toFloat(),
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

    private fun rebase(url: String, base: String): String {
        val path = Regex("""^https?://[^/]+(/.*)?$""").find(url)
            ?.groupValues?.get(1) ?: return url
        return base.trimEnd('/') + path
    }

    // ---- streams ----

    override suspend fun loadStreams(episodeId: String): EpisodeStreams {
        return try {
            val payload = LinkPayload.fromJson(episodeId) ?: return EpisodeStreams(emptyList(), emptyList())
            if (payload.links.isEmpty()) return EpisodeStreams(emptyList(), emptyList())
            val referer = payload.pageUrl.ifBlank { currentBase }
            val sources = coroutineScope {
                val sem = Semaphore(8)
                payload.links.map { pl ->
                    async {
                        sem.acquire()
                        try {
                            runCatching { buildLinks(pl, referer) }.getOrElse {
                                runCatching { buildLinks(pl, referer) }.getOrDefault(emptyList())
                            }
                        } finally {
                            sem.release()
                        }
                    }
                }.awaitAll().flatten()
            }
            val deduped = sources.distinctBy { it.url }
            EpisodeStreams(deduped, emptyList())
        } catch (_: Exception) {
            EpisodeStreams(emptyList(), emptyList())
        }
    }

    private suspend fun buildLinks(pl: PayloadLink, referer: String): List<StreamSource> {
        val kind = pl.kind.ifBlank { Servers.kindOf(pl.url) }
        val concrete: String
        val concreteKind: String
        if (pl.isGateway || GENXFM_REGEX.containsMatchIn(pl.url)) {
            val exp = NexdriveResolver.expand(pl.url.trimEnd('/') + "/", referer)
            if (exp.links.isEmpty()) {
                return listOf(gatewaySource(pl.url, kind, pl, referer))
            }
            if (pl.kind.isNotBlank() && pl.kind != Servers.GATE) {
                val pool = exp.links.filter { it.kind == pl.kind }
                if (pool.isEmpty()) return emptyList()
                val idx = pl.idx.coerceIn(0, pool.size - 1)
                concrete = pool[idx].url
                concreteKind = pool[idx].kind
            } else {
                val idx = pl.idx.coerceIn(0, exp.links.size - 1)
                concrete = exp.links[idx].url
                concreteKind = exp.links[idx].kind
            }
        } else {
            concrete = pl.url
            concreteKind = kind
        }

        return when (concreteKind) {
            Servers.GDRIVE -> {
                val direct = NexdriveResolver.resolveEmbed(concrete, referer)
                if (direct != null) {
                    listOf(
                        StreamSource(
                            url = direct,
                            quality = LinkNaming.qualityInt(pl.heading),
                            isM3u8 = direct.contains(".m3u8"),
                            serverName = "G-Drive",
                            headers = mapOf("Referer" to "https://fastdl.zip/"),
                        ),
                    )
                } else {
                    listOf(gatewaySource(concrete, concreteKind, pl, referer))
                }
            }
            Servers.VCLOUD -> {
                val streams = runCatching { VcloudResolver.resolve(concrete, referer) }
                    .getOrDefault(emptyList())
                if (streams.isEmpty()) {
                    listOf(gatewaySource(concrete, concreteKind, pl, referer))
                } else {
                    streams.map {
                        StreamSource(
                            url = it.url,
                            quality = LinkNaming.qualityInt(pl.heading),
                            isM3u8 = it.url.contains(".m3u8"),
                            serverName = "V-Cloud (${it.tag})",
                            headers = mapOf("Referer" to referer),
                        )
                    }
                }
            }
            Servers.ZIP -> listOf(gatewaySource(concrete, concreteKind, pl, referer))
            else -> {
                // Unknown concrete host: try the generic extractors, else hand
                // the link back as a download-only (browser) source.
                val viaExtractor = Extractors.resolve(concrete, referer)
                if (viaExtractor.sources.isNotEmpty()) viaExtractor.sources
                else listOf(gatewaySource(concrete, concreteKind, pl, referer))
            }
        }
    }

    /** A link the player cannot play directly: surfaced with quality 0 so the
     * UI can offer it as a download / open-in-browser source. */
    private fun gatewaySource(
        url: String,
        kind: String,
        pl: PayloadLink,
        referer: String,
    ): StreamSource = StreamSource(
        url = url,
        quality = 0,
        isM3u8 = url.contains(".m3u8"),
        serverName = Servers.labelFor(kind),
        headers = mapOf("Referer" to referer),
    )
}

// ---- payload (opaque episode id: JSON of the expanded download links) ----

private data class PayloadLink(
    val url: String,
    val kind: String = "",
    val heading: String = "",
    val idx: Int = 0,
    val isGateway: Boolean = true,
    val season: Int? = null,
    val episode: Int? = null,
)

private data class LinkPayload(
    val pageUrl: String,
    val links: List<PayloadLink>,
    val imdbId: String? = null,
) {
    fun toJson(): String {
        val arr = JSONArray()
        links.forEach {
            arr.put(
                JSONObject().put("u", it.url).put("k", it.kind).put("h", it.heading)
                    .put("i", it.idx).put("g", it.isGateway)
                    .put("s", it.season ?: JSONObject.NULL).put("e", it.episode ?: JSONObject.NULL),
            )
        }
        return JSONObject().put("page", pageUrl).put("links", arr)
            .put("imdb", imdbId ?: JSONObject.NULL).toString()
    }

    companion object {
        fun fromJson(data: String): LinkPayload? {
            val o = runCatching { JSONObject(data) }.getOrNull() ?: return null
            val arr = o.optJSONArray("links") ?: return null
            val links = (0 until arr.length()).mapNotNull { i ->
                val l = arr.optJSONObject(i) ?: return@mapNotNull null
                val u = l.optString("u").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                PayloadLink(
                    url = u,
                    kind = l.optString("k"),
                    heading = l.optString("h"),
                    idx = l.optInt("i", 0),
                    isGateway = l.optBoolean("g", true),
                    season = l.optInt("s", -1).takeIf { it > 0 },
                    episode = l.optInt("e", -1).takeIf { it > 0 },
                )
            }
            return LinkPayload(
                o.optString("page"),
                links,
                o.optString("imdb").takeIf { it.startsWith("tt") },
            )
        }
    }
}

// ---- server kinds (port of Servers in LinkNaming.kt) ----

private object Servers {
    const val GDRIVE = "G-Drive"
    const val VCLOUD = "V-Cloud"
    const val ZIP = "Batch/Zip"
    const val GATE = "Server"

    fun labelFor(kind: String): String = when (kind) {
        GDRIVE -> "G-Drive (Google Drive)"
        VCLOUD -> "V-Cloud"
        ZIP -> "Batch/Zip (Archive)"
        else -> "Vegamovies Server"
    }

    fun kindOf(url: String): String = when {
        url.contains("fastdl") -> GDRIVE
        url.contains("vcloud") -> VCLOUD
        url.contains("hubcloud") || url.contains("nexdrive") ||
            url.endsWith(".zip", ignoreCase = true) -> ZIP
        else -> GDRIVE
    }
}

// ---- link naming helpers (port of LinkNaming.kt, trimmed) ----

private object LinkNaming {
    fun qualityInt(heading: String?): Int {
        if (heading.isNullOrBlank()) return 0
        Regex("""(?i)\b(\d{3,4})p\b""").findAll(heading)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .maxOrNull()?.let { return it }
        if (Regex("""(?i)\b4K\b""").containsMatchIn(heading)) return 2160
        return 0
    }

    fun seasonEpisodeFrom(heading: String): Pair<Int?, Int?> {
        Regex("""(?i)\bS(\d{1,2})[\s.\-_]?E(?:P)?\s?0*(\d{1,3})\b""").find(heading)?.let {
            return it.groupValues[1].toIntOrNull() to it.groupValues[2].toIntOrNull()
        }
        Regex("""\b(\d{1,2})x(\d{2})\b""").find(heading)?.let {
            return it.groupValues[1].toIntOrNull() to it.groupValues[2].toIntOrNull()
        }
        val season = Regex("""(?i)season\s*(\d{1,2})""").find(heading)
            ?.groupValues?.get(1)?.toIntOrNull()
        val ep = Regex("""(?i)(?:episode|ep\.?|eps)\s*0*(\d{1,3})""").find(heading)
            ?.groupValues?.get(1)?.toIntOrNull()
        return season to ep
    }

    fun kindFromChip(chip: String): String = when {
        chip.contains("G-Direct", true) || chip.contains("G-Drive", true) ||
            chip.contains("Direct", true) -> Servers.GDRIVE
        chip.contains("V-Cloud", true) -> Servers.VCLOUD
        chip.contains("Batch", true) || chip.contains("Zip", true) ||
            chip.contains("ZiP", true) -> Servers.ZIP
        else -> ""
    }
}

// ---- nexdrive gateway resolver (port of NexdriveResolver.kt) ----

private data class Concrete(val kind: String, val url: String, val idx: Int)

private data class GatewayExpansion(val links: List<Concrete>) {
    companion object {
        val EMPTY = GatewayExpansion(emptyList())
    }
}

private object NexdriveResolver {
    val DIRECT_REGEX = Regex("""https://video-downloads\.googleusercontent\.com/\S+""")
    val FASTDL_REGEX = Regex(
        """https?://fastdl\.[a-z]{2,10}/embed(?:\.php)?\?download=[A-Za-z0-9_\-]+""",
        RegexOption.IGNORE_CASE,
    )
    val VCLOUD_REGEX = Regex(
        """https?://vcloud\.[a-z]{2,10}/[A-Za-z0-9_\-]{6,}""",
    )
    private val REURL_REGEX = Regex(
        """https?://fastdl\.[a-z]{2,10}/dl\.php\?link=(https?://[^\s"'<>)\\]+)""",
        RegexOption.IGNORE_CASE,
    )
    private val REURL_PARAM = Regex(
        """dl\.php\?link=([^"'<>\s\\]+)""",
        RegexOption.IGNORE_CASE,
    )

    private val cache = ConcurrentHashMap<String, GatewayExpansion>()
    private val directCache = ConcurrentHashMap<String, String>()

    suspend fun expand(gatewayUrl: String, pageReferer: String): GatewayExpansion {
        cache[gatewayUrl]?.let { return it }
        var html: String? = null
        for (attempt in 1..2) {
            html = runCatching {
                Http.get(
                    gatewayUrl,
                    mapOf(
                        "Referer" to pageReferer,
                        "Accept" to "text/html,application/xhtml+xml",
                    ),
                )
            }.getOrNull()
            if (!html.isNullOrBlank()) break
        }
        if (html.isNullOrBlank()) return GatewayExpansion.EMPTY
        val links = ArrayList<Concrete>(16)
        FASTDL_REGEX.findAll(html).forEachIndexed { i, m ->
            links += Concrete(Servers.GDRIVE, m.value, i)
        }
        VCLOUD_REGEX.findAll(html).forEachIndexed { i, m ->
            links += Concrete(Servers.VCLOUD, m.value, i)
        }
        val result = GatewayExpansion(links)
        if (result.links.isNotEmpty() && cache.size < 96) cache[gatewayUrl] = result
        return result
    }

    suspend fun resolveEmbed(embedUrl: String, referer: String): String? {
        directCache[embedUrl]?.let { return it.ifEmpty { null } }
        var body: String? = null
        for (attempt in 1..2) {
            body = runCatching {
                Http.get(
                    embedUrl,
                    mapOf(
                        "Referer" to referer,
                        "Accept" to "text/html,application/xhtml+xml",
                    ),
                )
            }.getOrNull()
            if (body?.let { extractDirect(it) } != null) break
        }
        val direct = body?.let { extractDirect(it) }
        if (directCache.size < 96) directCache[embedUrl] = direct ?: ""
        return direct
    }

    fun extractDirect(body: String): String? {
        REURL_REGEX.find(body)?.groupValues?.get(1)?.let { cand ->
            DIRECT_REGEX.find(cand)?.value?.let { return it }
        }
        REURL_PARAM.findAll(body).forEach { m ->
            val raw = m.groupValues[1]
            DIRECT_REGEX.find(raw)?.value?.let { return it }
            runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrNull()
                ?.let { DIRECT_REGEX.find(it)?.value }?.let { return it }
        }
        return DIRECT_REGEX.find(body)?.value
    }
}

// ---- vcloud resolver (port of VcloudResolver.kt) ----

private data class VcloudStream(val url: String, val tag: String)

private object VcloudResolver {
    private val ATOB_ATOB = Regex(
        """var\s+url\s*=\s*atob\s*\(\s*atob\s*\(\s*['"]([A-Za-z0-9+/=]{16,})['"]\s*\)\s*\)""",
    )
    private val BTN = Regex(
        """<a\b[^>]*href="([^"]+)"[^>]*>\s*(?:<[^>]+>\s*)*([^<]*(?:Download|Server|File)[^<]*)(?:<[^>]+>\s*)*</a>""",
        RegexOption.IGNORE_CASE,
    )
    private val STREAMABLE_HOST = Regex(
        """(r2\.cloudflarestorage\.com|r2\.dev)""",
        RegexOption.IGNORE_CASE,
    )

    private val directCache = ConcurrentHashMap<String, List<VcloudStream>>()

    suspend fun resolve(vcloudUrl: String, referer: String): List<VcloudStream> {
        directCache[vcloudUrl]?.let { return it }
        val page = runCatching {
            Http.get(vcloudUrl, mapOf("Referer" to referer))
        }.getOrNull() ?: return rememberEmpty(vcloudUrl)
        val tokenUrl = ATOB_ATOB.find(page)?.groupValues?.get(1)
            ?.let { decodeDoubleAtob(it) }
            ?.takeIf { it.startsWith("http") } ?: return rememberEmpty(vcloudUrl)
        val token = runCatching {
            Http.get(tokenUrl, mapOf("Referer" to vcloudUrl))
        }.getOrNull() ?: return rememberEmpty(vcloudUrl)
        val seen = ArrayList<VcloudStream>(4)
        val urls = LinkedHashSet<String>()
        BTN.findAll(token).forEach { m ->
            val raw = m.groupValues[1].trim()
            val label = m.groupValues[2].trim().let {
                Regex("""\[([^\]]+)]""").find(it)?.groupValues?.get(1)?.trim() ?: it
            }
            val abs = if (raw.startsWith("http")) raw else {
                val origin = Regex("""^https?://[^/]+""").find(tokenUrl)?.value
                    ?: return@forEach
                "$origin${if (raw.startsWith('/')) raw else "/$raw"}"
            }
            if (STREAMABLE_HOST.containsMatchIn(abs) && urls.add(abs)) {
                seen += VcloudStream(abs, label.take(20).ifBlank { "FSL" })
            }
        }
        if (seen.isNotEmpty() && directCache.size < 64) directCache[vcloudUrl] = seen
        return seen
    }

    private fun rememberEmpty(vcloudUrl: String): List<VcloudStream> {
        if (directCache.size < 64) directCache[vcloudUrl] = emptyList()
        return emptyList()
    }

    fun decodeDoubleAtob(blob: String): String? = try {
        val inner = String(decodeB64(blob.trim()))
        String(decodeB64(inner.trim()))
    } catch (_: Exception) {
        null
    }

    private fun decodeB64(s: String): ByteArray {
        val normalized = buildString {
            for (c in s) when (c) {
                '-' -> append('+')
                '_' -> append('/')
                else -> append(c)
            }
        }
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return android.util.Base64.decode(padded, android.util.Base64.DEFAULT)
    }
}
