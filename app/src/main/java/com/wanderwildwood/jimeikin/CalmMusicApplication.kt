package com.wanderwildwood.jimeikin

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.wanderwildwood.jimeikin.data.CalmMusicSettingsManager
import com.wanderwildwood.jimeikin.data.NowPlayingStorage
import com.wanderwildwood.jimeikin.data.PlaybackStateManager
import java.io.File

@OptIn(UnstableApi::class)
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

    /** Songs from the online catalogue, where this edition has one; see [OnlineMusic]. */
    val online: OnlineMusic by lazy { createOnlineMusic(this) }

    val playbackStateManager: PlaybackStateManager by lazy {
        PlaybackStateManager()
    }

    val nowPlayingStorage: NowPlayingStorage by lazy {
        NowPlayingStorage(this)
    }

    /** Names the reader gave songs, albums and artists, laid over the library as it is read. */
    val nameOverrides: com.wanderwildwood.jimeikin.data.NameOverrides by lazy {
        com.wanderwildwood.jimeikin.data.NameOverrides(
            dao = { com.wanderwildwood.jimeikin.data.CalmMusicDatabase.getDatabase(this).nameOverrideDao() },
            recorded = { online.recorded.value },
        )
    }

    /** The songs the reader has hearted. */
    val hearts: com.wanderwildwood.jimeikin.data.Hearts by lazy {
        com.wanderwildwood.jimeikin.data.Hearts(
            dao = { com.wanderwildwood.jimeikin.data.CalmMusicDatabase.getDatabase(this).heartedSongDao() },
            recorded = { online.recorded.value },
        )
    }

    lateinit var settingsManager: CalmMusicSettingsManager
        private set

    /** Every download, in one list. */
    lateinit var downloadQueue: DownloadQueue
        private set

    override fun onCreate() {
        super<Application>.onCreate()

        settingsManager = CalmMusicSettingsManager(this)
        downloadQueue = DownloadQueue(this)
    }
}
