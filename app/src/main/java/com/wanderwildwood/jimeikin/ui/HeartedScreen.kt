package com.wanderwildwood.jimeikin.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.jimeikin.R

/**
 * The songs the reader has hearted, newest first, played like a playlist: a song plays on down
 * the list, the top bar shuffles them all, and downloads what is still to download. Taking a
 * heart away takes the song off the list; nothing else is removed.
 */
@Composable
fun HeartedScreen(
    songs: List<SongUiModel>,
    currentSongId: String?,
    librarySongIds: Set<String>,
    onPlaySongClick: (SongUiModel, List<SongUiModel>) -> Unit,
    onShuffleClick: (List<SongUiModel>) -> Unit,
    onAddToPlaylistClick: (SongUiModel) -> Unit,
    onPlayNextClick: (SongUiModel) -> Unit,
    onAddToQueueClick: (SongUiModel) -> Unit,
    onRemoveFromLibraryClick: (SongUiModel) -> Unit,
    onDeleteClick: (SongUiModel) -> Unit,
    onKeepOnPhoneClick: (SongUiModel) -> Unit,
    onKeepAllClick: (List<SongUiModel>) -> Unit,
    canKeepOnline: Boolean,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (songs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                TextMMD(text = stringResource(R.string.hearted_empty))
            }
        } else {
            PagedColumnMMD(
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 0.dp),
            ) {
                items(items = songs, key = { it.id }) { song ->
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
                        isInLibrary = song.id in librarySongIds,
                        showDivider = song.id != songs.lastOrNull()?.id,
                    )
                }
            }
        }

        TopBarActions {
            if (songs.isNotEmpty()) {
                IconButton(onClick = { onShuffleClick(songs) }) {
                    Icon(
                        imageVector = Icons.Shuffle,
                        contentDescription = stringResource(R.string.hearted_shuffle),
                    )
                }
                if (songs.hasSongsToKeep(canKeepOnline, LocalIsDownloaded.current)) {
                    IconButton(onClick = { onKeepAllClick(songs) }) {
                        Icon(
                            imageVector = Icons.Download,
                            contentDescription = stringResource(R.string.player_playlist_keep_on_phone),
                        )
                    }
                }
            }
        }
    }
}
