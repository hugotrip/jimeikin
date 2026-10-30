package com.wanderwildwood.jimeikin.data

import com.wanderwildwood.jimeikin.ui.AlbumUiModel
import com.wanderwildwood.jimeikin.ui.ArtistUiModel
import com.wanderwildwood.jimeikin.ui.OnlineArtistUiModel
import com.wanderwildwood.jimeikin.ui.SearchResult
import com.wanderwildwood.jimeikin.ui.SongUiModel
import com.wanderwildwood.jimeikin.ui.buildSearchResults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistAlbumsFillTest {
    private fun album(id: String, title: String, year: Int?, source: String = "YOUTUBE") =
        AlbumUiModel(id = id, title = title, artist = "Test Band", sourceType = source, releaseYear = year)

    @Test
    fun onlyAlbumsNotAlreadyHeldAreAdded_newestFirst_noRepeats() {
        val local = listOf(album("l1", "First Record", 2001, "LOCAL_FILE"))
        val rest = ArtistAlbumsFill.rest(
            local,
            albums = listOf(album("a", "first record", 2001), album("b", "Second Record", 2005), album("c", "Third Record", 2010)),
            singles = listOf(album("d", "Third Record", 2010), album("e", "A Single", null)),
        )
        assertEquals(listOf("Third Record", "Second Record", "A Single"), rest.map { it.title })
    }

    @Test
    fun nothingOnlineLeavesNothing() {
        assertTrue(ArtistAlbumsFill.rest(emptyList(), emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun exactArtistNameLeadsEverythingElse() {
        val song = SongUiModel(
            id = "s1", title = "Test Band Anthem", artist = "Someone Else", durationText = "1:00",
            durationMillis = 60_000, trackNumber = 1, discNumber = 1, sourceType = "LOCAL_FILE",
            audioUri = "file:///x.mp3", album = "Misc",
        )
        val results = buildSearchResults(
            query = "test band",
            librarySongs = listOf(song),
            libraryAlbums = emptyList(),
            libraryArtists = emptyList(),
            onlineSongs = emptyList(),
            onlineAlbums = emptyList(),
            onlineArtists = listOf(OnlineArtistUiModel("UC1", "Test Band")),
        )
        assertTrue(results.first() is SearchResult.OnlineArtist)
        val withLibrary = buildSearchResults(
            "test band", listOf(song), emptyList(),
            listOf(ArtistUiModel("ar1", "Test Band", 2, 1)),
            emptyList(), emptyList(), listOf(OnlineArtistUiModel("UC1", "Test Band")),
        )
        assertTrue(withLibrary.first() is SearchResult.LibraryArtist)
    }
}
