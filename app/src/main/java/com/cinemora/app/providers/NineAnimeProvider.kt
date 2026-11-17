// Ported from NineAnime.kt + NineAnimeExtractors.kt in KSHITIJ8473/raghav
// (GPL-3.0, based on KSHITIJ8473/raghav). CloudStream APIs replaced with the
// app's own core (Http / Models / Extractors); no com.lagradost imports.
// The Moon (bysesayeveum.com) extractor is NOT ported: it relied on
// CloudStream's WebViewResolver interceptor, which has no equivalent here —
// those embeds fall through to Extractors.resolve instead.
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
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class NineAnimeProvider : AnimeProvider {
    override val id: String = "nineanime"
    override val name: String = "NineAnime"
    override val lang: Lang = Lang.ENGLISH
    override var baseUrl: String = "https://9anime.org.lv"

    private suspend fun base(): String {
        DomainHelper.getDomain("nineanime")?.let { baseUrl = it }
        return baseUrl
    }

    override suspend fun homeSections(): List<HomeSection> {
        return try {
            val base = base()
            val doc = Http.getDoc("$base/")
            val items = doc.select("article.bs").mapNotNull { element ->
                val a = element.selectFirst("a") ?: return@mapNotNull null
                val href = a.attr("href")
                if (href.isBlank()) return@mapNotNull null
                val title = element.selectFirst(".entry-title")?.text()
                    ?: element.selectFirst("h2")?.text()
                    ?: a.text()
                if (title.isBlank()) return@mapNotNull null
                AnimeCard(
                    id = Http.absUrl(base, href),
                    title = title.trim(),
                    posterUrl = element.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() },
                    providerId = id,
                    lang = lang,
                )
            }
            listOf(HomeSection("Latest Releases", id, items))
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun search(query: String): List<AnimeCard> {
        if (query.isBlank()) return emptyList()
        return try {
            val base = base()
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val doc = Http.getDoc("$base/?s=$encoded")
            doc.select("article.bs").mapNotNull { element ->
                val a = element.selectFirst("a") ?: return@mapNotNull null
                val href = a.attr("href")
                if (href.isBlank()) return@mapNotNull null
                val title = element.selectFirst(".entry-title")?.text()
                    ?: element.selectFirst("h2")?.text()
                    ?: a.text()
                if (title.isBlank()) return@mapNotNull null
                AnimeCard(
                    id = Http.absUrl(base, href),
                    title = title.trim(),
                    posterUrl = element.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() },
                    providerId = id,
                    lang = lang,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun loadDetails(id: String): AnimeDetails? {
        return try {
            val base = base()
            var detailUrl = Http.absUrl(base, id)
            if (!detailUrl.contains("/anime/")) {
                val doc = Http.getDoc(detailUrl)
                val animeLink = doc.select("a[href*=/anime/]")
                    .map { it.attr("href") }
                    .firstOrNull { href ->
                        val path = href.substringAfter("/anime/").trim('/')
                        path.isNotEmpty() && !path.contains('/') &&
                            !path.contains('?') && !path.contains("page")
                    }
                if (animeLink != null) detailUrl = Http.absUrl(base, animeLink)
            }

            val doc = Http.getDoc(detailUrl)
            val title = doc.selectFirst("h1.entry-title")?.text()
                ?: doc.selectFirst("h1")?.text()
                ?: "Unknown"
            val posterUrl = doc.selectFirst(".thumb img")?.attr("src")
                ?: doc.selectFirst(".poster img")?.attr("src")
                ?: doc.selectFirst("img")?.attr("src")
            val plot = doc.selectFirst(".entry-content")?.text()
                ?: doc.selectFirst(".desc")?.text()
                ?: doc.selectFirst(".story")?.text()
            val tags = doc.select(".genxed a").map { it.text() }
            val infoText = doc.selectFirst(".info-content")?.text() ?: ""
            val status = when {
                infoText.contains("Ongoing", ignoreCase = true) -> "Ongoing"
                infoText.contains("Completed", ignoreCase = true) -> "Completed"
                else -> null
            }
            val isDub = title.contains("(Dub)", ignoreCase = true) ||
                detailUrl.contains("-dub", ignoreCase = true)

            val episodes = mutableListOf<EpisodeItem>()
            val eplister = doc.select(".eplister li")
            if (eplister.isNotEmpty()) {
                eplister.forEach { li ->
                    val a = li.selectFirst("a") ?: return@forEach
                    val epHref = a.attr("href")
                    if (epHref.isBlank()) return@forEach
                    val epNum = li.selectFirst(".epl-num")?.text()?.toIntOrNull()
                        ?: Regex("""\d+""").find(li.selectFirst(".epl-num")?.text() ?: "")
                            ?.value?.toIntOrNull()
                        ?: 1
                    val epTitle = li.selectFirst(".epl-title")?.text() ?: "Episode $epNum"
                    episodes.add(
                        EpisodeItem(
                            id = Http.absUrl(base, epHref),
                            number = epNum,
                            title = epTitle,
                            season = 1,
                        ),
                    )
                }
            } else {
                // Fallback for single episode movie / OVA
                episodes.add(
                    EpisodeItem(id = detailUrl, number = 1, title = title, season = 1),
                )
            }
            // Episodes on 9anime are listed newest first; oldest first is expected.
            episodes.reverse()

            AnimeDetails(
                id = detailUrl,
                title = title.trim(),
                posterUrl = posterUrl?.takeIf { it.isNotBlank() },
                bannerUrl = null,
                description = plot?.takeIf { it.isNotBlank() },
                genres = tags,
                status = status,
                year = null,
                rating = null,
                seasons = listOf(SeasonGroup(1, "Season 1", episodes)),
                providerId = id,
                lang = lang,
                audioKinds = if (isDub) setOf(AudioKind.DUB) else setOf(AudioKind.SUB),
            )
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun loadStreams(episodeId: String): EpisodeStreams {
        return try {
            val doc = Http.getDoc(episodeId)
            // gogo mirrors wrap the real embeds one level deeper, one iframe
            // per server variant (base64-encoded iframe HTML in the option).
            val embedUrls = doc.select("select.mirror option").mapNotNull { opt ->
                val b64Value = opt.attr("value")
                if (b64Value.isBlank() || b64Value == "...") return@mapNotNull null
                val decodedIframe = try {
                    String(Base64.decode(b64Value.trim(), Base64.DEFAULT), Charsets.UTF_8)
                } catch (_: Exception) {
                    null
                } ?: return@mapNotNull null
                Jsoup.parse(decodedIframe).selectFirst("iframe")?.attr("src")
            }.flatMap { iframeUrl ->
                if (iframeUrl.contains("gogoanime.me.uk/newplayer.php")) {
                    val playerHtml = try {
                        Http.get(iframeUrl, mapOf("Referer" to episodeId))
                    } catch (_: Exception) {
                        ""
                    }
                    val innerSrcs = Jsoup.parse(playerHtml).select("iframe")
                        .map { it.attr("src") }.filter { it.isNotBlank() }
                    if (innerSrcs.isNotEmpty()) innerSrcs else listOf(iframeUrl)
                } else {
                    listOf(iframeUrl)
                }
            }.distinct()

            val results = coroutineScope {
                embedUrls.map { embedUrl ->
                    async {
                        try {
                            resolveAndExtract(embedUrl, episodeId)
                        } catch (_: Exception) {
                            EpisodeStreams(emptyList(), emptyList())
                        }
                    }
                }.awaitAll()
            }
            EpisodeStreams(
                sources = results.flatMap { it.sources },
                subtitles = results.flatMap { it.subtitles },
            )
        } catch (_: Exception) {
            EpisodeStreams(emptyList(), emptyList())
        }
    }

    private suspend fun resolveAndExtract(
        iframeUrl: String,
        refererUrl: String,
    ): EpisodeStreams {
        var embedUrl = iframeUrl
        if (iframeUrl.contains("gogoanime.me.uk/newplayer.php")) {
            val playerPage = try {
                Http.get(iframeUrl, mapOf("Referer" to refererUrl))
            } catch (_: Exception) {
                return EpisodeStreams(emptyList(), emptyList())
            }
            embedUrl = Jsoup.parse(playerPage).selectFirst("iframe")?.attr("src")
                ?: return EpisodeStreams(emptyList(), emptyList())
        }
        return try {
            when {
                embedUrl.contains("plyr.php#") -> {
                    // KiwiK: the real file URL is base64 after the '#'.
                    val b64 = embedUrl.substringAfter("#").substringBefore("#")
                    val decodedUrl = try {
                        String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
                    } catch (_: Exception) {
                        ""
                    }
                    if (decodedUrl.isBlank()) {
                        EpisodeStreams(emptyList(), emptyList())
                    } else {
                        Extractors.direct(
                            url = decodedUrl,
                            serverName = "KiwiK",
                            headers = mapOf("Referer" to "https://gogoanime.me.uk/"),
                        )
                    }
                }
                embedUrl.contains("megaplay.buzz") -> {
                    NineMegaPlayExtractor.extract(embedUrl, refererUrl)
                }
                embedUrl.contains("vidmoly.biz") -> {
                    NineVidmolyExtractor.extract(embedUrl, refererUrl)
                }
                // Moon (bysesayeveum.com) needs a WebView interceptor — skipped
                // here; fall through to the generic extractors instead.
                else -> Extractors.resolve(embedUrl, refererUrl)
            }
        } catch (_: Exception) {
            EpisodeStreams(emptyList(), emptyList())
        }
    }
}

/**
 * Parses an HLS master playlist into one source per quality variant
 * (CloudStream's M3u8Helper.generateM3u8 equivalent). Falls back to the
 * master URL itself when no variants are found.
 */
private fun m3u8VariantSources(
    masterUrl: String,
    masterText: String,
    serverName: String,
    headers: Map<String, String>,
): List<StreamSource> {
    val base = masterUrl.substringBefore('?').let { it.substringBeforeLast('/') + "/" }
    val variants = mutableListOf<Pair<String, Int>>()
    val lines = masterText.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i].trim()
        if (line.startsWith("#EXT-X-STREAM-INF:")) {
            val quality = Regex("""RESOLUTION=\d+x(\d+)""").find(line)
                ?.groupValues?.get(1)?.toIntOrNull() ?: 0
            var j = i + 1
            while (j < lines.size && (lines[j].isBlank() || lines[j].startsWith("#"))) j++
            if (j < lines.size) {
                val uri = lines[j].trim()
                if (uri.isNotEmpty()) {
                    val absolute = if (uri.startsWith("http")) uri else base + uri
                    variants.add(absolute to quality)
                }
                i = j
            }
        }
        i++
    }
    if (variants.isEmpty()) {
        return listOf(
            StreamSource(masterUrl, 0, true, serverName, headers),
        )
    }
    return variants.map { (url, quality) ->
        StreamSource(url, quality, true, serverName, headers)
    }
}

/** MegaPlay clones encrypt the `enc` field of their sources response; the key
 * material sits in lib/newclient.min.js, with pinned fallbacks. */
private object NineMegaPlayCipher {
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

/** Port of NineAnimeMegaPlay / MegaPlayBaseExtractor (mainUrl megaplay.buzz). */
private object NineMegaPlayExtractor {
    private const val NAME = "MegaPlay"
    private const val MAIN_URL = "https://megaplay.buzz"

    // Legacy imgnex cdn paths carry an /anime prefix the megap hosts dropped.
    private fun migrateLegacyUrl(url: String, cdnOrigin: String?): String {
        if (!url.contains("https://cdn.imgnex.top/anime")) return url
        return url.replace(
            "https://cdn.imgnex.top/anime",
            cdnOrigin ?: "https://megap.norami.top",
        )
    }

    private fun cdnOriginFor(streamUrl: String): String? {
        if (streamUrl.contains("cdn.imgnex.top")) return null
        return Regex("""https?://[^/]+""").find(streamUrl)?.value
    }

    private suspend fun fetchSourcesRoot(
        endpoint: String,
        headers: Map<String, String>,
    ): JSONObject? {
        val text = try {
            Http.get(endpoint, headers)
        } catch (_: Exception) {
            return null
        }
        return try {
            JSONObject(text)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun extract(url: String, referer: String): EpisodeStreams {
        return try {
            val pageHeaders = mapOf(
                "Referer" to referer.ifBlank { "https://9anime.org.lv/" },
            )
            val doc = try {
                Http.getDoc(url, pageHeaders)
            } catch (_: Exception) {
                return EpisodeStreams(emptyList(), emptyList())
            }
            val playerEl = doc.selectFirst("#megaplay-player")
            val streamId = playerEl?.attr("data-id")
                ?: playerEl?.attr("data-realid")
                ?: Regex("""/stream/s-\d+/(\d+)/""").find(url)?.groupValues?.get(1)
                ?: return EpisodeStreams(emptyList(), emptyList())

            val type = if (url.contains("/dub", ignoreCase = true)) "dub" else "sub"
            val ajaxHeaders = mapOf(
                "Accept" to "*/*",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to url,
            )
            // Legacy getSources still returns encrypted payloads pinned to the
            // dead imgnex host.
            val root = fetchSourcesRoot(
                "$MAIN_URL/stream/getSourcesNew?id=$streamId&type=$type",
                ajaxHeaders,
            ) ?: fetchSourcesRoot(
                "$MAIN_URL/stream/getSources?id=$streamId&type=$type",
                ajaxHeaders,
            ) ?: return EpisodeStreams(emptyList(), emptyList())

            val resolved = extractStreamUrl(root) ?: return EpisodeStreams(emptyList(), emptyList())
            val cdnOrigin = cdnOriginFor(resolved)
            val m3u8 = migrateLegacyUrl(resolved, cdnOrigin)
            val playbackHeaders = mapOf(
                "Accept" to "*/*",
                "Referer" to "$MAIN_URL/",
            )
            val masterText = try {
                Http.get(m3u8, playbackHeaders)
            } catch (_: Exception) {
                null
            }
            val sources = if (masterText != null && masterText.contains("#EXTM3U")) {
                m3u8VariantSources(m3u8, masterText, NAME, playbackHeaders)
            } else {
                listOf(StreamSource(m3u8, 0, m3u8.contains(".m3u8"), NAME, playbackHeaders))
            }
            val subtitles = mutableListOf<SubtitleTrack>()
            try {
                val tracks = root.optJSONArray("tracks") ?: return EpisodeStreams(sources, subtitles)
                for (i in 0 until tracks.length()) {
                    val track = tracks.optJSONObject(i) ?: continue
                    val kind = track.optString("kind")
                    if (kind != "captions" && kind != "subtitles") continue
                    val file = track.optString("file")
                    if (file.isBlank()) continue
                    val label = track.optString("label").ifBlank { "Unknown" }
                    subtitles.add(
                        SubtitleTrack(migrateLegacyUrl(file, cdnOrigin), "en", label),
                    )
                }
            } catch (_: Exception) {
            }
            EpisodeStreams(sources, subtitles)
        } catch (_: Exception) {
            EpisodeStreams(emptyList(), emptyList())
        }
    }

    private suspend fun extractStreamUrl(root: JSONObject): String? {
        val sources = root.opt("sources")
        val legacy = when (sources) {
            is JSONObject -> sources.optString("file").takeIf { it.isNotBlank() }
            is org.json.JSONArray -> sources.optJSONObject(0)?.optString("file")
                ?.takeIf { it.isNotBlank() }
            else -> null
        }
        if (!legacy.isNullOrBlank()) return legacy
        val enc = root.optString("enc").takeIf { it.isNotBlank() } ?: return null
        return NineMegaPlayCipher.resolveEncStreamUrl(enc, MAIN_URL)
    }
}

/** Port of NineAnimeVidmoly. */
private object NineVidmolyExtractor {
    private const val NAME = "Vidmoly"
    private const val MAIN_URL = "https://vidmoly.biz"

    suspend fun extract(url: String, referer: String): EpisodeStreams {
        return try {
            val headers = mapOf("Referer" to referer.ifBlank { "https://9anime.org.lv/" })
            val html = try {
                Http.get(url, headers)
            } catch (_: Exception) {
                return EpisodeStreams(emptyList(), emptyList())
            }
            val m3u8 = Regex("""file\s*:\s*['"](https?://[^'"]+?\.m3u8[^'"]*)['"]""")
                .find(html)?.groupValues?.get(1)
                ?: Regex("""https?://[^'"\s]+?\.m3u8[^'"\s]*""").find(html)?.value
                ?: return EpisodeStreams(emptyList(), emptyList())
            val playbackHeaders = mapOf(
                "Referer" to "$MAIN_URL/",
                "Origin" to MAIN_URL,
            )
            val masterText = try {
                Http.get(m3u8, playbackHeaders)
            } catch (_: Exception) {
                null
            }
            val sources = if (masterText != null && masterText.contains("#EXTM3U")) {
                m3u8VariantSources(m3u8, masterText, NAME, playbackHeaders)
            } else {
                listOf(StreamSource(m3u8, 0, true, NAME, playbackHeaders))
            }
            EpisodeStreams(sources, emptyList())
        } catch (_: Exception) {
            EpisodeStreams(emptyList(), emptyList())
        }
    }
}
