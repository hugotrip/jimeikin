package com.wanderwildwood.jimeikin.ui

import com.wanderwildwood.jimeikin.data.ArtistNames
import com.mudita.mmd.components.text_field.TextFieldMMD
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.checkbox.CheckboxMMD
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.jimeikin.R

@Composable
fun PlaylistAddSongsScreen(
    songs: List<SongUiModel>,
    initialSelectedSongIds: Set<String>,
    onSelectionChanged: (Set<String>) -> Unit,
) {
    var selectedIds by remember { mutableStateOf(initialSelectedSongIds) }

    LaunchedEffect(initialSelectedSongIds) {
        selectedIds = initialSelectedSongIds
    }

    // Scrolling a whole library a page at a time to find three songs was the complaint (Mudita
    // forum, 2026-09-26). What is ticked stays ticked while the list is narrowed, so a
    // playlist can be built from several searches in a row.
    var filter by remember { mutableStateOf("") }
    val shown = remember(songs, filter) {
        val key = ArtistNames.key(filter)
        if (filter.isBlank()) {
            songs
        } else {
            songs.filter { song ->
                listOfNotNull(song.title, song.artist, song.album).any {
                    it.contains(filter.trim(), ignoreCase = true) || (key.isNotEmpty() && ArtistNames.key(it).contains(key))
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (songs.isNotEmpty()) {
            TextFieldMMD(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                value = filter,
                onValueChange = { filter = it },
                label = { TextMMD(text = stringResource(R.string.player_add_songs_find)) },
                singleLine = true,
                trailingIcon = if (filter.isNotEmpty()) {
                    {
                        IconButton(onClick = { filter = "" }) {
                            Icon(imageVector = Icons.Close, contentDescription = stringResource(R.string.main_cd_clear_search))
                        }
                    }
                } else {
                    null
                },
            )
        }

        when {
            songs.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                TextMMD(text = stringResource(R.string.player_add_songs_empty))
            }

            shown.isEmpty() -> TextMMD(
                text = stringResource(R.string.player_add_songs_no_match),
                modifier = Modifier.padding(16.dp),
            )

            else -> PagedColumnMMD(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
            ) {
                items(shown.size) { index ->
                    val song = shown[index]
                    val isSelected = selectedIds.contains(song.id)

                    SelectableSongItem(
                        song = song,
                        isSelected = isSelected,
                        onToggleSelected = {
                            val newSelection = selectedIds.toMutableSet()
                            if (isSelected) {
                                newSelection.remove(song.id)
                            } else {
                                newSelection.add(song.id)
                            }
                            selectedIds = newSelection
                            onSelectionChanged(newSelection)
                        },
                        showDivider = index < shown.lastIndex,
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectableSongItem(
    song: SongUiModel,
    isSelected: Boolean,
    onToggleSelected: () -> Unit,
    showDivider: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleSelected)
            .padding(bottom = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CheckboxMMD(
                checked = isSelected,
                onCheckedChange = { onToggleSelected() },
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                TextMMD(
                    text = song.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                val isLocal = song.sourceType == "LOCAL_FILE" || song.sourceType == "YOUTUBE_DOWNLOAD"
                val fileExtension = if (isLocal) {
                    val uriString = song.audioUri ?: song.id
                    try {
                        val lastSegment = Uri.parse(uriString).lastPathSegment ?: ""
                        lastSegment.substringAfterLast('.', "").lowercase()
                    } catch (_: Exception) {
                        ""
                    }
                } else {
                    ""
                }
                val isMp4 = isLocal && fileExtension == "mp4"

                val localFileLabel = stringResource(R.string.player_song_local_file)
                val baseArtist = song.artist.ifBlank { if (isLocal) localFileLabel else "" }
                val prefix = when {
                    isMp4 -> "MP4 • "
                    else -> ""
                }
                val subtitle = if (!song.durationText.isNullOrBlank()) {
                    "$prefix${baseArtist} • ${song.durationText}"
                } else {
                    if (baseArtist.isNotBlank()) "$prefix$baseArtist" else if (prefix.isNotBlank()) prefix.trimEnd(' ', '•') else ""
                }
                SubtitleLine(text = subtitle, origin = originOf(song.sourceType))
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (showDivider) {
            HorizontalDividerMMD(thickness = 1.dp)
        }
    }
}
