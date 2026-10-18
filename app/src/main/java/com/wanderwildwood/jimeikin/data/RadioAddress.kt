package com.wanderwildwood.jimeikin.data

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * A station added by its stream address, for one radio.garden does not list.
 *
 * Stations publish their streams three ways: the stream itself, a playlist file that names it
 * (`.m3u`, `.pls` - what a "listen" link on a station's site usually is), or an HLS playlist,
 * which is how the BBC sends its stations now. The first two are followed here to the stream;
 * HLS is left to the player, which reads it itself. Then the stream is actually opened by a
 * silent player and listened to until it is ready to sound, because an address that answers is
 * not yet a station that plays - and finding that out after it is kept is too late.
 */
object RadioAddress {

    sealed interface Outcome {
        data class Plays(val streamUrl: String) : Outcome
        /** Not an http or https address at all. */
        data object NotAnAddress : Outcome
        /** Nothing answered there, or it answered with an error. */
        data object Unreachable : Outcome
        /** A playlist file with no stream in it. */
        data object EmptyPlaylist : Outcome
        /** It answered, but what came back is not something the player can sound. */
        data object DoesNotPlay : Outcome
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 12) MusicBox"
    private const val PLAYLIST_BYTES = 64 * 1024L
    private const val LISTEN_MS = 20_000L

    /** The address as typed, given http:// when it has no scheme; null when it is not a web address. */
    fun normalise(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
        val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
        val url = withScheme.toHttpUrlOrNull() ?: return null
        return url.toString()
    }

    suspend fun check(context: Context, input: String): Outcome {
        val address = normalise(input) ?: return Outcome.NotAnAddress
        val stream = when (val r = followPlaylists(address, depth = 0)) {
            is Outcome.Plays -> r.streamUrl
            else -> return r
        }
        return if (plays(context, stream)) Outcome.Plays(stream) else Outcome.DoesNotPlay
    }

    /** Through a `.pls` or plain `.m3u` to the stream it names; anything else is the stream. */
    private suspend fun followPlaylists(address: String, depth: Int): Outcome = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(address).header("User-Agent", USER_AGENT).build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Outcome.Unreachable
                val finalUrl = response.request.url.toString()
                val type = response.header("Content-Type").orEmpty().lowercase()
                val path = response.request.url.encodedPath.lowercase()
                val isPls = "scpls" in type || path.endsWith(".pls")
                val isM3u = "mpegurl" in type || path.endsWith(".m3u") || path.endsWith(".m3u8")
                if (!isPls && !isM3u) return@withContext Outcome.Plays(finalUrl)

                val text = response.body?.source()?.let { source ->
                    source.request(PLAYLIST_BYTES)
                    source.buffer.clone().readUtf8()
                }.orEmpty()
                // HLS is a playlist of segments, not of stations: the player reads it itself.
                if (isM3u && "#EXT-X-" in text) return@withContext Outcome.Plays(finalUrl)

                val named = text.lineSequence()
                    .map { it.trim() }
                    .mapNotNull { line ->
                        if (isPls) Regex("^File\\d+=(.+)$", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)?.trim()
                        else line.takeIf { it.isNotEmpty() && !it.startsWith("#") }
                    }
                    .mapNotNull { response.request.url.resolve(it)?.toString() }
                    .firstOrNull { it.startsWith("http://") || it.startsWith("https://") }
                    ?: return@withContext Outcome.EmptyPlaylist
                // A playlist naming another playlist is followed once more, and no further.
                if (depth < 1) followPlaylists(named, depth + 1) else Outcome.Plays(named)
            }
        } catch (e: Exception) {
            android.util.Log.w("RadioAddress", "could not open $address", e)
            Outcome.Unreachable
        }
    }

    /** Opened by a silent player of its own: true once it is ready to sound. */
    @OptIn(UnstableApi::class)
    private suspend fun plays(context: Context, stream: String): Boolean = withContext(Dispatchers.Main) {
        val dataSource = OkHttpDataSource.Factory(http).setUserAgent(USER_AGENT)
        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .build()
        try {
            player.volume = 0f
            withTimeoutOrNull(LISTEN_MS) {
                suspendCancellableCoroutine { continuation ->
                    player.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_READY && continuation.isActive) continuation.resume(true)
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            if (continuation.isActive) continuation.resume(false)
                        }
                    })
                    player.setMediaItem(MediaItem.fromUri(stream))
                    player.prepare()
                }
            } ?: false
        } finally {
            player.release()
        }
    }
}
