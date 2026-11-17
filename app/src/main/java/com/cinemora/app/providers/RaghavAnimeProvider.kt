// Ported from the RaghavAnime CloudStream plugin (GPL-3.0, based on
// KSHITIJ8473/raghav — RaghavAnime/AniSugeProvider.kt, AniSugeResolver.kt
// and MegaPlayHelper.kt), rewritten as native in-app code for this app:
// no CloudStream, no plugins, no DonationManager, no Firebase SDK.
//
// Engine: AniSuge (anisuge.tv). RaghavAnime itself is an AniList-metadata
// aggregator that fans out to many sub-sources (Miruro, AniSuge, AniWaves,
// AniKage, 2DHive, AniKoto, GoTaku, ...); AniSuge is the primary
// search/details engine of that set and was verified live on 2026-11-18
// (home + search + episode list + server list + MegaPlay embed all 200).
// Only this one engine is ported, under the RaghavAnime name.
//
// Notes on the port:
// - VRF token for /ajax/episode/list (RC4 + shift + rot13) ported verbatim
//   and verified live against Naruto (958 -> 220 episodes).
// - The CloudflareKiller interceptor is replaced by CfBypass (WebView)
//   on 403 / challenge pages, then one retry.
// - loadExtractor / WebViewResolver have no equivalent in this app, so
//   non-MegaPlay embeds fall back to Extractors.resolve() (generic hosts
//   registered at app start) and then to a raw .m3u8/.mp4 scan of the
//   embed page. MegaPlay-clone embeds (megaplay/vidwish/vidtube/vidstream/
//   vidplay) are fully resolved inline, including the AES-encrypted
//   `enc` source, signed master playlists and per-variant links.
// - The mapper API (mapper.nekostream.site) and the AnimePahe/Kwik
//   download path from AniSugeResolver.kt are ported as a secondary
//   source; Kwik's P.A.C.K.E.R. blob is unpacked by a small built-in
//   unpacker (no CloudStream JsPacker dependency).
// - AniSuge has no seasons: sub and dub are exposed as two groups
//   ("Subbed" / "Dubbed") when both exist, otherwise a single "Season 1".

package com.cinemora.app.providers

import com.cinemora.app.CinemoraAppHolder
import com.cinemora.app.core.AnimeCard
import com.cinemora.app.core.AnimeDetails
import com.cinemora.app.core.AnimeProvider
import com.cinemora.app.core.AudioKind
import com.cinemora.app.core.CfBypass
import com.cinemora.app.core.DomainHelper
import com.cinemora.app.core.EpisodeItem
import com.cinemora.app.core.EpisodeStreams
import com.cinemora.app.core.Extractors
import com.cinemora.app.core.HomeSection
import com.cinemora.app.core.Http
import com.cinemora.app.core.Lang
import com.cinemora.app.core.SeasonGroup
import com.cinemora.app.core.StreamSource
import com.cinemora.app.core.SubtitleTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.FormBody
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class RaghavAnimeProvider : AnimeProvider {

    override val id: String = "raghavanime"
    override val name: String = "RaghavAnime"
    override val lang: Lang = Lang.ENGLISH
    override var baseUrl: String = DEFAULT_BASE

    companion object {
        private const val DEFAULT_BASE = "https://anisuge.tv"
        private const val MAPPER_API = "https://mapper.nekostream.site/api/mal/"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        /** OkHttp client that does NOT follow redirects (for Location headers). */
        private val noRedirectClient by lazy {
            Http.client.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        }

        /** GET without following redirects: returns (statusCode, Location?). */
        suspend fun getNoRedirect(
            url: String,
            headers: Map<String, String> = emptyMap(),
        ): Pair<Int, String?> = withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(url)
                .header("User-Agent", Http.USER_AGENT)
            headers.forEach { (k, v) -> builder.header(k, v) }
            noRedirectClient.newCall(builder.get().build()).execute().use { res ->
                res.code to res.header("location")
            }
        }

        /** POST form without following redirects: returns (statusCode, Location?). */
        suspend fun postFormNoRedirect(
            url: String,
            form: Map<String, String>,
            headers: Map<String, String> = emptyMap(),
        ): Pair<Int, String?> = withContext(Dispatchers.IO) {
            val body = FormBody.Builder().apply {
                form.forEach { (k, v) -> add(k, v) }
            }.build()
            val builder = Request.Builder().url(url)
                .header("User-Agent", Http.USER_AGENT)
            headers.forEach { (k, v) -> builder.header(k, v) }
            noRedirectClient.newCall(builder.post(body).build()).execute().use { res ->
                res.code to res.header("location")
            }
        }
    }

    // ------------------------------------------------------------------
    // Small JSON models (kotlinx.serialization; original used Jackson)
    // ------------------------------------------------------------------

    @Serializable
    private data class EpsResponse(
        val status: Int? = null,
        val result: String? = null,
    )

    @Serializable
    private data class ServerInfoResponse(
        val status: Int? = null,
        val result: ServerInfoResult? = null,
    )

    @Serializable
    private data class ServerInfoResult(
        val url: String? = null,
    )

    // ------------------------------------------------------------------
    // Base URL / fetch helpers
    // ------------------------------------------------------------------

    private suspend fun refreshBase() {
        try {
            DomainHelper.getDomain("anisuge")?.takeIf { it.isNotBlank() }?.let {
                baseUrl = it.removeSuffix("/")
            }
        } catch (_: Exception) {
        }
    }

    private fun ajaxHeaders(referer: String): Map<String, String> = mapOf(
        "User-Agent" to DESKTOP_UA,
        "Referer" to referer,
        "X-Requested-With" to "XMLHttpRequest",
    )

    /**
     * GET with Cloudflare handling: on 403 / challenge page, run the WebView
     * bypass once (cookies land in Http's jar) and retry. Null on failure.
     */
    private suspend fun fetchText(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): String? {
        return try {
            val (code, body) = Http.getWithCode(url, headers)
            if (code == 403 || CfBypass.isChallengePage(body)) {
                if (CfBypass.resolve(url, CinemoraAppHolder.context)) {
                    val (code2, body2) = Http.getWithCode(url, headers)
                    if (code2 in 200..299) body2 else null
                } else {
                    null
                }
            } else if (code in 200..299) {
                body
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchDoc(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): Document? {
        val text = fetchText(url, headers) ?: return null
        return try {
            Jsoup.parse(text, url)
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------
    // VRF token (port of generateVrf / rc4 / shiftCharcode / rot13)
    // ------------------------------------------------------------------

    private fun rc4(key: ByteArray, input: ByteArray): ByteArray {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0..255) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
            val tmp = s[i]
            s[i] = s[j]
            s[j] = tmp
        }
        var i = 0
        j = 0
        val result = ByteArray(input.size)
        for (x in input.indices) {
            i = (i + 1) and 0xFF
            j = (j + s[i]) and 0xFF
            val tmp = s[i]
            s[i] = s[j]
            s[j] = tmp
            val k = s[(s[i] + s[j]) and 0xFF]
            result[x] = ((input[x].toInt() and 0xFF) xor k).toByte()
        }
        return result
    }

    private fun shiftCharcode(t: String): ByteArray {
        val result = ByteArray(t.length)
        for (r in t.indices) {
            var s = t[r].code
            when (r % 8) {
                1 -> s += 3
                7 -> s += 5
                2 -> s -= 4
                4 -> s -= 2
                6 -> s += 4
                0 -> s -= 3
                3 -> s += 2
                5 -> s += 5
            }
            result[r] = s.toByte()
        }
        return result
    }

    private fun rot13(s: String): String {
        val out = StringBuilder()
        for (c in s) {
            when (c) {
                in 'a'..'z' -> {
                    var o = c.code + 13
                    if (o > 'z'.code) o -= 26
                    out.append(o.toChar())
                }
                in 'A'..'Z' -> {
                    var o = c.code + 13
                    if (o > 'Z'.code) o -= 26
                    out.append(o.toChar())
                }
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    /** URL-safe base64 with padding, no line wraps (Android URL_SAFE|NO_WRAP). */
    private fun b64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().encodeToString(bytes)

    private fun generateVrf(input: String): String {
        val encoded = URLEncoder.encode(input, "UTF-8").replace("+", "%20")
        val key = "ysJhV6U27FVIjjuk".toByteArray(Charsets.UTF_8)
        val rc4Bytes = rc4(key, encoded.toByteArray(Charsets.UTF_8))
        val b64 = b64Url(rc4Bytes)
        val shifted = shiftCharcode(b64)
        val b64Shifted = b64Url(shifted)
        return rot13(b64Shifted)
    }

    // ------------------------------------------------------------------
    // Home + search
    // ------------------------------------------------------------------

    private fun parseCards(doc: Document, section: Element?): List<AnimeCard> {
        val items = section?.select(".item") ?: doc.select(".item")
        val out = mutableListOf<AnimeCard>()
        for (item in items) {
            val a: Element? = if (item.tagName() == "a") item else item.selectFirst("a[href]")
            val href = a?.attr("href")?.takeIf { it.isNotBlank() } ?: continue
            val title = item.selectFirst(".name")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: a.text().trim().takeIf { it.isNotBlank() }
                ?: continue
            val img = item.selectFirst("img")
            val poster = img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")?.takeIf { it.isNotBlank() }
            val cleanId = normalizeDetailsUrl(Http.absUrl(baseUrl, href))
            out.add(
                AnimeCard(
                    id = cleanId,
                    title = title,
                    posterUrl = poster?.let { Http.absUrl(baseUrl, it) },
                    providerId = id,
                    lang = lang,
                ),
            )
        }
        return out
    }

    /** Card links sometimes point at /anime/<slug>/ep-N; details live at the bare slug. */
    private fun normalizeDetailsUrl(url: String): String =
        url.replace(Regex("/ep-\\d+/?$"), "")

    override suspend fun homeSections(): List<HomeSection> {
        return try {
            refreshBase()
            val doc = fetchDoc(
                "$baseUrl/home",
                mapOf("User-Agent" to DESKTOP_UA, "Referer" to "$baseUrl/"),
            ) ?: return emptyList()
            val defs = listOf(
                "Recently Updated" to (
                    doc.selectFirst("section:has(h2:contains(Recently Updated))")
                        ?: doc.selectFirst("section:has(h2:contains(Latest Episode))")
                        ?: doc.selectFirst("section.pt-4")
                    ),
                "Recently Added" to doc.selectFirst("section.added"),
                "Just Completed" to doc.selectFirst("section.complete"),
                "Most Viewed" to doc.selectFirst("section.mostview"),
            )
            defs.mapNotNull { (title, section) ->
                val items = parseCards(doc, section)
                if (items.isEmpty()) null else HomeSection(title, id, items)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun search(query: String): List<AnimeCard> {
        if (query.isBlank()) return emptyList()
        return try {
            refreshBase()
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val doc = fetchDoc(
                "$baseUrl/filter?keyword=$encoded",
                mapOf("User-Agent" to DESKTOP_UA, "Referer" to "$baseUrl/"),
            ) ?: return emptyList()
            parseCards(doc, null)
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ------------------------------------------------------------------
    // Details + episodes
    // ------------------------------------------------------------------

    override suspend fun loadDetails(id: String): AnimeDetails? {
        return try {
            refreshBase()
            val url = normalizeDetailsUrl(Http.absUrl(baseUrl, id))
            val doc = fetchDoc(
                url,
                mapOf("User-Agent" to DESKTOP_UA, "Referer" to "$baseUrl/"),
            ) ?: return null
            val html = doc.html()

            val dataId = doc.selectFirst(".watch-wrap")?.attr("data-id")
                ?.takeIf { it.isNotBlank() }
                ?: Regex("""mangaId\s*=\s*(\d+)""").find(html)?.groupValues?.get(1)
                ?: return null

            val title = doc.selectFirst(".maindata h1.title")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                    ?.substringBefore(" – Watch")?.substringBefore(" Episode")?.trim()
                ?: "Unknown"

            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?.takeIf { it.isNotBlank() }

            val bannerStyle = doc.selectFirst(".media-bg")?.attr("style")
            val banner = bannerStyle?.let { style ->
                Regex("""url\(['"]?([^'")]+)['"]?\)""").find(style)?.groupValues?.get(1)
            }

            val plot = doc.selectFirst(".description .full")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst(".description")?.text()
                    ?.replace("more+", "")?.trim()?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

            var year: Int? = null
            var status: String? = null
            var rating: Float? = null
            doc.select(".meta div").forEach { div ->
                val text = div.text()
                when {
                    text.contains("Premiered:", ignoreCase = true) ||
                        text.contains("Aired:", ignoreCase = true) -> {
                        if (year == null) {
                            year = Regex("""\b(19\d\d|20\d\d)\b""").find(text)
                                ?.groupValues?.get(1)?.toIntOrNull()
                        }
                    }
                    text.contains("Status:", ignoreCase = true) && status == null -> {
                        status = text.substringAfter("Status:", "")
                            .trim().takeIf { it.isNotBlank() }
                    }
                    text.contains("MAL:", ignoreCase = true) && rating == null -> {
                        rating = Regex("""\d+(?:\.\d+)?""").find(
                            text.substringAfter("MAL:", ""),
                        )?.value?.toFloatOrNull()
                    }
                }
            }

            val genres = doc.select(".meta a[href*='/genre/'], .data a[href*='/genre/']")
                .map { it.text().trim() }.filter { it.isNotBlank() }
                .ifEmpty {
                    doc.select("a[href*='/genre/']")
                        .map { it.text().trim() }.filter { it.isNotBlank() }
                }.distinct()

            val (subEpisodes, dubEpisodes, malId) = loadEpisodeLists(url, dataId)

            val audioKinds = buildSet {
                if (subEpisodes.isNotEmpty()) add(AudioKind.SUB)
                if (dubEpisodes.isNotEmpty()) add(AudioKind.DUB)
            }.ifEmpty { setOf(AudioKind.SUB) }

            val seasons = when {
                subEpisodes.isNotEmpty() && dubEpisodes.isNotEmpty() -> listOf(
                    SeasonGroup(1, "Subbed", subEpisodes),
                    SeasonGroup(2, "Dubbed", dubEpisodes.map { it.copy(season = 2) }),
                )
                subEpisodes.isNotEmpty() -> listOf(SeasonGroup(1, "Season 1", subEpisodes))
                dubEpisodes.isNotEmpty() -> listOf(SeasonGroup(1, "Season 1", dubEpisodes))
                else -> emptyList()
            }

            AnimeDetails(
                id = url,
                title = title,
                posterUrl = poster,
                bannerUrl = banner,
                description = plot,
                genres = genres,
                status = status,
                year = year,
                rating = rating,
                malId = malId,
                seasons = seasons,
                providerId = id,
                lang = lang,
                audioKinds = audioKinds,
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Fetches /ajax/episode/list/<dataId>?vrf=... and splits the returned
     * HTML into sub / dub episode lists. Returns (sub, dub, malId).
     * EpisodeItem.id packs everything loadStreams needs:
     * base|dataId|epNum|dataIds|malId|slug|timestamp|sub|dub
     */
    private suspend fun loadEpisodeLists(
        detailsUrl: String,
        dataId: String,
    ): Triple<List<EpisodeItem>, List<EpisodeItem>, Int?> {
        val sub = mutableListOf<EpisodeItem>()
        val dub = mutableListOf<EpisodeItem>()
        var malId: Int? = null
        try {
            val vrf = generateVrf(dataId)
            val text = fetchText(
                "$baseUrl/ajax/episode/list/$dataId?vrf=$vrf",
                ajaxHeaders(detailsUrl),
            ) ?: return Triple(sub, dub, null)
            val epsJson = Http.json.decodeFromString<EpsResponse>(text)
            val epsHtml = epsJson.result ?: return Triple(sub, dub, null)
            val epsSoup = Jsoup.parse(epsHtml)
            for (epLink in epsSoup.select("a[data-ids]")) {
                val epNum = epLink.text().trim().toIntOrNull()
                    ?: epLink.attr("data-slug").toIntOrNull()
                    ?: epLink.attr("data-num").filter { it.isDigit() }.toIntOrNull()
                    ?: 1
                val epTitle = epLink.attr("data-num")
                    .takeIf { it.isNotBlank() } ?: "Episode $epNum"
                val dataIds = epLink.attr("data-ids").takeIf { it.isNotBlank() } ?: continue
                val mal = epLink.attr("data-mal").trim()
                if (malId == null) malId = mal.toIntOrNull()
                val slug = epLink.attr("data-slug").takeIf { it.isNotBlank() } ?: epNum.toString()
                val timestamp = epLink.attr("data-timestamp").trim()
                val hasSub = epLink.attr("data-sub") == "1"
                val hasDub = epLink.attr("data-dub") == "1"
                val epIdBase = "$baseUrl|$dataId|$epNum|$dataIds|$mal|$slug|$timestamp"
                if (hasSub) {
                    sub.add(EpisodeItem("$epIdBase|sub", epNum, epTitle, 1))
                }
                if (hasDub) {
                    dub.add(EpisodeItem("$epIdBase|dub", epNum, epTitle, 1))
                }
            }
        } catch (_: Exception) {
        }
        return Triple(sub, dub, malId)
    }

    // ------------------------------------------------------------------
    // Streams
    // ------------------------------------------------------------------

    override suspend fun loadStreams(episodeId: String): EpisodeStreams {
        return try {
            refreshBase()
            val parts = episodeId.split("|")
            if (parts.size < 8 || !parts[0].startsWith("http")) {
                return EpisodeStreams(emptyList(), emptyList())
            }
            val base = parts[0]
            val dataIds = parts[3]
            val malId = parts.getOrNull(4).orEmpty()
            val slug = parts.getOrNull(5) ?: parts[2]
            val timestamp = parts.getOrNull(6).orEmpty()
            val kind = parts.last() // "sub" or "dub"

            coroutineScope {
                val legacy = async {
                    runCatching { loadLegacyServers(base, dataIds, kind) }
                        .getOrDefault(EpisodeStreams(emptyList(), emptyList()))
                }
                val mapper = async {
                    if (malId.isNotBlank() && timestamp.isNotBlank()) {
                        runCatching { loadMapperSources(base, malId, slug, timestamp, kind) }
                            .getOrDefault(EpisodeStreams(emptyList(), emptyList()))
                    } else {
                        EpisodeStreams(emptyList(), emptyList())
                    }
                }
                val a = legacy.await()
                val b = mapper.await()
                val seen = LinkedHashSet<String>()
                val sources = (a.sources + b.sources).filter { seen.add(it.url) }
                EpisodeStreams(sources, a.subtitles + b.subtitles)
            }
        } catch (_: Exception) {
            EpisodeStreams(emptyList(), emptyList())
        }
    }

    /** Legacy per-server path: /ajax/server/list -> /ajax/server?get= -> embed. */
    private suspend fun loadLegacyServers(
        base: String,
        dataIds: String,
        kind: String,
    ): EpisodeStreams = coroutineScope {
        val encodedIds = URLEncoder.encode(dataIds, "UTF-8")
        val text = fetchText(
            "$base/ajax/server/list?servers=$encodedIds",
            ajaxHeaders("$base/watch/"),
        ) ?: return@coroutineScope EpisodeStreams(emptyList(), emptyList())
        val serverListJson = Http.json.decodeFromString<EpsResponse>(text)
        val serverListHtml = serverListJson.result
            ?: return@coroutineScope EpisodeStreams(emptyList(), emptyList())
        val soup = Jsoup.parse(serverListHtml)

        val serversToLoad = mutableListOf<Pair<String, String>>()
        for (st in soup.select(".server-type")) {
            val typeAttr = st.attr("data-type")
            val isMatch = if (kind == "sub") {
                typeAttr == "sub" || typeAttr == "hsub" ||
                    typeAttr == "h-sub" || typeAttr == "raw"
            } else {
                typeAttr == "dub" || typeAttr == "adub" || typeAttr == "a-dub"
            }
            if (!isMatch) continue
            for (s in st.select(".server")) {
                val linkId = s.attr("data-link-id").takeIf { it.isNotBlank() } ?: continue
                val serverName = s.selectFirst("span")?.text()?.trim()
                    ?.takeIf { it.isNotBlank() } ?: "Unknown Server"
                serversToLoad.add(serverName to linkId)
            }
        }
        if (serversToLoad.isEmpty()) return@coroutineScope EpisodeStreams(emptyList(), emptyList())

        val results = serversToLoad.map { (serverName, linkId) ->
            async {
                try {
                    val infoText = fetchText(
                        "$base/ajax/server?get=${URLEncoder.encode(linkId, "UTF-8")}",
                        ajaxHeaders("$base/watch/"),
                    ) ?: return@async EpisodeStreams(emptyList(), emptyList())
                    val info = Http.json.decodeFromString<ServerInfoResponse>(infoText)
                    val playerUrl = info.result?.url?.takeIf { it.isNotBlank() }
                        ?: return@async EpisodeStreams(emptyList(), emptyList())
                    resolveEmbed(playerUrl, base, serverName, kind)
                } catch (_: Exception) {
                    EpisodeStreams(emptyList(), emptyList())
                }
            }
        }.awaitAll()
        val seen = LinkedHashSet<String>()
        val sources = results.flatMap { it.sources }.filter { seen.add(it.url) }
        EpisodeStreams(sources, results.flatMap { it.subtitles })
    }

    /**
     * Mapper path (AniSugeResolver.kt): mapper.nekostream.site maps a MAL id
     * to per-provider stream + AnimePahe download links.
     */
    private suspend fun loadMapperSources(
        base: String,
        malId: String,
        slug: String,
        timestamp: String,
        kind: String,
    ): EpisodeStreams {
        val providers = AniSugeMapper.fetchProviders(malId, slug, timestamp) ?: return EpisodeStreams(
            emptyList(),
            emptyList(),
        )
        val sources = mutableListOf<StreamSource>()
        val subtitles = mutableListOf<SubtitleTrack>()
        for ((providerKey, subDub) in providers) {
            val displayName = AniSugeMapper.displayProviderName(providerKey)
            val entry = if (kind == "dub") subDub.second else subDub.first
            if (entry == null) continue

            entry.url?.let { streamUrl ->
                var embedUrl: String? = null
                try {
                    val infoText = fetchText(
                        "$base/ajax/server?get=${URLEncoder.encode(streamUrl, "UTF-8")}",
                        ajaxHeaders("$base/watch/"),
                    )
                    if (infoText != null) {
                        val info = Http.json.decodeFromString<ServerInfoResponse>(infoText)
                        embedUrl = info.result?.url?.takeIf { it.isNotBlank() }
                    }
                } catch (_: Exception) {
                }
                val resolved = resolveEmbed(
                    embedUrl ?: streamUrl,
                    base,
                    displayName,
                    kind,
                )
                sources += resolved.sources
                subtitles += resolved.subtitles
            }

            for ((qualityLabel, paheUrl) in entry.downloads) {
                try {
                    val kwikUrl = PaheDownloadResolver.resolveKwikUrl(paheUrl) ?: continue
                    val (playUrl, kwikPage) = KwikExtractor.resolve(kwikUrl, paheUrl) ?: continue
                    val quality = qualityLabel.filter { it.isDigit() }.toIntOrNull() ?: 0
                    sources += StreamSource(
                        url = playUrl,
                        quality = quality,
                        isM3u8 = playUrl.contains(".m3u8", ignoreCase = true),
                        serverName = "AniSuge $displayName $qualityLabel",
                        headers = mapOf("Referer" to kwikPage),
                    )
                } catch (_: Exception) {
                }
            }
        }
        return EpisodeStreams(sources, subtitles)
    }

    /**
     * Turns one embed/player URL into playable sources.
     * - plyr.php#<b64> -> direct file link
     * - MegaPlay clones -> full MegaPlay resolution (AES `enc` source)
     * - anything else -> registered generic extractors, then a raw
     *   .m3u8/.mp4 scan of the embed page
     */
    private suspend fun resolveEmbed(
        playerUrl: String,
        base: String,
        serverName: String,
        audioType: String,
    ): EpisodeStreams {
        if (playerUrl.contains("plyr.php#")) {
            val b64 = playerUrl.substringAfter("#").substringBefore("#")
            val decoded = try {
                val padded = b64.padBase64()
                String(Base64.getDecoder().decode(padded), Charsets.UTF_8)
            } catch (_: Exception) {
                try {
                    String(Base64.getUrlDecoder().decode(b64.padBase64()), Charsets.UTF_8)
                } catch (_: Exception) {
                    ""
                }
            }
            if (decoded.isNotBlank()) {
                return Extractors.direct(
                    decoded,
                    "AniSuge $serverName",
                    headers = mapOf("Referer" to "https://gogoanime.me.uk/"),
                )
            }
        }

        val embedHost = Regex("""https?://([^/]+)""").find(playerUrl)?.groupValues?.get(1).orEmpty()
        val isMegaplayClone = embedHost.contains("megaplay") ||
            embedHost.contains("vidwish") ||
            embedHost.contains("vidtube") ||
            embedHost.contains("vidstream") ||
            embedHost.contains("vidplay")

        if (isMegaplayClone) {
            val stream = MegaPlayHelper.resolveStream(playerUrl, "$base/")
            if (stream != null) {
                return MegaPlayHelper.emitLinks(
                    stream.m3u8,
                    "https://$embedHost/",
                    stream.subtitles,
                    "AniSuge $serverName",
                    audioType,
                )
            }
            return EpisodeStreams(emptyList(), emptyList())
        }

        val viaRegistry = try {
            Extractors.resolve(playerUrl, "$base/")
        } catch (_: Exception) {
            EpisodeStreams(emptyList(), emptyList())
        }
        if (viaRegistry.sources.isNotEmpty()) return viaRegistry

        // Last resort: scan the embed page for a direct file link.
        return try {
            val pageHtml = fetchText(
                playerUrl,
                mapOf("User-Agent" to DESKTOP_UA, "Referer" to "$base/"),
            ) ?: return EpisodeStreams(emptyList(), emptyList())
            val found = Regex("""["'](https?://[^"']+\.(?:m3u8|mp4)(?:\?[^"']*)?)["']""")
                .findAll(pageHtml)
                .map { it.groupValues[1] }
                .distinct()
                .toList()
            if (found.isEmpty()) return EpisodeStreams(emptyList(), emptyList())
            EpisodeStreams(
                sources = found.map { u ->
                    StreamSource(
                        url = u,
                        quality = 0,
                        isM3u8 = u.contains(".m3u8"),
                        serverName = "AniSuge $serverName",
                        headers = mapOf("Referer" to playerUrl),
                    )
                },
                subtitles = emptyList(),
            )
        } catch (_: Exception) {
            EpisodeStreams(emptyList(), emptyList())
        }
    }

    private fun String.padBase64(): String {
        var s = this
        while (s.length % 4 != 0) s += "="
        return s
    }

    // ------------------------------------------------------------------
    // AniSugeMapper / PaheDownloadResolver / KwikExtractor
    // (port of AniSugeResolver.kt)
    // ------------------------------------------------------------------

    private object AniSugeMapper {
        class MapperEntry(val url: String?, val downloads: Map<String, String>)

        suspend fun fetchProviders(
            malId: String,
            slug: String,
            timestamp: String,
        ): Map<String, Pair<MapperEntry?, MapperEntry?>>? {
            val text = try {
                Http.get(
                    MAPPER_API + "$malId/$slug/$timestamp",
                    mapOf(
                        "User-Agent" to DESKTOP_UA,
                        "Accept" to "application/json",
                    ),
                )
            } catch (_: Exception) {
                return null
            }
            val root = try {
                Http.json.parseToJsonElement(text).jsonObject
            } catch (_: Exception) {
                return null
            }
            val out = linkedMapOf<String, Pair<MapperEntry?, MapperEntry?>>()
            for ((key, value) in root) {
                if (value !is JsonObject) continue
                out[key] = parseEntry(value["sub"]) to parseEntry(value["dub"])
            }
            return out
        }

        private fun parseEntry(node: kotlinx.serialization.json.JsonElement?): MapperEntry? {
            if (node == null || node !is JsonObject) return null
            val url = (node["url"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            val downloads = linkedMapOf<String, String>()
            val dl = node["download"]
            if (dl is JsonObject) {
                for ((q, u) in dl) {
                    val s = (u as? kotlinx.serialization.json.JsonPrimitive)
                        ?.takeIf { it.isString }?.content
                    if (s != null && s.startsWith("http")) downloads[q] = s
                }
            }
            if (url == null && downloads.isEmpty()) return null
            return MapperEntry(url, downloads)
        }

        fun displayProviderName(key: String): String = when (key.lowercase()) {
            "gogoanime" -> "Vidstream"
            "anivibe" -> "vibe-Stream"
            "animepahe" -> "Kiwi-Stream"
            else -> key.replaceFirstChar { it.uppercase() }
        }
    }

    private object PaheDownloadResolver {
        private val workersUrlRegex = Regex("""const\s+url\s*=\s*"(https?://[^"]+)"""")
        private val anyHttpsRegex = Regex(""""(https?://[^"]*workers\.dev[^"]*)"""")

        /** Follows AnimePahe's download page to the Kwik redirector URL. */
        suspend fun resolveKwikUrl(paheUrl: String): String? {
            val page = try {
                Http.get(paheUrl, mapOf("User-Agent" to DESKTOP_UA))
            } catch (_: Exception) {
                return null
            }
            val base = workersUrlRegex.find(page)?.groupValues?.get(1)
                ?: anyHttpsRegex.find(page)?.groupValues?.get(1)
                ?: return null
            val redirector = base.trimEnd('/') + "/" + paheUrl.trimEnd('/').substringAfterLast('/')
            return try {
                val (code, location) = getNoRedirect(redirector)
                if (code == 302 && location != null && location.startsWith("http")) location else null
            } catch (_: Exception) {
                null
            }
        }
    }

    private object KwikExtractor {
        private val sourceRegex = Regex("""source\s*=\s*["']([^"']+)["']""")
        private val formActionRegex = Regex("""action="([^"]+)"""")
        private val formTokenRegex = Regex("""value="([^"]+)"""")

        /** Returns (playUrl, kwikPageUrl) or null. */
        suspend fun resolve(url: String, referer: String?): Pair<String, String>? {
            val pageHtml: String
            val pageUrl: String
            try {
                val (code, body) = Http.getWithCode(
                    url,
                    mapOf(
                        "User-Agent" to DESKTOP_UA,
                        "Referer" to (referer ?: "https://kwik.cx"),
                    ),
                )
                if (code !in 200..299) return null
                pageHtml = body
                pageUrl = url
            } catch (_: Exception) {
                return null
            }

            val unpacked = try {
                val packed = Jsoup.parse(pageHtml)
                    .selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data()
                packed?.let { unpackPacked(it) }
            } catch (_: Exception) {
                null
            }

            if (unpacked != null) {
                sourceRegex.find(unpacked)?.groupValues?.get(1)?.let { src ->
                    if (src.startsWith("http")) return src to pageUrl
                }
                val action = formActionRegex.find(unpacked)?.groupValues?.get(1)
                val token = formTokenRegex.find(unpacked)?.groupValues?.get(1)
                if (action != null && token != null) {
                    return postForRedirect(action, token, pageUrl)
                }
            }

            sourceRegex.find(pageHtml)?.groupValues?.get(1)?.let { src ->
                if (src.startsWith("http") && (src.contains(".m3u8") || src.contains(".mp4"))) {
                    return src to pageUrl
                }
            }
            return null
        }

        private suspend fun postForRedirect(
            action: String,
            token: String,
            pageUrl: String,
        ): Pair<String, String>? {
            var tries = 0
            var code = 419
            var location = ""
            while (code != 302 && tries < 10) {
                try {
                    val (c, loc) = postFormNoRedirect(
                        action,
                        mapOf("_token" to token),
                        mapOf("Referer" to pageUrl),
                    )
                    code = c
                    if (code == 302) location = loc ?: ""
                } catch (_: Exception) {
                }
                tries++
            }
            return location.takeIf { it.startsWith("http") }?.let { it to pageUrl }
        }

        /**
         * Minimal Dean Edwards P.A.C.K.E.R. unpacker (the packed blob Kwik
         * serves). Replaces CloudStream's getAndUnpack dependency.
         */
        private fun unpackPacked(packedJs: String): String? {
            val m = Regex(
                """\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\)""",
                RegexOption.DOT_MATCHES_ALL,
            ).find(packedJs) ?: return null
            var p = m.groupValues[1]
                .replace("\\'", "'")
                .replace("\\\\", "\\")
            val radix = m.groupValues[2].toIntOrNull() ?: return null
            var count = m.groupValues[3].toIntOrNull() ?: return null
            val symtab = m.groupValues[4].split("|")
            // JS: e = c => (c < a ? '' : e(c/a floor)) + ((c%a) > 35 ? chr(c%a+29) : (c%a).toString(36))
            fun enc(x: Int): String {
                var c = x
                val prefix = if (c < radix) "" else enc(c / radix)
                c %= radix
                val suffix = if (c > 35) (c + 29).toChar().toString() else c.toString(36)
                return prefix + suffix
            }
            while (count-- > 0) {
                if (count < symtab.size && symtab[count].isNotEmpty()) {
                    val word = enc(count)
                    p = Regex("\\b${Regex.escape(word)}\\b").replace(p) { symtab[count] }
                }
            }
            return p
        }
    }

    // ------------------------------------------------------------------
    // MegaPlay clone resolution (port of MegaPlayHelper.kt)
    // ------------------------------------------------------------------

    private object MegaPlayHelper {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        class MegaPlayStream(
            val m3u8: String,
            val subtitles: List<Pair<String, String>>,
            val audioType: String? = null,
        )

        suspend fun resolveStream(
            embedUrl: String,
            referer: String?,
        ): MegaPlayStream? {
            val host = Regex("""https?://([^/]+)""").find(embedUrl)?.groupValues?.get(1)
                ?: return null
            val pageHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to (referer ?: "https://$host/"),
            )
            val pageHtml = try {
                Http.get(embedUrl, pageHeaders)
            } catch (_: Exception) {
                return null
            }
            val streamId = Regex("""data-id=["'](\d+)""").find(pageHtml)?.groupValues?.get(1)
                ?: Regex("""data-realid=["'](\d+)""").find(pageHtml)?.groupValues?.get(1)
                ?: Regex("""/stream/s-\d+/(\d+)/""").find(embedUrl)?.groupValues?.get(1)
                ?: return null
            val audioType = audioTypeFromUrl(embedUrl)
                ?: Regex("""type\s*:\s*['"](dub|sub)['"]""").find(pageHtml)?.groupValues?.get(1)
                ?: "sub"
            val altHost = Regex("""data-domain=["']([^"']+)["']""").find(pageHtml)
                ?.groupValues?.get(1)
            val hosts = listOfNotNull(host, altHost?.takeIf { it != host }).distinct()
            for (apiHost in hosts) {
                val base = "https://$apiHost"
                val ajaxHeaders = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "*/*",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Origin" to base,
                    "Referer" to embedUrl,
                )
                for (endpoint in listOf("getSourcesNew", "getSources")) {
                    val root = fetchJson("$base/stream/$endpoint?id=$streamId&type=$audioType", ajaxHeaders)
                        ?: continue
                    val streamUrl = extractStream(root, base)
                    if (streamUrl != null) {
                        val subs = parseSubtitleTracks(root, streamUrl)
                        return MegaPlayStream(streamUrl, subs, audioType)
                    }
                }
            }
            return null
        }

        private fun audioTypeFromUrl(url: String): String? =
            Regex("""/(dub|sub)(?:[/?#]|$)""").find(url)?.groupValues?.get(1)

        private suspend fun fetchJson(
            url: String,
            headers: Map<String, String>,
        ): JsonObject? {
            return try {
                val text = Http.get(url, headers)
                Http.json.parseToJsonElement(text).jsonObject
            } catch (_: Exception) {
                null
            }
        }

        private suspend fun extractStream(root: JsonObject, base: String): String? {
            val sources = root["sources"]
            val plain = when {
                sources == null || sources is kotlinx.serialization.json.JsonNull -> null
                sources is JsonObject -> (sources["file"] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.takeIf { it.isString }?.content
                sources is kotlinx.serialization.json.JsonArray && sources.isNotEmpty() -> {
                    val first = sources[0]
                    if (first is JsonObject) {
                        (first["file"] as? kotlinx.serialization.json.JsonPrimitive)
                            ?.takeIf { it.isString }?.content
                    } else {
                        null
                    }
                }
                else -> null
            }
            if (!plain.isNullOrBlank()) return migrateLegacyUrl(plain)
            val enc = (root["enc"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.takeIf { it.isString }?.content ?: return null
            val resolved = MegaPlayCipher.resolveEncStreamUrl(enc, base) ?: return null
            return migrateLegacyUrl(resolved)
        }

        private fun migrateLegacyUrl(url: String): String {
            if (!url.contains("https://cdn.imgnex.top/anime")) return url
            return url.replace("https://cdn.imgnex.top/anime", "https://megap.norami.top")
        }

        private fun parseSubtitleTracks(
            root: JsonObject,
            m3u8: String,
        ): List<Pair<String, String>> {
            val subs = mutableListOf<Pair<String, String>>()
            val origin = Regex("""https?://[^/]+""").find(m3u8)?.value ?: "https://megap.norami.top"
            val tracks = root["tracks"]
            if (tracks !is kotlinx.serialization.json.JsonArray) return subs
            for (element in tracks) {
                if (element !is JsonObject) continue
                val kind = (element["kind"] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.takeIf { it.isString }?.content ?: continue
                if (kind != "captions" && kind != "subtitles") continue
                val file = (element["file"] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.takeIf { it.isString }?.content ?: continue
                if (file.isBlank()) continue
                val migrated = if (file.contains("https://cdn.imgnex.top/anime")) {
                    file.replace("https://cdn.imgnex.top/anime", origin)
                } else {
                    file
                }
                val label = (element["label"] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.takeIf { it.isString }?.content ?: "English"
                subs.add(label to migrated)
            }
            return subs
        }

        private const val TOKEN_KEY = "MpCdnT0k3n!9f2K#xQ7vL5mR8wN1pY4s"
        private const val TOKEN_LIFETIME_SECONDS = 7L * 24L * 60L * 60L
        private val hexIdsRegex =
            Regex("""/([a-f0-9]{32})/([a-f0-9]{32})/""", RegexOption.IGNORE_CASE)

        private fun b64url(bytes: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        fun signUrl(url: String): String {
            val m = hexIdsRegex.find(url) ?: return url
            val expires = System.currentTimeMillis() / 1000L + TOKEN_LIFETIME_SECONDS
            val payload = "$expires|${m.groupValues[1].lowercase()}/${m.groupValues[2].lowercase()}"
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(TOKEN_KEY.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            val signature = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
            val token = "${b64url(payload.toByteArray(Charsets.UTF_8))}.${b64url(signature)}"
            val sep = if (url.contains('?')) "&" else "?"
            return "$url${sep}token=$token"
        }

        private data class VariantEntry(val url: String, val quality: Int?)

        private val audioRenditionRegex =
            Regex("""#EXT-X-MEDIA:TYPE=AUDIO[^\r\n]*URI="""")

        private fun withTypeParam(url: String, audioType: String?): String {
            if (audioType != "sub" && audioType != "dub") return url
            val sep = if (url.contains('?')) "&" else "?"
            return "$url${sep}t=$audioType"
        }

        private fun parseVariants(masterUrl: String, masterText: String): List<VariantEntry> {
            val base = masterUrl.substringBefore('?')
                .let { it.substringBeforeLast('/') + "/" }
            val out = mutableListOf<VariantEntry>()
            val lines = masterText.lines()
            var i = 0
            while (i < lines.size) {
                val line = lines[i].trim()
                if (line.startsWith("#EXT-X-STREAM-INF:")) {
                    val res = Regex("""RESOLUTION=(\d+)x(\d+)""").find(line)
                    val quality = res?.groupValues?.get(2)?.toIntOrNull()
                    var j = i + 1
                    while (j < lines.size && (lines[j].isBlank() || lines[j].startsWith("#"))) j++
                    if (j < lines.size) {
                        val uri = lines[j].trim()
                        if (uri.isNotEmpty()) {
                            val absolute = if (uri.startsWith("http")) uri else base + uri
                            out.add(VariantEntry(absolute, quality))
                        }
                        i = j
                    }
                }
                i++
            }
            return out
        }

        /** Emits per-variant signed sources (or the master when applicable). */
        suspend fun emitLinks(
            m3u8: String,
            referer: String,
            subtitles: List<Pair<String, String>>,
            label: String,
            audioType: String? = null,
        ): EpisodeStreams {
            val playHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to referer,
            )
            val signedMaster = signUrl(m3u8)
            val masterText = try {
                Http.get(signedMaster, playHeaders)
            } catch (_: Exception) {
                null
            }
            val sources = mutableListOf<StreamSource>()
            if (masterText != null && audioRenditionRegex.containsMatchIn(masterText)) {
                // Renditions mean variants are video-only; only the master
                // carries audio, and t= picks the default language.
                val best = parseVariants(m3u8, masterText).maxOfOrNull { it.quality ?: 0 }
                sources += StreamSource(
                    url = signUrl(withTypeParam(m3u8, audioType)),
                    quality = best?.takeIf { it > 0 } ?: 0,
                    isM3u8 = true,
                    serverName = label,
                    headers = playHeaders,
                )
            } else {
                val variants = masterText?.let { parseVariants(m3u8, it) } ?: emptyList()
                if (variants.isNotEmpty()) {
                    for (v in variants) {
                        val suffix = v.quality?.let { "${it}p" } ?: ""
                        sources += StreamSource(
                            url = signUrl(v.url),
                            quality = v.quality ?: 0,
                            isM3u8 = true,
                            serverName = if (suffix.isEmpty()) label else "$label $suffix",
                            headers = playHeaders,
                        )
                    }
                } else if (masterText != null && masterText.contains("#EXTM3U")) {
                    sources += StreamSource(
                        url = signedMaster,
                        quality = 0,
                        isM3u8 = true,
                        serverName = label,
                        headers = playHeaders,
                    )
                } else if (masterText == null) {
                    // Master playlist fetch failed: still hand the player the
                    // signed master URL instead of nothing (deviation from
                    // the original, which emitted nothing in this case).
                    sources += StreamSource(
                        url = signedMaster,
                        quality = 0,
                        isM3u8 = true,
                        serverName = label,
                        headers = playHeaders,
                    )
                }
            }
            val subs = subtitles.map { (subLabel, subUrl) ->
                SubtitleTrack(url = subUrl, language = subLabel, label = subLabel)
            }
            return EpisodeStreams(sources, subs)
        }
    }

    private object MegaPlayCipher {
        private const val FALLBACK_KEY_SEED = "i?LMTAx0Q6,:}50U"
        private const val FALLBACK_IV_SEED = "W0;27ToaUpl_P%'c"

        @Volatile
        private var cachedSeeds: Pair<String, String>? = null

        private val keyPairRegex =
            Regex("[A-Za-z]\\w*=\"([^\"]{16})\",[A-Za-z]\\w*=\"([^\"]{16})\"")
        private val fileRegex = Regex("\"file\"\\s*:\\s*\"([^\"]+)\"")

        private fun fallback() = Pair(FALLBACK_KEY_SEED, FALLBACK_IV_SEED)

        private suspend fun keySeedCandidates(baseUrl: String): List<Pair<String, String>> {
            cachedSeeds?.let { return listOf(it, fallback()) }
            val dynamic = try {
                val js = Http.get("$baseUrl/lib/newclient.min.js")
                keyPairRegex.find(js)?.groupValues?.let { g ->
                    Pair(g[1], g[2]).also { cachedSeeds = it }
                }
            } catch (_: Exception) {
                null
            }
            return listOfNotNull(dynamic, fallback())
        }

        private fun decryptToken(enc: String, keySeed: String, ivSeed: String): String? {
            return try {
                var b64 = enc.replace('-', '+').replace('_', '/')
                while (b64.length % 4 != 0) b64 += "="
                val cipherBytes = Base64.getDecoder().decode(b64)
                val seedBytes = keySeed.toByteArray(Charsets.UTF_8)
                val keyBytes = ByteArray(32)
                System.arraycopy(seedBytes, 0, keyBytes, 0, minOf(32, seedBytes.size))
                val ivBytes = ByteArray(16)
                val ivSeedBytes = ivSeed.toByteArray(Charsets.UTF_8)
                System.arraycopy(ivSeedBytes, 0, ivBytes, 0, minOf(16, ivSeedBytes.size))
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
                String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
            } catch (_: Exception) {
                null
            }
        }

        suspend fun resolveEncStreamUrl(enc: String, baseUrl: String): String? {
            for ((keySeed, ivSeed) in keySeedCandidates(baseUrl)) {
                val plain = decryptToken(enc, keySeed, ivSeed) ?: continue
                fileRegex.find(plain)?.groupValues?.get(1)?.let { return it }
            }
            return null
        }
    }
}
