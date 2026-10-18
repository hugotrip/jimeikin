package com.wanderwildwood.jimeikin.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.jimeikin.R
import kotlinx.coroutines.delay

/**
 * "File under…": the artists another one can be put under, for a credit the automatic
 * grouping left on its own ("Asake & Tiakola" when the library holds no plain Asake).
 *
 * Choosing one writes that artist as the album artist of every song of [artist] on the phone,
 * the same tag Edit details writes. A first tap arms the row and says how many songs will
 * change; a second within four seconds does it. Songs from a music server cannot be written
 * from here, and the row says so when there are any.
 */
@Composable
fun FileUnderScreen(
    artist: ArtistUiModel,
    others: List<ArtistUiModel>,
    /** How many of [artist]'s songs are files on the phone, and how many are on a server; null while counting. */
    counts: Pair<Int, Int>?,
    isSaving: Boolean,
    progressText: String?,
    errorText: String?,
    onChoose: (ArtistUiModel) -> Unit,
) {
    var armedId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(armedId) {
        if (armedId != null) {
            delay(4000)
            armedId = null
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            TextMMD(
                text = stringResource(R.string.file_under_lead, artist.name),
                style = MaterialTheme.typography.bodyLarge,
            )
            val note = when {
                progressText != null -> progressText
                errorText != null -> errorText
                counts == null -> stringResource(R.string.file_under_counting)
                counts.first == 0 -> stringResource(R.string.file_under_none_on_phone)
                counts.second > 0 -> pluralStringResource(R.plurals.file_under_server_note, counts.second, counts.second)
                else -> null
            }
            if (note != null) {
                Spacer(modifier = Modifier.height(4.dp))
                TextMMD(text = note, style = MaterialTheme.typography.bodySmall)
            }
        }
        HorizontalDividerMMD(thickness = 1.dp)

        val canWrite = counts != null && counts.first > 0 && !isSaving
        PagedColumnMMD(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            jump = remember(others) { ListJump.Letters(others.map { it.name }) },
        ) {
            items(items = others, key = { it.id }) { target ->
                val isArmed = armedId == target.id
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = canWrite) {
                            if (isArmed) {
                                armedId = null
                                onChoose(target)
                            } else {
                                armedId = target.id
                            }
                        }
                        .padding(vertical = 12.dp),
                ) {
                    TextMMD(
                        text = if (isArmed && counts != null) {
                            pluralStringResource(R.plurals.file_under_confirm, counts.first, counts.first, target.name)
                        } else {
                            target.name
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isArmed) FontWeight.Bold else FontWeight.Normal,
                        maxLines = if (isArmed) 3 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
