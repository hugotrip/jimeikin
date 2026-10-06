package com.wanderwildwood.jimeikin

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.wanderwildwood.jimeikin.data.CalmMusicDatabase
import com.wanderwildwood.jimeikin.data.PlaylistManager
import com.wanderwildwood.jimeikin.data.PlaylistTrackEntity
import com.wanderwildwood.jimeikin.data.SongEntity
import com.wanderwildwood.jimeikin.ui.PlaylistUiModel
import com.wanderwildwood.jimeikin.ui.SongUiModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dedicated ViewModel for playlist-related state and operations. This pulls
 * playlist concerns out of CalmMusicViewModel/MainActivity while preserving
 * existing behavior.
 */
class PlaylistsViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private val app: CalmMusic
        get() = getApplication() as CalmMusic

    private val database: CalmMusicDatabase by lazy { CalmMusicDatabase.getDatabase(app) }
    private val songDao by lazy { database.songDao() }
    private val playlistDao by lazy { database.playlistDao() }
    private val playlistManager: PlaylistManager by lazy { PlaylistManager(songDao, playlistDao) }

    private val _playlists = MutableStateFlow<List<PlaylistUiModel>>(emptyList())
    val playlists: StateFlow<List<PlaylistUiModel>> = _playlists

    /**
     * Bumped whenever the songs in a playlist change. A details screen already on that
     * playlist has no reason of its own to look again - its playlist id has not changed -
     * so removals used to stay on screen until the reader navigated away and back.
     */
    private val _songsRefreshTrigger = MutableStateFlow(0)
    val songsRefreshTrigger: StateFlow<Int> = _songsRefreshTrigger

    /** An open playlist reads its songs again: a download has changed where some of them live. */
    fun refreshSongs() {
        _songsRefreshTrigger.value += 1
    }

    private suspend fun loadPlaylistsFromDb(): List<PlaylistUiModel> {
        val allPlaylistsWithCounts = playlistDao.getAllPlaylistsWithSongCount()
        return allPlaylistsWithCounts.map { playlist ->
            PlaylistUiModel(
                id = playlist.id,
                name = playlist.name,
                description = playlist.description,
                songCount = playlist.songCount,
            )
        }
    }

    suspend fun refreshPlaylists(): List<PlaylistUiModel> {
        return withContext(Dispatchers.IO) {
            val updated = loadPlaylistsFromDb()
            _playlists.value = updated
            updated
        }
    }

    data class AddSongsToPlaylistResult(
        val addedCount: Int,
        val totalSongCount: Int,
        val allSelectedAlreadyPresent: Boolean,
    )

    data class EditPlaylistParams(
        val playlistId: String?, // null = new
        val name: String,
        val description: String? = null,
        val songsToAdd: List<SongUiModel> = emptyList(),
    )

    data class EditPlaylistResult(
        val playlistId: String,
        val songCount: Int,
    )

    suspend fun getPlaylistSongs(playlistId: String): List<SongUiModel> {
        return withContext(Dispatchers.IO) {
            val entities = playlistDao.getSongsForPlaylist(playlistId)
            entities.map { entity ->
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
    }

    suspend fun updatePlaylistOrder(playlistId: String, songs: List<SongUiModel>) {
        withContext(Dispatchers.IO) {
            songs.forEachIndexed { index, song ->
                playlistDao.updateTrackPosition(playlistId, song.id, index)
            }
        }
    }

    suspend fun addSongToPlaylist(
        song: SongUiModel,
        playlist: PlaylistUiModel,
    ): PlaylistManager.AddSongResult {
        return withContext(Dispatchers.IO) {
            playlistManager.addSongToPlaylist(song, playlist.id)
        }
    }

    suspend fun addSongsToPlaylist(
        playlistId: String,
        selectedSongIds: Set<String>,
    ): AddSongsToPlaylistResult {
        return withContext(Dispatchers.IO) {
            val existingEntities = playlistDao.getSongsForPlaylist(playlistId)
            val existingIds = existingEntities.map { it.id }.toSet()

            // For now, build SongModels from DB directly.
            val allSongs = songDao.getAllSongs()
            val currentSongs = allSongs.map { entity ->
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

            val songsToAdd = currentSongs.filter { it.id in selectedSongIds && it.id !in existingIds }

            if (songsToAdd.isEmpty()) {
                AddSongsToPlaylistResult(
                    addedCount = 0,
                    totalSongCount = existingEntities.size,
                    allSelectedAlreadyPresent = true,
                )
            } else {
                val songEntities = songsToAdd.map { song ->
                    SongEntity(
                        id = song.id,
                        title = song.title,
                        artist = song.artist,
                        album = null,
                        albumId = null,
                        discNumber = null,
                        trackNumber = song.trackNumber,
                        durationMillis = song.durationMillis,
                        sourceType = song.sourceType,
                        audioUri = song.audioUri ?: song.id,
                        artistId = null,
                        releaseYear = null,
                    )
                }
                songDao.insertIfAbsent(songEntities)

                val startingPosition = playlistDao.nextTrackPosition(playlistId)
                val tracks = songsToAdd.mapIndexed { index, song ->
                    PlaylistTrackEntity(
                        playlistId = playlistId,
                        songId = song.id,
                        position = startingPosition + index,
                    )
                }
                playlistDao.upsertTracks(tracks)

                val newEntities = playlistDao.getSongsForPlaylist(playlistId)
                _songsRefreshTrigger.value += 1
                AddSongsToPlaylistResult(
                    addedCount = songsToAdd.size,
                    totalSongCount = newEntities.size,
                    allSelectedAlreadyPresent = false,
                )
            }
        }
    }

    /**
     * Songs onto the end of a playlist in the order given - an album's running order, or the
     * queue's. Unlike [addSongsToPlaylist] these need not be in the library already: a queue
     * holds YouTube songs streamed from a search, and they are added the way one song from
     * Now Playing always was.
     */
    suspend fun addSongsInOrder(
        playlistId: String,
        songs: List<SongUiModel>,
    ): AddSongsToPlaylistResult {
        return withContext(Dispatchers.IO) {
            val added = appendSongs(playlistId, songs)
            val total = playlistDao.getSongsForPlaylist(playlistId).size
            _songsRefreshTrigger.value += 1
            AddSongsToPlaylistResult(
                addedCount = added,
                totalSongCount = total,
                allSelectedAlreadyPresent = added == 0,
            )
        }
    }

    /** Returns how many went in; a song the playlist has already, or twice over, goes in once. */
    private suspend fun appendSongs(playlistId: String, songs: List<SongUiModel>): Int {
        val existingIds = playlistDao.getSongsForPlaylist(playlistId).map { it.id }.toSet()
        val toAdd = songs.distinctBy { it.id }.filter { it.id !in existingIds }
        if (toAdd.isEmpty()) return 0

        songDao.insertIfAbsent(
            toAdd.map { song ->
                SongEntity(
                    id = song.id,
                    title = song.title,
                    artist = song.artist,
                    album = null,
                    albumId = null,
                    discNumber = null,
                    trackNumber = song.trackNumber,
                    durationMillis = song.durationMillis,
                    sourceType = song.sourceType,
                    audioUri = song.audioUri ?: song.id,
                    artistId = null,
                    releaseYear = null,
                )
            }
        )
        val start = playlistDao.nextTrackPosition(playlistId)
        playlistDao.upsertTracks(
            toAdd.mapIndexed { offset, song ->
                PlaylistTrackEntity(playlistId = playlistId, songId = song.id, position = start + offset)
            }
        )
        return toAdd.size
    }

    suspend fun removeSongsFromPlaylist(
        playlistId: String,
        songIds: Set<String>,
    ): Int {
        return withContext(Dispatchers.IO) {
            if (songIds.isEmpty()) {
                return@withContext playlistDao.getSongsForPlaylist(playlistId).size
            }
            playlistDao.deleteTracksForPlaylistAndSongIds(
                playlistId = playlistId,
                songIds = songIds.toList(),
            )
            val songs = playlistDao.getSongsForPlaylist(playlistId)
            _songsRefreshTrigger.value += 1
            songs.size
        }
    }

    suspend fun createOrUpdatePlaylist(params: EditPlaylistParams): EditPlaylistResult {
        return withContext(Dispatchers.IO) {
            val playlistId = params.playlistId ?: "LOCAL_PLAYLIST:" + java.util.UUID.randomUUID().toString()

            if (params.playlistId != null) {
                // Update existing playlist metadata
                playlistDao.updatePlaylistMetadata(
                    id = playlistId,
                    name = params.name,
                    description = params.description,
                )
            } else {
                // Create a new playlist
                val entity = com.wanderwildwood.jimeikin.data.PlaylistEntity(
                    id = playlistId,
                    name = params.name,
                    description = params.description,
                )
                playlistDao.upsertPlaylist(entity)
            }

            // The songs a new playlist was made for: one from Now Playing, or an album, an
            // artist or the queue from the add-to-playlist sheet.
            if (params.songsToAdd.isNotEmpty()) {
                appendSongs(playlistId, params.songsToAdd)
            }

            val updatedSongs = playlistDao.getSongsForPlaylist(playlistId)
            EditPlaylistResult(
                playlistId = playlistId,
                songCount = updatedSongs.size,
            )
        }
    }

    suspend fun deletePlaylists(playlistsToDelete: List<PlaylistUiModel>): List<PlaylistUiModel> {
        return withContext(Dispatchers.IO) {
            playlistsToDelete.forEach { playlist ->
                playlistDao.deleteTracksForPlaylist(playlist.id)
                val entity = com.wanderwildwood.jimeikin.data.PlaylistEntity(
                    id = playlist.id,
                    name = playlist.name,
                    description = playlist.description,
                )
                playlistDao.deletePlaylist(entity)
            }
            val remaining = loadPlaylistsFromDb()
            _playlists.value = remaining
            remaining
        }
    }

    init {
        // Initial load of playlists
        viewModelScope.launch(Dispatchers.IO) {
            val updated = loadPlaylistsFromDb()
            _playlists.value = updated
        }
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(PlaylistsViewModel::class.java)) {
                        return PlaylistsViewModel(application) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class $modelClass")
                }
            }
    }
}
