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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.jimeikin.R

/** Simple UI model for distinct artists in the library. */
data class ArtistUiModel(
    val id: String,
    val name: String,
    val songCount: Int,
    val albumCount: Int,
    /** Where their songs stream from, if any of them are not on the phone. */
    val origin: Origin? = null,
)

@Composable
fun ArtistsScreen(
    artists: List<ArtistUiModel>,
    isLoading: Boolean,
    errorMessage: String?,
    isSyncInProgress: Boolean,
    hasAnySongs: Boolean,
    onOpenStreamingSettingsClick: () -> Unit,
    onOpenLocalSettingsClick: () -> Unit,
    onArtistClick: (ArtistUiModel) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when {
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    TextMMD(text = stringResource(R.string.library_artists_loading))
                }
            }

            errorMessage != null -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    TextMMD(text = stringResource(R.string.library_artists_error))
                }
            }

            artists.isEmpty() && isSyncInProgress -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    TextMMD(text = stringResource(R.string.library_sync_in_progress))
                }
            }

            artists.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!hasAnySongs) {
                        LibraryOnboardingEmptyState(
                            title = stringResource(R.string.library_artists_empty_title),
                            body = stringResource(R.string.library_nothing_added_yet),
                            onOpenStreamingSettingsClick = onOpenStreamingSettingsClick,
                            onOpenLocalSettingsClick = onOpenLocalSettingsClick,
                        )
                    } else {
                        TextMMD(text = stringResource(R.string.library_artists_no_artist_info))
                    }
                }
            }

            else -> {
                val lastArtistId = artists.lastOrNull()?.id
                PagedColumnMMD(contentPadding = PaddingValues(16.dp)) {
                    items(
                        items = artists,
                        key = { it.id },
                    ) { artist ->
                        val isLast = artist.id == lastArtistId
                        ArtistItem(
                            artist = artist,
                            onClick = { onArtistClick(artist) },
                            showDivider = !isLast,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ArtistItem(
    artist: ArtistUiModel,
    onClick: () -> Unit,
    showDivider: Boolean = true,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(bottom = 8.dp),
    ) {
        TextMMD(
            text = artist.name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        val songLabel = pluralStringResource(R.plurals.library_artist_song_count, artist.songCount, artist.songCount)
        val albumLabel = pluralStringResource(R.plurals.library_artist_album_count, artist.albumCount, artist.albumCount)
        val subtitle = stringResource(R.string.library_artist_songs_and_albums, songLabel, albumLabel)

        Spacer(modifier = Modifier.height(4.dp))
        SubtitleLine(text = subtitle, origin = artist.origin)

        Spacer(modifier = Modifier.height(12.dp))

        if (showDivider) {
            HorizontalDividerMMD(thickness = 1.dp)
        }
    }
}