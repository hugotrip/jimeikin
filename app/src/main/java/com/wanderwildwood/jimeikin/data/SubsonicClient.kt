package com.wanderwildwood.jimeikin.data

import android.content.res.Resources
import android.net.Uri
import com.wanderwildwood.jimeikin.R
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
 *
 * The one exception is a server older than Subsonic 1.13, which predates the tokens and
 * accepts only the password itself, hex-encoded. Such a server says so by refusing the
 * version this asks for and naming its own; then, and only then, the old form is used.
 */
class SubsonicClient(
    private val config: SubsonicConfig,
    private val resources: Resources,
) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // Not infinite: a server that accepts the connection and then says nothing would
        // otherwise wedge the sync for good.
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Whether the server is there and the credentials are right. */
    suspend fun ping(): SubsonicResult<Unit> = request("ping").map { }

    /**
     * Everything the library needs: the albums and the songs on each, by whichever route the
     * server supports. See [SubsonicLibraryReader].
     */
    suspend fun fetchLibrary(
        onProgress: suspend (SubsonicProgress) -> Unit = {},
    ): SubsonicResult<SubsonicLibrary> =
        SubsonicLibraryReader { method, params -> request(method, params) }.read(onProgress)

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

    /**
     * The API version spoken. Starts at the newest this was written against; a server that
     * is older answers error 30 with its own version, and every call after that uses it.
     */
    @Volatile
    private var apiVersion = API_VERSION

    /** True once the server has turned out to be too old for tokens: the password goes as itself. */
    val sendsPassword: Boolean get() = isOlder(apiVersion, TOKENS_SINCE)

    private suspend fun request(
        method: String,
        params: Map<String, String> = emptyMap(),
    ): SubsonicResult<JSONObject> {
        val first = requestOnce(method, params)
        if (first is SubsonicResult.Failure && first.code == ERROR_SERVER_TOO_OLD) {
            val theirs = first.serverVersion
            if (theirs != null && theirs != apiVersion && isOlder(theirs, apiVersion)) {
                apiVersion = theirs
                return requestOnce(method, params)
            }
        }
        return first
    }

    private suspend fun requestOnce(
        method: String,
        params: Map<String, String>,
    ): SubsonicResult<JSONObject> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url(method, params)).build()
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext SubsonicResult.Failure(
                        resources.getString(R.string.service_subsonic_download_server_answered, response.code),
                        code = -response.code,
                    )
                }
                val text = response.body?.string().orEmpty()
                val body = JSONObject(text).optJSONObject("subsonic-response")
                    ?: return@withContext SubsonicResult.Failure(
                        resources.getString(R.string.service_subsonic_not_a_music_server),
                    )
                if (body.optString("status") != "ok") {
                    val error = body.optJSONObject("error")
                    val code = error?.optInt("code")
                    // The two a person can actually act on are told apart; the rest are
                    // passed through as the server worded them.
                    return@withContext SubsonicResult.Failure(
                        when (code) {
                            40 -> resources.getString(R.string.service_subsonic_login_refused)
                            50 -> resources.getString(R.string.service_subsonic_not_allowed)
                            else -> error?.optString("message")
                                ?.takeIf { it.isNotBlank() }
                                ?: resources.getString(R.string.service_subsonic_refused)
                        },
                        code = code,
                        serverVersion = body.optString("version").takeIf { it.isNotBlank() },
                    )
                }
                SubsonicResult.Success(body)
            }
        } catch (e: Exception) {
            android.util.Log.w("SubsonicClient", "$method failed", e)
            SubsonicResult.Failure(resources.getString(R.string.service_subsonic_unreachable))
        }
    }

    private fun url(method: String, params: Map<String, String> = emptyMap()): String {
        val salt = newSalt()
        val builder = Uri.parse(config.normalisedBaseUrl).buildUpon()
            .appendPath("rest")
            .appendPath(method)
            .appendQueryParameter("u", config.user)
        val version = apiVersion
        if (isOlder(version, TOKENS_SINCE)) {
            builder.appendQueryParameter("p", "enc:" + config.password.toByteArray().joinToString("") { "%02x".format(it) })
        } else {
            builder
                .appendQueryParameter("t", md5(config.password + salt))
                .appendQueryParameter("s", salt)
        }
        builder
            .appendQueryParameter("v", version)
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

        /** The first version that took a salted token instead of the password. */
        const val TOKENS_SINCE = "1.13.0"
        const val ERROR_SERVER_TOO_OLD = 30
        const val CLIENT_NAME = "jimeikin"

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

    /**
     * Worded for the person reading it, not for a log. [code] is the server's Subsonic error
     * code, or minus the HTTP status when it never got that far; null when nothing answered.
     */
    data class Failure(
        val message: String,
        val code: Int? = null,
        val serverVersion: String? = null,
    ) : SubsonicResult<Nothing> {
        /** A wrong password or a forbidden account: no other route around it will help. */
        val isAboutTheAccount: Boolean get() = code == 40 || code == 41 || code == 50
    }
}

internal inline fun <T, R> SubsonicResult<T>.map(transform: (T) -> R): SubsonicResult<R> =
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
    /** As the server's album list gives it; used to tell a complete read from a short one. */
    val songCount: Int? = null,
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

/** Whether dotted version [a] comes before [b]: "1.8.0" is older than "1.13.0". */
internal fun isOlder(a: String, b: String): Boolean {
    val x = a.split('.').map { it.toIntOrNull() ?: 0 }
    val y = b.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(x.size, y.size)) {
        val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
        if (d != 0) return d < 0
    }
    return false
}

/**
 * A server song's stream address with a ceiling on its bitrate, or as it was when there is
 * no ceiling.
 *
 * Applied when a queue is built rather than when the library is read, so a change takes
 * effect the next time something is put on to play, without reading the library again. `format=raw` is
 * what asks the server not to transcode, so it has to go for the ceiling to mean anything;
 * a song already under the ceiling is still sent untouched, since the server only transcodes
 * what is over it.
 */
fun withBitrateCeiling(url: String, kbps: Int?): String {
    if (kbps == null || kbps <= 0 || "/rest/stream" !in url) return url
    val q = url.indexOf('?')
    if (q < 0) return "$url?maxBitRate=$kbps"
    val kept = url.substring(q + 1).split('&').filterNot {
        it == "format=raw" || it.startsWith("maxBitRate=")
    }
    return url.substring(0, q) + "?" + (kept + "maxBitRate=$kbps").joinToString("&")
}
