package com.wanderwildwood.jimeikin.playback

import android.content.Context
import android.net.Uri
import com.wanderwildwood.jimeikin.data.CalmMusicDatabase
import com.wanderwildwood.jimeikin.formatDurationMillis
import com.wanderwildwood.jimeikin.ui.SongUiModel
import java.net.URI

/**
 * `jimeikin://song?id=<library song id>`: another app asks Music Box to play one song it already
 * holds. Idea and first version by heavencanthandle (https://github.com/heavencanthandle/Music-box).
 *
 * What makes it safe to leave open to any app on the phone: the link carries nothing to fetch.
 * It names a row by id, the row is looked up in this library, and a song the library does not
 * hold plays nothing. A link can never hand Music Box an address, a title to show or a file to
 * read, so it cannot make the app reach anywhere it was not already going to, and nothing is
 * sent back. It is not browsable, so a web page cannot open it. The worst a link can do is start
 * a song of the person's own library, the same as pressing play.
 */
object SongLink {

    private const val SCHEME = "jimeikin"
    private const val HOST = "song"
    private const val MAX_ID = 300

    fun isLink(uri: Uri): Boolean = uri.scheme == SCHEME && uri.host == HOST

    /** The library id a link names, or null when it names none that could be one. */
    fun idOf(link: String): String? {
        val uri = runCatching { URI(link) }.getOrNull() ?: return null
        if (uri.scheme != SCHEME || uri.host != HOST) return null
        val id = uri.query?.split('&')
            ?.firstOrNull { it.startsWith("id=") }
            ?.removePrefix("id=")
            ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            ?: return null
        if (id.isBlank() || id.length > MAX_ID) return null
        // A station and a file opened once are not in the library; neither is a way in.
        if (id.startsWith(OpenedFile.ID_PREFIX)) return null
        return id
    }

    /** The song a link names, from the library's own row. Null when the library has none. */
    suspend fun read(context: Context, uri: Uri): SongUiModel? {
        val id = idOf(uri.toString()) ?: return null
        val entity = CalmMusicDatabase.getDatabase(context).songDao().getSongById(id) ?: return null
        if (entity.audioUri.isNullOrBlank()) return null
        return SongUiModel(
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
