package com.wanderwildwood.jimeikin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.jimeikin.R

/**
 * The line along the bottom of every screen while anything downloads.
 *
 * A run of songs is minutes long and happens wherever the listener has wandered off to, so it
 * is said wherever they are rather than only in Downloads, which is where pressing it goes. When
 * the run ends, how it went is said here for a few seconds, where they last saw it going.
 */
@Composable
fun DownloadLineBar(
    text: String,
    progress: String?,
    onClick: () -> Unit,
) {
    val label = stringResource(R.string.download_line_open)
    Column(modifier = Modifier.fillMaxWidth().background(Color.White)) {
        // A rule rather than a shade: the panel has no greys worth trusting.
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color.Black))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clickable(onClick = onClick)
                .semantics { onClick(label = label) { onClick(); true } }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextMMD(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (progress != null) {
                Spacer(modifier = Modifier.width(8.dp))
                TextMMD(text = progress, style = MaterialTheme.typography.bodyMedium, color = Color.Black)
            }
        }
    }
}
