// Ported from AnikotoProvider.kt + AnikotoExtractors.kt + MegaPlayResolver.kt
// in KSHITIJ8473/raghav (GPL-3.0, based on KSHITIJ8473/raghav). CloudStream
// APIs replaced with the app's own core (Http / Models / Extractors); no
// com.lagradost imports. Jackson JSON parsing replaced with org.json.
package com.cinemora.app.providers

import android.util.Base64
import com.cinemora.app.core.AnimeCard
import com.cinemora.app.core.AnimeDetails
import com.cinemora.app.core.AnimeProvider
import com.cinemora.app.core.AudioKind
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class AnikotoProvider : AnimeProvider {
    override val id: String = "anikoto"
    override val name: String = "AniKotoAnime"
    override val lang: Lang = Lang.ENGLISH
    override var baseUrl: String = "https://anikototv.to"

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5",
    )

    private fun ajaxHeaders(referer: String) = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        "X-Requested-With" to "XMLHttpRequest",
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "Referer" to referer,
    )

    private suspend fun base(): String {
        DomainHelper.getDomain("anikoto")?.let { baseUrl = it }
        return baseUrl
    }

    override suspend fun homeSections(): List<HomeSection> {
        val rows = listOf(
            "latest-updated" to "Latest Updated",
            "most-viewed" to "Most Popular",
            "status/currently-airing" to "Ongoing",
            "type/movie" to "Movies",
        )
        return try {
            val base = base()
            coroutineScope {
                rows.map { (path, title) ->
                    async {
                        try {
                            val doc = Http.getDoc("$base/$path?page=1", browserHeaders)
                            val items = doc.select("div.ani.items > div.item")
                                .mapNotNull { it.toSearchResult(base) }
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
            val base = base()
            val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
            val doc = Http.getDoc("$base/filter?keyword=$encodedQuery", browserHeaders)
            doc.select("div.ani.items > div.item").mapNotNull { it.toSearchResult(base) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun loadDetails(id: String): AnimeDetails? {
        return try {
            val base = base()
            val url = Http.absUrl(base, id)
            val doc = try {
                Http.getDoc(url, browserHeaders)
            } catch (_: Exception) {
                return null
            }
            val title = doc.selectFirst("#w-info h1.title, h1[itemprop=name], .title[itemprop=name]")
                ?.text()?.trim()
                ?: doc.selectFirst("h1.title")?.text()?.trim()
                ?: return null
            val poster = doc.selectFirst("#w-info .poster img, img[itemprop=image], .poster img")
                ?.let { it.attr("data-src").ifBlank { it.attr("src") } }
            val description =
                doc.selectFirst("#w-info .synopsis .content, #w-info .synopsis, .synopsis .content")
                    ?.text()
            val genres = doc.select("#w-info a[href*='/genre/'], .meta a[href*='/genre/']")
                .map { it.text().trim() }

            // The page may render differently depending on the skin, try a few.
            val animeId = doc.selectFirst("#watch-main")?.attr("data-id")
                ?: doc.selectFirst("[data-id]")?.attr("data-id")
                ?: Regex("""data-id=["'](\d+)["']""").find(doc.html())?.groupValues?.get(1)

            val subEpisodes = mutableListOf<EpisodeItem>()
            val dubEpisodes = mutableListOf<EpisodeItem>()

            if (animeId != null) {
                try {
                    val json = Http.get(
                        "$base/ajax/episode/list/$animeId",
                        ajaxHeaders(url),
                    )
                    val html = jsonResultString(json)
                    Jsoup.parse(html).select("a[data-ids]").forEach { el ->
                        val serverIds = el.attr("data-ids")
                        val episodeNumber = el.attr("data-num").toIntOrNull()
                        val hasSub = el.attr("data-sub") == "1"
                        val hasDub = el.attr("data-dub") == "1"
                        if (serverIds.isBlank()) return@forEach

                        val dTitleSpan = el.selectFirst(".d-title")
                        val episodeName = dTitleSpan?.text()?.trim()?.ifBlank { null }
                            ?: el.parent()?.attr("title")?.trim()?.ifBlank { null }
                            ?: dTitleSpan?.attr("data-jp")?.trim()?.ifBlank { null }
                            ?: "Episode ${episodeNumber ?: ""}"

                        if (hasSub || !hasDub) {
                            subEpisodes.add(
                                EpisodeItem(
                                    id = "anikoto|$url|$serverIds|sub",
                                    number = episodeNumber ?: 0,
                                    title = episodeName,
                                    season = 1,
                                ),
                            )
                        }
                        if (hasDub) {
                            dubEpisodes.add(
                                EpisodeItem(
                                    id = "anikoto|$url|$serverIds|dub",
                                    number = episodeNumber ?: 0,
                                    title = episodeName,
                                    season = 2,
                                ),
                            )
                        }
                    }
                } catch (_: Exception) {
                }
            }

            // Last resort when the ajax list never answered: walk the episode links.
            if (subEpisodes.isEmpty() && dubEpisodes.isEmpty()) {
                doc.select("a[href*='/ep-']").mapIndexed { i, el ->
                    subEpisodes.add(
                        EpisodeItem(
                            id = Http.absUrl(base, el.attr("href")),
                            number = i + 1,
                            title = el.text().ifBlank { "Episode ${i + 1}" },
                            season = 1,
                        ),
                    )
                }
            }

            val seasons = mutableListOf<SeasonGroup>()
            if (subEpisodes.isNotEmpty()) seasons.add(SeasonGroup(1, "Season 1", subEpisodes))
            if (dubEpisodes.isNotEmpty()) seasons.add(SeasonGroup(2, "Season 1 (Dub)", dubEpisodes))
            val audioKinds = mutableSetOf<AudioKind>()
            if (subEpisodes.isNotEmpty()) audioKinds.add(AudioKind.SUB)
            if (dubEpisodes.isNotEmpty()) audioKinds.add(AudioKind.DUB)

            AnimeDetails(
                id = url,
                title = title,
                posterUrl = poster?.let { Http.absUrl(base, it) },
                bannerUrl = null,
                description = description?.takeIf { it.isNotBlank() },
                genres = genres,
                status = null,
                year = null,
                rating = null,
                seasons = seasons,
                providerId = id,
                lang = lang,
                audioKinds = audioKinds.ifEmpty { setOf(AudioKind.SUB) },
            )
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun loadStreams(episodeId: String): EpisodeStreams {
        return try {
            val base = base()
            // CloudStream can prefix the data with mainUrl when it does not start
            // with http; strip it so the anikoto| marker is found.
            val cleanData = when {
                episodeId.startsWith("$base/anikoto|") -> episodeId.removePrefix("$base/")
                episodeId.startsWith("/anikoto|") -> episodeId.removePrefix("/")
                else -> episodeId
            }

            if (cleanData.startsWith("anikoto|")) {
                val parts = cleanData.split("|", limit = 4)
                if (parts.size < 4) return EpisodeStreams(emptyList(), emptyList())
                val referer = parts[1]
                val serverIds = parts[2]
                val audioType = parts[3].ifBlank { "sub" }
                if (serverIds.isBlank()) return EpisodeStreams(emptyList(), emptyList())
                return resolveServers(serverIds, referer, audioType)
            }

            // The episode page carries the same data-id as the anime page, so the
            // ajax list can be retried here when it failed during load.
            val doc = Http.getDoc(cleanData, browserHeaders)
            val animeId = doc.selectFirst("#watch-main")?.attr("data-id")
                ?: doc.selectFirst("[data-id]")?.attr("data-id")
                ?: Regex("""data-id=["'](\d+)["']""").find(doc.html())?.groupValues?.get(1)
            val epNum = Regex("""/ep-(\d+)""").find(cleanData)?.groupValues?.get(1)
                ?.toIntOrNull() ?: 1
            if (animeId.isNullOrBlank()) return EpisodeStreams(emptyList(), emptyList())

            val json = Http.get(
                "$base/ajax/episode/list/$animeId",
                ajaxHeaders(cleanData),
            )
            val html = jsonResultString(json)
            if (html.isBlank()) return EpisodeStreams(emptyList(), emptyList())
            val epEl = Jsoup.parse(html).select("a[data-ids]").find {
                it.attr("data-num").toIntOrNull() == epNum
            } ?: Jsoup.parse(html).selectFirst("a[data-ids]")
                ?: return EpisodeStreams(emptyList(), emptyList())
            val serverIds = epEl.attr("data-ids")
            val audioType = if (epEl.attr("data-dub") == "1") "dub" else "sub"
            if (serverIds.isBlank()) return EpisodeStreams(emptyList(), emptyList())
            resolveServers(serverIds, cleanData, audioType)
        } catch (_: Exception) {
            EpisodeStreams(emptyList(), emptyList())
        }
    }

    private suspend fun resolveServers(
        serverIds: String,
        referer: String,
        audioType: String,
    ): EpisodeStreams {
        return try {
            val base = baseUrl
            // serverIds is a base64 blob with '+', '=' and '/'; without encoding
            // '+' turns into a space server-side and the api answers 500.
            val encodedIds = URLEncoder.encode(serverIds, "UTF-8")
            val serverListJson = try {
                Http.get(
                    "$base/ajax/server/list?servers=$encodedIds",
                    ajaxHeaders(referer),
                )
            } catch (_: Exception) {
                return EpisodeStreams(emptyList(), emptyList())
            }
            val serverListHtml = jsonResultString(serverListJson)
            if (serverListHtml.isBlank()) return EpisodeStreams(emptyList(), emptyList())
            val serverDoc = Jsoup.parse(serverListHtml)

            val typeSelectors = if (audioType == "dub") {
                listOf("div.type[data-type=dub]")
            } else {
                listOf("div.type[data-type=sub]", "div.type[data-type=hsub]")
            }
            val preferredServers = typeSelectors.flatMap { sel ->
                serverDoc.select("$sel li[data-link-id]")
            }.ifEmpty {
                serverDoc.select("li[data-link-id]")
            }
            val serverEntries = preferredServers.mapNotNull { el ->
                val lid = el.attr("data-link-id")
                if (lid.isBlank()) null
                else lid to el.text().trim().ifBlank { "Server" }
            }.distinctBy { it.first }
            if (serverEntries.isEmpty()) return EpisodeStreams(emptyList(), emptyList())

            val sources = mutableListOf<StreamSource>()
            val subtitles = mutableListOf<SubtitleTrack>()
            for ((linkId, serverName) in serverEntries) {
                try {
                    val encodedLinkId = URLEncoder.encode(linkId, "UTF-8")
                    val serverJson = Http.get(
                        "$base/ajax/server?get=$encodedLinkId",
                        ajaxHeaders(referer),
                    )
                    var embedUrl = jsonResultUrl(serverJson)
                    if (embedUrl.isNullOrBlank()) continue
                    if (audioType == "dub" && embedUrl.contains("/sub")) {
                        embedUrl = embedUrl.replace("/sub", "/dub")
                    } else if (audioType == "sub" && embedUrl.contains("/dub")) {
                        embedUrl = embedUrl.replace("/dub", "/sub")
                    }
                    val resolved = resolveEmbedInline(embedUrl, referer, audioType, serverName)
                    sources.addAll(resolved.sources)
                    subtitles.addAll(resolved.subtitles)
                } catch (_: Exception) {
                }
            }
            EpisodeStreams(sources, subtitles)
        } catch (_: Exception) {
            EpisodeStreams(emptyList(), emptyList())
        }
    }

    private suspend fun resolveEmbedInline(
        url: String,
        referer: String,
        audioType: String,
        serverName: String,
    ): EpisodeStreams {
        val normalizedUrl = when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$baseUrl$url"
            else -> url
        }

        getHashM3u8(normalizedUrl)?.let { m3u8 ->
            return EpisodeStreams(
                sources = listOf(
                    StreamSource(
                        url = m3u8,
                        quality = 0,
                        isM3u8 = true,
                        serverName = "AniKoto $serverName",
                        headers = mapOf(
                            "Referer" to normalizedUrl,
                            "Origin" to "https://mewcdn.online",
                        ),
                    ),
                ),
                subtitles = emptyList(),
            )
        }

        val domain = Regex("""https?://([^/]+)""").find(normalizedUrl)?.groupValues?.get(1) ?: ""
        val isMegaPlayDomain = domain.contains("megaplay", ignoreCase = true) ||
            domain.contains("vidwish", ignoreCase = true) ||
            domain.contains("vidtube", ignoreCase = true)

        return if (isMegaPlayDomain) {
            resolveMegaPlayInline(normalizedUrl, referer, domain, audioType, serverName)
        } else {
            try {
                Extractors.resolve(normalizedUrl, referer)
            } catch (_: Exception) {
                EpisodeStreams(emptyList(), emptyList())
            }
        }
    }

    private suspend fun resolveMegaPlayInline(
        url: String,
        referer: String,
        domain: String,
        audioType: String,
        serverName: String,
    ): EpisodeStreams {
        val displayType = if (audioType == "dub") "DUB" else "SUB"
        val label = "AniKoto $serverName $displayType"
        val stream = MegaPlayResolver.resolveStream(url, referer) ?: return EpisodeStreams(emptyList(), emptyList())
        return MegaPlayResolver.emitLinks(
            "AniKoto", label, stream.m3u8, "https://$domain/",
            stream.subtitles, audioType,
        )
    }

    private fun jsonResultString(json: String): String {
        return try {
            val obj = JSONObject(json)
            if (obj.optInt("status") != 200) ""
            else obj.optString("result", "")
        } catch (_: Exception) {
            ""
        }
    }

    private fun jsonResultUrl(json: String): String? {
        return try {
            val obj = JSONObject(json)
            if (obj.optInt("status") != 200) null
            else obj.optJSONObject("result")?.optString("url")
        } catch (_: Exception) {
            null
        }
    }

    private fun getHashM3u8(url: String): String? {
        val encoded = url.substringAfter("#", "").substringBefore("#")
            .takeIf { it.isNotBlank() } ?: return null
        val decoded = try {
            String(Base64.decode(encoded, Base64.DEFAULT))
        } catch (_: Exception) {
            null
        } ?: return null
        return proxyPlayerHost(decoded).takeIf { it.startsWith("http") && it.contains(".m3u8") }
    }

    // The player hands out urls on hosts that 403 cross-origin requests;
    // these mirrors serve the same files with permissive headers.
    private fun proxyPlayerHost(url: String): String {
        var out = url
            .replace("vibeplayer.site", "nanobyte.bigdreamsmalldih.site")
        for (i in 1..16) {
            out = out.replace("vault-%02d.uwucdn.top".format(i), "uwu$i.bigdreamsmalldih.site")
        }
        out = out.replace("vault-99.uwucdn.top", "uwu17.bigdreamsmalldih.site")
        for (i in 10..16) {
            out = out.replace("vault-$i.owocdn.top", "$i.bigdreamsmalldih.site")
        }
        out = out.replace("vault-99.owocdn.top", "99.bigdreamsmalldih.site")
        return out
    }

    private fun Element.toSearchResult(base: String): AnimeCard? {
        val titleElement = selectFirst("a.name.d-title")
            ?: selectFirst("a[title]")
            ?: selectFirst("a[href*='/watch/']")
            ?: return null
        val href = titleElement.attr("href")
            .ifBlank { selectFirst("div.poster a, a")?.attr("href").orEmpty() }
        val title = titleElement.text().trim().ifBlank { titleElement.attr("title").trim() }
        if (href.isBlank() || title.isBlank()) return null
        val poster = selectFirst("div.poster img, img")
            ?.let { it.attr("data-src").ifBlank { it.attr("src") } }
        return AnimeCard(
            id = Http.absUrl(base, href),
            title = title,
            posterUrl = poster?.let { Http.absUrl(base, it) },
            providerId = id,
            lang = lang,
        )
    }
}

/** MegaPlay clones encrypt the `enc` field of their sources response; the key
 * material sits in lib/newclient.min.js, with pinned fallbacks. */
private object AnikotoMegaPlayCipher {
    private const val FALLBACK_KEY_SEED = "i?LMTAx0Q6,:}50U"
    private const val FALLBACK_IV_SEED = "W0;27ToaUpl_P%'c"

    @Volatile
    private var cachedSeeds: Pair<String, String>? = null

    private val keyPairRegex = Regex("[A-Za-z]\\w*=\"([^\"]{16})\",[A-Za-z]\\w*=\"([^\"]{16})\"")
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
            val cipherBytes = Base64.decode(b64, Base64.DEFAULT)
            val seedBytes = keySeed.toByteArray(Charsets.UTF_8)
            val keyBytes = ByteArray(32)
            System.arraycopy(seedBytes, 0, keyBytes, 0, minOf(32, seedBytes.size))
            val ivBytes = ByteArray(16)
            val ivSeedBytes = ivSeed.toByteArray(Charsets.UTF_8)
            System.arraycopy(ivSeedBytes, 0, ivBytes, 0, minOf(16, ivSeedBytes.size))
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                IvParameterSpec(ivBytes),
            )
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

/** Port of MegaPlayResolver (AnikotoExtractors' MegaPlay / Vidwish / Vidtube). */
private object MegaPlayResolver {
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    class MegaPlayStream(
        val m3u8: String,
        val subtitles: List<Pair<String, String>>,
        val audioType: String? = null,
    )

    fun audioTypeFromUrl(url: String): String? =
        Regex("""/(dub|sub)(?:[/?#]|$)""").find(url)?.groupValues?.get(1)

    // The ?s= param is never forwarded: the tcdn flavor serves segments with
    // a 252-byte prefix ExoPlayer cannot parse, the default cdn plays clean.
    suspend fun resolveStream(embedUrl: String, referer: String?): MegaPlayStream? {
        val host = Regex("""https?://([^/]+)""").find(embedUrl)?.groupValues?.get(1)
            ?: return null
        val pageHeaders = mapOf(
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
                "Accept" to "*/*",
                "X-Requested-With" to "XMLHttpRequest",
                "Origin" to base,
                "Referer" to embedUrl,
            )
            for (endpoint in listOf("getSourcesNew", "getSources")) {
                val url = "$base/stream/$endpoint?id=$streamId&type=$audioType"
                val root = fetchJson(url, ajaxHeaders) ?: continue
                val streamUrl = extractStream(root, base)
                if (streamUrl != null) {
                    return MegaPlayStream(streamUrl, parseSubtitleTracks(root), audioType)
                }
            }
        }
        return null
    }

    private suspend fun extractStream(root: JSONObject, base: String): String? {
        val sources = root.opt("sources")
        val plain = when (sources) {
            null, JSONObject.NULL -> null
            is JSONObject -> sources.optString("file").takeIf { it.isNotBlank() }
            is org.json.JSONArray -> if (sources.length() > 0) {
                sources.optJSONObject(0)?.optString("file")?.takeIf { it.isNotBlank() }
            } else {
                null
            }
            else -> null
        }
        if (!plain.isNullOrBlank()) return plain
        val enc = root.optString("enc").takeIf { it.isNotBlank() } ?: return null
        return AnikotoMegaPlayCipher.resolveEncStreamUrl(enc, base)
    }

    private fun parseSubtitleTracks(root: JSONObject): List<Pair<String, String>> {
        val subs = mutableListOf<Pair<String, String>>()
        val tracks = root.optJSONArray("tracks") ?: return subs
        for (i in 0 until tracks.length()) {
            val element = tracks.optJSONObject(i) ?: continue
            val kind = element.optString("kind")
            if (kind != "captions" && kind != "subtitles") continue
            val file = element.optString("file")
            if (file.isBlank()) continue
            subs.add((element.optString("label").ifBlank { "English" }) to file)
        }
        return subs
    }

    private suspend fun fetchJson(url: String, headers: Map<String, String>): JSONObject? {
        return try {
            JSONObject(Http.get(url, headers))
        } catch (_: Exception) {
            null
        }
    }

    // The cdn's openresty layer 403s master.m3u8 without a token; the web
    // player signs for 90s but the server only rejects tokens already expired.
    private const val TOKEN_KEY = "MpCdnT0k3n!9f2K#xQ7vL5mR8wN1pY4s"
    private const val TOKEN_LIFETIME_SECONDS = 7L * 24L * 60L * 60L
    private val hexIdsRegex = Regex("""/([a-f0-9]{32})/([a-f0-9]{32})/""", RegexOption.IGNORE_CASE)

    private fun b64url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

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

    private val audioRenditionRegex = Regex("""#EXT-X-MEDIA:TYPE=AUDIO[^\r\n]*URI=""")

    private fun withTypeParam(url: String, audioType: String?): String {
        if (audioType != "sub" && audioType != "dub") return url
        val sep = if (url.contains('?')) "&" else "?"
        return "$url${sep}t=$audioType"
    }

    // I-frame entries only appear as attributes of #EXT-X-I-FRAME-STREAM-INF,
    // never on their own line.
    private fun parseVariants(masterUrl: String, masterText: String): List<VariantEntry> {
        val base = masterUrl.substringBefore('?').let { it.substringBeforeLast('/') + "/" }
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

    suspend fun emitLinks(
        source: String,
        label: String,
        m3u8: String,
        referer: String,
        subtitles: List<Pair<String, String>>,
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
            // Renditions mean the variants are video-only, only the master
            // carries audio; t= picks the language marked as default.
            val best = parseVariants(m3u8, masterText).maxOfOrNull { it.quality ?: 0 }
            sources.add(
                StreamSource(
                    url = signUrl(withTypeParam(m3u8, audioType)),
                    quality = best?.takeIf { it > 0 } ?: 0,
                    isM3u8 = true,
                    serverName = label,
                    headers = playHeaders,
                ),
            )
        } else {
            val variants = masterText?.let { parseVariants(m3u8, it) } ?: emptyList()
            if (variants.isNotEmpty()) {
                for (v in variants) {
                    val suffix = v.quality?.let { "${it}p" } ?: ""
                    sources.add(
                        StreamSource(
                            url = signUrl(v.url),
                            quality = v.quality ?: 0,
                            isM3u8 = true,
                            serverName = if (suffix.isEmpty()) label else "$label $suffix",
                            headers = playHeaders,
                        ),
                    )
                }
            } else {
                sources.add(
                    StreamSource(
                        url = signedMaster,
                        quality = 0,
                        isM3u8 = true,
                        serverName = label,
                        headers = playHeaders,
                    ),
                )
            }
        }
        val subs = subtitles.map { (subLabel, subUrl) ->
            SubtitleTrack(subUrl, "en", subLabel)
        }
        return EpisodeStreams(sources, subs)
    }
}
