package com.wanderwildwood.jimeikin

import com.wanderwildwood.jimeikin.ui.SongUiModel
import com.wanderwildwood.jimeikin.ui.hasSongsToKeep
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeCopiesTest {

    private fun video(id: String, title: String, track: Int? = null, millis: Long? = 200_000L) = SongUiModel(
        id = id,
        title = title,
        artist = "The Lanterns",
        durationMillis = millis,
        trackNumber = track,
        discNumber = 1,
        sourceType = "YOUTUBE",
        audioUri = id,
        album = "Low Tide",
    )

    private fun file(name: String, title: String, artist: String = "The Lanterns", track: Int? = null, millis: Long? = 201_000L) =
        SongUiModel(
            id = "file:///music/$name.m4a",
            title = title,
            artist = artist,
            durationMillis = millis,
            trackNumber = track,
            sourceType = "YOUTUBE_DOWNLOAD",
            audioUri = "file:///music/$name.m4a",
            album = "Low Tide",
        )

    private val harbour = file("Harbour", "Harbour", track = 9)
    private val shingle = file("Shingle", "Shingle")

    @Test
    fun theRecordFindsTheCopy() {
        val recorded = mapOf("vidA" to harbour.id)
        assertEquals(harbour, YouTubeCopyMatch.copyOf(video("vidA", "Something else"), recorded, listOf(harbour)))
    }

    @Test
    fun aRecordForAFileThatIsGoneFindsNothingByItself() {
        val recorded = mapOf("vidA" to "file:///music/Deleted.m4a")
        assertNull(YouTubeCopyMatch.copyOf(video("vidA", "Breakwater"), recorded, listOf(harbour)))
    }

    @Test
    fun anEarlierDownloadIsMatchedByTitleAndArtistFoldedLikeTheIds() {
        val earlier = file("Harbour", "  harbour ", artist = "the  LANTERNS")
        assertEquals(earlier, YouTubeCopyMatch.copyOf(video("vidA", "Harbour"), emptyMap(), listOf(earlier)))
    }

    @Test
    fun anotherArtistOrAnotherLengthIsNotTheSameSong() {
        val byOthers = file("Harbour", "Harbour", artist = "Tidewater")
        assertNull(YouTubeCopyMatch.copyOf(video("vidA", "Harbour"), emptyMap(), listOf(byOthers)))
        val longer = file("Harbour", "Harbour", millis = 260_000L)
        assertNull(YouTubeCopyMatch.copyOf(video("vidA", "Harbour"), emptyMap(), listOf(longer)))
        val unknownLength = file("Harbour", "Harbour", millis = null)
        assertEquals(unknownLength, YouTubeCopyMatch.copyOf(video("vidA", "Harbour"), emptyMap(), listOf(unknownLength)))
    }

    @Test
    fun aCopyRecordedForOneVideoIsNotTakenByAnotherOfTheSameTitle() {
        val recorded = mapOf("vidLive" to harbour.id)
        assertNull(YouTubeCopyMatch.copyOf(video("vidStudio", "Harbour"), recorded, listOf(harbour)))
    }

    @Test
    fun onlyYouTubeSongsHaveCopies() {
        assertNull(YouTubeCopyMatch.copyOf(harbour, mapOf(harbour.id to harbour.id), listOf(harbour)))
    }

    @Test
    fun copiesTakeTheListingsPlaceAndAreShownOnce() {
        val listing = listOf(video("vidA", "Harbour", track = 1), video("vidB", "Breakwater", track = 2), harbour)
        val shown = YouTubeCopyMatch.withCopies(listing, mapOf("vidA" to harbour.id), listOf(harbour))
        assertEquals(listOf(harbour.id, "vidB"), shown.map { it.id })
        // The album's number for it, not the one in the file's tag.
        assertEquals(1, shown.first().trackNumber)
    }

    @Test
    fun albumOrderIsTrackNumberThenTheListingsOrder() {
        val songs = listOf(
            video("c", "Third", track = 3),
            video("x", "Unnumbered one", track = null),
            video("a", "First", track = 1),
            video("y", "Unnumbered two", track = null),
            video("b", "Second", track = 2),
        )
        assertEquals(listOf("a", "b", "c", "x", "y"), YouTubeCopyMatch.inAlbumOrder(songs).map { it.id })
    }

    @Test
    fun onlyWhatIsNeitherHereNorAskedForIsLeftToDownload() {
        val listing = listOf(
            video("vidA", "Harbour"),
            video("vidB", "Breakwater"),
            video("vidC", "Shingle"),
            video("vidD", "Groyne"),
            video("vidD", "Groyne"),
        )
        val left = YouTubeCopyMatch.leftToDownload(
            listing,
            recorded = mapOf("vidA" to harbour.id),
            downloads = listOf(harbour, shingle),
            isUnderway = { it == "vidB" },
        )
        assertEquals(listOf("vidD"), left.map { it.id })
    }

    @Test
    fun theDownloadButtonGoesOnceEverythingIsHere() {
        val listing = listOf(video("vidA", "Harbour"), video("vidC", "Shingle"))
        val recorded = mapOf("vidA" to harbour.id)
        val downloads = listOf(harbour, shingle)
        val here: (SongUiModel) -> Boolean = { YouTubeCopyMatch.copyOf(it, recorded, downloads) != null }
        assertFalse(listing.hasSongsToKeep(canKeepYouTube = true, isDownloaded = here))
        assertTrue(listing.hasSongsToKeep(canKeepYouTube = true, isDownloaded = { false }))
        // A song deleted from the phone is downloadable again.
        val afterDelete: (SongUiModel) -> Boolean = { YouTubeCopyMatch.copyOf(it, recorded, listOf(harbour)) != null }
        assertTrue(listing.hasSongsToKeep(canKeepYouTube = true, isDownloaded = afterDelete))
    }

    @Test
    fun theRecordSurvivesItsFile() {
        val recorded = mapOf("vidA" to "file:///music/Harbour.m4a", "vidB" to "file:///music/Breakwater (2).m4a")
        assertEquals(recorded, YouTubeCopyMatch.decode(YouTubeCopyMatch.encode(recorded)))
        assertEquals(emptyMap<String, String>(), YouTubeCopyMatch.decode(""))
        assertEquals(mapOf("vidA" to "x"), YouTubeCopyMatch.decode("vidA\tx\nbroken line\n\t\nvidZ\t\n"))
    }

    @Test
    fun anAlbumPageIsReadInItsOwnOrder() {
        fun row(index: String?, title: String, videoId: String?, artist: String?, duration: String) = JSONObject().put(
            "musicResponsiveListItemRenderer",
            JSONObject().apply {
                put(
                    "flexColumns",
                    org.json.JSONArray()
                        .put(column(JSONObject().put("text", title).apply {
                            if (videoId != null) {
                                put("navigationEndpoint", JSONObject().put("watchEndpoint", JSONObject().put("videoId", videoId)))
                            }
                        }))
                        .put(if (artist == null) JSONObject().put("musicResponsiveListItemFlexColumnRenderer", JSONObject().put("text", JSONObject()))
                        else column(JSONObject().put("text", artist))),
                )
                put(
                    "fixedColumns",
                    org.json.JSONArray().put(
                        JSONObject().put(
                            "musicResponsiveListItemFixedColumnRenderer",
                            JSONObject().put("text", JSONObject().put("runs", org.json.JSONArray().put(JSONObject().put("text", duration)))),
                        ),
                    ),
                )
                if (index != null) put("index", JSONObject().put("runs", org.json.JSONArray().put(JSONObject().put("text", index))))
            },
        )

        val shelf = org.json.JSONArray()
            .put(row("1", "Harbour", "vidA", null, "3:20"))
            .put(row("2", "Breakwater", "vidB", "The Lanterns & Tidewater", "1:02:03"))
            .put(row("3", "Not playable here", null, null, "2:00"))
            .put(row("4", "Shingle", "vidD", null, "4:05"))
        val root = JSONObject().put(
            "contents",
            JSONObject().put(
                "twoColumnBrowseResultsRenderer",
                JSONObject().put(
                    "secondaryContents",
                    JSONObject().put(
                        "sectionListRenderer",
                        JSONObject().put(
                            "contents",
                            org.json.JSONArray().put(JSONObject().put("musicShelfRenderer", JSONObject().put("contents", shelf))),
                        ),
                    ),
                ),
            ),
        )

        val tracks = parseAlbumTracks(root)
        assertEquals(listOf("vidA", "vidB", "vidD"), tracks.map { it.videoId })
        assertEquals(listOf(1, 2, 4), tracks.map { it.trackNumber })
        assertEquals(listOf(null, "The Lanterns & Tidewater", null), tracks.map { it.artist })
        assertEquals(listOf(200_000L, 3_723_000L, 245_000L), tracks.map { it.durationMillis })
        assertEquals(emptyList<InnertubeAlbumTrack>(), parseAlbumTracks(JSONObject()))
    }

    private fun column(run: JSONObject) = JSONObject().put(
        "musicResponsiveListItemFlexColumnRenderer",
        JSONObject().put("text", JSONObject().put("runs", org.json.JSONArray().put(run))),
    )
}
