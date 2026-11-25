package com.kodi.cloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Document
import java.net.URLEncoder

class AresHD : MainAPI() {
    override var mainUrl = "https://areshd.com"
    override var name = "AresHD"
    override var lang = "es"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/peliculas" to "Películas",
        "$mainUrl/series" to "Series",
        "$mainUrl/genero/accion" to "Acción",
        "$mainUrl/genero/animacion" to "Animación",
        "$mainUrl/genero/aventura" to "Aventura",
        "$mainUrl/genero/ciencia-ficcion" to "Ciencia Ficción",
        "$mainUrl/genero/comedia" to "Comedia",
        "$mainUrl/genero/crimen" to "Crimen",
        "$mainUrl/genero/documental" to "Documental",
        "$mainUrl/genero/drama" to "Drama",
        "$mainUrl/genero/familia" to "Familia",
        "$mainUrl/genero/fantasia" to "Fantasía",
        "$mainUrl/genero/misterio" to "Misterio",
        "$mainUrl/genero/romance" to "Romance",
        "$mainUrl/genero/suspense" to "Suspense",
        "$mainUrl/genero/terror" to "Terror",
        "$mainUrl/genero/western" to "Western",
    )

    companion object {
        private val PLAYER_URL_REGEX = Regex("""var\s+url\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
        private val MEDIA_URL_REGEX = Regex("""\.(?:mp4|m4v|mkv|webm|avi|mov|m3u8)(?:[?#]|$)""", RegexOption.IGNORE_CASE)
        private val YEAR_REGEX = Regex("""\b(19\d{2}|20\d{2})\b""")

        private val SKIP_SERVERS = setOf("youtube", "plustream")
        private val SKIP_TARGETS = Regex(
            "(?:youtube\\.com|youtu\\.be|plustream|cinestart\\.|okru\\.cx|ok\\.ru|vk\\.com|facebook\\.com|instagram\\.com|tiktok\\.com|t\\.me|telegram\\.)",
            RegexOption.IGNORE_CASE
        )
        private val LANGUAGE_LABELS = mapOf(
            "latino" to "Latino",
            "spanish" to "Español",
            "castellano" to "Español",
            "english" to "Inglés",
        )
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.trimEnd('/')}/pagina/$page"
        val doc = app.get(url).document
        val props = extractPageProps(doc) ?: return newHomePageResponse(request, emptyList(), false)
        val items = props.movies.orEmpty().mapNotNull { it.toSearchResponse() }
        val totalPages = props.pages ?: 1
        return newHomePageResponse(request, items, hasNext = page < totalPages)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return newSearchResponseList(emptyList(), false)

        val encoded = URLEncoder.encode(cleanQuery, "UTF-8").replace("+", "%20")
        val url = if (page <= 1) {
            "$mainUrl/search/$encoded"
        } else {
            "$mainUrl/search/$encoded/pagina/$page"
        }

        val doc = app.get(url).document
        val props = extractPageProps(doc) ?: return newSearchResponseList(emptyList(), false)
        val items = props.movies.orEmpty().mapNotNull { it.toSearchResponse() }
        val totalPages = props.pages ?: 1
        return newSearchResponseList(items, hasNext = page < totalPages)
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val props = extractPageProps(doc) ?: return null

        props.thisMovie?.let { movie ->
            val title = movie.titles?.name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val payload = LoadVideoData(videos = movie.videos, referer = url).toJson()
            return newMovieLoadResponse(title, url, TvType.Movie, payload) {
                this.posterUrl = movie.images?.poster
                this.backgroundPosterUrl = movie.images?.backdrop
                this.year = extractYear(movie.releaseDate)
                this.plot = movie.overview
                this.duration = movie.runtime
                this.tags = movie.genres?.mapNotNull { it.name?.trim()?.takeIf(String::isNotEmpty) }
                this.score = Score.from10(movie.rate?.average)
                val actors = movie.cast?.acting?.mapNotNull { it.name?.trim()?.takeIf(String::isNotEmpty) }.orEmpty()
                if (actors.isNotEmpty()) addActors(actors)
            }
        }

        props.thisSerie?.let { serie ->
            val title = serie.titles?.name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val serieSlug = serie.slug?.name?.trim()
            val episodes = serie.seasons.orEmpty().flatMap { season ->
                val seasonNum = season.number ?: season.season ?: 1
                season.episodes.orEmpty().mapNotNull { ep ->
                    val epNum = ep.number ?: ep.slug?.episode?.toIntOrNull() ?: return@mapNotNull null
                    val epSlugName = ep.slug?.name?.trim()?.takeIf { it.isNotEmpty() } ?: serieSlug ?: return@mapNotNull null
                    val epUrl = "$mainUrl/episodio/$epSlugName-temporada-$seasonNum-episodio-$epNum"
                    newEpisode(epUrl) {
                        this.name = ep.title?.trim()
                        this.season = seasonNum
                        this.episode = epNum
                        this.posterUrl = ep.image
                        this.description = ep.overview
                        addDate(ep.releaseDate)
                    }
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = serie.images?.poster
                this.backgroundPosterUrl = serie.images?.backdrop
                this.year = extractYear(serie.releaseDate)
                this.plot = serie.overview
                this.tags = serie.genres?.mapNotNull { it.name?.trim()?.takeIf(String::isNotEmpty) }
                this.score = Score.from10(serie.rate?.average)
                val actors = serie.cast?.acting?.mapNotNull { it.name?.trim()?.takeIf(String::isNotEmpty) }.orEmpty()
                if (actors.isNotEmpty()) addActors(actors)
            }
        }

        props.episode?.let { ep ->
            val title = ep.title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val payload = LoadVideoData(videos = ep.videos, referer = url).toJson()
            return newMovieLoadResponse(title, url, TvType.Movie, payload) {
                this.posterUrl = ep.image
                this.plot = ep.overview ?: props.serie?.overview
                this.year = extractYear(ep.releaseDate)
            }
        }

        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loaded = if (data.trimStart().startsWith("{")) {
            tryParseJson<LoadVideoData>(data)
        } else {
            val doc = app.get(data).document
            val props = extractPageProps(doc)
            val videos = props?.thisMovie?.videos ?: props?.episode?.videos
            LoadVideoData(videos = videos, referer = data)
        }

        val videos = loaded?.videos ?: return false
        val refererBase = loaded.referer ?: "$mainUrl/"

        val seen = HashSet<String>()
        val candidates = mutableListOf<LinkCandidate>()
        for ((langKey, options) in videos) {
            for (option in options) {
                val link = option.result?.trim().orEmpty()
                if (link.isEmpty() || !seen.add(link)) continue

                val srv = option.cyberlocker?.trim()?.lowercase().orEmpty()
                if (srv in SKIP_SERVERS) continue

                candidates.add(
                    LinkCandidate(
                        language = languageLabel(langKey),
                        server = srv,
                        quality = option.quality,
                        url = link,
                    )
                )
            }
        }

        candidates.amap { candidate ->
            val targetUrl = resolvePlayerRedirect(candidate.url, refererBase) ?: return@amap
            if (SKIP_TARGETS.containsMatchIn(targetUrl)) return@amap

            if (MEDIA_URL_REGEX.containsMatchIn(targetUrl)) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = linkLabel(candidate),
                        url = targetUrl,
                        type = if (targetUrl.contains(".m3u8", ignoreCase = true)) {
                            ExtractorLinkType.M3U8
                        } else {
                            INFER_TYPE
                        }
                    ) {
                        this.referer = candidate.url
                        qualityValue(candidate.quality)?.let { this.quality = it }
                    }
                )
            } else {
                loadExtractor(targetUrl, candidate.url, subtitleCallback) { link ->
                    callback(
                        link
                            .withLanguage(candidate.language)
                            .withFallbackQuality(qualityValue(candidate.quality))
                    )
                }
            }
        }

        return true
    }

    private suspend fun resolvePlayerRedirect(rawUrl: String, referer: String): String? {
        if (!rawUrl.contains("player.areshd.com", ignoreCase = true)) {
            return rawUrl
        }
        return runCatching {
            val html = app.get(rawUrl, referer = referer).text
            PLAYER_URL_REGEX.find(html)
                ?.groupValues
                ?.getOrNull(1)
                ?.replace("\\/", "/")
                ?.trim()
                ?.takeIf { it.startsWith("http", ignoreCase = true) }
        }.getOrNull()
    }

    private fun languageLabel(key: String?): String? {
        val raw = key?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return LANGUAGE_LABELS[raw.lowercase()] ?: raw.replaceFirstChar { it.uppercase() }
    }

    private fun linkLabel(candidate: LinkCandidate): String {
        val server = candidate.server.ifBlank { name }.replaceFirstChar { it.uppercase() }
        return candidate.language?.let { "$server ($it)" } ?: server
    }

    private fun qualityValue(raw: String?): Int? = when (raw?.trim()?.uppercase()) {
        "4K", "UHD", "2160P", "2160" -> Qualities.P2160.value
        "QHD", "2K", "1440P", "1440" -> Qualities.P1440.value
        "FULL_HD", "FHD", "1080P", "1080" -> Qualities.P1080.value
        "HD", "720P", "720" -> Qualities.P720.value
        "SD", "480P", "480" -> Qualities.P480.value
        "360P", "360" -> Qualities.P360.value
        else -> null
    }

    private fun ExtractorLink.withLanguage(language: String?): ExtractorLink {
        if (language.isNullOrBlank() || name.contains(language, ignoreCase = true)) return this
        @Suppress("DEPRECATION")
        return ExtractorLink(
            source = source,
            name = "$name ($language)",
            url = url,
            referer = referer,
            quality = quality,
            headers = headers,
            extractorData = extractorData,
            type = type,
            audioTracks = audioTracks,
        )
    }

    private fun ExtractorLink.withFallbackQuality(quality: Int?): ExtractorLink {
        if (quality == null || this.quality != Qualities.Unknown.value) return this
        this.quality = quality
        return this
    }

    private fun extractPageProps(doc: Document): PageProps? {
        val rawJson = doc.selectFirst("script#__NEXT_DATA__")?.data() ?: return null
        return tryParseJson<NextDataRoot>(rawJson)?.props?.pageProps
    }

    private fun extractYear(date: String?): Int? {
        if (date.isNullOrBlank()) return null
        return YEAR_REGEX.find(date)?.value?.toIntOrNull()
    }

    private fun MediaEntry.toSearchResponse(): SearchResponse? {
        val title = titles?.name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val slugFull = url?.slug?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val isSeries = slugFull.startsWith("series/")
        val slug = slugFull.substringAfterLast("/")
        val href = if (isSeries) "$mainUrl/serie/$slug" else "$mainUrl/pelicula/$slug"
        val poster = images?.poster

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    // --- Modelos JSON (#__NEXT_DATA__) ---

    private data class NextDataRoot(
        @JsonProperty("props") val props: PropsWrapper? = null
    )

    private data class PropsWrapper(
        @JsonProperty("pageProps") val pageProps: PageProps? = null
    )

    private data class PageProps(
        @JsonProperty("movies") val movies: List<MediaEntry>? = null,
        @JsonProperty("pages") val pages: Int? = null,
        @JsonProperty("thisMovie") val thisMovie: MediaDetail? = null,
        @JsonProperty("thisSerie") val thisSerie: SerieDetail? = null,
        @JsonProperty("episode") val episode: EpisodeDetail? = null,
        @JsonProperty("serie") val serie: SerieDetail? = null,
    )

    private data class MediaEntry(
        @JsonProperty("titles") val titles: Titles? = null,
        @JsonProperty("images") val images: Images? = null,
        @JsonProperty("url") val url: UrlSlug? = null,
        @JsonProperty("releaseDate") val releaseDate: String? = null,
    )

    private data class MediaDetail(
        @JsonProperty("titles") val titles: Titles? = null,
        @JsonProperty("images") val images: Images? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("runtime") val runtime: Int? = null,
        @JsonProperty("releaseDate") val releaseDate: String? = null,
        @JsonProperty("rate") val rate: Rate? = null,
        @JsonProperty("genres") val genres: List<NamedItem>? = null,
        @JsonProperty("cast") val cast: CastInfo? = null,
        @JsonProperty("videos") val videos: Map<String, List<VideoOption>>? = null,
    )

    private data class SerieDetail(
        @JsonProperty("titles") val titles: Titles? = null,
        @JsonProperty("images") val images: Images? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("releaseDate") val releaseDate: String? = null,
        @JsonProperty("slug") val slug: SlugInfo? = null,
        @JsonProperty("rate") val rate: Rate? = null,
        @JsonProperty("genres") val genres: List<NamedItem>? = null,
        @JsonProperty("cast") val cast: CastInfo? = null,
        @JsonProperty("seasons") val seasons: List<SeasonEntry>? = null,
    )

    private data class SeasonEntry(
        @JsonProperty("number") val number: Int? = null,
        @JsonProperty("season") val season: Int? = null,
        @JsonProperty("episodes") val episodes: List<EpisodeEntry>? = null,
    )

    private data class EpisodeEntry(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("number") val number: Int? = null,
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("releaseDate") val releaseDate: String? = null,
        @JsonProperty("slug") val slug: SlugInfo? = null,
    )

    private data class EpisodeDetail(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("releaseDate") val releaseDate: String? = null,
        @JsonProperty("videos") val videos: Map<String, List<VideoOption>>? = null,
    )

    private data class Titles(
        @JsonProperty("name") val name: String? = null,
    )

    private data class Images(
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("backdrop") val backdrop: String? = null,
    )

    private data class UrlSlug(
        @JsonProperty("slug") val slug: String? = null,
    )

    private data class SlugInfo(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("season") val season: String? = null,
        @JsonProperty("episode") val episode: String? = null,
    )

    private data class Rate(
        @JsonProperty("average") val average: Double? = null,
    )

    private data class NamedItem(
        @JsonProperty("name") val name: String? = null,
    )

    private data class CastInfo(
        @JsonProperty("acting") val acting: List<NamedItem>? = null,
    )

    private data class VideoOption(
        @JsonProperty("cyberlocker") val cyberlocker: String? = null,
        @JsonProperty("result") val result: String? = null,
        @JsonProperty("quality") val quality: String? = null,
    )

    private data class LoadVideoData(
        @JsonProperty("videos") val videos: Map<String, List<VideoOption>>? = null,
        @JsonProperty("referer") val referer: String? = null,
    )

    private data class LinkCandidate(
        val language: String?,
        val server: String,
        val quality: String?,
        val url: String,
    )
}
