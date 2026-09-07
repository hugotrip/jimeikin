package com.wanderwildwood.jimeikin.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.FloatingActionButtonMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.text.TextMMD

data class SongUiModel(
    val id: String,
    val title: String,
    val artist: String,
    val durationText: String? = null,
    val durationMillis: Long? = null,
    val discNumber: Int? = null,
    val trackNumber: Int? = null,
    val sourceType: String = "YOUTUBE",
    val audioUri: String? = null,
    val album: String? = null,
)

@Composable
fun SongsScreen(
    songs: List<SongUiModel>,
    isLoading: Boolean,
    errorMessage: String?,
    currentSongId: String?,
    isSyncInProgress: Boolean,
    onPlaySongClick: (SongUiModel) -> Unit,
    onShuffleClick: () -> Unit,
    onAddToPlaylistClick: (SongUiModel) -> Unit,
    onRemoveFromLibraryClick: (SongUiModel) -> Unit,
    onDeleteClick: (SongUiModel) -> Unit,
    onOpenStreamingSettingsClick: () -> Unit,
    onOpenLocalSettingsClick: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
        when {
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    TextMMD(text = "Loading songs...")
                }
            }

            errorMessage != null -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    TextMMD(text = "The songs could not be read")
                }
            }

            songs.isEmpty() && isSyncInProgress -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    TextMMD(text = "Music sync is in progress…")
                }
            }

            songs.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    LibraryOnboardingEmptyState(
                        title = "No songs yet",
                        body = "Search YouTube Music or choose local folders in Settings to start building your library.",
                        onOpenStreamingSettingsClick = onOpenStreamingSettingsClick,
                        onOpenLocalSettingsClick = onOpenLocalSettingsClick,
                    )
                }
            }

            else -> {
                LazyColumnMMD(
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = 16.dp,
                        end = 16.dp,
                        bottom = 88.dp,
                    ),
                ) {
                    items(
                        items = songs,
                        key = { it.id },
                    ) { song ->
                        val isLast = song.id == songs.lastOrNull()?.id
                        SongItem(
                            song = song,
                            isCurrentlyPlaying = song.id == currentSongId,
                            onClick = { onPlaySongClick(song) },
                            onAddToPlaylist = { onAddToPlaylistClick(song) },
                            onRemoveFromLibrary = { onRemoveFromLibraryClick(song) },
                            onDelete = { onDeleteClick(song) },
                            isDownloaded = false,
                            isInLibrary = true,
                            showDivider = !isLast,
                        )
                    }
                }
            }
        }

        if (!isLoading && errorMessage == null && songs.isNotEmpty()) {
            FloatingActionButtonMMD(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                onClick = onShuffleClick,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Shuffle,
                    contentDescription = "Shuffle songs",
                )
            }
        }
    }
}