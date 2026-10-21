package com.wanderwildwood.jimeikin.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.jimeikin.R

data class PlaylistUiModel(
    val id: String,
    val name: String,
    val description: String?,
    val songCount: Int? = null,
)

@Composable
fun PlaylistsScreen(
    playlists: List<PlaylistUiModel>,
    isInEditMode: Boolean,
    onPlaylistClick: (PlaylistUiModel) -> Unit,
    onAddPlaylistClick: () -> Unit,
    onSelectionChanged: (Set<String>) -> Unit,
    /** How many songs are hearted; the Hearted list heads the playlists while there are any. */
    heartedCount: Int = 0,
    onHeartedClick: () -> Unit = {},
    /** Reads the library again from where it comes from; a swipe down on the list's first page. */
    onPullAtTop: (() -> Unit)? = null,
) {
    val selectedState = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(isInEditMode) {
        if (!isInEditMode && selectedState.isNotEmpty()) {
            selectedState.clear()
            onSelectionChanged(emptySet())
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
        val showHearted = heartedCount > 0 && !isInEditMode
        if (playlists.isEmpty() && !showHearted) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    TextMMD(
                        text = stringResource(R.string.player_playlists_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    ButtonMMD(
                        onClick = onAddPlaylistClick,
                    ) {
                        TextMMD(text = stringResource(R.string.player_playlists_add))
                    }
                }
            }
        } else {
            val lastPlaylistId = playlists.lastOrNull()?.id
            PagedColumnMMD(
                    onPullAtTop = onPullAtTop.takeIf { !isInEditMode },
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = 16.dp,
                        end = 16.dp,
                        bottom = 0.dp,
                    ),
                ) {
                if (showHearted) {
                    item(key = "hearted") {
                        PlaylistItem(
                            playlist = PlaylistUiModel(
                                id = "hearted",
                                name = stringResource(R.string.hearted_title),
                                description = null,
                                songCount = heartedCount,
                            ),
                            onClick = onHeartedClick,
                            showDivider = playlists.isNotEmpty(),
                        )
                    }
                }
                items(
                    items = playlists,
                    key = { it.id },
                ) { playlist ->
                    val isSelected = selectedState[playlist.id] == true
                    if (isInEditMode) {
                        SelectablePlaylistItem(
                            playlist = playlist,
                            isSelected = isSelected,
                            onSelectionChange = { nowSelected ->
                                if (nowSelected) {
                                    selectedState[playlist.id] = true
                                } else {
                                    selectedState.remove(playlist.id)
                                }
                                val currentSelectedIds = selectedState.filterValues { it }.keys
                                onSelectionChanged(currentSelectedIds)
                            },
                            showDivider = playlist.id != lastPlaylistId,
                        )
                    } else {
                        PlaylistItem(
                            playlist = playlist,
                            onClick = { onPlaylistClick(playlist) },
                            showDivider = playlist.id != lastPlaylistId,
                        )
                    }
                }
            }
        }

        TopBarActions {
            if ((playlists.isNotEmpty() || showHearted) && !isInEditMode) {
                IconButton(
                    onClick = onAddPlaylistClick,
                ) {
                    Icon(
                        imageVector = Icons.Plus,
                        contentDescription = stringResource(R.string.player_playlists_new),
                    )
                }
            }
        }
    }
}