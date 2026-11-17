package com.cinemora.app.providers

import android.util.Base64
import com.cinemora.app.CinemoraAppHolder
import com.cinemora.app.core.AnimeCard
import com.cinemora.app.core.AnimeDetails
import com.cinemora.app.core.AnimeProvider
import com.cinemora.app.core.AudioKind
import com.cinemora.app.core.CfBypass
import com.cinemora.app.core.DomainHelper
import com.cinemora.app.core.EpisodeItem
import com.cinemora.app.core.EpisodeStreams
import com.cinemora.app.core.HomeSection
import com.cinemora.app.core.Http
import com.cinemora.app.core.Lang
import com.cinemora.app.core.SeasonGroup
import com.cinemora.app.core.StreamSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

/**
 * RareAnimesIndia — Hindi-dub anime / cartoons source.
 *
 * Ported from the RareAnimesIndia CloudStream plugin by KSHITIJ8473/raghav
 * (GPL-3.0, https://github.com/KSHITIJ8473/raghav), rewritten against this
 * app's own [AnimeProvider] contract. No CloudStream code is used.
 *
 * What was ported: home categories, search, hub/season pages, direct episode
 * rows, archive pages, the Codedew redirect chain (RAINet.kt) and the
 * Argon / StreamBeta / PixelDrain / HubCloud / GoFile / MediaFire resolvers,
 * plus the JuicyCodes payload decoder.
 *
 * What was NOT ported, and why:
 * - RAICFBypass.kt (458 lines) + RAICFStore: replaced by the generic
 *   [CfBypass] WebView pass; clearance cookies land in [Http]'s shared jar.
 * - RAIResolverPopup.kt (the WebView "resolver popup" UI fallback): this app
 *   has no popup UI in providers; when the HTTP resolvers find nothing,
 *   [loadStreams] honestly returns no sources.
 * - DonationManager / settings fragments / plugin boilerplate: not applicable.
 *
 * Reliability caveat: the site blocks plain requests with a bare 403 and
 * only works after a WebView Cloudflare pass; even then some hosts in the
 * chain (Argon, HubCloud, GoFile) may still refuse datacentre IPs.
 * Every interface method still never throws — failures yield empty results.
 */

@Serializable
private data class RAIVariant(
    val n: String,
    val u: String,
    val k: String = "",
)

@Serializable
private data class RAIEpisodeData(
    val v: List<RAIVariant> = emptyList(),
)

@Serializable
private data class JuicyDataInner(
    val token: String? = null,
    val routes: JuicyRoutes? = null,
)

@Serializable
private data class JuicyRoutes(
    val links: String? = null,
    val ping: String? = null,
)

@Serializable
private data class JuicyDataWrapper(
    val data: JuicyDataInner? = null,
)

@Serializable
private data class ArgonQuality(
    val label: String? = null,
    val size: String? = null,
    val link: String? = null,
)

@Serializable
private data class ArgonLinks(
    val qualities: List<ArgonQuality>? = null,
)

@Serializable
private data class StreamBetaLink(
    val name: String? = null,
    val url: String? = null,
    @SerialName("stream_url") val streamUrl: String? = null,
)

@Serializable
private data class GoFileAccountData(
    val token: String? = null,
)

@Serializable
private data class GoFileAccount(
    val data: GoFileAccountData? = null,
)

@Serializable
private data class GoFileChild(
    val name: String? = null,
    val size: Long? = null,
    val link: String? = null,
)

@Serializable
private data class GoFileContentData(
    val children: Map<String, GoFileChild>? = null,
)

@Serializable
private data class GoFileContent(
    val status: String? = null,
    val data: GoFileContentData? = null,
)

/** Port of JuicyCodes.kt — decodes the obfuscated payloads on Argon pages. */
private object JuicyCodes {
    private val SYMBOLS = charArrayOf('`', '%', '-', '+', '*', '$', '!', '_', '^', '=')

    fun decode(payload: String): String? {
        return try {
            if (payload.length < 8) return null
            val salt = payload.takeLast(3)
            val offset = buildString {
                salt.forEach { append(it.code - 100) }
            }.toLongOrNull() ?: return null
            val body = payload.dropLast(3)
                .replace('_', '+')
                .replace('-', '/')
                .let { it + "=".repeat((4 - it.length % 4) % 4) }
            val decoded = String(Base64.decode(body, Base64.DEFAULT), Charsets.ISO_8859_1)
            val digits = StringBuilder()
            for (c in decoded) {
                val idx = SYMBOLS.indexOf(c)
                if (idx < 0) return null
                digits.append(idx)
            }
            val out = StringBuilder()
            var i = 0
            while (i + 4 <= digits.length) {
                val quad = digits.substring(i, i + 4).toLongOrNull() ?: return null
                val code = ((quad % 1000L) - offset).toInt()
                if (code < 9 || code > 126) return null
                out.append(code.toChar())
                i += 4
            }
            out.toString()
        } catch (_: Exception) {
            null
        }
    }

    fun decodeFromHtml(html: String): String? {
        val start = html.indexOf("_juicycodes(")
        if (start == -1) return null
        val end = html.indexOf(");", start)
        if (end == -1) return null
        val call = html.substring(start + 12, end)
        val sb = StringBuilder()
        var inQuote = false
        for (c in call) {
            when {
                c == '"' -> inQuote = !inQuote
                inQuote && c != '"' -> sb.append(c)
            }
        }
        if (sb.isEmpty()) return null
        return decode(sb.toString())
    }
}

private fun htmlUnescape(s: String): String {
    return if (!s.contains('&')) s else s
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&#039;", "'")
}

class RareAnimesProvider : AnimeProvider {
    override val id: String = "rareanimes"
    override val name: String = "RareAnimesIndia"
    override val lang: Lang = Lang.HINDI

    override val baseUrl: String = FALLBACK

    companion object {
        // Live 2026-11-18: rareanimes.fans 301-redirects to www.rareanimes.foo,
        // which answers 403 to plain requests (Cloudflare). Firebase still
        // lists https://www.rareanimes.fans/ as the current domain.
        private const val FALLBACK = "https://rareanimes.fans"
        private const val RAI_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36"
        private const val STORE_HOST = "store.animetoonhindi.com"
        private const val CODEDEW_HOST = "codedew.com"
        private const val ARGON_HOST = "argon.razorshell.space"
        private const val ARGON_AL = "en-US,en;q=0.9"
        private const val HUBCLOUD_HOST = "hubcloud.ist"
        private const val PIXELDRAIN_HOST = "pixeldrain.net"
        private const val GOFILE_WT_SECRET = "12af056dacea0b"
        private const val SOURCE = "Rare Toons India"
    }

    private var mainUrl: String = FALLBACK

    private suspend fun refreshBase() {
        DomainHelper.getDomain("rareanimes")?.trimEnd('/')?.takeIf { it.isNotBlank() }
            ?.let { mainUrl = it }
    }

    // ---------- low-level HTTP (RAINet.kt, minus the CF-store + popup dialog) ----------

    private data class RaiResp(
        val code: Int,
        val text: String,
        val headers: Headers,
        val url: String,
    )

    private fun hostOf(url: String): String {
        return try {
            java.net.URL(url).host ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun queryParam(url: String, name: String): String? {
        return try {
            val q = java.net.URL(url).query ?: return null
            q.split("&").mapNotNull {
                val kv = it.split("=", limit = 2)
                if (kv.size == 2 && kv[0] == name) URLDecoder.decode(kv[1], "UTF-8") else null
            }.firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private fun buildRequest(
        url: String,
        headers: Map<String, String>,
        method: String = "GET",
        body: okhttp3.RequestBody? = null,
    ): Request {
        val b = Request.Builder().url(url)
            .header("User-Agent", RAI_UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        headers.forEach { (k, v) -> b.header(k, v) }
        return if (method == "POST") b.post(body ?: ByteArray(0).toRequestBody(null)).build()
        else b.get().build()
    }

    private suspend fun execute(req: Request, allowRedirects: Boolean): RaiResp =
        withContext(Dispatchers.IO) {
            val client = if (allowRedirects) Http.client
            else Http.client.newBuilder().followRedirects(false).followSslRedirects(false).build()
            client.newCall(req).execute().use { res ->
                RaiResp(res.code, res.body?.string() ?: "", res.headers, res.request.url.toString())
            }
        }

    private fun isBlocked(resp: RaiResp): Boolean =
        resp.code == 403 || CfBypass.isChallengePage(resp.text)

    /** GET with one Cloudflare-bypass retry (WebView pass, then retry once). */
    private suspend fun raiGet(
        url: String,
        headers: Map<String, String> = emptyMap(),
        allowRedirects: Boolean = true,
    ): RaiResp {
        var resp = execute(buildRequest(url, headers), allowRedirects)
        if (isBlocked(resp)) {
            if (CfBypass.resolve(url, CinemoraAppHolder.context)) {
                resp = execute(buildRequest(url, headers), allowRedirects)
            }
        }
        return resp
    }

    private suspend fun raiPostJson(
        url: String,
        json: String,
        headers: Map<String, String> = emptyMap(),
    ): RaiResp {
        val body = json.toRequestBody("application/json".toMediaType())
        var resp = execute(buildRequest(url, headers, "POST", body), true)
        if (isBlocked(resp)) {
            if (CfBypass.resolve(url, CinemoraAppHolder.context)) {
                resp = execute(
                    buildRequest(url, headers, "POST", json.toRequestBody("application/json".toMediaType())),
                    true,
                )
            }
        }
        return resp
    }

    /** The site keeps moving domains (.mov -> .fans -> .foo); accept any of them. */
    private fun isSiteUrl(url: String): Boolean {
        val h = hostOf(url)
        if (h.isBlank()) return false
        if (h == hostOf(mainUrl)) return true
        return h == "rareanimes.fans" || h.endsWith(".rareanimes.fans") ||
            h == "rareanimes.foo" || h.endsWith(".rareanimes.foo") ||
            h == "rareanimes.mov" || h.endsWith(".rareanimes.mov")
    }

    // ---------- home / search ----------

    private data class PageEntry(val title: String, val url: String, val poster: String?)

    private fun parseListings(html: String, base: String): List<PageEntry> {
        val doc = Jsoup.parse(html, base)
        return doc.select("article").mapNotNull { art ->
            val link = art.selectFirst("h2.entry-title a, .entry-title a")?.attr("abs:href")
            if (link.isNullOrBlank() || !isSiteUrl(link)) return@mapNotNull null
            val title = art.selectFirst("h2.entry-title, .entry-title")?.text()?.trim()
                ?: return@mapNotNull null
            val poster = art.selectFirst("img")?.let { img ->
                val src = img.attr("src").ifBlank { img.attr("data-src") }
                when {
                    src.startsWith("//") -> "https:$src"
                    src.startsWith("http") -> src
                    else -> null
                }
            }
            PageEntry(title, link, poster)
        }.distinctBy { it.url }
    }

    private val categories = listOf(
        "hindi-dub" to "Hindi Dub",
        "cartoon-network" to "Cartoon Network",
        "disney-xd" to "Disney XD",
        "hungama" to "Hungama",
        "marvel-hq" to "Marvel HQ",
        "pokemon" to "Pokemon",
    )

    override suspend fun homeSections(): List<HomeSection> {
        return try {
            refreshBase()
            coroutineScope {
                categories.map { (slug, title) ->
                    async {
                        try {
                            val resp = raiGet("$mainUrl/category/$slug/")
                            val entries = parseListings(resp.text, resp.url)
                            HomeSection(
                                title,
                                id,
                                entries.map {
                                    AnimeCard(it.url, it.title, it.poster, id, lang)
                                },
                            )
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
            val encoded = URLEncoder.encode(query, "UTF-8")
            val results = mutableListOf<PageEntry>()
            for (pageNum in 1..3) {
                val url = if (pageNum == 1) "$mainUrl/?s=$encoded"
                else "$mainUrl/page/$pageNum/?s=$encoded"
                val resp = raiGet(url)
                val entries = parseListings(resp.text, resp.url)
                if (entries.isEmpty()) break
                results.addAll(entries)
            }
            results.distinctBy { it.url }.map {
                AnimeCard(it.url, it.title, it.poster, id, lang)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ---------- detail-page parsing ----------

    private val EPISODE_HEADER =
        Regex("""^(?:Episode|EP)\s*[.\-]?\s*(\d{1,3})\b""", RegexOption.IGNORE_CASE)
    private val SEASON_MARKER =
        Regex("""^\s*Seasons?\s*[-\u2013:.]?\s*(\d{1,2})\s*$""", RegexOption.IGNORE_CASE)
    private val HUB_SEASON_NUMBER =
        Regex("""\bSeasons?\s*[-\u2013:.]?\s*(\d{1,2})\b""", RegexOption.IGNORE_CASE)
    private val HUB_MOVIE_NUMBER =
        Regex("""\bMovie\s*[-\u2013:.]?\s*(\d{1,3})\b""", RegexOption.IGNORE_CASE)
    private val HUB_SELF_SLUG = Regex("""\ball-(?:movies|seasons?|series|episodes?)-""")

    private data class SourceRef(val label: String, val url: String, val kind: String, val contextKey: String)
    private data class ArchiveEpisode(val name: String, val variants: List<RAIVariant>)
    private data class ArchiveResult(
        val dubKey: String,
        val sourceName: String,
        val episodes: List<ArchiveEpisode>,
    )
    private data class DirectEpisode(
        val season: Int,
        val epNum: Int,
        val name: String,
        val variants: MutableList<RAIVariant>,
    )
    private data class DirectParse(val episodes: List<DirectEpisode>, val orphans: List<RAIVariant>)
    private data class HubLink(val kind: String, val number: Int?, val name: String, val url: String)
    private data class HubEpisode(
        val season: Int,
        val epNum: Int,
        val name: String,
        val variants: MutableList<RAIVariant>,
    )
    private data class PageContent(
        val sources: List<SourceRef>,
        val direct: DirectParse,
        val archives: List<ArchiveResult>,
        val defaultSeason: Int,
        val finalUrl: String = "",
        val seasonKnown: Boolean = false,
    )

    private fun dubKeyOf(text: String): String {
        val t = " $text ".lowercase()
        val isCn = t.contains("cartoon network") || t.contains("caroon network") ||
            t.contains("cn dub") || Regex("""[\s(\[-]cn[)\],\s–-]""").containsMatchIn(t)
        val isXd = t.contains("hungama") || t.contains("disney xd") || t.contains("marvel hq")
        if (isCn && isXd) return ""
        if (isCn) return "cn"
        if (isXd) return "xd"
        val langs = listOf(
            "tamil" to "ta", "telugu" to "te", "english" to "en",
            "bengali" to "be", "hindi" to "hi",
        ).filter { t.contains(it.first) }.map { it.second }.distinct()
        return when (langs.size) {
            1 -> langs.first()
            0 -> ""
            else -> "multi"
        }
    }

    private fun dubLabel(key: String): String {
        return when (key) {
            "cn" -> "Hindi (Cartoon Network)"
            "xd" -> "Hindi (Hungama XD)"
            "hi" -> "Hindi"
            "ta" -> "Tamil"
            "te" -> "Telugu"
            "en" -> "English"
            "be" -> "Bengali"
            "multi" -> "Multi Audio"
            else -> "Main"
        }
    }

    private fun extractSources(doc: Document): List<SourceRef> {
        val content = doc.selectFirst("div.entry-content") ?: return emptyList()
        val sources = mutableListOf<SourceRef>()
        var context = ""
        val all: List<Element> = content.allElements
        for (el in all) {
            when (el.tagName()) {
                "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    val t = el.text().trim()
                    if (t.isNotBlank()) context = t
                }
                "a" -> {
                    if (isProseRow(el)) continue
                    val href = el.attr("abs:href")
                    val text = el.text().trim()
                    if (isInfoLabel(text) || (text.isBlank() && isInfoLabel(context))) continue
                    when {
                        href.contains("$STORE_HOST/archives/") ->
                            sources.add(SourceRef(text.ifBlank { context }, href, "archive", dubKeyOf(context)))
                        href.contains("$CODEDEW_HOST/zipper/") ->
                            sources.add(SourceRef(text.ifBlank { context }, href, "zipper", dubKeyOf(context)))
                        href.contains("$CODEDEW_HOST/zipcloud/") ->
                            sources.add(SourceRef(text.ifBlank { context }, href, "zipcloud", dubKeyOf(context)))
                    }
                }
            }
        }
        return sources.distinctBy { it.url }
    }

    // bare links inside long paragraphs are seo keyword links, real buttons are always bracket wrapped
    private fun isProseRow(a: Element): Boolean {
        val row = blockRowOf(a)
        val tag = row.tagName().lowercase()
        if (tag == "p" || tag == "li") {
            if (row.text().length <= 80) return false
            val html = row.html()
            val total = Regex("""<a\s""").findAll(html).count()
            val wrapped = Regex("""\[\s*<a\s""").findAll(html).count()
            return total == 0 || wrapped != total
        }
        return false
    }

    private fun blockRowOf(a: Element): Element {
        var cur = a
        while (true) {
            val parent = cur.parent() ?: return cur
            when (parent.tagName().lowercase()) {
                "p", "li", "h1", "h2", "h3", "h4", "h5", "h6" -> return parent
            }
            cur = parent
        }
    }

    private fun archiveSourceName(title: String, fallback: String): String {
        val t = title.lowercase()
        return when {
            t.contains("multiquality") -> "WatchMultiQuality"
            t.contains("hubcloud") -> "HubCloud"
            t.contains("watchnow") -> "WatchNow"
            else -> fallback.ifBlank { "Watch" }
        }
    }

    // linker pages sometimes label every row with the button name instead of the episode
    private fun isSourceLabel(name: String): Boolean {
        return when (name.trim().lowercase()) {
            "multiquality", "watchmultiquality", "watchmultquality", "quickmulti", "watchnow",
            "watchbeta", "hubcloud", "gofile", "mega", "zip", "download", "watch", "dlbeta" -> true
            else -> false
        }
    }

    // the site hides dead ad redirects behind info headings, they never resolve to a file
    private fun isInfoLabel(name: String): Boolean {
        return when (name.trim().lowercase().removeSuffix(":")) {
            "movie info", "movies info", "series info", "cartoon series info", "anime info" -> true
            else -> false
        }
    }

    private fun parseArchiveEpisodes(html: String, base: String): List<Pair<String, String>> {
        val doc = Jsoup.parse(html, base)
        val content = doc.selectFirst("div.entry-content") ?: doc.body() ?: return emptyList()
        return content.select("a[href]").mapNotNull { a ->
            val href = a.attr("abs:href")
            val text = a.text().trim()
            val isCodedew = href.contains("$CODEDEW_HOST/zipper/") ||
                href.contains("$CODEDEW_HOST/zipcloud/")
            if (isCodedew && text.isNotBlank()) text to href else null
        }.distinctBy { it.second }
    }

    private fun languageFromText(text: String): String {
        val t = text.lowercase()
        return when {
            t.contains("telugu") -> "Telugu"
            t.contains("tamil") -> "Tamil"
            t.contains("bengali") -> "Bengali"
            t.contains("english") || t.contains(" eng") -> "English"
            t.contains("hindi") -> "Hindi"
            else -> ""
        }
    }

    private val ROW_BLOCK_TAGS = setOf(
        "p", "div", "ul", "ol", "li", "table", "thead", "tbody", "tr", "td", "th",
        "blockquote", "section", "article", "figure",
        "h1", "h2", "h3", "h4", "h5", "h6",
    )

    // posts wrap their episode rows in arbitrary containers, the parser needs the rows flat
    private fun contentRows(content: Element): List<Element> {
        val rows = mutableListOf<Element>()
        fun walk(el: Element) {
            for (child in el.children()) {
                if (!ROW_BLOCK_TAGS.contains(child.tagName())) {
                    rows.add(child)
                    continue
                }
                if (child.children().any { ROW_BLOCK_TAGS.contains(it.tagName()) }) {
                    walk(child)
                } else {
                    rows.add(child)
                }
            }
        }
        walk(content)
        return rows
    }

    private fun parseDirectEpisodes(doc: Document, defaultSeason: Int): DirectParse {
        val content = doc.selectFirst("div.entry-content") ?: return DirectParse(emptyList(), emptyList())
        val episodes = mutableListOf<DirectEpisode>()
        val orphans = mutableListOf<RAIVariant>()
        var current: DirectEpisode? = null
        var sectionContext = ""
        var sectionKey = ""
        var sectionSeason: Int? = null
        val usedOrphanNames = mutableSetOf<String>()
        for (el in contentRows(content)) {
            val tag = el.tagName().lowercase()
            if (tag == "hr") continue
            val text = el.text().trim()
            if (text.isBlank()) continue

            val headerMatch = EPISODE_HEADER.find(text)
            val links = el.select(
                "a[href*=codedew.com/zipper/], a[href*=codedew.com/zipcloud/]",
            ).filter { !isProseRow(it) }

            var startedEpisode = false
            if (headerMatch != null) {
                val epNum = headerMatch.groupValues[1].toIntOrNull()
                if (epNum != null) {
                    current = DirectEpisode(sectionSeason ?: defaultSeason, epNum, text, mutableListOf())
                    episodes.add(current)
                    startedEpisode = true
                    if (links.isEmpty()) continue
                }
            }

            if (links.isEmpty()) {
                val marker = SEASON_MARKER.find(text)?.groupValues?.get(1)?.toIntOrNull()
                val isHeading = tag.startsWith("h") && el.select("a").isEmpty()
                if (isHeading || marker != null) {
                    if (marker != null) sectionSeason = marker
                    sectionContext = text
                    sectionKey = dubKeyOf(text)
                    if (!startedEpisode) current = null
                }
                continue
            }

            val langSpan = el.selectFirst("span:not(.ra-serv-txt)")?.text()
            val lang = languageFromText(langSpan ?: text.substringBefore("["))
            for (a in links) {
                val href = a.attr("abs:href")
                if (href.isBlank()) continue
                val label = a.text().trim()
                if (label.isBlank() || isInfoLabel(label)) continue
                val target = current
                if (target != null) {
                    val variantName = if (lang.isBlank()) label else "$lang $label"
                    target.variants.add(RAIVariant(variantName, href, dubKeyOf(lang.ifBlank { sectionContext })))
                } else {
                    val effectiveLang = if (lang.isBlank()) languageFromText(sectionContext) else lang
                    val base = if (effectiveLang.isBlank()) label else "$effectiveLang $label"
                    val variantName = if (usedOrphanNames.add(base)) base
                    else if (sectionContext.isBlank()) base
                    else "$sectionContext $base"
                    orphans.add(RAIVariant(variantName, href, sectionKey))
                }
            }
        }
        return DirectParse(episodes, orphans)
    }

    private suspend fun loadArchive(
        url: String,
        sourceLabel: String,
        contextKey: String,
        depth: Int,
    ): ArchiveResult? {
        if (depth > 2) return null
        return try {
            val response = raiGet(url)
            val html = response.text
            val doc = Jsoup.parse(html, response.url)
            val docTitle = doc.title()
            val dubKey = dubKeyOf(docTitle).ifBlank { contextKey }
            val sourceName = archiveSourceName(docTitle, sourceLabel)
            val eps = parseArchiveEpisodes(html, response.url)

            when {
                eps.size > 1 -> ArchiveResult(
                    dubKey,
                    sourceName,
                    eps.map { (name, u) -> ArchiveEpisode(name, listOf(RAIVariant(sourceName, u, dubKey))) },
                )
                eps.size == 1 -> {
                    val single = eps.first()
                    val target = try {
                        codedewResolver.resolve(single.second)
                    } catch (_: Exception) {
                        null
                    }
                    if (target is ResolvedTarget.Archive) {
                        loadArchive(target.url, sourceLabel, dubKey, depth + 1)
                    } else {
                        ArchiveResult(
                            dubKey,
                            sourceName,
                            listOf(ArchiveEpisode(single.first, listOf(RAIVariant(sourceName, single.second, dubKey)))),
                        )
                    }
                }
                else -> {
                    val codedew = doc.selectFirst(
                        "div.entry-content a[href*=codedew.com]",
                    )?.attr("abs:href") ?: return null
                    val target = try {
                        codedewResolver.resolve(codedew)
                    } catch (_: Exception) {
                        null
                    }
                    when (target) {
                        is ResolvedTarget.Archive -> loadArchive(target.url, sourceLabel, dubKey, depth + 1)
                        is ResolvedTarget.Argon,
                        is ResolvedTarget.ArgonDownload,
                        is ResolvedTarget.HubCloud,
                        is ResolvedTarget.PixelDrain ->
                            ArchiveResult(dubKey, sourceName, listOf(ArchiveEpisode("Full", listOf(RAIVariant(sourceName, codedew, dubKey)))))
                        else -> null
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseEpisodeNumber(name: String): Pair<Int?, Int>? {
        val m = Regex("""S\s*(\d{1,2})\s*E\s*(\d{1,3})""", RegexOption.IGNORE_CASE).find(name)
        if (m != null) {
            val s = m.groupValues[1].toIntOrNull() ?: return null
            val e = m.groupValues[2].toIntOrNull() ?: return null
            return s to e
        }
        val sn = HUB_SEASON_NUMBER.find(name)?.groupValues?.get(1)?.toIntOrNull()
        val e2 = Regex("""\bE\s*(\d{1,3})\b""", RegexOption.IGNORE_CASE).find(name)
            ?: Regex("""\bEpisode\s+(\d{1,3})\b""", RegexOption.IGNORE_CASE).find(name)
        if (e2 != null) {
            val e = e2.groupValues[1].toIntOrNull() ?: return null
            return sn to e
        }
        return null
    }

    private fun jsonEscape(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", " ")
            .replace("\r", " ")
            .replace("\t", " ")
    }

    /** Episode ids are a JSON blob of the episode's link variants (as in the plugin). */
    private fun variantsJson(variants: List<RAIVariant>): String {
        return """{"v":[""" + variants.joinToString(",") {
            """{"n":"${jsonEscape(it.n)}","u":"${jsonEscape(it.u)}","k":"${jsonEscape(it.k)}"}"""
        } + """]}"""
    }

    private fun extractMeta(doc: Document): Map<String, String> {
        val content = doc.selectFirst("div.entry-content")?.text() ?: ""
        val map = mutableMapOf<String, String>()
        Regex("""Season\s*No[.:]?\s*(\d{1,2})""", RegexOption.IGNORE_CASE).find(content)?.let {
            map["season"] = it.groupValues[1]
        }
        Regex("""Release\s*Year[.:]?\s*(\d{4})""", RegexOption.IGNORE_CASE).find(content)?.let {
            map["year"] = it.groupValues[1]
        }
        Regex("""Episodes[.:]?\s*(\d{1,4})""", RegexOption.IGNORE_CASE).find(content)?.let {
            map["episodes"] = it.groupValues[1]
        }
        return map
    }

    private fun isInternalPostLink(href: String): Boolean {
        if (href.isBlank() || !isSiteUrl(href)) return false
        if (href.contains("discord") || href.contains("?s=") || href.contains("#")) return false
        return try {
            val path = java.net.URL(href).path ?: return false
            if (path.isBlank() || path == "/") return false
            if (path.startsWith("/hindi/category/") || path.startsWith("/category/") ||
                path.startsWith("/tag/") || path.startsWith("/author/") ||
                path.startsWith("/page/") || path.startsWith("/home")
            ) return false
            if (HUB_SELF_SLUG.containsMatchIn(path)) return false
            path.length > 8
        } catch (_: Exception) {
            false
        }
    }

    private fun parseHubHeadings(doc: Document): List<HubLink> {
        val content = doc.selectFirst("div.entry-content") ?: return emptyList()
        val out = mutableListOf<HubLink>()
        val seen = mutableSetOf<String>()
        for (h in content.select("h2, h3, h4, li")) {
            val candidates = h.select("a[href]").mapNotNull { a ->
                val href = a.attr("abs:href")
                if (isInternalPostLink(href)) a else null
            }
            if (candidates.isEmpty()) continue
            val isListItem = h.tagName() == "li"
            // list rows carry one entry each, anything else is a menu or a tag cloud
            if (isListItem && (candidates.size != 1)) continue
            val link = candidates.firstOrNull { it.text().isNotBlank() } ?: candidates.first()
            val href = link.attr("abs:href")
            if (!seen.add(href)) continue
            val headingText = h.text().trim()
            val text = link.text().trim().ifBlank { headingText }
            if (text.isBlank()) continue
            if (isListItem && text.length < 8) continue
            val seasonNumber = HUB_SEASON_NUMBER.find(text)?.groupValues?.get(1)?.toIntOrNull()
                ?: HUB_SEASON_NUMBER.find(headingText)?.groupValues?.get(1)?.toIntOrNull()
            val movieNumber = if (seasonNumber == null) {
                HUB_MOVIE_NUMBER.find(text)?.groupValues?.get(1)?.toIntOrNull()
                    ?: HUB_MOVIE_NUMBER.find(headingText)?.groupValues?.get(1)?.toIntOrNull()
            } else null
            val kind = when {
                seasonNumber != null -> "season"
                movieNumber != null -> "movie"
                else -> "extra"
            }
            out.add(HubLink(kind, seasonNumber ?: movieNumber, text, href))
        }
        return out
    }

    private suspend fun parsePageContent(doc: Document, defaultSeason: Int): PageContent {
        val sources = extractSources(doc)
        val direct = parseDirectEpisodes(doc, defaultSeason)
        val archives = mutableListOf<ArchiveResult>()
        for (src in sources.filter { it.kind == "archive" }) {
            loadArchive(src.url, src.label, src.contextKey, 0)?.let { archives.add(it) }
        }
        return PageContent(sources, direct, archives, defaultSeason)
    }

    private fun buildSubPageEpisodes(content: PageContent): List<HubEpisode> {
        val out = LinkedHashMap<String, HubEpisode>()
        val dubKeys = mutableSetOf<String>()
        content.archives.forEach { if (it.dubKey.isNotBlank()) dubKeys.add(it.dubKey) }
        content.direct.episodes.forEach { ep -> ep.variants.forEach { if (it.k.isNotBlank()) dubKeys.add(it.k) } }
        val multiDub = dubKeys.size >= 2

        fun add(season: Int, epNum: Int, name: String, variant: RAIVariant) {
            val key = "$season:$epNum"
            val entry = out.getOrPut(key) { HubEpisode(season, epNum, name, mutableListOf()) }
            if (entry.variants.none { it.u == variant.u }) entry.variants.add(variant)
        }

        for (ep in content.direct.episodes) {
            val parsed = parseEpisodeNumber(ep.name)
            val season = parsed?.first ?: ep.season
            val epNum = parsed?.second ?: ep.epNum
            for (v in ep.variants) {
                val name = if (multiDub && v.k.isNotBlank()) "${v.n} ${dubLabel(v.k)}" else v.n
                add(season, epNum, ep.name, RAIVariant(name, v.u, v.k))
            }
        }

        for (archive in content.archives) {
            val suffix = if (multiDub && archive.dubKey.isNotBlank()) " ${dubLabel(archive.dubKey)}" else ""
            archive.episodes.forEachIndexed { idx, ep ->
                val parsed = parseEpisodeNumber(ep.name)
                val season = parsed?.first ?: content.defaultSeason
                val epNum = parsed?.second ?: (idx + 1)
                val epName = if (isSourceLabel(ep.name)) "" else ep.name
                val used = mutableSetOf<String>()
                for (v in ep.variants) {
                    var name = "${v.n}$suffix"
                    if (!used.add(name)) {
                        var i = 2
                        while (!used.add("$name $i")) i++
                        name = "$name $i"
                    }
                    add(season, epNum, epName, RAIVariant(name, v.u, archive.dubKey))
                }
            }
        }

        return out.values.toList()
    }

    private fun buildSubPageVariants(content: PageContent): List<RAIVariant> {
        val variants = mutableListOf<RAIVariant>()
        val dubKeys = mutableSetOf<String>()
        content.archives.forEach { if (it.dubKey.isNotBlank()) dubKeys.add(it.dubKey) }
        content.direct.orphans.forEach { if (it.k.isNotBlank()) dubKeys.add(it.k) }
        content.direct.episodes.forEach { ep -> ep.variants.forEach { if (it.k.isNotBlank()) dubKeys.add(it.k) } }
        val multiDub = dubKeys.size >= 2
        for (archive in content.archives) {
            val suffix = if (multiDub && archive.dubKey.isNotBlank()) " ${dubLabel(archive.dubKey)}" else ""
            archive.episodes.forEachIndexed { idx, ep ->
                ep.variants.forEach { v ->
                    if (variants.none { it.u == v.u }) {
                        val label = when {
                            archive.episodes.size == 1 -> "${v.n}$suffix"
                            isSourceLabel(ep.name) -> "${v.n}$suffix ${idx + 1}"
                            else -> "${v.n}$suffix ${ep.name}"
                        }
                        variants.add(RAIVariant(label.trim(), v.u, archive.dubKey))
                    }
                }
            }
        }
        content.direct.orphans.forEach { v ->
            if (variants.none { it.u == v.u }) {
                val label = if (multiDub && v.k.isNotBlank()) "${v.n} ${dubLabel(v.k)}" else v.n
                variants.add(RAIVariant(label, v.u, v.k))
            }
        }
        content.sources.filter { it.kind != "archive" }.forEach { src ->
            if (variants.none { it.u == src.url }) {
                variants.add(RAIVariant(src.label, src.url, src.contextKey))
            }
        }
        if (content.archives.isEmpty()) {
            content.sources.filter { it.kind == "archive" }.forEach { src ->
                if (variants.none { it.u == src.url }) {
                    variants.add(RAIVariant(src.label, src.url, src.contextKey))
                }
            }
        }
        return variants
    }

    private suspend fun loadHubSubPage(link: HubLink): PageContent? {
        return try {
            val response = raiGet(link.url)
            val doc = Jsoup.parse(response.text, response.url)
            if (isHubPage(doc)) {
                null
            } else {
                val subMeta = extractMeta(doc)
                val subDefault = subMeta["season"]?.toIntOrNull() ?: 1
                val content = parsePageContent(doc, subDefault)
                content.copy(
                    finalUrl = response.url,
                    seasonKnown = pageHasSeasonIdentity(doc, content, subMeta),
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun pageHasSeasonIdentity(doc: Document, content: PageContent, meta: Map<String, String>): Boolean {
        if (meta.containsKey("season")) return true
        for (archive in content.archives) {
            for (ep in archive.episodes) {
                if (parseEpisodeNumber(ep.name)?.first != null) return true
            }
        }
        for (ep in content.direct.episodes) {
            if (parseEpisodeNumber(ep.name)?.first != null) return true
        }
        val contentEl = doc.selectFirst("div.entry-content") ?: return false
        return contentEl.select("h1,h2,h3,h4,h5,h6,p").any { SEASON_MARKER.matches(it.text().trim()) }
    }

    private fun isHubPage(doc: Document): Boolean {
        if (parseHubHeadings(doc).count { it.kind == "season" || it.kind == "movie" } >= 2) return true
        val probe = parseDirectEpisodes(doc, 1)
        if (probe.episodes.isNotEmpty() || probe.orphans.isNotEmpty()) return false
        if (extractSources(doc).isNotEmpty()) return false
        return parseParagraphHubLinks(doc).size >= 4
    }

    private fun parseParagraphHubLinks(doc: Document): List<HubLink> {
        val content = doc.selectFirst("div.entry-content") ?: return emptyList()
        val out = mutableListOf<HubLink>()
        val seen = mutableSetOf<String>()
        for (p in content.select("p")) {
            val candidates = p.select("a[href]").filter { isInternalPostLink(it.attr("abs:href")) }
            if (candidates.size != 1) continue
            val a = candidates.first()
            val text = a.text().trim()
            if (text.length < 8) continue
            val rowText = p.text().trim()
            val rest = rowText.replaceFirst(text, "").trim()
            if (rest.length > 15) continue
            val href = a.attr("abs:href")
            if (!seen.add(href)) continue
            val seasonNumber = HUB_SEASON_NUMBER.find(text)?.groupValues?.get(1)?.toIntOrNull()
                ?: HUB_SEASON_NUMBER.find(rowText)?.groupValues?.get(1)?.toIntOrNull()
            val movieNumber = if (seasonNumber == null) {
                HUB_MOVIE_NUMBER.find(text)?.groupValues?.get(1)?.toIntOrNull()
                    ?: HUB_MOVIE_NUMBER.find(rowText)?.groupValues?.get(1)?.toIntOrNull()
            } else null
            val kind = when {
                seasonNumber != null -> "season"
                movieNumber != null -> "movie"
                else -> "extra"
            }
            out.add(HubLink(kind, seasonNumber ?: movieNumber, text, href))
        }
        return out
    }

    private fun seasonEpisodesForLink(link: HubLink, content: PageContent): List<HubEpisode> {
        val episodes = buildSubPageEpisodes(content)
        val target = link.number ?: return episodes
        // hub headings go stale when a post moves, the post itself is the safer source
        if (content.seasonKnown) return episodes
        return episodes.map { HubEpisode(target, it.epNum, it.name, it.variants) }
    }

    private suspend fun buildHubResponse(
        title: String,
        url: String,
        poster: String?,
        year: Int?,
        plot: String?,
        genres: List<String>,
        links: List<HubLink>,
    ): AnimeDetails? {
        val capped = links.take(40)
        val results = HashMap<Int, PageContent>(capped.size)

        for (chunkStart in capped.indices step 6) {
            val chunk = capped.withIndex().filter { it.index >= chunkStart && it.index < chunkStart + 6 }
            coroutineScope {
                chunk.map { (index, link) ->
                    async { index to loadHubSubPage(link) }
                }.awaitAll()
            }.forEach { (index, content) ->
                if (content != null) results[index] = content
            }
        }

        val epMap = LinkedHashMap<String, HubEpisode>()
        fun addEp(season: Int, epNum: Int, name: String, variant: RAIVariant) {
            val key = "$season:$epNum"
            val entry = epMap.getOrPut(key) { HubEpisode(season, epNum, name, mutableListOf()) }
            if (entry.variants.none { it.u == variant.u }) entry.variants.add(variant)
        }

        val seasonNames = sortedMapOf<Int, String>()
        val movieEntries = mutableListOf<Pair<String, List<RAIVariant>>>()
        val extraSeries = mutableListOf<Pair<String, List<HubEpisode>>>()
        val filledSeasons = HashMap<Int, String>()
        val loadedUrls = mutableSetOf<String>()

        for ((index, link) in capped.withIndex()) {
            val content = results[index] ?: continue
            val finalUrl = content.finalUrl.ifBlank { link.url }
            val twin = !loadedUrls.add(finalUrl)
            if (link.kind == "season") {
                val episodes = seasonEpisodesForLink(link, content)
                if (episodes.isEmpty()) {
                    val variants = buildSubPageVariants(content)
                    if (variants.isNotEmpty()) movieEntries.add(link.name to variants)
                    continue
                }
                val landed = episodes.map { it.season }.toSet()
                val conflict = landed.any { filledSeasons.containsKey(it) && filledSeasons[it] != finalUrl }
                when {
                    twin -> {
                        if (link.number != null && landed.contains(link.number) &&
                            !seasonNames.containsKey(link.number)
                        ) {
                            seasonNames[link.number] = link.name
                        }
                    }
                    conflict -> extraSeries.add(link.name to episodes)
                    else -> {
                        episodes.forEach { e ->
                            e.variants.forEach { v -> addEp(e.season, e.epNum, e.name, v) }
                        }
                        landed.forEach { filledSeasons[it] = finalUrl }
                        if (link.number != null && landed.contains(link.number)) {
                            seasonNames[link.number] = link.name
                        }
                    }
                }
            } else {
                if (twin) continue
                val episodes = buildSubPageEpisodes(content)
                // movie linker rows are server buttons for one file, only numbered rows make a real episode list
                val asSeries = episodes.size > 1 &&
                    (link.kind != "movie" ||
                        episodes.any { it.name.isNotBlank() && parseEpisodeNumber(it.name) != null })
                if (asSeries) {
                    extraSeries.add(link.name to episodes)
                } else {
                    val variants = buildSubPageVariants(content)
                    if (variants.isNotEmpty()) movieEntries.add(link.name to variants)
                }
            }
        }

        val realSeasons = epMap.values.map { it.season }.toSortedSet()
        var nextSeason = realSeasons.maxOrNull() ?: 0
        val moviesSeason = if (movieEntries.isNotEmpty()) {
            nextSeason += 1
            nextSeason
        } else 0

        val finalEpisodes = epMap.values.toMutableList()
        movieEntries.forEachIndexed { idx, entry ->
            finalEpisodes.add(HubEpisode(moviesSeason, idx + 1, entry.first, entry.second.toMutableList()))
        }
        extraSeries.forEach { (name, eps) ->
            val internal = eps.map { it.season }.distinct().sorted()
            if (internal.size > 1) {
                internal.forEachIndexed { i, s ->
                    nextSeason += 1
                    seasonNames[nextSeason] = "$name - Season ${i + 1}"
                    eps.filter { it.season == s }.forEach { e ->
                        finalEpisodes.add(HubEpisode(nextSeason, e.epNum, e.name, e.variants))
                    }
                }
            } else {
                nextSeason += 1
                seasonNames[nextSeason] = name
                eps.forEach { e ->
                    finalEpisodes.add(HubEpisode(nextSeason, e.epNum, e.name, e.variants))
                }
            }
        }

        if (finalEpisodes.isEmpty()) return null

        finalEpisodes.sortWith(compareBy({ it.season }, { it.epNum }))
        if (movieEntries.isNotEmpty()) {
            seasonNames[moviesSeason] = "Movies"
        }
        val seasons = finalEpisodes.groupBy { it.season }.entries.sortedBy { it.key }.map { (num, eps) ->
            SeasonGroup(
                season = num,
                name = seasonNames[num] ?: "Season $num",
                episodes = eps.sortedBy { it.epNum }.map { e ->
                    EpisodeItem(
                        id = variantsJson(e.variants),
                        number = e.epNum,
                        title = e.name.ifBlank { null },
                        season = e.season,
                    )
                },
            )
        }
        return AnimeDetails(
            id = url,
            title = title,
            posterUrl = poster,
            bannerUrl = poster,
            description = plot,
            genres = genres,
            status = null,
            year = year,
            rating = null,
            seasons = seasons,
            providerId = this.id,
            lang = lang,
            audioKinds = setOf(AudioKind.DUB),
        )
    }

    private fun buildNormalResponse(
        title: String,
        url: String,
        poster: String?,
        year: Int?,
        plot: String?,
        genres: List<String>,
        content: PageContent,
    ): AnimeDetails? {
        val sources = content.sources
        val direct = content.direct
        val archiveResults = content.archives
        val defaultSeason = content.defaultSeason

        data class EpEntry(
            val seasonIdx: Int,
            val realSeason: Int,
            val epNum: Int,
            val name: String,
            val variants: MutableList<RAIVariant>,
        )

        val dubOrder = mutableListOf<String>()
        fun orderKey(k: String) {
            if (k.isNotBlank() && !dubOrder.contains(k)) dubOrder.add(k)
        }
        archiveResults.forEach { orderKey(it.dubKey) }
        direct.episodes.forEach { ep -> ep.variants.forEach { orderKey(it.k) } }
        val splitDubs = dubOrder.size >= 2

        val epMap = LinkedHashMap<String, EpEntry>()
        val usedVariantNames = mutableSetOf<String>()
        var anyNumbered = false

        fun addVariant(seasonIdx: Int, season: Int, epNum: Int, name: String, variant: RAIVariant) {
            val key = "$seasonIdx:$season:$epNum"
            val entry = epMap.getOrPut(key) { EpEntry(seasonIdx, season, epNum, name, mutableListOf()) }
            if (entry.variants.none { it.u == variant.u }) entry.variants.add(variant)
        }

        for (ep in direct.episodes) {
            anyNumbered = true
            val keys = ep.variants.map { it.k }.filter { it.isNotBlank() }.distinct()
            val groups = if (keys.isEmpty()) listOf("") else keys
            for (g in groups) {
                val idx = if (splitDubs) dubOrder.indexOf(g).let { if (it >= 0) it else 0 } else 0
                ep.variants.filter { it.k == g || (g.isEmpty() && it.k.isBlank()) }.forEach { v ->
                    addVariant(idx, ep.season, ep.epNum, ep.name, v)
                }
            }
        }

        for (archive in archiveResults) {
            var vName = archive.sourceName
            if (!usedVariantNames.add("${archive.dubKey}:$vName")) {
                var i = 2
                while (!usedVariantNames.add("${archive.dubKey}:$vName $i")) i++
                vName = "$vName $i"
            }
            archive.episodes.forEachIndexed { idx, ep ->
                val parsed = parseEpisodeNumber(ep.name)
                if (parsed != null) anyNumbered = true
                val season = parsed?.first ?: defaultSeason
                val epNum = parsed?.second ?: (idx + 1)
                val epName = if (isSourceLabel(ep.name)) "" else ep.name
                val idx0 = if (splitDubs) dubOrder.indexOf(archive.dubKey).let { if (it >= 0) it else 0 } else 0
                ep.variants.forEach { v ->
                    addVariant(idx0, season, epNum, epName, RAIVariant(vName, v.u, archive.dubKey))
                }
            }
        }

        if (!anyNumbered) {
            // Movie: one "episode" holding every link variant.
            val variants = mutableListOf<RAIVariant>()
            for (archive in archiveResults) {
                archive.episodes.forEachIndexed { idx, ep ->
                    ep.variants.forEach { v ->
                        if (variants.none { it.u == v.u }) {
                            val label = when {
                                archive.episodes.size == 1 -> v.n
                                isSourceLabel(ep.name) -> "${v.n} ${idx + 1}"
                                else -> "${v.n} ${ep.name}"
                            }
                            variants.add(RAIVariant(label, v.u, v.k))
                        }
                    }
                }
            }
            direct.orphans.forEach { v ->
                if (variants.none { it.u == v.u }) variants.add(v)
            }
            sources.filter { it.kind != "archive" }.forEach { src ->
                if (variants.none { it.u == src.url }) {
                    variants.add(RAIVariant(src.label, src.url, src.contextKey))
                }
            }
            if (archiveResults.isEmpty()) {
                sources.filter { it.kind == "archive" }.forEach { src ->
                    if (variants.none { it.u == src.url }) {
                        variants.add(RAIVariant(src.label, src.url, src.contextKey))
                    }
                }
            }
            if (variants.isEmpty()) return null
            return AnimeDetails(
                id = url,
                title = title,
                posterUrl = poster,
                bannerUrl = poster,
                description = plot,
                genres = genres,
                status = null,
                year = year,
                rating = null,
                seasons = listOf(
                    SeasonGroup(
                        season = 1,
                        name = "Movie",
                        episodes = listOf(
                            EpisodeItem(
                                id = variantsJson(variants),
                                number = 1,
                                title = title,
                                season = 1,
                            ),
                        ),
                    ),
                ),
                providerId = this.id,
                lang = lang,
                audioKinds = setOf(AudioKind.DUB),
            )
        }

        val episodes = epMap.values.sortedWith(
            compareBy({ it.seasonIdx }, { it.realSeason }, { it.epNum }),
        )
        if (episodes.isEmpty()) return null
        val seasons = episodes.groupBy {
            if (splitDubs) it.seasonIdx + 1 else it.realSeason
        }.entries.sortedBy { it.key }.map { (num, eps) ->
            val name = if (splitDubs) {
                val idx = num - 1
                dubOrder.getOrNull(idx)?.let { dubLabel(it) } ?: "Season $num"
            } else "Season $num"
            SeasonGroup(
                season = num,
                name = name,
                episodes = eps.sortedBy { it.epNum }.map { e ->
                    EpisodeItem(
                        id = variantsJson(e.variants),
                        number = e.epNum,
                        title = e.name.ifBlank { null },
                        season = num,
                    )
                },
            )
        }
        return AnimeDetails(
            id = url,
            title = title,
            posterUrl = poster,
            bannerUrl = poster,
            description = plot,
            genres = genres,
            status = null,
            year = year,
            rating = null,
            seasons = seasons,
            providerId = this.id,
            lang = lang,
            audioKinds = setOf(AudioKind.DUB),
        )
    }

    override suspend fun loadDetails(id: String): AnimeDetails? {
        return try {
            refreshBase()
            val response = raiGet(id)
            val doc = Jsoup.parse(response.text, response.url)

            val title = doc.selectFirst("h1.entry-title, h1")?.text()?.trim()
                ?.takeIf { it.isNotBlank() } ?: return null
            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst(".herald-post-thumbnail img, img.wp-post-image")?.attr("abs:src")

            val meta = extractMeta(doc)
            val year = meta["year"]?.toIntOrNull()
            val defaultSeason = meta["season"]?.toIntOrNull() ?: 1

            val plot = doc.select("div.entry-content p").map { it.text().trim() }
                .firstOrNull {
                    it.startsWith("Synopsis", true) || it.startsWith("Storyline", true) ||
                        it.startsWith("Story:", true)
                }
                ?.substringAfter(":")?.trim()
                ?: doc.select("div.entry-content p").map { it.text().trim() }
                    .filter { it.length > 120 }
                    .maxByOrNull { it.length }

            val genres = doc.select(".meta-category a, .herald-meta a[href*=category]").map {
                it.text().trim()
            }.filter { it.isNotBlank() }.distinct().take(8)

            var hubLinks = parseHubHeadings(doc)
            var useHub = hubLinks.count { it.kind == "season" || it.kind == "movie" } >= 2
            if (!useHub) {
                // some collections list their seasons as plain paragraph links instead of headings
                val probe = parseDirectEpisodes(doc, 1)
                if (probe.episodes.isEmpty() && probe.orphans.isEmpty() && extractSources(doc).isEmpty()) {
                    val paragraphLinks = parseParagraphHubLinks(doc)
                    if (paragraphLinks.size >= 4) {
                        hubLinks = paragraphLinks
                        useHub = true
                    }
                }
            }
            if (useHub) {
                val hub = try {
                    buildHubResponse(title, id, poster, year, plot, genres, hubLinks)
                } catch (_: Exception) {
                    null
                }
                if (hub != null) return hub
            }

            val content = parsePageContent(doc, defaultSeason)
            buildNormalResponse(title, id, poster, year, plot, genres, content)
        } catch (_: Exception) {
            null
        }
    }



    // ---------- link resolution (RAINet.kt Codedew chain + host resolvers) ----------

    private sealed class ResolvedTarget {
        data class Argon(val code: String) : ResolvedTarget()
        data class ArgonDownload(val code: String) : ResolvedTarget()
        data class PixelDrain(val id: String) : ResolvedTarget()
        data class HubCloud(val id: String) : ResolvedTarget()
        data class Archive(val url: String) : ResolvedTarget()
        data class Direct(val url: String) : ResolvedTarget()
        data class StreamBeta(val id: String) : ResolvedTarget()
        data class GoFile(val code: String) : ResolvedTarget()
        data class MediaFire(val url: String) : ResolvedTarget()
        data class Mega(val url: String) : ResolvedTarget()
    }

    private class CodedewChainException(message: String) : Exception(message)

    private inner class CodedewResolver {

        private val DATA_HREF = Regex("""data-href="([^"]+)"""")

        private fun absolutize(base: String, location: String): String {
            if (location.startsWith("http")) return location
            return try {
                Http.absUrl(base, location)
            } catch (_: Exception) {
                if (location.startsWith("/")) "https://$CODEDEW_HOST$location" else location
            }
        }

        private fun classifyUrl(url: String): ResolvedTarget? {
            val u = htmlUnescape(url.substringBefore("#"))
            return when {
                u.contains("$CODEDEW_HOST/streambeta/") -> {
                    val id = queryParam(u, "url")
                    if (id.isNullOrBlank()) null else ResolvedTarget.StreamBeta(id)
                }
                u.contains("gofile.io/") -> {
                    val code = u.substringAfter("/d/").substringBefore("?").substringBefore("#")
                    if (code.isBlank()) null else ResolvedTarget.GoFile(code)
                }
                u.contains("mega" + ".nz/") || u.contains("mega.io/") -> ResolvedTarget.Mega(u)
                u.contains("$CODEDEW_HOST/multiquality/") -> {
                    val code = queryParam(u, "url")
                    if (code.isNullOrBlank()) null else ResolvedTarget.Argon(code)
                }
                u.contains("$CODEDEW_HOST/cdn/ziptron.php/") || u.contains("$CODEDEW_HOST/ziptron.php/") -> {
                    val afterQ = u.substringAfter("?")
                    val code = afterQ.substringBefore("&").substringBefore("/").substringBefore("#")
                    if (code.isBlank()) null else ResolvedTarget.ArgonDownload(code)
                }
                u.contains("$CODEDEW_HOST/watchbeta/") -> {
                    val id = queryParam(u, "url")
                    if (id.isNullOrBlank()) null else ResolvedTarget.PixelDrain(id)
                }
                u.contains("$HUBCLOUD_HOST/drive/") -> {
                    val id = u.substringAfter("$HUBCLOUD_HOST/drive/").substringBefore("?").substringBefore("/")
                    if (id.isBlank()) null else ResolvedTarget.HubCloud(id)
                }
                u.contains("pixeldrain.net/u/") || u.contains("pixeldrain.dev/u/") ||
                    u.contains("pixeldra.in/u/") -> {
                    val id = u.substringAfter("/u/").substringBefore("?").substringBefore("/")
                    if (id.isBlank()) null else ResolvedTarget.PixelDrain(id)
                }
                u.contains("$ARGON_HOST/embed/") -> {
                    val code = u.substringAfter("/embed/").substringBefore("?").substringBefore("/")
                    if (code.isBlank()) null else ResolvedTarget.Argon(code)
                }
                u.contains("$ARGON_HOST/downlead/") -> {
                    val code = u.substringAfter("/downlead/").substringBefore("?")
                        .substringBefore("/").substringBefore("#")
                    if (code.isBlank()) null else ResolvedTarget.ArgonDownload(code)
                }
                u.contains("mediafire.com/file/") -> ResolvedTarget.MediaFire(u)
                u.contains("$STORE_HOST/archives/") -> ResolvedTarget.Archive(u)
                u.contains("$CODEDEW_HOST/") -> null
                else -> null
            }
        }

        suspend fun resolve(url: String): ResolvedTarget {
            classifyUrl(url)?.let { return it }
            val current = when {
                url.contains("$CODEDEW_HOST/zipper/") ||
                    url.contains("$CODEDEW_HOST/watchbeta/") ||
                    url.contains("$CODEDEW_HOST/streambeta/") -> url
                url.contains("$CODEDEW_HOST/zipcloud/") -> {
                    val afterQ = url.substringAfter("zipcloud/", "").substringAfter("?")
                    val id = afterQ.substringBefore("&").substringBefore("/")
                    if (id.isBlank()) throw CodedewChainException("empty zipcloud id")
                    return ResolvedTarget.HubCloud(id)
                }
                url.contains("$HUBCLOUD_HOST/drive/") -> return classifyUrl(url)
                    ?: ResolvedTarget.HubCloud(
                        url.substringAfter("$HUBCLOUD_HOST/drive/").substringBefore("?").substringBefore("/"),
                    )
                url.contains("pixeldrain") && url.contains("/u/") -> return classifyUrl(url)
                    ?: throw CodedewChainException("bad pixeldrain url")
                url.contains("$STORE_HOST/archives/") -> return ResolvedTarget.Archive(url)
                else -> throw CodedewChainException("unhandled url: $url")
            }

            var target = current
            var referer = "https://$CODEDEW_HOST/"

            for (hop in 0 until 8) {
                val resp = raiGet(target, headers = mapOf("Referer" to referer), allowRedirects = false)

                if (resp.code in 300..399) {
                    val loc = resp.headers["location"]
                        ?: throw CodedewChainException("redirect without location")
                    target = absolutize(target, htmlUnescape(loc))
                    referer = "https://$CODEDEW_HOST/"
                    classifyUrl(target)?.let { return it }
                    continue
                }

                if (resp.code != 200) throw CodedewChainException("codedew returned ${resp.code}")

                classifyUrl(target)?.let { return it }

                val body = resp.text
                val dh = DATA_HREF.find(body) ?: throw CodedewChainException("no data-href on codedew page")
                target = absolutize(target, htmlUnescape(dh.groupValues[1]))
                referer = target
                classifyUrl(target)?.let { return it }
                if (isForeignAdLink(target)) {
                    throw CodedewChainException("codedew step button leads to ad site ${hostOf(target)}")
                }
            }
            throw CodedewChainException("codedew chain too deep")
        }

        // step buttons sometimes point at plain ad sites, those never lead anywhere
        private fun isForeignAdLink(url: String): Boolean {
            val host = hostOf(url)
            if (host.isBlank()) return false
            if (host == CODEDEW_HOST) return false
            val knownHosts = listOf(
                ARGON_HOST, HUBCLOUD_HOST, PIXELDRAIN_HOST, "pixeldrain.dev",
                "pixeldra.in", "gofile.io", "mediafire.com", "mega" + ".nz", "mega.io",
                STORE_HOST,
            )
            return knownHosts.none { host == it || host.endsWith(".$it") }
        }
    }

    private val codedewResolver = CodedewResolver()

    private fun qualityFromLabel(label: String): Int {
        return when {
            label.contains("2160", ignoreCase = true) -> 2160
            label.contains("1080", ignoreCase = true) -> 1080
            label.contains("720", ignoreCase = true) -> 720
            label.contains("480", ignoreCase = true) -> 480
            label.contains("360", ignoreCase = true) -> 360
            else -> 0
        }
    }

    private fun argonHeaders(referer: String): Map<String, String> = mapOf(
        "User-Agent" to RAI_UA,
        "Accept" to "*/*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Origin" to "https://$ARGON_HOST",
        "Referer" to referer,
    )

    private suspend fun resolveArgon(code: String, suffix: String, out: MutableList<StreamSource>): Boolean {
        var found = false
        try {
            val embed = raiGet(
                "https://$ARGON_HOST/embed/$code",
                headers = mapOf(
                    "User-Agent" to RAI_UA,
                    "Accept-Language" to ARGON_AL,
                    "Accept" to "text/html,*/*;q=0.8",
                    "Referer" to "https://$CODEDEW_HOST/",
                ),
            )
            val config = JuicyCodes.decodeFromHtml(embed.text)
            val m3u8 = config?.let {
                Regex(""""file"\s*:\s*"([^"]*\.m3u8)"""").find(it)?.groupValues?.get(1)
            }?.replace("\\/", "/")
            if (!m3u8.isNullOrBlank() && m3u8.startsWith("http")) {
                // the config file is an HLS playlist, a direct link makes the player choke on the playlist text
                out.add(
                    StreamSource(
                        url = m3u8,
                        quality = 0,
                        isM3u8 = true,
                        serverName = "MultiQuality [$suffix]",
                        headers = argonHeaders("https://$ARGON_HOST/") + mapOf(
                            "Sec-Fetch-Site" to "cross-site",
                            "Sec-Fetch-Mode" to "cors",
                            "Sec-Fetch-Dest" to "empty",
                        ),
                    ),
                )
                found = true
            }
        } catch (_: Exception) {
        }
        return found
    }

    private fun absorbSetCookies(jar: MutableMap<String, String>, response: RaiResp) {
        for (raw in response.headers.values("Set-Cookie")) {
            val pair = raw.substringBefore(";").trim()
            val idx = pair.indexOf('=')
            if (idx > 0) jar[pair.substring(0, idx)] = pair.substring(idx + 1)
        }
    }

    private fun cookieHeaderValue(jar: Map<String, String>): String {
        return jar.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private suspend fun resolveArgonDownload(code: String, suffix: String, out: MutableList<StreamSource>): Boolean {
        var found = false
        try {
            val jar = mutableMapOf<String, String>()
            val embedUrl = "https://$ARGON_HOST/embed/$code"
            val embed = raiGet(
                embedUrl,
                headers = mapOf(
                    "User-Agent" to RAI_UA,
                    "Accept-Language" to ARGON_AL,
                    "Referer" to "https://$CODEDEW_HOST/",
                    "Accept" to "text/html,*/*;q=0.8",
                ),
            )
            absorbSetCookies(jar, embed)
            val embedJuicy = Regex(
                """window\.juicyData\s*=\s*(\{.*?\})\s*</script>""",
                RegexOption.DOT_MATCHES_ALL,
            ).find(embed.text)?.groupValues?.get(1)
                ?.let { runCatching { Http.json.decodeFromString<JuicyDataWrapper>(it) }.getOrNull() }
            val pingToken = embedJuicy?.data?.token
            val pingRoute = embedJuicy?.data?.routes?.ping
            if (!pingRoute.isNullOrBlank() && !pingToken.isNullOrBlank()) {
                val pingId = UUID.randomUUID().toString().replace("-", "")
                val ping = raiPostJson(
                    "https://$ARGON_HOST$pingRoute",
                    """{"_token":"$pingToken","__type":"dawn","pingID":"$pingId"}""",
                    headers = mapOf(
                        "User-Agent" to RAI_UA,
                        "Accept-Language" to ARGON_AL,
                        "Accept" to "*/*",
                        "Referer" to embedUrl,
                        "Origin" to "https://$ARGON_HOST",
                        "Cookie" to cookieHeaderValue(jar),
                    ),
                )
                absorbSetCookies(jar, ping)
            }

            val dlUrl = "https://$ARGON_HOST/downlead/$code/"
            val dl = raiGet(
                dlUrl,
                headers = mapOf(
                    "User-Agent" to RAI_UA,
                    "Accept-Language" to ARGON_AL,
                    "Accept" to "text/html,*/*;q=0.8",
                    "Referer" to embedUrl,
                    "Cookie" to cookieHeaderValue(jar),
                ),
            )
            absorbSetCookies(jar, dl)
            val wrapper = Regex(
                """window\.juicyData\s*=\s*(\{.*?\})\s*</script>""",
                RegexOption.DOT_MATCHES_ALL,
            ).find(dl.text)?.groupValues?.get(1)
                ?.let { runCatching { Http.json.decodeFromString<JuicyDataWrapper>(it) }.getOrNull() }
            val token = wrapper?.data?.token
            val route = wrapper?.data?.routes?.links
            if (token.isNullOrBlank() || route.isNullOrBlank()) return false
            val api = raiPostJson(
                "https://$ARGON_HOST$route",
                """{"captcha":null,"_token":"$token"}""",
                headers = mapOf(
                    "User-Agent" to RAI_UA,
                    "Accept-Language" to ARGON_AL,
                    "Accept" to "application/json",
                    "Referer" to dlUrl,
                    "Origin" to "https://$ARGON_HOST",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Cookie" to cookieHeaderValue(jar),
                ),
            )
            if (api.code != 200) return false
            val links = Http.json.decodeFromString<ArgonLinks>(api.text)
            links.qualities?.forEach { q ->
                val link = q.link ?: return@forEach
                if (!link.startsWith("http")) return@forEach
                val label = q.label ?: "Download"
                val size = q.size?.let { " ($it)" } ?: ""
                out.add(
                    StreamSource(
                        url = link,
                        quality = qualityFromLabel(label),
                        isM3u8 = link.contains(".m3u8"),
                        serverName = "DLBeta $label$size [$suffix]",
                        headers = argonHeaders("https://$ARGON_HOST/"),
                    ),
                )
                found = true
            }
        } catch (_: Exception) {
        }
        return found
    }

    private fun emitPixelDrain(id: String, suffix: String, out: MutableList<StreamSource>): Boolean {
        out.add(
            StreamSource(
                url = "https://$PIXELDRAIN_HOST/api/file/$id",
                quality = 0,
                isM3u8 = false,
                serverName = "PixelDrain [$suffix]",
                headers = mapOf("User-Agent" to RAI_UA),
            ),
        )
        return true
    }

    private fun pixelDrainId(url: String): String? {
        val m = Regex("""/(?:u|api/file)/([A-Za-z0-9]{6,12})""").find(url) ?: return null
        return m.groupValues[1]
    }

    private suspend fun probeOk(url: String, timeoutMs: Long = 9_000L): Boolean {
        return try {
            withContext(Dispatchers.IO) {
                val req = Request.Builder().url(url)
                    .header("User-Agent", RAI_UA)
                    .header("Range", "bytes=0-1023")
                    .get().build()
                // Per-call timeouts: build a short-lived client off the shared one.
                val client = Http.client.newBuilder()
                    .connectTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .readTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .build()
                client.newCall(req).execute().use { res ->
                    res.code in 200..299 || res.code == 206
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun resolveStreamBeta(id: String, suffix: String, out: MutableList<StreamSource>): Boolean {
        var found = false
        try {
            val page = raiGet(
                "https://$CODEDEW_HOST/streambeta/?url=" + URLEncoder.encode(id, "UTF-8"),
            )
            if (page.code != 200) return false
            val m = Regex(
                """playerSources\s*=\s*(\[.*?\])\s*;""",
                RegexOption.DOT_MATCHES_ALL,
            ).find(page.text) ?: return false
            val parsed = Http.json.decodeFromString<List<StreamBetaLink>>(m.groupValues[1])
            if (parsed.isEmpty()) return false
            val emitted = mutableSetOf<String>()
            for (src in parsed) {
                val name = src.name ?: "Server"
                src.streamUrl?.takeIf { it.startsWith("http") }?.let { stream ->
                    if (!emitted.contains(stream) && probeOk(stream)) {
                        emitted.add(stream)
                        out.add(
                            StreamSource(
                                url = stream,
                                quality = 0,
                                isM3u8 = stream.contains(".m3u8"),
                                serverName = "WatchNow $name [$suffix]",
                                headers = mapOf("User-Agent" to RAI_UA),
                            ),
                        )
                        found = true
                    }
                }
                src.url?.takeIf { it.startsWith("http") }?.let { dl ->
                    val pdId = if (dl.contains("pixeldra")) pixelDrainId(dl) else null
                    val target = if (pdId != null) "https://$PIXELDRAIN_HOST/api/file/$pdId" else dl
                    if (!emitted.contains(target) && !target.contains("mega" + ".nz") && probeOk(target)) {
                        emitted.add(target)
                        out.add(
                            StreamSource(
                                url = target,
                                quality = 0,
                                isM3u8 = target.contains(".m3u8"),
                                serverName = "WatchNow $name DL [$suffix]",
                                headers = mapOf("User-Agent" to RAI_UA),
                            ),
                        )
                        found = true
                    }
                }
            }
        } catch (_: Exception) {
        }
        return found
    }

    private var goFileTokenCache: String? = null
    private var goFileTokenAt: Long = 0

    private suspend fun goFileToken(): String? {
        val cached = goFileTokenCache
        if (cached != null && System.currentTimeMillis() - goFileTokenAt < 3_600_000L) {
            return cached
        }
        return try {
            val accountResp = execute(
                buildRequest(
                    "https://api.gofile.io/accounts",
                    headers = mapOf(
                        "Origin" to "https://gofile.io",
                        "Referer" to "https://gofile.io/",
                    ),
                    method = "POST",
                    body = ByteArray(0).toRequestBody(null),
                ),
                true,
            )
            val account = Http.json.decodeFromString<GoFileAccount>(accountResp.text)
            val token = account.data?.token
            if (token.isNullOrBlank()) return null
            execute(
                buildRequest(
                    "https://api.gofile.io/accounts/website",
                    headers = mapOf(
                        "Authorization" to "Bearer $token",
                        "Origin" to "https://gofile.io",
                        "Referer" to "https://gofile.io/",
                    ),
                ),
                true,
            )
            goFileTokenCache = token
            goFileTokenAt = System.currentTimeMillis()
            token
        } catch (_: Exception) {
            null
        }
    }

    private fun goFileWt(token: String): String {
        val bucket = System.currentTimeMillis() / 14_400_000L
        val payload = "$RAI_UA::en-US::$token::$bucket::$GOFILE_WT_SECRET"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private suspend fun resolveGoFile(code: String, suffix: String, out: MutableList<StreamSource>): Boolean {
        var found = false
        try {
            val token = goFileToken() ?: return false
            val wt = goFileWt(token)
            val contentResp = execute(
                buildRequest(
                    "https://api.gofile.io/contents/$code?pageSize=100&sortField=name&sortDirection=1",
                    headers = mapOf(
                        "Authorization" to "Bearer $token",
                        "X-Website-Token" to wt,
                        "X-BL" to "en-US",
                        "Origin" to "https://gofile.io",
                        "Referer" to "https://gofile.io/",
                    ),
                ),
                true,
            )
            val content = Http.json.decodeFromString<GoFileContent>(contentResp.text)
            if (content.status != "ok") {
                goFileTokenCache = null
                return false
            }
            content.data?.children.orEmpty().values.forEach { child ->
                val link = child.link ?: return@forEach
                if (!link.startsWith("http")) return@forEach
                val sizeMb = child.size?.let {
                    if (it > 0) " ${it / 1048576}MB" else ""
                } ?: ""
                out.add(
                    StreamSource(
                        url = link,
                        quality = qualityFromLabel(child.name ?: ""),
                        isM3u8 = link.contains(".m3u8"),
                        serverName = "GoFile ${(child.name ?: "file").take(40)}$sizeMb [$suffix]",
                        headers = mapOf(
                            "User-Agent" to RAI_UA,
                            "Authorization" to "Bearer $token",
                        ),
                    ),
                )
                found = true
            }
        } catch (_: Exception) {
        }
        return found
    }

    private suspend fun resolveMediaFire(url: String, suffix: String, out: MutableList<StreamSource>): Boolean {
        var found = false
        try {
            val page = raiGet(url)
            if (page.code != 200) return false
            val direct = Regex("""href="(https://download[0-9a-z]*\.mediafire\.com/[^"]+)"""")
                .find(page.text)?.groupValues?.get(1)
            if (direct.isNullOrBlank()) return false
            out.add(
                StreamSource(
                    url = direct,
                    quality = 0,
                    isM3u8 = false,
                    serverName = "MediaFire [$suffix]",
                    headers = mapOf("User-Agent" to RAI_UA),
                ),
            )
            found = true
        } catch (_: Exception) {
        }
        return found
    }

    private suspend fun resolveHubCloud(id: String, suffix: String, out: MutableList<StreamSource>): Boolean {
        var found = false
        try {
            val page = raiGet("https://$HUBCLOUD_HOST/drive/$id")
            val resolverUrl = Regex("""var\s+url\s*=\s*'([^']+)'""").find(page.text)?.groupValues?.get(1)
            if (resolverUrl.isNullOrBlank()) return false
            val res = raiGet(resolverUrl, mapOf("Referer" to "https://$HUBCLOUD_HOST/"))
            val body = res.text

            Regex("""<a[^>]*href="(https?://[^"]+)"[^>]*id="fsl"""").find(body)?.let { m ->
                val fsl = htmlUnescape(m.groupValues[1])
                if (fsl.startsWith("http") && !fsl.contains("hubcloud.cx/favicon")) {
                    out.add(
                        StreamSource(
                            url = fsl,
                            quality = 0,
                            isM3u8 = fsl.contains(".m3u8"),
                            serverName = "HubCloud FSL [$suffix]",
                            headers = mapOf("User-Agent" to RAI_UA),
                        ),
                    )
                    found = true
                }
            }
            if (!found) {
                Regex("""href="(https?://[^"]*cloudflarestorage\.com/[^"]+)"""").find(body)?.let { m ->
                    out.add(
                        StreamSource(
                            url = htmlUnescape(m.groupValues[1]),
                            quality = 0,
                            isM3u8 = false,
                            serverName = "HubCloud FSL [$suffix]",
                            headers = mapOf("User-Agent" to RAI_UA),
                        ),
                    )
                    found = true
                }
            }

            Regex("""https://pixel\.hubcloud\.ist/\?id=[^"'\s<>]+""").find(body)?.let { pixelMatch ->
                try {
                    var hopUrl: String? = pixelMatch.value
                    for (hop in 0 until 3) {
                        val current = hopUrl ?: break
                        val hopResp = raiGet(current, allowRedirects = false)
                        val loc = hopResp.headers["location"]
                        if (loc.isNullOrBlank()) break
                        val next: String = if (loc.startsWith("http")) loc
                        else "https://pixel.hubcloud.ist$loc"
                        hopUrl = next
                        if (next.contains("dl.php?link=")) {
                            val direct = URLDecoder.decode(
                                next.substringAfter("dl.php?link="),
                                "UTF-8",
                            )
                            if (direct.startsWith("http")) {
                                out.add(
                                    StreamSource(
                                        url = direct,
                                        quality = 0,
                                        isM3u8 = direct.contains(".m3u8"),
                                        serverName = "HubCloud 10Gbps [$suffix]",
                                        headers = mapOf("User-Agent" to RAI_UA),
                                    ),
                                )
                                found = true
                            }
                            break
                        }
                    }
                } catch (_: Exception) {
                }
            }

            Regex("""https?://pixel(?:drain|dra)\.[a-z]+/u/([A-Za-z0-9]+)""")
                .findAll(body)
                .map { it.groupValues[1] }
                .distinct()
                .take(2)
                .forEach { pid ->
                    val api = "https://$PIXELDRAIN_HOST/api/file/$pid"
                    if (probeOk(api)) {
                        emitPixelDrain(pid, "$suffix mirror", out)
                        found = true
                    }
                }
        } catch (_: Exception) {
        }
        return found
    }

    private suspend fun resolveTarget(
        t: ResolvedTarget,
        label: String,
        out: MutableList<StreamSource>,
    ): Boolean {
        return when (t) {
            is ResolvedTarget.Argon -> resolveArgon(t.code, label, out)
            is ResolvedTarget.ArgonDownload -> resolveArgonDownload(t.code, label, out)
            is ResolvedTarget.StreamBeta -> resolveStreamBeta(t.id, label, out)
            is ResolvedTarget.PixelDrain -> emitPixelDrain(t.id, label, out)
            is ResolvedTarget.HubCloud -> resolveHubCloud(t.id, label, out)
            is ResolvedTarget.GoFile -> resolveGoFile(t.code, label, out)
            is ResolvedTarget.MediaFire -> resolveMediaFire(t.url, label, out)
            is ResolvedTarget.Mega -> false
            is ResolvedTarget.Archive -> {
                var emitted = false
                try {
                    val resp = raiGet(t.url)
                    parseArchiveEpisodes(resp.text, resp.url).take(3).forEach { (_, epUrl) ->
                        try {
                            val t2 = codedewResolver.resolve(epUrl)
                            if (resolveTarget(t2, label, out)) emitted = true
                        } catch (_: Exception) {
                        }
                    }
                } catch (_: Exception) {
                }
                emitted
            }
            is ResolvedTarget.Direct -> {
                out.add(
                    StreamSource(
                        url = t.url,
                        quality = 0,
                        isM3u8 = t.url.contains(".m3u8"),
                        serverName = "Direct [$label]",
                        headers = mapOf("User-Agent" to RAI_UA),
                    ),
                )
                true
            }
        }
    }

    override suspend fun loadStreams(episodeId: String): EpisodeStreams {
        return try {
            refreshBase()
            val variants = try {
                Http.json.decodeFromString<RAIEpisodeData>(episodeId).v
            } catch (_: Exception) {
                listOf(RAIVariant("Default", episodeId))
            }.filter { it.u.isNotBlank() }
            if (variants.isEmpty()) return EpisodeStreams(emptyList(), emptyList())

            val sources = mutableListOf<StreamSource>()
            for (v in variants) {
                try {
                    resolveTarget(codedewResolver.resolve(v.u), v.n, sources)
                } catch (_: Exception) {
                }
            }
            // No WebView resolver-popup fallback: providers carry no UI here.
            // If nothing resolved over HTTP, the episode honestly has no sources.
            EpisodeStreams(sources = sources.distinctBy { it.url }, subtitles = emptyList())
        } catch (_: Exception) {
            EpisodeStreams(emptyList(), emptyList())
        }
    }
}
