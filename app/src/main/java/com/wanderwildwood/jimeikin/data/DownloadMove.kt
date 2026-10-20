package com.wanderwildwood.jimeikin.data

import android.net.Uri
import android.os.Environment
import android.util.Log
import com.wanderwildwood.jimeikin.CalmMusic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Moves downloads made before 2.18.0 out of the app's private folder into Music/Music Box,
 * once, so they outlast the app like every download made since; see [PublicDownloads].
 *
 * Each file is copied, the copy checked against the original by size and sha256, and only then
 * is the song's row pointed at it - its id too, for a YouTube download, whose id is its file -
 * along with everything that names the song by that id: playlists, hearts, a name the reader
 * gave it and the record of which video it came from. The private copy is deleted last, and
 * only once the library on screen has been read again, so nothing still showing the old row
 * is left pointing at a file that has gone. A song in the queue being played is left where it
 * is until the next launch.
 *
 * Whatever fails, the old file and the old row are left exactly as they were and the move is
 * tried again next launch. One line per file goes to the log.
 */
object DownloadMove {

    private const val TAG = "DownloadMove"
    private const val YOUTUBE = "YOUTUBE_DOWNLOAD"

    private fun record(app: CalmMusic) = File(app.filesDir, "downloads-moved.txt")

    /** Private copies already moved and waiting to be deleted, old path to new. */
    fun moved(app: CalmMusic): Map<String, String> = runCatching {
        val file = record(app)
        if (!file.exists()) return emptyMap()
        file.readLines().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) null else line.substring(0, tab) to line.substring(tab + 1)
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun writeMoved(app: CalmMusic, moved: Map<String, String>) {
        runCatching {
            val file = record(app)
            if (moved.isEmpty()) {
                file.delete()
                return
            }
            val part = File(file.path + ".part")
            part.writeText(moved.entries.joinToString("") { "${it.key}\t${it.value}\n" })
            part.renameTo(file)
        }.onFailure { Log.w(TAG, "could not write the move record", it) }
    }

    /**
     * Moves every download still in the private folder whose song is not in [inUse] (audio
     * uris and ids). Returns how many moved; the caller reads the library again and then
     * calls [sweep].
     */
    suspend fun move(app: CalmMusic, inUse: () -> Set<String>): Int = withContext(Dispatchers.IO) {
        if (!PublicDownloads.isAvailable) return@withContext 0
        val privateDir = app.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: return@withContext 0
        val database = CalmMusicDatabase.getDatabase(app)
        val songDao = database.songDao()
        val rows = songDao.getSongsBySourceType(YOUTUBE) + songDao.getSongsBySourceType(SubsonicDownloader.SOURCE_TYPE)
        var count = 0
        for (row in rows) {
            val old = PublicDownloads.fileOf(row.audioUri) ?: continue
            if (!old.path.startsWith(privateDir.path + "/")) continue
            if (!old.exists()) {
                Log.i(TAG, "not moved, its file is missing: ${old.name}")
                continue
            }
            val playing = inUse()
            if (row.id in playing || row.audioUri in playing) {
                Log.i(TAG, "not moved yet, it is in the queue: ${old.name}")
                continue
            }
            if (moveOne(app, database, row, old)) count++
        }
        if (count > 0) {
            app.hearts.refresh()
            app.nameOverrides.refresh()
        }
        count
    }

    private fun moveOne(app: CalmMusic, database: CalmMusicDatabase, row: SongEntity, old: File): Boolean {
        val saved = try {
            if (row.sourceType == YOUTUBE) {
                PublicDownloads.save(app, old, old.name, PublicDownloads.mimeTypeOf(old.name) ?: "audio/mp4")
            } else {
                SubsonicDownloader.savePublic(app, row, old, null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "not moved, the copy failed: ${old.name}: ${e.message}")
            return false
        } ?: run {
            Log.i(TAG, "not moved, its kind of audio could not be told: ${old.name}")
            return false
        }

        val newId = try {
            repoint(database, row, saved.file)
        } catch (e: Exception) {
            // The row still names the private copy, which is untouched; the new copy goes.
            PublicDownloads.delete(app, saved.file)
            Log.w(TAG, "not moved, the library could not be updated: ${old.name}: ${e.message}")
            return false
        }
        if (newId != row.id) app.youTubeCopies.rename(row.id, newId)
        writeMoved(app, moved(app) + (old.path to saved.file.path))
        Log.i(TAG, "moved ${old.name} to ${saved.file.path}")
        return true
    }

    /**
     * Points [row] at [file] in one transaction, with everything that names the song by its id
     * - playlists, hearts, a name the reader gave it - when the id changes with it, as a YouTube
     * download's does: its id is its file. Returns the song's id from now on. Throws, leaving
     * the library as it was, if the database would not take it. The record of which video it
     * came from is the caller's to rename (YouTubeCopies.rename).
     */
    internal fun repoint(database: CalmMusicDatabase, row: SongEntity, file: File): String {
        val oldUri = row.audioUri
        val newUri = Uri.fromFile(file).toString()
        // A YouTube download's id is its file; a kept server song keeps the server's id.
        val newId = if (row.id == oldUri) newUri else row.id
        database.runInTransaction {
            val db = database.openHelper.writableDatabase
            if (newId != row.id) {
                db.execSQL("DELETE FROM songs WHERE id = ?", arrayOf(newId))
                db.execSQL("UPDATE songs SET id = ?, audioUri = ? WHERE id = ?", arrayOf(newId, newUri, row.id))
                db.execSQL("UPDATE OR REPLACE playlist_tracks SET songId = ? WHERE songId = ?", arrayOf(newId, row.id))
                db.execSQL(
                    "UPDATE OR REPLACE hearted_songs SET songId = ?, audioUri = ? WHERE songId = ?",
                    arrayOf(newId, newUri, row.id),
                )
                db.execSQL(
                    "UPDATE OR REPLACE name_overrides SET targetId = ? WHERE kind = ? AND targetId = ?",
                    arrayOf(newId, NameOverrides.SONG, row.id),
                )
            } else {
                db.execSQL("UPDATE songs SET audioUri = ? WHERE id = ?", arrayOf(newUri, row.id))
                db.execSQL("UPDATE hearted_songs SET audioUri = ? WHERE songId = ?", arrayOf(newUri, row.id))
            }
            db.execSQL(
                "UPDATE songs SET localFileSizeBytes = ?, localLastModifiedMillis = ? WHERE id = ?",
                arrayOf<Any>(file.length(), file.lastModified(), newId),
            )
        }
        return newId
    }

    /**
     * Deletes the private copies already moved, except any whose song is in [inUse]. A move
     * whose new copy has since gone missing is forgotten instead, and its private copy left,
     * so the next launch finds it again as a download rather than losing it.
     */
    suspend fun sweep(app: CalmMusic, inUse: () -> Set<String>) = withContext(Dispatchers.IO) {
        val moved = moved(app)
        if (moved.isEmpty()) return@withContext
        val left = moved.toMutableMap()
        val playing = inUse()
        for ((oldPath, newPath) in moved) {
            val old = File(oldPath)
            val new = File(newPath)
            when {
                !old.exists() -> left.remove(oldPath)
                Uri.fromFile(old).toString() in playing -> Log.i(TAG, "private copy kept for now, it is in the queue: ${old.name}")
                !new.exists() || new.length() != old.length() -> {
                    Log.w(TAG, "private copy kept, the moved copy is missing: ${old.name}")
                    left.remove(oldPath)
                }
                old.delete() -> {
                    Log.i(TAG, "private copy removed: ${old.name}")
                    left.remove(oldPath)
                }
                else -> Log.w(TAG, "private copy could not be removed: ${old.name}")
            }
        }
        if (left != moved) writeMoved(app, left)
    }
}
