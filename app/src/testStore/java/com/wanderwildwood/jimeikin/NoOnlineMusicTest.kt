package com.wanderwildwood.jimeikin

import com.wanderwildwood.jimeikin.ui.AlbumUiModel
import com.wanderwildwood.jimeikin.ui.SongUiModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The store edition installed over the full one: what the full edition left behind - songs it
 * streamed, downloads it recorded, tags naming a video - is passed over, and nothing throws but
 * the one refusal the player turns into a skip.
 */
class NoOnlineMusicTest {

    private val streamed = SongUiModel(id = "vid00000001", title = "Lantern Road", artist = "Mara Quill", sourceType = "YOUTUBE", audioUri = "vid00000001")
    private val download = SongUiModel(id = "file:///music/a.m4a", title = "Lantern Road", artist = "Mara Quill", sourceType = "YOUTUBE_DOWNLOAD")

    @Test
    fun nothingIsOffered() = runBlocking {
        val online = NoOnlineMusic
        assertFalse(online.available)
        assertTrue(online.search("lantern").let { it.songs.isEmpty() && it.albums.isEmpty() && it.artists.isEmpty() })
        assertTrue(online.albumSongs(AlbumUiModel(id = "MPREb_x", title = "Lantern Road", artist = "Mara Quill", sourceType = "YOUTUBE")).isEmpty())
        assertNull(online.findArtist("Mara Quill"))
        assertTrue(online.leftToDownload(listOf(streamed), listOf(download)) { false }.isEmpty())
        assertTrue(online.upgradeCandidates { emptySet() }.isEmpty())
    }

    @Test
    fun leftoversAreIgnored() {
        val online = NoOnlineMusic
        // A download's tag names its video; this edition does not read it.
        assertNull(online.idInComment("https://music.youtube.com/watch?v=vid00000001"))
        // Neither a streamed song nor a download asks for similar songs.
        assertNull(online.similarSeed(streamed))
        assertNull(online.similarSeed(download))
        assertNull(online.copyOf(streamed, listOf(download)))
        assertEquals(listOf(streamed), online.withCopies(listOf(streamed), listOf(download)))
        assertTrue(online.recorded.value.isEmpty())
        online.recordCopy("vid00000001", download.id)
        assertTrue(online.recorded.value.isEmpty())
    }

    @Test
    fun aStreamedSongIsSkippedWithAWord() {
        val online = NoOnlineMusic
        val address = online.playAddress(streamed.id)
        val host = address.substringAfter("://").substringBefore('/')
        assertTrue(online.isCatalogueHost(host))
        // Not under the key the full edition cached its opening seconds by, or those would play.
        assertNull(online.cacheKey(streamed.id))
        val refused = runCatching { online.resolveStream(streamed.id) }.exceptionOrNull()
        assertTrue(refused is java.io.IOException)
        assertEquals(R.string.main_not_in_this_edition, online.unplayableMessage(refused!!.javaClass.name, refused.message.orEmpty()))
        // Any other failure is said the usual way.
        assertNull(online.unplayableMessage("java.net.UnknownHostException", "radio.example"))
    }
}
