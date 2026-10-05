package com.wanderwildwood.jimeikin.playback

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.wanderwildwood.jimeikin.formatDurationMillis
import com.wanderwildwood.jimeikin.ui.SongUiModel

/**
 * One audio file handed over by another app - "Open with" from a file manager, a download, an
 * attachment - to be played now.
 *
 * It is played as it is and not added to the library: the library is the folders chosen in
 * Settings, and a file opened once from somewhere else is not a decision about what the library
 * holds. Its queue is that one song, the way a radio station's is.
 */
object OpenedFile {

    /** Kept apart from every library id, so nothing mistakes the file for a song it knows. */
    const val ID_PREFIX = "OPENED:"

    /**
     * The song, read from the file's own tags where it has them and from its name where it does
     * not. Null when the file cannot be opened at all, which for a file handed over by its path
     * usually means the app has not been allowed to read music files yet.
     */
    fun read(context: Context, uri: Uri): SongUiModel? {
        if (!readable(context, uri)) return null
        val name = displayName(context, uri)
        var tagTitle: String? = null
        var tagArtist: String? = null
        var tagAlbum: String? = null
        var durationMs: Long? = null
        runCatching {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(context, uri)
                fun tag(key: Int) = retriever.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }
                tagTitle = tag(MediaMetadataRetriever.METADATA_KEY_TITLE)
                tagArtist = tag(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: tag(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                tagAlbum = tag(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                durationMs = tag(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            }
        }
        return SongUiModel(
            id = ID_PREFIX + uri,
            title = title(tagTitle, name ?: uri.lastPathSegment),
            artist = tagArtist.orEmpty(),
            durationText = formatDurationMillis(durationMs),
            durationMillis = durationMs,
            sourceType = "LOCAL_FILE",
            audioUri = uri.toString(),
            album = tagAlbum,
        )
    }

    /**
     * Whether being refused this file could be put right by allowing access to music files: a
     * file named by its path, or one of the phone's own media entries, rather than a file another
     * app shared (whose permission comes with it).
     */
    fun needsMediaAccess(uri: Uri): Boolean =
        uri.scheme == "file" || (uri.scheme == "content" && uri.authority == "media")

    /** The title tag if the file has one, and otherwise its name without the extension. */
    fun title(tagTitle: String?, fileName: String?): String {
        tagTitle?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val name = fileName?.substringAfterLast('/')?.trim().orEmpty()
        val bare = if (name.lastIndexOf('.') > 0) name.substringBeforeLast('.') else name
        return bare.ifEmpty { name }
    }

    private fun readable(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)

    private fun displayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
    }
}
