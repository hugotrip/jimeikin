package com.wanderwildwood.jimeikin.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.jimeikin.data.RadioChannel
import com.wanderwildwood.jimeikin.data.RadioGarden
import com.wanderwildwood.jimeikin.data.RadioPlace
import com.wanderwildwood.jimeikin.data.RadioStationEntity
import kotlinx.coroutines.launch

/**
 * Two radios, which are not the same thing.
 *
 * **FM** is the phone's own tuner, and this screen does one thing with it: opens it. It used to
 * drive it from here - press play through an accessibility service, read the frequency out of a
 * notification, offer up and down - and every part of that was a worse version of the tuner the
 * phone already has. The tuner shows the frequency, holds the presets, and scans; a remote
 * control for it that could not show what it was tuned to was not worth the two permissions it
 * cost. So the row opens the tuner and gets out of the way.
 *
 * **Stations** are on the internet, and they are what a phone with a network can do that a
 * tuner cannot: hear somewhere else. They come from radio.garden, browsed the way that site is
 * worth browsing - by place - and kept by name once found.
 */
@Composable
fun RadioScreen(
    keptStations: List<RadioStationEntity>,
    onPlayStation: (RadioStationEntity) -> Unit,
    onKeepStation: (RadioChannel) -> Unit,
    onForgetStation: (RadioStationEntity) -> Unit,
    onPausePlayback: () -> Unit,
    isAppPlaying: Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Asked once. On a phone with no tuner the row is simply not there, rather than being there
    // and doing nothing.
    val tunerPackage = remember { findRadioPackage(context) }

    var browsingCountry by remember { mutableStateOf<String?>(null) }
    var browsingPlace by remember { mutableStateOf<RadioPlace?>(null) }
    var countries by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    var placesHere by remember { mutableStateOf<List<RadioPlace>>(emptyList()) }
    var channelsHere by remember { mutableStateOf<List<RadioChannel>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var browsing by remember { mutableStateOf(false) }

    BackHandler(enabled = browsing) {
        when {
            browsingPlace != null -> browsingPlace = null
            browsingCountry != null -> browsingCountry = null
            else -> browsing = false
        }
    }

    LaunchedEffect(browsing) {
        if (browsing && countries.isEmpty()) {
            loading = true
            countries = RadioGarden.countries()
            loading = false
        }
    }

    LaunchedEffect(browsingCountry) {
        val country = browsingCountry
        if (country != null) {
            loading = true
            placesHere = RadioGarden.placesIn(country)
            loading = false
        }
    }

    LaunchedEffect(browsingPlace) {
        val place = browsingPlace
        if (place != null) {
            loading = true
            channelsHere = RadioGarden.channelsIn(place)
            loading = false
        }
    }

    if (loading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            TextMMD("Reading the list…", fontSize = 16.sp)
        }
        return
    }

    when {
        browsingPlace != null -> ChannelList(
            place = browsingPlace!!,
            channels = channelsHere,
            keptIds = keptStations.map { it.id }.toSet(),
            onKeep = onKeepStation,
        )

        browsingCountry != null -> RowList(
            heading = browsingCountry!!,
            rows = placesHere.map { place ->
                Row3(place.title, plural(place.stationCount)) { browsingPlace = place }
            },
        )

        browsing -> RowList(
            heading = "Everywhere",
            rows = countries.map { (country, stations) ->
                Row3(country, plural(stations)) { browsingCountry = country }
            },
        )

        else -> RadioHome(
            keptStations = keptStations,
            hasTuner = tunerPackage != null,
            onPlayStation = { station ->
                if (isAppPlaying) onPausePlayback()
                onPlayStation(station)
            },
            onForgetStation = onForgetStation,
            onBrowse = { browsing = true },
            onOpenTuner = {
                tunerPackage?.let { pkg ->
                    scope.launch {
                        if (isAppPlaying) onPausePlayback()
                        launchSystemRadioApp(context, pkg)
                    }
                }
            },
        )
    }
}

private fun plural(count: Int) = if (count == 1) "1 station" else "$count stations"

private data class Row3(val title: String, val subtitle: String?, val onClick: () -> Unit)

@Composable
private fun RowList(heading: String, rows: List<Row3>) {
    if (rows.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            TextMMD("Nothing here", fontSize = 16.sp)
        }
        return
    }
    PagedColumnMMD(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        item {
            TextMMD(heading, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
        }
        items(rows.size) { index ->
            val row = rows[index]
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { row.onClick() }
                    .padding(vertical = 10.dp),
            ) {
                TextMMD(row.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                if (row.subtitle != null) {
                    TextMMD(row.subtitle, fontSize = 14.sp, maxLines = 1)
                }
            }
            if (index != rows.lastIndex) HorizontalDividerMMD(thickness = 1.dp)
        }
    }
}

@Composable
private fun ChannelList(
    place: RadioPlace,
    channels: List<RadioChannel>,
    keptIds: Set<String>,
    onKeep: (RadioChannel) -> Unit,
) {
    if (channels.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            TextMMD("No stations here", fontSize = 16.sp)
        }
        return
    }
    PagedColumnMMD(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        item {
            TextMMD(place.title, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
        }
        items(channels.size) { index ->
            val channel = channels[index]
            val alreadyKept = channel.id in keptIds
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !alreadyKept) { onKeep(channel) }
                    .padding(vertical = 10.dp),
            ) {
                TextMMD(channel.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                TextMMD(
                    text = if (alreadyKept) "Kept" else "Tap to keep",
                    fontSize = 14.sp,
                )
            }
            if (index != channels.lastIndex) HorizontalDividerMMD(thickness = 1.dp)
        }
    }
}

@Composable
private fun RadioHome(
    keptStations: List<RadioStationEntity>,
    hasTuner: Boolean,
    onPlayStation: (RadioStationEntity) -> Unit,
    onForgetStation: (RadioStationEntity) -> Unit,
    onBrowse: () -> Unit,
    onOpenTuner: () -> Unit,
) {
    PagedColumnMMD(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (keptStations.isNotEmpty()) {
            items(keptStations.size) { index ->
                val station = keptStations[index]
                var armedForget by remember(station.id) { mutableStateOf(false) }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (armedForget) armedForget = false else onPlayStation(station)
                        }
                        .padding(vertical = 10.dp),
                ) {
                    TextMMD(station.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                    TextMMD("${station.place} • ${station.country}", fontSize = 14.sp, maxLines = 1)
                    Spacer(Modifier.height(4.dp))
                    TextMMD(
                        text = if (armedForget) "Tap again to forget it" else "Forget",
                        fontSize = 14.sp,
                        fontWeight = if (armedForget) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.clickable {
                            if (armedForget) onForgetStation(station) else armedForget = true
                        },
                    )
                }
                HorizontalDividerMMD(thickness = 1.dp)
            }
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onBrowse() }
                    .padding(vertical = 10.dp),
            ) {
                TextMMD("Stations by place", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                TextMMD("Somewhere else, on the air now", fontSize = 14.sp)
            }
        }

        if (hasTuner) {
            item { HorizontalDividerMMD(thickness = 1.dp) }
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenTuner() }
                        .padding(vertical = 10.dp),
                ) {
                    TextMMD("FM radio", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    TextMMD("Opens the phone's tuner. Needs headphones.", fontSize = 14.sp)
                }
            }
        }

        if (keptStations.isEmpty()) {
            item {
                Spacer(Modifier.height(32.dp))
                TextMMD(
                    text = "Stations you keep appear at the top of this screen.",
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun findRadioPackage(context: Context): String? {
    val candidates = listOf("com.android.fmradio", "com.mediatek.fmradio", "com.caf.fmradio")
    return candidates.firstOrNull {
        context.packageManager.getLaunchIntentForPackage(it) != null
    }
}

private fun launchSystemRadioApp(context: Context, packageName: String) {
    try {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent != null) context.startActivity(intent)
    } catch (e: Exception) {
        android.util.Log.w("RadioScreen", "could not open the tuner", e)
    }
}
