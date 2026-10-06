package com.wanderwildwood.jimeikin

/**
 * What Downloads holds, and the arithmetic over it that the line along the bottom of the
 * screen and the notification both say. Nothing here touches Android, so all of it is tested
 * as plain Kotlin; [DownloadQueue] is the part that does the downloading.
 */

/** Where a download comes from, which decides how it is fetched and what it becomes. */
enum class DownloadKind { YOUTUBE, SERVER }

enum class DownloadState {
    WAITING,
    DOWNLOADING,
    DOWNLOADED,
    FAILED,
    CANCELED,

    /** Waiting or underway when the app last stopped, and never finished. */
    NOT_FINISHED,
    ;

    val isActive: Boolean get() = this == WAITING || this == DOWNLOADING
    val canRetry: Boolean get() = this == FAILED || this == CANCELED || this == NOT_FINISHED
}

/**
 * One song asked for. A YouTube song carries what the download needs to tag and file it,
 * since nothing else remembers a search result; a server song only needs its row, which the
 * download reads again when its turn comes.
 *
 * [percent] is null until the download can say how far along it is, and stays null for a
 * server that sends no length.
 */
data class DownloadItem(
    val id: String,
    val kind: DownloadKind,
    val songId: String,
    val title: String,
    val artist: String,
    val state: DownloadState,
    val album: String? = null,
    val albumArtist: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val durationMillis: Long? = null,
    val audioUri: String? = null,
    val percent: Int? = null,
    val reason: String? = null,
    val updatedAt: Long = 0L,
)

/** What the line along the bottom says, before it is put into words. */
sealed interface DownloadLine {
    /** [position] of [total] in the songs asked for since the queue was last empty. */
    data class Underway(val position: Int, val total: Int, val title: String, val percent: Int?) : DownloadLine

    /** The run is over. [onlyTitle] is the song's title when the run was a single song. */
    data class Finished(val downloaded: Int, val failed: Int, val onlyTitle: String?) : DownloadLine
}

/** The words the line is made of; strings.xml in the app, plain stand-ins in the tests. */
interface DownloadLineWords {
    fun underway(position: Int, total: Int): String
    fun underwayOne(): String
    fun percent(percent: Int): String
    fun downloaded(count: Int): String
    fun failed(count: Int): String
    fun downloadedOne(title: String): String
    fun failedOne(title: String): String
    fun join(first: String, second: String): String
}

object Downloads {

    /**
     * Progress is said in tens. The panel redraws in full, and a percent that moved by one
     * would redraw the line, the row in Downloads and the notification a hundred times a song.
     */
    const val PERCENT_STEP = 10

    /** How many finished downloads Downloads remembers, and for how long. */
    const val HISTORY_LIMIT = 100
    const val HISTORY_DAYS = 7
    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun step(fraction: Float): Int {
        val percent = (fraction * 100f).toInt().coerceIn(0, 100)
        return percent / PERCENT_STEP * PERCENT_STEP
    }

    /**
     * The line for the songs of one run -- those asked for since the queue was last empty --
     * or null when there is nothing to say: no run, or a run that was all canceled.
     */
    fun line(run: List<DownloadItem>): DownloadLine? {
        if (run.isEmpty()) return null
        val current = run.firstOrNull { it.state == DownloadState.DOWNLOADING }
            ?: run.firstOrNull { it.state == DownloadState.WAITING }
        if (current != null) {
            val behind = run.count { !it.state.isActive }
            return DownloadLine.Underway(
                position = behind + 1,
                total = run.size,
                title = current.title,
                percent = current.percent.takeIf { current.state == DownloadState.DOWNLOADING },
            )
        }
        val downloaded = run.count { it.state == DownloadState.DOWNLOADED }
        val failed = run.count { it.state == DownloadState.FAILED || it.state == DownloadState.NOT_FINISHED }
        if (downloaded == 0 && failed == 0) return null
        return DownloadLine.Finished(downloaded, failed, run.singleOrNull()?.title)
    }

    /**
     * [withPercent] false leaves the percent off, for the line on screen, which sets it apart
     * at the right where a long title cannot push it out of sight.
     */
    fun text(line: DownloadLine, words: DownloadLineWords, withPercent: Boolean = true): String = when (line) {
        is DownloadLine.Underway -> {
            val head = if (line.total == 1) words.underwayOne() else words.underway(line.position, line.total)
            val withTitle = if (line.title.isBlank()) head else words.join(head, line.title)
            if (line.percent == null || !withPercent) withTitle else words.join(withTitle, words.percent(line.percent))
        }
        is DownloadLine.Finished -> when {
            line.onlyTitle != null && line.downloaded == 1 -> words.downloadedOne(line.onlyTitle)
            line.onlyTitle != null -> words.failedOne(line.onlyTitle)
            line.failed == 0 -> words.downloaded(line.downloaded)
            line.downloaded == 0 -> words.failed(line.failed)
            else -> words.join(words.downloaded(line.downloaded), words.failed(line.failed))
        }
    }

    /**
     * What is kept across a restart: everything still to do, and of the finished, the newest
     * [HISTORY_LIMIT] from the last [HISTORY_DAYS] days.
     */
    fun prune(items: List<DownloadItem>, now: Long): List<DownloadItem> {
        val cutoff = now - HISTORY_DAYS * DAY_MS
        val keptFinished = items
            .filter { !it.state.isActive && it.updatedAt >= cutoff }
            .sortedByDescending { it.updatedAt }
            .take(HISTORY_LIMIT)
            .map { it.id }
            .toSet()
        return items.filter { it.state.isActive || it.id in keptFinished }
    }

    /**
     * The list as it is read back after the app stopped. Whatever was waiting or underway did
     * not finish, and says so, rather than claiming to be under way with nothing doing it.
     */
    fun restored(items: List<DownloadItem>): List<DownloadItem> = items.map {
        if (it.state.isActive) it.copy(state = DownloadState.NOT_FINISHED, percent = null) else it
    }

    // One download a line, its fields between tabs. The fields are a title and an artist and
    // their like, so the only characters that need escaping are the separators themselves.
    private const val VERSION = "1"

    fun encode(items: List<DownloadItem>): String = buildString {
        for (item in items) {
            val fields = listOf(
                VERSION,
                item.id,
                item.kind.name,
                item.songId,
                item.title,
                item.artist,
                item.state.name,
                item.album.orEmpty(),
                item.albumArtist.orEmpty(),
                item.trackNumber?.toString().orEmpty(),
                item.discNumber?.toString().orEmpty(),
                item.durationMillis?.toString().orEmpty(),
                item.audioUri.orEmpty(),
                item.reason.orEmpty(),
                item.updatedAt.toString(),
            )
            append(fields.joinToString("\t") { escape(it) })
            append('\n')
        }
    }

    fun decode(text: String): List<DownloadItem> = text.lineSequence().mapNotNull { line ->
        val f = line.split('\t').map { unescape(it) }
        if (f.size < 15 || f[0] != VERSION) return@mapNotNull null
        runCatching {
            DownloadItem(
                id = f[1],
                kind = DownloadKind.valueOf(f[2]),
                songId = f[3],
                title = f[4],
                artist = f[5],
                state = DownloadState.valueOf(f[6]),
                album = f[7].ifEmpty { null },
                albumArtist = f[8].ifEmpty { null },
                trackNumber = f[9].toIntOrNull(),
                discNumber = f[10].toIntOrNull(),
                durationMillis = f[11].toLongOrNull(),
                audioUri = f[12].ifEmpty { null },
                reason = f[13].ifEmpty { null },
                updatedAt = f[14].toLongOrNull() ?: 0L,
            )
        }.getOrNull()
    }.toList()

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

    private fun unescape(s: String): String {
        if ('\\' !in s) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    't' -> out.append('\t')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    else -> out.append(s[i + 1])
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}
