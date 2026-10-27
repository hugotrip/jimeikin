package com.wanderwildwood.jimeikin.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.wanderwildwood.jimeikin.CalmMusic
import com.wanderwildwood.jimeikin.R
import com.wanderwildwood.jimeikin.youTubeAccount

/** Settings rows for YouTube Music, which only the full edition has; see [SettingsScreen]. */
internal fun LazyListScope.onlineSwitches() {
    item {
        val online = (LocalContext.current.applicationContext as CalmMusic).online
        val completeAlbums by online.completeAlbums.collectAsState()
        SwitchRow(
            label = stringResource(R.string.settings_fill_album_gaps),
            checked = completeAlbums,
            onCheckedChange = online::setCompleteAlbums,
        )
    }

    item {
        val online = (LocalContext.current.applicationContext as CalmMusic).online
        val keepPlayingSimilar by online.keepPlayingSimilar.collectAsState()
        SwitchRow(
            label = stringResource(R.string.settings_keep_playing_similar),
            checked = keepPlayingSimilar,
            onCheckedChange = online::setKeepPlayingSimilar,
        )
    }
}

internal fun LazyListScope.onlineDownloadRows() {
    item {
        // Off by default: the songs fetched again as AAC wait for Wi-Fi. The note says why
        // turning it on is worth a thought.
        val context = LocalContext.current
        val settings = (context.applicationContext as CalmMusic).settingsManager
        var upgradeOnMobileData by remember { mutableStateOf(settings.getUpgradeOnMobileDataSync()) }
        SwitchRow(
            label = stringResource(R.string.settings_upgrade_on_mobile_data),
            note = stringResource(R.string.settings_upgrade_on_mobile_data_note),
            checked = upgradeOnMobileData,
            onCheckedChange = { on ->
                upgradeOnMobileData = on
                settings.setUpgradeOnMobileData(on)
                (context.applicationContext as CalmMusic).downloadQueue.nudge()
            },
        )
    }
}

/** Whether a YouTube account is signed in, for [onlineAccountRows]. */
@Composable
internal fun onlineAccountConnected(): Boolean =
    (LocalContext.current.applicationContext as CalmMusic).youTubeAccount.cookie.collectAsState().value != null

internal fun LazyListScope.onlineAccountRows(connected: Boolean, onConnectClick: () -> Unit) {
    item {
        ValueRow(
            label = stringResource(R.string.settings_youtube_account),
            value = if (connected) stringResource(R.string.settings_youtube_connected) else stringResource(R.string.settings_youtube_not_connected),
            onClick = if (connected) null else onConnectClick,
            enabled = !connected,
        )
    }

    if (connected) {
        item { Separator() }
        item {
            val account = (LocalContext.current.applicationContext as CalmMusic).youTubeAccount
            ArmedRow(
                label = stringResource(R.string.settings_disconnect_account),
                armedLabel = stringResource(R.string.settings_disconnect_account_armed),
                onConfirm = account::clear,
            )
        }
    }
}

/** The empty library's third way in: YouTube's settings; see [LibraryOnboardingEmptyState]. */
@Composable
internal fun OnlineSetUpButton(onClick: () -> Unit) {
    androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.ui.Modifier.height(8.dp))

    com.mudita.mmd.components.buttons.OutlinedButtonMMD(
        modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        onClick = onClick,
    ) {
        com.mudita.mmd.components.text.TextMMD(
            text = stringResource(R.string.library_empty_set_up_youtube),
            style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        )
    }
}
