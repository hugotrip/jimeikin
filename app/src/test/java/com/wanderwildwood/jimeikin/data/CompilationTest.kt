package com.wanderwildwood.jimeikin.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompilationTest {

    @Test
    fun anAlbumArtistTagWins() {
        assertEquals("Mara Quill", albumArtistOrCompilation("Mara Quill", "1"))
    }

    @Test
    fun aCompilationWithNoAlbumArtistIsVariousArtists() {
        assertEquals(COMPILATION_ARTIST, albumArtistOrCompilation(null, "1"))
        assertEquals(COMPILATION_ARTIST, albumArtistOrCompilation("  ", "true"))
        assertEquals(COMPILATION_ARTIST, albumArtistOrCompilation(null, " Yes "))
    }

    @Test
    fun noFlagOrAFalseFlagLeavesItAlone() {
        assertNull(albumArtistOrCompilation(null, null))
        assertNull(albumArtistOrCompilation(null, "0"))
        assertNull(albumArtistOrCompilation(null, "false"))
        assertNull(albumArtistOrCompilation(null, ""))
    }
}
