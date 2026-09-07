package com.wanderwildwood.jimeikin.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.tabs.PrimaryTabRowMMD
import com.mudita.mmd.components.tabs.TabMMD
import com.mudita.mmd.components.text.TextMMD

/** Lightweight UI model for a YouTube Music artist search result. */
data class YoutubeArtistUiModel(
    val browseId: String,
    val name: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    isSearching: Boolean,
    errorMessage: String?,
    songs: List<SongUiModel>,
    albums: List<AlbumUiModel>,
    artists: List<YoutubeArtistUiModel>,
    localSongs: List<SongUiModel>,
    selectedTab: Int,
    onSelectedTabChange: (Int) -> Unit,
    onPlaySongClick: (SongUiModel) -> Unit,
    onAlbumClick: (AlbumUiModel) -> Unit,
    onArtistClick: (YoutubeArtistUiModel) -> Unit,
    librarySongIds: Set<String> = emptySet(),
    onAddToPlaylistClick: (SongUiModel) -> Unit = {},
    onRemoveFromLibraryClick: (SongUiModel) -> Unit = {},
    onDeleteClick: (SongUiModel) -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxSize()) {
        PrimaryTabRowMMD(selectedTabIndex = selectedTab) {
            TabMMD(
                selected = selectedTab == 0,
                onClick = { onSelectedTabChange(0) },
                text = {
                    TextMMD(
                        text = "Songs",
                        fontSize = 16.sp,
                        fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal,
                    )
                },
            )
            TabMMD(
                selected = selectedTab == 1,
                onClick = { onSelectedTabChange(1) },
                text = {
                    TextMMD(
                        text = "Albums",
                        fontSize = 16.sp,
                        fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal,
                    )
                },
            )
            TabMMD(
                selected = selectedTab == 2,
                onClick = { onSelectedTabChange(2) },
                text = {
                    TextMMD(
                        text = "Artists",
                        fontSize = 16.sp,
                        fontWeight = if (selectedTab == 2) FontWeight.Bold else FontWeight.Normal,
                    )
                },
            )
            TabMMD(
                selected = selectedTab == 3,
                onClick = { onSelectedTabChange(3) },
                text = {
                    TextMMD(
                        text = "Local",
                        fontSize = 16.sp,
                        fontWeight = if (selectedTab == 3) FontWeight.Bold else FontWeight.Normal,
                    )
                },
            )
        }

        LazyColumnMMD(contentPadding = PaddingValues(16.dp)) {
            if (isSearching) {
                item {
                    TextMMD(text = "Searching...")
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            if (errorMessage != null) {
                item {
                    TextMMD(text = "The search did not reach YouTube")
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            when (selectedTab) {
                0 -> {
                    if (songs.isNotEmpty()) {
                        items(songs.size) { index ->
                            val song = songs[index]
                            SongItem(
                                song = song,
                                isCurrentlyPlaying = false,
                                onClick = { onPlaySongClick(song) },
                                onAddToPlaylist = { onAddToPlaylistClick(song) },
                                onRemoveFromLibrary = { onRemoveFromLibraryClick(song) },
                                onDelete = { onDeleteClick(song) },
                                showDivider = song != songs.lastOrNull(),
                                isInLibrary = librarySongIds.contains(song.id),
                            )
                        }
                    }

                    if (
                        !isSearching &&
                        errorMessage == null &&
                        songs.isEmpty()
                    ) {
                        item {
                            TextMMD(text = "No songs. Try a different search.")
                        }
                    }
                }

                1 -> {
                    if (albums.isNotEmpty()) {
                        items(albums.size) { index ->
                            val album = albums[index]
                            AlbumItem(
                                album = album,
                                onClick = { onAlbumClick(album) },
                                showDivider = album != albums.lastOrNull(),
                            )
                        }
                    }

                    if (
                        !isSearching &&
                        errorMessage == null &&
                        albums.isEmpty()
                    ) {
                        item {
                            TextMMD(text = "No albums. Try a different search.")
                        }
                    }
                }

                2 -> {
                    if (artists.isNotEmpty()) {
                        items(artists.size) { index ->
                            val artist = artists[index]
                            SearchArtistItem(
                                artist = artist,
                                onClick = { onArtistClick(artist) },
                                showDivider = artist != artists.lastOrNull(),
                            )
                        }
                    }

                    if (
                        !isSearching &&
                        errorMessage == null &&
                        artists.isEmpty()
                    ) {
                        item {
                            TextMMD(text = "No artists. Try a different search.")
                        }
                    }
                }

                3 -> {
                    if (localSongs.isNotEmpty()) {
                        items(localSongs.size) { index ->
                            val song = localSongs[index]
                            SongItem(
                                song = song,
                                isCurrentlyPlaying = false,
                                onClick = { onPlaySongClick(song) },
                                onAddToPlaylist = { onAddToPlaylistClick(song) },
                                onRemoveFromLibrary = { onRemoveFromLibraryClick(song) },
                                onDelete = { onDeleteClick(song) },
                                showDivider = song != localSongs.lastOrNull(),
                                isInLibrary = true,
                            )
                        }
                    }

                    if (!isSearching && localSongs.isEmpty()) {
                        item {
                            TextMMD(text = "No local songs found.")
                        }
                    }
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
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(modifier = Modifier.height(4.dp))
        TextMMD(
            text = "Artist",
            fontSize = 16.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (showDivider) {
            DashedDivider(thickness = 1.dp)
        }
    }
}
