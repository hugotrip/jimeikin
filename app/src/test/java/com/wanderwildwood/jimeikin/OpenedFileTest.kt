package com.wanderwildwood.jimeikin

import com.wanderwildwood.jimeikin.glance.NowPlaying
import com.wanderwildwood.jimeikin.playback.OpenedFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenedFileTest {

    @Test
    fun titleTagWins() {
        assertEquals("Lantern Tune", OpenedFile.title("Lantern Tune", "track01.mp3"))
    }

    @Test
    fun fileNameWithoutExtensionWhenNoTag() {
        assertEquals("03 River Song", OpenedFile.title(null, "03 River Song.mp3"))
        assertEquals("03 River Song", OpenedFile.title("  ", "/storage/emulated/0/Music/03 River Song.mp3"))
    }

    @Test
    fun onlyTheLastDotIsTheExtension() {
        assertEquals("live.at.the.hall", OpenedFile.title(null, "live.at.the.hall.flac"))
    }

    @Test
    fun aNameThatIsAllExtensionIsKept() {
        assertEquals(".ogg", OpenedFile.title(null, ".ogg"))
        assertEquals("", OpenedFile.title(null, null))
    }

    @Test
    fun glanceLineIsSongThenArtist() {
        assertEquals("Lantern Tune — Test Choir", NowPlaying.text("Lantern Tune", "Test Choir"))
    }

    @Test
    fun glanceLineWithoutArtistIsTheSongAlone() {
        assertEquals("Lantern Tune", NowPlaying.text(" Lantern Tune ", null))
        assertEquals("Lantern Tune", NowPlaying.text("Lantern Tune", " "))
    }

    @Test
    fun noTitleNoLine() {
        assertNull(NowPlaying.text(null, "Test Choir"))
        assertNull(NowPlaying.text("", "Test Choir"))
    }
}
