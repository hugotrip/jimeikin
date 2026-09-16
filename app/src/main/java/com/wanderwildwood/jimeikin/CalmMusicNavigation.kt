package com.wanderwildwood.jimeikin

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination
import com.wanderwildwood.jimeikin.ui.Icons

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Playlists : Screen("playlists", "Playlists", Icons.LibraryMusic)
    object PlaylistDetails : Screen("playlistDetails", "Playlist", Icons.LibraryMusic)
    object PlaylistAddSongs : Screen("playlistAddSongs", "Add songs", Icons.LibraryMusic)
    object PlaylistEdit : Screen("playlistEdit", "Playlist", Icons.LibraryMusic)
    object Artists : Screen("artists", "Artists", Icons.Person)
    object Songs : Screen("songs", "Songs", Icons.QueueMusic)
    object Albums : Screen("albums", "Albums", Icons.Album)
    object AlbumDetails : Screen("albumDetails", "Album", Icons.Album)
    object ArtistDetails : Screen("artistDetails", "Artist", Icons.LibraryMusic)
    object YoutubeArtistDetails : Screen("youtubeArtistDetails", "Artist", Icons.LibraryMusic)
    object Search : Screen("search", "Search", Icons.Search)

    object Radio : Screen("radio", "Radio", Icons.Radio) // Add this line
    object Downloads : Screen("downloads", "Downloads", Icons.Download)
    object Settings : Screen("settings", "Settings", Icons.Settings)
    object MusicServer : Screen("musicServer", "Music server", Icons.Settings)
    object YouTubeLogin : Screen("youtubeLogin", "Connect a YouTube account", Icons.Person)
}

/**
 * Five places, no menu. The fifth slot used to open a screen holding three doors — a
 * hamburger by another name, and the style keeps that shape for a menu of things to do
 * rather than a menu of places. Settings went to a cog and Downloads went into it, which
 * left Radio, and Radio is a place.
 */
val navItems = listOf(
    Screen.Playlists,
    Screen.Artists,
    Screen.Songs,
    Screen.Albums,
    Screen.Radio,
)

/**
 * Playlist details is registered with its id in the route ("playlistDetails/{playlistId}"),
 * so comparing a destination to the bare route never matches. Four places were spelling this
 * test out and three of them got it wrong, which is why the screen had no title and no menu.
 */
fun NavDestination?.isPlaylistDetails(): Boolean =
    this?.route == Screen.PlaylistDetails.route ||
            this?.route?.startsWith(Screen.PlaylistDetails.route + "/") == true
