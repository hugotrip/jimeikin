package com.wanderwildwood.jimeikin.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.wanderwildwood.jimeikin.ui.SongUiModel

/**
 * Encapsulates per-source playback subqueues and index maps for the current
 * playback queue. This is extracted from MainActivity/CalmMusic to reduce the
 * amount of playback bookkeeping state inside the composable while preserving
 * existing behavior.
 */
class PlaybackCoordinator {

    var localPlaybackSubqueue: List<SongUiModel> = emptyList()
        private set

    var localIndexByGlobal: IntArray? = null
        private set

    var localMediaItemsForQueue: List<MediaItem> = emptyList()
        private set

    var localQueueInitialized: Boolean = false

    /**
     * Rebuilds the per-source subqueues and index maps given the full playback
     * queue. Logic is copied from the original CalmMusic implementation.
     */
    fun rebuildPlaybackSubqueues(queue: List<SongUiModel>) {
        if (queue.isEmpty()) {
            localPlaybackSubqueue = emptyList()
            localIndexByGlobal = null
            localMediaItemsForQueue = emptyList()
            localQueueInitialized = false
            return
        }

        val localList = mutableListOf<SongUiModel>()
        val localMap = IntArray(queue.size) { -1 }

        var localCounter = 0

        queue.forEachIndexed { globalIndex, song ->
            when (song.sourceType) {
                // A server song is a url the player can open, so it queues beside a file
                // rather than needing the resolve step a YouTube track does.
                "LOCAL_FILE", "YOUTUBE_DOWNLOAD", "SUBSONIC", "SUBSONIC_DOWNLOAD" -> {
                    val uri = song.audioUri
                    if (!uri.isNullOrBlank()) {
                        localMap[globalIndex] = localCounter
                        localList += song
                        localCounter++
                    }
                }
            }
        }

        localPlaybackSubqueue = localList
        localIndexByGlobal = localMap

        localMediaItemsForQueue = localList.map { song ->
            val metadata = MediaMetadata.Builder()
                .setTitle(song.title)
                .setArtist(song.artist)
                .setAlbumTitle(song.album)
                .build()

            MediaItem.Builder()
                .setMediaId(song.id)
                .setUri(song.audioUri)
                .setMediaMetadata(metadata)
                .build()
        }

        localQueueInitialized = false
    }
}