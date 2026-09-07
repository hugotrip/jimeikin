package com.wanderwildwood.jimeikin.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CalmMusicSettingsManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _includeLocalMusic = MutableStateFlow(getIncludeLocalMusicSync())
    val includeLocalMusic: StateFlow<Boolean> = _includeLocalMusic.asStateFlow()

    private val _localMusicFolders = MutableStateFlow(getLocalMusicFoldersSync())
    val localMusicFolders: StateFlow<Set<String>> = _localMusicFolders.asStateFlow()

    private val _streamingProvider = MutableStateFlow(getStreamingProviderSync())
    val streamingProvider: StateFlow<StreamingProvider> = _streamingProvider.asStateFlow()

    private val _completeAlbumsWithYouTube = MutableStateFlow(getCompleteAlbumsWithYouTubeSync())
    val completeAlbumsWithYouTube: StateFlow<Boolean> = _completeAlbumsWithYouTube.asStateFlow()

    private val _youtubeAccountCookie = MutableStateFlow(getYouTubeAccountCookieSync())
    val youtubeAccountCookie: StateFlow<String?> = _youtubeAccountCookie.asStateFlow()

    fun getLastLocalLibraryScanMillis(): Long {
        return prefs.getLong(KEY_LAST_LOCAL_LIBRARY_SCAN_MILLIS, 0L)
    }

    fun updateLastLocalLibraryScanMillis(value: Long) {
        prefs.edit { putLong(KEY_LAST_LOCAL_LIBRARY_SCAN_MILLIS, value) }
    }

    fun setIncludeLocalMusic(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_INCLUDE_LOCAL_MUSIC, enabled) }
        _includeLocalMusic.value = enabled
    }

    private fun getIncludeLocalMusicSync(): Boolean {
        return prefs.getBoolean(KEY_INCLUDE_LOCAL_MUSIC, false)
    }

    fun addLocalMusicFolder(uri: String) {
        val current = getLocalMusicFoldersSync().toMutableSet()
        if (current.add(uri)) {
            prefs.edit { putStringSet(KEY_LOCAL_MUSIC_FOLDERS, current) }
            _localMusicFolders.value = current
        }
    }

    fun removeLocalMusicFolder(uri: String) {
        val current = getLocalMusicFoldersSync().toMutableSet()
        if (current.remove(uri)) {
            prefs.edit { putStringSet(KEY_LOCAL_MUSIC_FOLDERS, current) }
            _localMusicFolders.value = current
        }
    }

    fun getLocalMusicFoldersSync(): Set<String> {
        return prefs.getStringSet(KEY_LOCAL_MUSIC_FOLDERS, emptySet()) ?: emptySet()
    }

    private fun getStreamingProviderSync(): StreamingProvider {
        val raw = prefs.getString(KEY_STREAMING_PROVIDER, null)
        return StreamingProvider.fromStored(raw)
    }

    fun setStreamingProvider(provider: StreamingProvider) {
        prefs.edit { putString(KEY_STREAMING_PROVIDER, StreamingProvider.toStored(provider)) }
        _streamingProvider.value = provider
    }

    fun getCompleteAlbumsWithYouTubeSync(): Boolean {
        return prefs.getBoolean(KEY_COMPLETE_ALBUMS_WITH_YOUTUBE, false)
    }

    fun setCompleteAlbumsWithYouTube(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_COMPLETE_ALBUMS_WITH_YOUTUBE, enabled) }
        _completeAlbumsWithYouTube.value = enabled
    }

    private fun getYouTubeAccountCookieSync(): String? {
        return prefs.getString(KEY_YOUTUBE_ACCOUNT_COOKIE, null)
    }

    fun setYouTubeAccountCookie(cookie: String) {
        prefs.edit { putString(KEY_YOUTUBE_ACCOUNT_COOKIE, cookie) }
        _youtubeAccountCookie.value = cookie
    }

    fun clearYouTubeAccountCookie() {
        prefs.edit { remove(KEY_YOUTUBE_ACCOUNT_COOKIE) }
        _youtubeAccountCookie.value = null
    }

    companion object {
        private const val PREFS_NAME = "calmmusic_settings"
        private const val KEY_INCLUDE_LOCAL_MUSIC = "include_local_music"
        private const val KEY_LOCAL_MUSIC_FOLDERS = "local_music_folders"
        private const val KEY_LAST_LOCAL_LIBRARY_SCAN_MILLIS = "last_local_library_scan_millis"
        private const val KEY_STREAMING_PROVIDER = "streaming_provider"
        private const val KEY_COMPLETE_ALBUMS_WITH_YOUTUBE = "complete_albums_with_youtube"
        private const val KEY_YOUTUBE_ACCOUNT_COOKIE = "youtube_account_cookie"
    }
}