package com.wanderwildwood.jimeikin

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.annotation.OptIn
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import com.wanderwildwood.jimeikin.data.TagEditor
import com.wanderwildwood.jimeikin.data.AlbumEntity
import com.wanderwildwood.jimeikin.data.ArtistEntity
import com.wanderwildwood.jimeikin.data.ArtistWithCounts
import com.wanderwildwood.jimeikin.data.CalmMusicDatabase
import com.wanderwildwood.jimeikin.data.dropOrphanedOnlineRows
import com.wanderwildwood.jimeikin.data.CalmMusicSettingsManager
import com.wanderwildwood.jimeikin.data.DownloadMove
import com.wanderwildwood.jimeikin.data.DownloadReplace
import com.wanderwildwood.jimeikin.data.PublicDownloads
import com.wanderwildwood.jimeikin.data.LibraryRepository
import com.wanderwildwood.jimeikin.data.NowPlayingSnapshot
import com.wanderwildwood.jimeikin.data.NowPlayingStorage
import com.wanderwildwood.jimeikin.data.NowPlayingRepeatModeKeys
import com.wanderwildwood.jimeikin.data.SongEntity
import com.wanderwildwood.jimeikin.playback.PlaybackCoordinator
import com.wanderwildwood.jimeikin.ui.originOf
import com.wanderwildwood.jimeikin.ui.AlbumUiModel
import com.wanderwildwood.jimeikin.ui.ArtistUiModel
import com.wanderwildwood.jimeikin.ui.PlaylistUiModel
import com.wanderwildwood.jimeikin.ui.RepeatMode
import com.wanderwildwood.jimeikin.ui.SongUiModel
import com.wanderwildwood.jimeikin.ui.OnlineArtistUiModel
import com.wanderwildwood.jimeikin.data.ArtistAlbumsFill
import com.wanderwildwood.jimeikin.data.ArtistNames
import kotlinx.coroutines.CancellationException
import com.wanderwildwood.jimeikin.data.NameOverrideEntity
import com.wanderwildwood.jimeikin.data.NameOverrides
import com.wanderwildwood.jimeikin.data.NamedAlbumDao
import com.wanderwildwood.jimeikin.data.NamedArtistDao
import com.wanderwildwood.jimeikin.data.NamedSongDao
import com.wanderwildwood.jimeikin.data.SubsonicAlbum
import com.wanderwildwood.jimeikin.data.SubsonicClient
import com.wanderwildwood.jimeikin.data.SubsonicDownloader
import com.wanderwildwood.jimeikin.data.SubsonicResult
import com.wanderwildwood.jimeikin.data.SubsonicSong
import com.wanderwildwood.jimeikin.data.serverClient
import com.wanderwildwood.jimeikin.data.SubsonicSync
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Whether the player can be handed this song's uri and left to get on with it.
 *
 * Files on the card, downloads, songs streamed from a music server and songs kept from one
 * all go through media3 the same way — a uri is a uri, whether it names a file or a server.
 * Only YouTube is different, because its stream has to be resolved first.
 *
 * This used to be spelled out at six separate call sites, and adding a source meant finding
 * all six. Two were missed, which is why a server song reached the queue and then sat on
 * "Loading" for ever: nothing routed it to a player.
 *
 * The name is about where playback happens, not about whether a network is needed: a song on a
 * music server needs one, and a radio stream needs one and never ends. What they have in common
 * is that the address is already known, so none of them wants the resolve step a YouTube track
 * does. A radio stream was missing from this list and so was never handed to the player at all
 * - the screen said Loading and nothing ever came.
 */
private fun playsOnThisPhone(sourceType: String?): Boolean =
    sourceType == "LOCAL_FILE" ||
        sourceType == "YOUTUBE_DOWNLOAD" ||
        sourceType == "SUBSONIC" ||
        sourceType == "SUBSONIC_DOWNLOAD" ||
        sourceType == "RADIO"

/**
 * How far into a song "previous" stops meaning the song before it and starts meaning this
 * one from the top. Three seconds is what every other player uses, and the number matters
 * less than having one: without it there was no way to hear a song again except back and
 * then forward, and on a queue of one there was no way at all.
 */
private const val RESTART_RATHER_THAN_PREVIOUS_MS = 3_000L

/**
 * An artist page asked of the music server rather than read from the library; see
 * [CalmMusicViewModel.serverArtistPageFor]. Never a row's id, so it cannot meet one.
 */
private const val SERVER_ARTIST_PREFIX = "SUBSONIC:SERVER_ARTIST:"

/** How many similar songs go on the end at a time; the last of them asks for more. */
private const val SIMILAR_BATCH = 20

class CalmMusicViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private val app: CalmMusic
        @OptIn(UnstableApi::class)
        get() = getApplication() as CalmMusic

    private val database: CalmMusicDatabase by lazy { CalmMusicDatabase.getDatabase(app) }
    // Read through the names the reader gave things; see NamedSongDao. The plain DAOs are for
    // reading a row in order to write it back.
    private val rawSongDao by lazy { database.songDao() }
    private val rawArtistDao by lazy { database.artistDao() }
    private val songDao by lazy { NamedSongDao(rawSongDao, app.nameOverrides) }
    private val albumDao by lazy { NamedAlbumDao(database.albumDao(), app.nameOverrides) }
    private val artistDao by lazy { NamedArtistDao(rawArtistDao, app.nameOverrides) }
    private val playlistDao by lazy { database.playlistDao() }
    private val libraryRepository: LibraryRepository by lazy { LibraryRepository(app) }
    private val nowPlayingStorage: NowPlayingStorage by lazy { app.nowPlayingStorage }

    private val playbackCoordinator = PlaybackCoordinator { app.settingsManager.getSubsonicMaxBitrateSync() }
    private var localPlaybackMonitorJob: Job? = null

    private var lastCompletedSongId: String? = null

    /** The last song similar songs were looked up for, so each is asked about once. */
    private var lastSimilarSeedId: String? = null

    private val _librarySongs = MutableStateFlow<List<SongUiModel>>(emptyList())
    val librarySongs: StateFlow<List<SongUiModel>> = _librarySongs

    /**
     * Whether the queue saved at the last close has been read back yet, whatever it held. Until
     * then the top bar keeps the Now playing button's place, so the buttons beside it do not
     * move when it appears; after it, an empty queue means nothing is playing.
     */
    private val _isQueueRestored = MutableStateFlow(false)
    val isQueueRestored: StateFlow<Boolean> = _isQueueRestored

    private val _libraryAlbums = MutableStateFlow<List<AlbumUiModel>>(emptyList())
    val libraryAlbums: StateFlow<List<AlbumUiModel>> = _libraryAlbums

    private val _libraryArtists = MutableStateFlow<List<ArtistUiModel>>(emptyList())
    val libraryArtists: StateFlow<List<ArtistUiModel>> = _libraryArtists

    private val _libraryPlaylists = MutableStateFlow<List<PlaylistUiModel>>(emptyList())

    private val _libraryRefreshTrigger = MutableStateFlow(0)
    val libraryRefreshTrigger: StateFlow<Int> = _libraryRefreshTrigger.asStateFlow()

    private val _isLoadingSongs = MutableStateFlow(true)
    val isLoadingSongs: StateFlow<Boolean> = _isLoadingSongs

    private val _isLoadingAlbums = MutableStateFlow(true)
    val isLoadingAlbums: StateFlow<Boolean> = _isLoadingAlbums

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState

    fun onSongDownloaded(onlineSongId: String, controller: MediaController?) {
        viewModelScope.launch {
            delay(200)

            val state = _playbackState.value
            val queue = state.playbackQueue

            val indexInQueue = queue.indexOfFirst { it.id == onlineSongId && it.sourceType == "YOUTUBE" }
            if (indexInQueue == -1) return@launch

            val library = _librarySongs.value
            val oldSong = queue[indexInQueue]

            val newLocalSong = library.find { candidate ->
                if (candidate.sourceType != "LOCAL_FILE" && candidate.sourceType != "YOUTUBE_DOWNLOAD") return@find false

                if (areSongsMatching(candidate, oldSong)) return@find true

                val dur1 = candidate.durationMillis ?: 0L
                val dur2 = oldSong.durationMillis ?: 0L
                val durationMatch = abs(dur1 - dur2) < 2500

                val artist1 = candidate.artist.lowercase().replace(Regex("[^a-z0-9]"), "")
                val artist2 = oldSong.artist.lowercase().replace(Regex("[^a-z0-9]"), "")
                val artistMatch = artist1.isNotEmpty() && (artist1.contains(artist2) || artist2.contains(artist1))

                durationMatch && artistMatch
            } ?: return@launch

            val newQueue = queue.toMutableList()
            newQueue[indexInQueue] = newLocalSong.copy(
                trackNumber = oldSong.trackNumber,
                discNumber = oldSong.discNumber
            )

            val isNowPlaying = (state.playbackQueueIndex == indexInQueue)

            if (isNowPlaying && controller != null && state.isPlaybackPlaying) {
                val currentPos = controller.currentPosition

                val newState = state.copy(
                    playbackQueue = newQueue,
                    playbackQueueEntities = newQueue.map { it.toQueueEntity() },
                    nowPlayingSong = newLocalSong,
                    currentSongId = newLocalSong.id,
                    nowPlayingDurationMs = newLocalSong.durationMillis ?: state.nowPlayingDurationMs,
                    isBuffering = true
                )
                _playbackState.value = newState
                persistPlaybackSnapshot(newState)

                startPlaybackFromQueue(
                    queue = newQueue,
                    startIndex = indexInQueue,
                    isNewQueue = false,
                    localController = controller,
                    startPositionMs = currentPos
                )
            } else {
                val newState = state.copy(
                    playbackQueue = newQueue,
                    playbackQueueEntities = newQueue.map { it.toQueueEntity() },
                    nowPlayingSong = if (isNowPlaying) newLocalSong else state.nowPlayingSong,
                    currentSongId = if (isNowPlaying) newLocalSong.id else state.currentSongId
                )
                _playbackState.value = newState
                persistPlaybackSnapshot(newState)
            }
        }
    }

    suspend fun getAlbumSongs(albumId: String): List<SongUiModel> {
        return withContext(Dispatchers.IO) {
            // The Albums tab shows one row for every album row that folds to the same title and
            // artist - the half of a record on the card and the whole of it on a server, two
            // discs in two folders, one album a server has split in two - and opened only the
            // first of them, so the page was missing every song filed under the others. All of
            // them are read now, in id order, which puts disc folders in their own order.
            val allAlbums = albumDao.getAllAlbums()
            val opened = allAlbums.firstOrNull { it.id == albumId }
            val albumKey = albumKeyer()
            val sameAlbum = opened?.let { o ->
                val key = albumKey(o.name, o.artist)
                allAlbums.filter { albumKey(it.name, it.artist) == key }.map { it.id }.sorted()
            } ?: listOf(albumId)

            val idsToFetch = LinkedHashSet(sameAlbum)
            idsToFetch.add(albumId)

            val suffix = albumId.substringAfter(":", missingDelimiterValue = "")
            if (suffix.isNotEmpty()) {
                idsToFetch.add("LOCAL_FILE:$suffix")
                idsToFetch.add("YOUTUBE:$suffix")
                idsToFetch.add("YOUTUBE_DOWNLOAD:$suffix")
            }

            // A part with no disc numbers is numbered by its place among the parts, so two
            // folders' track ones do not interleave.
            val discOfPart = sameAlbum.withIndex().associate { (i, id) -> id to i + 1 }
            val partOfSong = mutableMapOf<String, Int>()
            val allEntities = withoutServerDuplicates(
                idsToFetch.flatMap { id ->
                    songDao.getSongsByAlbumId(id).onEach { partOfSong.putIfAbsent(it.id, discOfPart[id] ?: 1) }
                }.distinctBy { it.id },
            )

            allEntities.map { entity ->
                SongUiModel(
                    id = entity.id,
                    title = entity.title,
                    artist = entity.artist,
                    durationText = formatDurationMillis(entity.durationMillis),
                    durationMillis = entity.durationMillis,
                    trackNumber = entity.trackNumber,
                    discNumber = entity.discNumber,
                    sourceType = entity.sourceType,
                    audioUri = entity.audioUri,
                    album = entity.album,
                )
            }.let { songs ->
                // Only where the parts both start again at track one: half an album tagged and
                // half not is still one disc, and keeps one running order.
                val partsRestart = songs
                    .filter { it.discNumber == null && it.trackNumber != null }
                    .groupBy { it.trackNumber }
                    .values
                    .any { same -> same.mapNotNull { partOfSong[it.id] }.distinct().size > 1 }
                songs.sortedWith(
                    compareBy(
                        { it.discNumber ?: if (partsRestart) partOfSong[it.id] ?: 1 else 1 },
                        { it.trackNumber ?: 0 },
                    ),
                )
            }
        }
    }

    /**
     * An album's page: each song once, the copy on the phone wherever there is one, in the
     * album's track order. The YouTube listing behind it still names videos already
     * downloaded, and showed them as streams, with a Download button to fetch them again.
     */
    suspend fun getAlbumSongsForDetails(album: AlbumUiModel): List<SongUiModel> {
        val songs = albumSongsForDetails(album)
        if (songs.none { it.sourceType == "YOUTUBE" }) return songs
        return app.online.inAlbumOrder(app.online.withCopies(songs, downloadedOnlineSongs()))
    }

    /** The catalogue's downloads in the library, which listings are matched against. */
    private suspend fun downloadedOnlineSongs(): List<SongUiModel> = withContext(Dispatchers.IO) {
        songDao.getSongsBySourceType("YOUTUBE_DOWNLOAD").map { entity ->
            SongUiModel(
                id = entity.id,
                title = entity.title,
                artist = entity.artist,
                durationText = formatDurationMillis(entity.durationMillis),
                durationMillis = entity.durationMillis,
                trackNumber = entity.trackNumber,
                discNumber = entity.discNumber,
                sourceType = entity.sourceType,
                audioUri = entity.audioUri,
                album = entity.album,
            )
        }
    }

    private suspend fun albumSongsForDetails(album: AlbumUiModel): List<SongUiModel> {
        val localSongs = getAlbumSongs(album.id)

        val shouldComplete = app.online.completeAlbums.value

        if (localSongs.isNotEmpty()) {
            // An album on a music server is already whole: the server holds the record, not a
            // few tracks off it. Filling its gaps from YouTube found no gaps to fill and added
            // near-misses instead - the same title twice, once from the server and once from a
            // YouTube listing that spelled it differently enough to slip past the matcher.
            val fromServer = localSongs.any { it.sourceType == SubsonicSync.SOURCE_TYPE }
            if (shouldComplete && !fromServer) {
                val onlineSongs = getOnlineAlbumSongs(album)
                if (onlineSongs.isNotEmpty()) {
                    return mergeLocalAndOnlineAlbums(localSongs, onlineSongs)
                }
            }
            return localSongs
        }

        val serverAlbumId = SubsonicSync.serverAlbumId(album.id)
        return if (album.sourceType == "YOUTUBE") {
            getOnlineAlbumSongs(album)
        } else if (album.sourceType == SubsonicSync.SOURCE_TYPE && serverAlbumId != null) {
            // Opened from a server song the library holds no album for; see serverAlbumFor.
            serverAlbumSongs(serverAlbumId)
        } else {
            emptyList()
        }
    }

    private fun mergeLocalAndOnlineAlbums(
        localSongs: List<SongUiModel>,
        onlineSongs: List<SongUiModel>
    ): List<SongUiModel> {
        val mergedList = mutableListOf<SongUiModel>()
        val availableLocal = localSongs.toMutableList()
        // What the album already shows, by title. A YouTube listing will happily offer the
        // same song twice - a remaster beside the original, the same take under two
        // spellings - and the pairing below only ever consumes one copy from the album,
        // so the second arrived as a second row for a track already on the page.
        val titlesShown = mutableSetOf<String>()

        for (ytSong in onlineSongs) {
            val matchIndex = availableLocal.indexOfFirst { local ->
                areSongsMatching(local, ytSong)
            }

            if (matchIndex != -1) {
                val localSong = availableLocal.removeAt(matchIndex)
                // The song's own number, where its file has one. A library album has no
                // YouTube album id, so its listing comes from a search, and a search numbers
                // its results in the order they were found: those numbers were stamped over
                // tags written from the album's real running order, and Peaches came first
                // however the album was downloaded.
                val displaySong = localSong.copy(
                    trackNumber = localSong.trackNumber ?: ytSong.trackNumber,
                    discNumber = localSong.discNumber ?: ytSong.discNumber
                )

                titlesShown.add(ArtistNames.key(displaySong.title))
                mergedList.add(displaySong)
            } else if (titlesShown.add(ArtistNames.key(ytSong.title))) {
                mergedList.add(ytSong)
            }
        }

        // Whatever the listing did not account for is still on the album and still belongs.
        if (availableLocal.isNotEmpty()) {
            mergedList.addAll(availableLocal.sortedBy { it.trackNumber })
        }

        return mergedList
    }

    private fun areSongsMatching(local: SongUiModel, remote: SongUiModel): Boolean {
        fun normalize(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

        val lTitle = normalize(local.title)
        val rTitle = normalize(remote.title)

        if (lTitle == rTitle) return true

        if (lTitle.length > 3 && rTitle.length > 3) {
            if (lTitle.contains(rTitle) || rTitle.contains(lTitle)) return true
        }

        return false
    }

    private suspend fun getOnlineAlbumSongs(album: AlbumUiModel): List<SongUiModel> = app.online.albumSongs(album)

    data class ArtistContent(
        val songs: List<SongUiModel>,
        val albums: List<AlbumUiModel>,
        /** Whom the page is for, as the Artists tab names them; null when not known. */
        val name: String? = null,
    )

    /** Where the artist line on Now playing leads: a page in the library, or one on YouTube. */
    sealed interface ArtistPage {
        data class Library(val artist: ArtistUiModel) : ArtistPage
        data class Online(val artist: OnlineArtistUiModel) : ArtistPage
    }

    /**
     * The artist page for the song that is playing, found the way the Artists tab groups them.
     *
     * The song's own row names its artist by id, and that name is folded with [ArtistNames.key]
     * to find the one entry the tab shows for every spelling of it. A song streamed from YouTube
     * and never saved has no row, so YouTube is asked instead, and only a name that folds to the
     * same key is taken - a page for someone else is worse than no page.
     */
    suspend fun artistPageFor(song: SongUiModel): ArtistPage? {
        var groupOf: (String) -> String = ArtistNames::artistKey
        val names = withContext(Dispatchers.IO) {
            val entity = songDao.getSongById(song.id)
            val artistIds = buildList {
                entity?.artistId?.let(::add)
                entity?.albumId?.let { albumId ->
                    albumDao.getAllAlbums().firstOrNull { it.id == albumId }?.artistId?.let(::add)
                }
            }
            val artists = artistDao.getAllArtists()
            groupOf = ArtistNames.grouping(artists.map { it.name })
            val byId = artistIds.mapNotNull { id -> artists.firstOrNull { it.id == id }?.name }
            (byId + listOfNotNull(entity?.artist, song.artist)).filter { it.isNotBlank() }.distinct()
        }

        val libraryArtists = _libraryArtists.value
        for (name in names) {
            val key = groupOf(name)
            libraryArtists.firstOrNull { groupOf(it.name) == key }?.let {
                return ArtistPage.Library(it)
            }
        }

        if (isFromServer(song.sourceType)) return serverArtistPageFor(song)

        if (song.sourceType != "YOUTUBE" || song.artist.isBlank()) return null

        // "A, B" and "A & B" are how YouTube credits two people; the page is the first one's.
        // The whole name is tried first, so a band called "Iron & Wine" is still found whole.
        val first = song.artist.split(", ", " & ").first().trim()
        return withContext(Dispatchers.IO) {
            listOf(song.artist, first).distinct().firstNotNullOfOrNull { name ->
                app.online.findArtist(name)?.let { ArtistPage.Online(it) }
            }
        }
    }

    /**
     * The album the song that is playing comes from, as the Albums tab shows it.
     *
     * That tab folds the same record held twice - on the card and on a server - into one entry,
     * so the song's own album row is found first and then the entry it was folded into. A song
     * streamed from YouTube and never saved is given the album YouTube names, which the album
     * page already knows how to fill.
     */
    suspend fun albumPageFor(song: SongUiModel): AlbumUiModel? {
        val key = withContext(Dispatchers.IO) { albumKeyer() }

        val albumEntity = withContext(Dispatchers.IO) {
            songDao.getSongById(song.id)?.albumId?.let { albumId ->
                albumDao.getAllAlbums().firstOrNull { it.id == albumId }
            }
        }

        val libraryAlbums = _libraryAlbums.value
        if (albumEntity != null) {
            val wanted = key(albumEntity.name, albumEntity.artist)
            libraryAlbums.firstOrNull { it.id == albumEntity.id || key(it.title, it.artist) == wanted }
                ?.let { return it }
        }

        val title = song.album?.takeIf { it.isNotBlank() } ?: return null
        val artistKey = ArtistNames.artistKey(song.artist)
        libraryAlbums.firstOrNull {
            it.title.trim().equals(title.trim(), ignoreCase = true) &&
                    (it.artist == null || ArtistNames.artistKey(it.artist) == artistKey)
        }?.let { return it }

        if (isFromServer(song.sourceType)) return serverAlbumFor(song, albumEntity, title)

        if (song.sourceType != "YOUTUBE") return null
        return AlbumUiModel(
            id = "",
            title = title,
            artist = song.artist,
            sourceType = "YOUTUBE",
        )
    }

    private fun isFromServer(sourceType: String?): Boolean =
        sourceType == SubsonicSync.SOURCE_TYPE || sourceType == SubsonicDownloader.SOURCE_TYPE

    /** A client for the music server set up in Settings, or null when there is none. */
    private fun serverClient(): SubsonicClient? = app.settingsManager.serverClient(app.resources)

    /** The server's own record of a song: the album and artist ids its library row does not keep. */
    private suspend fun serverSongFor(client: SubsonicClient, song: SongUiModel): SubsonicSong? {
        val id = SubsonicSync.serverSongId(song.id) ?: return null
        return (client.fetchSong(id) as? SubsonicResult.Success)?.value
    }

    /**
     * The album page for a song streamed from the music server when the library has no album
     * for it: the song outlived its album in the library, or the library knows the record only
     * as its card copy under another name. The page is filled from the server - see
     * [serverAlbumSongs] - so the rest of the record is there to play, as it is for an album
     * in the library.
     */
    private suspend fun serverAlbumFor(song: SongUiModel, albumRow: AlbumEntity?, title: String): AlbumUiModel? {
        val fromRow = albumRow?.id?.let(SubsonicSync::serverAlbumId)
        val serverId = fromRow ?: serverClient()?.let { serverSongFor(it, song) }?.albumId ?: return null
        return AlbumUiModel(
            id = SubsonicSync.albumId(serverId),
            title = albumRow?.name ?: title,
            artist = albumRow?.artist ?: song.artist,
            sourceType = SubsonicSync.SOURCE_TYPE,
        )
    }

    /**
     * The same for the artist line: their page as the server files them, under the album's
     * artist first, the way the library files a compilation.
     */
    private suspend fun serverArtistPageFor(song: SongUiModel): ArtistPage? = withContext(Dispatchers.IO) {
        val client = serverClient() ?: return@withContext null
        val serverSong = serverSongFor(client, song) ?: return@withContext null
        val albumArtistId = serverSong.albumId
            ?.let { (serverAlbum(client, it) as? SubsonicResult.Success)?.value?.first?.artistId }
        listOfNotNull(albumArtistId, serverSong.artistId).distinct().firstNotNullOfOrNull { artistId ->
            (client.fetchArtist(artistId) as? SubsonicResult.Success)?.value?.let { (name, albums) ->
                ArtistPage.Library(
                    ArtistUiModel(
                        id = SERVER_ARTIST_PREFIX + artistId,
                        name = name,
                        songCount = albums.sumOf { it.songCount ?: 0 },
                        albumCount = albums.size,
                    ),
                )
            }
        }
    }

    // Each server album as first fetched for a page, as with YouTube's listings: the page is
    // drawn again whenever the library changes, and need not ask the server again each time.
    private val serverAlbumListings =
        java.util.concurrent.ConcurrentHashMap<String, Pair<SubsonicAlbum, List<SubsonicSong>>>()

    private suspend fun serverAlbum(
        client: SubsonicClient,
        serverAlbumId: String,
    ): SubsonicResult<Pair<SubsonicAlbum, List<SubsonicSong>>> {
        val cacheKey = (app.settingsManager.subsonicConfig.value?.normalisedBaseUrl ?: "") + "\n" + serverAlbumId
        serverAlbumListings[cacheKey]?.let { return SubsonicResult.Success(it) }
        return client.fetchAlbum(serverAlbumId).also { result ->
            if (result is SubsonicResult.Success) serverAlbumListings[cacheKey] = result.value
        }
    }

    /**
     * A server album's songs as the server lists them, each as the library would play it: the
     * copy on the phone where there is one, the library's own row where it has one (a song kept
     * on the phone plays from its file), and otherwise a stream from the server.
     *
     * Throws with the server's reason when it cannot be asked, which the page shows.
     */
    private suspend fun serverAlbumSongs(serverAlbumId: String): List<SongUiModel> = withContext(Dispatchers.IO) {
        val client = serverClient()
            ?: throw IllegalStateException(app.getString(R.string.library_album_load_failed))
        val listing = when (val r = serverAlbum(client, serverAlbumId)) {
            is SubsonicResult.Failure -> throw IllegalStateException(r.message)
            is SubsonicResult.Success -> r.value
        }
        asTheLibraryPlaysThem(client, listing.second)
    }

    private suspend fun asTheLibraryPlaysThem(client: SubsonicClient, songs: List<SubsonicSong>): List<SongUiModel> {
        val all = songDao.getAllSongs()
        val rows = all.associateBy { it.id }
        val onThisPhone = all
            .filter { it.sourceType == "LOCAL_FILE" || it.sourceType == "YOUTUBE_DOWNLOAD" || it.sourceType == SubsonicDownloader.SOURCE_TYPE }
            .associateBy { ArtistNames.songKey(it.artist, it.album, it.title) }

        return songs.map { s ->
            val key = ArtistNames.songKey(s.artist, s.album, s.title)
            val entity = onThisPhone[key] ?: rows[SubsonicSync.songId(s.id)]
            if (entity != null) {
                SongUiModel(
                    id = entity.id,
                    title = entity.title,
                    artist = entity.artist,
                    durationText = formatDurationMillis(entity.durationMillis),
                    durationMillis = entity.durationMillis,
                    trackNumber = s.trackNumber ?: entity.trackNumber,
                    discNumber = s.discNumber ?: entity.discNumber,
                    sourceType = entity.sourceType,
                    audioUri = entity.audioUri,
                    album = entity.album,
                )
            } else {
                SongUiModel(
                    id = SubsonicSync.songId(s.id),
                    title = s.title,
                    artist = s.artist,
                    durationText = formatDurationMillis(s.durationMillis),
                    durationMillis = s.durationMillis,
                    trackNumber = s.trackNumber,
                    discNumber = s.discNumber,
                    sourceType = SubsonicSync.SOURCE_TYPE,
                    audioUri = client.streamUrl(s.id, s.suffix),
                    album = s.album,
                )
            }
        }.sortedWith(compareBy({ it.discNumber ?: 1 }, { it.trackNumber ?: 0 }))
    }

    /** An artist page filled from the server, for [serverArtistPageFor]. */
    private suspend fun serverArtistContent(serverArtistId: String): ArtistContent = withContext(Dispatchers.IO) {
        val client = serverClient()
            ?: throw IllegalStateException(app.getString(R.string.library_artist_load_failed))
        val (name, albums) = when (val r = client.fetchArtist(serverArtistId)) {
            is SubsonicResult.Failure -> throw IllegalStateException(r.message)
            is SubsonicResult.Success -> r.value
        }
        val songs = albums.flatMap { album ->
            (serverAlbum(client, album.id) as? SubsonicResult.Success)?.value?.second.orEmpty()
        }
        ArtistContent(
            songs = asTheLibraryPlaysThem(client, songs).distinctBy { it.id },
            albums = albums
                .map { album ->
                    AlbumUiModel(
                        id = SubsonicSync.albumId(album.id),
                        title = album.name,
                        artist = album.artist,
                        sourceType = SubsonicSync.SOURCE_TYPE,
                        releaseYear = album.year,
                    )
                }
                .sortedWith(
                    compareByDescending<AlbumUiModel> { it.releaseYear ?: Int.MIN_VALUE }.thenBy { it.title },
                ),
            name = name,
        )
    }

    suspend fun getArtistContent(artistId: String): ArtistContent {
        if (artistId.startsWith(SERVER_ARTIST_PREFIX)) return serverArtistContent(artistId.removePrefix(SERVER_ARTIST_PREFIX))
        return withContext(Dispatchers.IO) {
            // The same fold the Artists tab groups by, so the page holds every row the tab
            // put under this one name - Bjork's server songs beside Björk's on the card.
            val allArtists = artistDao.getAllArtistsWithCounts()
            val normalizeName = ArtistNames.grouping(allArtists.map { it.name })
            val baseArtist = allArtists.firstOrNull { it.id == artistId }

            val related = if (baseArtist != null) {
                val key = normalizeName(baseArtist.name)
                allArtists.filter { normalizeName(it.name) == key }
            } else {
                emptyList()
            }
            val relatedArtistIds: List<String> = related.map { it.id }.ifEmpty { listOf(artistId) }
            val groupName = baseArtist?.let { base ->
                if (related.size > 1) ArtistNames.groupName(related.map { it.name }, normalizeName(base.name)) else base.name
            }

            // Their own songs, and every song on an album filed under them: a track crediting
            // a guest is still on the record, and the Artists tab counts it, so the page that
            // said "12 songs" listed only the ones whose own artist tag matched.
            val filedAlbumIds = relatedArtistIds.flatMap { id -> albumDao.getAlbumsByArtistId(id) }.map { it.id }
            val songEntities = withoutServerDuplicates(
                (relatedArtistIds.flatMap { id -> songDao.getSongsByArtistId(id) } +
                    filedAlbumIds.flatMap { id -> songDao.getSongsByAlbumId(id) })
                    .distinctBy { it.id },
            )

            // An album filed under someone else still belongs here if one of these songs is on
            // it - a guest on a record no tag named the artist of. The Artists tab counts it.
            val songAlbumIds = songEntities.mapNotNull { it.albumId }.toSet()
            val albumEntities = (
                relatedArtistIds.flatMap { id -> albumDao.getAlbumsByArtistId(id) } +
                    if (songAlbumIds.isEmpty()) emptyList()
                    else albumDao.getAllAlbums().filter { it.id in songAlbumIds }
                ).distinctBy { it.id }

            val songs = songEntities.map { entity ->
                SongUiModel(
                    id = entity.id,
                    title = entity.title,
                    artist = entity.artist,
                    durationText = formatDurationMillis(entity.durationMillis),
                    durationMillis = entity.durationMillis,
                    trackNumber = entity.trackNumber,
                    discNumber = entity.discNumber,
                    sourceType = entity.sourceType,
                    audioUri = entity.audioUri,
                    album = entity.album,
                )
            }

            val albumIdToYear: Map<String, Int?> = songEntities
                .mapNotNull { entity ->
                    val albumId = entity.albumId ?: return@mapNotNull null
                    albumId to entity.releaseYear
                }
                .groupBy(
                    keySelector = { it.first },
                    valueTransform = { it.second },
                )
                .mapValues { (_, years) ->
                    years.filterNotNull().maxOrNull()
                }

            // Worked out from the songs, not the album row: a server album kept on the
            // phone is still a server album by its row, and plays offline all the same.
            val songSourcesByAlbumId = songEntities
                .filter { it.albumId != null }
                .groupBy({ it.albumId!! }, { it.sourceType })

            val mergedAlbums = mergeAlbums(albumEntities, allArtists.map { it.name }, albumIdToYear, songSourcesByAlbumId)
                .sortedWith(
                    compareByDescending<AlbumUiModel> { album ->
                        album.releaseYear ?: Int.MIN_VALUE
                    }.thenBy { album -> album.title },
                )

            ArtistContent(songs, mergedAlbums, groupName)
        }
    }

    /** Whether "Fill in album gaps" is on; always off in an edition without YouTube. */
    val completeAlbumsFromOnline get() = app.online.completeAlbums

    private val onlineAlbumsCache = HashMap<String, Pair<Long, List<AlbumUiModel>>>()

    /**
     * The albums and singles YouTube lists for the artist named [name] that [local] lacks, for
     * the library artist's page. Empty when "Fill in album gaps" is off (nothing is fetched),
     * when this edition has no YouTube, or when YouTube has no artist of exactly that name -
     * [ArtistNames.key] decides, so a different artist is never shown. Null when YouTube could
     * not be reached. Kept for [ONLINE_ALBUMS_KEEP_MS] so opening the page again does not ask again.
     */
    suspend fun getOnlineAlbumsFor(name: String, local: List<AlbumUiModel>): List<AlbumUiModel>? {
        if (!app.online.available || !app.online.completeAlbums.value || name.isBlank()) return emptyList()
        val key = ArtistNames.key(name)
        val now = System.currentTimeMillis()
        val cached = synchronized(onlineAlbumsCache) { onlineAlbumsCache[key] }
        val all = if (cached != null && now - cached.first < ONLINE_ALBUMS_KEEP_MS) {
            cached.second
        } else {
            val fetched = withContext(Dispatchers.IO) {
                try {
                    val artist = app.online.findArtist(name) ?: return@withContext emptyList()
                    val page = app.online.artistPage(artist.browseId, emptyList())
                    page.albums + page.singles
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            } ?: return null
            synchronized(onlineAlbumsCache) { onlineAlbumsCache[key] = now to fetched }
            fetched
        }
        return ArtistAlbumsFill.rest(local, all, emptyList())
    }

    suspend fun getOnlineArtistPage(browseId: String): OnlineArtistPage =
        app.online.artistPage(browseId, downloadedOnlineSongs())

    private fun rebuildPlaybackSubqueues(queue: List<SongUiModel>) {
        playbackCoordinator.rebuildPlaybackSubqueues(queue)
    }

    private fun persistPlaybackSnapshot(state: PlaybackState = _playbackState.value) {
        val queueIds = state.playbackQueue.map { it.id }
        val index = state.playbackQueueIndex
        val isPlaying = state.isPlaybackPlaying
        val positionMs = state.nowPlayingPositionMs
        val repeatModeKey = when (state.repeatMode) {
            RepeatMode.OFF -> NowPlayingRepeatModeKeys.OFF
            RepeatMode.QUEUE -> NowPlayingRepeatModeKeys.QUEUE
            RepeatMode.ONE -> NowPlayingRepeatModeKeys.ONE
        }
        val isShuffleOn = state.isShuffleOn

        viewModelScope.launch(Dispatchers.IO) {
            nowPlayingStorage.save(
                NowPlayingSnapshot(
                    queueSongIds = queueIds,
                    currentIndex = index,
                    isPlaying = isPlaying,
                    positionMs = positionMs,
                    repeatModeKey = repeatModeKey,
                    isShuffleOn = isShuffleOn,
                )
            )
        }
    }

    fun togglePlayback(localController: MediaController?) {
        val state = _playbackState.value
        val song = state.nowPlayingSong ?: return
        val currentlyPlaying = state.isPlaybackPlaying

        if (!currentlyPlaying) {
            val queue = state.playbackQueue
            val index = state.playbackQueueIndex

            val needsInit = when (song.sourceType) {
                "LOCAL_FILE", "YOUTUBE", "YOUTUBE_DOWNLOAD" -> !playbackCoordinator.localQueueInitialized
                else -> false
            }

            if (needsInit && queue.isNotEmpty() && index != null && index in queue.indices) {
                startPlaybackFromQueue(
                    queue = queue,
                    startIndex = index,
                    isNewQueue = false,
                    localController = localController,
                )
                return
            }
        }

        if (currentlyPlaying) {
            localController?.pause()
        } else {
            // After an error the player sits idle; it has to be prepared again to try.
            if (localController?.playbackState == Player.STATE_IDLE && localController.mediaItemCount > 0) {
                localController.prepare()
            }
            localController?.playWhenReady = true
        }

        _playbackState.value = state.copy(isPlaybackPlaying = !currentlyPlaying)
        persistPlaybackSnapshot()
    }

    private fun SongUiModel.toQueueEntity(): SongEntity =
        SongEntity(
            id = id,
            title = title,
            artist = artist,
            album = album,
            albumId = null,
            discNumber = discNumber,
            trackNumber = trackNumber,
            durationMillis = durationMillis,
            sourceType = sourceType,
            audioUri = audioUri ?: id,
            artistId = null,
            releaseYear = null,
            localLastModifiedMillis = null,
            localFileSizeBytes = null,
        )

    fun startPlaybackFromQueue(
        queue: List<SongUiModel>,
        startIndex: Int,
        isNewQueue: Boolean = true,
        localController: MediaController?,
        startPositionMs: Long = 0L,
    ) {
        if (queue.isEmpty() || startIndex !in queue.indices) return

        val previous = _playbackState.value
        val originalQueue = if (isNewQueue) queue else previous.originalPlaybackQueue
        val shuffle = if (isNewQueue) false else previous.isShuffleOn

        rebuildPlaybackSubqueues(queue)

        val song = queue[startIndex]
        val repeatMode = previous.repeatMode

        val queueEntities = queue.map { it.toQueueEntity() }
        val shouldShowInitialBuffering = song.sourceType != "LOCAL_FILE" && song.sourceType != "YOUTUBE_DOWNLOAD"

        val newState = previous.copy(
            playbackQueue = queue,
            playbackQueueEntities = queueEntities,
            playbackQueueIndex = startIndex,
            originalPlaybackQueue = originalQueue,
            isShuffleOn = shuffle,
            currentSongId = song.id,
            nowPlayingSong = song,
            isPlaybackPlaying = true,
            isBuffering = shouldShowInitialBuffering,
            nowPlayingPositionMs = startPositionMs,
            nowPlayingDurationMs = song.durationMillis ?: 0L,
        )
        _playbackState.value = newState
        persistPlaybackSnapshot(newState)

        if (playsOnThisPhone(song.sourceType)) {
            val controller = localController
            if (controller != null && playbackCoordinator.localMediaItemsForQueue.isNotEmpty()) {

                // IMPORTANT: Segmented Playback Logic
                // We identify the contiguous segment of local media (LOCAL_FILE or
                // YOUTUBE_DOWNLOAD) starting from startIndex. This prevents the
                // player from auto-advancing into "gaps" where a streaming
                // YouTube song should be.
                var segmentEndIndex = startIndex
                while (segmentEndIndex < queue.size &&
                    playsOnThisPhone(queue[segmentEndIndex].sourceType)
                ) {
                    segmentEndIndex++
                }

                // Construct the MediaItem list for ONLY this segment
                val segmentMediaItems = (startIndex until segmentEndIndex).mapNotNull { globalIndex ->
                    val localIndex = playbackCoordinator.localIndexByGlobal?.get(globalIndex)
                    if (localIndex != null && localIndex != -1) {
                        playbackCoordinator.localMediaItemsForQueue.getOrNull(localIndex)
                    } else null
                }

                if (segmentMediaItems.isNotEmpty()) {
                    controller.setMediaItems(
                        segmentMediaItems,
                        0, // Start at the beginning of THIS segment
                        startPositionMs
                    )

                    // IMPORTANT: Never delegate REPEAT_ALL to the local player in a mixed queue.
                    // The ViewModel must handle the loop.
                    controller.repeatMode = when (repeatMode) {
                        RepeatMode.ONE -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }

                    controller.prepare()
                    controller.playWhenReady = true
                    playbackCoordinator.localQueueInitialized = true
                }
            }
        } else if (song.sourceType == "YOUTUBE") {
            val controller = localController
            if (controller != null) {
                viewModelScope.launch {
                    playOnlineSongInQueue(song, startIndex, controller, startPositionMs)
                }
                playbackCoordinator.localQueueInitialized = true
            }
        }
    }

    fun startShuffledPlaybackFromQueue(
        queue: List<SongUiModel>,
        localController: MediaController?,
    ) {
        if (queue.isEmpty()) return

        val shuffledQueue = queue.shuffled()
        val previous = _playbackState.value

        _playbackState.value = previous.copy(
            originalPlaybackQueue = queue,
            isShuffleOn = true,
        )

        startPlaybackFromQueue(shuffledQueue, 0, isNewQueue = false, localController = localController)
    }

    /**
     * Puts songs into the queue that is already playing, either straight after the current
     * song or at the end, without interrupting it.
     *
     * The queue this app plays is its own list, and the player is only ever handed the run
     * of songs around the current one that it can open by itself - so changing the list is
     * not enough. Left at that, the player would go on to whatever it was already holding
     * and the new song would be stepped over, because what keeps the two in step is matching
     * the player's media id back to a row in the list. So a song going in next is spliced
     * into the player's own running order as well.
     *
     * A song going on the end needs none of that: the player reaches the end of what it
     * holds, says so, and the queue is read again from the list at that point.
     */
    fun enqueue(
        songs: List<SongUiModel>,
        playNext: Boolean,
        localController: MediaController?,
    ) {
        if (songs.isEmpty()) return

        val state = _playbackState.value
        val queue = state.playbackQueue
        val index = state.playbackQueueIndex

        // With nothing playing there is nothing to queue behind, and the honest reading of
        // "play next" on a silent phone is simply to play it.
        if (queue.isEmpty() || index == null || index !in queue.indices) {
            startPlaybackFromQueue(
                queue = songs,
                startIndex = 0,
                isNewQueue = true,
                localController = localController,
            )
            return
        }

        val insertAt = if (playNext) index + 1 else queue.size
        val newQueue = queue.toMutableList().apply { addAll(insertAt, songs) }

        // The unshuffled order is what shuffle is turned off back into. With shuffle off the
        // two lists are the same and the song goes in the same place; with it on, the songs
        // join the end of the order they would otherwise have been in.
        val newOriginal = if (state.isShuffleOn) state.originalPlaybackQueue + songs else newQueue

        // Rebuilding the subqueues clears this, and it is what tells a resume from pause
        // whether the player still holds a queue. Clearing it here would make the next
        // press of play start the current song again from the beginning.
        val wasInitialized = playbackCoordinator.localQueueInitialized
        rebuildPlaybackSubqueues(newQueue)

        val newState = state.copy(
            playbackQueue = newQueue,
            playbackQueueEntities = newQueue.map { it.toQueueEntity() },
            originalPlaybackQueue = newOriginal,
            // The index is left alone: everything went in after the song that is playing.
        )
        _playbackState.value = newState
        persistPlaybackSnapshot(newState)

        val controller = localController
        val canSplice = playNext &&
            controller != null &&
            wasInitialized &&
            controller.mediaItemCount > 0 &&
            playsOnThisPhone(queue[index].sourceType) &&
            songs.all { playsOnThisPhone(it.sourceType) }

        if (canSplice && controller != null) {
            // Taken from the rebuilt list rather than built again here, so a queued song is
            // handed to the player exactly as a played one is.
            val items = songs.indices.mapNotNull { offset ->
                val global = insertAt + offset
                val local = playbackCoordinator.localIndexByGlobal?.getOrNull(global) ?: -1
                if (local >= 0) playbackCoordinator.localMediaItemsForQueue.getOrNull(local) else null
            }
            if (items.isNotEmpty()) {
                controller.addMediaItems(controller.currentMediaItemIndex + 1, items)
            }
        }

        if (wasInitialized) playbackCoordinator.localQueueInitialized = true
    }

    /**
     * Up next's arrows: one song still to come, moved a place earlier or later. Only what is
     * after the song playing moves, and only among itself, so the song playing keeps its place
     * and goes on playing.
     */
    fun moveInQueue(from: Int, to: Int, localController: MediaController?) {
        val state = _playbackState.value
        val queue = state.playbackQueue
        val index = state.playbackQueueIndex ?: return
        if (from == to || from <= index || to <= index || from !in queue.indices || to !in queue.indices) return

        val newQueue = queue.toMutableList().apply { add(to, removeAt(from)) }
        // Under shuffle the order shuffle goes back to is the album's, and moving a song in
        // the shuffled order says nothing about that one.
        val newOriginal = if (state.isShuffleOn) state.originalPlaybackQueue else newQueue
        applyQueueEdit(state, newQueue, index, newOriginal, localController)
    }

    /** Up next's cross: a song still to come taken out of the queue. Not the one playing. */
    fun removeFromQueue(at: Int, localController: MediaController?) {
        val state = _playbackState.value
        val queue = state.playbackQueue
        val index = state.playbackQueueIndex ?: return
        if (at <= index || at !in queue.indices) return

        val removed = queue[at]
        val newQueue = queue.toMutableList().apply { removeAt(at) }
        val newOriginal = if (state.isShuffleOn) {
            state.originalPlaybackQueue.toMutableList().apply {
                val there = indexOfFirst { it.id == removed.id }
                if (there >= 0) removeAt(there)
            }
        } else {
            newQueue
        }
        applyQueueEdit(state, newQueue, index, newOriginal, localController)
    }

    /** A row in Up next pressed: the queue stays as it is and plays on from that song. */
    fun playFromQueue(at: Int, localController: MediaController?) {
        val queue = _playbackState.value.playbackQueue
        if (at !in queue.indices) return
        startPlaybackFromQueue(
            queue = queue,
            startIndex = at,
            isNewQueue = false,
            localController = localController,
        )
    }

    /**
     * A change to the songs after the one playing, made without interrupting it.
     *
     * The list is this app's, but the player holds its own copy of the run of songs it can
     * open by itself, and goes on to whatever that copy says next. So everything it holds past
     * the current song is taken back and the run is handed to it again from the edited list -
     * the same run starting a song would have given it. A YouTube song is held alone and the
     * list is read again when it ends, so it needs nothing doing.
     */
    private fun applyQueueEdit(
        state: PlaybackState,
        newQueue: List<SongUiModel>,
        newIndex: Int,
        newOriginal: List<SongUiModel>,
        localController: MediaController?,
    ) {
        val current = state.nowPlayingSong ?: return

        // As in enqueue: rebuilding clears this, and a resume from pause reads it.
        val wasInitialized = playbackCoordinator.localQueueInitialized
        rebuildPlaybackSubqueues(newQueue)

        val newState = state.copy(
            playbackQueue = newQueue,
            playbackQueueEntities = newQueue.map { it.toQueueEntity() },
            playbackQueueIndex = newIndex,
            originalPlaybackQueue = newOriginal,
        )
        _playbackState.value = newState
        persistPlaybackSnapshot(newState)

        val controller = localController
        if (controller != null &&
            wasInitialized &&
            playsOnThisPhone(current.sourceType) &&
            controller.currentMediaItem?.mediaId == current.id
        ) {
            val at = controller.currentMediaItemIndex
            if (at + 1 < controller.mediaItemCount) {
                controller.removeMediaItems(at + 1, controller.mediaItemCount)
            }
            val ahead = mutableListOf<MediaItem>()
            var global = newIndex + 1
            while (global < newQueue.size && playsOnThisPhone(newQueue[global].sourceType)) {
                val local = playbackCoordinator.localIndexByGlobal?.getOrNull(global) ?: -1
                if (local >= 0) playbackCoordinator.localMediaItemsForQueue.getOrNull(local)?.let { ahead += it }
                global++
            }
            if (ahead.isNotEmpty()) controller.addMediaItems(ahead)
        }

        if (wasInitialized) playbackCoordinator.localQueueInitialized = true
    }

    /**
     * With "Keep playing similar songs" on, the last song in the queue being a YouTube song -
     * streamed, or downloaded from YouTube - is the moment to ask YouTube Music what it would
     * play next, and add that to the end. A download is asked about by the video it came from,
     * as the download record knows it; one the record does not know is left to end the queue,
     * and so is a song from a folder or the music server. It is asked while the song plays
     * rather than when it ends, so the next one follows without a gap, and the songs it adds
     * show in the queue like any other. The last of those, in its turn, asks again.
     *
     * Nothing is asked while the phone is offline; the song is asked about again once a
     * network is back, if it is still playing. A lookup that fails adds nothing and leaves the
     * playing song alone.
     *
     * Nothing is added under repeat: the listener has said what to play after the end.
     */
    private fun addSimilarIfLast(state: PlaybackState, controller: MediaController) {
        val queue = state.playbackQueue
        val index = state.playbackQueueIndex ?: return
        if (index != queue.lastIndex) return
        val seed = queue[index]
        if (state.repeatMode != RepeatMode.OFF) return
        if (seed.id == lastSimilarSeedId) return
        if (!app.online.keepPlayingSimilar.value) return
        val videoId = app.online.similarSeed(seed) ?: return
        if (!isOnline()) return
        lastSimilarSeedId = seed.id

        viewModelScope.launch {
            val songs = app.online.similar(videoId, { _playbackState.value.playbackQueue }, SIMILAR_BATCH)
            // The listener may have started something else while the catalogue answered.
            if (_playbackState.value.playbackQueue.lastOrNull()?.id != seed.id) return@launch
            if (songs.isNotEmpty()) enqueue(songs, playNext = false, localController = controller)
        }
    }

    /** Whether the phone has a network that reaches the internet just now. */
    private fun isOnline(): Boolean = runCatching {
        val connectivity = app.getSystemService(ConnectivityManager::class.java) ?: return@runCatching false
        val network = connectivity.activeNetwork ?: return@runCatching false
        connectivity.getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(false)

    fun playNextInQueue(localController: MediaController?) {
        val state = _playbackState.value
        val queue = state.playbackQueue
        if (queue.isEmpty()) return

        val currentIndex = state.playbackQueueIndex ?: return
        if (currentIndex !in queue.indices) return

        val targetIndex = when {
            currentIndex < queue.lastIndex -> currentIndex + 1
            currentIndex == queue.lastIndex && state.repeatMode == RepeatMode.QUEUE -> 0
            state.repeatMode == RepeatMode.ONE -> currentIndex
            else -> return
        }

        val nextSong = queue[targetIndex]
        val shouldShowBuffering = nextSong.sourceType != "LOCAL_FILE" && nextSong.sourceType != "YOUTUBE_DOWNLOAD"

        _playbackState.value = state.copy(
            playbackQueueIndex = targetIndex,
            currentSongId = nextSong.id,
            nowPlayingSong = nextSong,
            nowPlayingDurationMs = nextSong.durationMillis ?: state.nowPlayingDurationMs,
            nowPlayingPositionMs = 0L,
            isPlaybackPlaying = true,
            isBuffering = shouldShowBuffering,
        )
        persistPlaybackSnapshot()

        startPlaybackFromQueue(
            queue = queue,
            startIndex = targetIndex,
            isNewQueue = false,
            localController = localController,
        )
    }

    fun playPreviousInQueue(localController: MediaController?) {
        val state = _playbackState.value
        val queue = state.playbackQueue
        if (queue.isEmpty()) return

        val currentIndex = state.playbackQueueIndex ?: return
        if (currentIndex !in queue.indices) return

        val targetIndex = when {
            currentIndex > 0 -> currentIndex - 1
            currentIndex == 0 && state.repeatMode == RepeatMode.QUEUE -> queue.lastIndex
            state.repeatMode == RepeatMode.ONE -> currentIndex
            else -> return
        }

        val prevSong = queue[targetIndex]
        val shouldShowBuffering = prevSong.sourceType != "LOCAL_FILE" && prevSong.sourceType != "YOUTUBE_DOWNLOAD"

        _playbackState.value = state.copy(
            playbackQueueIndex = targetIndex,
            currentSongId = prevSong.id,
            nowPlayingSong = prevSong,
            nowPlayingDurationMs = prevSong.durationMillis ?: state.nowPlayingDurationMs,
            nowPlayingPositionMs = 0L,
            isPlaybackPlaying = true,
            isBuffering = shouldShowBuffering,
        )
        persistPlaybackSnapshot()

        startPlaybackFromQueue(
            queue = queue,
            startIndex = targetIndex,
            isNewQueue = false,
            localController = localController,
        )
    }

    /**
     * What the button under the SkipPrevious icon actually does. It went straight to the
     * previous track however long the current one had been playing, so pressing it to hear
     * a song again took you off the song instead.
     *
     * Past the first few seconds it now starts the current song over; inside them it does
     * what it always did. A live stream has no beginning to return to, so it is left alone.
     */
    fun playPreviousOrRestart(localController: MediaController?) {
        val state = _playbackState.value
        val isLive = state.nowPlayingSong?.sourceType == "RADIO"
        if (!isLive && state.nowPlayingPositionMs > RESTART_RATHER_THAN_PREVIOUS_MS) {
            // A seek, not a restart of the queue: re-entering it would resolve a YouTube or
            // server stream over again for a song the player is already holding.
            localController?.seekTo(0L)
            _playbackState.value = state.copy(nowPlayingPositionMs = 0L)
            persistPlaybackSnapshot()
            return
        }
        playPreviousInQueue(localController)
    }

    fun toggleShuffleMode(localController: MediaController?) {
        val state = _playbackState.value
        val queue = state.playbackQueue
        val index = state.playbackQueueIndex ?: return
        val current = state.nowPlayingSong ?: return

        if (queue.isEmpty() || index !in queue.indices) return

        if (!state.isShuffleOn) {
            val remaining = (queue.take(index) + queue.drop(index + 1)).shuffled()
            val newQueue = listOf(current) + remaining
            val newQueueEntities = newQueue.map { it.toQueueEntity() }

            val newState = state.copy(
                playbackQueue = newQueue,
                playbackQueueEntities = newQueueEntities,
                playbackQueueIndex = 0,
                originalPlaybackQueue = queue,
                isShuffleOn = true,
                currentSongId = current.id,
                nowPlayingSong = current,
            )
            _playbackState.value = newState
            persistPlaybackSnapshot(newState)

            if (playsOnThisPhone(current.sourceType)) {
                startPlaybackFromQueue(
                    queue = newQueue,
                    startIndex = 0,
                    isNewQueue = false,
                    localController = localController,
                    startPositionMs = state.nowPlayingPositionMs
                )
            }

        } else {
            if (state.originalPlaybackQueue.isEmpty()) {
                _playbackState.value = state.copy(isShuffleOn = false)
                persistPlaybackSnapshot()
                return
            }

            val restoreQueue = state.originalPlaybackQueue
            val originalIndex = restoreQueue.indexOfFirst { it.id == current.id }
                .takeIf { it >= 0 } ?: 0

            val restoredCurrent = restoreQueue[originalIndex]
            val restoreEntities = restoreQueue.map { it.toQueueEntity() }

            val newState = state.copy(
                isShuffleOn = false,
                playbackQueue = restoreQueue,
                playbackQueueEntities = restoreEntities,
                playbackQueueIndex = originalIndex,
                currentSongId = restoredCurrent.id,
                nowPlayingSong = restoredCurrent,
                nowPlayingDurationMs = restoredCurrent.durationMillis ?: state.nowPlayingDurationMs,
                nowPlayingPositionMs = 0L,
            )
            _playbackState.value = newState
            persistPlaybackSnapshot(newState)

            if (playsOnThisPhone(restoredCurrent.sourceType)) {
                startPlaybackFromQueue(
                    queue = restoreQueue,
                    startIndex = originalIndex,
                    isNewQueue = false,
                    localController = localController,
                    startPositionMs = state.nowPlayingPositionMs
                )
            }
        }
    }

    fun cycleRepeatMode(localController: MediaController?) {
        val state = _playbackState.value
        val newRepeat = when (state.repeatMode) {
            RepeatMode.OFF -> RepeatMode.QUEUE
            RepeatMode.QUEUE -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }

        _playbackState.value = state.copy(repeatMode = newRepeat)
        persistPlaybackSnapshot()

        val song = state.nowPlayingSong
        if (playsOnThisPhone(song?.sourceType)) {
            localController?.let { controller ->
                controller.repeatMode = when (newRepeat) {
                    RepeatMode.ONE -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF // Always OFF for Queue/Off
                }
            }
        }
    }

    @OptIn(UnstableApi::class)
    private suspend fun playOnlineSongInQueue(
        song: SongUiModel,
        targetIndex: Int,
        localController: MediaController,
        startPositionMs: Long = 0L,
    ) {
        val videoId = song.id

        withContext(Dispatchers.Main) {
            val metadata = MediaMetadata.Builder()
                .setTitle(song.title)
                .setArtist(song.artist)
                .setAlbumTitle(song.album)
                .build()

            val mediaItem = MediaItem.Builder()
                .setMediaId(videoId)
                .setCustomCacheKey(app.online.cacheKey(videoId))
                .setUri(app.online.playAddress(videoId))
                .setMediaMetadata(metadata)
                .build()

            localController.setMediaItem(mediaItem)
            localController.prepare()
            if (startPositionMs > 0) {
                localController.seekTo(startPositionMs)
            }
            localController.playWhenReady = true

            val currentState = _playbackState.value
            if (targetIndex != currentState.playbackQueueIndex && currentState.currentSongId != song.id) {
                return@withContext
            }

            val updatedSong = song.copy(
                sourceType = "YOUTUBE",
                audioUri = videoId,
            )

            val state = _playbackState.value
            if (targetIndex !in state.playbackQueue.indices) {
                return@withContext
            }

            val updatedQueue = state.playbackQueue.toMutableList()
            updatedQueue[targetIndex] = updatedSong

            val updatedQueueEntities = updatedQueue.map { it.toQueueEntity() }

            val newState = state.copy(
                playbackQueue = updatedQueue,
                playbackQueueEntities = updatedQueueEntities,
                currentSongId = updatedSong.id,
                nowPlayingSong = updatedSong,
                nowPlayingDurationMs = updatedSong.durationMillis ?: state.nowPlayingDurationMs,
                nowPlayingPositionMs = startPositionMs, // Reflect the actual start pos
                isPlaybackPlaying = true,
            )

            _playbackState.value = newState
            persistPlaybackSnapshot(newState)
        }
    }

    fun startLocalPlaybackMonitoring(controller: MediaController) {
        localPlaybackMonitorJob?.cancel()
        localPlaybackMonitorJob = viewModelScope.launch {
            // The button follows the player the moment it changes, not at the next tick of the
            // loop below: a file the player cannot read leaves it idle at once, and the button
            // that said Pause for a second or two after the error dialog said Play only later.
            val follow = object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    if (!events.containsAny(
                            Player.EVENT_PLAYBACK_STATE_CHANGED,
                            Player.EVENT_PLAY_WHEN_READY_CHANGED,
                            Player.EVENT_PLAYER_ERROR,
                        )
                    ) return
                    val state = _playbackState.value
                    if (state.nowPlayingSong == null || !playsOnThisPhone(state.nowPlayingSong.sourceType)) return
                    val playing = player.playWhenReady && player.playbackState != Player.STATE_IDLE &&
                        player.playbackState != Player.STATE_ENDED
                    if (state.isPlaybackPlaying != playing) {
                        _playbackState.value = state.copy(isPlaybackPlaying = playing, isBuffering = false)
                    }
                }
            }
            controller.addListener(follow)
            try {
            var lastOnlineQueueIndex: Int? = null
            // Once a second, not five times a second. The seek bar and the clock are the
            // only readers, both show whole seconds, and every republish repaints a panel
            // that redraws in full.
            val fastIntervalMs = 1000L
            val slowIntervalMs = 2000L

            while (true) {
                val state = _playbackState.value
                val queue = state.playbackQueue
                val currentSong = state.nowPlayingSong
                val isLocalFile = playsOnThisPhone(currentSong?.sourceType)
                val isCatalogueSong = currentSong?.sourceType == "YOUTUBE"
                var didAutoAdvance = false

                if (!isLocalFile && !isCatalogueSong) {
                    delay(slowIntervalMs)
                    continue
                }

                // Read controller state. A player left idle by an error (a file it cannot read)
                // keeps playWhenReady, but nothing is playing: the button must say Play.
                val playbackState = controller.playbackState
                val isPlaying = controller.playWhenReady && playbackState != Player.STATE_IDLE &&
                    playbackState != Player.STATE_ENDED
                val position = controller.currentPosition
                val duration = controller.duration
                // What can stall is what comes over a network. This used to ask
                // playsOnThisPhone, which answers a different question - whether the player
                // can be handed the uri - and had just been taught to say yes to radio. The
                // result was a stream that went silent when the network dropped while the
                // screen went on showing a pause button, and a server song that did the same.
                val cannotStall = currentSong?.sourceType == "LOCAL_FILE" ||
                    currentSong?.sourceType == "YOUTUBE_DOWNLOAD" ||
                    currentSong?.sourceType == "SUBSONIC_DOWNLOAD"
                val isBufferingNow = !cannotStall && playbackState == Player.STATE_BUFFERING

                var newState = state.copy(
                    isPlaybackPlaying = isPlaying,
                    nowPlayingPositionMs = position,
                    nowPlayingDurationMs = if (duration > 0) duration else state.nowPlayingDurationMs,
                    isBuffering = isBufferingNow,
                )

                val currentMediaId = controller.currentMediaItem?.mediaId
                if (currentMediaId != null && queue.isNotEmpty()) {
                    val targetIndex = queue.indexOfFirst { it.id == currentMediaId }
                    if (targetIndex >= 0 && targetIndex != state.playbackQueueIndex) {
                        val newSong = queue[targetIndex]
                        newState = newState.copy(
                            playbackQueueIndex = targetIndex,
                            currentSongId = newSong.id,
                            nowPlayingSong = newSong,
                            nowPlayingDurationMs = newSong.durationMillis
                                ?: newState.nowPlayingDurationMs,
                            nowPlayingPositionMs = position,
                            isPlaybackPlaying = isPlaying,
                        )
                    }
                }

                if (isCatalogueSong || currentSong?.sourceType == "YOUTUBE_DOWNLOAD") addSimilarIfLast(state, controller)

                // Handle End-of-Track / End-of-Segment Auto-Advance
                if (playbackState == Player.STATE_ENDED && currentSong != null) {
                    val songId = currentSong.id
                    if (songId != lastCompletedSongId) {
                        lastCompletedSongId = songId

                        val currentIndex = state.playbackQueueIndex

                        if (queue.isNotEmpty() && currentIndex != null && currentIndex in queue.indices) {
                            val hasNext = currentIndex < queue.lastIndex
                            val hasMultiple = queue.size > 1
                            when {
                                state.repeatMode == RepeatMode.ONE -> {
                                    didAutoAdvance = true
                                    startPlaybackFromQueue(
                                        queue = queue,
                                        startIndex = currentIndex,
                                        isNewQueue = false,
                                        localController = controller,
                                    )
                                }
                                hasNext -> {
                                    didAutoAdvance = true
                                    playNextInQueue(controller)
                                }
                                !hasNext && hasMultiple && state.repeatMode == RepeatMode.QUEUE -> {
                                    didAutoAdvance = true
                                    startPlaybackFromQueue(
                                        queue = queue,
                                        startIndex = 0,
                                        isNewQueue = false,
                                        localController = controller,
                                    )
                                }
                            }
                        }
                    }
                } else if (playbackState == Player.STATE_READY && isPlaying) {
                    lastCompletedSongId = null
                }

                if (isCatalogueSong) {
                    // Maintain a small prefetch window
                    val currentIndex = state.playbackQueueIndex
                    if (currentIndex != null && currentIndex in queue.indices && currentIndex != lastOnlineQueueIndex) {
                        lastOnlineQueueIndex = currentIndex
                        val windowIds = mutableListOf<String>()
                        for (offset in -5..5) {
                            val idx = currentIndex + offset
                            if (idx in queue.indices) {
                                val s = queue[idx]
                                if (s.sourceType == "YOUTUBE") {
                                    windowIds += s.id
                                }
                            }
                        }
                        if (windowIds.isNotEmpty()) {
                            app.online.updateQueueWindow(windowIds)
                        }
                    }
                }

                if (!didAutoAdvance && newState != state) {
                    _playbackState.value = newState
                    persistPlaybackSnapshot(newState)
                }

                val nextDelayMs = when {
                    (isLocalFile || isCatalogueSong) && isPlaying -> fastIntervalMs
                    else -> slowIntervalMs
                }
                delay(nextDelayMs)
            }
            } finally {
                controller.removeListener(follow)
            }
        }
    }

    suspend fun resyncLocalLibrary(
        folders: Set<String>,
        onScanProgress: (Float) -> Unit,
        onIngestProgress: (Float) -> Unit,
    ): LibraryRepository.LocalResyncResult {
        val result = libraryRepository.resyncLocalLibrary(
            folders,
            onScanProgress,
            onIngestProgress
        )

        refreshLibraryFromDatabase()
        // Downloads found again in a chosen folder may still be Opus; see DownloadUpgrade.
        runCatching {
            app.online.upgradeCandidates(songsInUse).forEach { app.downloadQueue.enqueueUpgrade(it) }
        }.onFailure { android.util.Log.w("CalmMusicViewModel", "making downloads AAC failed", it) }

        return result
    }

    suspend fun addStreamingSongToLibrary(song: SongUiModel) {
        if (song.sourceType != "YOUTUBE") return

        try {
            var songToSave = song
            if (songToSave.trackNumber == null && !songToSave.album.isNullOrBlank()) {
                val artistName = songToSave.artist
                val albumTitle = songToSave.album!!

                val tempAlbum = AlbumUiModel(
                    id = "",
                    title = albumTitle,
                    artist = artistName,
                    sourceType = "YOUTUBE",
                    releaseYear = null
                )

                val albumSongs = getOnlineAlbumSongs(tempAlbum)

                val match = albumSongs.firstOrNull {
                    areSongsMatching(it, songToSave)
                }

                if (match != null && match.trackNumber != null) {
                    songToSave = songToSave.copy(
                        trackNumber = match.trackNumber,
                        discNumber = match.discNumber ?: 1
                    )
                }
            }

            // Separate track artist vs album artist so albums can be grouped by album artist.
            val trackArtistName = songToSave.artist.takeIf { it.isNotBlank() } ?: "Unknown artist"
            val albumName = songToSave.album?.takeIf { it.isNotBlank() }

            // Try to infer a stable album artist from the existing library when possible.
            val inferredAlbumArtist = albumName?.let { albumTitle ->
                _libraryAlbums.value.firstOrNull { existing ->
                    existing.title.equals(albumTitle, ignoreCase = true)
                }?.artist
            }

            val effectiveAlbumArtist = inferredAlbumArtist?.takeIf { it.isNotBlank() } ?: trackArtistName

            withContext(Dispatchers.IO) {
                fun String.toIdComponent(): String =
                    trim()
                        .replace(Regex("\\s+"), " ")
                        .lowercase()

                val trackArtistKey = trackArtistName.toIdComponent()
                val albumArtistKey = effectiveAlbumArtist.toIdComponent()
                val albumKey = albumName?.toIdComponent()

                val existingArtists = rawArtistDao.getAllArtists()

                val existingTrackArtist = existingArtists.firstOrNull { existing ->
                    existing.name.toIdComponent() == trackArtistKey
                }
                val existingAlbumArtist = if (albumKey != null) {
                    existingArtists.firstOrNull { existing ->
                        existing.name.toIdComponent() == albumArtistKey
                    }
                } else null

                val trackArtistId = existingTrackArtist?.id ?: "YOUTUBE:$trackArtistKey"
                val albumArtistId = if (albumKey != null) {
                    existingAlbumArtist?.id ?: "YOUTUBE:$albumArtistKey"
                } else trackArtistId

                val albumId = if (albumKey != null) {
                    "YOUTUBE:$albumArtistKey:$albumKey"
                } else null

                val songEntity = SongEntity(
                    id = songToSave.id,
                    title = songToSave.title,
                    artist = trackArtistName,
                    album = albumName,
                    albumId = albumId,
                    discNumber = songToSave.discNumber,
                    trackNumber = songToSave.trackNumber,
                    durationMillis = songToSave.durationMillis,
                    sourceType = "YOUTUBE",
                    audioUri = songToSave.id,
                    artistId = trackArtistId,
                    releaseYear = null,
                    localLastModifiedMillis = null,
                    localFileSizeBytes = null,
                )

                val artistsToUpsert = mutableListOf<ArtistEntity>()
                if (existingTrackArtist == null) {
                    artistsToUpsert += ArtistEntity(
                        id = trackArtistId,
                        name = trackArtistName,
                        sourceType = "YOUTUBE",
                    )
                }
                if (albumKey != null && albumArtistId != trackArtistId && existingAlbumArtist == null) {
                    artistsToUpsert += ArtistEntity(
                        id = albumArtistId,
                        name = effectiveAlbumArtist,
                        sourceType = "YOUTUBE",
                    )
                }
                if (artistsToUpsert.isNotEmpty()) {
                    artistDao.upsertAll(artistsToUpsert)
                }

                val albumEntity = if (albumId != null && albumName != null) {
                    AlbumEntity(
                        id = albumId,
                        name = albumName,
                        artist = effectiveAlbumArtist,
                        sourceType = "YOUTUBE",
                        artistId = albumArtistId,
                    )
                } else null

                if (albumEntity != null) {
                    albumDao.upsertAll(listOf(albumEntity))
                }
                songDao.upsertAll(listOf(songEntity))
            }

            refreshLibraryFromDatabase()
        } catch (_: Exception) {
        }
    }

    /** False when the song is still there: a download whose file could not be deleted. */
    suspend fun removeSongFromLibrary(song: SongUiModel): Boolean {
        // A song this app downloaded or kept is its file: taking it out of the library and
        // leaving the file meant a download came straight back at the next launch, and a
        // kept server song left its copy in a folder nothing would ever clear.
        when (song.sourceType) {
            "YOUTUBE_DOWNLOAD" -> return deleteLocalMediaSong(song)
            com.wanderwildwood.jimeikin.data.SubsonicDownloader.SOURCE_TYPE -> {
                withContext(Dispatchers.IO) {
                    rawSongDao.getSongById(song.id)?.let { com.wanderwildwood.jimeikin.data.SubsonicDownloader.unkeep(app, it) }
                }
            }
        }
        withContext(Dispatchers.IO) {
            songDao.deleteByIds(listOf(song.id))
            database.dropOrphanedOnlineRows()
        }
        refreshLibraryFromDatabase()
        return true
    }

    /** A kept server song, taken off the phone and back to streaming. See [SubsonicDownloader.unkeep]. */
    /**
     * A whole album out of the library at once: each YouTube download's file deleted, each kept
     * server song's copy cleared, and every row gone, from playlists too, the way one song's
     * removal does it. The library is read again once at the end rather than after each song.
     * The reader's own files are never passed here. Returns how many went.
     */
    suspend fun removeSongsFromLibrary(songs: List<SongUiModel>): Int {
        val needConsent = mutableListOf<Pair<SongUiModel, Uri>>()
        val removed = withContext(Dispatchers.IO) {
            var count = 0
            for (song in songs) {
                runCatching {
                    when (song.sourceType) {
                        "LOCAL_FILE" -> return@runCatching
                        "YOUTUBE_DOWNLOAD" -> {
                            val uri = Uri.parse(song.audioUri ?: song.id)
                            if (uri.scheme == null || uri.scheme == "file") {
                                uri.path?.let { path ->
                                    val file = java.io.File(path)
                                    when (val deletion = PublicDownloads.delete(app, file)) {
                                        PublicDownloads.Deletion.Done -> Unit
                                        // Asked about all together once the rest are done.
                                        is PublicDownloads.Deletion.NeedsConsent -> {
                                            needConsent += song to deletion.uri
                                            return@runCatching
                                        }
                                        PublicDownloads.Deletion.Failed -> return@runCatching
                                    }
                                }
                            } else {
                                DocumentFile.fromSingleUri(app, uri)?.delete()
                            }
                            app.online.forgetCopy(song.id)
                        }
                        com.wanderwildwood.jimeikin.data.SubsonicDownloader.SOURCE_TYPE -> {
                            rawSongDao.getSongById(song.id)?.let { com.wanderwildwood.jimeikin.data.SubsonicDownloader.unkeep(app, it) }
                        }
                    }
                    playlistDao.deleteTracksForSongId(song.id)
                    songDao.deleteByIds(listOf(song.id))
                    count++
                }
            }
            database.dropOrphanedOnlineRows()
            count
        }
        // Downloads written before a reinstall are not this app's to delete, and Android asks
        // once for all of them. Those it is not allowed stay, file and row.
        var consented = 0
        if (needConsent.isNotEmpty()) {
            val request = PublicDownloads.consentFor(app, needConsent.map { it.second })
            if (request != null && askToDelete(request)) {
                consented = withContext(Dispatchers.IO) {
                    needConsent.count { (song, _) ->
                        val gone = PublicDownloads.fileOf(song.audioUri ?: song.id)?.exists() != true
                        if (gone) forgetDownloadRow(song)
                        gone
                    }.also { database.dropOrphanedOnlineRows() }
                }
            }
        }
        refreshLibraryFromDatabase()
        return removed + consented
    }

    private suspend fun forgetDownloadRow(song: SongUiModel) {
        playlistDao.deleteTracksForSongId(song.id)
        songDao.deleteByIds(listOf(song.id))
        app.online.forgetCopy(song.id)
    }

    /**
     * Android's question before a file another install of this app wrote can be deleted; see
     * [PublicDownloads.Deletion.NeedsConsent]. The screen shows it and answers through
     * [answerDeleteConsent].
     */
    class DeleteConsent(val request: android.content.IntentSender, internal val answer: CompletableDeferred<Boolean>)

    private val _deleteConsent = MutableStateFlow<DeleteConsent?>(null)
    val deleteConsent: StateFlow<DeleteConsent?> = _deleteConsent

    fun answerDeleteConsent(allowed: Boolean) {
        val asked = _deleteConsent.value ?: return
        _deleteConsent.value = null
        asked.answer.complete(allowed)
    }

    private suspend fun askToDelete(request: android.content.IntentSender): Boolean {
        val asked = DeleteConsent(request, CompletableDeferred())
        _deleteConsent.value?.answer?.complete(false)
        _deleteConsent.value = asked
        return asked.answer.await()
    }

    suspend fun unkeepServerSong(song: SongUiModel): Boolean {
        val done = withContext(Dispatchers.IO) {
            val row = rawSongDao.getSongById(song.id) ?: return@withContext false
            val restored = com.wanderwildwood.jimeikin.data.SubsonicDownloader.unkeep(app, row)
            if (restored != null) songDao.upsertAll(listOf(restored)) else songDao.deleteByIds(listOf(row.id))
            true
        }
        refreshLibraryFromDatabase()
        return done
    }

    /**
     * Permanently delete a LOCAL_FILE or YOUTUBE_DOWNLOAD song, including its
     * underlying file and any playlist memberships. Returns true if the
     * database was updated successfully (file deletion best-effort).
     */
    /** What the editor needs to start from: the file's own tags, or the list's if unreadable. */
    suspend fun readTagsForEditing(songs: List<SongUiModel>): TagEditor.Fields {
        val first = songs.first()
        val read = withContext(Dispatchers.IO) {
            runCatching { TagEditor.read(app, Uri.parse(first.audioUri ?: first.id)) }.getOrNull()
        }
        return read ?: TagEditor.Fields(
            title = first.title,
            artist = first.artist,
            album = first.album,
            albumArtist = null,
            trackNumber = first.trackNumber?.toString(),
        )
    }

    /**
     * Writes [fields] into each song's file, one after another. Returns how many were written
     * and what went wrong with the first that was not. The library is not touched here: a
     * rescan afterwards re-reads the changed files and regroups albums and artists.
     */
    suspend fun writeTags(
        songs: List<SongUiModel>,
        fields: TagEditor.Fields,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Pair<Int, TagEditor.Result?> = withContext(Dispatchers.IO) {
        var written = 0
        var firstFailure: TagEditor.Result? = null
        songs.forEachIndexed { index, song ->
            withContext(Dispatchers.Main) { onProgress(index + 1, songs.size) }
            val uri = Uri.parse(song.audioUri ?: song.id)
            when (val result = TagEditor.write(app, uri, fields)) {
                is TagEditor.Result.Written -> {
                    written++
                    // On the phone's own storage a file keeps its address through the swap;
                    // on a provider that does not, its playlists follow it.
                    if (result.uri.toString() != song.id) {
                        playlistDao.updateSongIdForAllPlaylists(song.id, result.uri.toString())
                    }
                }
                // The edited copy is whole under its pending name, and the rescan that
                // follows gives it the file's name back.
                TagEditor.Result.LeftPending -> written++
                else -> if (firstFailure == null) firstFailure = result
            }
        }
        written to firstFailure
    }

    /** Album row ids in the library, so a menu offers Edit name only where it has a row to name. */
    private val _albumRowIds = MutableStateFlow<Set<String>>(emptySet())
    val albumRowIds: StateFlow<Set<String>> = _albumRowIds

    /**
     * Gives a song the title [title] in this app, or gives it back the one it came with when
     * [title] is empty or is that one. Works for any song, streamed or a file: nothing is
     * written to the song itself.
     */
    suspend fun renameSong(song: SongUiModel, title: String) {
        val overrides = app.nameOverrides
        val names = overrides.current()
        val ids = com.wanderwildwood.jimeikin.data.sameSongIds(song.id, app.online.recorded.value)
        val existing = ids.firstNotNullOfOrNull { names.get(NameOverrides.SONG, it) }
        val original = existing?.originalName
            ?: withContext(Dispatchers.IO) { rawSongDao.getSongById(song.id)?.title }
            ?: song.title
        val wanted = title.trim()
        val set = if (wanted.isEmpty() || wanted == original) {
            emptyList()
        } else {
            listOf(NameOverrideEntity(NameOverrides.SONG, song.id, wanted, null, original))
        }
        withContext(Dispatchers.IO) { overrides.write(set, mapOf(NameOverrides.SONG to ids)) }
        refreshLibraryFromDatabase()
    }

    /**
     * Names an album and its artist in this app. Every album row the Albums tab shows as this
     * one is given the name, so a record held in parts stays one record. An empty field, or the
     * name a row came with, gives that row its own back.
     */
    suspend fun renameAlbum(album: AlbumUiModel, title: String, artist: String) {
        val overrides = app.nameOverrides
        withContext(Dispatchers.IO) {
            val names = overrides.current()
            val key = albumKeyer()
            val wanted = key(album.title, album.artist)
            val raw = database.albumDao().getAllAlbums().associateBy { it.id }
            val rows = albumDao.getAllAlbums().filter { it.id == album.id || key(it.name, it.artist) == wanted }
            val newTitle = title.trim()
            val newArtist = artist.trim()
            val set = mutableListOf<NameOverrideEntity>()
            val clear = mutableListOf<String>()
            rows.forEach { row ->
                val original = raw[row.id] ?: return@forEach
                val existing = names.get(NameOverrides.ALBUM, row.id)
                val name = when {
                    newTitle.isEmpty() -> null
                    newTitle == album.title -> existing?.name
                    else -> newTitle
                }?.takeUnless { it == original.name }
                val by = when {
                    newArtist.isEmpty() -> null
                    newArtist == album.artist.orEmpty() -> existing?.artist
                    else -> newArtist
                }?.takeUnless { it == original.artist }
                if (name == null && by == null) {
                    clear += row.id
                } else {
                    set += NameOverrideEntity(NameOverrides.ALBUM, row.id, name, by, null)
                }
            }
            overrides.write(set, mapOf(NameOverrides.ALBUM to clear))
        }
        refreshLibraryFromDatabase()
    }

    /** The artist rows the Artists tab shows as [artist], with their names as shown. */
    private suspend fun artistRowsOf(artist: ArtistUiModel): List<ArtistWithCounts> {
        val all = artistDao.getAllArtistsWithCounts()
        val groupOf = ArtistNames.grouping(all.map { it.name })
        val primary = all.firstOrNull { it.id == artist.id } ?: return emptyList()
        val key = groupOf(primary.name)
        return all.filter { groupOf(it.name) == key }
    }

    /**
     * Renames an artist in this app. Their songs credited to them alone are renamed with them;
     * a song crediting them with someone else keeps its credit and stays filed under them. An
     * empty name gives back every name under this artist, filings included.
     */
    suspend fun renameArtist(artist: ArtistUiModel, name: String) {
        val overrides = app.nameOverrides
        withContext(Dispatchers.IO) {
            val names = overrides.current()
            val rows = artistRowsOf(artist).ifEmpty { return@withContext }
            val ids = rows.map { it.id }
            val wanted = name.trim()
            if (wanted.isEmpty()) {
                overrides.write(emptyList(), mapOf(NameOverrides.ARTIST to ids, NameOverrides.ARTIST_UNDER to ids))
                return@withContext
            }
            if (wanted == artist.name) return@withContext
            val raw = rawArtistDao.getAllArtists().associateBy { it.id }
            val renamed = mutableListOf<NameOverrideEntity>()
            val filed = mutableListOf<NameOverrideEntity>()
            rows.forEach { row ->
                val rawName = raw[row.id]?.name ?: row.name
                if (ArtistNames.artistKey(row.name) == ArtistNames.artistKey(artist.name)) {
                    val original = names.get(NameOverrides.ARTIST, row.id)?.originalName ?: row.name
                    // Back to the name the row came with: nothing left to lay over it.
                    if (wanted == rawName && names.get(NameOverrides.ARTIST_UNDER, row.id) == null) return@forEach
                    renamed += NameOverrideEntity(NameOverrides.ARTIST, row.id, wanted, null, original)
                } else {
                    val original = names.get(NameOverrides.ARTIST_UNDER, row.id)?.originalName ?: rawName
                    filed += NameOverrideEntity(NameOverrides.ARTIST_UNDER, row.id, wanted, null, original)
                }
            }
            overrides.write(renamed + filed, mapOf(NameOverrides.ARTIST to ids))
        }
        refreshLibraryFromDatabase()
    }

    /**
     * Files [artist] under [target] in this app, as File under does for files by their tags:
     * the Artists tab shows them as one, and each song keeps its own credit.
     */
    suspend fun fileArtistUnder(artist: ArtistUiModel, target: ArtistUiModel) {
        val overrides = app.nameOverrides
        withContext(Dispatchers.IO) {
            val names = overrides.current()
            val rows = artistRowsOf(artist).ifEmpty { return@withContext }
            val raw = rawArtistDao.getAllArtists().associateBy { it.id }
            val filed = rows.map { row ->
                val original = names.get(NameOverrides.ARTIST_UNDER, row.id)?.originalName ?: raw[row.id]?.name ?: row.name
                NameOverrideEntity(NameOverrides.ARTIST_UNDER, row.id, target.name, null, original)
            }
            overrides.write(filed, mapOf(NameOverrides.ARTIST to rows.map { it.id }))
        }
        refreshLibraryFromDatabase()
    }

    suspend fun deleteLocalMediaSong(song: SongUiModel): Boolean {
        if (song.sourceType != "LOCAL_FILE" && song.sourceType != "YOUTUBE_DOWNLOAD") return false

        return try {
            val removed = withContext(Dispatchers.IO) {
                val uriString = song.audioUri ?: song.id
                if (uriString.isNotBlank()) {
                    try {
                        val uri = Uri.parse(uriString)
                        if (song.sourceType == "YOUTUBE_DOWNLOAD") {
                            // Downloads are file:// paths: in Music/Music Box, or the app's
                            // own folder for one made before 2.18.0 or on Android 9 and 10.
                            // The row goes only with the file, so a download is never left on
                            // the phone with nothing in the app to find it by.
                            if (uri.scheme == null || uri.scheme == "file") {
                                val file = uri.path?.let { java.io.File(it) }
                                val gone = when (val deletion = file?.let { PublicDownloads.delete(app, it) }) {
                                    null, PublicDownloads.Deletion.Done -> true
                                    is PublicDownloads.Deletion.NeedsConsent ->
                                        askToDelete(deletion.request) && file?.exists() != true
                                    PublicDownloads.Deletion.Failed -> false
                                }
                                if (!gone) return@withContext false
                            } else {
                                try {
                                    DocumentFile.fromSingleUri(app, uri)?.delete()
                                } catch (_: Exception) {
                                }
                            }
                        } else {
                            try {
                                DocumentFile.fromSingleUri(app, uri)?.delete()
                            } catch (_: Exception) {
                                if (uri.scheme == null || uri.scheme == "file") {
                                    uri.path?.let { path ->
                                        try {
                                            java.io.File(path).delete()
                                        } catch (_: Exception) {
                                        }
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {
                    }
                }

                // Remove from all playlists and from the songs table.
                playlistDao.deleteTracksForSongId(song.id)
                songDao.deleteByIds(listOf(song.id))
                database.dropOrphanedOnlineRows()
                true
            }
            if (!removed) return false
            if (song.sourceType == "YOUTUBE_DOWNLOAD") app.online.forgetCopy(song.id)

            refreshLibraryFromDatabase()
            true
        } catch (_: Exception) {
            false
        }
    }


    /** How the Albums tab folds album rows into one album; see [ArtistNames.albumGrouping]. */
    private suspend fun albumKeyer(): (String, String?) -> String =
        ArtistNames.albumGrouping(artistDao.getAllArtistsWithCounts().map { it.name })

    /**
     * Album rows as the Albums tab shows them: one entry for every row that folds to the same
     * title and artist. Where the rows credit different people - a record whose songs were filed
     * each under its own collaboration - the entry is under the artist they all group under,
     * not under whichever guest's row came first.
     */
    private fun mergeAlbums(
        albums: List<AlbumEntity>,
        artistNames: Collection<String>,
        albumIdToYear: Map<String, Int?>,
        songSourcesByAlbumId: Map<String, List<String>>,
    ): List<AlbumUiModel> {
        val groupOf = ArtistNames.grouping(artistNames)
        return albums
            .groupBy { ArtistNames.key(it.name) + "\u0000" + groupOf(it.artist.orEmpty()) }
            .map { (key, duplicates) ->
                val artistKey = key.substringAfter("\u0000")
                val primary = duplicates.find { it.sourceType == "LOCAL_FILE" }
                    ?: duplicates.find { ArtistNames.artistKey(it.artist.orEmpty()) == artistKey }
                    ?: duplicates.first()
                val credits = duplicates.mapNotNull { it.artist?.takeIf(String::isNotBlank) }
                val artist = if (credits.map(ArtistNames::artistKey).distinct().size > 1) {
                    ArtistNames.groupName(credits, artistKey)
                } else {
                    primary.artist
                }
                AlbumUiModel(
                    id = primary.id,
                    title = primary.name,
                    artist = artist,
                    sourceType = primary.sourceType,
                    releaseYear = duplicates.mapNotNull { albumIdToYear[it.id] }.maxOrNull(),
                    origin = originOf(duplicates.flatMap { songSourcesByAlbumId[it.id].orEmpty() }),
                )
            }
    }

    /**
     * A song held both on this phone and on a music server is one song.
     *
     * Someone who keeps their collection on a Navidrome server and also carries some of it on
     * the phone would otherwise see most of their library twice — once as a file and once as a
     * pointer to the server. The copy that survives is the one on the phone, because it plays
     * without a network; the server's copy of that same record is simply not listed, and the
     * server keeps earning its place for everything the phone does not have.
     *
     * Matched on artist, album and title with the same folding used for artist names, so a
     * record tagged a little differently on each side still meets itself.
     */
    private fun withoutServerDuplicates(songs: List<SongEntity>): List<SongEntity> {
        val onThisPhone = songs
            .asSequence()
            .filter {
                it.sourceType == "LOCAL_FILE" ||
                    it.sourceType == "YOUTUBE_DOWNLOAD" ||
                    it.sourceType == SubsonicDownloader.SOURCE_TYPE
            }
            .map { ArtistNames.songKey(it.artist, it.album, it.title) }
            .toHashSet()
        if (onThisPhone.isEmpty()) return songs
        return songs.filterNot { song ->
            song.sourceType == SubsonicSync.SOURCE_TYPE &&
                ArtistNames.songKey(song.artist, song.album, song.title) in onThisPhone
        }
    }

    suspend fun refreshLibraryFromDatabase() {
        try {
            val (allSongs, allAlbums) = withContext(Dispatchers.IO) {
                // Songs kept from the online catalogue stream from it, and an edition without
                // one leaves them out rather than list what cannot play. They stay in the
                // database, so the full edition installed again finds them where they were.
                val songsFromDb = songDao.getAllSongs().filter { app.online.available || it.sourceType != "YOUTUBE" }
                val albumsFromDb = albumDao.getAllAlbums()
                songsFromDb to albumsFromDb
            }
                .let { (songs, albums) -> withoutServerDuplicates(songs) to albums }
            val allArtistsWithCounts = withContext(Dispatchers.IO) {
                artistDao.getAllArtistsWithCounts().filter { app.online.available || it.sourceType != "YOUTUBE" }
            }

            val uniqueAlbumCounts = allAlbums
                .filter { it.artistId != null }
                .groupBy { it.artistId!! }
                .mapValues { (_, albums) ->
                    albums.groupBy {
                        ArtistNames.albumKey(it.name, it.artist)
                    }.count()
                }

            val songModels = allSongs.map { entity ->
                SongUiModel(
                    id = entity.id,
                    title = entity.title,
                    artist = entity.artist,
                    durationText = com.wanderwildwood.jimeikin.formatDurationMillis(entity.durationMillis),
                    durationMillis = entity.durationMillis,
                    trackNumber = entity.trackNumber,
                    sourceType = entity.sourceType,
                    audioUri = entity.audioUri,
                    album = entity.album,
                )
            }

            val albumIdToYear: Map<String, Int?> = allSongs
                .mapNotNull { entity ->
                    val albumId = entity.albumId ?: return@mapNotNull null
                    albumId to entity.releaseYear
                }
                .groupBy(
                    keySelector = { it.first },
                    valueTransform = { it.second },
                )
                .mapValues { (_, years) ->
                    years.filterNotNull().maxOrNull()
                }

            // An album whose songs have all gone is not an album any more. Nothing is
            // deleted here; the row simply stops being offered until a scan finds it again.
            // Worked out from the songs, not the album row: a server album kept on the
            // phone is still a server album by its row, and plays offline all the same.
            val songSourcesByAlbumId = allSongs
                .filter { it.albumId != null }
                .groupBy({ it.albumId!! }, { it.sourceType })

            val mergedAlbums = mergeAlbums(
                allAlbums.filter { albumIdToYear.containsKey(it.id) },
                allArtistsWithCounts.map { it.name },
                albumIdToYear,
                songSourcesByAlbumId,
            )

            val mergedArtists = mergeArtistsByName(allArtistsWithCounts, allSongs, allAlbums)
            _albumRowIds.value = allAlbums.mapTo(HashSet()) { it.id }

            updateLibrary(
                songs = songModels,
                albums = mergedAlbums,
                artists = mergedArtists,
            )
        } catch (_: Exception) {
        }
    }

    fun updateLibrary(
        songs: List<SongUiModel>,
        albums: List<AlbumUiModel>,
        artists: List<ArtistUiModel>,
    ) {
        _librarySongs.value = songs
        _libraryAlbums.value = albums
        _libraryArtists.value = artists
        _libraryRefreshTrigger.value += 1
    }

    /**
     * The counts a reader sees have to come from the songs a reader can see.
     *
     * These used to be summed from a query over the whole songs table, one row per source, so
     * an artist held both on a card and on a server was reported at twice their real size —
     * 110 songs and 10 albums for a Björk who has 55 and 5 — while the lists underneath,
     * which drop the server's copy of a song already on the phone, showed the true number.
     * Counting the same songs the lists are built from is the only way the two can agree.
     */
    private fun mergeArtistsByName(
        allArtistsWithCounts: List<ArtistWithCounts>,
        songs: List<SongEntity>,
        albums: List<AlbumEntity>,
    ): List<ArtistUiModel> {
        // A song reaches an artist two ways: by its own artistId, or through the album it is
        // on. The query this replaced joined on both, and counting only the first put a zero
        // beside every artist whose songs are filed under their album instead.
        val artistIdByAlbumId = albums.mapNotNull { a -> a.artistId?.let { a.id to it } }.toMap()
        val albumKey = ArtistNames.albumGrouping(allArtistsWithCounts.map { it.name })
        val albumKeyById = albums.associate { it.id to albumKey(it.name, it.artist) }
        val songsByArtistId = mutableMapOf<String, MutableList<SongEntity>>()
        songs.forEach { song ->
            val ids = setOfNotNull(song.artistId, song.albumId?.let { artistIdByAlbumId[it] })
            ids.forEach { songsByArtistId.getOrPut(it) { mutableListOf() }.add(song) }
        }

        // Collaborations ("Asake & Olamide") gather under their first artist where the library
        // says that is who they are; see ArtistNames.grouping.
        val groupOf = ArtistNames.grouping(allArtistsWithCounts.map { it.name })
        return allArtistsWithCounts
            .groupBy { groupOf(it.name) }
            .map { (key, group) ->
                val alone = group.filter { ArtistNames.artistKey(it.name) == key }.ifEmpty { group }
                val primary = alone.find { it.sourceType == "LOCAL_FILE" }
                    ?: alone.find { it.sourceType == "YOUTUBE_DOWNLOAD" }
                    ?: alone.first()

                val theirSongs = group.flatMap { songsByArtistId[it.id].orEmpty() }.distinctBy { it.id }
                val totalSongCount = theirSongs.size
                // Counted the way the Albums tab shows them: half a record tagged and half not,
                // or two disc folders, are two album rows and one album.
                val totalAlbumCount = theirSongs.mapNotNull { it.albumId }
                    .map { id -> albumKeyById[id] ?: id }
                    .distinct().size

                ArtistUiModel(
                    id = primary.id,
                    name = if (group.size == 1) primary.name else ArtistNames.groupName(group.map { it.name }, key),
                    songCount = totalSongCount,
                    albumCount = totalAlbumCount,
                    origin = originOf(theirSongs.map { it.sourceType }),
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    override fun onCleared() {
        super.onCleared()
        localPlaybackMonitorJob?.cancel()
    }

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                libraryRepository.ingestAppDownloadsIfMissing()
            }

            // The same fold the refresh does. Without it the first list drawn after launch
            // held both copies of every record that is on the card and on the server too,
            // for as long as the first refresh took - about fifteen seconds over three
            // thousand songs, which is long enough to look like the library itself is wrong.
            val allSongs = withContext(Dispatchers.IO) { withoutServerDuplicates(songDao.getAllSongs()) }
            val allAlbums = withContext(Dispatchers.IO) { albumDao.getAllAlbums() }
            val allArtistsWithCounts = withContext(Dispatchers.IO) { artistDao.getAllArtistsWithCounts() }
            val allPlaylistsWithCounts = withContext(Dispatchers.IO) { playlistDao.getAllPlaylistsWithSongCount() }

            val uniqueAlbumCounts = allAlbums
                .filter { it.artistId != null }
                .groupBy { it.artistId!! }
                .mapValues { (_, albums) ->
                    albums.groupBy {
                        ArtistNames.albumKey(it.name, it.artist)
                    }.count()
                }

            _librarySongs.value = allSongs.map { entity ->
                SongUiModel(
                    id = entity.id,
                    title = entity.title,
                    artist = entity.artist,
                    durationText = formatDurationMillis(entity.durationMillis),
                    durationMillis = entity.durationMillis,
                    trackNumber = entity.trackNumber,
                    sourceType = entity.sourceType,
                    audioUri = entity.audioUri,
                    album = entity.album,
                )
            }
            val albumIdToYear: Map<String, Int?> = allSongs
                .mapNotNull { entity ->
                    val albumId = entity.albumId ?: return@mapNotNull null
                    albumId to entity.releaseYear
                }
                .groupBy(
                    keySelector = { it.first },
                    valueTransform = { it.second },
                )
                .mapValues { (_, years) ->
                    years.filterNotNull().maxOrNull()
                }

            // Worked out from the songs, not the album row: a server album kept on the
            // phone is still a server album by its row, and plays offline all the same.
            val songSourcesByAlbumId = allSongs
                .filter { it.albumId != null }
                .groupBy({ it.albumId!! }, { it.sourceType })

            val mergedAlbums = mergeAlbums(allAlbums, allArtistsWithCounts.map { it.name }, albumIdToYear, songSourcesByAlbumId)

            _libraryAlbums.value = mergedAlbums
            _albumRowIds.value = allAlbums.mapTo(HashSet()) { it.id }

            _libraryArtists.value = mergeArtistsByName(allArtistsWithCounts, allSongs, allAlbums)
            _libraryPlaylists.value = allPlaylistsWithCounts.map { playlist ->
                PlaylistUiModel(
                    id = playlist.id,
                    name = playlist.name,
                    description = playlist.description,
                    songCount = playlist.songCount,
                )
            }

            val snapshot = withContext(Dispatchers.IO) { nowPlayingStorage.load() }
            if (snapshot != null) {
                val songsById = allSongs.associateBy { it.id }
                val queueEntities = snapshot.queueSongIds.mapNotNull { songsById[it] }
                if (queueEntities.isNotEmpty()) {
                    val playbackQueue = queueEntities.map { entity ->
                        SongUiModel(
                            id = entity.id,
                            title = entity.title,
                            artist = entity.artist,
                            durationText = formatDurationMillis(entity.durationMillis),
                            durationMillis = entity.durationMillis,
                            trackNumber = entity.trackNumber,
                            sourceType = entity.sourceType,
                            audioUri = entity.audioUri,
                            album = entity.album,
                        )
                    }

                    val indexFromSnapshot = snapshot.currentIndex
                    val effectiveIndex = indexFromSnapshot?.takeIf { it in playbackQueue.indices } ?: 0
                    val currentSong = playbackQueue[effectiveIndex]

                    val repeatMode = when (snapshot.repeatModeKey) {
                        NowPlayingRepeatModeKeys.QUEUE -> RepeatMode.QUEUE
                        NowPlayingRepeatModeKeys.ONE -> RepeatMode.ONE
                        else -> RepeatMode.OFF
                    }

                    val playbackQueueEntities = playbackQueue.map { it.toQueueEntity() }

                    _playbackState.value = PlaybackState(
                        playbackQueue = playbackQueue,
                        playbackQueueEntities = playbackQueueEntities,
                        playbackQueueIndex = effectiveIndex,
                        originalPlaybackQueue = if (snapshot.isShuffleOn) playbackQueue else emptyList(),
                        repeatMode = repeatMode,
                        isShuffleOn = snapshot.isShuffleOn,
                        currentSongId = currentSong.id,
                        nowPlayingSong = currentSong,
                        isPlaybackPlaying = snapshot.isPlaying,
                        nowPlayingPositionMs = snapshot.positionMs,
                        nowPlayingDurationMs = currentSong.durationMillis ?: 0L,
                    )
                }
            }
            _isQueueRestored.value = true

            _isLoadingSongs.value = false
            _isLoadingAlbums.value = false

            moveDownloadsToMusicFolder()
        }
    }

    /**
     * Downloads made before 2.18.0 go to Music/Music Box, once, after the library is up so
     * the first launch is not held for it; see [DownloadMove]. The private copies are deleted
     * only after the library has been read again, so no row on screen still names one.
     */
    private val songsInUse: () -> Set<String> = {
        val state = _playbackState.value
        (state.playbackQueue + state.originalPlaybackQueue).flatMapTo(HashSet()) { listOfNotNull(it.id, it.audioUri) }
    }

    /** Playing something that comes over the network: YouTube, the music server or the radio. */
    private fun isStreaming(state: PlaybackState): Boolean =
        state.isPlaybackPlaying && state.nowPlayingSong?.sourceType.let { it == "YOUTUBE" || it == "SUBSONIC" || it == "RADIO" }

    private fun moveDownloadsToMusicFolder() {
        val inUse = songsInUse
        // A YouTube download being made AAC waits for these; see DownloadUpgrade.
        app.downloadQueue.songsInUse = inUse
        app.downloadQueue.isStreaming = { isStreaming(_playbackState.value) }
        viewModelScope.launch {
            _playbackState.map(::isStreaming).distinctUntilChanged().collect { streaming ->
                if (!streaming) app.downloadQueue.nudge()
            }
        }
        viewModelScope.launch {
            runCatching {
                DownloadMove.sweep(app, inUse)
                if (DownloadMove.move(app, inUse) > 0) {
                    refreshLibraryFromDatabase()
                    DownloadMove.sweep(app, inUse)
                }
            }.onFailure { android.util.Log.w("CalmMusicViewModel", "moving downloads failed", it) }
            // Then YouTube downloads still Opus are fetched again as AAC, through the download
            // queue, behind anything asked for; see DownloadUpgrade. A replacement left
            // unfinished last time is finished first.
            runCatching {
                if (DownloadReplace.sweep(app, inUse)) refreshLibraryFromDatabase()
                app.online.upgradeCandidates(inUse).forEach { app.downloadQueue.enqueueUpgrade(it) }
            }.onFailure { android.util.Log.w("CalmMusicViewModel", "making downloads AAC failed", it) }
        }
    }

    /**
     * A download was made AAC: once the library on screen names the new file, the old one is
     * deleted and the new one takes its name; see [DownloadReplace.sweep].
     */
    fun finishUpgrades(onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching {
                refreshLibraryFromDatabase()
                if (DownloadReplace.sweep(app, songsInUse)) refreshLibraryFromDatabase()
            }.onFailure { android.util.Log.w("CalmMusicViewModel", "finishing AAC downloads failed", it) }
            onDone()
        }
    }

    companion object {
        private const val ONLINE_ALBUMS_KEEP_MS = 30 * 60 * 1000L

        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(CalmMusicViewModel::class.java)) {
                        return CalmMusicViewModel(application) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class $modelClass")
                }
            }
    }
}

data class PlaybackState(
    val playbackQueue: List<SongUiModel> = emptyList(),
    val playbackQueueEntities: List<SongEntity> = emptyList(),
    val playbackQueueIndex: Int? = null,
    val originalPlaybackQueue: List<SongUiModel> = emptyList(),
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val isShuffleOn: Boolean = false,
    val currentSongId: String? = null,
    val nowPlayingSong: SongUiModel? = null,
    val isPlaybackPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val nowPlayingPositionMs: Long = 0L,
    val nowPlayingDurationMs: Long = 0L,
)