package com.wanderwildwood.jimeikin.data

import android.net.Uri
import android.os.Environment
import android.util.Log
import com.wanderwildwood.jimeikin.CalmMusic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Makes the YouTube downloads made before 2.18.1 real .m4a files, once. They are Opus in WebM
 * under a .m4a name, with no tags in them; see [YouTubeAudioFile].
 *
 * A download is told by its first bytes, not its name. Its audio is copied into a new MP4
 * file, sample for sample with nothing re-encoded, tagged from the library's own row, and
 * checked: one audio track, as long as the original to within a second, and it decodes. Only
 * then is the song's row pointed at it, in one transaction with its playlists, hearts and any
 * name the reader gave it ([DownloadMove.repoint]). The old file is deleted last, once the
 * library on screen has been read again, and the new file then takes the old one's name.
 *
 * Whatever fails, the old file and the row are left as they were and it is tried again next
 * launch. A file this install did not write - one from before a reinstall - is passed over:
 * replacing it would mean asking about every one. A song in the queue being played waits for
 * the next launch. One line per file goes to the log.
 */
object DownloadRemux {

    private const val TAG = "DownloadRemux"
    private const val YOUTUBE = "YOUTUBE_DOWNLOAD"

    private fun record(app: CalmMusic) = File(app.filesDir, "downloads-remuxed.txt")

    /** Conversions not yet finished with, old path to new: the old file still to be deleted. */
    fun pending(app: CalmMusic): Map<String, String> = runCatching {
        val file = record(app)
        if (!file.exists()) return emptyMap()
        file.readLines().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) null else line.substring(0, tab) to line.substring(tab + 1)
        }.toMap()
    }.getOrDefault(emptyMap())

    /** Both files of every conversion in hand, which nothing but the library's own rows may list. */
    fun pendingPaths(app: CalmMusic): Set<String> = pending(app).flatMapTo(HashSet()) { listOf(it.key, it.value) }

    private fun writePending(app: CalmMusic, pending: Map<String, String>) {
        val file = record(app)
        if (pending.isEmpty()) {
            file.delete()
            return
        }
        val part = File(file.path + ".part")
        part.writeText(pending.entries.joinToString("") { "${it.key}\t${it.value}\n" })
        if (!part.renameTo(file)) throw java.io.IOException("could not write the conversion record")
    }

    /**
     * Converts every YouTube download that is still WebM and whose song is not in [inUse]
     * (audio uris and ids). Returns how many were; the caller reads the library again and then
     * calls [sweep].
     */
    suspend fun convert(app: CalmMusic, inUse: () -> Set<String>): Int = withContext(Dispatchers.IO) {
        if (!YouTubeAudioFile.canRemuxOpus) return@withContext 0
        val database = CalmMusicDatabase.getDatabase(app)
        val rows = database.songDao().getSongsBySourceType(YOUTUBE)
        if (rows.isEmpty()) return@withContext 0
        val privateDir = app.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
        val inHand = pendingPaths(app)
        val videoOf = app.youTubeCopies.recorded.value.entries.associate { (video, song) -> song to video }
        val albums = database.albumDao().getAllAlbums().associateBy { it.id }
        var count = 0
        for (row in rows) {
            val old = PublicDownloads.fileOf(row.audioUri) ?: continue
            if (old.path in inHand || !old.isFile) continue
            if (!YouTubeAudioFile.isWebm(old)) continue
            if (PublicDownloads.isAvailable && !PublicDownloads.isPublic(old.path)) {
                Log.i(TAG, "not converted yet, it is still to be moved: ${old.name}")
                continue
            }
            if (!PublicDownloads.isOwn(app, old)) {
                Log.i(TAG, "not converted, this install did not write it: ${old.name}")
                continue
            }
            val playing = inUse()
            if (row.id in playing || row.audioUri in playing) {
                Log.i(TAG, "not converted yet, it is in the queue: ${old.name}")
                continue
            }
            val tags = YouTubeAudioFile.Tags(
                title = row.title,
                artist = row.artist,
                album = row.album,
                albumArtist = row.albumId?.let { albums[it]?.artist },
                trackNumber = row.trackNumber,
                discNumber = row.discNumber,
                videoId = videoOf[row.id],
            )
            if (convertOne(app, database, row, old, privateDir, tags)) count++
        }
        if (count > 0) {
            app.hearts.refresh()
            app.nameOverrides.refresh()
        }
        count
    }

    private fun convertOne(
        app: CalmMusic,
        database: CalmMusicDatabase,
        row: SongEntity,
        old: File,
        privateDir: File?,
        tags: YouTubeAudioFile.Tags,
    ): Boolean {
        val work = File.createTempFile("remux-", ".m4a", app.cacheDir)
        try {
            val sourceUs = YouTubeAudioFile.remux(old, work) ?: run {
                Log.w(TAG, "not converted, its audio could not be copied: ${old.name}")
                return false
            }
            if (!YouTubeAudioFile.writeTags(work, tags)) Log.w(TAG, "converted without tags, they would not take: ${old.name}")
            if (!YouTubeAudioFile.verify(work, sourceUs)) {
                Log.w(TAG, "not converted, the copy did not check out: ${old.name}")
                return false
            }
            val base = old.name.substringBeforeLast('.', old.name)
            val placed = try {
                if (PublicDownloads.isPublic(old.path)) {
                    PublicDownloads.save(app, work, "$base.m4a", "audio/mp4").file
                } else {
                    placeBeside(work, old.parentFile ?: privateDir ?: return false, base)
                }
            } catch (e: Exception) {
                Log.w(TAG, "not converted, the new file could not be saved: ${old.name}: ${e.message}")
                return false
            }
            // Noted before the row moves, so a kill between the two is put right next launch
            // by [sweep], whichever side of the move it fell.
            try {
                writePending(app, pending(app) + (old.path to placed.path))
            } catch (e: Exception) {
                PublicDownloads.delete(app, placed)
                Log.w(TAG, "not converted, it could not be noted: ${old.name}: ${e.message}")
                return false
            }
            val newId = try {
                DownloadMove.repoint(database, row, placed)
            } catch (e: Exception) {
                PublicDownloads.delete(app, placed)
                runCatching { writePending(app, pending(app) - old.path) }
                Log.w(TAG, "not converted, the library could not be updated: ${old.name}: ${e.message}")
                return false
            }
            if (newId != row.id) app.youTubeCopies.rename(row.id, newId)
            Log.i(TAG, "converted ${old.name} to ${placed.name}")
            return true
        } finally {
            work.delete()
        }
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
            throw java.io.IOException("could not put ${target.name} in place")
        }
        return target
    }

    /**
     * Finishes the conversions in hand: deletes each old file whose song now names the new
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
        for ((oldPath, newPath) in pending) {
            val old = File(oldPath)
            val new = File(newPath)
            val oldUri = Uri.fromFile(old).toString()
            val newRow = byUri[Uri.fromFile(new).toString()]
            when {
                byUri.containsKey(oldUri) -> {
                    // The row never moved: the old file is still the song.
                    if (newRow == null && new.exists()) PublicDownloads.delete(app, new)
                    Log.i(TAG, "conversion undone, the library still names the old file: ${old.name}")
                    left.remove(oldPath)
                }
                newRow == null && !new.exists() -> {
                    // The song was removed meanwhile, its new file with it; the old file was
                    // the same song, and left would come back as a download of its own.
                    val gone = PublicDownloads.delete(app, old)
                    Log.i(TAG, "old file of a removed song deleted: ${old.name} (${gone::class.simpleName})")
                    left.remove(oldPath)
                }
                newRow == null || !new.exists() -> {
                    Log.w(TAG, "old file kept, the converted one is missing: ${old.name}")
                    left.remove(oldPath)
                }
                oldUri in playing || newRow.id in playing || newRow.audioUri in playing ->
                    Log.i(TAG, "old file kept for now, it is in the queue: ${old.name}")
                else -> when (val gone = PublicDownloads.delete(app, old)) {
                    PublicDownloads.Deletion.Done -> {
                        Log.i(TAG, "old file removed: ${old.name}")
                        left.remove(oldPath)
                        if (takeOldName(app, database, newRow, new, old)) renamed = true
                    }
                    else -> Log.w(TAG, "old file could not be removed: ${old.name} (${gone::class.simpleName})")
                }
            }
        }
        if (left != pending) runCatching { writePending(app, left) }.onFailure { Log.w(TAG, "could not write the conversion record", it) }
        if (renamed) {
            app.hearts.refresh()
            app.nameOverrides.refresh()
        }
        renamed
    }

    /**
     * Gives the converted file the name its WebM had, "Song.m4a" for "Song (1).m4a", now the
     * old file has gone. If the library will not follow, the file is named back. Whether it was.
     */
    private fun takeOldName(app: CalmMusic, database: CalmMusicDatabase, row: SongEntity, new: File, old: File): Boolean {
        val wanted = old.name.substringBeforeLast('.', old.name) + ".m4a"
        if (new.name == wanted || File(new.parentFile, wanted).exists()) return false
        val renamed = if (PublicDownloads.isAvailable && PublicDownloads.isPublic(new.path)) {
            PublicDownloads.rename(app, new, wanted)
        } else {
            File(new.parentFile, wanted).takeIf { new.renameTo(it) }
        } ?: return false
        return try {
            val newId = DownloadMove.repoint(database, row, renamed)
            if (newId != row.id) app.youTubeCopies.rename(row.id, newId)
            Log.i(TAG, "${new.name} renamed ${renamed.name}")
            true
        } catch (e: Exception) {
            val back = if (PublicDownloads.isAvailable && PublicDownloads.isPublic(renamed.path)) {
                PublicDownloads.rename(app, renamed, new.name)
            } else {
                new.takeIf { renamed.renameTo(it) }
            }
            Log.w(TAG, "${new.name} kept its name, the library could not follow: ${e.message}" + if (back == null) " (and it could not be named back)" else "")
            false
        }
    }
}
