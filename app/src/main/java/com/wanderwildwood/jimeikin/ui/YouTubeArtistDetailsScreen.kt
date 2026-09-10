package com.wanderwildwood.jimeikin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wanderwildwood.jimeikin.CalmMusicViewModel
import com.mudita.mmd.components.buttons.FloatingActionButtonMMD
import com.mudita.mmd.components.tabs.PrimaryTabRowMMD
import com.mudita.mmd.components.tabs.TabMMD
import com.mudita.mmd.components.text.TextMMD

/**
 * Stripped-down, eInk-friendly view of a YouTube Music artist page: just the
 * artist's top songs, albums, and singles/new releases -- no artwork
 * carousel, no video, no "fans might also like".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeArtistDetailsScreen(
    browseId: String?,
    viewModel: CalmMusicViewModel,
    onPlaySongClick: (SongUiModel, List<SongUiModel>) -> Unit,
    onAlbumClick: (AlbumUiModel) -> Unit,
    onShuffleSongsClick: (List<SongUiModel>) -> Unit,
    onAddToPlaylistClick: (SongUiModel) -> Unit = {},
    onPlayNextClick: (SongUiModel) -> Unit = {},
    onAddToQueueClick: (SongUiModel) -> Unit = {},
    onRemoveFromLibraryClick: (SongUiModel) -> Unit = {},
    onDeleteClick: (SongUiModel) -> Unit = {},
    onKeepOnPhoneClick: (SongUiModel) -> Unit = {},
) {
    var songs by remember { mutableStateOf<List<SongUiModel>>(emptyList()) }
    var albums by remember { mutableStateOf<List<AlbumUiModel>>(emptyList()) }
    var singles by remember { mutableStateOf<List<AlbumUiModel>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val tabOptions = listOf("Songs", "Albums", "Singles")

    val playbackState by viewModel.playbackState.collectAsState()
    val currentSongId = playbackState.currentSongId

    LaunchedEffect(browseId) {
        if (browseId.isNullOrBlank()) {
            isLoading = false
            errorMessage = "Unknown artist"
            return@LaunchedEffect
        }
        isLoading = true
        errorMessage = null
        try {
            val page = viewModel.getYoutubeArtistPage(browseId)
            songs = page.songs
            albums = page.albums
            singles = page.singles
        } catch (e: Exception) {
            errorMessage = e.message ?: "Failed to load artist"
        } finally {
            isLoading = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    TextMMD(text = "Loading artist...")
                }
            }

            errorMessage != null -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        TextMMD(text = "This artist could not be read")
                        TextMMD(text = errorMessage!!)
                    }
                }
            }

            songs.isEmpty() && albums.isEmpty() && singles.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    TextMMD(text = "No content for this artist yet")
                }
            }

            else -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    PrimaryTabRowMMD(selectedTabIndex = selectedTab) {
                        tabOptions.forEachIndexed { index, title ->
                            TabMMD(
                                selected = selectedTab == index,
                                onClick = { selectedTab = index },
                                text = {
                                    TextMMD(
                                        text = title,
                                        fontSize = 16.sp,
                                        fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                                    )
                                },
                            )
                        }
                    }

                    when (selectedTab) {
                        0 -> {
                            PagedColumnMMD(
                                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 88.dp),
                                verticalArrangement = Arrangement.Top,
                            ) {
                                if (songs.isNotEmpty()) {
                                    items(songs) { song ->
                                        SongItem(
                                            song = song,
                                            isCurrentlyPlaying = song.id == currentSongId,
                                            onClick = { onPlaySongClick(song, songs) },
                                            onAddToPlaylist = { onAddToPlaylistClick(song) },
                                            onPlayNext = { onPlayNextClick(song) },
                                            onAddToQueue = { onAddToQueueClick(song) },
                                            onRemoveFromLibrary = { onRemoveFromLibraryClick(song) },
                                            onDelete = { onDeleteClick(song) },
                                        onKeepOnPhone = { onKeepOnPhoneClick(song) },
                                            showDivider = song != songs.lastOrNull(),
                                        )
                                    }
                                } else {
                                    item {
                                        Box(
                                            modifier = Modifier.fillMaxSize().height(200.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            TextMMD(text = "No songs for this artist")
                                        }
                                    }
                                }
                            }
                        }

                        1 -> {
                            PagedColumnMMD(
                                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 88.dp),
                                verticalArrangement = Arrangement.Top,
                            ) {
                                if (albums.isNotEmpty()) {
                                    items(albums) { album ->
                                        AlbumItem(
                                            album = album,
                                            onClick = { onAlbumClick(album) },
                                            showDivider = album != albums.lastOrNull(),
                                        )
                                    }
                                } else {
                                    item {
                                        Box(
                                            modifier = Modifier.fillMaxSize().height(200.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            TextMMD(text = "No albums for this artist")
                                        }
                                    }
                                }
                            }
                        }

                        else -> {
                            PagedColumnMMD(
                                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 88.dp),
                                verticalArrangement = Arrangement.Top,
                            ) {
                                if (singles.isNotEmpty()) {
                                    items(singles) { single ->
                                        AlbumItem(
                                            album = single,
                                            onClick = { onAlbumClick(single) },
                                            showDivider = single != singles.lastOrNull(),
                                        )
                                    }
                                } else {
                                    item {
                                        Box(
                                            modifier = Modifier.fillMaxSize().height(200.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            TextMMD(text = "No singles or new releases")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (!isLoading && errorMessage == null && songs.isNotEmpty()) {
            FloatingActionButtonMMD(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                onClick = { onShuffleSongsClick(songs) },
            ) {
                Icon(
                    imageVector = Icons.Outlined.Shuffle,
                    contentDescription = "Shuffle artist songs",
                )
            }
        }
    }
}
