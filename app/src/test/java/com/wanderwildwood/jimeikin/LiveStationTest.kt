package com.wanderwildwood.jimeikin

import androidx.media3.common.MediaItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveStationTest {
    private fun item(id: String) = MediaItem.Builder().setMediaId(id).build()

    @Test
    fun aStationIsLiveSoItGoesStraightToTheNetwork() {
        assertTrue(isLiveSet(listOf(item("RADIO:https://example.org/stream.m3u8"))))
    }

    @Test
    fun songsAreNotLive() {
        assertFalse(isLiveSet(listOf(item("song-1"), item("YOUTUBE:abc"))))
        assertFalse(isLiveSet(emptyList()))
    }
}
