package com.wanderwildwood.jimeikin.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.File

/**
 * Knows Music Box's own downloads again after a reinstall.
 *
 * A download outlasts the app in Music/Music Box, but a new install knows nothing of it: found
 * through a chosen folder it came back as a plain song of the reader's own, and YouTube still
 * offered it to stream as though it were not on the phone. Since 2.18.1 a YouTube download
 * names its video in its comment tag, and that is read here ([OnlineMusic.idInComment]).
 *
 * Only files directly in a Music Box folder are read, and only their index, not the audio
 * ([Mp4Boxes]). What each said is kept by its document id, size and time, so a file is read
 * once and not at every scan. A file with no video in its tags stays an ordinary song.
 * (Music-server downloads carry nothing of where they came from, so they stay ordinary songs.)
 */
object DownloadRecognition {

    private const val TAG = "DownloadRecognition"

    private fun cacheFile(context: Context) = File(context.filesDir, "music-box-tags.txt")

    /** A file a scan found: how the picker names it, its address, and what it was when found. */
    data class Found(val documentId: String, val uri: Uri, val size: Long, val lastModified: Long)

    /** Whether the file the picker names [documentId] sits directly in a Music Box folder. */
    fun isInMusicBox(documentId: String, folder: String = PublicDownloads.relativePath): Boolean {
        val path = documentId.substringAfter(':', "")
        return path.substringBeforeLast('/', "") + "/" == folder
    }

    /** The document id of a content:// address, or null for anything else. */
    fun documentIdOf(uri: String?): String? =
        uri?.takeIf { it.startsWith("content://") }?.let { runCatching { DocumentsContract.getDocumentId(Uri.parse(it)) }.getOrNull() }

    /**
     * The video each of [files] came from, by document id; a file whose tags name none is left
     * out. Reads only files not already read as they are now.
     */
    fun videoIds(context: Context, files: List<Found>): Map<String, String> {
        // An edition with no catalogue has nothing to know them by, and leaves the record of
        // what each file said as the full edition wrote it.
        val online = (context.applicationContext as com.wanderwildwood.jimeikin.CalmMusic).online
        if (files.isEmpty() || !online.available) return emptyMap()
        val cache = load(context)
        val now = HashMap<String, Entry>()
        var read = 0
        for (file in files) {
            val known = cache[file.documentId]?.takeIf { it.size == file.size && it.lastModified == file.lastModified }
            now[file.documentId] = known ?: run {
                read++
                val comment = Mp4Boxes.read(context, file.uri)?.comment
                Entry(file.size, file.lastModified, online.idInComment(comment).orEmpty())
            }
        }
        if (read > 0 || now.keys != cache.keys) save(context, now)
        if (read > 0) Log.i(TAG, "read the tags of $read files in Music Box")
        return now.filterValues { it.videoId.isNotEmpty() }.mapValues { it.value.videoId }
    }

    private data class Entry(val size: Long, val lastModified: Long, val videoId: String)

    private fun load(context: Context): Map<String, Entry> = runCatching {
        val file = cacheFile(context)
        if (!file.exists()) return emptyMap()
        file.readLines().mapNotNull { line ->
            val f = line.split('\t')
            if (f.size != 4) return@mapNotNull null
            val size = f[1].toLongOrNull() ?: return@mapNotNull null
            val modified = f[2].toLongOrNull() ?: return@mapNotNull null
            f[0] to Entry(size, modified, f[3])
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun save(context: Context, entries: Map<String, Entry>) {
        runCatching {
            val file = cacheFile(context)
            val part = File(file.path + ".part")
            // A document id is a path, which may hold anything but a tab or a line break in practice.
            part.writeText(
                entries.entries
                    .filter { '\t' !in it.key && '\n' !in it.key }
                    .joinToString("") { (id, e) -> "$id\t${e.size}\t${e.lastModified}\t${e.videoId}\n" },
            )
            part.renameTo(file)
        }.onFailure { Log.w(TAG, "could not keep what the tags said", it) }
    }
}
