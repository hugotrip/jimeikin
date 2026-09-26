package com.wanderwildwood.jimeikin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.jimeikin.R

@Composable
fun LibraryOnboardingEmptyState(
    title: String,
    body: String,
    onOpenStreamingSettingsClick: () -> Unit,
    onOpenLocalSettingsClick: () -> Unit,
    onOpenMusicServerClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TextMMD(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(8.dp))

        TextMMD(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(24.dp))

        // In the order a copy is worth having in, which is also the order search lists them:
        // the phone, then a server at home, then YouTube.
        ButtonMMD(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            onClick = onOpenLocalSettingsClick,
        ) {
            TextMMD(
                text = stringResource(R.string.library_empty_choose_folder),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButtonMMD(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            onClick = onOpenMusicServerClick,
        ) {
            TextMMD(
                text = stringResource(R.string.library_empty_music_server),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButtonMMD(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            onClick = onOpenStreamingSettingsClick,
        ) {
            TextMMD(
                text = stringResource(R.string.library_empty_set_up_youtube),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
