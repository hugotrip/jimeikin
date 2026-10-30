package com.wanderwildwood.jimeikin.data

import com.wanderwildwood.jimeikin.ui.AlbumUiModel

/**
 * What an artist's page adds from YouTube when "Fill in album gaps" is on: the artist's albums
 * and singles that are not already on the page. A record held locally - on the card, on a
 * server or downloaded - is the page's own row and is not listed twice. Newest first, then by
 * title, like the rest of the page.
 */
object ArtistAlbumsFill {
    fun rest(
        local: List<AlbumUiModel>,
        albums: List<AlbumUiModel>,
        singles: List<AlbumUiModel>,
    ): List<AlbumUiModel> {
        val owned = local.map { ArtistNames.key(it.title) }.toHashSet()
        val seen = HashSet<String>()
        return (albums + singles)
            .filter { it.title.isNotBlank() }
            .filter { ArtistNames.key(it.title) !in owned }
            .filter { seen.add(ArtistNames.key(it.title)) }
            .sortedWith(compareByDescending<AlbumUiModel> { it.releaseYear ?: Int.MIN_VALUE }.thenBy { it.title })
    }
}
