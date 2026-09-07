package com.wanderwildwood.jimeikin

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.navigation.NavDestination.Companion.hierarchy
import com.wanderwildwood.jimeikin.ui.AlbumUiModel
import com.wanderwildwood.jimeikin.ui.PlaylistUiModel
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.nav_bar.NavigationBarItemMMD
import com.mudita.mmd.components.nav_bar.NavigationBarMMD
import com.mudita.mmd.components.search_bar.SearchBarDefaultsMMD
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.mudita.mmd.components.menus.DropdownMenuItemMMD
import com.mudita.mmd.components.menus.DropdownMenuMMD
import com.wanderwildwood.jimeikin.ui.DashedDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.graphics.vector.rememberVectorPainter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalmMusicTopAppBar(
    currentDestination: NavDestination?,
    canNavigateBack: Boolean,
    focusRequester: FocusRequester,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onPerformSearchClick: () -> Unit,
    selectedAlbum: AlbumUiModel?,
    selectedArtistName: String?,
    selectedPlaylist: PlaylistUiModel?,
    isPlaylistsEditMode: Boolean,
    isPlaylistDetailsEditMode: Boolean,
    playlistEditSelectionCount: Int,
    playlistDetailsSelectionCount: Int,
    isPlaylistDetailsMenuExpanded: Boolean,
    hasLibraryPlaylists: Boolean,
    hasNowPlaying: Boolean,
    onBackClick: () -> Unit,
    onCancelPlaylistsEditClick: () -> Unit,
    onCancelPlaylistDetailsEditClick: () -> Unit,
    onEnterPlaylistsEditClick: () -> Unit,
    onNavigateToSearchClick: () -> Unit,
    onPlaylistDetailsMenuToggle: () -> Unit,
    onPlaylistDetailsEditClick: () -> Unit,
    onPlaylistDetailsAddSongsClick: () -> Unit,
    onPlaylistDetailsRenameClick: () -> Unit,
    onPlaylistDetailsDeleteClick: () -> Unit,
    onShowDeletePlaylistSongsConfirmationClick: () -> Unit,
    onShowDeletePlaylistsConfirmationClick: () -> Unit,
    onPlaylistAddSongsDoneClick: () -> Unit,
    onNowPlayingClick: () -> Unit,
    onNavigateToSettingsClick: () -> Unit,
    onShowAboutClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val navRoutes = remember { navItems.map { it.route } }
    val keyboardController = LocalSoftwareKeyboardController.current

    TopAppBarMMD(
        navigationIcon = {
            when {
                currentDestination.isPlaylistDetails() && isPlaylistDetailsEditMode -> {
                    IconButton(onClick = onCancelPlaylistDetailsEditClick) {
                        Icon(
                            imageVector = Icons.Outlined.Clear,
                            contentDescription = "Cancel playlist edits",
                        )
                    }
                }

                currentDestination?.route == Screen.Playlists.route && isPlaylistsEditMode -> {
                    IconButton(onClick = onCancelPlaylistsEditClick) {
                        Icon(
                            imageVector = Icons.Outlined.Clear,
                            contentDescription = "Cancel playlist edit",
                        )
                    }
                }

                canNavigateBack && currentDestination?.route !in navRoutes -> {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                }
            }
        },
        title = {
            when {
                currentDestination?.route == Screen.Search.route -> {
                    SearchBarDefaultsMMD.InputField(
                        query = searchQuery,
                        onQueryChange = onSearchQueryChange,
                        onSearch = {
                            keyboardController?.hide()
                            onPerformSearchClick()
                        },
                        expanded = true,
                        onExpandedChange = { },
                        placeholder = { TextMMD("Search") },
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    keyboardController?.hide()
                                    onPerformSearchClick()
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Search,
                                    contentDescription = "Search",
                                )
                            }
                        },
                        modifier = Modifier
                            .focusRequester(focusRequester),
                    )
                }

                currentDestination?.route == Screen.AlbumDetails.route && selectedAlbum != null -> {
                    androidx.compose.foundation.layout.Column {
                        TextMMD(
                            text = selectedAlbum.title,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val artist = selectedAlbum.artist
                        if (!artist.isNullOrBlank()) {
                            TextMMD(
                                text = artist,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                (currentDestination?.route == Screen.ArtistDetails.route ||
                    currentDestination?.route == Screen.YoutubeArtistDetails.route) &&
                    selectedArtistName != null -> {
                    TextMMD(
                        text = selectedArtistName,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                currentDestination.isPlaylistDetails() && selectedPlaylist != null -> {
                    TextMMD(
                        text = selectedPlaylist.name,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                else -> {
                    TextMMD(
                        text = getAppBarTitle(currentDestination, isEditingPlaylist = selectedPlaylist != null),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
            }
        },
        actions = {
            CalmMusicTopAppBarActions(
                currentDestination = currentDestination,
                isPlaylistsEditMode = isPlaylistsEditMode,
                playlistEditSelectionCount = playlistEditSelectionCount,
                playlistDetailsSelectionCount = playlistDetailsSelectionCount,
                isPlaylistDetailsEditMode = isPlaylistDetailsEditMode,
                isPlaylistDetailsMenuExpanded = isPlaylistDetailsMenuExpanded,
                hasLibraryPlaylists = hasLibraryPlaylists,
                hasNowPlaying = hasNowPlaying,
                onEnterPlaylistsEditClick = onEnterPlaylistsEditClick,
                onNavigateToSearchClick = onNavigateToSearchClick,
                onPlaylistDetailsMenuToggle = onPlaylistDetailsMenuToggle,
                onPlaylistDetailsEditClick = onPlaylistDetailsEditClick,
                onPlaylistDetailsAddSongsClick = onPlaylistDetailsAddSongsClick,
                onPlaylistDetailsRenameClick = onPlaylistDetailsRenameClick,
                onPlaylistDetailsDeleteClick = onPlaylistDetailsDeleteClick,
                onShowDeletePlaylistSongsConfirmationClick = onShowDeletePlaylistSongsConfirmationClick,
                onShowDeletePlaylistsConfirmationClick = onShowDeletePlaylistsConfirmationClick,
                onPlaylistAddSongsDoneClick = onPlaylistAddSongsDoneClick,
                onNowPlayingClick = onNowPlayingClick,
                onNavigateToSettingsClick = onNavigateToSettingsClick,
                onShowAboutClick = onShowAboutClick,
            )
        },
        showDivider = false,
        modifier = modifier,
    )
}

@Composable
private fun CalmMusicTopAppBarActions(
    currentDestination: NavDestination?,
    isPlaylistsEditMode: Boolean,
    playlistEditSelectionCount: Int,
    playlistDetailsSelectionCount: Int,
    isPlaylistDetailsEditMode: Boolean,
    isPlaylistDetailsMenuExpanded: Boolean,
    hasLibraryPlaylists: Boolean,
    hasNowPlaying: Boolean,
    onEnterPlaylistsEditClick: () -> Unit,
    onNavigateToSearchClick: () -> Unit,
    onPlaylistDetailsMenuToggle: () -> Unit,
    onPlaylistDetailsEditClick: () -> Unit,
    onPlaylistDetailsAddSongsClick: () -> Unit,
    onPlaylistDetailsRenameClick: () -> Unit,
    onPlaylistDetailsDeleteClick: () -> Unit,
    onShowDeletePlaylistSongsConfirmationClick: () -> Unit,
    onShowDeletePlaylistsConfirmationClick: () -> Unit,
    onPlaylistAddSongsDoneClick: () -> Unit,
    onNowPlayingClick: () -> Unit,
    onNavigateToSettingsClick: () -> Unit,
    onShowAboutClick: () -> Unit,
) {
    val navRoutes = remember { navItems.map { it.route } }

    if (currentDestination?.route != Screen.Search.route && currentDestination?.route in navRoutes) {
        if (currentDestination?.route == Screen.Playlists.route && hasLibraryPlaylists && !isPlaylistsEditMode) {
            IconButton(onClick = onEnterPlaylistsEditClick) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = "Edit playlists",
                )
            }
        }

        IconButton(onClick = onNavigateToSearchClick) {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = "Search",
            )
        }

        // A cog, top right. Settings used to be two taps down inside a "More" tab, which
        // promised a menu of places and held one.
        IconButton(onClick = onNavigateToSettingsClick) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = "Settings",
            )
        }
    }

    // About is not a setting, but it is what a stranger looks for before trusting an app, and
    // Settings is where they go looking. The other apps here put the i in the same corner of
    // the same screen.
    if (currentDestination?.route == Screen.Settings.route) {
        IconButton(onClick = onShowAboutClick) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = "About",
            )
        }
    }

    if (currentDestination.isPlaylistDetails() && !isPlaylistDetailsEditMode) {
        androidx.compose.foundation.layout.Box {
            IconButton(onClick = onPlaylistDetailsMenuToggle) {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = "Playlist options",
                )
            }

            DropdownMenuMMD(
                expanded = isPlaylistDetailsMenuExpanded,
                onDismissRequest = onPlaylistDetailsMenuToggle,
            ) {
                DropdownMenuItemMMD(
                    text = { TextMMD("Edit") },
                    onClick = onPlaylistDetailsEditClick,
                )

                HorizontalDividerMMD(thickness = 1.dp)

                DropdownMenuItemMMD(
                    text = { TextMMD("Add songs") },
                    onClick = onPlaylistDetailsAddSongsClick,
                )

                HorizontalDividerMMD(thickness = 1.dp)

                DropdownMenuItemMMD(
                    text = { TextMMD("Rename") },
                    onClick = onPlaylistDetailsRenameClick,
                )

                HorizontalDividerMMD(thickness = 1.dp)

                DropdownMenuItemMMD(
                    text = { TextMMD("Delete") },
                    onClick = onPlaylistDetailsDeleteClick,
                )
            }
        }
    }

    if (
        currentDestination?.route == Screen.Playlists.route &&
        isPlaylistsEditMode &&
        playlistEditSelectionCount > 0
    ) {
        OutlinedButtonMMD(
            contentPadding = PaddingValues(8.dp),
            modifier = Modifier.padding(horizontal = 8.dp),
            onClick = onShowDeletePlaylistsConfirmationClick,
        ) {
            TextMMD(
                text = "Delete $playlistEditSelectionCount",
                textAlign = TextAlign.Center,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }

    if (currentDestination?.route == Screen.PlaylistAddSongs.route) {
        ButtonMMD(
            contentPadding = PaddingValues(8.dp),
            modifier = Modifier.padding(horizontal = 8.dp),
            onClick = onPlaylistAddSongsDoneClick,
        ) {
            TextMMD(
                text = "Done",
                textAlign = TextAlign.Center,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }

    if (
        hasNowPlaying &&
        currentDestination?.route != Screen.PlaylistAddSongs.route &&
        currentDestination?.route != Screen.Radio.route &&
        !(currentDestination?.route == Screen.Playlists.route && isPlaylistsEditMode) &&
        !(currentDestination.isPlaylistDetails() && isPlaylistDetailsEditMode)
    ) {
        ButtonMMD(
            onClick = onNowPlayingClick,
            contentPadding = PaddingValues(8.dp),
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            TextMMD(
                text = "Now playing",
                textAlign = TextAlign.Center,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
fun CalmMusicBottomBar(
    currentDestination: NavDestination?,
    onNavigate: (String) -> Unit,
) {
    val navRoutes = remember { navItems.map { it.route } }

    if (currentDestination?.route in navRoutes) {
        NavigationBarMMD(
            modifier = Modifier.padding(bottom = 2.dp),
        ) {
            navItems.forEach { screen ->
                val isSelected =
                    currentDestination?.hierarchy?.any { it.route == screen.route } == true
                NavigationBarItemMMD(
                    icon = {
                        Icon(
                            painter = rememberVectorPainter(image = screen.icon),
                            contentDescription = screen.label,
                        )
                    },
                    label = {
                        TextMMD(
                            text = screen.label,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    selected = isSelected,
                    onClick = { onNavigate(screen.route) },
                )
            }
        }
    }
}
