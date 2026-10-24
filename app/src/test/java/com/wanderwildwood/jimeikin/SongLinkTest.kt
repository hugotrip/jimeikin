package com.wanderwildwood.jimeikin

import com.wanderwildwood.jimeikin.playback.SongLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SongLinkTest {

    @Test
    fun aLinkNamesALibraryId() {
        assertEquals("SUBSONIC:abc123", SongLink.idOf("jimeikin://song?id=SUBSONIC%3Aabc123"))
        assertEquals("SUBSONIC:abc123", SongLink.idOf("jimeikin://song?id=SUBSONIC:abc123&title=ignored"))
    }

    @Test
    fun nothingButTheIdIsRead() {
        // An address or a title in the link is never looked at, only the id.
        assertEquals("a", SongLink.idOf("jimeikin://song?url=http%3A%2F%2Fexample.test%2Fx&id=a"))
    }

    @Test
    fun otherLinksAndMissingIdsNameNothing() {
        assertNull(SongLink.idOf("jimeikin://song"))
        assertNull(SongLink.idOf("jimeikin://song?id="))
        assertNull(SongLink.idOf("jimeikin://other?id=a"))
        assertNull(SongLink.idOf("https://song/?id=a"))
        assertNull(SongLink.idOf("not a link"))
    }

    @Test
    fun aFileOpenedOnceAndAnOverlongIdAreRefused() {
        assertNull(SongLink.idOf("jimeikin://song?id=OPENED%3Acontent%3A%2F%2Fx"))
        assertNull(SongLink.idOf("jimeikin://song?id=" + "a".repeat(400)))
    }
}
