package com.wanderwildwood.jimeikin.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.wanderwildwood.jimeikin.R
import com.wanderwildwood.jimeikin.data.TagEditor

/**
 * Opens the editor for these songs. Provided once at the top so that every song row, on
 * every screen, can offer "Edit details" without each screen passing it down.
 */
val LocalEditDetails = staticCompositionLocalOf<((List<SongUiModel>) -> Unit)?> { null }

/** Only a file in a chosen folder can be edited: a server song is the server's to change. */
fun SongUiModel.isEditable(): Boolean = sourceType == "LOCAL_FILE"

/**
 * The tags of one song, or the album-wide ones of several.
 *
 * For an album only the album's name and who it is by are offered: those are the fields
 * every track has to share, and a title or track number set across twelve songs at once is
 * never what anyone meant. Only fields that were changed are written.
 */
@Composable
fun EditDetailsScreen(
    songCount: Int,
    initial: TagEditor.Fields?,
    isSaving: Boolean,
    progressText: String?,
    errorText: String?,
    onSave: (TagEditor.Fields) -> Unit,
    onCancel: () -> Unit,
) {
    if (initial == null) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            TextMMD(text = stringResource(R.string.edit_details_reading))
        }
        return
    }

    val isAlbum = songCount > 1
    var title by remember(initial) { mutableStateOf(initial.title.orEmpty()) }
    var artist by remember(initial) { mutableStateOf(initial.artist.orEmpty()) }
    var album by remember(initial) { mutableStateOf(initial.album.orEmpty()) }
    var albumArtist by remember(initial) { mutableStateOf(initial.albumArtist.orEmpty()) }
    var track by remember(initial) { mutableStateOf(initial.trackNumber.orEmpty()) }

    fun changed(now: String, was: String?) = now.trim().takeIf { it != was.orEmpty().trim() }
    val fields = TagEditor.Fields(
        title = if (isAlbum) null else changed(title, initial.title),
        artist = if (isAlbum) null else changed(artist, initial.artist),
        album = changed(album, initial.album),
        albumArtist = changed(albumArtist, initial.albumArtist),
        trackNumber = if (isAlbum) null else changed(track, initial.trackNumber),
    )
    val hasChanges = fields != TagEditor.Fields()
    val trackIsValid = track.isBlank() || track.trim().toIntOrNull()?.let { it > 0 } == true

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        if (isAlbum) {
            TextMMD(
                text = stringResource(R.string.edit_details_album_note, songCount),
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.height(12.dp))
        } else {
            Field(title, { title = it }, R.string.edit_details_title, !isSaving)
            Field(artist, { artist = it }, R.string.edit_details_artist, !isSaving)
        }
        Field(album, { album = it }, R.string.edit_details_album, !isSaving)
        Field(albumArtist, { albumArtist = it }, R.string.edit_details_album_artist, !isSaving)
        if (!isAlbum) {
            Field(track, { track = it }, R.string.edit_details_track, !isSaving, numeric = true)
        }

        if (progressText != null) {
            TextMMD(text = progressText, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(12.dp))
        }
        if (errorText != null) {
            TextMMD(text = errorText, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(12.dp))
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedButtonMMD(
                onClick = onCancel,
                enabled = !isSaving,
                modifier = Modifier.weight(1f),
            ) {
                TextMMD(text = stringResource(R.string.player_cancel))
            }
            Spacer(modifier = Modifier.width(8.dp))
            ButtonMMD(
                onClick = { onSave(fields) },
                enabled = hasChanges && trackIsValid && !isSaving,
                modifier = Modifier.weight(1f),
            ) {
                TextMMD(text = stringResource(R.string.player_save))
            }
        }
    }
}

@Composable
private fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    labelRes: Int,
    enabled: Boolean,
    numeric: Boolean = false,
) {
    TextFieldMMD(
        modifier = Modifier.fillMaxWidth(),
        value = value,
        onValueChange = onValueChange,
        label = { TextMMD(text = stringResource(labelRes)) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
    )
    Spacer(Modifier.height(12.dp))
}
