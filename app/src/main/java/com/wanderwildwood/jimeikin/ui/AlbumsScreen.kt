package com.wanderwildwood.jimeikin.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.menus.DropdownMenuItemMMD
import com.mudita.mmd.components.menus.DropdownMenuMMD
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.jimeikin.R
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** UI model for displaying albums in the library. */
data class AlbumUiModel(
    val id: String,
    val title: String,
    val artist: String?,
    val sourceType: String,
    /** Optional release year for display when available. */
    val releaseYear: Int? = null,
    /** Where it streams from, if not the phone. The library works this out from its songs. */
    val origin: Origin? = originOf(sourceType),
)

@Composable
fun AlbumsScreen(
    albums: List<AlbumUiModel>,
    isLoading: Boolean,
    errorMessage: String?,
    isSyncInProgress: Boolean,
    hasAnySongs: Boolean,
    onOpenStreamingSettingsClick: () -> Unit,
    onOpenLocalSettingsClick: () -> Unit,
    onOpenMusicServerClick: () -> Unit = {},
    onAlbumClick: (AlbumUiModel) -> Unit = {},
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
                    TextMMD(text = stringResource(R.string.library_albums_loading))
                }
            }

            errorMessage != null -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    TextMMD(text = stringResource(R.string.library_albums_error))
                }
            }

            albums.isEmpty() && isSyncInProgress -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    TextMMD(text = stringResource(R.string.library_sync_in_progress))
                }
            }

            albums.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!hasAnySongs) {
                        LibraryOnboardingEmptyState(
                            title = stringResource(R.string.library_albums_empty_title),
                            body = stringResource(R.string.library_nothing_added_yet),
                            onOpenStreamingSettingsClick = onOpenStreamingSettingsClick,
                            onOpenLocalSettingsClick = onOpenLocalSettingsClick,
                            onOpenMusicServerClick = onOpenMusicServerClick,
                        )
                    } else {
                        TextMMD(text = stringResource(R.string.library_albums_no_album_info))
                    }
                }
            }

            else -> {
                val lastAlbumId = albums.lastOrNull()?.id
                PagedColumnMMD(contentPadding = PaddingValues(16.dp)) {
                    items(
                        items = albums,
                        key = { it.id },
                    ) { album ->
                        val isLast = album.id == lastAlbumId
                        AlbumItem(
                            album = album,
                            onClick = { onAlbumClick(album) },
                            showDivider = !isLast,
                        )
                    }
                }
            }
        }
    }
}

/**
 * What a long press on an album row can do with the whole record, wherever the row is: the
 * library, an artist's page, a search, a YouTube artist's albums. The same four things a song's
 * own menu offers, on every track in album order. Provided once at the top like
 * [LocalEditDetails], so a page need not thread five handlers through to its rows.
 */
class AlbumActions(
    /** The album's tracks in order, as its own page would list them. */
    val songs: suspend (AlbumUiModel) -> List<SongUiModel>,
    val canKeepYouTube: Boolean,
    val onPlayNext: (AlbumUiModel, List<SongUiModel>) -> Unit,
    val onAddToQueue: (AlbumUiModel, List<SongUiModel>) -> Unit,
    val onAddToPlaylist: (List<SongUiModel>) -> Unit,
    val onDownload: (List<SongUiModel>) -> Unit,
    val onLoadFailed: () -> Unit,
)

val LocalAlbumActions = staticCompositionLocalOf<AlbumActions?> { null }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumItem(
    album: AlbumUiModel,
    onClick: () -> Unit,
    showDivider: Boolean = true,
    /** "Album", where albums share a list with songs and artists and need telling apart. */
    kindLabel: String? = null,
) {
    val actions = LocalAlbumActions.current
    val isDownloaded = LocalIsDownloaded.current
    val scope = rememberCoroutineScope()

    // The menu opens on the press and the tracks come behind it: a library album's are a
    // read of the database, a YouTube album's a fetch. Three of the rows need nothing until
    // pressed, and wait for the tracks then; Download alone appears once they are known,
    // since a record already on the phone has nothing to download and is not offered it.
    var showMenu by remember { mutableStateOf(false) }
    // Null until the tracks are asked for; a failed fetch leaves it null again for the next press.
    var loading by remember(album.id, album.sourceType) { mutableStateOf<Deferred<List<SongUiModel>?>?>(null) }
    var songs by remember(album.id, album.sourceType) { mutableStateOf<List<SongUiModel>?>(null) }

    fun withSongs(action: (List<SongUiModel>) -> Unit) {
        showMenu = false
        val known = songs
        if (known != null) {
            action(known)
            return
        }
        val pending = loading ?: return
        scope.launch {
            val loaded = pending.await()
            if (loaded == null) {
                loading = null
                actions?.onLoadFailed?.invoke()
            } else {
                action(loaded)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = actions?.let { a ->
                    {
                        showMenu = true
                        if (songs == null && loading == null) {
                            // Caught here rather than thrown: a failing child would take
                            // the row's whole scope down with it, and this one is shared.
                            loading = scope.async {
                                runCatching { a.songs(album) }.getOrNull()?.takeIf { it.isNotEmpty() }.also { songs = it }
                            }
                        }
                    }
                },
            )
            .padding(bottom = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                TextMMD(
                    text = album.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val details = when {
                    !album.artist.isNullOrBlank() && album.releaseYear != null ->
                        stringResource(R.string.library_album_artist_and_year, album.artist, album.releaseYear)
                    !album.artist.isNullOrBlank() -> album.artist
                    album.releaseYear != null -> album.releaseYear.toString()
                    else -> ""
                }
                val subtitle = listOfNotNull(kindLabel, details.takeIf { it.isNotBlank() }).joinToString(" • ")
                if (subtitle.isNotBlank() || album.origin != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    SubtitleLine(text = subtitle, origin = album.origin)
                }
            }

            if (showMenu && actions != null) {
                Box(modifier = Modifier.wrapContentSize()) {
                    Icon(
                        imageVector = Icons.Close,
                        contentDescription = stringResource(R.string.player_song_close_menu),
                        modifier = Modifier
                            .size(24.dp)
                            .clickable { showMenu = false }
                    )

                    DropdownMenuMMD(
                        expanded = true,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItemMMD(
                            text = { TextMMD(text = stringResource(R.string.player_song_play_next)) },
                            onClick = { withSongs { actions.onPlayNext(album, it) } }
                        )
                        HorizontalDividerMMD(thickness = 1.dp)
                        DropdownMenuItemMMD(
                            text = { TextMMD(text = stringResource(R.string.player_song_add_to_queue)) },
                            onClick = { withSongs { actions.onAddToQueue(album, it) } }
                        )
                        HorizontalDividerMMD(thickness = 1.dp)
                        DropdownMenuItemMMD(
                            text = { TextMMD(text = stringResource(R.string.player_add_to_playlist)) },
                            onClick = { withSongs { actions.onAddToPlaylist(it) } }
                        )
                        if (songs?.hasSongsToKeep(actions.canKeepYouTube, isDownloaded) == true) {
                            HorizontalDividerMMD(thickness = 1.dp)
                            DropdownMenuItemMMD(
                                text = { TextMMD(text = stringResource(R.string.player_song_keep_on_phone)) },
                                onClick = { withSongs { actions.onDownload(it) } }
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (showDivider) {
            HorizontalDividerMMD(thickness = 1.dp)
        }
    }
}
