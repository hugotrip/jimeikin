package com.wanderwildwood.jimeikin.data

import com.wanderwildwood.jimeikin.ui.jumpLetterOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ArtistNamesTest {

    @Test
    fun guestsAreCutFromTheCredit() {
        assertEquals("Gorillaz", ArtistNames.lead("Gorillaz feat. De La Soul"))
        assertEquals("Gorillaz", ArtistNames.lead("Gorillaz (feat. De La Soul)"))
        assertEquals("Gorillaz", ArtistNames.lead("Gorillaz ft. Mos Def"))
        assertEquals("Gorillaz", ArtistNames.lead("Gorillaz FEATURING Mos Def"))
        assertEquals("Gorillaz", ArtistNames.lead("Gorillaz [ft Mos Def]"))
    }

    @Test
    fun namesThatOnlyLookLikeACreditAreLeftWhole() {
        assertEquals("Daft Punk", ArtistNames.lead("Daft Punk"))
        assertEquals("Simon & Garfunkel", ArtistNames.lead("Simon & Garfunkel"))
        assertEquals("Crosby, Stills and Nash", ArtistNames.lead("Crosby, Stills and Nash"))
        assertEquals("Featherweight", ArtistNames.lead("Featherweight"))
    }

    @Test
    fun oneArtistHoweverCredited() {
        val key = ArtistNames.artistKey("The Beatles")
        assertEquals(key, ArtistNames.artistKey("Beatles"))
        assertEquals(key, ArtistNames.artistKey("Beatles, The"))
        assertEquals(key, ArtistNames.artistKey("the beatles"))
        assertEquals(key, ArtistNames.artistKey("  THE BEATLES "))
        assertEquals(ArtistNames.artistKey("Björk"), ArtistNames.artistKey("Bjork feat. Thom Yorke"))
        assertEquals("the", ArtistNames.artistKey("The The"))
        assertNotEquals(ArtistNames.artistKey("Theatre"), ArtistNames.artistKey("atre"))
    }

    @Test
    fun theSpellingShownIsTheCarefulOne() {
        assertEquals("Nick Drake", ArtistNames.preferred(listOf("NICK DRAKE", "nick drake", "Nick Drake")))
        assertEquals("Björk", ArtistNames.preferred(listOf("Bjork", "Björk")))
        assertEquals("Bjork", ArtistNames.preferred(listOf("Bjork", "Bjork", "Björk")))
    }

    @Test
    fun anAlbumIsItsTitleAndItsArtist() {
        assertEquals(
            ArtistNames.albumKey("Demon Days", "Gorillaz"),
            ArtistNames.albumKey("demon days", "Gorillaz feat. Shaun Ryder"),
        )
        assertNotEquals(
            ArtistNames.albumKey("Greatest Hits", "Queen"),
            ArtistNames.albumKey("Greatest Hits", "ABBA"),
        )
    }

    @Test
    fun lettersFollowTheSortedName() {
        assertEquals("T", jumpLetterOf("The Beatles"))
        assertEquals("O", jumpLetterOf("Ödland"))
        assertEquals("#", jumpLetterOf("10,000 Maniacs"))
        assertEquals("#", jumpLetterOf("たゆたう"))
        assertEquals("A", jumpLetterOf("(a) Song"))
        assertEquals("#", jumpLetterOf(""))
    }
}
