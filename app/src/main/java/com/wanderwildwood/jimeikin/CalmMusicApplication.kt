package com.wanderwildwood.jimeikin

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.wanderwildwood.jimeikin.data.CalmMusicSettingsManager
import com.wanderwildwood.jimeikin.data.NowPlayingStorage
import com.wanderwildwood.jimeikin.data.PlaybackStateManager
import okhttp3.OkHttpClient
import java.io.File

@UnstableApi
class CalmMusic : Application() {

    val mediaCache: SimpleCache by lazy {
        val cacheDirectory = File(this.cacheDir, "media_cache")
        val evictor = LeastRecentlyUsedCacheEvictor(256L * 1024L * 1024L) // 256 MB
        SimpleCache(cacheDirectory, evictor)
    }

    val cacheDataSourceFactory: CacheDataSource.Factory by lazy {
        val upstream = DefaultDataSource.Factory(this)
        CacheDataSource.Factory()
            .setCache(mediaCache)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    val youTubeSearchClient: YouTubeMusicSearchClient by lazy {
        YouTubeMusicSearchClientImpl.create()
    }

    val youTubeInnertubeClient: YouTubeMusicInnertubeClient by lazy {
        val client = OkHttpClient.Builder().build()
        YouTubeMusicInnertubeClientImpl(client) { settingsManager.youtubeAccountCookie.value }
    }

    val youTubeStreamResolver: YouTubeStreamResolver by lazy {
        YouTubeStreamResolver()
    }

    val youTubePrecacheManager: YouTubePrecacheManager by lazy {
        YouTubePrecacheManager(this)
    }

    val playbackStateManager: PlaybackStateManager by lazy {
        PlaybackStateManager()
    }

    val nowPlayingStorage: NowPlayingStorage by lazy {
        NowPlayingStorage(this)
    }

    lateinit var settingsManager: CalmMusicSettingsManager
        private set

    lateinit var youTubeDownloadManager: YouTubeDownloadManager
        private set

    override fun onCreate() {
        super<Application>.onCreate()

        settingsManager = CalmMusicSettingsManager(this)
        youTubeDownloadManager = YouTubeDownloadManager(
            app = this,
            appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO),
        )
    }
}
