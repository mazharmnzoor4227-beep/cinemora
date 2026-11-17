// Generic video-host extractors.
//
// Ported from the CloudStream open-source project
// (https://github.com/recloudstream/cloudstream, GPL-3.0):
//   - library/src/commonMain/kotlin/com/lagradost/cloudstream3/extractors/StreamWishExtractor.kt
//   - library/src/commonMain/kotlin/com/lagradost/cloudstream3/extractors/Filemoon.kt
//   - library/src/commonMain/kotlin/com/lagradost/cloudstream3/extractors/VidHidePro.kt
//     (+ VidhideExtractor.kt, which is a thin subclass of it)
//   - library/src/commonMain/kotlin/com/lagradost/cloudstream3/extractors/Voe.kt
//   - library/src/commonMain/kotlin/com/lagradost/cloudstream3/extractors/MixDrop.kt
//   - library/src/commonMain/kotlin/com/lagradost/cloudstream3/extractors/StreamTape.kt
//   - library/src/commonMain/kotlin/com/lagradost/cloudstream3/extractors/helper/JWPlayerHelper.kt
//   - library/src/commonMain/kotlin/com/lagradost/cloudstream3/utils/JsUnpacker.kt
//
// GPL-3.0, based on recloudstream/cloudstream extractors.
//
// What was deliberately NOT ported:
//   - CloudStream's WebViewResolver fallbacks (StreamWish / Filemoon use a
//     WebView to intercept .m3u8 requests when the page parse fails). There is
//     no WebView in this layer, so those fallbacks are skipped; the page-parse
//     path is the only path here.
//   - CloudStream's M3u8Helper.generateM3u8 expansion: when a host gives one
//     master .m3u8 we return that master URL once (quality 0) and let the
//     player adapt, per this app's contract.
//   - JsUnpacker's ad-related companion helpers (Class.forName tricks) —
//     unrelated to unpacking and not needed here.
//   - StreamTape's evalJs: CloudStream evaluates the botlink JS with a JS
//     engine. This app has no JS engine, so the botlink line is evaluated with
//     a small string-concatenation evaluator instead (see StreamTapeExtractor).
package com.cinemora.app.providers

import com.cinemora.app.core.EpisodeStreams
import com.cinemora.app.core.Extractors
import com.cinemora.app.core.Http
import com.cinemora.app.core.StreamSource
import com.cinemora.app.core.SubtitleTrack
import com.cinemora.app.core.VideoExtractor
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import org.jsoup.Jsoup
import java.util.Base64
import kotlin.math.pow

// ---------------------------------------------------------------------------
// Dean Edwards P.A.C.K.E.R. unpacker (ported from CloudStream's JsUnpacker)
// ---------------------------------------------------------------------------

/** Unpacks `eval(function(p,a,c,k,e,d){...})` P.A.C.K.E.R. coded JavaScript. */
private object JsUnpacker {

    fun detect(packedJS: String?): Boolean {
        if (packedJS == null) return false
        val js = packedJS.replace(" ", "")
        return Regex("eval\\(function\\(p,a,c,k,e,[rd]").containsMatchIn(js)
    }

    fun unpack(packedJS: String?): String? {
        val js = packedJS ?: return null
        try {
            val match = Regex(
                """(?s)\}\s*\('(.*)',\s*(.*?),\s*(\d+),\s*'(.*?)'\.split\('\|'\)"""
            ).find(js)
            if (match != null && match.groupValues.size == 5) {
                val payload = match.groupValues[1].replace("\\'", "'")
                val radixStr = match.groupValues[2]
                val countStr = match.groupValues[3]
                val symtab = match.groupValues[4].split("|").toTypedArray()
                var radix = 36
                var count = 0
                try {
                    radix = radixStr.toIntOrNull() ?: radix
                } catch (_: Exception) {
                }
                try {
                    count = countStr.toIntOrNull() ?: 0
                } catch (_: Exception) {
                }
                if (symtab.size != count) {
                    throw Exception("Unknown p.a.c.k.e.r. encoding")
                }
                val unbase = Unbase(radix)
                val wordRegex = Regex("""\b[a-zA-Z0-9_]+\b""")
                val decoded = StringBuilder(payload)
                var replaceOffset = 0
                wordRegex.findAll(payload).forEach { wordMatch ->
                    val word = wordMatch.value
                    val x = unbase.unbase(word)
                    val value = if (x in symtab.indices) symtab[x] else null
                    if (!value.isNullOrEmpty()) {
                        decoded.setRange(
                            wordMatch.range.first + replaceOffset,
                            wordMatch.range.last + 1 + replaceOffset,
                            value
                        )
                        replaceOffset += value.length - word.length
                    }
                }
                return decoded.toString()
            }
        } catch (_: Exception) {
            // Unpacking failed; caller falls back to the raw text.
        }
        return null
    }

    private class Unbase(private val radix: Int) {
        private val alphabet62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        private val alphabet95 =
            " !\"#$%&\\'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\\\]^_`abcdefghijklmnopqrstuvwxyz{|}~"
        private var alphabet: String? = null
        private var dictionary: HashMap<String, Int>? = null

        fun unbase(str: String): Int {
            var ret = 0
            if (alphabet == null) {
                ret = str.toInt(radix)
            } else {
                val tmp = StringBuilder(str).reverse().toString()
                for (i in tmp.indices) {
                    ret += (radix.toDouble().pow(i.toDouble()) * dictionary!![tmp.substring(i, i + 1)]!!).toInt()
                }
            }
            return ret
        }

        init {
            if (radix > 36) {
                when {
                    radix < 62 -> alphabet = alphabet62.substring(0, radix)
                    radix in 63..94 -> alphabet = alphabet95.substring(0, radix)
                    radix == 62 -> alphabet = alphabet62
                    radix == 95 -> alphabet = alphabet95
                }
                dictionary = HashMap(95)
                for (i in 0 until alphabet!!.length) {
                    dictionary!![alphabet!!.substring(i, i + 1)] = i
                }
            }
        }
    }
}

/** Extract the packed `eval(function(p,a,c,k,e,d){...})` block from a page, if any. */
private val packedBlockRegex =
    Regex("""(?s)eval\(function\(p,a,c,k,e,[rd].*?\.split\('\|'\)[^)]*\)\)""")

private fun getPacked(text: String): String? = packedBlockRegex.find(text)?.value

/** Unpack the page when it contains packed JS, otherwise return the text as-is. */
private fun getAndUnpack(text: String): String {
    val packed = getPacked(text) ?: return text
    return JsUnpacker.unpack(packed) ?: text
}

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

private fun hostOf(url: String): String {
    return try {
        java.net.URL(url).host.lowercase()
    } catch (_: Exception) {
        ""
    }
}

private fun originOf(url: String): String {
    return try {
        val u = java.net.URL(url)
        "${u.protocol}://${u.host}"
    } catch (_: Exception) {
        url
    }
}

private fun hostMatches(url: String, vararg domains: String): Boolean {
    val host = hostOf(url)
    return domains.any { host == it || host.endsWith(".$it") }
}

private fun hostContains(url: String, vararg parts: String): Boolean {
    val host = hostOf(url)
    return parts.any { host.contains(it) }
}

private fun fixUrl(url: String, mainUrl: String): String {
    return when {
        url.startsWith("/") -> mainUrl.trimEnd('/') + url
        url.startsWith("http") -> url
        else -> "${mainUrl.trimEnd('/')}/$url"
    }
}

/** "1080p" -> 1080, "720" -> 720, anything else -> 0 (unknown). */
private fun parseQuality(label: String?): Int {
    if (label.isNullOrBlank()) return 0
    return Regex("""(\d{3,4})p?""").find(label)?.groupValues?.getOrNull(1)
        ?.toIntOrNull() ?: 0
}

private fun playerHeaders(referer: String, extra: Map<String, String> = emptyMap()): Map<String, String> =
    mapOf(
        "Referer" to referer,
        "User-Agent" to Http.USER_AGENT,
    ) + extra

/**
 * Port of CloudStream's JwPlayerHelper.extractStreamLinks, without the
 * M3u8Helper expansion: a master .m3u8 is returned once (quality 0) and the
 * player adapts. Parses `sources: [{file, label, type}]` and
 * `tracks: [{file, kind, label}]` out of a JWPlayer setup script, with a
 * fallback that scans the script for bare .m3u8 / master.txt URLs.
 */
private fun parseJwPlayer(
    script: String,
    serverName: String,
    referer: String,
    mainUrl: String,
    extraHeaders: Map<String, String> = emptyMap(),
): EpisodeStreams {
    val headers = playerHeaders(referer, extraHeaders)
    val sources = mutableListOf<StreamSource>()
    val seenUrls = mutableSetOf<String>()

    fun addSource(rawUrl: String, quality: Int) {
        val fixed = fixUrl(rawUrl.replace("\\/", "/"), mainUrl)
        if (!seenUrls.add(fixed)) return
        sources += StreamSource(
            url = fixed,
            quality = quality,
            isM3u8 = fixed.contains(".m3u8") || fixed.contains("master.txt"),
            serverName = serverName,
            headers = headers,
        )
    }

    val sourcesArray = Regex(""""?sources"?:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
        .find(script)?.groupValues?.getOrNull(1)
    if (sourcesArray != null) {
        for (obj in Regex("""\{([^{}]*)\}""").findAll(sourcesArray)) {
            val body = obj.groupValues[1]
            val file = Regex("""["']?file["']?\s*[:=]\s*["']([^"']+)["']""")
                .find(body)?.groupValues?.getOrNull(1) ?: continue
            val label = Regex("""["']?label["']?\s*[:=]\s*["']([^"']*)["']""")
                .find(body)?.groupValues?.getOrNull(1)
            addSource(file, parseQuality(label))
        }
    }

    if (sources.isEmpty()) {
        // Fallback: bare HLS links anywhere in the script, e.g.
        // `"hls2": "https://.../master.m3u8?..."`
        for (m in Regex("""[:=]\s*["']([^"'\s]+(\.m3u8|master\.txt)[^"'\s]*)["']""").findAll(script)) {
            addSource(m.groupValues[1], 0)
        }
    }

    val subtitles = mutableListOf<SubtitleTrack>()
    val tracksArray = Regex(""""?tracks"?:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
        .find(script)?.groupValues?.getOrNull(1)
    if (tracksArray != null) {
        for (obj in Regex("""\{([^{}]*)\}""").findAll(tracksArray)) {
            val body = obj.groupValues[1]
            val file = Regex("""["']?file["']?\s*[:=]\s*["']([^"']+)["']""")
                .find(body)?.groupValues?.getOrNull(1) ?: continue
            val label = Regex("""["']?label["']?\s*[:=]\s*["']([^"']*)["']""")
                .find(body)?.groupValues?.getOrNull(1) ?: continue
            val kind = Regex("""["']?kind["']?\s*[:=]\s*["']([^"']*)["']""")
                .find(body)?.groupValues?.getOrNull(1)
            if (kind == null || kind.contains("caption", ignoreCase = true) ||
                kind.contains("subtitle", ignoreCase = true)
            ) {
                subtitles += SubtitleTrack(
                    url = fixUrl(file.replace("\\/", "/"), mainUrl),
                    language = label,
                    label = label,
                )
            }
        }
    }

    return EpisodeStreams(sources, subtitles)
}

private fun emptyStreams() = EpisodeStreams(emptyList(), emptyList())

// ---------------------------------------------------------------------------
// StreamWish (+ VidWish and the other StreamWish clones)
// ---------------------------------------------------------------------------

/**
 * Port of CloudStream's StreamWishExtractor. The embed page hides its JWPlayer
 * setup inside packed JS (or, failing that, a plain `jwplayer("vplayer")`
 * setup script). The WebView .m3u8-interception fallback from the original
 * is not portable here and is skipped.
 */
class StreamWishExtractor : VideoExtractor {
    private val mainUrl = "https://streamwish.to"

    override fun canHandle(url: String): Boolean {
        return hostMatches(
            url,
            "streamwish.to", "streamwish.site", "streamwish.com",
            "mwish.pro", "dwish.pro", "embedwish.com", "hgcloud.to",
            "wishembed.pro", "kswplayer.info", "wishfast.top", "sfastwish.com",
            "strwish.xyz", "strwish.com", "flaswish.com", "awish.pro",
            "obeywish.com", "jodwish.com", "swhoi.com", "multimovies.cloud",
            "uqloads.xyz", "doodporn.xyz", "cdnwish.com", "asnwish.com",
            "nekowish.my.id", "neko-stream.click", "swdyu.com", "wishonly.site",
            "playerwish.com", "streamhls.to", "hlswish.com",
            "vidwish.live", "vidwish.com",
        ) || hostContains(url, "streamwish", "vidwish")
    }

    override suspend fun extract(url: String, referer: String): EpisodeStreams {
        return try {
            val embedUrl = resolveEmbedUrl(url)
            val origin = originOf(embedUrl)
            val headers = mapOf(
                "Accept" to "*/*",
                "Connection" to "keep-alive",
                "Sec-Fetch-Dest" to "empty",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Site" to "cross-site",
                "Referer" to "$origin/",
                "Origin" to origin,
                "User-Agent" to Http.USER_AGENT,
            )
            val page = Http.get(embedUrl, headers)
            val script = when {
                getPacked(page) != null -> getAndUnpack(page)
                else -> {
                    val doc = Jsoup.parse(page, embedUrl)
                    doc.select("script")
                        .firstOrNull { it.html().contains("jwplayer(\"vplayer\").setup(") }
                        ?.html()
                        ?: doc.select("script")
                            .firstOrNull { it.data().contains("sources:") }
                            ?.data()
                        .orEmpty()
                }
            }
            parseJwPlayer(script, "StreamWish", embedUrl, origin, headers)
        } catch (_: Exception) {
            emptyStreams()
        }
    }

    /** /e/<id> and /f/<id> embeds resolve to /<id> on the same host. */
    private fun resolveEmbedUrl(inputUrl: String): String {
        val id = when {
            inputUrl.contains("/f/") -> inputUrl.substringAfter("/f/")
            inputUrl.contains("/e/") -> inputUrl.substringAfter("/e/")
            else -> return inputUrl
        }.substringBefore("?").substringBefore("#")
        val base = inputUrl.substringBefore("/f/").substringBefore("/e/")
        return "$base/$id"
    }
}

// ---------------------------------------------------------------------------
// Filemoon
// ---------------------------------------------------------------------------

/**
 * Port of CloudStream's FilemoonV2. The landing page usually only holds an
 * <iframe> to the real player page; the packed JWPlayer script lives there.
 * When there is no iframe, the packed script on the landing page itself is
 * tried. The WebView interception fallback from the original is skipped.
 */
class FilemoonExtractor : VideoExtractor {
    private val mainUrl = "https://filemoon.to"

    override fun canHandle(url: String): Boolean {
        return hostMatches(
            url,
            "filemoon.to", "filemoon.sx", "filemoon.in", "filemoon.com",
            "filemoon.link",
        ) || hostContains(url, "filemoon")
    }

    override suspend fun extract(url: String, referer: String): EpisodeStreams {
        return try {
            val defaultHeaders = mapOf(
                "Referer" to url,
                "Sec-Fetch-Dest" to "iframe",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "cross-site",
                "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64; rv:137.0) Gecko/20100101 Firefox/137.0",
            )
            val page = Http.get(url, defaultHeaders)
            val doc = Jsoup.parse(page, url)
            val iframeSrc = doc.selectFirst("iframe")?.attr("src")

            if (iframeSrc.isNullOrEmpty()) {
                val scriptData = doc.select("script")
                    .firstOrNull { it.data().contains("function(p,a,c,k,e,d)") }
                    ?.data().orEmpty()
                return parseJwPlayer(
                    getAndUnpack(scriptData), "FileMoon", url, mainUrl, defaultHeaders
                )
            }

            val iframeUrl = Http.absUrl(url, iframeSrc)
            val iframeHeaders = defaultHeaders + ("Accept-Language" to "en-US,en;q=0.5")
            val iframePage = Http.get(iframeUrl, iframeHeaders)
            val iframeDoc = Jsoup.parse(iframePage, iframeUrl)
            val iframeScriptData = iframeDoc.select("script")
                .firstOrNull { it.data().contains("function(p,a,c,k,e,d)") }
                ?.data().orEmpty()
            parseJwPlayer(
                getAndUnpack(iframeScriptData), "FileMoon", iframeUrl, mainUrl, defaultHeaders
            )
        } catch (_: Exception) {
            emptyStreams()
        }
    }
}

// ---------------------------------------------------------------------------
// VidHide (VidHidePro family)
// ---------------------------------------------------------------------------

/**
 * Port of CloudStream's VidHidePro (VidhideExtractor is a thin subclass of
 * it). Download / file links are rewritten to the /v/ embed form, then the
 * packed JS (or a plain `sources:` script) is parsed as JWPlayer setup.
 */
class VidHideExtractor : VideoExtractor {
    private val mainUrl = "https://vidhidepro.com"

    override fun canHandle(url: String): Boolean {
        return hostMatches(
            url,
            "vidhide.com", "vidhidepro.com", "vidhidehub.com",
            "vidhidevip.com", "vidhidepre.com",
            "filelions.live", "filelions.online", "filelions.to",
            "kinoger.be", "smoothpre.com", "dhtpre.com", "peytonepre.com",
            "ryderjet.com",
        ) || hostContains(url, "vidhide", "filelions")
    }

    override suspend fun extract(url: String, referer: String): EpisodeStreams {
        return try {
            val embedUrl = getEmbedUrl(url)
            val origin = originOf(embedUrl)
            val headers = mapOf(
                "Sec-Fetch-Dest" to "empty",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Site" to "cross-site",
                "Origin" to origin,
                "User-Agent" to Http.USER_AGENT,
                "Referer" to referer.ifEmpty { "$origin/" },
            )
            val page = Http.get(embedUrl, headers)
            val script = if (getPacked(page) != null) {
                getAndUnpack(page)
            } else {
                Jsoup.parse(page, embedUrl).select("script")
                    .firstOrNull { it.data().contains("sources:") }
                    ?.data()
                    .orEmpty()
            }
            parseJwPlayer(script, "VidHide", embedUrl, origin, headers)
        } catch (_: Exception) {
            emptyStreams()
        }
    }

    private fun getEmbedUrl(url: String): String {
        return when {
            url.contains("/d/") -> url.replace("/d/", "/v/")
            url.contains("/download/") -> url.replace("/download/", "/v/")
            url.contains("/file/") -> url.replace("/file/", "/v/")
            else -> url.replace("/f/", "/v/")
        }
    }
}

// ---------------------------------------------------------------------------
// Voe
// ---------------------------------------------------------------------------

/**
 * Port of CloudStream's Voe. The page carries a `script[type=application/json]`
 * blob that is obfuscated (ROT13 -> pattern replace -> strip underscores ->
 * base64 -> char shift -3 -> reverse -> base64) and decodes to JSON with a
 * `source` (.m3u8) and/or `direct_access_url` (.mp4).
 */
class VoeExtractor : VideoExtractor {
    private val mainUrl = "https://voe.sx"
    private val redirectRegex = Regex("""window\.location\.href\s*=\s*'([^']+)';""")

    override fun canHandle(url: String): Boolean {
        val host = hostOf(url)
        return hostMatches(
            url,
            "voe.sx", "tubelessceliolymph.com", "simpulumlamerop.com",
            "urochsunloath.com", "nathanfromsubject.com", "yip.su",
            "metagnathtuggers.com", "donaldlineelse.com", "charlestoughrace.com",
        ) || host.startsWith("voe.")
    }

    override suspend fun extract(url: String, referer: String): EpisodeStreams {
        return try {
            var page = Http.get(url, mapOf("Referer" to referer))
            var pageUrl = url
            val redirectUrl = redirectRegex.find(page)?.groupValues?.getOrNull(1)
            if (redirectUrl != null) {
                pageUrl = redirectUrl
                page = Http.get(redirectUrl, mapOf("Referer" to referer))
            }

            val doc = Jsoup.parse(page, pageUrl)
            val raw = doc.selectFirst("script[type=application/json]")?.data()?.trim()
                ?: return emptyStreams()
            if (!raw.contains("[\"")) return emptyStreams()
            val encoded = raw.substringAfter("[\"").substringBeforeLast("\"]")
            if (encoded.isBlank()) return emptyStreams()

            val decrypted = decryptF7(encoded) ?: return emptyStreams()
            val headers = playerHeaders(pageUrl, mapOf("Origin" to "$mainUrl/"))
            val sources = mutableListOf<StreamSource>()
            decrypted.source?.let { m3u8 ->
                sources += StreamSource(
                    url = m3u8,
                    quality = 0,
                    isM3u8 = true,
                    serverName = "Voe",
                    headers = headers,
                )
            }
            decrypted.directAccessUrl?.let { mp4 ->
                sources += StreamSource(
                    url = mp4,
                    quality = 0,
                    isM3u8 = false,
                    serverName = "Voe",
                    headers = headers,
                )
            }
            EpisodeStreams(sources, emptyList())
        } catch (_: Exception) {
            emptyStreams()
        }
    }

    private fun decryptF7(p8: String): VoeDecrypted? {
        return try {
            val vF = rot13(p8)
            val vF2 = replacePatterns(vF)
            val vF3 = removeUnderscores(vF2)
            val vF4 = b64decode(vF3)
            val vF5 = charShift(vF4, 3)
            val vF6 = vF5.reversed()
            val vAtob = b64decode(vF6)
            Http.json.decodeFromString<VoeDecrypted>(vAtob)
        } catch (_: Exception) {
            null
        }
    }

    private fun rot13(input: String): String {
        return input.map { c ->
            when (c) {
                in 'A'..'Z' -> ((c - 'A' + 13) % 26 + 'A'.code).toChar()
                in 'a'..'z' -> ((c - 'a' + 13) % 26 + 'a'.code).toChar()
                else -> c
            }
        }.joinToString("")
    }

    private fun replacePatterns(input: String): String {
        val patterns = listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&")
        return patterns.fold(input) { result, pattern ->
            result.replace(Regex(Regex.escape(pattern)), "_")
        }
    }

    private fun removeUnderscores(input: String): String = input.replace("_", "")

    private fun charShift(input: String, shift: Int): String {
        return input.map { (it.code - shift).toChar() }.joinToString("")
    }

    /** Base64 decode that tolerates missing padding and stray whitespace. */
    private fun b64decode(input: String): String {
        val clean = input.filterNot { it.isWhitespace() }
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        return String(Base64.getDecoder().decode(padded), Charsets.UTF_8)
    }

    @Serializable
    private data class VoeDecrypted(
        @SerialName("source") val source: String? = null,
        @SerialName("direct_access_url") val directAccessUrl: String? = null,
    )
}

// ---------------------------------------------------------------------------
// MixDrop
// ---------------------------------------------------------------------------

/**
 * Port of CloudStream's MixDrop. The page's packed JS unpacks to a script
 * containing `wurl = "<direct file url>";` — that URL is the video.
 */
class MixDropExtractor : VideoExtractor {
    private val mainUrl = "https://mixdrop.co"
    private val srcRegex = Regex("""wurl.*?=.*?"(.*?)";""")

    override fun canHandle(url: String): Boolean {
        return hostMatches(
            url,
            "mixdrop.co", "mixdrop.ps", "mixdrop.si", "mixdrop.bz",
            "mixdrop.ag", "mixdrop.ch", "mixdrop.to",
            "mdy48tn97.com", "mxdrop.to",
        ) || hostContains(url, "mixdrop", "mxdrop")
    }

    override suspend fun extract(url: String, referer: String): EpisodeStreams {
        return try {
            val page = Http.get(
                url.replaceFirst("/f/", "/e/"),
                mapOf("Referer" to referer),
            )
            val unpacked = getAndUnpack(page)
            val link = srcRegex.find(unpacked)?.groupValues?.getOrNull(1)
                ?: return emptyStreams()
            val fixed = httpsify(link)
            Extractors.direct(
                fixed,
                "MixDrop",
                0,
                playerHeaders(url),
            )
        } catch (_: Exception) {
            emptyStreams()
        }
    }

    private fun httpsify(url: String): String {
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http") -> url
            else -> "https://$url"
        }
    }
}

// ---------------------------------------------------------------------------
// StreamTape
// ---------------------------------------------------------------------------

/**
 * Port of CloudStream's StreamTape. The page hides the video URL in a line
 * like:
 *   document.getElementById('botlink').innerHTML = '//streamtape.com/get_video?...';
 * CloudStream evaluates that line with a JS engine (evalJs); this port uses a
 * small string-concatenation evaluator instead (quoted literals joined by `+`,
 * with simple `var name = '...'` references resolved from the same script).
 * If the host ever obfuscates the line beyond plain concatenation, this
 * extractor will return empty rather than guess.
 */
class StreamTapeExtractor : VideoExtractor {
    override fun canHandle(url: String): Boolean {
        return hostMatches(
            url,
            "streamtape.com", "streamtape.net", "streamtape.xyz",
            "watchadsontape.com", "shavetape.cash",
        ) || hostContains(url, "streamtape", "shavetape", "watchadsontape")
    }

    override suspend fun extract(url: String, referer: String): EpisodeStreams {
        return try {
            val page = Http.get(url, mapOf("Referer" to referer))
            val doc = Jsoup.parse(page, url)
            val scriptHtml = doc.select("script")
                .firstOrNull { it.html().contains("botlink').innerHTML") }
                ?.html()
                ?: return emptyStreams()
            val line = scriptHtml.lines()
                .firstOrNull { it.contains("botlink').innerHTML") }
                ?: return emptyStreams()
            val expr = line.substringAfter("innerHTML").substringAfter("=", "")
            if (expr.isBlank()) return emptyStreams()
            val evaluated = evalStringConcat(expr, scriptHtml) ?: return emptyStreams()
            val base = evaluated.trim()
            if (base.isEmpty()) return emptyStreams()
            val withScheme = when {
                base.startsWith("//") -> "https:$base"
                base.startsWith("http") -> base
                else -> "https://$base"
            }
            Extractors.direct(
                "$withScheme&stream=1",
                "StreamTape",
                0,
                playerHeaders(url),
            )
        } catch (_: Exception) {
            emptyStreams()
        }
    }

    /**
     * Evaluate a JS expression made only of quoted string literals, `+` and
     * simple variable references (`var name = '...'` resolved from [script]).
     * Returns null for anything more complex.
     */
    private fun evalStringConcat(expr: String, script: String): String? {
        val out = StringBuilder()
        var i = 0
        var sawAnything = false
        while (i < expr.length) {
            val c = expr[i]
            when {
                c.isWhitespace() || c == '+' || c == ';' -> i++
                c == '\'' || c == '"' -> {
                    val end = expr.indexOf(c, i + 1)
                    if (end == -1) return null
                    out.append(expr.substring(i + 1, end))
                    sawAnything = true
                    i = end + 1
                }
                c.isLetter() || c == '_' || c == '$' -> {
                    val nameMatch = Regex("""[A-Za-z_$][A-Za-z0-9_$]*""").find(expr, i)
                        ?: return null
                    val name = nameMatch.value
                    val value = Regex("""(?:var|let|const)\s+${Regex.escape(name)}\s*=\s*['"]([^'"]*)['"]""")
                        .find(script)?.groupValues?.getOrNull(1)
                        ?: return null
                    out.append(value)
                    sawAnything = true
                    i = nameMatch.range.last + 1
                }
                else -> return null
            }
        }
        return if (sawAnything) out.toString() else null
    }
}

// ---------------------------------------------------------------------------
// Registration
// ---------------------------------------------------------------------------

object GenericExtractors {
    fun registerAll() {
        Extractors.register(StreamWishExtractor())
        Extractors.register(FilemoonExtractor())
        Extractors.register(VidHideExtractor())
        Extractors.register(VoeExtractor())
        Extractors.register(MixDropExtractor())
        Extractors.register(StreamTapeExtractor())
    }
}
