package com.wanderwildwood.jimeikin.data

import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.wanderwildwood.jimeikin.BuildConfig
import com.wanderwildwood.jimeikin.CalmMusic
import com.wanderwildwood.jimeikin.NoAacStream
import com.wanderwildwood.jimeikin.downloadAudioStream
import com.wanderwildwood.jimeikin.youTubeCopies
import com.wanderwildwood.jimeikin.youTubeInnertubeClient
import com.wanderwildwood.jimeikin.youTubeStreamResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File

/**
 * Makes every YouTube download AAC.
 *
 * Downloads made before 2.18.1 are Opus in WebM, and 2.18.1 copied them into .m4a files as
 * Opus still - and Apple's players, among others, will not play Opus from an .m4a file. Each
 * one is fetched again from YouTube by its video, as AAC, which is what a new download gets;
 * that loses nothing, where turning the Opus into AAC would lose a little a second time. Only
 * when YouTube itself says it has no AAC for the video, or the video is not known at all, is
 * the Opus decoded and encoded as AAC on the phone ([YouTubeAudioFile.transcodeToAac]). No
 * network is never a reason to do that; the song waits and is tried again.
 *
 * Each song goes through the download queue as a download of its own, after anything the
 * reader asked for, and only on an unmetered network while nothing is streaming (see
 * DownloadQueue). The new file is tagged, checked - one AAC track, as long as the old one to
 * within a second, and it decodes - and put in place of the old one by [DownloadReplace], which
 * keeps the song, its playlists and its name. Whatever fails, the old file stays the song and
 * it is tried again next launch. One line per file goes to the log.
 */
object DownloadUpgrade {

    private const val TAG = "DownloadUpgrade"
    private const val YOUTUBE = "YOUTUBE_DOWNLOAD"

    /**
     * Debug builds only: with a file of this name in the app's own folder, YouTube is taken to
     * have no AAC for any video, so the fallback can be tried on an emulator.
     */
    private const val NO_AAC_HOOK = "test-no-aac"

    sealed class Result {
        object Done : Result()
        data class Failed(val reason: String) : Result()

        /** Not now - the song is in the queue being played - and tried again next launch. */
        object Later : Result()
    }

    /**
     * The YouTube downloads still Opus that can be made AAC: this install's own files, and
     * documents in a folder the reader chose. A file another install wrote, reached only by
     * its path, is passed over: replacing it would mean Android asking about every one.
     */
    suspend fun candidates(app: CalmMusic, inUse: () -> Set<String>): List<SongEntity> = withContext(Dispatchers.IO) {
        val rows = CalmMusicDatabase.getDatabase(app).songDao().getSongsBySourceType(YOUTUBE)
        if (rows.isEmpty()) return@withContext emptyList()
        val inHand = DownloadReplace.pending(app).flatMapTo(HashSet()) { listOf(it.key, it.value) }
        val found = mutableListOf<SongEntity>()
        for (row in rows) {
            val place = DownloadReplace.Place.ofRow(row.audioUri) ?: continue
            if (place.key in inHand) continue
            when (place) {
                is DownloadReplace.Place.Path -> {
                    val file = place.file
                    if (!file.isFile) continue
                    // On Android 11 and later a private download is moved first; see DownloadMove.
                    if (PublicDownloads.isAvailable && !PublicDownloads.isPublic(file.path)) continue
                    if (!YouTubeAudioFile.isOpus(file)) continue
                    if (!PublicDownloads.isOwn(app, file)) {
                        Log.i(TAG, "not made AAC, this install did not write it: ${file.name}")
                        continue
                    }
                }
                is DownloadReplace.Place.Document -> if (!YouTubeAudioFile.isOpus(app, place.document)) continue
            }
            val playing = inUse()
            if (row.id in playing || row.audioUri in playing) {
                Log.i(TAG, "not made AAC yet, it is in the queue: ${place.name}")
                continue
            }
            found += row
        }
        found
    }

    /** Makes the download [songId] AAC; see the class comment. [onProgress] hears the fetch. */
    suspend fun upgrade(
        app: CalmMusic,
        songId: String,
        client: OkHttpClient,
        inUse: () -> Set<String>,
        onProgress: (Float) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        val database = CalmMusicDatabase.getDatabase(app)
        val row = database.songDao().getSongById(songId)
        val old = row?.let { DownloadReplace.Place.ofRow(it.audioUri) }
        fun fail(reason: String): Result {
            Log.w(TAG, "not made AAC: ${old?.name ?: songId}: $reason")
            return Result.Failed(reason)
        }
        if (row == null || old == null) return@withContext fail("it is not in the library any more")
        if (!DownloadReplace.exists(app, old)) return@withContext fail("its file is missing")
        val setSource: (MediaExtractor) -> Unit = when (old) {
            is DownloadReplace.Place.Path -> { extractor -> extractor.setDataSource(old.file.path) }
            is DownloadReplace.Place.Document -> { extractor -> extractor.setDataSource(app, old.document, null) }
        }
        val opus = when (old) {
            is DownloadReplace.Place.Path -> YouTubeAudioFile.isOpus(old.file)
            is DownloadReplace.Place.Document -> YouTubeAudioFile.isOpus(app, old.document)
        }
        if (!opus) return@withContext Result.Done
        val playing = inUse()
        if (row.id in playing || row.audioUri in playing) {
            Log.i(TAG, "not made AAC yet, it is in the queue: ${old.name}")
            return@withContext Result.Later
        }

        val oldUs = YouTubeAudioFile.durationUs(setSource)
        val videoId = app.youTubeCopies.recorded.value.entries.firstOrNull { it.value == row.id }?.key
            ?: when (old) {
                is DownloadReplace.Place.Path -> Mp4Boxes.read(old.file)
                is DownloadReplace.Place.Document -> Mp4Boxes.read(app, old.document)
            }?.comment.let(YouTubeAudioFile::videoIdOf)
        val tags = YouTubeAudioFile.Tags(
            title = row.title,
            artist = row.artist,
            album = row.album,
            albumArtist = row.albumId?.let { id -> database.albumDao().getAllAlbums().firstOrNull { it.id == id }?.artist },
            trackNumber = row.trackNumber,
            discNumber = row.discNumber,
            videoId = videoId,
        )

        val fetched = File.createTempFile("aac-", ".download", app.cacheDir)
        val work = File.createTempFile("aac-", ".m4a", app.cacheDir)
        try {
            val url = videoId?.let { aacUrl(app, it) }
            val how = if (url != null) {
                downloadAudioStream(client, url, fetched, onProgress)
                YouTubeAudioFile.remux(fetched, work) ?: return@withContext fail("YouTube's AAC could not be copied")
                "fetched again from YouTube"
            } else {
                YouTubeAudioFile.transcodeToAac(setSource, work) ?: return@withContext fail("it could not be encoded as AAC")
                val why = if (videoId == null) "its video is not known" else "YouTube has no AAC for it"
                "transcoded from Opus at ${YouTubeAudioFile.TRANSCODE_BIT_RATE / 1000} kb/s, $why"
            }
            if (!YouTubeAudioFile.writeTags(work, tags)) Log.w(TAG, "the tags would not take: ${old.name}")
            if (!YouTubeAudioFile.verify(work, oldUs, MediaFormat.MIMETYPE_AUDIO_AAC)) {
                return@withContext fail("the new file did not check out")
            }
            DownloadReplace.replace(app, database, row, old, work)
            Log.i(TAG, "made AAC: ${old.name}, $how")
            Result.Done
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(e.message ?: e.javaClass.simpleName)
        } finally {
            fetched.delete()
            work.delete()
        }
    }

    /**
     * The video's AAC stream, or null when YouTube says it has none. Throws when YouTube could
     * not be asked, so the song waits rather than being encoded again for want of a network.
     * NewPipe's word decides, as it can read the streams the first way finds ciphered.
     */
    private suspend fun aacUrl(app: CalmMusic, videoId: String): String? {
        if (BuildConfig.DEBUG && File(app.filesDir, NO_AAC_HOOK).exists()) {
            Log.i(TAG, "[$videoId] taken to have no AAC ($NO_AAC_HOOK)")
            return null
        }
        try {
            return app.youTubeInnertubeClient.getAacAudioUrl(videoId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // NoAacStream among them: NewPipe is asked as well before that is believed.
        }
        return try {
            app.youTubeStreamResolver.getAacAudioUrl(videoId)
        } catch (e: NoAacStream) {
            null
        }
    }
}
