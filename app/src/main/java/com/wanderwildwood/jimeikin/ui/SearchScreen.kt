package com.wanderwildwood.jimeikin.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.jimeikin.R

/** Lightweight UI model for a YouTube Music artist search result. */
data class YoutubeArtistUiModel(
    val browseId: String,
    val name: String,
)

/**
 * One list: songs, albums and artists together, the phone's first, then the server's, then
 * YouTube's - see [buildSearchResults]. The library's part is there as soon as the search is
 * made, and with no signal; YouTube's joins it when it comes.
 */
@Composable
fun SearchScreen(
    isSearching: Boolean,
    errorMessage: String?,
    results: List<SearchResult>,
    onPlaySongClick: (SongUiModel) -> Unit,
    onAlbumClick: (AlbumUiModel) -> Unit,
    onLibraryArtistClick: (ArtistUiModel) -> Unit,
    onArtistClick: (YoutubeArtistUiModel) -> Unit,
    librarySongIds: Set<String> = emptySet(),
    onAddToPlaylistClick: (SongUiModel) -> Unit = {},
    onPlayNextClick: (SongUiModel) -> Unit = {},
    onAddToQueueClick: (SongUiModel) -> Unit = {},
    onRemoveFromLibraryClick: (SongUiModel) -> Unit = {},
    onDeleteClick: (SongUiModel) -> Unit = {},
    onKeepOnPhoneClick: (SongUiModel) -> Unit = {},
) {
    val albumKind = stringResource(R.string.library_search_kind_album)
    Column(modifier = Modifier.fillMaxSize()) {
        PagedColumnMMD(contentPadding = PaddingValues(16.dp)) {
            if (isSearching) {
                item {
                    TextMMD(text = stringResource(R.string.library_search_searching))
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            if (errorMessage != null) {
                item {
                    TextMMD(text = stringResource(R.string.library_search_failed))
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            items(results.size) { index ->
                val showDivider = index < results.lastIndex
                when (val result = results[index]) {
                    is SearchResult.Song -> {
                        val song = result.song
                        SongItem(
                            song = song,
                            isCurrentlyPlaying = false,
                            onClick = { onPlaySongClick(song) },
                            onAddToPlaylist = { onAddToPlaylistClick(song) },
                            onPlayNext = { onPlayNextClick(song) },
                            onAddToQueue = { onAddToQueueClick(song) },
                            onRemoveFromLibrary = { onRemoveFromLibraryClick(song) },
                            onDelete = { onDeleteClick(song) },
                            onKeepOnPhone = { onKeepOnPhoneClick(song) },
                            showDivider = showDivider,
                            isInLibrary = librarySongIds.contains(song.id),
                        )
                    }
                    is SearchResult.Album -> AlbumItem(
                        album = result.album,
                        onClick = { onAlbumClick(result.album) },
                        showDivider = showDivider,
                        kindLabel = albumKind,
                    )
                    is SearchResult.LibraryArtist -> ArtistItem(
                        artist = result.artist,
                        onClick = { onLibraryArtistClick(result.artist) },
                        showDivider = showDivider,
                    )
                    is SearchResult.YouTubeArtist -> SearchArtistItem(
                        artist = result.artist,
                        onClick = { onArtistClick(result.artist) },
                        showDivider = showDivider,
                    )
                }
            }

            if (!isSearching && errorMessage == null && results.isEmpty()) {
                item {
                    TextMMD(text = stringResource(R.string.library_search_nothing))
                }
            }
        }
    }
}

@Composable
private fun SearchArtistItem(
    artist: YoutubeArtistUiModel,
    onClick: () -> Unit,
    showDivider: Boolean = true,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(bottom = 8.dp),
    ) {
        TextMMD(
            text = artist.name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(modifier = Modifier.height(4.dp))
        SubtitleLine(text = stringResource(R.string.library_search_result_artist), origin = Origin.YOUTUBE)

        Spacer(modifier = Modifier.height(12.dp))

        if (showDivider) {
            HorizontalDividerMMD(thickness = 1.dp)
        }
    }
}
