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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.wanderwildwood.jimeikin.data.ArtistNames
import com.wanderwildwood.jimeikin.data.RadioChannel
import com.wanderwildwood.jimeikin.data.RadioGarden
import com.wanderwildwood.jimeikin.data.RadioPlace
import com.wanderwildwood.jimeikin.data.RadioSearchResults
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
    searchOpen: Boolean,
    onSearchOpenChange: (Boolean) -> Unit,
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
    var allPlaces by remember { mutableStateOf<List<RadioPlace>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var browsing by remember { mutableStateOf(false) }

    // One box per level, cleared on the way in. Two hundred and twenty-seven countries and, in
    // France alone, over a thousand towns: a list that can only be paged through is a list you
    // give up on.
    var query by remember { mutableStateOf("") }

    // What the magnifying glass in the top bar asks radio.garden. Unlike the filter on a list,
    // which narrows what is already on screen, this reaches the two things a list cannot hold:
    // every station's name, and a town whose country you would otherwise have to think of
    // first. A postcode is answered differently again - see [RadioGarden.nearest].
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf(RadioSearchResults(emptyList(), emptyList())) }
    var nearbyPlaces by remember { mutableStateOf<List<RadioPlace>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchedFor by remember { mutableStateOf("") }

    BackHandler(enabled = browsing || searchOpen) {
        when {
            browsingPlace != null -> browsingPlace = null
            browsingCountry != null && !searchOpen -> browsingCountry = null
            searchOpen -> onSearchOpenChange(false)
            else -> browsing = false
        }
    }

    LaunchedEffect(searchOpen) {
        if (searchOpen) {
            searchQuery = ""
            searchedFor = ""
            searchResults = RadioSearchResults(emptyList(), emptyList())
            nearbyPlaces = emptyList()
        }
    }

    LaunchedEffect(browsing) {
        if (browsing && countries.isEmpty()) {
            loading = true
            countries = RadioGarden.countries()
            allPlaces = RadioGarden.places()
            loading = false
        }
        if (browsing) query = ""

    }

    LaunchedEffect(browsingCountry) {
        val country = browsingCountry
        query = ""
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

    if (searchOpen && browsingPlace == null) {
        RadioSearch(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            onSubmit = {
                val asked = searchQuery.trim()
                if (asked.length >= 2) {
                    scope.launch {
                        searching = true
                        searchedFor = asked
                        nearbyPlaces = RadioGarden.locatePostcode(asked)
                            ?.let { (lat, lon) -> RadioGarden.nearest(lat, lon) }
                            ?: emptyList()
                        searchResults = if (nearbyPlaces.isEmpty()) {
                            RadioGarden.search(asked)
                        } else {
                            RadioSearchResults(emptyList(), emptyList())
                        }
                        searching = false
                    }
                }
            },
            searching = searching,
            searchedFor = searchedFor,
            results = searchResults,
            nearby = nearbyPlaces,
            keptIds = keptStations.map { it.id }.toSet(),
            onKeep = onKeepStation,
            onOpenPlace = { place -> browsingPlace = place },
        )
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
            query = query,
            onQueryChange = { query = it },
            hint = "Find a town",
            rows = placesHere
                .filter { it.title.matches(query) }
                .map { place ->
                    Row3(place.title, plural(place.stationCount)) { browsingPlace = place }
                },
        )

        browsing -> {
            val matchingCountries = countries.filter { it.first.matches(query) }
            // Typing a town's name on the country list finds the town. Nobody looking for
            // Reykjavík wants to be told to think of Iceland first.
            val matchingTowns = if (query.trim().length < 2) emptyList() else {
                allPlaces
                    .filter { it.stationCount > 0 && it.title.matches(query) }
                    .sortedByDescending { it.stationCount }
                    .take(40)
            }
            RowList(
                heading = "Everywhere",
                query = query,
                onQueryChange = { query = it },
                hint = "Find a country or a town",
                rows = matchingCountries.map { (country, stations) ->
                    Row3(country, plural(stations)) { browsingCountry = country }
                } + matchingTowns.map { place ->
                    Row3(place.title, place.country + " • " + plural(place.stationCount)) {
                        browsingPlace = place
                    }
                },
            )
        }

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

/** Case and accents ignored, so "reykjavik" finds Reykjavík and "cote" finds Côte d'Ivoire. */
private fun String.matches(query: String): Boolean {
    val needle = query.trim()
    if (needle.isEmpty()) return true
    return ArtistNames.key(this).contains(ArtistNames.key(needle))
}

@Composable
private fun RowList(
    heading: String,
    query: String,
    onQueryChange: (String) -> Unit,
    hint: String,
    rows: List<Row3>,
) {
    PagedColumnMMD(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        item {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = query,
                onValueChange = onQueryChange,
                label = { TextMMD(text = hint) },
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            TextMMD(heading, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
        }
        if (rows.isEmpty()) {
            item {
                Spacer(Modifier.height(24.dp))
                TextMMD(
                    text = "Nothing by that name",
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
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

/**
 * The search behind the magnifying glass: station names, towns, and postcodes.
 *
 * Three questions with one box, because they are the same question asked three ways. A name is
 * put to radio.garden's own index, which knows every station's title and every town's; a
 * postcode is turned into a point and answered with the towns nearest it, which is what
 * somebody typing a postcode means and what no amount of name matching would give them.
 */
@Composable
private fun RadioSearch(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    searching: Boolean,
    searchedFor: String,
    results: RadioSearchResults,
    nearby: List<RadioPlace>,
    keptIds: Set<String>,
    onKeep: (RadioChannel) -> Unit,
    onOpenPlace: (RadioPlace) -> Unit,
) {
    PagedColumnMMD(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        item {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = query,
                onValueChange = onQueryChange,
                label = { TextMMD(text = "Station, town or postcode") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            )
            Spacer(Modifier.height(12.dp))
        }

        if (searching) {
            item {
                TextMMD("Looking…", fontSize = 16.sp)
            }
            return@PagedColumnMMD
        }

        if (nearby.isNotEmpty()) {
            item {
                TextMMD(
                    text = "Near $searchedFor",
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            items(nearby.size) { index ->
                val place = nearby[index]
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenPlace(place) }
                        .padding(vertical = 10.dp),
                ) {
                    TextMMD(place.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    TextMMD(
                        text = place.country + " • " + plural(place.stationCount),
                        fontSize = 14.sp,
                        maxLines = 1,
                    )
                }
                if (index != nearby.lastIndex) HorizontalDividerMMD(thickness = 1.dp)
            }
            return@PagedColumnMMD
        }

        if (results.channels.isNotEmpty()) {
            item {
                TextMMD("Stations", fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
            }
            items(results.channels.size) { index ->
                val channel = results.channels[index]
                val alreadyKept = channel.id in keptIds
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !alreadyKept) { onKeep(channel) }
                        .padding(vertical = 10.dp),
                ) {
                    TextMMD(channel.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                    TextMMD(
                        text = listOf(channel.place, channel.country)
                            .filter { it.isNotBlank() }
                            .joinToString(" • ")
                            .ifBlank { "Somewhere" },
                        fontSize = 14.sp,
                        maxLines = 1,
                    )
                    TextMMD(if (alreadyKept) "Kept" else "Tap to keep", fontSize = 14.sp)
                }
                HorizontalDividerMMD(thickness = 1.dp)
            }
        }

        if (results.places.isNotEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                TextMMD("Places", fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
            }
            items(results.places.size) { index ->
                val place = results.places[index]
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenPlace(place) }
                        .padding(vertical = 10.dp),
                ) {
                    TextMMD(place.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    TextMMD(place.country, fontSize = 14.sp, maxLines = 1)
                }
                if (index != results.places.lastIndex) HorizontalDividerMMD(thickness = 1.dp)
            }
        }

        if (searchedFor.isNotBlank() && results.channels.isEmpty() && results.places.isEmpty()) {
            item {
                Spacer(Modifier.height(24.dp))
                TextMMD(
                    text = "Nothing for \"$searchedFor\"",
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
