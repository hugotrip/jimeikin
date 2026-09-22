package com.wanderwildwood.jimeikin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.wanderwildwood.jimeikin.R

@Composable
fun PlaylistEditScreen(
    initialName: String,
    isEditing: Boolean,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        TextFieldMMD(
            modifier = Modifier.fillMaxWidth(),
            value = name,
            onValueChange = { name = it },
            label = { TextMMD(text = stringResource(R.string.player_playlist_name)) },
            singleLine = true,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedButtonMMD(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
            ) {
                TextMMD(text = stringResource(R.string.player_cancel))
            }

            Spacer(modifier = Modifier.width(8.dp))

            ButtonMMD(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                TextMMD(text = if (isEditing) stringResource(R.string.player_save) else stringResource(R.string.player_create))
            }
        }
    }
}
