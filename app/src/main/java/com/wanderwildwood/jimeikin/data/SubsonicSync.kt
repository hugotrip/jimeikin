package com.wanderwildwood.jimeikin.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Brings a music server's library into the same tables as the music on the card.
 *
 * The songs go in as ordinary rows with `sourceType = "SUBSONIC"`, which is what makes them
 * appear on the Songs, Albums and Artists screens beside everything else without any of
 * those screens knowing a server exists. What separates them is the one thing a reader needs
 * to know — a server song needs the network — and the row says that the same way a YouTube
 * result does: with a dotted rule under it.
 *
 * The stream url is stored on the row rather than resolved at play time, so the queue and
 * the playback coordinator treat a server song exactly like a file. Re-entering the password
 * rewrites them all on the next sync.
 */
object SubsonicSync {

    const val SOURCE_TYPE = "SUBSONIC"

    /** Server ids are short and could collide with a document id, so they are namespaced. */
    private fun songId(id: String) = "SUBSONIC:$id"
    private fun albumId(id: String) = "SUBSONIC:ALBUM:$id"
    private fun artistId(name: String) =
        "SUBSONIC:ARTIST:" + name.trim().replace(Regex("\\s+"), " ").lowercase()

    suspend fun sync(
        client: SubsonicClient,
        songDao: SongDao,
        albumDao: AlbumDao,
        artistDao: ArtistDao,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): SubsonicResult<Int> {
        val library = when (val r = client.fetchLibrary(onProgress)) {
            is SubsonicResult.Failure -> return r
            is SubsonicResult.Success -> r.value
        }

        val songs = library.songs.map { song ->
            SongEntity(
                id = songId(song.id),
                title = song.title,
                artist = song.artist,
                album = song.album,
                albumId = song.albumId?.let { albumId(it) },
                discNumber = song.discNumber,
                trackNumber = song.trackNumber,
                durationMillis = song.durationMillis,
                sourceType = SOURCE_TYPE,
                audioUri = client.streamUrl(song.id),
                // Filed under the album artist, the same rule the card library follows, so a
                // compilation stays one artist rather than one per track.
                artistId = library.albums.firstOrNull { it.id == song.albumId }
                    ?.let { artistId(it.artist) }
                    ?: artistId(song.artist),
                releaseYear = song.year,
            )
        }

        val albums = library.albums.map { album ->
            AlbumEntity(
                id = albumId(album.id),
                name = album.name,
                artist = album.artist,
                sourceType = SOURCE_TYPE,
                artistId = artistId(album.artist),
            )
        }

        val artists = library.albums
            .map { it.artist }
            .distinctBy { artistId(it) }
            .map { name -> ArtistEntity(artistId(name), name, SOURCE_TYPE) }

        withContext(Dispatchers.IO) {
            // Replaced whole rather than merged: the server is the authority on what it has,
            // and anything it no longer lists is gone rather than merely unseen. Nothing on
            // the phone is touched by this - these rows are only ever pointers to the server.
            songDao.deleteBySourceType(SOURCE_TYPE)
            albumDao.deleteBySourceType(SOURCE_TYPE)
            artistDao.deleteBySourceType(SOURCE_TYPE)

            if (songs.isNotEmpty()) {
                songs.chunked(100).forEach { songDao.upsertAll(it) }
            }
            if (albums.isNotEmpty()) albumDao.upsertAll(albums)
            if (artists.isNotEmpty()) artistDao.upsertAll(artists)
        }

        return SubsonicResult.Success(songs.size)
    }

    /** Everything the server put here, taken back out. The server itself is untouched. */
    suspend fun forget(
        songDao: SongDao,
        albumDao: AlbumDao,
        artistDao: ArtistDao,
    ) = withContext(Dispatchers.IO) {
        songDao.deleteBySourceType(SOURCE_TYPE)
        albumDao.deleteBySourceType(SOURCE_TYPE)
        artistDao.deleteBySourceType(SOURCE_TYPE)
    }
}
