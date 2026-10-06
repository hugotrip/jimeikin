package com.wanderwildwood.jimeikin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.jimeikin.DownloadItem
import com.wanderwildwood.jimeikin.DownloadKind
import com.wanderwildwood.jimeikin.DownloadState
import com.wanderwildwood.jimeikin.R

/**
 * Everything downloading, waiting, and finished in the last week: YouTube's songs and the music
 * server's in one list. What is still to come is at the top, in the order it will be fetched;
 * below it, the newest finished first.
 */
@Composable
fun DownloadsScreen(
    downloads: List<DownloadItem>,
    onCancelDownload: (String) -> Unit,
    onRetry: (String) -> Unit,
    onRetryAll: () -> Unit,
    onClear: () -> Unit,
) {
    val ordered = downloads.filter { it.state.isActive } +
        downloads.filter { !it.state.isActive }.sortedByDescending { it.updatedAt }
    val retryable = downloads.count { it.state.canRetry }
    val finished = downloads.count { !it.state.isActive }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        if (ordered.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                TextMMD(
                    text = stringResource(R.string.player_downloads_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            if (retryable > 1 || finished > 0) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    if (retryable > 1) {
                        SmallButton(
                            text = stringResource(R.string.player_downloads_retry_all),
                            onClick = onRetryAll,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    if (finished > 0) {
                        SmallButton(
                            text = stringResource(R.string.player_downloads_clear),
                            onClick = onClear,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            PagedColumnMMD(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // No keys: a row that finishes moves down out of the active ones, and a keyed list
                // would follow it there, scrolling what is still downloading out of sight.
                items(ordered.size) { index ->
                    val status = ordered[index]
                    DownloadRow(
                        status = status,
                        onCancel = { onCancelDownload(status.id) },
                        onRetry = { onRetry(status.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SmallButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButtonMMD(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        onClick = onClick,
    ) {
        TextMMD(text = text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DownloadRow(
    status: DownloadItem,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                TextMMD(
                    text = status.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                TextMMD(
                    text = stringResource(
                        R.string.download_line_join,
                        status.artist,
                        stringResource(
                            if (status.kind == DownloadKind.SERVER) R.string.player_downloads_from_server else R.string.player_downloads_from_youtube,
                        ),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }

            if (status.state.isActive) {
                IconButton(onClick = onCancel) {
                    Icon(
                        imageVector = Icons.Close,
                        contentDescription = stringResource(R.string.player_downloads_cancel),
                    )
                }
            } else if (status.state.canRetry) {
                SmallButton(text = stringResource(R.string.player_downloads_retry), onClick = onRetry)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // No bars. The panel redraws in full, so a sweeping indicator is a smear and a
        // battery cost; the percent says the same thing, and moves in tens.
        val said = when (status.state) {
            DownloadState.WAITING -> stringResource(R.string.player_downloads_waiting)
            DownloadState.DOWNLOADING -> status.percent?.let { stringResource(R.string.player_downloads_percent, it) }
                ?: stringResource(R.string.player_downloads_underway)
            DownloadState.DOWNLOADED -> stringResource(R.string.player_downloads_done)
            DownloadState.FAILED -> status.reason?.let {
                stringResource(R.string.download_line_join, stringResource(R.string.player_downloads_failed), it)
            } ?: stringResource(R.string.player_downloads_failed)
            DownloadState.CANCELED -> stringResource(R.string.player_downloads_canceled)
            DownloadState.NOT_FINISHED -> stringResource(R.string.player_downloads_not_finished)
        }
        TextMMD(text = said, style = MaterialTheme.typography.labelSmall)

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDividerMMD()
    }
}
