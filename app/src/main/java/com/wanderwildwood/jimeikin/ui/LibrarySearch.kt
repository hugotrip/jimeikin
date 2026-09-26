package com.wanderwildwood.jimeikin.ui

import com.wanderwildwood.jimeikin.data.ArtistNames

/** One row of search results: a song, an album, or an artist from the library or YouTube. */
sealed class SearchResult {
    data class Song(val song: SongUiModel) : SearchResult()
    data class Album(val album: AlbumUiModel) : SearchResult()
    data class LibraryArtist(val artist: ArtistUiModel) : SearchResult()
    data class YouTubeArtist(val artist: YoutubeArtistUiModel) : SearchResult()
}

/**
 * Search results, as one list.
 *
 * Songs, albums and artists are interwoven rather than put behind tabs, ordered first by where
 * they are - the phone, then the server, then YouTube, which is the order a copy is worth
 * having in - and then by how well they match. Each song, album or artist appears once, as
 * its best copy: a song on the phone is not listed again from the server or from YouTube,
 * and one on the server is not listed again from YouTube.
 */
fun buildSearchResults(
    query: String,
    librarySongs: List<SongUiModel>,
    libraryAlbums: List<AlbumUiModel>,
    libraryArtists: List<ArtistUiModel>,
    youTubeSongs: List<SongUiModel>,
    youTubeAlbums: List<AlbumUiModel>,
    youTubeArtists: List<YoutubeArtistUiModel>,
): List<SearchResult> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()

    class Candidate(
        val result: SearchResult,
        val key: String,
        val place: Int,
        val match: Int,
        val kind: Int,
        val index: Int,
    )

    val candidates = mutableListOf<Candidate>()
    fun add(result: SearchResult, key: String, place: Int, match: Int?, kind: Int) {
        // A library item that does not match is not a result.
        val m = match ?: return
        candidates += Candidate(result, key, place, m, kind, candidates.size)
    }

    librarySongs.forEach { s ->
        add(
            SearchResult.Song(s), songKey(s), placeOf(originOf(s.sourceType)),
            match(q, s.title, s.artist, s.album), KIND_SONG,
        )
    }
    libraryAlbums.forEach { a ->
        add(SearchResult.Album(a), albumKey(a), placeOf(a.origin), match(q, a.title, a.artist), KIND_ALBUM)
    }
    libraryArtists.forEach { a ->
        add(SearchResult.LibraryArtist(a), artistKey(a.name), placeOf(a.origin), match(q, a.name), KIND_ARTIST)
    }
    // YouTube has already ranked its own results, and knows better than a name match which
    // of forty albums called Moondance is the one meant. Its order is kept, the three lists
    // taken a turn at a time - a song, an album, an artist - with an artist whose name is
    // exactly what was typed put first.
    fun youTubeTurn(index: Int, exact: Boolean) = if (exact) -1 else index
    youTubeSongs.forEachIndexed { i, s ->
        add(SearchResult.Song(s), songKey(s), PLACE_YOUTUBE, youTubeTurn(i, false), KIND_SONG)
    }
    youTubeAlbums.forEachIndexed { i, a ->
        add(SearchResult.Album(a), albumKey(a), PLACE_YOUTUBE, youTubeTurn(i, false), KIND_ALBUM_YT)
    }
    // An artist is offered only when the name is what was searched for. YouTube answers
    // "moondance" with whoever has a song of that name, and a row that says only "Sam Smith,
    // Artist" is not an answer to it.
    youTubeArtists.forEachIndexed { i, a ->
        val m = match(q, a.name) ?: return@forEachIndexed
        add(SearchResult.YouTubeArtist(a), artistKey(a.name), PLACE_YOUTUBE, youTubeTurn(i, m == 0), KIND_ARTIST_YT)
    }

    return candidates
        .sortedWith(compareBy({ it.place }, { it.match }, { it.kind }, { it.index }))
        // Sorted best-first, so the copy kept is the best one there is.
        .distinctBy { it.key }
        .map { it.result }
}

private const val PLACE_PHONE = 0
private const val PLACE_SERVER = 1
private const val PLACE_YOUTUBE = 2

// In the library a match on an artist leads, then an album, then a song. In YouTube's part
// a song leads each turn: it is what a search there is usually for.
private const val KIND_ARTIST = 0
private const val KIND_ALBUM = 1
private const val KIND_SONG = 2
private const val KIND_ALBUM_YT = 3
private const val KIND_ARTIST_YT = 4

private fun placeOf(origin: Origin?): Int = when (origin) {
    Origin.SERVER -> PLACE_SERVER
    Origin.YOUTUBE -> PLACE_YOUTUBE
    // A download is on the phone: it plays with no signal, like a file put there.
    else -> PLACE_PHONE
}

/**
 * How well a name answers the query: the name itself, exactly, then starting with it, then
 * containing it; then one of the other fields - an artist or an album - containing it.
 */
private fun match(query: String, name: String, vararg others: String?): Int? {
    val n = ArtistNames.key(name)
    val k = ArtistNames.key(query)
    return when {
        k.isEmpty() -> if (name.contains(query, ignoreCase = true)) 2 else null
        n == k -> 0
        n.startsWith(k) -> 1
        n.contains(k) -> 2
        others.any { it != null && ArtistNames.key(it).contains(k) } -> 3
        else -> null
    }
}

// A song is the same song under a different album - YouTube files a track under whatever
// release it found it on - so songs meet on who and what, not where.
private fun songKey(s: SongUiModel) = "song|" + ArtistNames.key(s.artist) + "|" + ArtistNames.key(withoutVersion(s.title))

private fun albumKey(a: AlbumUiModel) =
    "album|" + ArtistNames.key(a.artist.orEmpty()) + "|" + ArtistNames.key(withoutVersion(a.title))

private fun artistKey(name: String) = "artist|" + ArtistNames.key(name)

/**
 * "Moondance (2013 Remaster)" is Moondance. Only remasters are folded: a live take, a demo
 * or a remix is a different recording and is listed on its own.
 */
private val REMASTER_IN_BRACKETS = Regex("""\s*[(\[][^)\]]*remaster[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)
private val REMASTER_AFTER_DASH = Regex("""\s+-\s+[^-]*remaster.*$""", RegexOption.IGNORE_CASE)

private fun withoutVersion(title: String): String =
    title.replace(REMASTER_IN_BRACKETS, "").replace(REMASTER_AFTER_DASH, "").trim()
