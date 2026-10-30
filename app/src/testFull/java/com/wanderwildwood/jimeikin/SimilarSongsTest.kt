package com.wanderwildwood.jimeikin

import com.wanderwildwood.jimeikin.ui.SongUiModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SimilarSongsTest {

    private fun song(id: String, sourceType: String, audioUri: String? = id) = SongUiModel(
        id = id,
        title = "Harbour",
        artist = "The Lanterns",
        sourceType = sourceType,
        audioUri = audioUri,
    )

    private fun result(videoId: String) = InnertubeSongResult(
        videoId = videoId,
        title = "Song $videoId",
        artist = "The Lanterns",
        album = null,
        durationMillis = 200_000L,
    )

    private val download = song("file:///music/Harbour.m4a", "YOUTUBE_DOWNLOAD")
    private val recorded = mapOf("vidHarbour1" to download.id)

    @Test
    fun streamedSongIsItsOwnVideo() {
        assertEquals("vidStream01", SimilarSongs.seedVideoId(song("vidStream01", "YOUTUBE"), emptyMap()))
    }

    @Test
    fun downloadIsAskedAboutByTheVideoItCameFrom() {
        assertEquals("vidHarbour1", SimilarSongs.seedVideoId(download, recorded))
    }

    @Test
    fun downloadTheRecordDoesNotKnowIsSkipped() {
        assertNull(SimilarSongs.seedVideoId(download, emptyMap()))
    }

    @Test
    fun plainLocalFilesAndServerSongsAreNotFollowed() {
        val local = song("file:///music/Shingle.flac", "LOCAL_FILE")
        // Even a folder song whose id the record happens to name is not a YouTube download.
        assertNull(SimilarSongs.seedVideoId(local, mapOf("vidX000001" to local.id)))
        assertNull(SimilarSongs.seedVideoId(song("SUBSONIC:1", "SUBSONIC"), emptyMap()))
        assertNull(SimilarSongs.seedVideoId(song("SUBSONIC:2", "SUBSONIC_DOWNLOAD"), emptyMap()))
        assertNull(SimilarSongs.seedVideoId(song("radio:1", "RADIO"), emptyMap()))
    }

    @Test
    fun pickLeavesOutTheSeedWhatIsQueuedAndDownloadsInTheQueue() {
        val other = song("file:///music/Shingle.m4a", "YOUTUBE_DOWNLOAD")
        val queue = listOf(song("vidQueued01", "YOUTUBE"), other, download)
        val record = recorded + ("vidShingle1" to other.id)
        val found = listOf("vidHarbour1", "vidQueued01", "vidShingle1", "vidNew00001", "vidNew00001", "vidNew00002")
            .map(::result)

        val picked = SimilarSongs.pick(found, queue, record, seedVideoId = "vidHarbour1", batch = 20)

        assertEquals(listOf("vidNew00001", "vidNew00002"), picked.map { it.videoId })
    }

    @Test
    fun pickTakesNoMoreThanABatch() {
        val found = (1..30).map { result("vid%07d".format(it)) }
        val picked = SimilarSongs.pick(found, listOf(download), recorded, "vidHarbour1", batch = 20)
        assertEquals(20, picked.size)
        assertEquals("vid0000001", picked.first().videoId)
    }
}
