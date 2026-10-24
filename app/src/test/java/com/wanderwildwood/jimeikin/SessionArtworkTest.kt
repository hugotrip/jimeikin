package com.wanderwildwood.jimeikin

import androidx.media3.common.MediaMetadata
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionArtworkTest {
    private val icon = byteArrayOf(1, 2, 3)
    private val cover = byteArrayOf(9, 9)

    private fun song(withCover: Boolean) = MediaMetadata.Builder()
        .setTitle("Lantern Tune")
        .apply { if (withCover) setArtworkData(cover, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
        .build()

    @Test
    fun aStationCarriesTheAppIcon() {
        val out = song(false).forSession(radio = true, icon = icon)
        assertArrayEquals(icon, out.artworkData)
        assertTrue(out.title == "Lantern Tune")
    }

    @Test
    fun aSongKeepsItsCoverInsideTheApp() {
        assertNull(song(true).forSession(radio = false, icon = icon).artworkData)
    }

    @Test
    fun noIconMeansNoPictureEvenForAStation() {
        assertNull(song(true).forSession(radio = true, icon = null).artworkData)
    }

    @Test
    fun onlyRadioIdsAreStations() {
        assertTrue(isRadio("RADIO:abc"))
        assertFalse(isRadio("ADDRESS:http://x"))
        assertFalse(isRadio(null))
    }
}
