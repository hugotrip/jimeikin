package com.wanderwildwood.jimeikin.data

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Talks to a Navidrome server, or anything else that speaks Subsonic.
 *
 * Navidrome, Airsonic and Gonic all implement the same API, so this is written against
 * Subsonic 1.16.1 rather than against Navidrome in particular; the only Navidrome-specific
 * thing here is that it is what this was tested on.
 *
 * **The password is never sent.** Subsonic's token scheme is `md5(password + salt)` with a
 * fresh salt per request, which is what this uses. That is not encryption — anyone reading
 * the wire can replay a request — so over the open internet this wants HTTPS; on a home
 * network it is enough to keep the password itself off the wire. The password still has to
 * be kept on the phone to compute the tokens, which is worth saying plainly in the About.
 */
class SubsonicClient(private val config: SubsonicConfig) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // Not infinite: a server that accepts the connection and then says nothing would
        // otherwise wedge the sync for good.
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Whether the server is there and the credentials are right. */
    suspend fun ping(): SubsonicResult<Unit> = request("ping").map { }

    /**
     * Everything the library needs, in one pass: the artists, their albums, and the songs on
     * each album. On a small library that is one call plus one per album, which on a home
     * network is a second or two; there is no bulk "give me everything" call in the API.
     */
    suspend fun fetchLibrary(
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): SubsonicResult<SubsonicLibrary> {
        val albumsResult = fetchAllAlbums()
        val albums = when (albumsResult) {
            is SubsonicResult.Failure -> return albumsResult
            is SubsonicResult.Success -> albumsResult.value
        }

        val songs = mutableListOf<SubsonicSong>()
        albums.forEachIndexed { index, album ->
            when (val songsResult = fetchAlbumSongs(album.id)) {
                is SubsonicResult.Failure -> return songsResult
                is SubsonicResult.Success -> songs += songsResult.value
            }
            onProgress(index + 1, albums.size)
        }

        return SubsonicResult.Success(SubsonicLibrary(albums = albums, songs = songs))
    }

    /**
     * Where the audio actually is. Built fresh from the stored password rather than kept in
     * the database, so changing the password on the server and re-entering it here is enough
     * to make every song playable again without a resync.
     *
     * Anything the phone can decode is asked for untouched, so a FLAC arrives as a FLAC and
     * nothing is spent re-encoding it. For the formats it cannot decode — Windows Media,
     * Musepack, Monkey's Audio and the like — the server is asked to transcode instead, which
     * is the whole reason the Subsonic API takes a format at all. Without that those records
     * would be listed and then refuse to play.
     */
    fun streamUrl(songId: String, suffix: String?): String {
        val playable = suffix != null && suffix.lowercase() in PLAYABLE_SUFFIXES
        return url(
            "stream",
            if (playable) mapOf("id" to songId, "format" to "raw")
            else mapOf("id" to songId, "format" to "mp3"),
        )
    }

    /**
     * The playlists the server keeps, with the songs on each.
     *
     * Two calls per playlist is the API's shape: one for the list, one for the contents.
     */
    suspend fun fetchPlaylists(): SubsonicResult<List<SubsonicPlaylist>> {
        val body = when (val r = request("getPlaylists")) {
            is SubsonicResult.Failure -> return r
            is SubsonicResult.Success -> r.value
        }
        val array = body.optJSONObject("playlists")?.optJSONArray("playlist")
        val result = mutableListOf<SubsonicPlaylist>()
        for (i in 0 until (array?.length() ?: 0)) {
            val o = array!!.getJSONObject(i)
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: continue
            val name = o.optString("name").ifBlank { "Playlist" }
            val songs = when (val songsResult = fetchPlaylistSongIds(id)) {
                is SubsonicResult.Failure -> return songsResult
                is SubsonicResult.Success -> songsResult.value
            }
            if (songs.isNotEmpty()) result += SubsonicPlaylist(id, name, songs)
        }
        return SubsonicResult.Success(result)
    }

    private suspend fun fetchPlaylistSongIds(playlistId: String): SubsonicResult<List<String>> {
        val body = when (val r = request("getPlaylist", mapOf("id" to playlistId))) {
            is SubsonicResult.Failure -> return r
            is SubsonicResult.Success -> r.value
        }
        val array = body.optJSONObject("playlist")?.optJSONArray("entry")
        val ids = mutableListOf<String>()
        for (i in 0 until (array?.length() ?: 0)) {
            array!!.getJSONObject(i).optString("id").takeIf { it.isNotBlank() }?.let { ids += it }
        }
        return SubsonicResult.Success(ids)
    }

    /** The same file, for keeping. Subsonic's download never transcodes. */
    fun downloadUrl(songId: String): String = url("download", mapOf("id" to songId))

    private suspend fun fetchAllAlbums(): SubsonicResult<List<SubsonicAlbum>> {
        val all = mutableListOf<SubsonicAlbum>()
        var offset = 0
        // The API caps a page at 500 whatever you ask for, so this pages until a short one.
        while (true) {
            val page = request(
                "getAlbumList2",
                mapOf(
                    "type" to "alphabeticalByName",
                    "size" to PAGE_SIZE.toString(),
                    "offset" to offset.toString(),
                ),
            )
            val body = when (page) {
                is SubsonicResult.Failure -> return page
                is SubsonicResult.Success -> page.value
            }
            val array = body.optJSONObject("albumList2")?.optJSONArray("album")
            val count = array?.length() ?: 0
            for (i in 0 until count) {
                val o = array!!.getJSONObject(i)
                all += SubsonicAlbum(
                    id = o.getString("id"),
                    name = o.optString("name").ifBlank { "[Unknown album]" },
                    artist = o.optString("artist").ifBlank { "[Unknown artist]" },
                    artistId = o.optString("artistId").takeIf { it.isNotBlank() },
                    year = o.optInt("year").takeIf { it > 0 },
                )
            }
            if (count < PAGE_SIZE) return SubsonicResult.Success(all)
            offset += count
        }
    }

    private suspend fun fetchAlbumSongs(albumId: String): SubsonicResult<List<SubsonicSong>> {
        val body = when (val r = request("getAlbum", mapOf("id" to albumId))) {
            is SubsonicResult.Failure -> return r
            is SubsonicResult.Success -> r.value
        }
        val array = body.optJSONObject("album")?.optJSONArray("song")
        val songs = mutableListOf<SubsonicSong>()
        for (i in 0 until (array?.length() ?: 0)) {
            val o = array!!.getJSONObject(i)
            songs += SubsonicSong(
                id = o.getString("id"),
                title = o.optString("title").ifBlank { "[Untitled]" },
                artist = o.optString("artist").ifBlank { "[Unknown artist]" },
                artistId = o.optString("artistId").takeIf { it.isNotBlank() },
                album = o.optString("album").takeIf { it.isNotBlank() },
                albumId = o.optString("albumId").takeIf { it.isNotBlank() } ?: albumId,
                trackNumber = o.optInt("track").takeIf { it > 0 },
                discNumber = o.optInt("discNumber").takeIf { it > 0 },
                year = o.optInt("year").takeIf { it > 0 },
                durationMillis = o.optInt("duration").takeIf { it > 0 }?.let { it * 1000L },
                sizeBytes = o.optLong("size").takeIf { it > 0 },
                suffix = o.optString("suffix").takeIf { it.isNotBlank() },
            )
        }
        return SubsonicResult.Success(songs)
    }

    private suspend fun request(
        method: String,
        params: Map<String, String> = emptyMap(),
    ): SubsonicResult<JSONObject> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url(method, params)).build()
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext SubsonicResult.Failure(
                        "The server answered ${response.code}.",
                    )
                }
                val text = response.body?.string().orEmpty()
                val body = JSONObject(text).optJSONObject("subsonic-response")
                    ?: return@withContext SubsonicResult.Failure(
                        "That address answered, but not like a music server.",
                    )
                if (body.optString("status") != "ok") {
                    val error = body.optJSONObject("error")
                    // The two a person can actually act on are told apart; the rest are
                    // passed through as the server worded them.
                    return@withContext SubsonicResult.Failure(
                        when (error?.optInt("code")) {
                            40 -> "That username and password were not accepted."
                            50 -> "That account is not allowed to read the library."
                            else -> error?.optString("message")
                                ?.takeIf { it.isNotBlank() }
                                ?: "The server refused the request."
                        },
                    )
                }
                SubsonicResult.Success(body)
            }
        } catch (e: Exception) {
            android.util.Log.w("SubsonicClient", "$method failed", e)
            SubsonicResult.Failure("The server could not be reached.")
        }
    }

    private fun url(method: String, params: Map<String, String> = emptyMap()): String {
        val salt = newSalt()
        val builder = Uri.parse(config.normalisedBaseUrl).buildUpon()
            .appendPath("rest")
            .appendPath(method)
            .appendQueryParameter("u", config.user)
            .appendQueryParameter("t", md5(config.password + salt))
            .appendQueryParameter("s", salt)
            .appendQueryParameter("v", API_VERSION)
            .appendQueryParameter("c", CLIENT_NAME)
            .appendQueryParameter("f", "json")
        params.forEach { (k, v) -> builder.appendQueryParameter(k, v) }
        return builder.build().toString()
    }

    private fun newSalt(): String {
        val bytes = ByteArray(8)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun md5(value: String): String =
        MessageDigest.getInstance("MD5")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val API_VERSION = "1.16.1"
        const val CLIENT_NAME = "jimeikin"
        const val PAGE_SIZE = 500

        /** What media3 can decode on this phone without help from the server. */
        val PLAYABLE_SUFFIXES = setOf(
            "mp3", "m4a", "m4b", "aac", "mp4", "flac", "ogg", "oga", "opus", "wav", "mka",
        )
    }
}

data class SubsonicConfig(
    val baseUrl: String,
    val user: String,
    val password: String,
) {
    /** People type "192.168.1.10:4533", and a trailing slash, and sometimes both. */
    val normalisedBaseUrl: String
        get() {
            val trimmed = baseUrl.trim().trimEnd('/')
            return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else {
                "http://$trimmed"
            }
        }

    val isComplete: Boolean
        get() = baseUrl.isNotBlank() && user.isNotBlank() && password.isNotBlank()
}

sealed interface SubsonicResult<out T> {
    data class Success<T>(val value: T) : SubsonicResult<T>

    /** Worded for the person reading it, not for a log. */
    data class Failure(val message: String) : SubsonicResult<Nothing>
}

private inline fun <T, R> SubsonicResult<T>.map(transform: (T) -> R): SubsonicResult<R> =
    when (this) {
        is SubsonicResult.Success -> SubsonicResult.Success(transform(value))
        is SubsonicResult.Failure -> this
    }

data class SubsonicLibrary(
    val albums: List<SubsonicAlbum>,
    val songs: List<SubsonicSong>,
)

data class SubsonicPlaylist(
    val id: String,
    val name: String,
    val songIds: List<String>,
)

data class SubsonicAlbum(
    val id: String,
    val name: String,
    val artist: String,
    val artistId: String?,
    val year: Int?,
)

data class SubsonicSong(
    val id: String,
    val title: String,
    val artist: String,
    val artistId: String?,
    val album: String?,
    val albumId: String?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val year: Int?,
    val durationMillis: Long?,
    val sizeBytes: Long?,
    val suffix: String?,
)
