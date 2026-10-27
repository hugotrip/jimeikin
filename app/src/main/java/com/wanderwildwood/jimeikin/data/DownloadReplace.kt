package com.wanderwildwood.jimeikin.data

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.wanderwildwood.jimeikin.CalmMusic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Puts a new file in place of a download's old one, keeping the song: its row, playlists,
 * hearts, a name the reader gave it and the record of which video it came from all follow, and
 * in the end the new file has the old one's name.
 *
 * The new file is written beside the old one - MediaStore or the picker numbers it, "Song (1)
 * .m4a" - and checked to be the same bytes. Noted in a small record, then the song's row is
 * pointed at it in one transaction ([DownloadMove.repoint]). The old file is deleted only by
 * [sweep], which the caller runs once the library on screen has been read again, so nothing
 * still showing the old row names a file that has gone; the new file then takes the old name.
 * A kill anywhere in between is put right by the next [sweep], whichever side of the switch it
 * fell. Whatever fails before the switch, the new file goes and the old one is left as it was.
 *
 * A download is a file by its path - in Music/Music Box, or the app's private folder on Android
 * 9 and 10 - or, for one written before a reinstall and found again through a folder the reader
 * chose, a document in that folder, which the folder's grant lets the app replace without
 * asking ([DownloadRecognition]).
 *
 * 2.18.1 began this record for its WebM-to-MP4 conversions; any it left unfinished are finished
 * here.
 */
object DownloadReplace {

    private const val TAG = "DownloadReplace"

    private fun record(app: CalmMusic) = File(app.filesDir, "downloads-remuxed.txt")

    /** Where a download's file is: a path, or a document in a folder the reader chose. */
    sealed class Place {
        /** As the record keeps it. */
        abstract val key: String

        /** As a song's row names it. */
        abstract val uri: String
        abstract val name: String

        data class Path(val file: File) : Place() {
            override val key: String get() = file.path
            override val uri: String get() = Uri.fromFile(file).toString()
            override val name: String get() = file.name
        }

        data class Document(val document: Uri) : Place() {
            override val key: String get() = document.toString()
            override val uri: String get() = document.toString()

            // The file picker's document ids are the file's path in its storage, so the name
            // is there even once the file has gone.
            override val name: String
                get() = runCatching { DocumentsContract.getDocumentId(document).substringAfterLast('/') }.getOrDefault("")
        }

        companion object {
            fun ofRow(audioUri: String?): Place? = when {
                audioUri == null -> null
                audioUri.startsWith("content://") -> Document(Uri.parse(audioUri))
                else -> PublicDownloads.fileOf(audioUri)?.let(::Path)
            }

            fun ofKey(key: String): Place = if (key.startsWith("content://")) Document(Uri.parse(key)) else Path(File(key))
        }
    }

    /** Replacements not yet finished with, old to new ([Place.key]): the old file still to be deleted. */
    fun pending(app: CalmMusic): Map<String, String> = runCatching {
        val file = record(app)
        if (!file.exists()) return emptyMap()
        file.readLines().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) null else line.substring(0, tab) to line.substring(tab + 1)
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun pendingPlaces(app: CalmMusic): List<Place> = pending(app).flatMap { listOf(it.key, it.value) }.map(Place::ofKey)

    /** The paths of both files of every replacement in hand, which nothing but the library's own rows may list. */
    fun pendingPaths(app: CalmMusic): Set<String> =
        pendingPlaces(app).filterIsInstance<Place.Path>().mapTo(HashSet()) { it.file.path }

    /** The same, as the file picker names them, for a scan of a chosen folder to pass over. */
    fun pendingDocumentIds(app: CalmMusic): Set<String> = pendingPlaces(app).mapNotNullTo(HashSet()) { place ->
        when (place) {
            is Place.Path -> place.file.takeIf { PublicDownloads.isPublic(it.path) }?.let(PublicDownloads::documentIdOf)
            is Place.Document -> runCatching { DocumentsContract.getDocumentId(place.document) }.getOrNull()
        }
    }

    private fun writePending(app: CalmMusic, pending: Map<String, String>) {
        val file = record(app)
        if (pending.isEmpty()) {
            file.delete()
            return
        }
        val part = File(file.path + ".part")
        part.writeText(pending.entries.joinToString("") { "${it.key}\t${it.value}\n" })
        if (!part.renameTo(file)) throw IOException("could not write the replacement record")
    }

    fun exists(app: CalmMusic, place: Place): Boolean = when (place) {
        is Place.Path -> place.file.isFile
        is Place.Document -> runCatching { DocumentFile.fromSingleUri(app, place.document)?.exists() == true }.getOrDefault(false)
    }

    /**
     * Puts [work] beside [old] as a new .m4a file and switches [row] over to it; see the class
     * comment. Throws, with what went wrong as its message, leaving the song as it was.
     */
    fun replace(app: CalmMusic, database: CalmMusicDatabase, row: SongEntity, old: Place, work: File) {
        val base = old.name.substringBeforeLast('.', old.name).ifBlank { "Song" }
        val placed = try {
            saveBeside(app, old, work, base)
        } catch (e: Exception) {
            throw IOException("the new file could not be saved: ${e.message}", e)
        }
        // Noted before the row moves, so a kill between the two is put right next launch by
        // [sweep], whichever side of the move it fell.
        try {
            writePending(app, pending(app) + (old.key to placed.key))
        } catch (e: Exception) {
            delete(app, placed)
            throw IOException("it could not be noted: ${e.message}", e)
        }
        val newId = try {
            repoint(app, database, row, placed)
        } catch (e: Exception) {
            delete(app, placed)
            runCatching { writePending(app, pending(app) - old.key) }
            throw IOException("the library could not be updated: ${e.message}", e)
        }
        if (newId != row.id) app.online.renameCopy(row.id, newId)
    }

    private fun repoint(app: CalmMusic, database: CalmMusicDatabase, row: SongEntity, place: Place): String = when (place) {
        is Place.Path -> DownloadMove.repoint(database, row, place.file)
        is Place.Document -> {
            val (size, modified) = sizeAndTime(app, place.document)
            DownloadMove.repoint(database, row, place.uri, size, modified)
        }
    }

    private fun saveBeside(app: CalmMusic, old: Place, work: File, base: String): Place = when (old) {
        is Place.Path ->
            if (PublicDownloads.isAvailable && PublicDownloads.isPublic(old.file.path)) {
                Place.Path(PublicDownloads.save(app, work, "$base.m4a", "audio/mp4").file)
            } else {
                val dir = old.file.parentFile ?: app.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                    ?: throw IOException("no folder")
                Place.Path(placeBeside(work, dir, base))
            }
        is Place.Document -> Place.Document(saveBesideDocument(app, old.document, work, base))
    }

    /** A private download's new file: beside the old one, under the first free numbered name. */
    private fun placeBeside(source: File, dir: File, base: String): File {
        var n = 2
        var target: File
        do {
            target = File(dir, "$base ($n).m4a")
            n++
        } while (target.exists())
        val part = File(dir, target.name + ".part")
        FileInputStream(source).use { input -> FileOutputStream(part).use { input.copyTo(it) } }
        if (part.length() != source.length() || !part.renameTo(target)) {
            part.delete()
            throw IOException("could not put ${target.name} in place")
        }
        return target
    }

    /**
     * A new document beside [old] in the reader's chosen folder, holding [work], checked by its
     * size and sha256 read back. Removed again if any of that fails.
     */
    private fun saveBesideDocument(app: CalmMusic, old: Uri, work: File, base: String): Uri {
        val resolver = app.contentResolver
        val documentId = DocumentsContract.getDocumentId(old)
        val parentId = documentId.substringBeforeLast('/', "").ifEmpty { throw IOException("no folder for $documentId") }
        val parent = DocumentsContract.buildDocumentUriUsingTree(old, parentId)
        val created = DocumentsContract.createDocument(resolver, parent, "audio/mp4", "$base.m4a")
            ?: throw IOException("the folder would not take $base.m4a")
        try {
            val expected = PublicDownloads.sha256(work)
            resolver.openOutputStream(created, "w")?.use { out ->
                work.inputStream().use { it.copyTo(out, 64 * 1024) }
            } ?: throw IOException("could not open $base.m4a for writing")
            val written = resolver.openInputStream(created)?.use { PublicDownloads.sha256(it) }
                ?: throw IOException("could not read $base.m4a back")
            if (written != expected) throw IOException("the copy of $base.m4a is not the same file")
            return created
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, created) }
            throw e
        }
    }

    private fun sizeAndTime(app: CalmMusic, document: Uri): Pair<Long, Long> =
        app.contentResolver.query(
            document,
            arrayOf(DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            null,
            null,
            null,
        )?.use { if (it.moveToFirst()) it.getLong(0) to it.getLong(1) else null } ?: throw IOException("no such document")

    private fun delete(app: CalmMusic, place: Place): PublicDownloads.Deletion = when (place) {
        is Place.Path -> PublicDownloads.delete(app, place.file)
        is Place.Document -> {
            val gone = runCatching { DocumentsContract.deleteDocument(app.contentResolver, place.document) }.getOrDefault(false)
            if (gone || !exists(app, place)) PublicDownloads.Deletion.Done else PublicDownloads.Deletion.Failed
        }
    }

    private fun rename(app: CalmMusic, place: Place, name: String): Place? = when (place) {
        is Place.Path ->
            if (PublicDownloads.isAvailable && PublicDownloads.isPublic(place.file.path)) {
                PublicDownloads.rename(app, place.file, name)?.let { Place.Path(it) }
            } else {
                File(place.file.parentFile, name).takeIf { place.file.renameTo(it) }?.let { Place.Path(it) }
            }
        is Place.Document -> runCatching {
            DocumentsContract.renameDocument(app.contentResolver, place.document, name)
        }.getOrNull()?.let { Place.Document(it) }?.takeIf { it.name == name }
    }

    /**
     * Finishes the replacements in hand: deletes each old file whose song now names the new
     * one, then gives the new file the old one's name. One whose row still names the old file
     * never moved, and its new file goes instead. Songs in [inUse] wait. Returns whether any
     * song was renamed, which the caller shows by reading the library again.
     */
    suspend fun sweep(app: CalmMusic, inUse: () -> Set<String>): Boolean = withContext(Dispatchers.IO) {
        val pending = pending(app)
        if (pending.isEmpty()) return@withContext false
        val database = CalmMusicDatabase.getDatabase(app)
        val byUri = database.songDao().getAllSongs().associateBy { it.audioUri }
        val left = pending.toMutableMap()
        val playing = inUse()
        var renamed = false
        for ((oldKey, newKey) in pending) {
            val old = Place.ofKey(oldKey)
            val new = Place.ofKey(newKey)
            val newRow = byUri[new.uri]
            val newExists = exists(app, new)
            when {
                byUri.containsKey(old.uri) -> {
                    // The row never moved: the old file is still the song.
                    if (newRow == null && newExists) delete(app, new)
                    Log.i(TAG, "replacement undone, the library still names the old file: ${old.name}")
                    left.remove(oldKey)
                }
                newRow == null && !newExists -> {
                    // The song was removed meanwhile, its new file with it; the old file was
                    // the same song, and left would come back as a download of its own.
                    val gone = delete(app, old)
                    Log.i(TAG, "old file of a removed song deleted: ${old.name} (${gone::class.simpleName})")
                    left.remove(oldKey)
                }
                newRow == null || !newExists -> {
                    Log.w(TAG, "old file kept, the new one is missing: ${old.name}")
                    left.remove(oldKey)
                }
                old.uri in playing || newRow.id in playing || newRow.audioUri in playing ->
                    Log.i(TAG, "old file kept for now, it is in the queue: ${old.name}")
                else -> when (val gone = delete(app, old)) {
                    PublicDownloads.Deletion.Done -> {
                        Log.i(TAG, "old file removed: ${old.name}")
                        left.remove(oldKey)
                        if (takeOldName(app, database, newRow, new, old)) renamed = true
                    }
                    else -> Log.w(TAG, "old file could not be removed: ${old.name} (${gone::class.simpleName})")
                }
            }
        }
        if (left != pending) runCatching { writePending(app, left) }.onFailure { Log.w(TAG, "could not write the replacement record", it) }
        if (renamed) {
            app.hearts.refresh()
            app.nameOverrides.refresh()
        }
        renamed
    }

    /**
     * Gives the new file the name the old one had, "Song.m4a" for "Song (1).m4a", now the old
     * file has gone. If the library will not follow, the file is named back. Whether it was.
     */
    private fun takeOldName(app: CalmMusic, database: CalmMusicDatabase, row: SongEntity, new: Place, old: Place): Boolean {
        val wanted = old.name.substringBeforeLast('.', old.name) + ".m4a"
        if (new.name == wanted) return false
        if (new is Place.Path && File(new.file.parentFile, wanted).exists()) return false
        val renamed = rename(app, new, wanted) ?: return false
        return try {
            val newId = repoint(app, database, row, renamed)
            if (newId != row.id) app.online.renameCopy(row.id, newId)
            Log.i(TAG, "${new.name} renamed ${renamed.name}")
            true
        } catch (e: Exception) {
            val back = rename(app, renamed, new.name)
            Log.w(TAG, "${new.name} kept its name, the library could not follow: ${e.message}" + if (back == null) " (and it could not be named back)" else "")
            false
        }
    }
}
