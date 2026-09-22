package com.wanderwildwood.jimeikin.ui

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.switcher.SwitchMMD
import com.mudita.mmd.components.text.TextMMD
import kotlinx.coroutines.delay
import com.wanderwildwood.jimeikin.R

/**
 * One flat screen. It used to be three tabs holding one, three and five controls between
 * them, each control wrapped in a paragraph explaining itself; a screen with this little on
 * it does not need doors, and a label that needs a paragraph is the wrong label.
 *
 * Rows that only adjust come first, and the two that take something away come last.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    completeAlbumsWithYouTube: Boolean,
    onCompleteAlbumsWithYouTubeChange: (Boolean) -> Unit,
    localFolders: List<String>,
    isYoutubeAccountConnected: Boolean,
    onConnectYoutubeAccountClick: () -> Unit,
    onDisconnectYoutubeAccountClick: () -> Unit,
    hasBatteryOptimizationExemption: Boolean,
    onRequestBatteryOptimizationExemption: () -> Unit,
    onAddFolderClick: () -> Unit,
    onRemoveFolderClick: (String) -> Unit,
    onRescanLocalMusicClick: () -> Unit,
    onNavigateToDownloadsClick: () -> Unit,
    musicServerSummary: String,
    onNavigateToMusicServerClick: () -> Unit,
    isRescanningLocal: Boolean,
    isIngestingLocal: Boolean,
    localScanProgress: Float,
    localIngestProgress: Float,
    localScanTotalDiscovered: Int?,
    localScanSkippedUnchanged: Int?,
    localScanIndexedNewOrUpdated: Int?,
    localScanDeletedMissing: Int?,
    localScanUnreadableFolders: Int?,
) {
    val context = LocalContext.current
    LazyColumnMMD(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Top,
    ) {
        run {
            if (localFolders.isEmpty()) {
                item {
                    TextMMD(
                        text = stringResource(R.string.settings_no_folders),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            } else {
                items(localFolders) { folder ->
                    FolderRow(
                        path = formatDirectoryPath(
                            folder,
                            stringResource(R.string.settings_folder_phone),
                            stringResource(R.string.settings_folder_card),
                        ),
                        onRemove = { onRemoveFolderClick(folder) },
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ButtonMMD(
                        onClick = onAddFolderClick,
                        modifier = Modifier.weight(1f),
                    ) { TextMMD(text = stringResource(R.string.settings_add_folder), style = MaterialTheme.typography.titleSmall) }

                    if (localFolders.isNotEmpty()) {
                        OutlinedButtonMMD(
                            onClick = onRescanLocalMusicClick,
                            modifier = Modifier.weight(1f),
                            enabled = !isRescanningLocal && !isIngestingLocal,
                        ) { TextMMD(text = stringResource(R.string.settings_rescan_folders), style = MaterialTheme.typography.titleSmall) }
                    }
                }
            }

            // What the scan is doing, said in words. A bar that moves is a smear on this
            // panel, and the percentage was drawn twice - once as a slider nobody could
            // move, once as a number underneath it.
            val scanLine = when {
                isIngestingLocal ->
                    context.getString(R.string.settings_scan_adding, (localIngestProgress * 100f).toInt().coerceIn(0, 100))
                // Until the folders have been walked the app does not know how many songs
                // there are, so it does not know what fraction of them it has read. It used
                // to say 0% for the whole of that, which on a large card reads as stuck.
                isRescanningLocal && localScanProgress <= 0f ->
                    context.getString(R.string.settings_scan_looking)
                isRescanningLocal ->
                    context.getString(R.string.settings_scan_reading, (localScanProgress * 100f).toInt().coerceIn(0, 100))
                else -> null
            }
            if (scanLine != null) {
                item {
                    TextMMD(
                        text = scanLine,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            if (scanLine == null && localScanTotalDiscovered != null) {
                item {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        TextMMD(
                            text = localScanIndexedNewOrUpdated
                                ?.takeIf { it > 0 }
                                ?.let {
                                    pluralStringResource(
                                        R.plurals.settings_scan_found_new_or_changed,
                                        localScanTotalDiscovered,
                                        localScanTotalDiscovered,
                                        it,
                                    )
                                }
                                ?: pluralStringResource(
                                    R.plurals.settings_scan_found,
                                    localScanTotalDiscovered,
                                    localScanTotalDiscovered,
                                ),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        if (localScanDeletedMissing != null && localScanDeletedMissing > 0) {
                            TextMMD(
                                text = pluralStringResource(
                                    R.plurals.settings_scan_removed_missing,
                                    localScanDeletedMissing,
                                    localScanDeletedMissing,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        if (localScanUnreadableFolders != null && localScanUnreadableFolders > 0) {
                            TextMMD(
                                text = if (localScanUnreadableFolders == 1) {
                                    stringResource(R.string.settings_scan_one_folder_unreadable)
                                } else {
                                    pluralStringResource(
                                        R.plurals.settings_scan_folders_unreadable,
                                        localScanUnreadableFolders,
                                        localScanUnreadableFolders,
                                    )
                                },
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }

        item { Separator() }

        item {
            SwitchRow(
                label = stringResource(R.string.settings_fill_album_gaps),
                checked = completeAlbumsWithYouTube,
                onCheckedChange = onCompleteAlbumsWithYouTubeChange,
            )
        }

        item {
            ValueRow(
                label = stringResource(R.string.settings_background_playback),
                value = if (hasBatteryOptimizationExemption) {
                    stringResource(R.string.settings_background_playback_allowed)
                } else {
                    stringResource(R.string.settings_background_playback_not_allowed)
                },
                onClick = onRequestBatteryOptimizationExemption,
                enabled = !hasBatteryOptimizationExemption,
            )
        }

        item {
            // Downloads is transient status about streaming rather than a place of its own;
            // it used to cost a row on a screen that existed only to hold three doors. The
            // label carries itself, so it gets no second line.
            LinkRow(label = stringResource(R.string.settings_downloads), onClick = onNavigateToDownloadsClick)
        }

        item {
            ValueRow(
                label = stringResource(R.string.settings_music_server),
                value = musicServerSummary,
                onClick = onNavigateToMusicServerClick,
                enabled = true,
            )
        }

        item {
            ValueRow(
                label = stringResource(R.string.settings_youtube_account),
                value = if (isYoutubeAccountConnected) stringResource(R.string.settings_youtube_connected) else stringResource(R.string.settings_youtube_not_connected),
                onClick = if (isYoutubeAccountConnected) null else onConnectYoutubeAccountClick,
                enabled = !isYoutubeAccountConnected,
            )
        }

        if (isYoutubeAccountConnected) {
            item { Separator() }
            item {
                ArmedRow(
                    label = stringResource(R.string.settings_disconnect_account),
                    armedLabel = stringResource(R.string.settings_disconnect_account_armed),
                    onConfirm = onDisconnectYoutubeAccountClick,
                )
            }
        }
    }
}

/** A row that only opens somewhere. It has no value to show, so it shows none. */
@Composable
private fun LinkRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextMMD(text = label, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun Separator() {
    HorizontalDividerMMD(
        thickness = 1.dp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextMMD(text = label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        SwitchMMD(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** A row shows its value, and otherwise says nothing. */
@Composable
private fun ValueRow(
    label: String,
    value: String,
    onClick: (() -> Unit)?,
    enabled: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        TextMMD(text = label, style = MaterialTheme.typography.titleSmall)
        TextMMD(text = value, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * The row asks, rather than a dialog: one repaint instead of two, and the question is put in
 * the place the answer belongs. It disarms itself, so a stray tap leaves nothing live for
 * whoever picks the phone up next.
 */
@Composable
private fun ArmedRow(
    label: String,
    armedLabel: String,
    onConfirm: () -> Unit,
) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(4000)
            armed = false
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (armed) {
                    armed = false
                    onConfirm()
                } else {
                    armed = true
                }
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextMMD(
            text = if (armed) armedLabel else label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (armed) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/**
 * A folder row is its own remove button. The trash glyph beside it removed the folder on one
 * tap, unasked, and cost a column of width to do it.
 */
@Composable
private fun FolderRow(path: String, onRemove: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(4000)
            armed = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (armed) {
                    armed = false
                    onRemove()
                } else {
                    armed = true
                }
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (armed) {
            TextMMD(
                text = stringResource(R.string.settings_remove_folder_armed),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(2.dp))
        }
        TextMMD(text = path, style = MaterialTheme.typography.labelSmall)
    }
}

private fun formatDirectoryPath(uriString: String, phone: String, card: String): String {
    try {
        val decoded = Uri.decode(uriString)
        // A card is "<uuid>:Music", the built-in storage is "primary:Music". Both used to
        // fall through to the raw content:// uri, which told the reader nothing.
        val treeMarker = decoded.substringAfterLast("/tree/", "")
        val body = if (treeMarker.isNotEmpty()) treeMarker else decoded
        val volume = body.substringBefore(':', "")
        val path = body.substringAfter(':', "").replace("/", " > ")
        val volumeLabel = when {
            volume.equals("primary", ignoreCase = true) -> phone
            volume.isNotEmpty() -> card
            else -> return uriString
        }
        return if (path.isEmpty()) volumeLabel else "$volumeLabel > $path"
    } catch (e: Exception) {
        return uriString
    }
}
