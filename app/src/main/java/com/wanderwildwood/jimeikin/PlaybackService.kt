package com.wanderwildwood.jimeikin

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
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
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.wanderwildwood.jimeikin.glance.NowPlaying
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

        /**
         * The session while the service is alive, for the lock-screen controls, which run in
         * this same process and follow the player directly rather than binding to it.
         */
        private val _activeSession = MutableStateFlow<MediaSession?>(null)
        val activeSession: StateFlow<MediaSession?> = _activeSession

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

            // Whatever changed - a new song, play, pause, a stop - Glance's line is worked out
            // again, and Glance is told only if the line is now different.
            override fun onEvents(player: Player, events: Player.Events) {
                publishNowPlaying(player)
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

        // What the session publishes, which is what a launcher's now-playing widget, the lock
        // screen and the media notification all draw from. It carries no picture at all: a
        // song's cover, embedded or fetched, is stripped here, so covers stay inside the app
        // and the home and lock screens stay calmer, each drawing its own plain look.
        //
        // It has to be stripped twice. The notification, the platform session and the
        // lock-screen strip ask the player for the song, so getMediaMetadata covers them. But
        // a controller that connects to the session is sent the song as it changes, straight
        // from the listener callback, and that would still carry the cover - so every
        // listener is wrapped and handed the stripped song too.
        val sessionPlayer = object : ForwardingPlayer(player) {
            private val wrapped = HashMap<Player.Listener, Player.Listener>()

            override fun getMediaMetadata(): MediaMetadata =
                super.getMediaMetadata().withoutArtwork()

            override fun addListener(listener: Player.Listener) {
                super.addListener(wrapped.getOrPut(listener) { NoArtworkListener(listener) })
            }

            override fun removeListener(listener: Player.Listener) {
                super.removeListener(wrapped.remove(listener) ?: listener)
            }

            // A station picks up where it is now, not where it was paused. Resumed from the
            // pause, it played on from that moment, so after a while in another app it ran
            // minutes behind the same station anywhere else. Every play comes through here:
            // the app's own button, the notification and the lock screen.
            override fun play() {
                catchUpIfLive()
                super.play()
            }

            override fun setPlayWhenReady(playWhenReady: Boolean) {
                if (playWhenReady) catchUpIfLive()
                super.setPlayWhenReady(playWhenReady)
            }

            private fun catchUpIfLive() {
                if (player.playWhenReady || player.playbackState == Player.STATE_IDLE) return
                // An HLS station says it is live; a plain stream has no length to say anything.
                val live = player.isCurrentMediaItemLive ||
                    (player.duration == C.TIME_UNSET && !player.isCurrentMediaItemSeekable)
                if (live) player.seekToDefaultPosition()
            }
        }

        mediaSession = MediaSession.Builder(this, sessionPlayer)
            .setSessionActivity(sessionActivityPendingIntent)
            .build()
        _activeSession.value = mediaSession

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
                // A live radio stream has no end and no length, and handing one to the cache
                // stalls it before a note is heard - it sat on "Loading" for ever. This flag
                // is exactly for that case: cache what has a length, stream what does not. A
                // song from a music server still has a length and is still cached.
                return@Factory dataSpec.buildUpon()
                    .setFlags(dataSpec.flags or DataSpec.FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN)
                    .build()
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

    /** What is loaded, for Glance's lock-screen panel; nothing while stopped or finished. */
    private fun publishNowPlaying(player: Player) {
        val item = player.currentMediaItem
        val idle = player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED
        val meta = player.mediaMetadata
        val title = meta.title?.toString()?.takeIf { it.isNotBlank() }
        NowPlaying.set(
            this,
            if (item == null || idle || title == null) {
                null
            } else {
                NowPlaying.Now(
                    title = title,
                    artist = meta.artist?.toString()?.takeIf { it.isNotBlank() },
                    isPlaying = player.playWhenReady,
                )
            },
        )
    }

    private fun createNotificationChannel() {
        val name = getString(R.string.service_playback_channel_name)
        val descriptionText = getString(R.string.service_playback_channel_description)
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


    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        _activeSession.value = null
        NowPlaying.set(this, null)
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}

/** The same song with no picture: no embedded cover and no link to one. */
private fun MediaMetadata.withoutArtwork(): MediaMetadata {
    if (artworkData == null && artworkUri == null) return this
    return buildUpon()
        .setArtworkData(null, null)
        .setArtworkUri(null)
        .build()
}

/**
 * Passes every player callback on unchanged, except that a song changing arrives with no
 * picture. Every callback is written out by hand, so none is quietly dropped: each one has a
 * default that does nothing, and a callback left out here would never reach the session.
 */
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
private class NoArtworkListener(private val inner: Player.Listener) : Player.Listener {
    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) =
        inner.onMediaMetadataChanged(mediaMetadata.withoutArtwork())

    override fun onEvents(player: Player, events: Player.Events) = inner.onEvents(player, events)
    override fun onTimelineChanged(timeline: Timeline, reason: Int) = inner.onTimelineChanged(timeline, reason)
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = inner.onMediaItemTransition(mediaItem, reason)
    override fun onTracksChanged(tracks: Tracks) = inner.onTracksChanged(tracks)
    override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) = inner.onPlaylistMetadataChanged(mediaMetadata)
    override fun onIsLoadingChanged(isLoading: Boolean) = inner.onIsLoadingChanged(isLoading)
    override fun onLoadingChanged(isLoading: Boolean) = inner.onLoadingChanged(isLoading)
    override fun onAvailableCommandsChanged(availableCommands: Player.Commands) = inner.onAvailableCommandsChanged(availableCommands)
    override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) = inner.onTrackSelectionParametersChanged(parameters)
    override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) = inner.onPlayerStateChanged(playWhenReady, playbackState)
    override fun onPlaybackStateChanged(playbackState: Int) = inner.onPlaybackStateChanged(playbackState)
    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = inner.onPlayWhenReadyChanged(playWhenReady, reason)
    override fun onPlaybackSuppressionReasonChanged(reason: Int) = inner.onPlaybackSuppressionReasonChanged(reason)
    override fun onIsPlayingChanged(isPlaying: Boolean) = inner.onIsPlayingChanged(isPlaying)
    override fun onRepeatModeChanged(repeatMode: Int) = inner.onRepeatModeChanged(repeatMode)
    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = inner.onShuffleModeEnabledChanged(shuffleModeEnabled)
    override fun onPlayerError(error: PlaybackException) = inner.onPlayerError(error)
    override fun onPlayerErrorChanged(error: PlaybackException?) = inner.onPlayerErrorChanged(error)
    override fun onPositionDiscontinuity(reason: Int) = inner.onPositionDiscontinuity(reason)
    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) =
        inner.onPositionDiscontinuity(oldPosition, newPosition, reason)
    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = inner.onPlaybackParametersChanged(playbackParameters)
    override fun onSeekBackIncrementChanged(seekBackIncrementMs: Long) = inner.onSeekBackIncrementChanged(seekBackIncrementMs)
    override fun onSeekForwardIncrementChanged(seekForwardIncrementMs: Long) = inner.onSeekForwardIncrementChanged(seekForwardIncrementMs)
    override fun onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs: Long) = inner.onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs)
    override fun onAudioSessionIdChanged(audioSessionId: Int) = inner.onAudioSessionIdChanged(audioSessionId)
    override fun onAudioAttributesChanged(audioAttributes: AudioAttributes) = inner.onAudioAttributesChanged(audioAttributes)
    override fun onVolumeChanged(volume: Float) = inner.onVolumeChanged(volume)
    override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) = inner.onSkipSilenceEnabledChanged(skipSilenceEnabled)
    override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) = inner.onDeviceInfoChanged(deviceInfo)
    override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) = inner.onDeviceVolumeChanged(volume, muted)
    override fun onVideoSizeChanged(videoSize: VideoSize) = inner.onVideoSizeChanged(videoSize)
    override fun onSurfaceSizeChanged(width: Int, height: Int) = inner.onSurfaceSizeChanged(width, height)
    override fun onRenderedFirstFrame() = inner.onRenderedFirstFrame()
    override fun onCues(cues: List<Cue>) = inner.onCues(cues)
    override fun onCues(cueGroup: CueGroup) = inner.onCues(cueGroup)
    override fun onMetadata(metadata: Metadata) = inner.onMetadata(metadata)
}
