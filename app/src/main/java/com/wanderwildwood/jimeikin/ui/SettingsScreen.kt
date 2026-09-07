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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.switcher.SwitchMMD
import com.mudita.mmd.components.text.TextMMD
import kotlinx.coroutines.delay

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
    includeLocalMusic: Boolean,
    localFolders: List<String>,
    isYoutubeAccountConnected: Boolean,
    onConnectYoutubeAccountClick: () -> Unit,
    onDisconnectYoutubeAccountClick: () -> Unit,
    hasBatteryOptimizationExemption: Boolean,
    onRequestBatteryOptimizationExemption: () -> Unit,
    onIncludeLocalMusicChange: (Boolean) -> Unit,
    onAddFolderClick: () -> Unit,
    onRemoveFolderClick: (String) -> Unit,
    onRescanLocalMusicClick: () -> Unit,
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
    LazyColumnMMD(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Top,
    ) {
        item {
            SwitchRow(
                label = "Music on this phone",
                checked = includeLocalMusic,
                onCheckedChange = onIncludeLocalMusicChange,
            )
        }

        if (includeLocalMusic) {
            if (localFolders.isEmpty()) {
                item {
                    TextMMD(
                        text = "No folders yet",
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            } else {
                items(localFolders) { folder ->
                    FolderRow(
                        path = formatDirectoryPath(folder),
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
                    ) { TextMMD(text = "Add a folder", fontSize = 16.sp) }

                    if (localFolders.isNotEmpty()) {
                        OutlinedButtonMMD(
                            onClick = onRescanLocalMusicClick,
                            modifier = Modifier.weight(1f),
                            enabled = !isRescanningLocal && !isIngestingLocal,
                        ) { TextMMD(text = "Read them again", fontSize = 16.sp) }
                    }
                }
            }

            // What the scan is doing, said in words. A bar that moves is a smear on this
            // panel, and the percentage was drawn twice - once as a slider nobody could
            // move, once as a number underneath it.
            val scanLine = when {
                isIngestingLocal ->
                    "Adding to the library, ${(localIngestProgress * 100f).toInt().coerceIn(0, 100)}%"
                isRescanningLocal ->
                    "Reading the folders, ${(localScanProgress * 100f).toInt().coerceIn(0, 100)}%"
                else -> null
            }
            if (scanLine != null) {
                item {
                    TextMMD(
                        text = scanLine,
                        fontSize = 14.sp,
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
                            text = "$localScanTotalDiscovered songs found" +
                                (localScanIndexedNewOrUpdated
                                    ?.takeIf { it > 0 }
                                    ?.let { ", $it new or changed" } ?: ""),
                            fontSize = 13.sp,
                        )
                        if (localScanDeletedMissing != null && localScanDeletedMissing > 0) {
                            TextMMD(
                                text = "$localScanDeletedMissing were no longer in the folders " +
                                    "and have left the library. The files were not touched.",
                                fontSize = 13.sp,
                            )
                        }
                        if (localScanUnreadableFolders != null && localScanUnreadableFolders > 0) {
                            TextMMD(
                                text = if (localScanUnreadableFolders == 1) {
                                    "One folder could not be read. Its songs were left alone."
                                } else {
                                    "$localScanUnreadableFolders folders could not be read. " +
                                        "Their songs were left alone."
                                },
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
            }
        }

        item { Separator() }

        item {
            SwitchRow(
                label = "Fill in album gaps from YouTube",
                checked = completeAlbumsWithYouTube,
                onCheckedChange = onCompleteAlbumsWithYouTubeChange,
            )
        }

        item {
            ValueRow(
                label = "Background playback",
                value = if (hasBatteryOptimizationExemption) {
                    "Allowed"
                } else {
                    "The system may stop it — tap to allow"
                },
                onClick = onRequestBatteryOptimizationExemption,
                enabled = !hasBatteryOptimizationExemption,
            )
        }

        item {
            ValueRow(
                label = "YouTube account",
                value = if (isYoutubeAccountConnected) "Connected" else "Not connected",
                onClick = if (isYoutubeAccountConnected) null else onConnectYoutubeAccountClick,
                enabled = !isYoutubeAccountConnected,
            )
        }

        if (isYoutubeAccountConnected) {
            item { Separator() }
            item {
                ArmedRow(
                    label = "Disconnect the account",
                    armedLabel = "Disconnect the account — tap again",
                    onConfirm = onDisconnectYoutubeAccountClick,
                )
            }
        }
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
        TextMMD(text = label, fontSize = 16.sp, modifier = Modifier.weight(1f))
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
        TextMMD(text = label, fontSize = 16.sp)
        TextMMD(text = value, fontSize = 14.sp)
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
            fontSize = 16.sp,
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
                text = "Stop reading this folder — its songs leave the library; tap again",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(2.dp))
        }
        TextMMD(text = path, fontSize = 14.sp)
    }
}

private fun formatDirectoryPath(uriString: String): String {
    try {
        val decoded = Uri.decode(uriString)
        // A card is "<uuid>:Music", the built-in storage is "primary:Music". Both used to
        // fall through to the raw content:// uri, which told the reader nothing.
        val treeMarker = decoded.substringAfterLast("/tree/", "")
        val body = if (treeMarker.isNotEmpty()) treeMarker else decoded
        val volume = body.substringBefore(':', "")
        val path = body.substringAfter(':', "").replace("/", " > ")
        val volumeLabel = when {
            volume.equals("primary", ignoreCase = true) -> "Phone"
            volume.isNotEmpty() -> "Card"
            else -> return uriString
        }
        return if (path.isEmpty()) volumeLabel else "$volumeLabel > $path"
    } catch (e: Exception) {
        return uriString
    }
}
