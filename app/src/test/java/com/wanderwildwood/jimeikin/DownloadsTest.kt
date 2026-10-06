package com.wanderwildwood.jimeikin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadsTest {

    private fun item(
        id: String,
        state: DownloadState,
        title: String = "Song $id",
        percent: Int? = null,
        updatedAt: Long = 0L,
        kind: DownloadKind = DownloadKind.YOUTUBE,
    ) = DownloadItem(
        id = id,
        kind = kind,
        songId = "song-$id",
        title = title,
        artist = "The Lanterns",
        state = state,
        percent = percent,
        updatedAt = updatedAt,
    )

    /** English, as strings.xml says it, so the tests read as the line does. */
    private val words = object : DownloadLineWords {
        override fun underway(position: Int, total: Int) = "Downloading $position of $total"
        override fun underwayOne() = "Downloading"
        override fun percent(percent: Int) = "$percent%"
        override fun downloaded(count: Int) = if (count == 1) "One song downloaded" else "$count songs downloaded"
        override fun failed(count: Int) = if (count == 1) "One song did not download" else "$count songs did not download"
        override fun downloadedOne(title: String) = "Downloaded \"$title\""
        override fun failedOne(title: String) = "Could not download \"$title\""
        override fun join(first: String, second: String) = "$first · $second"
    }

    private fun say(run: List<DownloadItem>) = Downloads.line(run)?.let { Downloads.text(it, words) }

    @Test
    fun percentMovesInTens() {
        assertEquals(0, Downloads.step(0f))
        assertEquals(0, Downloads.step(0.09f))
        assertEquals(10, Downloads.step(0.10f))
        assertEquals(40, Downloads.step(0.42f))
        assertEquals(90, Downloads.step(0.999f))
        assertEquals(100, Downloads.step(1f))
        assertEquals(100, Downloads.step(1.7f))
        assertEquals(0, Downloads.step(-0.2f))
    }

    @Test
    fun aStepOnlyChangesTenTimesASong() {
        val seen = (0..1000).map { Downloads.step(it / 1000f) }.distinct()
        assertEquals(listOf(0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100), seen)
    }

    @Test
    fun underwayCountsFromTheFinishedOnes() {
        val run = listOf(
            item("1", DownloadState.DOWNLOADED),
            item("2", DownloadState.FAILED),
            item("3", DownloadState.CANCELED),
            item("4", DownloadState.DOWNLOADING, title = "River Song", percent = 40),
        ) + (5..12).map { item("$it", DownloadState.WAITING) }
        assertEquals(DownloadLine.Underway(4, 12, "River Song", 40), Downloads.line(run))
        assertEquals("Downloading 4 of 12 · River Song · 40%", say(run))
    }

    @Test
    fun theScreenLineSetsThePercentApart() {
        val line = Downloads.line(listOf(item("1", DownloadState.DOWNLOADING, title = "River Song", percent = 40), item("2", DownloadState.WAITING)))!!
        assertEquals("Downloading 1 of 2 · River Song", Downloads.text(line, words, withPercent = false))
    }

    @Test
    fun noPercentUntilOneIsKnown() {
        val run = listOf(item("1", DownloadState.DOWNLOADING, title = "River Song"), item("2", DownloadState.WAITING))
        assertEquals("Downloading 1 of 2 · River Song", say(run))
    }

    @Test
    fun aWaitingSongIsNamedBeforeItStarts() {
        val run = listOf(item("1", DownloadState.DOWNLOADED), item("2", DownloadState.WAITING, title = "Hill Air", percent = 30))
        assertEquals("Downloading 2 of 2 · Hill Air", say(run))
    }

    @Test
    fun oneSongIsNotCounted() {
        val run = listOf(item("1", DownloadState.DOWNLOADING, title = "River Song", percent = 70))
        assertEquals("Downloading · River Song · 70%", say(run))
    }

    @Test
    fun summaryOfAllDownloaded() {
        val run = (1..12).map { item("$it", DownloadState.DOWNLOADED) }
        assertEquals(DownloadLine.Finished(12, 0, null), Downloads.line(run))
        assertEquals("12 songs downloaded", say(run))
    }

    @Test
    fun summarySaysWhatDidNotDownload() {
        val run = (1..10).map { item("$it", DownloadState.DOWNLOADED) } +
            item("11", DownloadState.FAILED) + item("12", DownloadState.NOT_FINISHED) + item("13", DownloadState.CANCELED)
        assertEquals("10 songs downloaded · 2 songs did not download", say(run))
    }

    @Test
    fun summaryWhenNothingDownloaded() {
        val run = listOf(item("1", DownloadState.FAILED), item("2", DownloadState.FAILED))
        assertEquals("2 songs did not download", say(run))
    }

    @Test
    fun summaryOfOneSongNamesIt() {
        assertEquals("Downloaded \"Hill Air\"", say(listOf(item("1", DownloadState.DOWNLOADED, title = "Hill Air"))))
        assertEquals("Could not download \"Hill Air\"", say(listOf(item("1", DownloadState.FAILED, title = "Hill Air"))))
    }

    @Test
    fun nothingToSayForAnEmptyOrCanceledRun() {
        assertNull(Downloads.line(emptyList()))
        assertNull(Downloads.line(listOf(item("1", DownloadState.CANCELED), item("2", DownloadState.CANCELED))))
    }

    @Test
    fun interruptedDownloadsComeBackAsNotFinished() {
        val restored = Downloads.restored(
            listOf(
                item("1", DownloadState.DOWNLOADING, percent = 60),
                item("2", DownloadState.WAITING),
                item("3", DownloadState.DOWNLOADED),
                item("4", DownloadState.FAILED),
            ),
        )
        assertEquals(
            listOf(DownloadState.NOT_FINISHED, DownloadState.NOT_FINISHED, DownloadState.DOWNLOADED, DownloadState.FAILED),
            restored.map { it.state },
        )
        assertNull(restored[0].percent)
        assertEquals(true, restored[0].state.canRetry)
    }

    @Test
    fun historyKeepsAWeekAndAHundred() {
        val day = 24L * 60 * 60 * 1000
        val now = 30 * day
        val old = item("old", DownloadState.DOWNLOADED, updatedAt = now - 8 * day)
        val recent = (1..120).map { item("r$it", DownloadState.DOWNLOADED, updatedAt = now - it * 1000L) }
        val waiting = item("w", DownloadState.WAITING, updatedAt = now - 20 * day)
        val kept = Downloads.prune(listOf(old) + recent + waiting, now)
        assertEquals(101, kept.size)
        assertEquals(false, kept.any { it.id == "old" })
        assertEquals(true, kept.any { it.id == "w" })
        assertEquals(true, kept.any { it.id == "r1" })
        assertEquals(false, kept.any { it.id == "r101" })
    }

    @Test
    fun encodingRoundTrips() {
        val items = listOf(
            DownloadItem(
                id = "a",
                kind = DownloadKind.YOUTUBE,
                songId = "dQw-4",
                title = "Tabs\tand\nnew lines \\ too",
                artist = "The Lanterns",
                state = DownloadState.FAILED,
                album = "Hill Air",
                albumArtist = "Lanterns",
                trackNumber = 3,
                discNumber = 1,
                durationMillis = 215_000L,
                audioUri = "dQw-4",
                reason = "Probe failed: 403",
                updatedAt = 1_700_000_000_000L,
            ),
            item("b", DownloadState.DOWNLOADED, kind = DownloadKind.SERVER, updatedAt = 5L),
        )
        assertEquals(items, Downloads.decode(Downloads.encode(items)))
    }

    @Test
    fun percentIsNotWritten() {
        val decoded = Downloads.decode(Downloads.encode(listOf(item("a", DownloadState.DOWNLOADING, percent = 50))))
        assertNull(decoded.single().percent)
    }

    @Test
    fun unreadableLinesAreSkipped() {
        val good = Downloads.encode(listOf(item("a", DownloadState.DOWNLOADED)))
        val decoded = Downloads.decode("nonsense\n2\tfrom\ta\tlater\tversion\n" + good + "\n")
        assertEquals(listOf("a"), decoded.map { it.id })
    }
}
