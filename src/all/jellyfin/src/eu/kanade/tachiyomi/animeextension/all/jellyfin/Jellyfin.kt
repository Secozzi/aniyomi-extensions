package eu.kanade.tachiyomi.animeextension.all.jellyfin

import android.annotation.SuppressLint
import android.app.Application
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import android.text.InputType
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.BuildConfig
import eu.kanade.tachiyomi.animeextension.all.jellyfin.dto.ItemDto
import eu.kanade.tachiyomi.animeextension.all.jellyfin.dto.ItemListDto
import eu.kanade.tachiyomi.animeextension.all.jellyfin.dto.ItemType
import eu.kanade.tachiyomi.animeextension.all.jellyfin.dto.LoginDto
import eu.kanade.tachiyomi.animeextension.all.jellyfin.dto.MediaLibraryDto
import eu.kanade.tachiyomi.animeextension.all.jellyfin.dto.PlaybackInfoDto
import eu.kanade.tachiyomi.animeextension.all.jellyfin.dto.SessionDto
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.UnmeteredSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.HttpException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Dns
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import org.apache.commons.text.StringSubstitutor
import rx.Single
import rx.schedulers.Schedulers
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.security.MessageDigest
import kotlin.getValue

class Jellyfin(private val suffix: String) : ConfigurableAnimeSource, AnimeHttpSource(), UnmeteredSource {
    internal val preferences: SharedPreferences by getPreferencesLazy {
        val quality = getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        Constants.QUALITY_MIGRATION_MAP[quality]?.let {
            edit().putString(PREF_QUALITY_KEY, it.toString()).commit()
        }
    }

    private val context: Application by injectLazy()
    private val deviceInfo by lazy { getDeviceInfo(context) }
    private val displayName by lazy { preferences.displayName.ifBlank { suffix } }

    override var baseUrl by LazyMutable { preferences.hostUrl }

    override val lang = "all"

    override val name by lazy { "Jellyfin ($displayName)" }

    override val supportsLatest = true

    override val id by lazy {
        val key = "jellyfin" + (if (suffix == "1") "" else " ($suffix)") + "/all/$versionId"
        val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }.reduce(Long::or) and Long.MAX_VALUE
    }

    override val client = network.client.newBuilder()
        .dns(Dns.SYSTEM)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .addHeader("Accept", "application/json, application/octet-stream;q=0.9, */*;q=0.8")
                .build()

            if (request.url.encodedPath.endsWith("AuthenticateByName")) {
                return@addInterceptor chain.proceed(request)
            }

            val apiKey = preferences.apiKey
            if (apiKey.isBlank()) {
                throw IOException("Please login in extension settings")
            }

            val authRequest = request.newBuilder()
                .addHeader("Authorization", getAuthHeader(deviceInfo, apiKey))
                .build()

            chain.proceed(authRequest)
        }
        .build()

    // ============================== Popular ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        checkPreferences()

        val startIndex = (page - 1) * SEASONS_FETCH_LIMIT
        val url = getItemsUrl(startIndex)

        return getPopularAnimePage(url, page)
    }

    private suspend fun getPopularAnimePage(url: HttpUrl, page: Int): AnimesPage {
        val items = client.get(url).parseAs<ItemListDto>()
        val animeList = items.items.flatMap {
            if (it.type == ItemType.BoxSet && preferences.splitCollections) {
                val boxSetUrl = url.newBuilder().apply {
                    setQueryParameter("ParentId", it.id)
                }.build()

                getAnimeList(client.get(boxSetUrl).parseAs())
            } else {
                listOf(it.toSAnime(baseUrl, preferences.userId))
            }
        }
        val hasNextPage = SEASONS_FETCH_LIMIT * page < items.totalRecordCount

        return AnimesPage(animeList, hasNextPage)
    }

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        checkPreferences()

        val startIndex = (page - 1) * SEASONS_FETCH_LIMIT
        val url = getItemsUrl(startIndex).newBuilder().apply {
            setQueryParameter("SortBy", "DateCreated,SortName")
            setQueryParameter("SortOrder", "Descending")
        }.build()

        return getPopularAnimePage(url, page)
    }

    // =============================== Search ===============================

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        checkPreferences()

        val startIndex = (page - 1) * SERIES_FETCH_LIMIT
        val url = getItemsUrl(startIndex).newBuilder().apply {
            // Search for series, rather than seasons, since season names can just be "Season 1"
            setQueryParameter("IncludeItemTypes", "Movie,Series")
            setQueryParameter("Limit", SERIES_FETCH_LIMIT.toString())
            setQueryParameter("SearchTerm", query)
        }.build()

        val items = client.get(url).parseAs<ItemListDto>()
        val animeList = items.items.flatMap { series ->
            val seasonsUrl = getItemsUrl(1).newBuilder().apply {
                setQueryParameter("ParentId", series.id)
                removeAllQueryParameters("StartIndex")
                removeAllQueryParameters("Limit")
            }.build()

            val seasonsData = client.get(seasonsUrl).parseAs<ItemListDto>()

            seasonsData.items.map { it.toSAnime(baseUrl, preferences.userId) }
        }

        val hasNextPage = SERIES_FETCH_LIMIT * page < items.totalRecordCount

        return AnimesPage(animeList, hasNextPage)
    }

    // =========================== Anime Details ============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        if (!anime.url.startsWith("http")) throw Exception("Migrate from jellyfin to jellyfin")

        val data = client.get(anime.url).parseAs<ItemDto>()
        val infoData = if (preferences.seriesData && data.seriesId != null) {
            val httpUrl = anime.url.toHttpUrl()
            val seriesUrl = httpUrl.newBuilder().apply {
                removePathSegment(httpUrl.pathSize - 1)
                addPathSegment(data.seriesId)
            }.build()

            client.get(seriesUrl).parseAs<ItemDto>()
        } else {
            data
        }

        return infoData.toSAnime(baseUrl, preferences.userId)
    }

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        if (!anime.url.startsWith("http")) throw Exception("Migrate from jellyfin to jellyfin")

        val url = getEpisodeListUrl(anime)
        val response = client.get(url)

        return if (url.fragment?.startsWith("boxSet") == true) {
            val data = response.parseAs<ItemListDto>()
            val animeList = data.items.map {
                it.toSAnime(baseUrl, preferences.userId)
            }.sortedByDescending { it.title }

            animeList.flatMap {
                episodeListParse(
                    response = client.get(getEpisodeListUrl(it)),
                    prefix = "${it.title} - ",
                )
            }
        } else {
            episodeListParse(response, "")
        }
    }

    private fun getEpisodeListUrl(anime: SAnime): HttpUrl {
        val httpUrl = anime.url.toHttpUrl()
        val itemId = httpUrl.pathSegments[3]
        val fragment = httpUrl.fragment!!

        return when {
            fragment.startsWith("seriesId") -> {
                httpUrl.newBuilder().apply {
                    encodedPath("/")
                    addPathSegment("Shows")
                    addPathSegment(fragment.split(",").last())
                    addPathSegment("Episodes")
                    addQueryParameter("seasonId", httpUrl.pathSegments.last())
                    addQueryParameter("userId", preferences.userId)
                    addQueryParameter("Fields", "Overview,MediaSources,DateCreated,OriginalTitle,SortName")
                }.build()
            }
            fragment.startsWith("boxSet") -> {
                httpUrl.newBuilder().apply {
                    removePathSegment(3)
                    addQueryParameter("Recursive", "true")
                    addQueryParameter("SortBy", "SortName")
                    addQueryParameter("SortOrder", "Ascending")
                    addQueryParameter("IncludeItemTypes", "Movie,Season,BoxSet,Series")
                    addQueryParameter("ParentId", itemId)
                    addQueryParameter("Fields", "DateCreated,OriginalTitle,SortName")
                }.build()
            }
            fragment.startsWith("series") -> {
                httpUrl.newBuilder().apply {
                    encodedPath("/")
                    addPathSegment("Shows")
                    addPathSegment(itemId)
                    addPathSegment("Episodes")
                    addQueryParameter("Fields", "DateCreated,OriginalTitle,SortName")
                }.build()
            }
            else -> {
                httpUrl.newBuilder().apply {
                    addQueryParameter("Fields", "DateCreated,OriginalTitle,SortName")
                }.build()
            }
        }
    }

    private fun episodeListParse(response: Response, prefix: String): List<SEpisode> {
        val itemList = if (response.request.url.pathSize > 3) {
            listOf(response.parseAs<ItemDto>())
        } else {
            response.parseAs<ItemListDto>().items
        }

        return itemList.map {
            it.toSEpisode(
                baseUrl = baseUrl,
                userId = preferences.userId,
                prefix = prefix,
                epDetails = preferences.epDetails,
                episodeTemplate = preferences.episodeTemplate,
            )
        }.reversed()
    }

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        if (!episode.url.startsWith("http")) throw Exception("Migrate from jellyfin to jellyfin")

        val item = client.get(episode.url).parseAs<ItemDto>()
        val mediaSource = item.mediaSources?.firstOrNull() ?: return emptyList()
        val itemId = item.id

        val videoList = mutableListOf<Video>()
        val subtitleList = mutableListOf<Track>()
        val externalSubtitleList = mutableListOf<Track>()

        var audioTrackIndex: Int? = null
        var subtitleTrackIndex: Int? = null
        var referenceBitrate = Constants.QUALITIES_LIST.first().videoBitrate

        mediaSource.mediaStreams.forEach { media ->
            when (media.type) {
                "Video" -> {
                    referenceBitrate = media.bitRate!!
                }
                "Subtitle" -> {
                    if (media.supportsExternalStream) {
                        val subtitleUrl = baseUrl.toHttpUrl().newBuilder().apply {
                            addPathSegment("Videos")
                            addPathSegment(itemId)
                            addPathSegment(mediaSource.id!!)
                            addPathSegment("Subtitles")
                            addPathSegment(media.index.toString())
                            addPathSegment("0")
                            addPathSegment("Stream.${media.codec}")
                        }.build().toString()

                        if (media.isExternal) {
                            externalSubtitleList.add(Track(subtitleUrl, media.displayTitle!!))
                        }
                        subtitleList.add(Track(subtitleUrl, media.displayTitle!!))
                    }
                    if (media.language == preferences.subLang) {
                        subtitleTrackIndex = media.index
                    }
                }
                "Audio" -> {
                    if (media.language == preferences.audioLang) {
                        audioTrackIndex = media.index
                    }
                }
            }
        }

        val qualities = Constants.QUALITIES_LIST.takeWhile { it.videoBitrate < referenceBitrate }
        val playbackInfo = PlaybackInfoDto(
            userId = preferences.userId,
            isPlayback = true,
            mediaSourceId = mediaSource.id!!,
            maxStreamingBitrate = qualities.last().videoBitrate.toLong(),
            audioStreamIndex = audioTrackIndex?.toString(),
            subtitleStreamIndex = subtitleTrackIndex?.toString(),
            alwaysBurnInSubtitleWhenTranscoding = preferences.burnSub,
            enableTranscoding = true,
            deviceProfile = getDeviceProfile(
                name = deviceInfo.name,
                videoCodec = preferences.videoCodec,
                videoBitrate = qualities.last().videoBitrate,
                audioBitrate = qualities.last().audioBitrate,
            ),
        )

        val sessionUrl = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("Items")
            addPathSegment(itemId)
            addPathSegment("PlaybackInfo")
            addQueryParameter("userId", preferences.userId)
        }.build().toString()

        val sessionData = client.post(
            url = sessionUrl,
            body = JSON_INSTANCE.encodeToString(playbackInfo).toJsonBody(),
        ).parseAs<SessionDto>()

        val videoBitrate = mediaSource.bitrate!!.formatBytes().replace("B", "b")
        val staticUrl = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("Videos")
            addPathSegment(itemId)
            addPathSegment("stream")
            addQueryParameter("static", "True")
            addQueryParameter("PlaySessionId", sessionData.playSessionId)
        }.build().toString()

        val videoHeaders = headersBuilder()
            .add("Authorization", getAuthHeader(deviceInfo, preferences.apiKey))
            .build()

        val staticVideo = Video(
            url = Long.MAX_VALUE.toString(),
            quality = "Source - ${videoBitrate}ps",
            videoUrl = staticUrl,
            subtitleTracks = externalSubtitleList,
            headers = videoHeaders,
        )

        // Build video list
        if (mediaSource.supportsDirectStream) {
            videoList.add(staticVideo)
        }

        val transcodingUrl = sessionData.mediaSources.first().transcodingUrl
            ?.takeIf { mediaSource.supportsTranscoding }
            ?.let { (baseUrl + it).toHttpUrl() }
            ?: return videoList

        qualities.forEach {
            val url = transcodingUrl.newBuilder().apply {
                setQueryParameter("VideoBitrate", it.videoBitrate.toString())
                setQueryParameter("AudioBitrate", it.audioBitrate.toString())
            }.build().toString()

            videoList.add(
                Video(
                    url = it.videoBitrate.toString(),
                    quality = it.description,
                    videoUrl = url,
                    subtitleTracks = subtitleList,
                    headers = videoHeaders,
                ),
            )
        }

        return videoList.sort()
    }

    override fun List<Video>.sort(): List<Video> {
        return sortedWith(
            compareBy(
                { it.url.equals(preferences.quality, true) },
                { it.url.toLong() },
            ),
        ).reversed()
    }

    // =============================== Login ================================

    data class DeviceInfo(
        val clientName: String,
        val version: String,
        val id: String,
        val name: String,
    )

    // From https://github.com/jellyfin/jellyfin-sdk-kotlin
    private fun Application.getDeviceName(): String {
        // Use name from device settings
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            val name = Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)
            if (!name.isNullOrBlank()) return name
        }

        // Concatenate the name based on manufacturer and model
        val manufacturer = Build.MANUFACTURER
        val model = Build.MODEL

        return if (model.startsWith(manufacturer) || manufacturer.isBlank()) {
            model
        } else {
            "$manufacturer $model"
        }
    }

    fun getDeviceInfo(context: Application): DeviceInfo {
        @SuppressLint("HardwareIds")
        val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        val name = context.getDeviceName()

        return DeviceInfo(
            clientName = "Aniyomi",
            version = BuildConfig.VERSION_NAME,
            id = id,
            name = name,
        )
    }

    private suspend fun authenticate(username: String, password: String): LoginDto {
        val authHeaders = Headers.headersOf("Authorization", getAuthHeader(deviceInfo))

        val body = buildJsonObject {
            put("Username", username)
            put("Pw", password)
        }.toBody()

        return try {
            val resp = client.post(
                url = "$baseUrl/Users/AuthenticateByName",
                headers = authHeaders,
                body = body,
            )

            resp.parseAs<LoginDto>()
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to perform login", e)
            throw Exception("Failed to login")
        }
    }

    // ============================= Utilities ==============================

    private fun getItemsUrl(startIndex: Int): HttpUrl {
        return baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("Users")
            addPathSegment(preferences.userId)
            addPathSegment("Items")
            addQueryParameter("StartIndex", startIndex.toString())
            addQueryParameter("Limit", SEASONS_FETCH_LIMIT.toString())
            addQueryParameter("Recursive", "true")
            addQueryParameter("SortBy", "SortName")
            addQueryParameter("SortOrder", "Ascending")
            addQueryParameter(
                "IncludeItemTypes",
                listOf(
                    ItemType.Movie,
                    ItemType.Season,
                    ItemType.BoxSet,
                ).joinToString(",") { it.name },
            )
            addQueryParameter("ImageTypeLimit", "1")
            addQueryParameter("ParentId", preferences.selectedLibrary)
            addQueryParameter("EnableImageTypes", "Primary")
        }.build()
    }

    private fun getAnimeList(itemList: ItemListDto): List<SAnime> {
        return itemList
            .items
            .map { it.toSAnime(baseUrl, preferences.userId) }
    }

    private fun checkPreferences() {
        if (preferences.selectedLibrary.isBlank()) {
            throw Exception("Select library in extension settings")
        }
    }

    @Suppress("SpellCheckingInspection")
    companion object {
        private const val SEASONS_FETCH_LIMIT = 20
        private const val SERIES_FETCH_LIMIT = 5
        private val LIBRARY_BLACKLIST = listOf(
            "music",
            "musicvideos",
            "trailers",
            "books",
            "photos",
            "livetv",
        )

        const val APIKEY_KEY = "api_key"
        const val USERID_KEY = "user_id"
        const val LIBRARY_LIST_KEY = "media_library_list"

        internal const val EXTRA_SOURCES_COUNT_KEY = "extraSourcesCount"
        internal const val EXTRA_SOURCES_COUNT_DEFAULT = "3"
        private val EXTRA_SOURCES_ENTRIES = (1..10).map { it.toString() }

        private const val PREF_CUSTOM_LABEL_KEY = "pref_label"
        private const val PREF_CUSTOM_LABEL_DEFAULT = ""

        private const val HOSTURL_KEY = "host_url"
        private const val HOSTURL_DEFAULT = ""

        private const val USERNAME_KEY = "username"
        private const val USERNAME_DEFAULT = ""

        private const val PASSWORD_KEY = "password"
        private const val PASSWORD_DEFAULT = ""

        private const val MEDIA_LIBRARY_KEY = "library_pref"
        private const val MEDIA_LIBRARY_DEFAULT = ""

        private const val PREF_EPISODE_NAME_TEMPLATE_KEY = "pref_episode_name_template"
        private const val PREF_EPISODE_NAME_TEMPLATE_DEFAULT = "{type} {number} - {title}"

        private const val PREF_EP_DETAILS_KEY = "pref_episode_details_key"
        private val PREF_EP_DETAILS = listOf("Overview", "Runtime", "Size")
        private val PREF_EP_DETAILS_DEFAULT = emptySet<String>()

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "Source"

        private const val PREF_VIDEO_CODEC_KEY = "pref_video_codec"
        private const val PREF_VIDEO_CODEC_DEFAULT = "h264"

        private const val PREF_AUDIO_KEY = "preferred_audioLang"
        private const val PREF_AUDIO_DEFAULT = "jpn"

        private const val PREF_SUB_KEY = "preferred_subLang"
        private const val PREF_SUB_DEFAULT = "eng"

        private const val PREF_BURN_SUB_KEY = "pref_burn_subs"
        private const val PREF_BURN_SUB_DEFAULT = false

        private const val PREF_INFO_TYPE = "preferred_meta_type"
        private const val PREF_INFO_DEFAULT = false

        private const val PREF_SPLIT_COLLECTIONS_KEY = "preferred_split_col"
        private const val PREF_SPLIT_COLLECTIONS_DEFAULT = false

        private val SUBSTITUTE_VALUES = hashMapOf(
            "title" to "",
            "originalTitle" to "",
            "sortTitle" to "",
            "type" to "",
            "typeShort" to "",
            "seriesTitle" to "",
            "seasonTitle" to "",
            "number" to "",
            "createdDate" to "",
            "releaseDate" to "",
            "size" to "",
            "sizeBytes" to "",
            "runtime" to "",
            "runtimeS" to "",
        )
        private val STRING_SUBSTITUTOR = StringSubstitutor(SUBSTITUTE_VALUES, "{", "}").apply {
            isEnableUndefinedVariableException = true
        }

        private const val LOG_TAG = "Jellyfin"
    }

    // ============================ Preferences =============================

    // Updating `name` requires a restart, so there's no point in using a delegate for this
    private val SharedPreferences.displayName
        get() = getString(PREF_CUSTOM_LABEL_KEY, PREF_CUSTOM_LABEL_DEFAULT)!!

    private var SharedPreferences.libraryList by preferences.delegate(LIBRARY_LIST_KEY, "[]")

    private val userIdDelegate = preferences.delegate(USERID_KEY, "")
    private var SharedPreferences.userId by userIdDelegate

    private val apiKeyDelegate = preferences.delegate(APIKEY_KEY, "")
    private var SharedPreferences.apiKey by apiKeyDelegate

    private val hostUrlDelegate = preferences.delegate(HOSTURL_KEY, HOSTURL_DEFAULT)
    private val SharedPreferences.hostUrl by hostUrlDelegate

    private val usernameDelegate = preferences.delegate(USERNAME_KEY, USERNAME_DEFAULT)
    private val SharedPreferences.username by usernameDelegate

    private val passwordDelegate = preferences.delegate(PASSWORD_KEY, PASSWORD_DEFAULT)
    private val SharedPreferences.password by passwordDelegate

    private val selectedLibraryDelegate = preferences.delegate(MEDIA_LIBRARY_KEY, MEDIA_LIBRARY_DEFAULT)
    private var SharedPreferences.selectedLibrary by selectedLibraryDelegate

    private val episodeTemplateDelegate = preferences.delegate(PREF_EPISODE_NAME_TEMPLATE_KEY, PREF_EPISODE_NAME_TEMPLATE_DEFAULT)
    private val SharedPreferences.episodeTemplate by episodeTemplateDelegate

    private val epDetailsDelegate = preferences.delegate(PREF_EP_DETAILS_KEY, PREF_EP_DETAILS_DEFAULT)
    private val SharedPreferences.epDetails by epDetailsDelegate

    private val qualityDelegate = preferences.delegate(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)
    private val SharedPreferences.quality by qualityDelegate

    private val videoCodecDelegate = preferences.delegate(PREF_VIDEO_CODEC_KEY, PREF_VIDEO_CODEC_DEFAULT)
    private val SharedPreferences.videoCodec by videoCodecDelegate

    private val audioLangDelegate = preferences.delegate(PREF_AUDIO_KEY, PREF_AUDIO_DEFAULT)
    private val SharedPreferences.audioLang by audioLangDelegate

    private val subLangDelegate = preferences.delegate(PREF_SUB_KEY, PREF_SUB_DEFAULT)
    private val SharedPreferences.subLang by subLangDelegate

    private val burnSubDelegate = preferences.delegate(PREF_BURN_SUB_KEY, PREF_BURN_SUB_DEFAULT)
    private val SharedPreferences.burnSub by burnSubDelegate

    private val seriesDataDelegate = preferences.delegate(PREF_INFO_TYPE, PREF_INFO_DEFAULT)
    private val SharedPreferences.seriesData by seriesDataDelegate

    private val splitCollectionDelegate = preferences.delegate(PREF_SPLIT_COLLECTIONS_KEY, PREF_SPLIT_COLLECTIONS_DEFAULT)
    private val SharedPreferences.splitCollections by splitCollectionDelegate

    private fun SharedPreferences.clearCredentials() {
        preferences.libraryList = "[]"
        preferences.selectedLibrary = MEDIA_LIBRARY_DEFAULT
        preferences.userId = ""
        preferences.apiKey = ""
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val mediaLibrarySummary: (String) -> String = {
            if (it.isBlank()) {
                "Currently not logged in"
            } else {
                "Selected: %s"
            }
        }
        val mediaLibraryPref = ListPreference(screen.context).apply {
            val libraryList = JSON_INSTANCE.decodeFromString<List<MediaLibraryDto>>(preferences.libraryList)

            key = MEDIA_LIBRARY_KEY
            title = "Select media library"
            summary = mediaLibrarySummary(preferences.apiKey)
            entries = libraryList.map { it.name }.toTypedArray()
            entryValues = libraryList.map { it.id }.toTypedArray()
            setDefaultValue(MEDIA_LIBRARY_DEFAULT)
            setEnabled(preferences.apiKey.isNotBlank())

            setOnPreferenceChangeListener { _, newValue ->
                selectedLibraryDelegate.updateValue(newValue as String)
                true
            }
        }

        fun onCompleteLogin(result: Boolean) {
            mediaLibraryPref.setEnabled(result)
            mediaLibraryPref.summary = mediaLibrarySummary(if (result) "unused" else "")
            mediaLibraryPref.value = ""

            if (result) {
                val libraryList = JSON_INSTANCE.decodeFromString<List<MediaLibraryDto>>(preferences.libraryList)
                mediaLibraryPref.entries = libraryList.map { it.name }.toTypedArray()
                mediaLibraryPref.entryValues = libraryList.map { it.id }.toTypedArray()
            } else {
                preferences.clearCredentials()
            }
        }

        fun logIn() {
            Single.fromCallable {
                runBlocking(Dispatchers.IO) {
                    mediaLibraryPref.setEnabled(false)
                    mediaLibraryPref.summary = "Loading..."

                    preferences.clearCredentials()

                    val loginDto = authenticate(preferences.username, preferences.password)

                    preferences.userId = loginDto.sessionInfo.userId
                    preferences.apiKey = loginDto.accessToken

                    val getLibrariesUrl = baseUrl.toHttpUrl().newBuilder().apply {
                        addPathSegment("Users")
                        addPathSegment(loginDto.sessionInfo.userId)
                        addPathSegment("Items")
                    }.build()

                    client.get(getLibrariesUrl).parseAs<ItemListDto>()
                }
            }
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io())
                .subscribe(
                    { libraryListDto ->
                        val libraryList = libraryListDto.items
                            .filterNot { it.collectionType in LIBRARY_BLACKLIST }
                            .map { MediaLibraryDto(it.name, it.id) }

                        preferences.libraryList = JSON_INSTANCE.encodeToString<List<MediaLibraryDto>>(libraryList)

                        displayToast("Login successful")
                        onCompleteLogin(true)
                    },
                    { e ->
                        val message = when {
                            e is HttpException && e.code == 401 -> "Invalid credentials"
                            else -> e.message
                        }

                        Log.e(LOG_TAG, "Failed to login", e)
                        displayToast("Login failed: $message")
                        onCompleteLogin(false)
                    },
                )

            mediaLibraryPref.setEnabled(true)
        }

        if (suffix == "1") {
            screen.addListPreference(
                key = EXTRA_SOURCES_COUNT_KEY,
                default = EXTRA_SOURCES_COUNT_DEFAULT,
                title = "Number of sources",
                summary = "Number of Jellyfin sources to create. There will always be at least one Jellyfin source.",
                entries = EXTRA_SOURCES_ENTRIES,
                entryValues = EXTRA_SOURCES_ENTRIES,
                restartRequired = true,
            )
        }

        screen.addEditTextPreference(
            key = PREF_CUSTOM_LABEL_KEY,
            default = suffix,
            title = "Source display name",
            summary = displayName.ifBlank { "Here you can change the source displayed suffix" },
            restartRequired = true,
        )

        val addressUrlSummary: (String) -> String = { it.ifBlank { "The server address" } }
        screen.addEditTextPreference(
            key = HOSTURL_KEY,
            default = HOSTURL_DEFAULT,
            title = "Address",
            summary = addressUrlSummary(baseUrl),
            getSummary = addressUrlSummary,
            dialogMessage = "The address must not end with a forward slash.",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            validate = { it.toHttpUrlOrNull() != null && !it.endsWith("/") },
            validationMessage = { "The URL is invalid, malformed, or ends with a slash" },
        ) {
            baseUrl = it
            hostUrlDelegate.updateValue(it)

            if (it.isBlank()) {
                onCompleteLogin(false)
            } else {
                if (preferences.username.isNotBlank() && preferences.password.isNotBlank()) {
                    logIn()
                }
            }
        }

        val userNameSummary: (String) -> String = { it.ifBlank { "The user account name" } }
        screen.addEditTextPreference(
            key = USERNAME_KEY,
            default = USERNAME_DEFAULT,
            title = "Username",
            summary = userNameSummary(preferences.username),
            getSummary = userNameSummary,
        ) {
            usernameDelegate.updateValue(it)
            if (it.isBlank()) {
                onCompleteLogin(false)
            } else {
                if (baseUrl.isNotBlank() && preferences.password.isNotBlank()) {
                    logIn()
                }
            }
        }

        val passwordSummary: (String) -> String = {
            if (it.isBlank()) "The user account password" else "•".repeat(it.length)
        }
        screen.addEditTextPreference(
            key = PASSWORD_KEY,
            default = PASSWORD_DEFAULT,
            title = "Password",
            summary = passwordSummary(preferences.password),
            getSummary = passwordSummary,
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        ) {
            passwordDelegate.updateValue(it)
            if (it.isBlank()) {
                onCompleteLogin(false)
            } else {
                if (baseUrl.isNotBlank() && preferences.username.isNotBlank()) {
                    logIn()
                }
            }
        }

        screen.addPreference(mediaLibraryPref)

        screen.addEditTextPreference(
            key = PREF_EPISODE_NAME_TEMPLATE_KEY,
            default = PREF_EPISODE_NAME_TEMPLATE_DEFAULT,
            title = "Episode title format",
            summary = "Customize how episode names appear",
            dialogMessage = """
            |Supported placeholders:
            |- {title}: Episode name
            |- {originalTitle}: Original title
            |- {sortTitle}: Sort title
            |- {type}: Type, 'Episode' for episodes and 'Movie' for movies
            |- {typeShort}: Type, 'Ep.' for episodes and 'Movie' for movies
            |- {seriesTitle}: Series name
            |- {seasonTitle}: Season name
            |- {number}: Episode number
            |- {createdDate}: Episode creation date
            |- {releaseDate}: Episode release date
            |- {size}: Episode file size (formatted)
            |- {sizeBytes}: Episode file size (in bytes)
            |- {runtime}: Episode runtime (formatted)
            |- {runtimeS}: Episode runtime (in seconds)
            |If you wish to place some text between curly brackets, place the escape character "$"
            |before the opening curly bracket, e.g. ${'$'}{series}.
            """.trimMargin(),
            inputType = InputType.TYPE_CLASS_TEXT,
            validate = {
                try {
                    STRING_SUBSTITUTOR.replace(it)
                    true
                } catch (_: IllegalArgumentException) {
                    false
                }
            },
            validationMessage = { "Invalid episode title format" },
            lazyDelegate = episodeTemplateDelegate,
        )

        @Suppress("SpellCheckingInspection")
        screen.addSetPreference(
            key = PREF_EP_DETAILS_KEY,
            default = PREF_EP_DETAILS_DEFAULT,
            title = "Additional details for episodes",
            summary = "Show additional details about an episode in the scanlator field",
            entries = PREF_EP_DETAILS,
            entryValues = PREF_EP_DETAILS,
            lazyDelegate = epDetailsDelegate,
        )

        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            default = PREF_QUALITY_DEFAULT,
            title = "Preferred quality",
            summary = "Preferred quality. 'Source' means no transcoding.",
            entries = listOf("Source") + Constants.QUALITIES_LIST.reversed().map { it.description },
            entryValues = listOf("Source") + Constants.QUALITIES_LIST.reversed().map { it.videoBitrate.toString() },
            lazyDelegate = qualityDelegate,
        )

        screen.addEditTextPreference(
            key = PREF_VIDEO_CODEC_KEY,
            default = PREF_VIDEO_CODEC_DEFAULT,
            title = "Transcoding video codec",
            summary = "Video codec when transcoding. Does not affect 'Source' quality.",
            lazyDelegate = videoCodecDelegate,
        )

        screen.addEditTextPreference(
            key = PREF_AUDIO_KEY,
            default = PREF_AUDIO_DEFAULT,
            title = "Preferred transcoding audio language",
            summary = "Preferred audio when transcoding. Does not affect 'Source' quality.",
            dialogMessage = "Enter language as 3 letter ISO 639-2/T code",
            validate = { it in Constants.LANG_CODES },
            validationMessage = {
                if (it.length == 3) {
                    "'$it' is not a valid code"
                } else {
                    "'$it' is not a three letter code"
                }
            },
            lazyDelegate = audioLangDelegate,
        )

        screen.addEditTextPreference(
            key = PREF_SUB_KEY,
            default = PREF_SUB_DEFAULT,
            title = "Preferred transcoding subtitle language",
            summary = "Preferred subtitle when transcoding. Does not affect 'Source' quality.",
            dialogMessage = "Enter language as 3 letter ISO 639-2/T code",
            validate = { it in Constants.LANG_CODES },
            validationMessage = {
                if (it.length == 3) {
                    "'$it' is not a valid code"
                } else {
                    "'$it' is not a three letter code"
                }
            },
            lazyDelegate = subLangDelegate,
        )

        screen.addSwitchPreference(
            key = PREF_BURN_SUB_KEY,
            default = PREF_BURN_SUB_DEFAULT,
            title = "Burn in subtitles",
            summary = "Burn in subtitles when transcoding. Does not affect 'Source' quality.",
            lazyDelegate = burnSubDelegate,
        )

        screen.addSwitchPreference(
            key = PREF_INFO_TYPE,
            default = PREF_INFO_DEFAULT,
            title = "Retrieve metadata from series",
            summary = "Enable this to retrieve metadata from series instead of season when applicable.",
            lazyDelegate = seriesDataDelegate,
        )

        screen.addSwitchPreference(
            key = PREF_SPLIT_COLLECTIONS_KEY,
            default = PREF_SPLIT_COLLECTIONS_DEFAULT,
            title = "Split collections",
            summary = "Split each item in a collection into its own entry",
            lazyDelegate = splitCollectionDelegate,
        )
    }

    // TODO(16): Remove with ext lib 16
    override fun popularAnimeRequest(page: Int) = throw UnsupportedOperationException()
    override fun popularAnimeParse(response: Response) = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException()
    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = throw UnsupportedOperationException()
    override fun searchAnimeParse(response: Response) = throw UnsupportedOperationException()
    override fun animeDetailsRequest(anime: SAnime) = throw UnsupportedOperationException()
    override fun animeDetailsParse(response: Response) = throw UnsupportedOperationException()
    override fun episodeListRequest(anime: SAnime) = throw UnsupportedOperationException()
    override fun episodeListParse(response: Response) = throw UnsupportedOperationException()
    override fun videoListRequest(episode: SEpisode) = throw UnsupportedOperationException()
    override fun videoListParse(response: Response) = throw UnsupportedOperationException()
}
