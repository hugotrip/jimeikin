package com.wanderwildwood.jimeikin.data

/**
 * The library's tables as the reader has named things: every read goes through [NameOverrides]
 * on its way out, so a list, a page and a search all see the same names, and the grouping that
 * folds albums and artists together works on them too.
 *
 * Writes pass straight through. A row read here and written back would carry the reader's name
 * into the table, so anything that reads a row to change it uses the plain DAO instead.
 */
class NamedSongDao(private val dao: SongDao, private val overrides: NameOverrides) : SongDao by dao {
    private suspend fun List<SongEntity>.named(): List<SongEntity> =
        overrides.current().let { names -> if (names.isEmpty()) this else map(names::song) }

    // Sorted again by the names shown, as the query sorts by the stored ones (NOCASE).
    override suspend fun getAllSongs() = overrides.current().let { names ->
        if (names.isEmpty()) dao.getAllSongs() else dao.getAllSongs().map(names::song).sortedBy { it.title.lowercase() }
    }
    override suspend fun getSongsBySourceType(sourceType: String) = dao.getSongsBySourceType(sourceType).named()
    override suspend fun getSongsByAlbumId(albumId: String) = dao.getSongsByAlbumId(albumId).named()
    override suspend fun getSongById(id: String) = dao.getSongById(id)?.let { overrides.current().song(it) }
    override suspend fun getSongsByArtistId(artistId: String) = dao.getSongsByArtistId(artistId).named()
}

class NamedAlbumDao(private val dao: AlbumDao, private val overrides: NameOverrides) : AlbumDao by dao {
    private suspend fun List<AlbumEntity>.named(): List<AlbumEntity> =
        overrides.current().let { names -> if (names.isEmpty()) this else map(names::album) }

    override suspend fun getAllAlbums() = overrides.current().let { names ->
        if (names.isEmpty()) dao.getAllAlbums() else dao.getAllAlbums().map(names::album).sortedBy { it.name.lowercase() }
    }
    override suspend fun getAlbumsByArtistId(artistId: String) = dao.getAlbumsByArtistId(artistId).named()
}

class NamedArtistDao(private val dao: ArtistDao, private val overrides: NameOverrides) : ArtistDao by dao {
    override suspend fun getAllArtists(): List<ArtistEntity> =
        overrides.current().let { names ->
            if (names.isEmpty()) dao.getAllArtists() else dao.getAllArtists().map { names.artist(it) }.sortedBy { it.name.lowercase() }
        }

    override suspend fun getAllArtistsWithCounts(): List<ArtistWithCounts> =
        overrides.current().let { names ->
            if (names.isEmpty()) dao.getAllArtistsWithCounts()
            else dao.getAllArtistsWithCounts().map { names.artist(it) }.sortedBy { it.name.lowercase() }
        }
}
