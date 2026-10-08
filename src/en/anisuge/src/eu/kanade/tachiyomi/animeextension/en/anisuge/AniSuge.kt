package eu.kanade.tachiyomi.animeextension.en.anisuge

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SAnimeEpisodeUpdate
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import extensions.utils.Source
import extensions.utils.addListPreference
import extensions.utils.get
import extensions.utils.getArrayOrNull
import extensions.utils.getString
import extensions.utils.getStringOrNull
import extensions.utils.parseAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

@Suppress("unused")
class AniSuge : Source() {

    override val name = "AniSuge"

    override val baseUrl = "https://anisuge.tv"

    override val lang = "en"

    override val supportsLatest = true

    override val json: Json by lazy {
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    private val ajaxHeaders by lazy {
        headersBuilder()
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Accept", "application/json, text/javascript, */*; q=0.01")
            .build()
    }

    // ============================== Popular ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage = getListing("$baseUrl/most-viewed?page=$page")

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): AnimesPage = getListing("$baseUrl/latest-updated?page=$page")

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val url = "$baseUrl/filter".toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) {
                addQueryParameter("keyword", query.trim())
            }

            filters.forEach { filter ->
                when (filter) {
                    is CheckBoxGroupFilter ->
                        filter.state
                            .filterIsInstance<CheckBoxValue>()
                            .filter { it.state }
                            .forEach { addQueryParameter(filter.param, it.value) }

                    is SortFilter -> addQueryParameter("sort", filter.selected)

                    else -> {}
                }
            }

            addQueryParameter("page", page.toString())
        }.build()

        return getListing(url.toString())
    }

    private suspend fun getListing(url: String): AnimesPage {
        val document = client.get(url).use { Jsoup.parse(it.body.string(), baseUrl) }
        return parseListing(document)
    }

    private fun parseListing(document: Document): AnimesPage {
        val animes = document.select("div.main-card > div.item").mapNotNull { item ->
            val poster = item.selectFirst("a.poster") ?: return@mapNotNull null
            val image = item.selectFirst("img")

            SAnime.create().apply {
                url = poster.absUrl("href").toAnimePath()
                title = item.selectFirst(".name a")?.text()?.trim().orEmpty()
                thumbnail_url = image?.let { it.absUrl("data-src").ifEmpty { it.absUrl("src") } }
            }
        }

        val hasNextPage = document.selectFirst("ul.pagination a[rel=next]") != null

        return AnimesPage(animes, hasNextPage)
    }

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Filters are applied together with the search text"),
        SortFilter(),
        CheckBoxGroupFilter("Type", "term_type[]", TYPES),
        CheckBoxGroupFilter("Genre", "genre[]", GENRES),
        CheckBoxGroupFilter("Status", "status[]", STATUSES),
        CheckBoxGroupFilter("Season", "season[]", SEASONS),
        CheckBoxGroupFilter("Year", "year[]", YEARS),
        CheckBoxGroupFilter("Language", "language[]", LANGUAGES),
        CheckBoxGroupFilter("Rating", "rating[]", RATINGS),
    )

    private class CheckBoxValue(name: String, val value: String) : AnimeFilter.CheckBox(name)

    private class CheckBoxGroupFilter(
        name: String,
        val param: String,
        values: List<Pair<String, String>>,
    ) : AnimeFilter.Group<CheckBoxValue>(name, values.map { CheckBoxValue(it.first, it.second) })

    private class SortFilter :
        AnimeFilter.Select<String>(
            "Sort by",
            SORTS.map { it.first }.toTypedArray(),
        ) {
        val selected: String get() = SORTS[state].second
    }

    // =========================== Anime Details ============================

    override suspend fun getAnimeEpisodeUpdate(
        anime: SAnime,
        episodes: List<SEpisode>,
        fetchDetails: Boolean,
        fetchEpisodes: Boolean,
    ): SAnimeEpisodeUpdate {
        val document = client.get(baseUrl + anime.url).use { Jsoup.parse(it.body.string(), baseUrl) }

        val newAnime = if (fetchDetails) parseDetails(document, anime) else anime
        val newEpisodes = if (fetchEpisodes) getEpisodes(document, anime) else episodes

        return SAnimeEpisodeUpdate(newAnime, newEpisodes)
    }

    private fun parseDetails(document: Document, anime: SAnime): SAnime = SAnime.create().apply {
        url = anime.url
        title = document.selectFirst("h1.title")?.text()?.trim() ?: anime.title
        thumbnail_url = document.selectFirst("img[itemprop=image]")?.absUrl("src") ?: anime.thumbnail_url

        val rows = document.select(".data .meta > div").associate { row ->
            val label = row.children().firstOrNull()?.text()?.trim()?.removeSuffix(":")?.trim().orEmpty()
            val value = row.selectFirst("span")?.text()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
            label to value
        }

        genre = rows["Genre"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.joinToString()
        author = rows["Studios"]?.takeIf { it.isNotEmpty() }
        status = when {
            rows["Status"].orEmpty().contains("Currently", true) -> SAnime.ONGOING
            rows["Status"].orEmpty().contains("Finished", true) -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }

        val synopsis = document.selectFirst(".description .full")?.text()?.trim()
            ?: document.selectFirst(".description")?.text()?.trim()

        description = buildString {
            if (!synopsis.isNullOrEmpty()) {
                append(synopsis.removeSuffix("less-").trim())
                append("\n\n")
            }
            document.selectFirst(".data .rating")?.text()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                appendLine("Rating: $it")
            }
            listOf("Type", "Premiered", "Aired", "MAL", "Duration", "Episodes").forEach { key ->
                rows[key]?.takeIf { it.isNotEmpty() && it != "?" }?.let {
                    appendLine("$key: ${it.replace("&NBSP", "").trim()}")
                }
            }
        }.trim()
    }

    // ============================== Episodes ==============================

    private suspend fun getEpisodes(document: Document, anime: SAnime): List<SEpisode> {
        val animeId = document.selectFirst(".watch-wrap")?.attr("data-id")?.takeIf { it.isNotEmpty() }
            ?: throw Exception("Could not find the anime id on the page")

        val html = client.get("$baseUrl/ajax/episode/list/$animeId", ajaxHeaders)
            .parseAs<HtmlResultDto>()
            .result

        return Jsoup.parseBodyFragment(html, baseUrl)
            .select("a[data-slug]")
            .map { ep ->
                val number = ep.attr("data-slug")
                val title = ep.attr("data-num").trim()
                val hasSub = ep.attr("data-sub") == "1"
                val hasDub = ep.attr("data-dub") == "1"

                SEpisode.create().apply {
                    // The token after '#' is what the server list endpoint needs
                    url = "${anime.url}/ep-$number#ids=${ep.attr("data-ids")}"
                    episode_number = number.toFloatOrNull() ?: 0F
                    name = if (title.isEmpty() || title.equals(number, true)) {
                        "Episode $number"
                    } else {
                        "Episode $number: $title"
                    }
                    date_upload = ep.attr("data-timestamp").toLongOrNull()?.times(1000L) ?: 0L
                    scanlator = listOfNotNull(
                        "Sub".takeIf { hasSub },
                        "Dub".takeIf { hasDub },
                    ).joinToString(" • ").ifEmpty { null }
                }
            }
            .sortedByDescending { it.episode_number }
    }

    override fun getEpisodeUrl(episode: SEpisode): String = baseUrl + episode.url.substringBefore("#")

    // ============================ Video Links =============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val ids = episode.url.substringAfter("#ids=", "").ifEmpty {
            throw Exception("Missing episode token, refresh the episode list")
        }

        val listUrl = "$baseUrl/ajax/server/list".toHttpUrl().newBuilder()
            .addQueryParameter("servers", ids)
            .build()

        val html = client.get(listUrl.toString(), ajaxHeaders, CacheControl.FORCE_NETWORK)
            .parseAs<HtmlResultDto>()
            .result

        val preferredType = preferences.getString(PREF_AUDIO_KEY, PREF_AUDIO_DEFAULT)

        return Jsoup.parseBodyFragment(html, baseUrl)
            .select(".server-type")
            .flatMap { group ->
                val type = group.attr("data-type")
                group.select(".server").mapNotNull { server ->
                    val linkId = server.attr("data-link-id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    val serverName = server.text().trim()

                    Hoster(
                        hosterUrl = "$baseUrl/ajax/server?get=$linkId",
                        hosterName = "${type.replaceFirstChar { it.uppercase() }} - $serverName",
                        memo = buildJsonObject {
                            put("type", type)
                            put("server", serverName)
                        },
                    )
                }
            }
            .sortedByDescending { it.memo.getString("type") == preferredType }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val embedUrl = client.get(hoster.hosterUrl, ajaxHeaders, CacheControl.FORCE_NETWORK)
            .parseAs<ServerResultDto>()
            .result.url

        val host = embedUrl.toHttpUrl().host
        return when {
            host.contains("megaplay") -> extractMegaPlay(embedUrl, hoster.hosterName)
            else -> throw Exception("Unsupported video host: $host")
        }
    }

    /**
     * TODO (playback is NOT working yet): findings from the live site (Oct 2026):
     *  - The embed page only loads with `Referer: https://anisuge.tv/` (otherwise it shows a 410 page).
     *  - The stream id is the number in the page `<title>` ("File 178727 - MegaPlay"), not a `data-id`.
     *  - `/stream/getSources?id=<id>` returns `tracks` (subtitles, plain) plus an encrypted `enc`
     *    field instead of `sources`; the player script (newclient.min.js) decrypts it into the
     *    master.m3u8 URL. That decryption still has to be reimplemented here.
     * The code below is the earlier unverified guess and will fail until that is done.
     */
    private suspend fun extractMegaPlay(embedUrl: String, label: String): List<Video> {
        val origin = embedUrl.toHttpUrl().let { "${it.scheme}://${it.host}" }

        val page = client.get(embedUrl, headers, CacheControl.FORCE_NETWORK).use { it.body.string() }
        val streamId = Regex("""data-id=["'](\d+)["']""").find(page)?.groupValues?.get(1)
            ?: throw Exception("MegaPlay: could not find the stream id")

        val sourceHeaders = headersBuilder()
            .set("Referer", embedUrl)
            .set("X-Requested-With", "XMLHttpRequest")
            .build()

        val data = client.get("$origin/stream/getSources?id=$streamId", sourceHeaders, CacheControl.FORCE_NETWORK)
            .parseAs<JsonObject>()

        val file = when (val sources = data["sources"]) {
            is JsonObject -> sources.getStringOrNull("file")
            is JsonArray -> sources.firstOrNull()?.jsonObject?.getStringOrNull("file")
            else -> null
        }?.takeIf { it.isNotEmpty() } ?: throw Exception("MegaPlay: no playable source found")

        val subtitles = data.getArrayOrNull("tracks").orEmpty().mapNotNull { track ->
            val obj = track.jsonObject
            val kind = obj.getStringOrNull("kind")
            val url = obj.getStringOrNull("file")
            if (url.isNullOrEmpty() || (kind != null && kind !in SUBTITLE_KINDS)) {
                return@mapNotNull null
            }
            Track(url, obj.getStringOrNull("label") ?: "Subtitle")
        }

        val playerHeaders = headersBuilder()
            .set("Referer", "$origin/")
            .set("Origin", origin)
            .build()

        return listOf(
            Video(
                videoUrl = file,
                videoTitle = label,
                headers = playerHeaders,
                subtitleTracks = subtitles,
            ),
        )
    }

    // ============================= Utilities ==============================

    /** `https://host/anime/slug-id/ep-3` -> `/anime/slug-id` */
    private fun String.toAnimePath(): String {
        val segments = toHttpUrl().pathSegments.filter { it.isNotEmpty() }
        return "/" + segments.take(2).joinToString("/")
    }

    @Serializable
    private class HtmlResultDto(val result: String)

    @Serializable
    private class ServerResultDto(val result: ServerDto)

    @Serializable
    private class ServerDto(val url: String)

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_AUDIO_KEY,
            default = PREF_AUDIO_DEFAULT,
            title = "Preferred audio",
            summary = "%s",
            entries = listOf("Sub", "Dub"),
            entryValues = listOf("sub", "dub"),
        )
    }

    companion object {
        private const val PREF_AUDIO_KEY = "preferred_audio"
        private const val PREF_AUDIO_DEFAULT = "sub"

        private val SUBTITLE_KINDS = setOf("captions", "subtitles", "subtitle")

        private val SORTS = listOf(
            "Default" to "default",
            "Latest Updated" to "latest-updated",
            "Score" to "score",
            "Name A-Z" to "name-az",
            "Release Date" to "release-date",
            "Most Viewed" to "most-viewed",
            "Number of episodes" to "number_of_episodes",
        )

        private val TYPES = listOf(
            "Movie" to "Movie",
            "Music" to "Music",
            "ONA" to "ONA",
            "OVA" to "OVA",
            "Special" to "Special",
            "TV" to "TV",
            "TV Short" to "TV_SHORT",
            "TV Special" to "TV Special",
            "Unknown" to "unknown",
        )

        private val GENRES = listOf(
            "Action" to "1",
            "Action & Adventure" to "2343",
            "Adventure" to "2",
            "Animation" to "2344",
            "Boys Love" to "2329",
            "Cars" to "538",
            "Comedy" to "8",
            "Dementia" to "453",
            "Demons" to "119",
            "Drama" to "62",
            "Ecchi" to "214",
            "Erotica" to "2319",
            "Fantasy" to "3",
            "Game" to "180",
            "Girls Love" to "2326",
            "Gourmet" to "2324",
            "Harem" to "215",
            "Hentai" to "2366",
            "Historical" to "70",
            "Horror" to "222",
            "Isekai" to "74",
            "Josei" to "404",
            "Kids" to "46",
            "Magic" to "203",
            "Martial Arts" to "114",
            "Mecha" to "123",
            "Military" to "125",
            "Music" to "242",
            "Mystery" to "57",
            "Parody" to "162",
            "Police" to "136",
            "Psychological" to "73",
            "Romance" to "28",
            "Samurai" to "163",
            "School" to "14",
            "Sci-Fi" to "12",
            "Sci-Fi & Fantasy" to "2351",
            "Seinen" to "50",
            "Shoujo" to "252",
            "Shoujo Ai" to "235",
            "Shounen" to "15",
            "Shounen Ai" to "233",
            "Slice of Life" to "35",
            "Space" to "124",
            "Sports" to "29",
            "Super Power" to "16",
            "Supernatural" to "9",
            "Suspense" to "2327",
            "Thriller" to "54",
            "Unknown" to "32",
            "Vampire" to "58",
        )

        private val STATUSES = listOf(
            "Finished Airing" to "finished-airing",
            "Currently Airing" to "currently-airing",
            "Not Yet Aired" to "not-yet-aired",
        )

        private val SEASONS = listOf(
            "Fall" to "fall",
            "Summer" to "summer",
            "Spring" to "spring",
            "Winter" to "winter",
            "Unknown" to "unknown",
        )

        private val YEARS = (2026 downTo 1980).map { it.toString() to it.toString() }

        private val LANGUAGES = listOf(
            "Sub" to "sub",
            "Dub" to "dub",
        )

        private val RATINGS = listOf(
            "G - All Ages" to "g",
            "PG - Children" to "pg",
            "PG 13 - Teens 13 and Older" to "pg_13",
            "R - 17+, Violence & Profanity" to "r",
            "R+ - Profanity & Mild Nudity" to "r+",
            "Rx - Hentai" to "rx",
        )
    }
}
