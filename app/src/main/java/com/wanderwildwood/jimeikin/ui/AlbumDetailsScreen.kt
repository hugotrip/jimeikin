package com.wanderwildwood.jimeikin.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wanderwildwood.jimeikin.CalmMusicViewModel
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.tabs.PrimaryTabRowMMD
import com.mudita.mmd.components.tabs.TabMMD
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.jimeikin.R
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailsScreen(
    album: AlbumUiModel?,
    viewModel: CalmMusicViewModel,
    onPlaySongClick: (SongUiModel, List<SongUiModel>) -> Unit,
    onShuffleClick: (List<SongUiModel>) -> Unit,
    librarySongIds: Set<String> = emptySet(),
    onAddToPlaylistClick: (SongUiModel) -> Unit = {},
    onPlayNextClick: (SongUiModel) -> Unit = {},
    onAddToQueueClick: (SongUiModel) -> Unit = {},
    onRemoveFromLibraryClick: (SongUiModel) -> Unit = {},
    onDeleteClick: (SongUiModel) -> Unit = {},
    onKeepOnPhoneClick: (SongUiModel) -> Unit = {},
    onKeepAllClick: (List<SongUiModel>) -> Unit = {},
    canKeepYouTube: Boolean = false,
    onAddAllToPlaylistClick: (List<SongUiModel>) -> Unit = {},
    onRemoveAlbumClick: ((List<SongUiModel>) -> Unit)? = null,
) {
    var songs by remember { mutableStateOf<List<SongUiModel>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val playbackState by viewModel.playbackState.collectAsState()
    val currentSongId = playbackState.currentSongId

    val refreshTrigger by viewModel.libraryRefreshTrigger.collectAsState()
    val loadFailedText = stringResource(R.string.library_album_load_failed)

    LaunchedEffect(album?.id, album?.sourceType, refreshTrigger) {
        if (album == null) {
            isLoading = false
            return@LaunchedEffect
        }
        isLoading = true
        errorMessage = null
        try {
            songs = viewModel.getAlbumSongsForDetails(album)
        } catch (e: Exception) {
            errorMessage = e.message ?: loadFailedText
        } finally {
            isLoading = false
        }
    }

    val discNumbers = remember(songs) {
        songs.map { it.discNumber ?: 1 }.distinct().sorted()
    }

    var selectedDiscIndex by remember(discNumbers) { mutableIntStateOf(0) }

    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
        when {
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    TextMMD(text = stringResource(R.string.library_album_loading))
                }
            }

            errorMessage != null -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    TextMMD(text = errorMessage!!)
                }
            }

            songs.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    TextMMD(text = stringResource(R.string.library_album_no_songs))
                }
            }

            else -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (discNumbers.size > 1) {
                        PrimaryTabRowMMD(selectedTabIndex = selectedDiscIndex) {
                            discNumbers.forEachIndexed { index, disc ->
                                TabMMD(
                                    selected = selectedDiscIndex == index,
                                    onClick = { selectedDiscIndex = index },
                                    text = {
                                        TextMMD(
                                            text = stringResource(R.string.library_album_disc, disc),
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = if (selectedDiscIndex == index) FontWeight.Bold else FontWeight.Normal,
                                        )
                                    },
                                )
                            }
                        }
                    }

                    val currentDiscNumber = discNumbers.getOrElse(selectedDiscIndex) { 1 }
                    val displaySongs = if (discNumbers.size > 1) {
                        songs.filter { (it.discNumber ?: 1) == currentDiscNumber }
                    } else {
                        songs
                    }

                    PagedColumnMMD(
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = 16.dp,
                        end = 16.dp,
                        bottom = 88.dp,
                    ),
                ) {
                        items(displaySongs.size) { index ->
                            val song = displaySongs[index]
                            SongItem(
                                song = song,
                                isCurrentlyPlaying = song.id == currentSongId,
                                onClick = {
                                    onPlaySongClick(song, songs)
                                },
                                onAddToPlaylist = { onAddToPlaylistClick(song) },
                                onPlayNext = { onPlayNextClick(song) },
                                onAddToQueue = { onAddToQueueClick(song) },
                                onRemoveFromLibrary = { onRemoveFromLibraryClick(song) },
                                onDelete = { onDeleteClick(song) },
                                        onKeepOnPhone = { onKeepOnPhoneClick(song) },
                                showDivider = song != displaySongs.lastOrNull(),
                                showTrackNumber = true,
                                isInLibrary = librarySongIds.contains(song.id),
                                // The heading above already says both. A compilation, where
                                // the tracks are by different people, still names each one.
                                knownArtist = album?.artist,
                                knownAlbum = album?.title,
                            )
                        }

                        // What this app put on the phone for the album: YouTube downloads and
                        // kept server songs, and any streamed song saved to the library. A file
                        // the reader put there themselves is not this app's to take as a batch;
                        // each still has its own Delete. A downloaded album could only be
                        // undone a song at a time, twenty-six presses for one record.
                        val removable = songs.filter {
                            it.id in librarySongIds && it.sourceType != "LOCAL_FILE"
                        }
                        if (onRemoveAlbumClick != null && removable.isNotEmpty()) {
                            item(key = "remove-album") {
                                RemoveAlbumButton(count = removable.size) { onRemoveAlbumClick(removable) }
                            }
                        }
                    }
                }
            }
        }

        TopBarActions {
            if (!isLoading && errorMessage == null && songs.isNotEmpty()) {
                IconButton(onClick = { onShuffleClick(songs) }) {
                    Icon(
                        imageVector = Icons.Shuffle,
                        contentDescription = stringResource(R.string.library_album_shuffle),
                    )
                }

                // Adding a whole album a song at a time was the thing the review thread
                // asked for; the write underneath it was already here and unused.
                IconButton(onClick = { onAddAllToPlaylistClick(songs) }) {
                    Icon(
                        imageVector = Icons.PlaylistAdd,
                        contentDescription = stringResource(R.string.library_album_add_to_playlist),
                    )
                }

                val editDetails = LocalEditDetails.current
                val editable = songs.filter { it.isEditable() }
                if (editDetails != null && editable.isNotEmpty()) {
                    IconButton(onClick = { editDetails(editable) }) {
                        Icon(
                            imageVector = Icons.Edit,
                            contentDescription = stringResource(R.string.library_album_edit),
                        )
                    }
                }

                // Only where there is something to download: a record already on the phone
                // needs nothing, and this button would then be a button that does nothing.
                if (songs.hasSongsToKeep(canKeepYouTube, LocalIsDownloaded.current)) {
                    IconButton(onClick = { onKeepAllClick(songs) }) {
                        Icon(
                            imageVector = Icons.Download,
                            contentDescription = stringResource(R.string.library_album_keep_on_phone),
                        )
                    }
                }
            }
        }
    }
}
/** Pressed once it says what it will do; pressed again within four seconds it does it. */
@Composable
private fun RemoveAlbumButton(count: Int, onConfirmed: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(4000)
            armed = false
        }
    }
    Column(modifier = Modifier.padding(top = 16.dp)) {
        OutlinedButtonMMD(
            onClick = { if (armed) onConfirmed() else armed = true },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
        ) {
            TextMMD(
                text = if (armed) {
                    pluralStringResource(R.plurals.library_album_remove_confirm, count, count)
                } else {
                    stringResource(R.string.library_album_remove)
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = if (armed) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}
