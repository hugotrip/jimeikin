package com.wanderwildwood.jimeikin

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination
import com.wanderwildwood.jimeikin.ui.Icons

sealed class Screen(val route: String, @StringRes val labelRes: Int, val icon: ImageVector) {
    object Playlists : Screen("playlists", R.string.main_title_playlists, Icons.LibraryMusic)
    object PlaylistDetails : Screen("playlistDetails", R.string.main_title_playlist, Icons.LibraryMusic)
    object PlaylistAddSongs : Screen("playlistAddSongs", R.string.main_title_add_songs, Icons.LibraryMusic)
    object PlaylistEdit : Screen("playlistEdit", R.string.main_title_playlist, Icons.LibraryMusic)
    object Artists : Screen("artists", R.string.main_title_artists, Icons.Person)
    object Songs : Screen("songs", R.string.main_title_songs, Icons.QueueMusic)
    object Albums : Screen("albums", R.string.main_title_albums, Icons.Album)
    object AlbumDetails : Screen("albumDetails", R.string.main_title_album, Icons.Album)
    object ArtistDetails : Screen("artistDetails", R.string.main_title_artist, Icons.LibraryMusic)
    object YoutubeArtistDetails : Screen("youtubeArtistDetails", R.string.main_title_artist, Icons.LibraryMusic)
    object Search : Screen("search", R.string.main_title_search, Icons.Search)

    object Radio : Screen("radio", R.string.main_title_radio, Icons.Radio) // Add this line
    object Downloads : Screen("downloads", R.string.main_title_downloads, Icons.Download)
    object Settings : Screen("settings", R.string.main_title_settings, Icons.Settings)
    object MusicServer : Screen("musicServer", R.string.main_title_music_server, Icons.Settings)
    object YouTubeLogin : Screen("youtubeLogin", R.string.main_title_connect_youtube, Icons.Person)
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
