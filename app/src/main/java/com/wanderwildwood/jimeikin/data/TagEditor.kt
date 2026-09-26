package com.wanderwildwood.jimeikin.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.TagOptionSingleton
import java.io.File
import java.io.FileOutputStream

/**
 * Writes a song's tags back into the file it came from.
 *
 * The file is someone's music and the phone can lose power or have its card pulled at any
 * moment, so nothing here writes over it in place. The tags are written into a copy in the
 * cache, the copy is read back to prove the write took, and the copy is then laid down beside
 * the original under a name no scan indexes ([PENDING_SUFFIX]). Only once it is whole on the
 * card is the original deleted and the copy renamed into its place. Whatever moment this is
 * interrupted at, a whole file is left: the untouched original, or the finished copy under
 * the pending name, which [recoverPending] finishes renaming on the next scan.
 */
object TagEditor {
    const val PENDING_SUFFIX = ".jimeikin-new"

    /** Set while an edit is on the card, so a scan does not take its copy for an orphan. */
    @Volatile
    var isWriting = false
        private set

    /** The fields a reader can change. In [write], a null field is left as the file has it. */
    data class Fields(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val albumArtist: String? = null,
        val trackNumber: String? = null,
    )

    sealed class Result {
        /** [uri] is where the file is now; the same as before on the phone's own storage. */
        data class Written(val uri: Uri) : Result()

        /** The format could not be read or written - the original was never touched. */
        object Unsupported : Result()

        /** Something failed before the original was touched. */
        object Failed : Result()

        /** The original is gone and the edited copy could not take its name. */
        object LeftPending : Result()
    }

    fun read(context: Context, uri: Uri): Fields? {
        val name = displayName(context, uri) ?: return null
        val temp = copyToCache(context, uri, name) ?: return null
        return try {
            TagOptionSingleton.getInstance().isAndroid = true
            val tag = AudioFileIO.read(temp).tag ?: return Fields()
            Fields(
                title = tag.getFirst(FieldKey.TITLE),
                artist = tag.getFirst(FieldKey.ARTIST),
                album = tag.getFirst(FieldKey.ALBUM),
                albumArtist = tag.getFirst(FieldKey.ALBUM_ARTIST),
                trackNumber = tag.getFirst(FieldKey.TRACK).substringBefore('/'),
            )
        } catch (_: Exception) {
            null
        } finally {
            temp.delete()
        }
    }

    fun write(context: Context, uri: Uri, fields: Fields): Result {
        isWriting = true
        try {
            return writeCopy(context, uri, fields)
        } finally {
            isWriting = false
        }
    }

    private fun writeCopy(context: Context, uri: Uri, fields: Fields): Result {
        val name = displayName(context, uri) ?: return Result.Failed
        val temp = copyToCache(context, uri, name) ?: return Result.Failed
        try {
            TagOptionSingleton.getInstance().isAndroid = true
            // A YouTube download is WebM saved under .m4a and fails here, before anything on
            // the card has been touched.
            val audioFile = try {
                AudioFileIO.read(temp)
            } catch (_: Exception) {
                return Result.Unsupported
            }
            try {
                val tag = audioFile.tagOrCreateAndSetDefault
                tag.put(FieldKey.TITLE, fields.title)
                tag.put(FieldKey.ARTIST, fields.artist)
                tag.put(FieldKey.ALBUM, fields.album)
                tag.put(FieldKey.ALBUM_ARTIST, fields.albumArtist)
                tag.put(FieldKey.TRACK, fields.trackNumber)
                audioFile.commit()
            } catch (_: Exception) {
                return Result.Unsupported
            }

            val check = try {
                AudioFileIO.read(temp).tag
            } catch (_: Exception) {
                null
            } ?: return Result.Failed
            if (!check.agrees(FieldKey.TITLE, fields.title) ||
                !check.agrees(FieldKey.ARTIST, fields.artist) ||
                !check.agrees(FieldKey.ALBUM, fields.album) ||
                !check.agrees(FieldKey.ALBUM_ARTIST, fields.albumArtist) ||
                !check.agrees(FieldKey.TRACK, fields.trackNumber)
            ) {
                return Result.Failed
            }

            return replace(context, uri, name, temp)
        } finally {
            temp.delete()
        }
    }

    /**
     * Finishes an edit that was interrupted. With the original still there, the copy was not
     * yet known to be whole and is thrown away; with the original gone, the copy was, and it
     * takes the original's name. Returns the document id the file ends up under, or null if
     * there is no file to index.
     */
    fun recoverPending(
        context: Context,
        treeUri: Uri,
        pendingDocumentId: String,
        pendingName: String,
        siblingNames: Set<String>,
    ): String? {
        val resolver = context.contentResolver
        val pendingUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, pendingDocumentId)
        val originalName = pendingName.removeSuffix(PENDING_SUFFIX)
        return try {
            if (originalName in siblingNames) {
                DocumentsContract.deleteDocument(resolver, pendingUri)
                null
            } else {
                DocumentsContract.renameDocument(resolver, pendingUri, originalName)
                    ?.let { DocumentsContract.getDocumentId(it) }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun replace(context: Context, uri: Uri, name: String, edited: File): Result {
        val resolver = context.contentResolver
        return try {
            val ids = DocumentsContract.findDocumentPath(resolver, uri)?.path
            if (ids == null || ids.size < 2) return Result.Failed
            val parentId = ids[ids.size - 2]
            val pendingName = name + PENDING_SUFFIX

            // A copy left by an edit that failed before it was whole.
            childByName(context, uri, parentId, pendingName)?.let {
                DocumentsContract.deleteDocument(resolver, it)
            }

            val parentUri = DocumentsContract.buildDocumentUriUsingTree(uri, parentId)
            val pendingUri = DocumentsContract.createDocument(
                resolver,
                parentUri,
                "application/octet-stream",
                pendingName,
            ) ?: return Result.Failed

            val copied = try {
                resolver.openOutputStream(pendingUri, "w")?.use { output ->
                    edited.inputStream().use { it.copyTo(output) }
                    output.flush()
                    (output as? FileOutputStream)?.fd?.sync()
                } != null
            } catch (_: Exception) {
                false
            }
            if (!copied || sizeOf(context, pendingUri) != edited.length() ||
                displayName(context, pendingUri) != pendingName
            ) {
                runCatching { DocumentsContract.deleteDocument(resolver, pendingUri) }
                return Result.Failed
            }

            if (!DocumentsContract.deleteDocument(resolver, uri)) {
                runCatching { DocumentsContract.deleteDocument(resolver, pendingUri) }
                return Result.Failed
            }
            val renamed = try {
                DocumentsContract.renameDocument(resolver, pendingUri, name)
            } catch (_: Exception) {
                null
            } ?: return Result.LeftPending
            Result.Written(renamed)
        } catch (_: Exception) {
            Result.Failed
        }
    }

    private fun Tag.put(key: FieldKey, value: String?) {
        value ?: return
        val trimmed = value.trim()
        if (trimmed.isEmpty()) deleteField(key) else setField(key, trimmed)
    }

    private fun Tag.agrees(key: FieldKey, value: String?): Boolean {
        value ?: return true
        val read = getFirst(key).orEmpty()
        return if (key == FieldKey.TRACK) {
            read.substringBefore('/').trim().trimStart('0') == value.trim().trimStart('0')
        } else {
            read == value.trim()
        }
    }

    private fun childByName(context: Context, treeUri: Uri, parentId: String, name: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == name) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(0))
                }
            }
        }
        return null
    }

    private fun displayName(context: Context, uri: Uri): String? = queryColumn(context, uri) {
        it.getString(it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
    }

    private fun sizeOf(context: Context, uri: Uri): Long? = queryColumn(context, uri) {
        it.getLong(it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE))
    }

    private fun <T> queryColumn(context: Context, uri: Uri, read: (android.database.Cursor) -> T): T? = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) read(cursor) else null
        }
    } catch (_: Exception) {
        null
    }

    /** jaudiotagger picks its reader by extension, so the copy keeps the file's own. */
    private fun copyToCache(context: Context, uri: Uri, name: String): File? {
        val ext = name.substringAfterLast('.', "").lowercase().ifEmpty { return null }
        val temp = try {
            File.createTempFile("tag_edit", ".$ext", context.cacheDir)
        } catch (_: Exception) {
            return null
        }
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temp).use { input.copyTo(it) }
            } ?: run {
                temp.delete()
                return null
            }
            temp
        } catch (_: Exception) {
            temp.delete()
            null
        }
    }
}
