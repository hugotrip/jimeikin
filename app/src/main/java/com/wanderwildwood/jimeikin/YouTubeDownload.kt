package com.wanderwildwood.jimeikin

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.wanderwildwood.jimeikin.data.AlbumEntity
import com.wanderwildwood.jimeikin.data.ArtistEntity
import com.wanderwildwood.jimeikin.data.CalmMusicDatabase
import com.wanderwildwood.jimeikin.data.dropOrphanedYouTubeRows
import com.wanderwildwood.jimeikin.data.LocalMusicScanner
import com.wanderwildwood.jimeikin.data.SongEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong

/**
 * Shared internal implementation of the YouTube download pipeline.
 */
@OptIn(UnstableApi::class)
internal suspend fun performYouTubeDownloadInternal(
    app: CalmMusic,
    song: com.wanderwildwood.jimeikin.ui.SongUiModel,
    albumArtist: String?,
    targetDir: File,
    context: Context,
    client: OkHttpClient,
    onProgress: (Float) -> Unit,
): Boolean {
    // Every 8 KB read used to publish, which on a panel that redraws in full is the worst
    // rate the app can ask for. A whole percent is as fine as anything on screen can show.
    var lastPublishedPercent = -1
    val publishProgress: (Float) -> Unit = { fraction ->
        val percent = (fraction * 100f).toInt().coerceIn(0, 100)
        if (percent != lastPublishedPercent) {
            lastPublishedPercent = percent
            onProgress(fraction)
        }
    }
    var tmpFile: File? = null
    try {
        val videoId = song.id
        val TAG = "YouTubeDownload"

        val streamUrl = withContext(Dispatchers.IO) {
            try {
                val url = app.youTubeInnertubeClient.getBestAudioUrl(videoId)
                Log.i(TAG, "[$videoId] Resolved URL via InnerTube")
                url
            } catch (e: Exception) {
                Log.w(TAG, "[$videoId] InnerTube failed: ${e.message}. Falling back to NewPipe.")
                val url = app.youTubeStreamResolver.getDownloadAudioUrl(videoId)
                Log.i(TAG, "[$videoId] Resolved URL via NewPipe")
                url
            }
        }

        val safeTitle = (song.title.ifBlank { videoId })
            .replace(Regex("""[\\\\/:*?\"<>|]"""), "_")
        // ⚠ Never deleted up front, which is what this did: the file was named from the title
        // alone and whatever already had that name was removed before the download began. A
        // second song called "Intro" erased the first -- its file, and through the row keyed on
        // the file, the song itself, so its playlist entries played the new one -- and a
        // re-download that then failed had already thrown away the good copy.
        val targetFile = withContext(Dispatchers.IO) { chooseTarget(targetDir, safeTitle, song.artist, song.title) }

        tmpFile = withContext(Dispatchers.IO) {
            File.createTempFile("yt-$videoId-", ".m4a", context.cacheDir)
        }

        val downloadSuccess = withContext(Dispatchers.IO) {
            val userAgent = YouTubeStreamResolver.NEWPIPE_USER_AGENT

            val probeRequest = Request.Builder()
                .url(streamUrl)
                .header("User-Agent", userAgent)
                .head()
                .build()

            val (contentLength, supportsRanges) = client.newCall(probeRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("Probe failed: ${response.code}")
                }
                val length = response.header("Content-Length")?.toLongOrNull() ?: -1L
                val acceptRanges = response.header("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true
                length to acceptRanges
            }

            if (contentLength > 0L && supportsRanges) {
                val chunkCount = 4.coerceAtMost(((contentLength / (5L * 1024 * 1024)).toInt() + 1).coerceAtLeast(2))
                val chunkSize = contentLength / chunkCount
                val downloaded = AtomicLong(0L)

                kotlinx.coroutines.coroutineScope {
                    repeat(chunkCount) { index ->
                        val start = index * chunkSize
                        val endExclusive = if (index == chunkCount - 1) contentLength else (start + chunkSize)
                        val end = endExclusive - 1

                        launch(Dispatchers.IO) {
                            val rangeRequest = Request.Builder()
                                .url(streamUrl)
                                .header("User-Agent", userAgent)
                                .addHeader("Range", "bytes=$start-$end")
                                .build()

                            client.newCall(rangeRequest).execute().use { response ->
                                if (!response.isSuccessful) {
                                    throw IllegalStateException("Chunk download failed: ${response.code}")
                                }
                                val body = response.body ?: throw IllegalStateException("Empty body for chunk")

                                RandomAccessFile(tmpFile, "rw").use { raf ->
                                    val buffer = ByteArray(8 * 1024)
                                    var read: Int
                                    var offset = start
                                    while (body.byteStream().read(buffer).also { read = it } != -1) {
                                        // Canceled from Downloads: stop reading now, rather
                                        // than finishing a song nobody wants and holding up
                                        // the next one meanwhile.
                                        ensureActive()
                                        if (read <= 0) continue
                                        synchronized(raf) {
                                            raf.seek(offset)
                                            raf.write(buffer, 0, read)
                                        }
                                        offset += read
                                        val totalSoFar = downloaded.addAndGet(read.toLong())
                                        publishProgress((totalSoFar.toDouble() / contentLength.toDouble()).toFloat())
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                val request = Request.Builder()
                    .url(streamUrl)
                    .header("User-Agent", userAgent)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("Download failed: ${response.code}")
                    }
                    val body = response.body ?: throw IllegalStateException("Empty body")
                    val total = body.contentLength().takeIf { it > 0 } ?: -1L

                    FileOutputStream(tmpFile).use { out ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(8 * 1024)
                            var read: Int
                            var readSoFar = 0L
                            while (input.read(buffer).also { read = it } != -1) {
                                ensureActive()
                                out.write(buffer, 0, read)
                                if (total > 0) {
                                    readSoFar += read
                                    publishProgress(readSoFar.toFloat() / total.toFloat())
                                }
                            }
                        }
                    }
                }
            }
            true
        }

        if (!downloadSuccess) return false

        withContext(Dispatchers.IO) {
            // Written beside it and renamed over it, so an existing copy is only replaced by a
            // whole one: a kill or a full card part way through leaves the old file as it was.
            val part = File(targetFile.parentFile, targetFile.name + ".part")
            FileInputStream(tmpFile).use { input ->
                FileOutputStream(part).use { output ->
                    input.copyTo(output)
                }
            }
            if (!part.renameTo(targetFile)) {
                part.delete()
                throw java.io.IOException("could not put ${targetFile.name} in place")
            }
        }

        withContext(Dispatchers.IO) {
            try {
                TagOptionSingleton.getInstance().isAndroid = true
                val audioFile = AudioFileIO.read(targetFile)
                val tag = audioFile.tagAndConvertOrCreateAndSetDefault

                tag.setField(FieldKey.TITLE, song.title)
                tag.setField(FieldKey.ARTIST, song.artist)
                if (!song.album.isNullOrBlank()) tag.setField(FieldKey.ALBUM, song.album)

                if (!albumArtist.isNullOrBlank()) {
                    tag.setField(FieldKey.ALBUM_ARTIST, albumArtist)
                }

                song.trackNumber?.let { tag.setField(FieldKey.TRACK, it.toString()) }
                song.discNumber?.let { tag.setField(FieldKey.DISC_NO, it.toString()) }

                audioFile.commit()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        onProgress(1f)

        withContext(Dispatchers.IO) {
            try {
                val settings = app.settingsManager

                val database = CalmMusicDatabase.getDatabase(app)
                val songDao = database.songDao()
                val albumDao = database.albumDao()
                val artistDao = database.artistDao()
                val playlistDao = database.playlistDao()

                val fileUri = android.net.Uri.fromFile(targetFile)
                val existingStreamingEntity = SongEntity(
                    id = videoId,
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    albumId = null,
                    discNumber = song.discNumber,
                    trackNumber = song.trackNumber,
                    durationMillis = song.durationMillis,
                    sourceType = "YOUTUBE",
                    audioUri = song.audioUri ?: videoId,
                    artistId = null,
                    releaseYear = null,
                    localLastModifiedMillis = null,
                    localFileSizeBytes = null,
                )

                val scannedAudio = LocalMusicScanner.buildSongEntityFromFile(
                    context = context,
                    uri = fileUri,
                    name = targetFile.name,
                    lastModified = targetFile.lastModified(),
                    fileSize = targetFile.length(),
                    existing = existingStreamingEntity,
                )

                fun String.toIdComponent(): String =
                    trim().replace(Regex("\\s+"), " ").lowercase()

                val trackArtistKey = song.artist.toIdComponent()
                val albumKey = song.album?.toIdComponent()

                val effectiveAlbumArtist = albumArtist?.takeIf { it.isNotBlank() } ?: song.artist
                val albumArtistKey = effectiveAlbumArtist.toIdComponent()

                val albumId = if (albumKey != null) {
                    "YOUTUBE_DOWNLOAD:$albumArtistKey:$albumKey"
                } else null

                // The same rule the local library follows: an album's songs are filed under
                // the album artist, so a downloaded album stays one artist however many
                // guests are credited on its tracks. Only a song with no album at all earns
                // a row of its own under the track artist.
                val artistId = if (albumId != null) {
                    "YOUTUBE_DOWNLOAD:$albumArtistKey"
                } else {
                    "YOUTUBE_DOWNLOAD:$trackArtistKey"
                }

                val localSongEntity = scannedAudio.song.copy(
                    sourceType = "YOUTUBE_DOWNLOAD",
                    artistId = artistId,
                    albumId = albumId
                )

                if (albumId == null && artistId.isNotBlank()) {
                    artistDao.upsertAll(listOf(ArtistEntity(
                        id = artistId,
                        name = song.artist,
                        sourceType = "YOUTUBE_DOWNLOAD"
                    )))
                }

                if (albumId != null && localSongEntity.album != null) {
                    val albumEntityArtistId = "YOUTUBE_DOWNLOAD:$albumArtistKey"

                    artistDao.upsertAll(listOf(ArtistEntity(
                        id = albumEntityArtistId,
                        name = effectiveAlbumArtist,
                        sourceType = "YOUTUBE_DOWNLOAD"
                    )))

                    albumDao.upsertAll(listOf(AlbumEntity(
                        id = albumId,
                        name = localSongEntity.album,
                        artist = effectiveAlbumArtist,
                        sourceType = "YOUTUBE_DOWNLOAD",
                        artistId = albumEntityArtistId
                    )))
                }

                songDao.upsertAll(listOf(localSongEntity))
                // The row is keyed on its file from here on; this is what still knows it was
                // this video, so a listing that names the video can find it on the phone.
                app.youTubeCopies.record(videoId, localSongEntity.id)
                playlistDao.updateSongIdForAllPlaylists(oldSongId = videoId, newSongId = fileUri.toString())

                if (localSongEntity.id != videoId) {
                    songDao.deleteByIds(listOf(videoId))
                }
                database.dropOrphanedYouTubeRows()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return true
    } finally {
        tmpFile?.delete()
    }

}

/**
 * Where a download goes: its title, or the title numbered, "Intro (2)", when another song
 * already has it. A file with the same title *and* artist is taken to be this song and is
 * the one case replaced -- and only once the new copy is whole; see the rename above.
 */
private fun chooseTarget(dir: File, base: String, artist: String?, title: String): File {
    var n = 1
    while (true) {
        val name = if (n == 1) "$base.m4a" else "$base ($n).m4a"
        val candidate = File(dir, name)
        if (!candidate.exists() || isSameSong(candidate, artist, title)) return candidate
        n++
    }
}

private fun isSameSong(file: File, artist: String?, title: String): Boolean = runCatching {
    TagOptionSingleton.getInstance().isAndroid = true
    val tag = AudioFileIO.read(file).tag ?: return false
    tag.getFirst(FieldKey.TITLE) == title && tag.getFirst(FieldKey.ARTIST) == artist.orEmpty()
}.getOrDefault(false)
