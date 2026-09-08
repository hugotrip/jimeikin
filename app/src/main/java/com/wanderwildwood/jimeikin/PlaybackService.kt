package com.wanderwildwood.jimeikin

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.core.graphics.createBitmap
import android.util.Log
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Media3-based playback service.
 */
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "calmmusic_playback_channel"
        private var errorCallback: ((PlaybackException) -> Unit)? = null

        private const val NEWPIPE_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

        private const val BYPASS_COOKIES = "SOCS=CAI; VISITOR_INFO1_LIVE=i7Sm6Qgj0lE; CONSENT=YES+cb.20210328-17-p0.en+FX+475"

        fun registerErrorCallback(callback: ((PlaybackException) -> Unit)?) {
            errorCallback = callback
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                30_000,
                120_000,
                500,
                1000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val mediaSourceFactory = DefaultMediaSourceFactory(createDataSourceFactory())

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()

        val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        player.setAudioAttributes(audioAttributes, true)

        player.setHandleAudioBecomingNoisy(true)

        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                errorCallback?.invoke(error)
                super.onPlayerError(error)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                super.onIsPlayingChanged(isPlaying)
                (application as? CalmMusic)?.playbackStateManager?.updatePlaybackStatus(isPlaying)
            }

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                super.onMediaItemTransition(mediaItem, reason)
                val meta = mediaItem?.mediaMetadata
                if (meta != null) {
                    val uri = mediaItem.localConfiguration?.uri
                    val inferredSourceType = when (uri?.scheme) {
                        "content", "file" -> "LOCAL_FILE"
                        else -> "YOUTUBE"
                    }

                    (application as? CalmMusic)?.playbackStateManager?.updateState(
                        songId = mediaItem.mediaId,
                        title = meta.title?.toString() ?: "Unknown Title",
                        artist = meta.artist?.toString() ?: "Unknown Artist",
                        isPlaying = player.isPlaying,
                        sourceType = inferredSourceType,
                    )
                }
            }
        })

        val sessionActivityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            sessionActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // What the session publishes, which is what a launcher's now-playing widget draws
        // from. A track with a cover keeps its cover; a track without one is given the app's
        // own mark rather than nothing, so the widget always has a picture to draw. inkOS
        // draws a bitmap as a rectangle and falls back to a glyph in a pill when there is
        // none, and most music here carries no embedded cover at all.
        val sessionPlayer = object : ForwardingPlayer(player) {
            override fun getMediaMetadata(): MediaMetadata {
                val metadata = super.getMediaMetadata()
                if (metadata.artworkData != null || metadata.artworkUri != null) return metadata
                val fallback = appIconArtwork() ?: return metadata
                return metadata.buildUpon()
                    .setArtworkData(fallback, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                    .build()
            }
        }

        mediaSession = MediaSession.Builder(this, sessionPlayer)
            .setSessionActivity(sessionActivityPendingIntent)
            .build()

        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(CHANNEL_ID)
            .setNotificationId(NOTIFICATION_ID)
            .build()

        setMediaNotificationProvider(notificationProvider)
    }

    @OptIn(UnstableApi::class)
    private fun createDataSourceFactory(): DataSource.Factory {
        val app = application as CalmMusic

        val okHttpClient = OkHttpClient.Builder()
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        val upstreamFactory = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent(NEWPIPE_USER_AGENT)
            .setDefaultRequestProperties(mapOf(
                "Cookie" to BYPASS_COOKIES,
                "Referer" to "https://www.youtube.com/"
            ))

        val resolvingFactory = ResolvingDataSource.Factory(upstreamFactory) { dataSpec ->
            val uri = dataSpec.uri
            val scheme = uri.scheme
            if (scheme == "content" || scheme == "file") {
                return@Factory dataSpec
            }

            // Everything below resolves a YouTube video id into a playable stream. Only a
            // YouTube song should go through it: its uri is a bare video id with no scheme
            // at all. Anything that arrives already addressed — a music server on the local
            // network, most obviously — is played as given.
            //
            // This used to pass through only file and content, so a Subsonic stream url was
            // handed to the extractor as though the whole url were a video id, and playback
            // failed with "No service can handle the url".
            val host = uri.host.orEmpty()
            val isYouTube = host.endsWith("youtube.com") ||
                host.endsWith("youtu.be") ||
                host.endsWith("googlevideo.com")
            if (scheme != null && !isYouTube) {
                return@Factory dataSpec
            }

            val videoId = dataSpec.key
                ?: uri.getQueryParameter("v")
                ?: uri.lastPathSegment
                ?: return@Factory dataSpec

            val precache = app.youTubePrecacheManager
            val now = System.currentTimeMillis()
            val cached = precache.getCachedWithLabel(videoId, now)

            if (cached != null) {
                val (cachedUrl, cachedLabel) = cached
                app.playbackStateManager.updateStreamResolverLabel(cachedLabel)
                return@Factory dataSpec.withUri(cachedUrl.toUri())
            }

            val (resolvedUrl, resolverLabel) = runBlocking(Dispatchers.IO) {
                try {
                    app.youTubeInnertubeClient.getBestAudioUrl(videoId) to "Innertube"
                } catch (_: Exception) {
                    app.youTubeStreamResolver.getBestAudioUrl(videoId) to "NewPipe"
                }
            }
            precache.putUrl(videoId, resolvedUrl, resolverLabel, now)
            app.playbackStateManager.updateStreamResolverLabel(resolverLabel)

            dataSpec.withUri(resolvedUrl.toUri())
        }

        val networkAndCacheStack = CacheDataSource.Factory()
            .setCache(app.mediaCache)
            .setUpstreamDataSourceFactory(resolvingFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        return DefaultDataSource.Factory(this, networkAndCacheStack)
    }

    private fun createNotificationChannel() {
        val name = "Music Box playback"
        val descriptionText = "Music playback controls"
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
            description = descriptionText
        }
        val notificationManager: NotificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        mediaSession?.player?.stop()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }


    /**
     * The launcher icon as PNG bytes, decoded once. Used only where a track has no cover of
     * its own; it is deliberately the app's mark rather than a generic note, so the widget
     * says which app is playing.
     */
    private var cachedIconArtwork: ByteArray? = null
    private var triedIconArtwork = false

    private fun appIconArtwork(): ByteArray? {
        if (triedIconArtwork) return cachedIconArtwork
        triedIconArtwork = true
        cachedIconArtwork = try {
            val drawable = packageManager.getApplicationIcon(packageName)
            val size = 256
            val bitmap = createBitmap(size, size)
            val canvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            java.io.ByteArrayOutputStream().use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
        } catch (e: Exception) {
            Log.w("PlaybackService", "Could not render the app icon for the media session", e)
            null
        }
        return cachedIconArtwork
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}