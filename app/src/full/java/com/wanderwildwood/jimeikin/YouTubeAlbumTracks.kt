package com.wanderwildwood.jimeikin

import org.json.JSONObject

/** One track of an album as YouTube Music's own album page lists it. */
data class InnertubeAlbumTrack(
    val videoId: String,
    val title: String,
    /** Null where the row leaves it out, which it does when it is the album's artist. */
    val artist: String?,
    val durationMillis: Long?,
    val trackNumber: Int?,
)

/**
 * The tracks of an album page (a browse of its MPRE id), in the album's order.
 *
 * The page used to be put together from a search for the album's title instead, which found
 * some of its tracks in whatever order the search ranked them - an order that could change
 * from one search to the next, so the page re-sorted itself each time it was drawn again. A
 * track YouTube cannot play has no video id and is left out.
 */
internal fun parseAlbumTracks(root: JSONObject): List<InnertubeAlbumTrack> {
    val sections = root.optJSONObject("contents")
        ?.optJSONObject("twoColumnBrowseResultsRenderer")
        ?.optJSONObject("secondaryContents")
        ?.optJSONObject("sectionListRenderer")
        ?.optJSONArray("contents")
        ?: return emptyList()

    val tracks = mutableListOf<InnertubeAlbumTrack>()
    for (i in 0 until sections.length()) {
        val items = sections.optJSONObject(i)
            ?.optJSONObject("musicShelfRenderer")
            ?.optJSONArray("contents")
            ?: continue
        for (j in 0 until items.length()) {
            val item = items.optJSONObject(j)?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
            parseAlbumTrack(item)?.let { tracks += it }
        }
    }
    return tracks
}

private fun parseAlbumTrack(item: JSONObject): InnertubeAlbumTrack? {
    val flexColumns = item.optJSONArray("flexColumns") ?: return null

    fun columnRuns(index: Int) = flexColumns.optJSONObject(index)
        ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
        ?.optJSONObject("text")
        ?.optJSONArray("runs")

    val titleRun = columnRuns(0)?.optJSONObject(0) ?: return null
    val title = titleRun.optString("text").trim()
    if (title.isEmpty()) return null

    val videoId = titleRun.optJSONObject("navigationEndpoint")
        ?.optJSONObject("watchEndpoint")
        ?.optString("videoId")
        .takeUnless { it.isNullOrBlank() }
        ?: item.optJSONObject("playlistItemData")
            ?.optString("videoId")
            .takeUnless { it.isNullOrBlank() }
        ?: return null

    // The second column is the track's artists when they differ from the album's: "A & B",
    // as runs with the joins between them. Empty when it is the album artist's own.
    val artist = columnRuns(1)?.let { runs ->
        (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
    }?.trim()?.takeIf { it.isNotEmpty() }

    val durationText = item.optJSONArray("fixedColumns")
        ?.optJSONObject(0)
        ?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")
        ?.optJSONObject("text")
        ?.optJSONArray("runs")
        ?.optJSONObject(0)
        ?.optString("text")

    val trackNumber = item.optJSONObject("index")
        ?.optJSONArray("runs")
        ?.optJSONObject(0)
        ?.optString("text")
        ?.trim()
        ?.toIntOrNull()

    return InnertubeAlbumTrack(
        videoId = videoId,
        title = title,
        artist = artist,
        durationMillis = durationText?.let(::durationTextToMillis),
        trackNumber = trackNumber,
    )
}

/** "4:38" or "1:02:03" to milliseconds; null for anything else. */
internal fun durationTextToMillis(text: String): Long? {
    val parts = text.trim().split(":")
    if (parts.size < 2) return null
    val numbers = parts.mapNotNull { it.toIntOrNull() }
    if (numbers.size != parts.size) return null

    val seconds = when (numbers.size) {
        2 -> numbers[0] * 60 + numbers[1]
        3 -> numbers[0] * 3600 + numbers[1] * 60 + numbers[2]
        else -> return null
    }
    return (seconds * 1000L).coerceAtLeast(0L)
}
