package com.wanderwildwood.jimeikin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Minimal YouTube Music search client using the Innertube JSON API.
 *
 * This is intentionally small and focused: it only implements anonymous
 * search for songs/albums and returns just the metadata CalmMusic needs
 * (videoId, title, artist, optional album, duration).
 */
interface YouTubeMusicInnertubeClient {
    suspend fun searchSongs(query: String, limit: Int = 25): List<InnertubeSongResult>
    suspend fun searchAlbums(query: String, limit: Int = 25): List<InnertubeAlbumResult>
    suspend fun searchArtists(query: String, limit: Int = 25): List<InnertubeArtistResult>

    suspend fun getArtistPage(browseId: String): InnertubeArtistPage

    suspend fun getBestAudioUrl(videoId: String): String
}


data class InnertubeSongResult(
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMillis: Long?,
)

data class InnertubeAlbumResult(
    val albumId: String,
    val title: String,
    val artist: String?,
    val year: Int?,
)

data class InnertubeArtistResult(
    val browseId: String,
    val name: String,
)

data class InnertubeArtistPage(
    val name: String,
    val songs: List<InnertubeSongResult>,
    val albums: List<InnertubeAlbumResult>,
    val singles: List<InnertubeAlbumResult>,
)

data class InnertubeSearchResults(
    val songs: List<InnertubeSongResult>,
    val albums: List<InnertubeAlbumResult>,
)

private enum class MusicSearchFilter {
    NONE,
    SONGS,
    ALBUMS,
    ARTISTS,
}

private val VISITOR_DATA_REGEX: Regex = Regex("\"visitorData\":\"([^\"]+)\"")

private const val PARAMS_SONGS: String = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"
private const val PARAMS_ALBUMS: String = "EgWKAQIYAWoKEAkQChAFEAMQBA%3D%3D"
private const val PARAMS_ARTISTS: String = "EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D"
private const val BROWSE_URL: String =
    "https://youtubei.googleapis.com/youtubei/v1/browse?prettyPrint=false&key=AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"

internal class YouTubeMusicInnertubeClientImpl(
    private val httpClient: OkHttpClient,
    private val cookieProvider: () -> String? = { null },
) : YouTubeMusicInnertubeClient {

    private val apiKey: String = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
    private val baseUrl: String = "https://youtubei.googleapis.com/youtubei/v1/search?prettyPrint=false&key=$apiKey"

    /**
     * Audio streams are resolved straight from Innertube's player endpoint.
     *
     * This used to go through a public Piped instance, but that whole approach
     * died: api.piped.io no longer resolves in DNS at all, pipedapi.kavin.rocks
     * answers 5xx, and the one instance still listed in Piped's own registry
     * returns video metadata with an empty audioStreams array. Depending on
     * somebody else's public proxy staying alive was the real bug.
     *
     * The VISIONOS client plus a visitorData token is used deliberately.
     * YouTube rotates which clients get unciphered, un-throttled URLs
     * anonymously, so this WILL change again -- re-probe before assuming the
     * bug is local.
     *
     * Two separate things have to hold, and checking only the first is the
     * trap that cost a whole debugging session:
     *  1. the player endpoint answers OK with plain `url` fields, AND
     *  2. those URLs actually serve the WHOLE file. Verify with an
     *     open-ended `Range: bytes=0-` and a tail range, not just
     *     `bytes=0-16383` -- a PO-token-throttled URL happily returns 206
     *     for a small head request and 403 for everything past ~1 MB.
     *
     * History, so the same ground isn't re-covered:
     *  - Piped (any instance): dead, the network is gone.
     *  - WEB_REMIX (still used for search/browse, where it works fine):
     *    answers UNPLAYABLE at the player endpoint.
     *  - ANDROID_MUSIC / IOS_MUSIC / TVHTML5 / ANDROID_VR: LOGIN_REQUIRED
     *    ("Sign in to confirm you're not a bot") for real music tracks.
     *    ANDROID_VR worked 2026-08-18 and was dead by 2026-08-25, while
     *    STILL answering OK for ordinary non-music videos -- which makes the
     *    breakage look intermittent. Always probe with an actual music track.
     *  - IOS: resolves fine, but its URLs are PO-token-throttled to roughly
     *    the first 1 MB. ExoPlayer sends `Range: bytes=0-`, gets 403, and
     *    the UI sits at 0:00 with a real duration showing.
     *  - VISIONOS *without* visitorData: LOGIN_REQUIRED.
     *  - VISIONOS *with* visitorData: 6/7 tracks fully playable, open-ended
     *    and tail ranges both 206. Client context mirrors yt-dlp's.
     *
     * The one track that still fails is gated for every anonymous client;
     * that is what the NewPipe fallback tier in PlaybackService is for.
     */
    private val playerUrl: String = "https://youtubei.googleapis.com/youtubei/v1/player?prettyPrint=false"

    private val visionOsClientVersion: String = "1.02"
    private val visionOsDeviceModel: String = "RealityDevice17,1"
    private val visionOsVersion: String = "26.5.23O471"
    private val visionOsUserAgent: String =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/26.0 Safari/605.1.15"

    /**
     * A visitorData token, harvested from the YouTube home page. Without one
     * the VISIONOS player request answers LOGIN_REQUIRED. Cached because it
     * stays valid for a long time; refreshed once on a failed player request
     * before giving up, so an expired token self-heals.
     */
    @Volatile
    private var cachedVisitorData: String? = null

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val originYouTubeMusic = "https://music.youtube.com"

    /**
     * Adds the signed-in user's cookie and SAPISIDHASH authorization to a
     * request when a YouTube account is connected, so search reflects the
     * user's own account. See:
     * https://developers.google.com/youtube/v3/guides/authentication#OAuth2_Auth_For_Server_Side_Apps_Alt
     * (SAPISIDHASH is the same scheme music.youtube.com's own web client uses.)
     */
    private fun applyAuthHeaders(builder: Request.Builder) {
        val cookie = cookieProvider() ?: return
        val sapisid = cookie.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("SAPISID=") }
            ?.substringAfter("SAPISID=")
            ?: return

        val timestamp = System.currentTimeMillis() / 1000
        val hash = sha1("$timestamp $sapisid $originYouTubeMusic")

        builder
            .header("Cookie", cookie)
            .header("Authorization", "SAPISIDHASH ${timestamp}_$hash")
            .header("Origin", originYouTubeMusic)
    }

    private fun sha1(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    override suspend fun searchSongs(query: String, limit: Int): List<InnertubeSongResult> {
        if (query.isBlank() || limit <= 0) return emptyList()
        return searchInternal(
            query = query,
            filter = MusicSearchFilter.SONGS,
            limitSongs = limit,
            limitAlbums = 0,
        ).songs
    }

    override suspend fun searchAlbums(query: String, limit: Int): List<InnertubeAlbumResult> {
        if (query.isBlank() || limit <= 0) return emptyList()
        return searchInternal(
            query = query,
            filter = MusicSearchFilter.ALBUMS,
            limitSongs = 0,
            limitAlbums = limit,
        ).albums
    }

    override suspend fun searchArtists(query: String, limit: Int): List<InnertubeArtistResult> {
        if (query.isBlank() || limit <= 0) return emptyList()

        return withContext(Dispatchers.IO) {
            val bodyJson = buildSearchRequestBody(query, MusicSearchFilter.ARTISTS)
            val requestBody = bodyJson.toString().toRequestBody(jsonMediaType)

            val requestBuilder = Request.Builder()
                .url(baseUrl)
                .post(requestBody)
            applyAuthHeaders(requestBuilder)

            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val bodyString = response.body?.string() ?: return@withContext emptyList()

                parseArtistSearchResults(JSONObject(bodyString), limit)
            }
        }
    }

    override suspend fun getArtistPage(browseId: String): InnertubeArtistPage {
        if (browseId.isBlank()) return InnertubeArtistPage("", emptyList(), emptyList(), emptyList())

        return withContext(Dispatchers.IO) {
            val context = JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", "1.20250101.01.00")
                    put("hl", "en")
                    put("gl", "US")
                })
            }
            val bodyJson = JSONObject().apply {
                put("context", context)
                put("browseId", browseId)
            }
            val requestBody = bodyJson.toString().toRequestBody(jsonMediaType)

            val requestBuilder = Request.Builder()
                .url(BROWSE_URL)
                .post(requestBody)
            applyAuthHeaders(requestBuilder)

            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext InnertubeArtistPage("", emptyList(), emptyList(), emptyList())
                }
                val bodyString = response.body?.string()
                    ?: return@withContext InnertubeArtistPage("", emptyList(), emptyList(), emptyList())

                parseArtistPage(JSONObject(bodyString))
            }
        }
    }

    private suspend fun searchInternal(
        query: String,
        filter: MusicSearchFilter,
        limitSongs: Int,
        limitAlbums: Int,
    ): InnertubeSearchResults {
        if (query.isBlank() || (limitSongs <= 0 && limitAlbums <= 0)) {
            return InnertubeSearchResults(emptyList(), emptyList())
        }

        return withContext(Dispatchers.IO) {
            val bodyJson = buildSearchRequestBody(query, filter)
            val requestBody = bodyJson.toString().toRequestBody(jsonMediaType)

            val requestBuilder = Request.Builder()
                .url(baseUrl)
                .post(requestBody)

            applyAuthHeaders(requestBuilder)

            val request = requestBuilder.build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext InnertubeSearchResults(emptyList(), emptyList())
                val bodyString = response.body?.string() ?: return@withContext InnertubeSearchResults(emptyList(), emptyList())

                parseSearchResults(JSONObject(bodyString), limitSongs, limitAlbums)
            }
        }
    }

    private fun buildSearchRequestBody(
        query: String,
        filter: MusicSearchFilter,
    ): JSONObject {
        val client = JSONObject().apply {
            put("clientName", "WEB_REMIX")
            put("clientVersion", "1.20250101.01.00")
            put("hl", "en")
            put("gl", "US")
        }

        val context = JSONObject().apply {
            put("client", client)
        }

        val params = when (filter) {
            MusicSearchFilter.SONGS -> PARAMS_SONGS
            MusicSearchFilter.ALBUMS -> PARAMS_ALBUMS
            MusicSearchFilter.ARTISTS -> PARAMS_ARTISTS
            MusicSearchFilter.NONE -> null
        }

        return JSONObject().apply {
            put("context", context)
            put("query", query)
            if (!params.isNullOrBlank()) {
                put("params", params)
            }
        }
    }

    private fun findSearchSectionList(root: JSONObject): JSONObject? {
        val contentsRoot = root.optJSONObject("contents") ?: return null

        val directSectionList = contentsRoot.optJSONObject("sectionListRenderer")
        if (directSectionList != null) return directSectionList

        val tabbed = contentsRoot.optJSONObject("tabbedSearchResultsRenderer") ?: return null
        val tabs = tabbed.optJSONArray("tabs") ?: return null

        var found: JSONObject? = null
        for (i in 0 until tabs.length()) {
            val tab = tabs.optJSONObject(i) ?: continue
            val tabRenderer = tab.optJSONObject("tabRenderer") ?: continue
            val selected = tabRenderer.optBoolean("selected", false)
            val content = tabRenderer.optJSONObject("content") ?: continue
            val candidate = content.optJSONObject("sectionListRenderer")
            if (candidate != null && (selected || found == null)) {
                found = candidate
                if (selected) break
            }
        }
        return found
    }

    private fun parseArtistSearchResults(root: JSONObject, limit: Int): List<InnertubeArtistResult> {
        val artists = mutableListOf<InnertubeArtistResult>()
        val sectionList = findSearchSectionList(root) ?: return emptyList()
        val contents = sectionList.optJSONArray("contents") ?: return emptyList()

        for (i in 0 until contents.length()) {
            val section = contents.optJSONObject(i)
                ?.optJSONObject("musicShelfRenderer") ?: continue
            val items = section.optJSONArray("contents") ?: continue

            for (j in 0 until items.length()) {
                val item = items.optJSONObject(j)
                    ?.optJSONObject("musicResponsiveListItemRenderer") ?: continue

                val artist = parseArtistItem(item)
                if (artist != null) {
                    artists += artist
                }
                if (artists.size >= limit) return artists
            }
        }

        return artists
    }

    private fun parseArtistItem(item: JSONObject): InnertubeArtistResult? {
        val flexColumns = item.optJSONArray("flexColumns") ?: return null
        if (flexColumns.length() == 0) return null

        val mainColumn = flexColumns.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?: return null

        val titleRuns = mainColumn.optJSONObject("text")?.optJSONArray("runs")
        if (titleRuns == null || titleRuns.length() == 0) return null

        val titleRun = titleRuns.optJSONObject(0)
        val name = titleRun?.optString("text").orEmpty()
        if (name.isBlank()) return null

        val browseId = titleRun
            ?.optJSONObject("navigationEndpoint")
            ?.optJSONObject("browseEndpoint")
            ?.optString("browseId")
            .takeUnless { it.isNullOrBlank() }
            ?: item.optJSONObject("navigationEndpoint")
                ?.optJSONObject("browseEndpoint")
                ?.optString("browseId")
                .takeUnless { it.isNullOrBlank() }
            ?: return null

        return InnertubeArtistResult(browseId = browseId, name = name)
    }

    private fun parseArtistPage(root: JSONObject): InnertubeArtistPage {
        val header = root.optJSONObject("header")
        val name = header?.optJSONObject("musicImmersiveHeaderRenderer")
            ?.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
            ?: header?.optJSONObject("musicVisualHeaderRenderer")
                ?.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
            ?: header?.optJSONObject("musicHeaderRenderer")
                ?.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
            ?: ""

        val songs = mutableListOf<InnertubeSongResult>()
        val albums = mutableListOf<InnertubeAlbumResult>()
        val singles = mutableListOf<InnertubeAlbumResult>()

        val tabs = root.optJSONObject("contents")
            ?.optJSONObject("singleColumnBrowseResultsRenderer")
            ?.optJSONArray("tabs")

        val sectionListContents = tabs?.optJSONObject(0)
            ?.optJSONObject("tabRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents")
            ?: return InnertubeArtistPage(name, emptyList(), emptyList(), emptyList())

        for (i in 0 until sectionListContents.length()) {
            val section = sectionListContents.optJSONObject(i) ?: continue

            val musicShelf = section.optJSONObject("musicShelfRenderer")
            if (musicShelf != null) {
                val title = musicShelf.optJSONObject("title")
                    ?.optJSONArray("runs")?.optJSONObject(0)?.optString("text").orEmpty()
                val items = musicShelf.optJSONArray("contents") ?: continue

                if (title.contains("song", ignoreCase = true)) {
                    for (j in 0 until items.length()) {
                        val item = items.optJSONObject(j)
                            ?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                        parseSongItem(item)?.let { songs += it.copy(artist = name.ifBlank { it.artist }) }
                    }
                }
                continue
            }

            val carousel = section.optJSONObject("musicCarouselShelfRenderer") ?: continue
            val carouselTitle = carousel.optJSONObject("header")
                ?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
                ?.optJSONObject("title")
                ?.optJSONArray("runs")?.optJSONObject(0)?.optString("text").orEmpty()
            val carouselItems = carousel.optJSONArray("contents") ?: continue

            val bucket = when {
                carouselTitle.contains("single", ignoreCase = true) ||
                    carouselTitle.contains("new release", ignoreCase = true) -> singles
                carouselTitle.contains("album", ignoreCase = true) -> albums
                else -> null
            } ?: continue

            for (j in 0 until carouselItems.length()) {
                val twoRow = carouselItems.optJSONObject(j)
                    ?.optJSONObject("musicTwoRowItemRenderer") ?: continue
                parseArtistAlbumItem(twoRow)?.let { bucket += it.copy(artist = name.ifBlank { null }) }
            }
        }

        return InnertubeArtistPage(name, songs, albums, singles)
    }

    private fun parseArtistAlbumItem(renderer: JSONObject): InnertubeAlbumResult? {
        val titleRuns = renderer.optJSONObject("title")?.optJSONArray("runs")
        val title = titleRuns?.optJSONObject(0)?.optString("text").orEmpty()
        if (title.isBlank()) return null

        val browseId = renderer.optJSONObject("navigationEndpoint")
            ?.optJSONObject("browseEndpoint")
            ?.optString("browseId")
            .takeUnless { it.isNullOrBlank() }
            ?: return null

        val subtitleRuns = renderer.optJSONObject("subtitle")?.optJSONArray("runs")
        var year: Int? = null
        if (subtitleRuns != null) {
            for (k in 0 until subtitleRuns.length()) {
                val text = subtitleRuns.optJSONObject(k)?.optString("text").orEmpty().trim()
                val maybeYear = text.toIntOrNull()
                if (maybeYear != null && maybeYear in 1900..2100) {
                    year = maybeYear
                    break
                }
            }
        }

        return InnertubeAlbumResult(
            albumId = browseId,
            title = title,
            artist = null,
            year = year,
        )
    }

    private fun parseSearchResults(
        root: JSONObject,
        limitSongs: Int,
        limitAlbums: Int,
    ): InnertubeSearchResults {
        val songs = mutableListOf<InnertubeSongResult>()
        val albums = mutableListOf<InnertubeAlbumResult>()

        val sectionList = findSearchSectionList(root)
            ?: return InnertubeSearchResults(emptyList(), emptyList())

        val contents = sectionList.optJSONArray("contents")
            ?: return InnertubeSearchResults(emptyList(), emptyList())

        for (i in 0 until contents.length()) {
            val section = contents.optJSONObject(i)
                ?.optJSONObject("musicShelfRenderer") ?: continue

            val items = section.optJSONArray("contents") ?: continue

            for (j in 0 until items.length()) {
                val item = items.optJSONObject(j)
                    ?.optJSONObject("musicResponsiveListItemRenderer") ?: continue

                if (songs.size < limitSongs) {
                    val song = parseSongItem(item)
                    if (song != null) {
                        songs += song
                    }
                }

                if (albums.size < limitAlbums) {
                    val album = parseAlbumItem(item)
                    if (album != null) {
                        albums += album
                    }
                }

                if (songs.size >= limitSongs && albums.size >= limitAlbums) {
                    break
                }
            }

            if (songs.size >= limitSongs && albums.size >= limitAlbums) {
                break
            }
        }

        return InnertubeSearchResults(songs, albums)
    }

    override suspend fun getBestAudioUrl(videoId: String): String = withContext(Dispatchers.IO) {
        val visitorData = visitorData(forceRefresh = false)
            ?: error("Could not obtain a visitorData token")

        try {
            requestBestAudioUrl(videoId, visitorData)
        } catch (first: Exception) {
            // A stale visitorData shows up as LOGIN_REQUIRED. Harvest a fresh
            // one and try once more before falling through to the NewPipe tier.
            val refreshed = visitorData(forceRefresh = true)
            if (refreshed == null || refreshed == visitorData) throw first
            requestBestAudioUrl(videoId, refreshed)
        }
    }

    private fun requestBestAudioUrl(videoId: String, visitorData: String): String {
        val bodyJson = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "VISIONOS")
                    put("clientVersion", visionOsClientVersion)
                    put("deviceMake", "Apple")
                    put("deviceModel", visionOsDeviceModel)
                    put("userAgent", visionOsUserAgent)
                    put("osName", "visionOS")
                    put("osVersion", visionOsVersion)
                    put("visitorData", visitorData)
                    put("hl", "en")
                    put("gl", "US")
                })
            })
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }

        // Deliberately anonymous -- applyAuthHeaders() is NOT called here.
        // Sending the signed-in music.youtube.com cookie/SAPISIDHASH alongside
        // this client context makes YouTube answer LOGIN_REQUIRED and return no
        // streams at all. Search still uses the authenticated path.
        val request = Request.Builder()
            .url(playerUrl)
            .header("User-Agent", visionOsUserAgent)
            .header("X-Goog-Visitor-Id", visitorData)
            .post(bodyJson.toString().toRequestBody(jsonMediaType))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Innertube player request failed: ${response.code}")
            }
            val bodyString = response.body?.string() ?: error("Empty Innertube player body")
            val root = JSONObject(bodyString)

            val status = root.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
            if (status.isNotBlank() && status != "OK") {
                val reason = root.optJSONObject("playabilityStatus")?.optString("reason").orEmpty()
                error("Innertube player status $status${if (reason.isBlank()) "" else ": $reason"}")
            }

            val formats = root.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats")
                ?: error("No adaptiveFormats in Innertube player response")

            var bestAudio: JSONObject? = null
            var bestBitrate = -1
            for (i in 0 until formats.length()) {
                val format = formats.optJSONObject(i) ?: continue
                if (!format.optString("mimeType").startsWith("audio")) continue

                // A format with no direct `url` is signature-ciphered and would
                // need the player JS to decode. Skip it rather than hand
                // ExoPlayer a URI it cannot fetch (which is what produced the
                // "stuck at 0:00" symptom).
                val url = format.optString("url")
                if (url.isBlank()) continue

                val bitrate = format.optInt("bitrate", 0)
                if (bitrate > bestBitrate) {
                    bestBitrate = bitrate
                    bestAudio = format
                }
            }

            return bestAudio?.optString("url")?.takeIf { it.isNotBlank() }
                ?: error("No playable audio stream in Innertube player response")
        }
    }

    /**
     * Returns a visitorData token, harvesting one from the YouTube home page
     * if there is none cached (or if [forceRefresh] is set).
     */
    private fun visitorData(forceRefresh: Boolean): String? {
        if (!forceRefresh) cachedVisitorData?.let { return it }
        val fresh = fetchVisitorData()
        if (fresh != null) cachedVisitorData = fresh
        return fresh ?: cachedVisitorData
    }

    private fun fetchVisitorData(): String? {
        return try {
            val request = Request.Builder()
                .url("https://www.youtube.com/")
                .header("User-Agent", visionOsUserAgent)
                .header("Accept-Language", "en-US,en;q=0.9")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val html = response.body?.string() ?: return null
                val raw = VISITOR_DATA_REGEX.find(html)?.groupValues?.getOrNull(1) ?: return null
                // `raw` is still a JSON string body (it can carry \u003d and the
                // like). Let JSONObject do the unescaping rather than hand-rolling it.
                JSONObject("{\"v\":\"$raw\"}").optString("v").takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseSongItem(item: JSONObject): InnertubeSongResult? {
        val flexColumns = item.optJSONArray("flexColumns") ?: return null
        if (flexColumns.length() == 0) return null

        val mainColumn = flexColumns.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?: return null

        val text = mainColumn.optJSONObject("text")
        val titleRuns = text?.optJSONArray("runs")
        if (titleRuns == null || titleRuns.length() == 0) return null

        val titleRun = titleRuns.optJSONObject(0)
        val title = titleRun?.optString("text").orEmpty()
        if (title.isBlank()) return null

        val videoId = titleRun
            ?.optJSONObject("navigationEndpoint")
            ?.optJSONObject("watchEndpoint")
            ?.optString("videoId")
            .takeUnless { it.isNullOrBlank() }
            ?: item.optJSONObject("playlistItemData")
                ?.optString("videoId")
                .takeUnless { it.isNullOrBlank() }
            ?: return null

        var artist: String? = null
        var album: String? = null
        var durationText: String? = null

        for (i in 1 until flexColumns.length()) {
            val subtitleColumn = flexColumns.optJSONObject(i)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?: continue

            val subtitleText = subtitleColumn.optJSONObject("text") ?: continue
            val subtitleRuns = subtitleText.optJSONArray("runs") ?: continue

            for (k in 0 until subtitleRuns.length()) {
                val run = subtitleRuns.optJSONObject(k) ?: continue
                val textValue = run.optString("text").orEmpty().trim()
                if (textValue.isEmpty()) continue

                val navEndpoint = run.optJSONObject("navigationEndpoint")
                val browseEndpoint = navEndpoint?.optJSONObject("browseEndpoint")
                val browseId = browseEndpoint?.optString("browseId")

                when {
                    artist == null && browseId != null && browseId.startsWith("UC") -> {
                        artist = textValue
                    }
                    album == null && browseId != null && browseId.startsWith("MPRE") -> {
                        album = textValue
                    }
                    artist == null && browseId.isNullOrBlank() -> {
                        artist = textValue
                    }
                }

                if (durationText == null && textValue.contains(":")) {
                    durationText = textValue
                }
            }
        }

        val safeArtist = artist?.takeIf { it.isNotBlank() } ?: "Unknown artist"
        val durationMillis = durationText?.let { parseDurationToMillis(it) }

        return InnertubeSongResult(
            videoId = videoId,
            title = title,
            artist = safeArtist,
            album = album,
            durationMillis = durationMillis,
        )
    }

    private fun parseAlbumItem(item: JSONObject): InnertubeAlbumResult? {
        val flexColumns = item.optJSONArray("flexColumns") ?: return null
        if (flexColumns.length() == 0) return null

        val mainColumn = flexColumns.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?: return null

        val text = mainColumn.optJSONObject("text")
        val titleRuns = text?.optJSONArray("runs")
        if (titleRuns == null || titleRuns.length() == 0) return null

        val titleRun = titleRuns.optJSONObject(0)
        val title = titleRun?.optString("text").orEmpty()
        if (title.isBlank()) return null

        val topNavEndpoint = item.optJSONObject("navigationEndpoint")
        val topBrowseEndpoint = topNavEndpoint?.optJSONObject("browseEndpoint")

        val runBrowseEndpoint = titleRun
            ?.optJSONObject("navigationEndpoint")
            ?.optJSONObject("browseEndpoint")

        val albumId = (topBrowseEndpoint?.optString("browseId")
            ?: runBrowseEndpoint?.optString("browseId"))
            .takeUnless { it.isNullOrBlank() }
            ?: return null

        var artist: String? = null
        var year: Int? = null

        for (i in 1 until flexColumns.length()) {
            val subtitleColumn = flexColumns.optJSONObject(i)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?: continue

            val subtitleText = subtitleColumn.optJSONObject("text") ?: continue
            val subtitleRuns = subtitleText.optJSONArray("runs") ?: continue

            for (k in 0 until subtitleRuns.length()) {
                val run = subtitleRuns.optJSONObject(k) ?: continue
                val textValue = run.optString("text").orEmpty().trim()
                if (textValue.isEmpty() || textValue == "•") continue

                val navEndpoint = run.optJSONObject("navigationEndpoint")
                val subBrowseEndpoint = navEndpoint?.optJSONObject("browseEndpoint")
                val subBrowseId = subBrowseEndpoint?.optString("browseId")

                val maybeYear = textValue.toIntOrNull()
                if (maybeYear != null && maybeYear in 1900..2100) {
                    if (year == null) year = maybeYear
                    continue
                }

                if (textValue.equals("Album", ignoreCase = true) ||
                    textValue.equals("Single", ignoreCase = true) ||
                    textValue.equals("EP", ignoreCase = true)) {
                    continue
                }

                if (subBrowseId != null && subBrowseId.startsWith("UC")) {
                    artist = textValue
                } else if (artist == null) {
                    artist = textValue
                }
            }
        }

        return InnertubeAlbumResult(
            albumId = albumId,
            title = title,
            artist = artist,
            year = year,
        )
    }

    private fun parseDurationToMillis(text: String): Long? {
        val parts = text.trim().split(":")
        if (parts.size < 2) return null
        val numbers = parts.mapNotNull { it.toIntOrNull() }
        if (numbers.size != parts.size) return null

        val seconds = when (numbers.size) {
            2 -> numbers[0] * 60 + numbers[1]
            3 -> numbers[0] * 3600 + numbers[1] * 60 + numbers[2]
            else -> return null
        }
        return (seconds * 1000L).coerceAtLeast(0L)
    }
}