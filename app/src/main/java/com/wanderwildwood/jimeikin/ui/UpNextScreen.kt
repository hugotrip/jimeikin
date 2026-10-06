package com.wanderwildwood.jimeikin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.jimeikin.R

/**
 * The queue, from the song playing to the end: what an album, a playlist or "Add to queue" has
 * lined up.
 *
 * Songs move with a pair of arrows rather than by dragging. A drag on this panel is a row that
 * smears down the screen behind your finger and lands a place off, and an arrow is one press
 * and one redraw. Only what is still to come moves or comes out; the song playing stays put.
 * The songs already played are left off - they are behind you, and an album twelve songs in
 * would otherwise open on a page of them.
 */
@Composable
fun UpNextScreen(
    queue: List<SongUiModel>,
    currentIndex: Int,
    onBackClick: () -> Unit,
    onSongClick: (Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onRemove: (Int) -> Unit,
    onSaveAsPlaylist: () -> Unit,
) {
    val lastIndex = queue.lastIndex
    val toCome = (lastIndex - currentIndex).coerceAtLeast(0)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            // Whatever is under this page must not take its presses.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {}
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.clickable(onClick = onBackClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Back,
                contentDescription = stringResource(R.string.player_back),
            )
            Spacer(modifier = Modifier.width(12.dp))
            TextMMD(
                text = stringResource(R.string.queue_title),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextMMD(
            text = if (toCome == 0) {
                stringResource(R.string.queue_nothing_after)
            } else {
                pluralStringResource(R.plurals.queue_songs_to_come, toCome, toCome)
            },
            style = MaterialTheme.typography.labelSmall,
        )

        Spacer(modifier = Modifier.height(8.dp))

        val shown = if (currentIndex in queue.indices) queue.drop(currentIndex) else emptyList()

        PagedColumnMMD(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            itemsIndexed(
                items = shown,
                // A song can be in the queue twice, so its place is part of its key.
                key = { offset, song -> "${currentIndex + offset}:${song.id}" },
            ) { offset, song ->
                val at = currentIndex + offset
                UpNextRow(
                    song = song,
                    isPlaying = offset == 0,
                    canMoveUp = at > currentIndex + 1,
                    canMoveDown = offset > 0 && at < lastIndex,
                    onClick = { if (offset > 0) onSongClick(at) },
                    onMoveUp = { onMove(at, at - 1) },
                    onMoveDown = { onMove(at, at + 1) },
                    onRemove = { onRemove(at) },
                )
                if (at < lastIndex) HorizontalDividerMMD(thickness = 1.dp)
            }

            item(key = "save") {
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButtonMMD(
                    onClick = onSaveAsPlaylist,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(12.dp),
                ) {
                    TextMMD(
                        text = stringResource(R.string.queue_save_as_playlist),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
private fun UpNextRow(
    song: SongUiModel,
    isPlaying: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onClick: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onClick)
                .padding(vertical = 8.dp),
        ) {
            TextMMD(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(4.dp))
            val subtitle = if (isPlaying) {
                stringResource(R.string.queue_playing)
            } else {
                listOfNotNull(
                    song.artist.takeIf { it.isNotBlank() },
                    song.durationText?.takeIf { it.isNotBlank() },
                ).joinToString(" • ")
            }
            TextMMD(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (isPlaying) {
            Icon(
                imageVector = Icons.Headphones,
                contentDescription = stringResource(R.string.player_song_now_playing),
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .size(24.dp),
            )
        } else {
            // An arrow with nowhere to go is left as a gap rather than drawn grey, so the
            // three buttons stay in their columns down the page.
            ArrowSlot(canMoveUp, Icons.ArrowUp, stringResource(R.string.queue_move_up), onMoveUp)
            ArrowSlot(canMoveDown, Icons.ArrowDown, stringResource(R.string.queue_move_down), onMoveDown)
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Close,
                    contentDescription = stringResource(R.string.queue_remove),
                )
            }
        }
    }
}

@Composable
private fun ArrowSlot(
    enabled: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    if (enabled) {
        IconButton(onClick = onClick) {
            Icon(imageVector = icon, contentDescription = description)
        }
    } else {
        Spacer(modifier = Modifier.size(48.dp))
    }
}
