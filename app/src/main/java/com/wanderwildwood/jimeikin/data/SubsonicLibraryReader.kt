package com.wanderwildwood.jimeikin.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject

/** How far a library read has got, in whichever unit the server is being read by. */
sealed interface SubsonicProgress {
    data class Songs(val done: Int, val total: Int) : SubsonicProgress
    data class Albums(val done: Int, val total: Int) : SubsonicProgress

    /** A folder walk cannot know how many folders there are until it has walked them. */
    data class Folders(val done: Int) : SubsonicProgress
}

/**
 * Reads a whole library from a server, by whichever route that server supports.
 *
 * Kept apart from [SubsonicClient] so it takes nothing but a way to make a call, which is
 * what lets its fallbacks be tested against made-up servers rather than only the one at home.
 *
 * Three routes, tried in order:
 *
 * 1. **Tags, all songs in a few pages.** The album list, then every song through `search3`
 *    with an empty query, 500 to a page. A library of three thousand songs is half a dozen
 *    calls instead of one per album, which on a phone is the difference between a few
 *    seconds and a few minutes.
 * 2. **Tags, album by album.** Not every server answers an empty search, and one that does
 *    may answer it with less than everything. The album list says how many songs each album
 *    has, so a short answer is caught and the albums are read one by one instead, four at a
 *    time.
 * 3. **Folders.** A server that does not keep a tag library at all — older Subsonic, or
 *    Airsonic and Gonic set up to browse by folder — has no album list to give. Then the
 *    library is walked the way the server stores it, folder by folder, and the albums are
 *    made from the folders the songs sit in.
 *
 *    The same walk is used when there is an album list but every album on it is unknown.
 *    That is what a tag library made from untagged files looks like — Gonic files them all
 *    as "Unknown Album" by "Unknown Artist" — while the folders still say "Artist/Album".
 *    Only when every album is unknown: a library with some tags is better read by them.
 */
class SubsonicLibraryReader(
    private val call: suspend (method: String, params: Map<String, String>) -> SubsonicResult<JSONObject>,
) {

    suspend fun read(onProgress: suspend (SubsonicProgress) -> Unit = {}): SubsonicResult<SubsonicLibrary> {
        val tagAlbums = fetchAlbumList()
        if (tagAlbums is SubsonicResult.Failure && tagAlbums.isAboutTheAccount) return tagAlbums

        val albums = (tagAlbums as? SubsonicResult.Success)?.value.orEmpty()
        if (albums.isNotEmpty() && albums.all { isUnknown(it.name) }) {
            val walked = walkFolders(onProgress)
            if (walked is SubsonicResult.Success && walked.value.songs.isNotEmpty()) return walked
            if (walked is SubsonicResult.Failure && walked.isAboutTheAccount) return walked
        }
        if (albums.isNotEmpty()) {
            val expected = albums.sumOf { it.songCount ?: 0 }
            val everySong = fetchEverySong(expected, onProgress)
            if (everySong != null) return SubsonicResult.Success(SubsonicLibrary(albums, everySong))
            return fetchAlbumByAlbum(albums, onProgress)
        }

        // No album list. Either the server has no tag library, or it has one and it is
        // empty; the folders tell the two apart, and cost one call when there is nothing.
        return when (val walked = walkFolders(onProgress)) {
            is SubsonicResult.Success -> walked
            // The folder walk failing says less than the album list failing did.
            is SubsonicResult.Failure -> tagAlbums as? SubsonicResult.Failure ?: walked
        }
    }

    private suspend fun fetchAlbumList(): SubsonicResult<List<SubsonicAlbum>> {
        val all = mutableListOf<SubsonicAlbum>()
        var offset = 0
        // The API caps a page at 500 whatever is asked for, so this pages until a short one.
        while (true) {
            val body = when (
                val page = call(
                    "getAlbumList2",
                    mapOf(
                        "type" to "alphabeticalByName",
                        "size" to PAGE_SIZE.toString(),
                        "offset" to offset.toString(),
                    ),
                )
            ) {
                is SubsonicResult.Failure -> return page
                is SubsonicResult.Success -> page.value
            }
            val array = body.optJSONObject("albumList2")?.optJSONArray("album")
            val count = array?.length() ?: 0
            for (i in 0 until count) {
                array!!.getJSONObject(i).toAlbum()?.let { all += it }
            }
            if (count < PAGE_SIZE) return SubsonicResult.Success(all)
            offset += count
        }
    }

    /**
     * One album and every song on it, as the server has it now.
     *
     * For a page opened on a song the library has no album for - one played from a queue
     * kept since the album was removed, or one the library filed as its card copy under
     * another name - so the page is the server's record rather than nothing.
     */
    suspend fun readAlbum(albumId: String): SubsonicResult<Pair<SubsonicAlbum, List<SubsonicSong>>> =
        call("getAlbum", mapOf("id" to albumId)).map { body ->
            val o = body.optJSONObject("album") ?: JSONObject()
            val album = o.toAlbum() ?: SubsonicAlbum(albumId, UNKNOWN_ALBUM, UNKNOWN_ARTIST, null, null)
            album to songsIn(o.optJSONArray("song"), albumId)
        }

    /** One song, for the album and artist ids its row in the library does not keep. */
    suspend fun readSong(songId: String): SubsonicResult<SubsonicSong?> =
        call("getSong", mapOf("id" to songId)).map { body ->
            body.optJSONObject("song")?.toSong(null)
        }

    /** An artist's name and the albums the server files under them. */
    suspend fun readArtist(artistId: String): SubsonicResult<Pair<String, List<SubsonicAlbum>>> =
        call("getArtist", mapOf("id" to artistId)).map { body ->
            val o = body.optJSONObject("artist") ?: JSONObject()
            val array = o.optJSONArray("album")
            val albums = (0 until (array?.length() ?: 0)).mapNotNull { array!!.getJSONObject(it).toAlbum() }
            o.optString("name").ifBlank { UNKNOWN_ARTIST } to albums
        }

    private fun JSONObject.toAlbum(): SubsonicAlbum? {
        val id = optString("id").takeIf { it.isNotBlank() } ?: return null
        return SubsonicAlbum(
            id = id,
            name = optString("name").ifBlank { UNKNOWN_ALBUM },
            artist = optString("artist").ifBlank { UNKNOWN_ARTIST },
            artistId = optString("artistId").takeIf { it.isNotBlank() },
            year = optInt("year").takeIf { it > 0 },
            songCount = optInt("songCount", -1).takeIf { it >= 0 },
        )
    }

    /**
     * Every song in a few calls, or null when the server would not or could not say.
     *
     * Null rather than a failure: this is only ever the quick way, and there is always the
     * slow way behind it.
     */
    private suspend fun fetchEverySong(
        expected: Int,
        onProgress: suspend (SubsonicProgress) -> Unit,
    ): List<SubsonicSong>? {
        if (expected <= 0) return null
        val byId = LinkedHashMap<String, SubsonicSong>()
        var offset = 0
        while (true) {
            val body = when (
                val page = call(
                    "search3",
                    mapOf(
                        "query" to "",
                        "artistCount" to "0",
                        "albumCount" to "0",
                        "songCount" to PAGE_SIZE.toString(),
                        "songOffset" to offset.toString(),
                    ),
                )
            ) {
                is SubsonicResult.Failure -> return null
                is SubsonicResult.Success -> page.value
            }
            val array = body.optJSONObject("searchResult3")?.optJSONArray("song")
            val count = array?.length() ?: 0
            songsIn(array).forEach { byId[it.id] = it }
            onProgress(SubsonicProgress.Songs(byId.size.coerceAtMost(expected), expected))
            // A server that ignores the offset sends the same page forever; a page that adds
            // nothing new ends it as surely as a short one.
            if (count < PAGE_SIZE || byId.size >= expected || offset >= MAX_SONGS) break
            val before = offset
            offset += count
            if (byId.size <= before) break
        }
        return byId.values.toList().takeIf { it.size >= expected }
    }

    private suspend fun fetchAlbumByAlbum(
        albums: List<SubsonicAlbum>,
        onProgress: suspend (SubsonicProgress) -> Unit,
    ): SubsonicResult<SubsonicLibrary> = coroutineScope {
        val gate = Semaphore(PARALLEL_CALLS)
        var done = 0
        val results = albums.map { album ->
            async {
                gate.withPermit {
                    val result = call("getAlbum", mapOf("id" to album.id)).map { body ->
                        songsIn(body.optJSONObject("album")?.optJSONArray("song"), album.id)
                    }
                    onProgress(SubsonicProgress.Albums(++done, albums.size))
                    result
                }
            }
        }.awaitAll()
        results.firstOrNull { it is SubsonicResult.Failure }?.let { return@coroutineScope it as SubsonicResult.Failure }
        val songs = results.flatMap { (it as SubsonicResult.Success).value }
        SubsonicResult.Success(SubsonicLibrary(albums, songs))
    }

    /**
     * The library the way the server keeps it on disk: the top-level folders from
     * `getIndexes`, then each folder's contents until there are only songs left.
     */
    private suspend fun walkFolders(onProgress: suspend (SubsonicProgress) -> Unit): SubsonicResult<SubsonicLibrary> {
        val top = when (val r = call("getIndexes", emptyMap())) {
            is SubsonicResult.Failure -> return r
            is SubsonicResult.Success -> r.value.optJSONObject("indexes") ?: JSONObject()
        }

        val folderNames = HashMap<String, String>()
        val folderParents = HashMap<String, String>()
        val songs = mutableListOf<SubsonicSong>()
        var level = mutableListOf<String>()
        listOf("shortcut").plus("index").forEach { key ->
            val entries = top.optJSONArray(key) ?: return@forEach
            for (i in 0 until entries.length()) {
                val entry = entries.getJSONObject(i)
                if (key == "shortcut") {
                    entry.folderId()?.let { level += it; folderNames[it] = entry.optString("name") }
                } else {
                    val artists = entry.optJSONArray("artist") ?: continue
                    for (j in 0 until artists.length()) {
                        val a = artists.getJSONObject(j)
                        a.folderId()?.let { level += it; folderNames[it] = a.optString("name") }
                    }
                }
            }
        }
        // Songs loose at the very top of the music folder.
        songs += songsIn(top.optJSONArray("child"))

        val seen = HashSet<String>(level)
        var walked = 0
        var depth = 0
        while (level.isNotEmpty() && depth < MAX_FOLDER_DEPTH && walked < MAX_FOLDERS) {
            val next = mutableListOf<String>()
            val gate = Semaphore(PARALLEL_CALLS)
            val pages = coroutineScope {
                level.map { id ->
                    async { gate.withPermit { id to call("getMusicDirectory", mapOf("id" to id)) } }
                }.awaitAll()
            }
            for ((id, result) in pages) {
                val directory = when (result) {
                    is SubsonicResult.Failure -> if (result.isAboutTheAccount) return result else continue
                    is SubsonicResult.Success -> result.value.optJSONObject("directory") ?: continue
                }
                directory.optString("name").takeIf { it.isNotBlank() }?.let { folderNames[id] = it }
                val children = directory.optJSONArray("child") ?: continue
                for (i in 0 until children.length()) {
                    val child = children.getJSONObject(i)
                    if (child.optBoolean("isDir")) {
                        val childId = child.optString("id").takeIf { it.isNotBlank() } ?: continue
                        if (seen.add(childId)) {
                            next += childId
                            folderParents[childId] = id
                            folderNames[childId] = child.optString("title").ifBlank { child.optString("name") }
                        }
                    } else if (!child.optBoolean("isVideo")) {
                        child.toSong(fallbackAlbumId = id)?.let { songs += it }
                    }
                }
                onProgress(SubsonicProgress.Folders(++walked))
            }
            level = next
            depth++
        }

        val unique = songs.distinctBy { it.id }
        val albums = albumsFromFolders(unique, folderNames, folderParents)
        // An untagged song takes its album's artist, which the folders supplied.
        val albumArtist = albums.associate { it.id to it.artist }
        val named = unique.map { song ->
            if (isUnknown(song.artist)) song.copy(artist = song.albumId?.let { albumArtist[it] } ?: UNKNOWN_ARTIST) else song
        }
        return SubsonicResult.Success(SubsonicLibrary(albums, named))
    }

    /**
     * A folder of songs is an album, named by the songs' own album tag where they have one and
     * by the folder where they do not, and filed under whoever plays on most of it — or, when
     * no song says, under the folder it sits in, which is how "Artist/Album" is laid out.
     */
    private fun albumsFromFolders(
        songs: List<SubsonicSong>,
        folderNames: Map<String, String>,
        folderParents: Map<String, String>,
    ): List<SubsonicAlbum> =
        songs.filter { it.albumId != null }
            .groupBy { it.albumId!! }
            .map { (id, onAlbum) ->
                SubsonicAlbum(
                    id = id,
                    name = onAlbum.mostCommon { it.album?.takeUnless(::isUnknown) }
                        ?: folderNames[id]?.takeIf { it.isNotBlank() }
                        ?: UNKNOWN_ALBUM,
                    artist = onAlbum.mostCommon { it.artist.takeUnless(::isUnknown) }
                        ?: folderParents[id]?.let { folderNames[it] }?.takeIf { it.isNotBlank() }
                        ?: UNKNOWN_ARTIST,
                    artistId = null,
                    year = onAlbum.firstNotNullOfOrNull { it.year },
                    songCount = onAlbum.size,
                )
            }
            .sortedBy { it.name.lowercase() }

    private fun songsIn(array: JSONArray?, fallbackAlbumId: String? = null): List<SubsonicSong> {
        val songs = mutableListOf<SubsonicSong>()
        for (i in 0 until (array?.length() ?: 0)) {
            val o = array!!.getJSONObject(i)
            if (o.optBoolean("isDir") || o.optBoolean("isVideo")) continue
            o.toSong(fallbackAlbumId)?.let { songs += it }
        }
        return songs
    }

    private fun JSONObject.toSong(fallbackAlbumId: String?): SubsonicSong? {
        val id = optString("id").takeIf { it.isNotBlank() } ?: return null
        return SubsonicSong(
            id = id,
            title = optString("title").ifBlank { "[Untitled]" },
            artist = optString("artist").ifBlank { UNKNOWN_ARTIST },
            artistId = optString("artistId").takeIf { it.isNotBlank() },
            album = optString("album").takeIf { it.isNotBlank() },
            albumId = optString("albumId").takeIf { it.isNotBlank() }
                ?: fallbackAlbumId
                ?: optString("parent").takeIf { it.isNotBlank() },
            trackNumber = optInt("track").takeIf { it > 0 },
            discNumber = optInt("discNumber").takeIf { it > 0 },
            year = optInt("year").takeIf { it > 0 },
            durationMillis = optInt("duration").takeIf { it > 0 }?.let { it * 1000L },
            sizeBytes = optLong("size").takeIf { it > 0 },
            suffix = optString("suffix").takeIf { it.isNotBlank() },
        )
    }

    /** Each server spells "no tag" its own way: "[Unknown Album]", "Unknown Artist", blank. */
    private fun isUnknown(name: String?): Boolean =
        name.isNullOrBlank() || name.trim('[', ']', ' ').lowercase() in UNKNOWN_NAMES

    private fun JSONObject.folderId(): String? = optString("id").takeIf { it.isNotBlank() }

    private inline fun <T> List<T>.mostCommon(key: (T) -> String?): String? =
        mapNotNull(key).groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    private companion object {
        const val PAGE_SIZE = 500
        const val PARALLEL_CALLS = 4

        // Limits on a walk that only a broken or looping server would reach.
        const val MAX_FOLDER_DEPTH = 8
        const val MAX_FOLDERS = 50_000
        const val MAX_SONGS = 500_000

        const val UNKNOWN_ALBUM = "[Unknown album]"
        const val UNKNOWN_ARTIST = "[Unknown artist]"
        val UNKNOWN_NAMES = setOf("unknown album", "unknown artist", "unknown")
    }
}
