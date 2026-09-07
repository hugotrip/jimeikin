package com.wanderwildwood.jimeikin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wanderwildwood.jimeikin.YouTubeDownloadStatus
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.text.TextMMD

@Composable
fun DownloadsScreen(
    downloads: List<YouTubeDownloadStatus>,
    onCancelDownload: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        if (downloads.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                TextMMD(
                    text = "No recent downloads",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumnMMD(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(downloads.size) { index ->
                    val status = downloads[index]
                    DownloadItem(
                        status = status,
                        onCancel = { onCancelDownload(status.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadItem(
    status: YouTubeDownloadStatus,
    onCancel: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                TextMMD(
                    text = status.title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                TextMMD(
                    text = status.artist,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }

            if (status.state == YouTubeDownloadStatus.State.PENDING || status.state == YouTubeDownloadStatus.State.IN_PROGRESS) {
                IconButton(onClick = onCancel) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "Cancel",
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        when (status.state) {
            // No bars. The panel redraws in full, so a sweeping indicator is a smear and a
            // battery cost, and the percentage below it already said the same thing.
            YouTubeDownloadStatus.State.PENDING -> {
                TextMMD(text = "Waiting", fontSize = 12.sp)
            }
            YouTubeDownloadStatus.State.IN_PROGRESS -> {
                TextMMD(text = "${(status.progress * 100).toInt()}%", fontSize = 12.sp)
            }
            YouTubeDownloadStatus.State.COMPLETED -> {
                TextMMD(text = "Downloaded", fontSize = 12.sp)
            }
            YouTubeDownloadStatus.State.FAILED -> {
                TextMMD(text = "This one did not download", fontSize = 12.sp)
            }
            YouTubeDownloadStatus.State.CANCELED -> {
                TextMMD(text = "Canceled", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDividerMMD()
    }
}