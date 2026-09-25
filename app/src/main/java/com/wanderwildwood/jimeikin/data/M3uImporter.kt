package com.wanderwildwood.jimeikin.data

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Turns the .m3u files found during a library scan into playlists.
 *
 * A playlist made on a computer names its songs by path. The songs here are SAF documents
 * whose ids are content:// uris with no path in them, so the match is made on the path each
 * file had *inside the chosen folder*, which the scan records as it walks.
 *
 * An .m3u routinely names files that are not on this phone. Entries that find nothing are
 * dropped without comment; a playlist that comes out half the length of its file is the
 * honest result, and saying so on every scan would be noise.
 */
object M3uImporter {

    /** Playlists are re-read on every scan, so the id has to be the file, not the moment. */
    private fun playlistIdFor(relativePath: String) = "M3U:$relativePath"

    suspend fun importAll(
        context: Context,
        playlistFiles: List<ScannedPlaylistFile>,
        relativePathBySongId: Map<String, String>,
        playlistDao: PlaylistDao,
    ) {
        if (playlistFiles.isEmpty()) return

        // Two ways in: the full path inside the chosen folder, and the bare file name for
        // the many .m3u files that were written beside their music.
        val byPath = mutableMapOf<String, String>()
        val byFileName = mutableMapOf<String, MutableList<String>>()
        for ((songId, path) in relativePathBySongId) {
            byPath[path.lowercase()] = songId
            byFileName.getOrPut(path.substringAfterLast('/').lowercase()) { mutableListOf() }
                .add(songId)
        }

        // ⚠ Only a file that has changed since it was last read. Rewriting every playlist on
        // every scan, which is what this did, undid whatever was done to it on the phone --
        // renamed, reordered, songs added -- and brought back one that had been deleted, every
        // time the library was scanned. The file still wins when it changes: edited on a
        // computer and rescanned, it says what the file says.
        val seen = context.getSharedPreferences(IMPORTED_PREFS, Context.MODE_PRIVATE)
        val existing = playlistDao.getAllPlaylistsWithSongCount().map { it.id }.toSet()
        for (file in playlistFiles) {
            val stamp = "${file.lastModified}:${file.size}"
            val id = playlistIdFor(file.relativePath)
            if (file.lastModified > 0 && seen.getString(id, null) == stamp) continue
            // First scan since this was kept: a playlist already here is taken as it stands,
            // so updating does not undo edits one last time.
            if (seen.getString(id, null) == null && id in existing) {
                seen.edit().putString(id, stamp).apply()
                continue
            }
            val entries = readEntries(context, file) ?: continue
            val songIds = mutableListOf<String>()
            for (entry in entries) {
                val resolved = resolve(entry, file.directoryPath, byPath, byFileName)
                if (resolved != null && resolved !in songIds) songIds.add(resolved)
            }
            if (songIds.isEmpty()) continue

            playlistDao.upsertPlaylist(
                PlaylistEntity(
                    id = id,
                    name = file.name.substringBeforeLast('.'),
                ),
            )
            // Rewritten whole each scan, so editing the file on a computer and rescanning
            // says what the file says rather than merging two histories.
            playlistDao.deleteTracksForPlaylist(id)
            playlistDao.upsertTracks(
                songIds.mapIndexed { index, songId ->
                    PlaylistTrackEntity(playlistId = id, songId = songId, position = index)
                },
            )
            seen.edit().putString(id, stamp).apply()
        }
    }

    /** Which version of each .m3u was last read in, as "lastModified:size" by playlist id. */
    private const val IMPORTED_PREFS = "m3u_imported"

    private fun readEntries(context: Context, file: ScannedPlaylistFile): List<String>? {
        return try {
            context.contentResolver.openInputStream(file.uri)?.use { stream ->
                BufferedReader(InputStreamReader(stream)).readLines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * An entry may be relative to the .m3u's own folder, absolute on the machine that wrote
     * it, or Windows-shaped. Only the tail is of any use here.
     */
    private fun resolve(
        entry: String,
        directoryPath: String,
        byPath: Map<String, String>,
        byFileName: Map<String, MutableList<String>>,
    ): String? {
        if (entry.startsWith("http://") || entry.startsWith("https://")) return null

        val cleaned = entry.replace('\\', '/').trim()
        val segments = mutableListOf<String>()
        if (!cleaned.startsWith("/") && directoryPath.isNotEmpty()) {
            segments.addAll(directoryPath.split('/'))
        }
        for (part in cleaned.split('/')) {
            when {
                part.isEmpty() || part == "." -> Unit
                part == ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.size - 1)
                else -> segments.add(part)
            }
        }
        if (segments.isEmpty()) return null

        val joined = segments.joinToString("/").lowercase()
        byPath[joined]?.let { return it }

        // Then the longest tail of the path that matches something we scanned, which covers
        // an .m3u written against a different root.
        for (start in segments.indices) {
            val tail = segments.subList(start, segments.size).joinToString("/").lowercase()
            byPath[tail]?.let { return it }
        }

        // Last, the bare file name - but only where it names one song and no more.
        val candidates = byFileName[segments.last().lowercase()]
        return if (candidates != null && candidates.size == 1) candidates[0] else null
    }
}
