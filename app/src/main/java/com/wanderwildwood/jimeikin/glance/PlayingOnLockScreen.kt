package com.wanderwildwood.jimeikin.glance

import android.content.Context
import com.wanderwildwood.jimeikin.CalmMusic
import com.wanderwildwood.jimeikin.R

/**
 * The song playing or paused, for Glance's lock-screen panel: "Song — Artist", with whether it
 * is playing beside it. Nothing at all while nothing is playing.
 */
class PlayingOnLockScreen : GlanceProvider() {

    override fun enabled(context: Context): Boolean = runCatching {
        (context.applicationContext as CalmMusic).settingsManager.getPlayingOnLockScreenSync()
    }.getOrDefault(false)

    override fun lines(context: Context): List<GlanceProvider.Line> {
        val now = NowPlaying.current ?: return emptyList()
        val text = NowPlaying.text(now.title, now.artist) ?: return emptyList()
        val lead = context.getString(if (now.isPlaying) R.string.glance_playing else R.string.glance_paused)
        return listOf(GlanceProvider.Line(text = text, lead = lead))
    }
}
