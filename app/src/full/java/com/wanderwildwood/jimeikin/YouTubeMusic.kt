package com.wanderwildwood.jimeikin

import android.content.Context
import androidx.core.content.edit
import com.wanderwildwood.jimeikin.data.DownloadUpgrade
import com.wanderwildwood.jimeikin.data.Secrets
import com.wanderwildwood.jimeikin.data.SongEntity
import com.wanderwildwood.jimeikin.data.YouTubeAudioFile
import com.wanderwildwood.jimeikin.ui.AlbumUiModel
import com.wanderwildwood.jimeikin.ui.OnlineArtistUiModel
import com.wanderwildwood.jimeikin.ui.SongUiModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** The full edition's catalogue: YouTube Music, through InnerTube with NewPipe behind it. */
fun createOnlineMusic(app: CalmMusic): OnlineMusic = YouTubeMusic(app)

/** The YouTube parts, for the code in this edition that reaches them directly. */
private val CalmMusic.youTube: YouTubeMusic get() = online as YouTubeMusic
val CalmMusic.youTubeInnertubeClient: YouTubeMusicInnertubeClient get() = youTube.innertube
val CalmMusic.youTubeStreamResolver: YouTubeStreamResolver get() = youTube.streamResolver
val CalmMusic.youTubeCopies: YouTubeCopies get() = youTube.copies
val CalmMusic.youTubeAccount: YouTubeMusic.Account get() = youTube.account

class YouTubeMusic(private val app: CalmMusic) : OnlineMusic {

    // The same settings file, and the same keys, as before the editions were split.
    private val prefs = app.getSharedPreferences("calmmusic_settings", Context.MODE_PRIVATE)

    /** The YouTube account signed in on the settings page, if any; its cookie is sealed like a password. */
    inner class Account {
        private val _cookie = MutableStateFlow(load())
        val cookie: StateFlow<String?> = _cookie.asStateFlow()

        private fun load(): String? {
            val stored = prefs.getString(KEY_YOUTUBE_ACCOUNT_COOKIE, null) ?: return null
            val cookie = Secrets.open(stored) ?: return null
            if (!Secrets.isSealed(stored)) {
                Secrets.seal(cookie)?.let { sealed -> prefs.edit { putString(KEY_YOUTUBE_ACCOUNT_COOKIE, sealed) } }
            }
            return cookie
        }

        fun set(cookie: String) {
            prefs.edit { putString(KEY_YOUTUBE_ACCOUNT_COOKIE, Secrets.seal(cookie)) }
            _cookie.value = cookie
        }

        fun clear() {
            prefs.edit { remove(KEY_YOUTUBE_ACCOUNT_COOKIE) }
            _cookie.value = null
        }
    }

    val account = Account()

    val innertube: YouTubeMusicInnertubeClient by lazy {
        YouTubeMusicInnertubeClientImpl(OkHttpClient.Builder().build()) { account.cookie.value }
    }

    val streamResolver: YouTubeStreamResolver by lazy { YouTubeStreamResolver() }

    private val precache: YouTubePrecacheManager by lazy { YouTubePrecacheManager(app) }

    /** Which YouTube songs are already downloaded, and as which file. */
    val copies: YouTubeCopies by lazy { YouTubeCopies(File(app.filesDir, "youtube-downloads.txt")) }

    override val available: Boolean = true

    override val recorded: StateFlow<Map<String, String>> get() = copies.recorded
    override fun recordCopy(onlineId: String, songId: String) = copies.record(onlineId, songId)
    override fun renameCopy(oldSongId: String, newSongId: String) = copies.rename(oldSongId, newSongId)
    override fun forgetCopy(songId: String) = copies.forget(songId)
    override fun copyOf(song: SongUiModel, downloads: List<SongUiModel>): SongUiModel? = copies.copyOf(song, downloads)
    override fun withCopies(songs: List<SongUiModel>, downloads: List<SongUiModel>): List<SongUiModel> =
        copies.withCopies(songs, downloads)
    override fun leftToDownload(songs: List<SongUiModel>, downloads: List<SongUiModel>, isUnderway: (String) -> Boolean) =
        copies.leftToDownload(songs, downloads, isUnderway)
    override fun copyIn(song: SongUiModel, recorded: Map<String, String>, downloads: List<SongUiModel>): SongUiModel? =
        YouTubeCopyMatch.copyOf(song, recorded, downloads)
    override fun withCopiesIn(songs: List<SongUiModel>, recorded: Map<String, String>, downloads: List<SongUiModel>): List<SongUiModel> =
        YouTubeCopyMatch.withCopies(songs, recorded, downloads)
    override fun inAlbumOrder(songs: List<SongUiModel>): List<SongUiModel> = YouTubeCopyMatch.inAlbumOrder(songs)
    override fun idInComment(comment: String?): String? = YouTubeAudioFile.videoIdOf(comment)

    override suspend fun search(query: String): OnlineSearch {
        val songResults = innertube.searchSongs(query = query, limit = 25)
        val albumResults = innertube.searchAlbums(query = query, limit = 25)
        val artistResults = innertube.searchArtists(query = query, limit = 25)
        return OnlineSearch(
            songs = songResults.map { it.toSong(trackNumber = null) },
            albums = albumResults.map { it.toAlbum(fallbackArtist = null) },
            artists = artistResults.map { OnlineArtistUiModel(browseId = it.browseId, name = it.name) },
        )
    }

    // Each album's listing, as first fetched. A page is drawn again whenever the library
    // changes - each time a download lands - and fetching the listing afresh each time could
    // bring it back in another order, or with a track more or less.
    private val albumListings = ConcurrentHashMap<String, List<SongUiModel>>()

    override suspend fun albumSongs(album: AlbumUiModel): List<SongUiModel> {
        val cacheKey = listOf(album.id, YouTubeCopyMatch.key(album.title), YouTubeCopyMatch.key(album.artist.orEmpty()))
            .joinToString("|")
        albumListings[cacheKey]?.let { return it }
        val songs = albumTracks(album).ifEmpty { searchAlbumSongs(album) }
        if (songs.isNotEmpty()) albumListings[cacheKey] = songs
        return songs
    }

    /** The album's own page on YouTube Music, when it came from there and so has its id. */
    private suspend fun albumTracks(album: AlbumUiModel): List<SongUiModel> {
        if (!album.id.startsWith("MPRE")) return emptyList()
        val tracks = withContext(Dispatchers.IO) {
            runCatching { innertube.getAlbumTracks(album.id) }.getOrDefault(emptyList())
        }
        return tracks.mapIndexed { index, track ->
            SongUiModel(
                id = track.videoId,
                title = track.title,
                artist = track.artist ?: album.artist.orEmpty(),
                durationText = formatDurationMillis(track.durationMillis),
                durationMillis = track.durationMillis,
                trackNumber = track.trackNumber ?: (index + 1),
                discNumber = 1,
                sourceType = "YOUTUBE",
                audioUri = track.videoId,
                album = album.title,
            )
        }
    }

    private suspend fun searchAlbumSongs(album: AlbumUiModel): List<SongUiModel> {
        return withContext(Dispatchers.IO) {
            val termBuilder = StringBuilder().apply {
                append(album.title)
                val artist = album.artist
                if (!artist.isNullOrBlank()) {
                    append(' ')
                    append(artist)
                }
            }

            val results = innertube.searchSongs(
                query = termBuilder.toString(),
                limit = 50,
            )

            val targetAlbumName = album.title.trim()

            val filtered = results.filter { result ->
                val resultAlbum = result.album?.trim().orEmpty()
                if (resultAlbum.isEmpty()) return@filter false

                resultAlbum.equals(targetAlbumName, ignoreCase = true) ||
                        resultAlbum.contains(targetAlbumName, ignoreCase = true) ||
                        targetAlbumName.contains(resultAlbum, ignoreCase = true)
            }

            val songsForAlbum = if (filtered.isNotEmpty()) filtered else results

            songsForAlbum.mapIndexed { index, item ->
                SongUiModel(
                    id = item.videoId,
                    title = item.title,
                    artist = item.artist,
                    durationText = formatDurationMillis(item.durationMillis),
                    durationMillis = item.durationMillis,
                    trackNumber = index + 1,
                    discNumber = 1,
                    sourceType = "YOUTUBE",
                    audioUri = item.videoId,
                    album = album.title,
                )
            }
        }
    }

    override suspend fun findArtist(name: String): OnlineArtistUiModel? {
        val key = com.wanderwildwood.jimeikin.data.ArtistNames.key(name)
        return runCatching { innertube.searchArtists(name, limit = 5) }
            .getOrDefault(emptyList())
            .firstOrNull { com.wanderwildwood.jimeikin.data.ArtistNames.key(it.name) == key }
            ?.let { OnlineArtistUiModel(browseId = it.browseId, name = it.name) }
    }

    override suspend fun artistPage(browseId: String, downloads: List<SongUiModel>): OnlineArtistPage {
        return withContext(Dispatchers.IO) {
            val page = innertube.getArtistPage(browseId)
            val songs = page.songs.mapIndexed { index, item -> item.toSong(trackNumber = index + 1, discNumber = 1) }
            OnlineArtistPage(
                name = page.name,
                // The ones already downloaded as their files, so their menus offer to remove
                // the download rather than to fetch it again.
                songs = copies.withCopies(songs, downloads),
                albums = page.albums.map { it.toAlbum(fallbackArtist = page.name) },
                singles = page.singles.map { it.toAlbum(fallbackArtist = page.name) },
            )
        }
    }

    override fun similarSeed(seed: SongUiModel): String? = SimilarSongs.seedVideoId(seed, copies.recorded.value)

    override suspend fun similar(seedId: String, queue: () -> List<SongUiModel>, count: Int): List<SongUiModel> {
        val found = innertube.getUpNext(seedId)
        return SimilarSongs.pick(found, queue(), copies.recorded.value, seedId, count).map { it.toSong(trackNumber = null) }
    }

    override fun precacheSearch(ids: List<String>) = precache.precacheSearchResults(ids)
    override fun clearSearchWindow() = precache.clearSearchWindow()
    override fun updateQueueWindow(ids: List<String>) = precache.updateQueueWindow(ids)

    override val streamHeaders: Map<String, String> = mapOf(
        "Cookie" to BYPASS_COOKIES,
        "Referer" to "https://www.youtube.com/",
    )

    override fun isCatalogueHost(host: String): Boolean =
        host.endsWith("youtube.com") || host.endsWith("youtu.be") || host.endsWith("googlevideo.com")

    override fun playAddress(id: String): String = "https://www.youtube.com/watch?v=$id"

    override fun cacheKey(id: String): String = id

    override fun unplayableMessage(errorClassName: String, message: String): Int? {
        val isNewPipeContentNotAvailable =
            errorClassName == "org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException" ||
                message.contains("page needs to be reloaded", ignoreCase = true) ||
                message.contains("ContentNotAvailable", ignoreCase = true)
        return if (isNewPipeContentNotAvailable) R.string.main_youtube_cannot_play else null
    }

    override fun resolveStream(id: String): Pair<String, String?> {
        val now = System.currentTimeMillis()
        precache.getCachedWithLabel(id, now)?.let { return it }
        val (resolvedUrl, resolverLabel) = runBlocking(Dispatchers.IO) {
            try {
                innertube.getBestAudioUrl(id) to "Innertube"
            } catch (_: Exception) {
                streamResolver.getBestAudioUrl(id) to "NewPipe"
            }
        }
        precache.putUrl(id, resolvedUrl, resolverLabel, now)
        return resolvedUrl to resolverLabel
    }

    override suspend fun download(item: DownloadItem, targetDir: File, client: OkHttpClient, onProgress: (Float) -> Unit): Boolean {
        val song = SongUiModel(
            id = item.songId,
            title = item.title,
            artist = item.artist,
            durationMillis = item.durationMillis,
            discNumber = item.discNumber,
            trackNumber = item.trackNumber,
            sourceType = "YOUTUBE",
            audioUri = item.audioUri,
            album = item.album,
        )
        return performYouTubeDownloadInternal(
            app = app,
            song = song,
            albumArtist = item.albumArtist,
            targetDir = targetDir,
            context = app,
            client = client,
            onProgress = onProgress,
        )
    }

    override suspend fun upgradeCandidates(inUse: () -> Set<String>): List<SongEntity> = DownloadUpgrade.candidates(app, inUse)

    override suspend fun upgrade(
        songId: String,
        client: OkHttpClient,
        inUse: () -> Set<String>,
        onProgress: (Float) -> Unit,
    ): Pair<DownloadState, String?> =
        when (val result = DownloadUpgrade.upgrade(app, songId, client, inUse, onProgress)) {
            DownloadUpgrade.Result.Done -> DownloadState.DOWNLOADED to null
            is DownloadUpgrade.Result.Failed -> DownloadState.FAILED to result.reason
            DownloadUpgrade.Result.Later -> DownloadState.NOT_FINISHED to null
        }

    private val _completeAlbums = MutableStateFlow(prefs.getBoolean(KEY_COMPLETE_ALBUMS_WITH_YOUTUBE, false))
    override val completeAlbums: StateFlow<Boolean> = _completeAlbums.asStateFlow()
    override fun setCompleteAlbums(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_COMPLETE_ALBUMS_WITH_YOUTUBE, enabled) }
        _completeAlbums.value = enabled
    }

    /** Off unless chosen: when a YouTube song is the last in the queue, add what YouTube plays after it. */
    private val _keepPlayingSimilar = MutableStateFlow(prefs.getBoolean(KEY_KEEP_PLAYING_SIMILAR, false))
    override val keepPlayingSimilar: StateFlow<Boolean> = _keepPlayingSimilar.asStateFlow()
    override fun setKeepPlayingSimilar(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_KEEP_PLAYING_SIMILAR, enabled) }
        _keepPlayingSimilar.value = enabled
    }

    private fun InnertubeSongResult.toSong(trackNumber: Int?, discNumber: Int? = null) = SongUiModel(
        id = videoId,
        title = title,
        artist = artist,
        durationText = formatDurationMillis(durationMillis),
        durationMillis = durationMillis,
        trackNumber = trackNumber,
        discNumber = discNumber,
        sourceType = "YOUTUBE",
        audioUri = videoId,
        album = album,
    )

    private fun InnertubeAlbumResult.toAlbum(fallbackArtist: String?) = AlbumUiModel(
        id = albumId,
        title = title,
        artist = artist ?: fallbackArtist,
        sourceType = "YOUTUBE",
        releaseYear = year,
    )

    private companion object {
        const val KEY_COMPLETE_ALBUMS_WITH_YOUTUBE = "complete_albums_with_youtube"
        const val KEY_KEEP_PLAYING_SIMILAR = "keep_playing_similar"
        const val KEY_YOUTUBE_ACCOUNT_COOKIE = "youtube_account_cookie"
        const val BYPASS_COOKIES = "SOCS=CAI; VISITOR_INFO1_LIVE=i7Sm6Qgj0lE; CONSENT=YES+cb.20210328-17-p0.en+FX+475"
    }
}
