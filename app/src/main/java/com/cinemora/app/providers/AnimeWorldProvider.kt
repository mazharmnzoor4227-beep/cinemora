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
import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

/**
 * AnimeWorldIndia — Hindi-dub anime source.
 *
 * Ported from the AnimeWorldIndia CloudStream plugin by KSHITIJ8473/raghav
 * (GPL-3.0, https://github.com/KSHITIJ8473/raghav), rewritten against this
 * app's own [AnimeProvider] contract. No CloudStream code is used.
 *
 * The site sits behind Cloudflare: a 403 or a challenge page triggers one
 * [CfBypass] WebView pass (clearance cookies land in [Http]'s shared jar)
 * and then a single retry. The plugin's own 470-line AnimeWorldCFBypass was
 * NOT ported — the generic [CfBypass] replaces it.
 */
class AnimeWorldProvider : AnimeProvider {
    override val id: String = "animeworldindia"
    override val name: String = "AnimeWorldIndia"
    override val lang: Lang = Lang.HINDI

    override val baseUrl: String = FALLBACK

    companion object {
        private const val FALLBACK = "https://watchanimeworld.one"
    }

    private var mainUrl: String = FALLBACK

    private suspend fun refreshBase() {
        DomainHelper.getDomain("animeworldindia")
            ?.takeIf { it.contains("animeworld") }
            ?.let { mainUrl = it.trimEnd('/') }
    }

    /** GET with one Cloudflare-bypass retry; null when the page is unusable. */
    private suspend fun getText(url: String, headers: Map<String, String> = emptyMap()): String? {
        return try {
            val (code, body) = Http.getWithCode(url, headers)
            if (code == 403 || CfBypass.isChallengePage(body)) {
                if (CfBypass.resolve(url, CinemoraAppHolder.context)) {
                    val (code2, body2) = Http.getWithCode(url, headers)
                    if (code2 == 403 || CfBypass.isChallengePage(body2)) null else body2
                } else null
            } else body
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun getDoc(url: String, headers: Map<String, String> = emptyMap()): Document? {
        val text = getText(url, headers) ?: return null
        return try {
            Jsoup.parse(text, url)
        } catch (_: Exception) {
            null
        }
    }

    private fun imgSrc(el: org.jsoup.nodes.Element?): String? {
        val img = el ?: return null
        val src = img.attr("data-src").ifBlank { img.attr("src") }.trim()
        if (src.isBlank()) return null
        return if (src.startsWith("//")) "https:$src" else src
    }

    private fun parseCards(doc: Document): List<AnimeCard> {
        return doc.select("li.post, article.post").mapNotNull { el ->
            val link = el.selectFirst("a[href*=/series/]")?.attr("abs:href")
                ?: return@mapNotNull null
            if (link.endsWith("/series/")) return@mapNotNull null
            val title = el.selectFirst("h2, h3")?.text()?.trim()
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            AnimeCard(
                id = link,
                title = title,
                posterUrl = imgSrc(el.selectFirst("img")),
                providerId = id,
                lang = lang,
            )
        }.distinctBy { it.id }
    }

    override suspend fun homeSections(): List<HomeSection> {
        return try {
            refreshBase()
            val doc = getDoc(mainUrl) ?: return emptyList()
            val all = parseCards(doc)
            if (all.isEmpty()) return emptyList()
            val franchises = doc.select("a[href*=/category/franchise/]").mapNotNull { el ->
                val href = el.attr("abs:href")
                val title = el.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                AnimeCard(
                    id = href,
                    title = title,
                    posterUrl = imgSrc(el.selectFirst("img")),
                    providerId = id,
                    lang = lang,
                )
            }.distinctBy { it.id }
            listOf(
                HomeSection("Latest Series", id, all.take(20)),
                HomeSection("Franchises", id, franchises),
                HomeSection("Trending Anime", id, all.shuffled().take(20)),
                HomeSection("Popular Series", id, all.reversed().take(20)),
            ).filter { it.items.isNotEmpty() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun search(query: String): List<AnimeCard> {
        if (query.isBlank()) return emptyList()
        return try {
            refreshBase()
            val results = mutableListOf<AnimeCard>()
            val encoded = URLEncoder.encode(query, "UTF-8")
            for (pageNum in 1..3) {
                val doc = getDoc("$mainUrl/page/$pageNum/?s=$encoded") ?: break
                val items = doc.select("li.post, article.post").mapNotNull { el ->
                    val seriesLink = el.selectFirst("a[href*=/series/]")?.attr("abs:href")
                    val movieLink = el.selectFirst("a[href*=/movies/]")?.attr("abs:href")
                    val link = seriesLink ?: movieLink ?: return@mapNotNull null
                    if (link.endsWith("/series/") || link.endsWith("/movies/")) return@mapNotNull null
                    val title = el.selectFirst("h2, h3")?.text()?.trim()
                        ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    AnimeCard(
                        id = link,
                        title = title,
                        posterUrl = imgSrc(el.selectFirst("img")),
                        providerId = id,
                        lang = lang,
                    )
                }.distinctBy { it.id }
                if (items.isEmpty()) break
                results.addAll(items)
            }
            results.distinctBy { it.id }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun loadDetails(id: String): AnimeDetails? {
        return try {
            refreshBase()
            val doc = getDoc(id) ?: return null
            val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: return null
            val poster = imgSrc(doc.selectFirst(".poster img, .post-thumbnail img"))
            val backdrop = imgSrc(doc.selectFirst(".backdrop img, .bg img"))
            val plot = doc.selectFirst(".description, .synopsis, .plot, p")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
            val genres = doc.select("a[href*=genre]").map { it.text().trim() }
                .filter { it.isNotBlank() }.distinct()

            if (id.contains("/movies/")) {
                return AnimeDetails(
                    id = id,
                    title = title,
                    posterUrl = poster,
                    bannerUrl = backdrop ?: poster,
                    description = plot,
                    genres = genres,
                    status = null,
                    year = null,
                    rating = null,
                    seasons = listOf(
                        SeasonGroup(
                            season = 1,
                            name = "Movie",
                            episodes = listOf(
                                EpisodeItem(id = id, number = 1, title = title, season = 1),
                            ),
                        ),
                    ),
                    providerId = this.id,
                    lang = lang,
                    audioKinds = setOf(AudioKind.DUB),
                )
            }

            val postId = doc.select("[data-post]").firstOrNull()?.attr("data-post") ?: ""
            val seasonTabs = doc.select("[data-season]").mapNotNull { el ->
                val seasonNum = el.attr("data-season").toIntOrNull() ?: return@mapNotNull null
                val seasonName = el.text().trim().ifBlank { "Season $seasonNum" }
                seasonNum to seasonName
            }.distinctBy { it.first }.sortedBy { it.first }

            val initialEpLinks = doc.select("a[href*=/episode/]")
                .map { it.attr("abs:href") }.distinct()
            val initialSeasonNum = initialEpLinks.firstOrNull()?.let {
                Regex("""(\d+)x(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull()
            } ?: (seasonTabs.firstOrNull()?.first ?: 1)

            val episodesBySeason = linkedMapOf<Int, MutableList<EpisodeItem>>()
            val allSeasons = seasonTabs.map { it.first }.ifEmpty { listOf(initialSeasonNum) }
            val fullSeasons = (1..(allSeasons.maxOrNull() ?: initialSeasonNum)).toList()
            for (season in fullSeasons) {
                val epLinks = if (season == initialSeasonNum && initialEpLinks.isNotEmpty()) {
                    initialEpLinks
                } else {
                    fetchSeasonEpisodes(postId, season)
                }
                for (epUrl in epLinks) {
                    val match = Regex("""(\d+)x(\d+)""").find(epUrl)
                    val list = episodesBySeason.getOrPut(season) { mutableListOf() }
                    val epNum = match?.groupValues?.get(2)?.toIntOrNull() ?: (list.size + 1)
                    val seasonNum = match?.groupValues?.get(1)?.toIntOrNull() ?: season
                    list.add(
                        EpisodeItem(
                            id = epUrl,
                            number = epNum,
                            title = cleanEpisodeName(epUrl),
                            season = seasonNum,
                        ),
                    )
                }
            }
            if (episodesBySeason.isEmpty() && initialEpLinks.isNotEmpty()) {
                for (epUrl in initialEpLinks) {
                    val match = Regex("""(\d+)x(\d+)""").find(epUrl)
                    val epNum = match?.groupValues?.get(2)?.toIntOrNull() ?: 1
                    val seasonNum = match?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    episodesBySeason.getOrPut(seasonNum) { mutableListOf() }.add(
                        EpisodeItem(
                            id = epUrl,
                            number = epNum,
                            title = cleanEpisodeName(epUrl),
                            season = seasonNum,
                        ),
                    )
                }
            }

            val seasonNames = seasonTabs.toMap()
            val seasons = episodesBySeason.entries.sortedBy { it.key }.map { (num, eps) ->
                SeasonGroup(
                    season = num,
                    name = seasonNames[num] ?: "Season $num",
                    episodes = eps.sortedBy { it.number },
                )
            }
            AnimeDetails(
                id = id,
                title = title,
                posterUrl = poster,
                bannerUrl = backdrop ?: poster,
                description = plot,
                genres = genres,
                status = null,
                year = null,
                rating = null,
                seasons = seasons,
                providerId = this.id,
                lang = lang,
                audioKinds = setOf(AudioKind.DUB),
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanEpisodeName(epUrl: String): String {
        val raw = epUrl.substringAfter("/episode/").trimEnd('/')
        val match = Regex("""(\d+)x(\d+)""").find(raw)
        val epNum = match?.groupValues?.get(2)?.toIntOrNull()
        val withoutNum = raw.replace(Regex("""\d+x\d+"""), "").trim('-')
        val parts = withoutNum.split("-").filter { it.isNotBlank() && it != "ep" && it != "episode" }
        val name = parts.joinToString(" ")
            .replace(Regex("\\s+/\\s*"), " ")
            .replace("/", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        return if (name.isNotBlank()) name else "Episode ${epNum ?: ""}".trim()
    }

    private suspend fun fetchSeasonEpisodes(postId: String, season: Int): List<String> {
        if (postId.isBlank()) return emptyList()
        return try {
            val ajaxUrl =
                "$mainUrl/wp-admin/admin-ajax.php?action=action_select_season&season=$season&post=$postId"
            val doc = getDoc(
                ajaxUrl,
                headers = mapOf(
                    "Referer" to mainUrl,
                    "X-Requested-With" to "XMLHttpRequest",
                ),
            ) ?: return emptyList()
            doc.select("a[href*=/episode/]").map { it.attr("abs:href") }.distinct()
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun loadStreams(episodeId: String): EpisodeStreams {
        return try {
            refreshBase()
            val doc = getDoc(episodeId) ?: return EpisodeStreams(emptyList(), emptyList())
            val iframes = doc.select("iframe").mapNotNull { el ->
                val src = el.attr("data-src").ifBlank { el.attr("src") }
                if (src.isNotBlank()) src else null
            }.distinct()

            val sources = mutableListOf<StreamSource>()
            for (iframe in iframes) {
                if (iframe.contains("zephyrix") || iframe.contains("zephyrflick")) {
                    resolveZephyrix(iframe)?.let { sources.add(it) }
                } else {
                    // Any other embed goes through the registered generic extractors.
                    val resolved = Extractors.resolve(iframe, episodeId)
                    sources.addAll(resolved.sources)
                }
            }
            EpisodeStreams(sources = sources.distinctBy { it.url }, subtitles = emptyList())
        } catch (_: Exception) {
            EpisodeStreams(emptyList(), emptyList())
        }
    }

    @Serializable
    private data class ZephyrixVideo(
        val videoSource: String? = null,
        val securedLink: String? = null,
    )

    private suspend fun resolveZephyrix(iframeUrl: String): StreamSource? {
        return try {
            val videoId = Regex("/video/([a-f0-9]+)").find(iframeUrl)
                ?.groupValues?.get(1) ?: return null
            val playerBase = Regex("(https?://[^/]+)").find(iframeUrl)
                ?.groupValues?.get(1) ?: return null
            val body = Http.postForm(
                "$playerBase/player/index.php?data=$videoId&do=getVideo",
                form = mapOf("hash" to videoId, "r" to "$mainUrl/"),
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Origin" to playerBase,
                    "Referer" to "$playerBase/video/$videoId",
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                ),
            )
            val video = Http.json.decodeFromString<ZephyrixVideo>(body)
            // master.txt keeps every audio rendition (hin/tam/tel/eng/jpn) in one playlist
            val streamUrl = video.videoSource ?: video.securedLink ?: return null
            val absoluteUrl = if (streamUrl.startsWith("http")) streamUrl else playerBase + streamUrl
            StreamSource(
                url = absoluteUrl,
                quality = 0,
                isM3u8 = absoluteUrl.contains(".m3u8"),
                serverName = "AnimeWorldIndia",
                headers = mapOf(
                    "Accept" to "*/*",
                    "Referer" to "$playerBase/",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                ),
            )
        } catch (_: Exception) {
            null
        }
    }
}
