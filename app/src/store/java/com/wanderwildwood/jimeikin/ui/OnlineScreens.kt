package com.wanderwildwood.jimeikin.ui

import androidx.compose.runtime.Composable
import com.wanderwildwood.jimeikin.CalmMusicViewModel

// The store edition has no online catalogue: nothing leads to these pages, which are here only
// so the shared navigation compiles the same in both editions.

@Suppress("UNUSED_PARAMETER")
@Composable
fun OnlineArtistScreen(
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
) = Unit

@Suppress("UNUSED_PARAMETER")
@Composable
fun OnlineSignInScreen(
    onDone: () -> Unit,
    onBackClick: () -> Unit,
) = Unit
