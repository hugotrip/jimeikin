package com.wanderwildwood.jimeikin.data

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
        assertEquals("T", firstLetterOf("The Beatles"))
        assertEquals("O", firstLetterOf("Ödland"))
        assertEquals("#", firstLetterOf("10,000 Maniacs"))
        assertEquals("#", firstLetterOf("たゆたう"))
        assertEquals("A", firstLetterOf("(a) Song"))
        assertEquals("#", firstLetterOf(""))
    }

    @Test
    fun guestsGatherUnderTheArtistTheyAllShare() {
        val credits = listOf("Asake & Ludmilla", "Asake & Olamide", "Asake & Stormzy", "Asake & Tiakola")
        val groupOf = ArtistNames.grouping(credits)
        credits.forEach { assertEquals(ArtistNames.artistKey("Asake"), groupOf(it)) }
        assertEquals("Asake", ArtistNames.groupName(credits, groupOf(credits.first())))
    }

    @Test
    fun aCollaborationGoesUnderAnArtistWhoIsAlsoThereAlone() {
        val groupOf = ArtistNames.grouping(listOf("Asake", "Asake & Olamide"))
        assertEquals(groupOf("Asake"), groupOf("Asake & Olamide"))
        assertEquals("Asake", ArtistNames.groupName(listOf("Asake", "Asake & Olamide"), groupOf("Asake")))
    }

    @Test
    fun bandsWithAnAmpersandStayWhole() {
        val groupOf = ArtistNames.grouping(listOf("Iron & Wine", "Simon & Garfunkel", "Paul Simon", "Asake"))
        assertEquals(ArtistNames.artistKey("Iron & Wine"), groupOf("Iron & Wine"))
        assertEquals(ArtistNames.artistKey("Simon & Garfunkel"), groupOf("Simon & Garfunkel"))
    }

    @Test
    fun theLongestArtistAtTheStartOfACreditWins() {
        val groupOf = ArtistNames.grouping(listOf("Iron & Wine", "Iron & Wine, Calexico", "Iron & Wine & Ben Bridwell"))
        assertEquals(ArtistNames.artistKey("Iron & Wine"), groupOf("Iron & Wine"))
        assertEquals(ArtistNames.artistKey("Iron & Wine"), groupOf("Iron & Wine, Calexico"))
        assertEquals(ArtistNames.artistKey("Iron & Wine"), groupOf("Iron & Wine & Ben Bridwell"))
    }

    @Test
    fun aCommaBeforeTheIsPartOfTheName() {
        assertEquals(listOf("Tyler, The Creator"), ArtistNames.collaborators("Tyler, The Creator"))
        assertEquals(listOf("Beatles, The"), ArtistNames.collaborators("Beatles, The"))
        val groupOf = ArtistNames.grouping(listOf("Tyler", "Tyler, The Creator"))
        assertNotEquals(groupOf("Tyler"), groupOf("Tyler, The Creator"))
    }

    @Test
    fun anXBetweenTwoNamesIsACollaboration() {
        assertEquals(listOf("Calvin Harris", "Dua Lipa"), ArtistNames.collaborators("Calvin Harris x Dua Lipa"))
        assertEquals(listOf("Calvin Harris", "Dua Lipa"), ArtistNames.collaborators("Calvin Harris X Dua Lipa"))
        assertEquals(listOf("Malcolm X"), ArtistNames.collaborators("Malcolm X"))

        val alone = ArtistNames.grouping(listOf("Calvin Harris x Dua Lipa"))
        assertEquals(ArtistNames.artistKey("Calvin Harris x Dua Lipa"), alone("Calvin Harris x Dua Lipa"))

        val withSolo = ArtistNames.grouping(listOf("Calvin Harris", "Calvin Harris x Dua Lipa"))
        assertEquals(ArtistNames.artistKey("Calvin Harris"), withSolo("Calvin Harris x Dua Lipa"))

        val twice = ArtistNames.grouping(listOf("Calvin Harris x Dua Lipa", "Calvin Harris x Rihanna"))
        assertEquals(ArtistNames.artistKey("Calvin Harris"), twice("Calvin Harris x Dua Lipa"))
        assertEquals(ArtistNames.artistKey("Calvin Harris"), twice("Calvin Harris x Rihanna"))
    }

    @Test
    fun guestsAfterFeatAreLeftOutOfTheCollaborators() {
        assertEquals(listOf("Asake", "Olamide"), ArtistNames.collaborators("Asake & Olamide feat. Someone"))
    }

    @Test
    fun anAlbumSplitByItsGuestsIsOneAlbumUnderTheMainArtist() {
        val artists = listOf("Mara Quill", "Mara Quill & Dov Ash", "Mara Quill & Lio Fenn", "Mara Quill, Sela Brook")
        val key = ArtistNames.albumGrouping(artists)
        val one = key("Lantern Road", "Mara Quill")
        assertEquals(one, key("Lantern Road", "Mara Quill & Dov Ash"))
        assertEquals(one, key("Lantern Road", "Mara Quill & Lio Fenn"))
        assertEquals(one, key("Lantern Road", "Mara Quill, Sela Brook"))
        assertEquals(one, key("Lantern Road", "Mara Quill feat. Dov Ash"))
        // Another record of the same name by someone else stays apart.
        assertNotEquals(one, key("Lantern Road", "Dov Ash"))
        // A band whose name has an ampersand is not split up.
        val band = ArtistNames.albumGrouping(listOf("Iron & Wine"))
        assertNotEquals(band("Shepherd", "Iron & Wine"), band("Shepherd", "Iron"))
    }
}
