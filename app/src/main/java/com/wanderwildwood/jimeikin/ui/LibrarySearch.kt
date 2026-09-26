package com.wanderwildwood.jimeikin.ui

/**
 * Search, over the library as well as YouTube.
 *
 * Every tab lists the phone's copies first, then the server's, then YouTube's, because that
 * is the order a copy is worth having in: one that plays with no signal, one that needs only
 * the house, one that needs the internet. A song held in all three places is listed three
 * times, in that order, each with its mark - the reader chooses, and the top one is always
 * the one that will still play on a train.
 */
private fun placeRank(sourceType: String): Int = when (sourceType) {
    "LOCAL_FILE", "YOUTUBE_DOWNLOAD", "SUBSONIC_DOWNLOAD" -> 0
    "SUBSONIC" -> 1
    else -> 2
}

private fun placeRank(source: StreamSource?): Int = when (source) {
    null -> 0
    StreamSource.SERVER -> 1
    StreamSource.YOUTUBE -> 2
}

/** Within a place, a match on the name comes before a match on who made it or where it is. */
private fun String?.has(query: String) = this?.contains(query, ignoreCase = true) == true

fun searchLibrarySongs(songs: List<SongUiModel>, query: String): List<SongUiModel> =
    songs.mapNotNull { song ->
        when {
            song.title.has(query) -> song to 0
            song.artist.has(query) -> song to 1
            song.album.has(query) -> song to 2
            else -> null
        }
    }
        .sortedWith(compareBy({ placeRank(it.first.sourceType) }, { it.second }))
        .map { it.first }

fun searchLibraryAlbums(albums: List<AlbumUiModel>, query: String): List<AlbumUiModel> =
    albums.mapNotNull { album ->
        when {
            album.title.has(query) -> album to 0
            album.artist.has(query) -> album to 1
            else -> null
        }
    }
        .sortedWith(compareBy({ placeRank(it.first.streamsFrom) }, { it.second }))
        .map { it.first }

fun searchLibraryArtists(artists: List<ArtistUiModel>, query: String): List<ArtistUiModel> =
    artists.filter { it.name.has(query) }.sortedBy { placeRank(it.streamsFrom) }

/**
 * The library's matches, then YouTube's. A YouTube song already saved to the library is the
 * same item twice, so it is listed once, where the library put it.
 */
fun <T> libraryThenYouTube(library: List<T>, youTube: List<T>, id: (T) -> String): List<T> {
    val seen = library.mapTo(HashSet()) { id(it) }
    return library + youTube.filter { id(it) !in seen }
}
