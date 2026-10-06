package com.wanderwildwood.jimeikin

import android.util.Log
import com.wanderwildwood.jimeikin.ui.SongUiModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

/**
 * Which YouTube songs are already on the phone, and as which file.
 *
 * A YouTube song is known by its video id until it is downloaded; then it becomes a row keyed
 * on its file, and the video id is gone from it. Every list YouTube hands back - an album, an
 * artist's songs, a search - still names the video, so a page could not tell that its songs
 * were here: an album's Download button stayed up after the last of them landed, and pressing
 * it downloaded the whole album again. This remembers each download's video id as it finishes,
 * in a small file beside the download list.
 *
 * Songs downloaded before there was a record are matched by title and artist instead, folded
 * the way the library's ids are, and recorded when found.
 */
class YouTubeCopies(private val file: File) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private val _recorded = MutableStateFlow(load())
    /** Video id to the id of its downloaded song. */
    val recorded: StateFlow<Map<String, String>> = _recorded.asStateFlow()

    private fun load(): Map<String, String> = runCatching {
        if (file.exists()) YouTubeCopyMatch.decode(file.readText()) else emptyMap()
    }.getOrElse {
        Log.w(TAG, "could not read the download record", it)
        emptyMap()
    }

    fun record(videoId: String, songId: String) {
        if (_recorded.value[videoId] == songId) return
        _recorded.update { it + (videoId to songId) }
        save()
    }

    /** The song has gone - deleted, or its file removed - and its video is not here any more. */
    fun forget(songId: String) {
        if (songId !in _recorded.value.values) return
        _recorded.update { map -> map.filterValues { it != songId } }
        save()
    }

    /**
     * The copy of [song] on the phone among [downloads], if it is a YouTube song that has one.
     * One found by title and artist is recorded, so it is known by its video from then on.
     */
    fun copyOf(song: SongUiModel, downloads: List<SongUiModel>): SongUiModel? {
        val recorded = _recorded.value
        val copy = YouTubeCopyMatch.copyOf(song, recorded, downloads) ?: return null
        if (recorded[song.id] != copy.id) record(song.id, copy.id)
        return copy
    }

    /** [songs] with each YouTube song already here swapped for its copy; see [YouTubeCopyMatch.withCopies]. */
    fun withCopies(songs: List<SongUiModel>, downloads: List<SongUiModel>): List<SongUiModel> {
        songs.forEach { copyOf(it, downloads) }
        return YouTubeCopyMatch.withCopies(songs, _recorded.value, downloads)
    }

    /** The YouTube songs in [songs] still to download; see [YouTubeCopyMatch.leftToDownload]. */
    fun leftToDownload(
        songs: List<SongUiModel>,
        downloads: List<SongUiModel>,
        isUnderway: (String) -> Boolean,
    ): List<SongUiModel> {
        songs.forEach { copyOf(it, downloads) }
        return YouTubeCopyMatch.leftToDownload(songs, _recorded.value, downloads, isUnderway)
    }

    private fun save() {
        val snapshot = _recorded.value
        scope.launch {
            synchronized(lock) {
                runCatching {
                    val part = File(file.path + ".part")
                    part.writeText(YouTubeCopyMatch.encode(snapshot))
                    part.renameTo(file)
                }.onFailure { Log.w(TAG, "could not write the download record", it) }
            }
        }
    }

    private companion object {
        const val TAG = "YouTubeCopies"
    }
}

/** The matching itself, apart from the file, so it can be tested on invented songs. */
object YouTubeCopyMatch {

    /** Within this, two lengths are the same recording; a file's length is never exactly YouTube's. */
    private const val SAME_LENGTH_MILLIS = 3_000L

    /** Folded as the library's ids are: trimmed, runs of spaces made one, lower case. */
    fun key(text: String): String = text.trim().replace(Regex("\\s+"), " ").lowercase()

    /**
     * The downloaded copy of [song], or null when it is not a YouTube song or has none.
     *
     * The record is asked first. Failing that, a download with the same title and artist (and
     * the same length, where both are known) is taken - unless the record already gives that
     * download to some other video, which is a second recording of the same title.
     */
    fun copyOf(
        song: SongUiModel,
        recorded: Map<String, String>,
        downloads: List<SongUiModel>,
    ): SongUiModel? {
        if (song.sourceType != "YOUTUBE") return null
        recorded[song.id]?.let { id -> downloads.firstOrNull { it.id == id } }?.let { return it }

        val claimed = recorded.filterKeys { it != song.id }.values.toSet()
        val title = key(song.title)
        val artist = key(song.artist)
        if (title.isEmpty()) return null
        return downloads.firstOrNull { copy ->
            copy.sourceType == "YOUTUBE_DOWNLOAD" &&
                copy.id !in claimed &&
                key(copy.title) == title &&
                key(copy.artist) == artist &&
                sameLength(copy.durationMillis, song.durationMillis)
        }
    }

    private fun sameLength(a: Long?, b: Long?): Boolean =
        a == null || b == null || a <= 0L || b <= 0L || abs(a - b) <= SAME_LENGTH_MILLIS

    /**
     * Each YouTube song in [songs] that is already here becomes its copy, keeping the place the
     * list gave it - its track and disc - so a page does not reorder as downloads land. A song
     * listed twice, once as the video and once as its file, is shown once.
     */
    fun withCopies(
        songs: List<SongUiModel>,
        recorded: Map<String, String>,
        downloads: List<SongUiModel>,
    ): List<SongUiModel> = songs
        .map { song ->
            val copy = copyOf(song, recorded, downloads) ?: return@map song
            copy.copy(
                trackNumber = song.trackNumber ?: copy.trackNumber,
                discNumber = song.discNumber ?: copy.discNumber,
            )
        }
        .distinctBy { it.id }

    /** The YouTube songs in [songs] still to download: not here, and not already asked for. */
    fun leftToDownload(
        songs: List<SongUiModel>,
        recorded: Map<String, String>,
        downloads: List<SongUiModel>,
        isUnderway: (String) -> Boolean,
    ): List<SongUiModel> = songs.filter {
        it.sourceType == "YOUTUBE" && !isUnderway(it.id) && copyOf(it, recorded, downloads) == null
    }.distinctBy { it.id }

    /** By disc, then track; a song with no number keeps the order the list gave it, after them. */
    fun inAlbumOrder(songs: List<SongUiModel>): List<SongUiModel> =
        songs.sortedWith(compareBy({ it.discNumber ?: 1 }, { it.trackNumber ?: Int.MAX_VALUE }))

    fun encode(recorded: Map<String, String>): String =
        recorded.entries.joinToString(separator = "\n", postfix = if (recorded.isEmpty()) "" else "\n") {
            "${it.key}\t${it.value}"
        }

    fun decode(text: String): Map<String, String> = text.lineSequence()
        .mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0 || tab == line.length - 1) null else line.substring(0, tab) to line.substring(tab + 1)
        }
        .toMap()
}
