package com.wanderwildwood.jimeikin.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.jimeikin.R

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

/**
 * Whether a YouTube song is already on the phone as a download. A listing from YouTube names
 * the video even once its file is here, so the row alone cannot say. Provided once at the top,
 * like [LocalEditDetails], so every row and every page asks the same question the same way.
 */
val LocalIsDownloaded = compositionLocalOf<(SongUiModel) -> Boolean> { { false } }

/**
 * Whether a download-it-all button has anything behind it: a song still on a music server, or a
 * YouTube song not yet downloaded where YouTube downloads are on. Songs already on the phone
 * need nothing.
 */
fun List<SongUiModel>.hasSongsToKeep(
    canKeepYouTube: Boolean,
    isDownloaded: (SongUiModel) -> Boolean = { false },
): Boolean = any {
    it.sourceType == "SUBSONIC" || (canKeepYouTube && it.sourceType == "YOUTUBE" && !isDownloaded(it))
}

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
    onPlayNextClick: (SongUiModel) -> Unit,
    onAddToQueueClick: (SongUiModel) -> Unit,
    onRemoveFromLibraryClick: (SongUiModel) -> Unit,
    onDeleteClick: (SongUiModel) -> Unit,
    onKeepOnPhoneClick: (SongUiModel) -> Unit = {},
    onOpenStreamingSettingsClick: () -> Unit,
    onOpenLocalSettingsClick: () -> Unit,
    onOpenMusicServerClick: () -> Unit = {},
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
                    TextMMD(text = stringResource(R.string.library_songs_loading))
                }
            }

            // Only when there is nothing else to show. This error comes from the scan of
            // the folders on the phone and from nowhere else, but this list holds the
            // music server's songs too - so a card that failed to mount, or a folder
            // whose permission did not survive an update, used to replace a working
            // library of thousands with the words "could not be read". The failure is
            // worth saying when it leaves the reader with nothing; it is not worth
            // hiding everything they still have.
            errorMessage != null && songs.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    TextMMD(text = stringResource(R.string.library_songs_error))
                }
            }

            songs.isEmpty() && isSyncInProgress -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    TextMMD(text = stringResource(R.string.library_sync_in_progress))
                }
            }

            songs.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    LibraryOnboardingEmptyState(
                        title = stringResource(R.string.library_songs_empty_title),
                        body = stringResource(R.string.library_nothing_added_yet),
                        onOpenStreamingSettingsClick = onOpenStreamingSettingsClick,
                        onOpenLocalSettingsClick = onOpenLocalSettingsClick,
                        onOpenMusicServerClick = onOpenMusicServerClick,
                    )
                }
            }

            else -> {
                PagedColumnMMD(
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
                            onPlayNext = { onPlayNextClick(song) },
                            onAddToQueue = { onAddToQueueClick(song) },
                            onRemoveFromLibrary = { onRemoveFromLibraryClick(song) },
                            onDelete = { onDeleteClick(song) },
                            onKeepOnPhone = { onKeepOnPhoneClick(song) },
                            isDownloaded = false,
                            isInLibrary = true,
                            showDivider = !isLast,
                        )
                    }
                }
            }
        }

        TopBarActions {
            if (!isLoading && errorMessage == null && songs.isNotEmpty()) {
                IconButton(
                    onClick = onShuffleClick,
                ) {
                    Icon(
                        imageVector = Icons.Shuffle,
                        contentDescription = stringResource(R.string.library_songs_shuffle),
                    )
                }
            }
        }
    }
}