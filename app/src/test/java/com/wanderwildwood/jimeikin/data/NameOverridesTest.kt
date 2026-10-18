package com.wanderwildwood.jimeikin.data

import org.junit.Assert.assertEquals
import org.junit.Test

class NameOverridesTest {

    private fun names(vararg rows: NameOverrideEntity, recorded: Map<String, String> = emptyMap()) =
        Names(rows.associateBy { it.kind to it.targetId }) { recorded }

    private fun song(id: String, title: String, artist: String, artistId: String?, album: String? = null, albumId: String? = null) =
        SongEntity(id, title, artist, album, albumId, null, null, null, "YOUTUBE", id, artistId)

    @Test
    fun aSongTitleFollowsTheSongFromStreamToDownload() {
        val n = names(
            NameOverrideEntity(NameOverrides.SONG, "vid1", "Harbour Lights", null, "Harbour Lights (2024 Remastered)"),
            recorded = mapOf("vid1" to "file1"),
        )
        assertEquals("Harbour Lights", n.songTitle("vid1"))
        assertEquals("Harbour Lights", n.songTitle("file1"))
    }

    @Test
    fun renamingAnArtistRenamesTheirOwnCreditsButKeepsGuests() {
        val n = names(NameOverrideEntity(NameOverrides.ARTIST, "a1", "Mara Q.", null, "Mara Quill"))
        assertEquals("Mara Q.", n.song(song("s1", "One", "Mara Quill", "a1")).artist)
        assertEquals("Mara Quill & Dov Ash", n.song(song("s2", "Two", "Mara Quill & Dov Ash", "a1")).artist)
        assertEquals("Mara Q.", n.artist(ArtistEntity("a1", "Mara Quill", "YOUTUBE")).name)
    }

    @Test
    fun aFiledCreditKeepsItsSongsButFilesItsAlbum() {
        val n = names(NameOverrideEntity(NameOverrides.ARTIST_UNDER, "a2", "Mara Quill", null, "Mara Quill & Dov Ash"))
        assertEquals("Mara Quill", n.artist(ArtistEntity("a2", "Mara Quill & Dov Ash", "YOUTUBE")).name)
        assertEquals("Mara Quill & Dov Ash", n.song(song("s2", "Two", "Mara Quill & Dov Ash", "a2")).artist)
        val album = AlbumEntity("al2", "Lantern Road", "Mara Quill & Dov Ash", "YOUTUBE_DOWNLOAD", "a2")
        assertEquals("Mara Quill", n.album(album).artist)
    }

    @Test
    fun anAlbumRenameReachesItsSongs() {
        val n = names(NameOverrideEntity(NameOverrides.ALBUM, "al1", "Lantern Road", null, null))
        val s = song("s1", "One", "Mara Quill", "a1", album = "Lantern Road (Deluxe)", albumId = "al1")
        assertEquals("Lantern Road", n.song(s).album)
        assertEquals("Lantern Road", n.album(AlbumEntity("al1", "Lantern Road (Deluxe)", "Mara Quill", "LOCAL_FILE", "a1")).name)
    }
}
