package com.wanderwildwood.jimeikin

import com.wanderwildwood.jimeikin.ui.SongUiModel

/**
 * The choices behind "Keep playing similar songs from YouTube", apart from the player, so they
 * can be tested on invented songs.
 */
object SimilarSongs {

    /**
     * The YouTube video to ask what would follow [seed], or null when nothing should.
     *
     * A streamed YouTube song is its own video. A downloaded one is a file now, and the video it
     * came from is only in [recorded] (video id to song id, see [YouTubeCopies]); a download the
     * record does not know has no video to ask about. Anything else - a song from a folder, the
     * music server, the radio - is not YouTube's to follow.
     */
    fun seedVideoId(seed: SongUiModel, recorded: Map<String, String>): String? = when (seed.sourceType) {
        "YOUTUBE" -> (seed.audioUri ?: seed.id).takeIf { it.isNotBlank() }
        "YOUTUBE_DOWNLOAD" -> recorded.entries.firstOrNull { it.value == seed.id }?.key
        else -> null
    }

    /**
     * Up to [batch] of [found] to put on the end of [queue]: none that is already in it,
     * whether as the video or as its download, and not the seed itself, which YouTube Music
     * lists first.
     */
    fun pick(
        found: List<InnertubeSongResult>,
        queue: List<SongUiModel>,
        recorded: Map<String, String>,
        seedVideoId: String,
        batch: Int,
    ): List<InnertubeSongResult> {
        val inQueue = queue.map { it.id }.toSet()
        val copiesInQueue = recorded.filterValues { it in inQueue }.keys
        return found
            .filter { it.videoId != seedVideoId && it.videoId !in inQueue && it.videoId !in copiesInQueue }
            .distinctBy { it.videoId }
            .take(batch)
    }
}
