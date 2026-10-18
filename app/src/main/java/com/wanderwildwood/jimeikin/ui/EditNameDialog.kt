package com.wanderwildwood.jimeikin.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.wanderwildwood.jimeikin.R
import com.wanderwildwood.jimeikin.data.HeartedSongEntity
import com.wanderwildwood.jimeikin.data.Names

/** What Edit name was asked for on. */
sealed interface NameTarget {
    data class Song(val song: SongUiModel) : NameTarget
    data class Album(val album: AlbumUiModel) : NameTarget
    data class Artist(val artist: ArtistUiModel) : NameTarget
}

/**
 * Edit name, from a row's menu. Provided once at the top like [LocalEditDetails]. An album is
 * offered it only where the library has a row for it: a YouTube listing has nothing to name.
 */
class NameEditing(
    val canEditAlbum: (AlbumUiModel) -> Boolean,
    val edit: (NameTarget) -> Unit,
)

val LocalNameEditing = staticCompositionLocalOf<NameEditing?> { null }

/** The names the reader gave things, for a row that was drawn before the name was given. */
val LocalNames = staticCompositionLocalOf<Names?> { null }

/** A song's title as the reader named it. */
@Composable
fun SongUiModel.shownTitle(): String = LocalNames.current?.songTitle(id) ?: title

/** Hearting songs, from a song's menu; see [com.wanderwildwood.jimeikin.data.Hearts]. */
class HeartActions(
    val isHearted: (SongUiModel) -> Boolean,
    val toggle: (SongUiModel) -> Unit,
)

val LocalHearts = staticCompositionLocalOf<HeartActions?> { null }

/** What a hearted song keeps of itself, so the Hearted list can play it from anywhere. */
fun SongUiModel.toHeartedRow(nowMillis: Long) = HeartedSongEntity(
    songId = id,
    title = title,
    artist = artist,
    album = album,
    sourceType = sourceType,
    audioUri = audioUri,
    durationMillis = durationMillis,
    heartedAtMillis = nowMillis,
)

fun HeartedSongEntity.toSongUiModel(format: (Long?) -> String?) = SongUiModel(
    id = songId,
    title = title,
    artist = artist,
    durationText = format(durationMillis),
    durationMillis = durationMillis,
    sourceType = sourceType,
    audioUri = audioUri,
    album = album,
)

/**
 * Edit name: the name as it is shown, to change. Nothing is written to the song itself; the
 * app shows the new name wherever the old one was. An empty field gives back the name it
 * came with. An album also has its artist to name.
 */
@Composable
fun EditNameDialog(
    name: String,
    /** The album's artist; null for a song or an artist, which have no second field. */
    artist: String?,
    onSave: (name: String, artist: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var nameField by remember(name) { mutableStateOf(name) }
    var artistField by remember(artist) { mutableStateOf(artist.orEmpty()) }

    EInkDialog(onDismiss = onDismiss) {
        TextMMD(
            text = stringResource(R.string.edit_name_title),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(12.dp))
        TextFieldMMD(
            modifier = Modifier.fillMaxWidth(),
            value = nameField,
            onValueChange = { nameField = it },
            label = { TextMMD(text = stringResource(R.string.edit_name_name)) },
            singleLine = true,
        )
        if (artist != null) {
            Spacer(Modifier.height(12.dp))
            TextFieldMMD(
                modifier = Modifier.fillMaxWidth(),
                value = artistField,
                onValueChange = { artistField = it },
                label = { TextMMD(text = stringResource(R.string.edit_details_album_artist)) },
                singleLine = true,
            )
        }
        Spacer(Modifier.height(8.dp))
        TextMMD(text = stringResource(R.string.edit_name_note), style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(18.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedButtonMMD(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                TextMMD(text = stringResource(R.string.player_cancel))
            }
            Spacer(Modifier.width(8.dp))
            ButtonMMD(onClick = { onSave(nameField, artistField) }, modifier = Modifier.weight(1f)) {
                TextMMD(text = stringResource(R.string.player_save))
            }
        }
    }
}
