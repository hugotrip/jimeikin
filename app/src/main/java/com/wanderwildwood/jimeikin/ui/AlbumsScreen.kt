package com.wanderwildwood.jimeikin.ui

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.jimeikin.R

/** UI model for displaying albums in the library. */
data class AlbumUiModel(
    val id: String,
    val title: String,
    val artist: String?,
    val sourceType: String,
    /** Optional release year for display when available. */
    val releaseYear: Int? = null,
    /** Where it streams from, if not the phone. The library works this out from its songs. */
    val streamsFrom: StreamSource? = streamSourceOf(sourceType),
)

@Composable
fun AlbumsScreen(
    albums: List<AlbumUiModel>,
    isLoading: Boolean,
    errorMessage: String?,
    isSyncInProgress: Boolean,
    hasAnySongs: Boolean,
    onOpenStreamingSettingsClick: () -> Unit,
    onOpenLocalSettingsClick: () -> Unit,
    onAlbumClick: (AlbumUiModel) -> Unit = {},
) {
    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
        when {
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    TextMMD(text = stringResource(R.string.library_albums_loading))
                }
            }

            errorMessage != null -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    TextMMD(text = stringResource(R.string.library_albums_error))
                }
            }

            albums.isEmpty() && isSyncInProgress -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    TextMMD(text = stringResource(R.string.library_sync_in_progress))
                }
            }

            albums.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!hasAnySongs) {
                        LibraryOnboardingEmptyState(
                            title = stringResource(R.string.library_albums_empty_title),
                            body = stringResource(R.string.library_nothing_added_yet),
                            onOpenStreamingSettingsClick = onOpenStreamingSettingsClick,
                            onOpenLocalSettingsClick = onOpenLocalSettingsClick,
                        )
                    } else {
                        TextMMD(text = stringResource(R.string.library_albums_no_album_info))
                    }
                }
            }

            else -> {
                val lastAlbumId = albums.lastOrNull()?.id
                PagedColumnMMD(contentPadding = PaddingValues(16.dp)) {
                    items(
                        items = albums,
                        key = { it.id },
                    ) { album ->
                        val isLast = album.id == lastAlbumId
                        AlbumItem(
                            album = album,
                            onClick = { onAlbumClick(album) },
                            showDivider = !isLast,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AlbumItem(
    album: AlbumUiModel,
    onClick: () -> Unit,
    showDivider: Boolean = true,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(bottom = 8.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.Center,
        ) {
            TextMMD(
                text = album.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = when {
                !album.artist.isNullOrBlank() && album.releaseYear != null ->
                    stringResource(R.string.library_album_artist_and_year, album.artist, album.releaseYear)
                !album.artist.isNullOrBlank() -> album.artist
                album.releaseYear != null -> album.releaseYear.toString()
                else -> ""
            }
            if (subtitle.isNotBlank() || album.streamsFrom != null) {
                Spacer(modifier = Modifier.height(4.dp))
                SubtitleLine(text = subtitle, source = album.streamsFrom)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (showDivider) {
            HorizontalDividerMMD(thickness = 1.dp)
        }
    }
}
