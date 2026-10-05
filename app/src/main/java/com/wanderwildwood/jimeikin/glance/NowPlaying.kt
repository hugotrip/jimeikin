package com.wanderwildwood.jimeikin.glance

import android.content.Context

/**
 * What is playing, as the player last said it, for Glance's lock-screen panel.
 *
 * Written by [com.wanderwildwood.jimeikin.PlaybackService] on the player's thread whenever
 * something about it changes, and read by [PlayingOnLockScreen] on whatever thread Glance's
 * question arrives on. Null whenever nothing is loaded: stopped, ended, or the service gone.
 */
object NowPlaying {

    data class Now(val title: String, val artist: String?, val isPlaying: Boolean)

    @Volatile
    var current: Now? = null
        private set

    /** Tells Glance only when what it would draw has changed, not on every tick of the player. */
    fun set(context: Context, now: Now?) {
        if (now == current) return
        current = now
        GlanceProvider.changed(context)
    }

    /**
     * The one line the panel shows: "Song — Artist", or the song alone where nothing names who
     * sings it. Null where there is not even a title, which leaves the panel without the line.
     */
    fun text(title: String?, artist: String?): String? {
        val song = title?.trim().orEmpty()
        if (song.isEmpty()) return null
        val by = artist?.trim().orEmpty()
        return if (by.isEmpty()) song else "$song — $by"
    }
}
