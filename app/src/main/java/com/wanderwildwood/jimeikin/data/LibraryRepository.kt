package com.wanderwildwood.jimeikin.data

import android.net.Uri
import android.os.Environment
import com.wanderwildwood.jimeikin.CalmMusic
import com.wanderwildwood.jimeikin.ui.AlbumUiModel
import com.wanderwildwood.jimeikin.ui.ArtistUiModel
import com.wanderwildwood.jimeikin.ui.SongUiModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository responsible for performing library-related data work against the
 * local Room database and filesystem scanners.
 */
class LibraryRepository(
    private val app: CalmMusic,
) {

    private val database: CalmMusicDatabase by lazy { CalmMusicDatabase.getDatabase(app) }
    private val songDao by lazy { database.songDao() }
    private val albumDao by lazy { database.albumDao() }
    private val artistDao by lazy { database.artistDao() }
    private val playlistDao by lazy { database.playlistDao() }

    data class LocalResyncStats(
        val totalDiscovered: Int,
        val skippedUnchanged: Int,
        val indexedNewOrUpdated: Int,
        val deletedMissing: Int,
        val unreadableFolders: Int = 0,
    )

    data class LocalResyncResult(
        val errorMessage: String?,
        val stats: LocalResyncStats? = null,
    )

    suspend fun resyncLocalLibrary(
        folders: Set<String>,
        onScanProgress: (Float) -> Unit,
        onIngestProgress: (Float) -> Unit,
    ): LocalResyncResult {
        var error: String? = null
        var stats: LocalResyncStats? = null

        try {
            run {
                if (folders.isNotEmpty()) {
                    try {
                        val lastScanMillis = app.settingsManager.getLastLocalLibraryScanMillis()

                        val existingAlbumsMap = withContext(Dispatchers.IO) {
                            albumDao.getAllAlbums()
                                .filter { it.sourceType == "LOCAL_FILE" }
                                .associateBy { it.id }
                        }

                        val (scannedAudio, existingLocalSongs) = withContext(Dispatchers.IO) {
                            val existingLocalSongs = songDao.getSongsBySourceType("LOCAL_FILE")
                            val existingByUri = existingLocalSongs.associateBy { it.audioUri }
                            val scanned = LocalMusicScanner.scanFolders(
                                context = app,
                                folderUris = folders,
                                existingSongsByUri = existingByUri,
                                lastScanMillis = lastScanMillis,
                            ) { processed, total ->
                                val progress = if (total > 0) {
                                    (processed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                                } else {
                                    1f
                                }
                                onScanProgress(progress)
                            }
                            scanned to existingLocalSongs
                        }

                        onIngestProgress(0f)

                        // A folder that could not be read this time says nothing about whether
                        // its songs are still there. Until it can be read again, nothing under
                        // it is deleted - a card that was slow to mount used to empty the
                        // whole library.
                        val hadUnreadableFolder = scannedAudio.unreadableTreeUris.isNotEmpty()
                        val normalizedLocalEntities = scannedAudio.audio.map { it.song }

                        withContext(Dispatchers.IO) {
                            val artistEntities = mutableListOf<ArtistEntity>()

                            fun String.normalize() = trim().replace(Regex("\\s+"), " ").lowercase()

                            // The album artist is the one a reader means by "artist": an album
                            // tagged Gorillaz stays one row however many guests are credited on
                            // its tracks. Album artists are collected first so that where both
                            // exist it is the album artist's own spelling that names the row.
                            val albumIdsWithAlbumArtist = mutableSetOf<String>()
                            scannedAudio.audio.forEach { wrapper ->
                                val entity = wrapper.song
                                val explicit = wrapper.albumArtist

                                // If explicit is missing (unchanged file), check our preserved map
                                val effectiveAlbumArtist = explicit?.takeIf { it.isNotBlank() }
                                    ?: entity.albumId?.let { existingAlbumsMap[it]?.artist }

                                if (!effectiveAlbumArtist.isNullOrBlank()) {
                                    val id = "LOCAL_FILE:" + effectiveAlbumArtist.normalize()
                                    artistEntities.add(ArtistEntity(id, effectiveAlbumArtist, wrapper.song.sourceType))
                                    entity.albumId?.let { albumIdsWithAlbumArtist.add(it) }
                                }
                            }

                            // A track artist earns a row of its own only where nothing on the
                            // album said who the album is by.
                            normalizedLocalEntities.forEach { entity ->
                                if (entity.albumId != null && entity.albumId in albumIdsWithAlbumArtist) return@forEach
                                val id = entity.artistId ?: return@forEach
                                val name = entity.artist.takeIf { it.isNotBlank() } ?: id.removePrefix("LOCAL_FILE:")
                                artistEntities.add(ArtistEntity(id, name, entity.sourceType))
                            }

                            val uniqueArtists = artistEntities.distinctBy { it.id }

                            onIngestProgress(0.1f)

                            val albumEntities: List<AlbumEntity> = scannedAudio.audio
                                .mapNotNull { wrapper ->
                                    val entity = wrapper.song
                                    val id = entity.albumId ?: return@mapNotNull null
                                    val name = entity.album ?: return@mapNotNull null

                                    val artistName = wrapper.albumArtist?.takeIf { it.isNotBlank() }
                                        ?: existingAlbumsMap[id]?.artist
                                        ?: entity.artist

                                    val albumArtistId = "LOCAL_FILE:" + artistName.normalize()

                                    id to AlbumEntity(
                                        id = id,
                                        name = name,
                                        artist = artistName,
                                        sourceType = entity.sourceType,
                                        artistId = albumArtistId,
                                    )
                                }
                                .distinctBy { it.first }
                                .map { it.second }

                            onIngestProgress(0.2f)

                            if (!hadUnreadableFolder &&
                                normalizedLocalEntities.isEmpty() && albumEntities.isEmpty() && uniqueArtists.isEmpty()
                            ) {
                                onIngestProgress(1f)
                                songDao.deleteBySourceType("LOCAL_FILE")
                                albumDao.deleteBySourceType("LOCAL_FILE")
                                artistDao.deleteBySourceType("LOCAL_FILE")
                                return@withContext
                            }

                            val existingById = existingLocalSongs.associateBy { it.id }
                            val scannedById = normalizedLocalEntities.associateBy { it.id }

                            val songsToDelete = if (hadUnreadableFolder) {
                                emptySet()
                            } else {
                                existingById.keys - scannedById.keys
                            }
                            val songsToUpsert = scannedById.values.filter { newEntity ->
                                val existing = existingById[newEntity.id]
                                existing == null || existing != newEntity
                            }

                            val totalDiscovered = normalizedLocalEntities.size
                            val indexedNewOrUpdated = songsToUpsert.size
                            val skippedUnchanged = (totalDiscovered - indexedNewOrUpdated).coerceAtLeast(0)
                            val deletedMissing = songsToDelete.size
                            stats = LocalResyncStats(
                                totalDiscovered = totalDiscovered,
                                skippedUnchanged = skippedUnchanged,
                                indexedNewOrUpdated = indexedNewOrUpdated,
                                deletedMissing = deletedMissing,
                                unreadableFolders = scannedAudio.unreadableTreeUris.size,
                            )

                            val totalWriteItems = songsToDelete.size + songsToUpsert.size + albumEntities.size + uniqueArtists.size
                            var writtenItems = 0

                            fun reportWriteProgress() {
                                if (totalWriteItems <= 0) return
                                val writeFraction = (writtenItems.toFloat() / totalWriteItems.toFloat()).coerceIn(0f, 1f)
                                val progress = 0.2f + 0.8f * writeFraction
                                onIngestProgress(progress.coerceIn(0f, 1f))
                            }

                            if (songsToDelete.isNotEmpty()) {
                                songDao.deleteByIds(songsToDelete.toList())
                                writtenItems += songsToDelete.size
                                reportWriteProgress()
                            }

                            if (songsToUpsert.isNotEmpty()) {
                                val chunkSize = 100.coerceAtMost(songsToUpsert.size)
                                songsToUpsert.chunked(chunkSize).forEach { chunk ->
                                    songDao.upsertAll(chunk)
                                    writtenItems += chunk.size
                                    reportWriteProgress()
                                }
                            }

                            // Playlists written on a computer, read after the songs they name
                            // are in the library.
                            M3uImporter.importAll(
                                context = app,
                                playlistFiles = scannedAudio.playlistFiles,
                                relativePathBySongId = scannedAudio.relativePathBySongId,
                                playlistDao = playlistDao,
                            )

                            // Albums and artists are rebuilt from what was found, so they can
                            // only be cleared when everything was read.
                            if (!hadUnreadableFolder) {
                                albumDao.deleteBySourceType("LOCAL_FILE")
                                artistDao.deleteBySourceType("LOCAL_FILE")
                            }

                            if (albumEntities.isNotEmpty()) {
                                albumDao.upsertAll(albumEntities)
                                writtenItems += albumEntities.size
                                reportWriteProgress()
                            }
                            if (uniqueArtists.isNotEmpty()) {
                                artistDao.upsertAll(uniqueArtists)
                                writtenItems += uniqueArtists.size
                                reportWriteProgress()
                            }

                            onIngestProgress(1f)
                        }
                    } catch (e: Exception) {
                        error = e.message ?: "Failed to scan local music"
                    }
                } else {
                    withContext(Dispatchers.IO) {
                        songDao.deleteBySourceType("LOCAL_FILE")
                        albumDao.deleteBySourceType("LOCAL_FILE")
                        artistDao.deleteBySourceType("LOCAL_FILE")
                    }
                }
            }

            // The caller refreshes from the database itself; building UI models here meant
            // reading the whole library a second time at the end of every scan.
            app.settingsManager.updateLastLocalLibraryScanMillis(System.currentTimeMillis())

            return LocalResyncResult(
                errorMessage = error,
                stats = stats,
            )
        } catch (e: Exception) {
            val message = e.message ?: "Failed to scan local music"
            return LocalResyncResult(
                errorMessage = message,
                stats = null,
            )
        }
    }

    suspend fun ingestAppDownloadsIfMissing(): Int {
        return withContext(Dispatchers.IO) {
            val downloadsDir = app.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: return@withContext 0
            val files = downloadsDir.listFiles()?.filter { it.isFile } ?: emptyList()
            if (files.isEmpty()) return@withContext 0

            val existingDownloads = songDao.getSongsBySourceType("YOUTUBE_DOWNLOAD")
            val existingByUri = existingDownloads.associateBy { it.audioUri }

            val toInsert = mutableListOf<SongEntity>()

            for (file in files) {
                val uri = Uri.fromFile(file)
                val uriString = uri.toString()
                if (existingByUri.containsKey(uriString)) continue

                val scanned = LocalMusicScanner.buildSongEntityFromFile(
                    context = app,
                    uri = uri,
                    name = file.name,
                    lastModified = file.lastModified(),
                    fileSize = file.length(),
                    existing = null,
                )
                toInsert += scanned.song.copy(sourceType = "YOUTUBE_DOWNLOAD")
            }

            if (toInsert.isNotEmpty()) {
                songDao.upsertAll(toInsert)
            }

            toInsert.size
        }
    }
}