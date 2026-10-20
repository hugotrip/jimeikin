package com.wanderwildwood.jimeikin.data

import android.annotation.SuppressLint
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.util.Log
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.audio.mp4.Mp4TagReader
import org.jaudiotagger.audio.mp4.Mp4TagWriter
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.TagOptionSingleton
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Makes a YouTube download a real .m4a file with its tags in it.
 *
 * YouTube's best audio is usually Opus in a WebM file, and downloads were saved as that with a
 * .m4a name: other players could refuse the file for not being what its name said, and the
 * tags written into it never took, so after a reinstall it came back with no artist and no
 * album. A download now asks for the AAC stream, and whatever arrives is copied, sample for
 * sample with nothing re-encoded, into a plain MP4 file - YouTube's own MP4 is cut into
 * fragments that the tag library cannot write to. Opus is written by [OpusMp4Writer], and
 * Android reads it back from Android 10, so an Opus download on Android 9 stays WebM. The copy
 * is then tagged and checked before anything uses it.
 *
 * Covers are not written into the file; the app keeps its covers to itself.
 */
object YouTubeAudioFile {

    private const val TAG = "YouTubeAudioFile"

    /** What a download's tags say. Blank fields are left out. */
    data class Tags(
        val title: String,
        val artist: String?,
        val album: String?,
        val albumArtist: String?,
        val trackNumber: Int?,
        val discNumber: Int?,
        val videoId: String?,
    )

    /** A download made ready: its file, and the extension and mime type it is saved under. */
    data class Prepared(val file: File, val extension: String, val mimeType: String)

    /** Whether [file] is a WebM (Matroska) file, by its first bytes, whatever its name. */
    fun isWebm(file: File): Boolean = PublicDownloads.sniffMimeType(file) == "audio/webm"

    /** Whether a WebM download can be made an MP4 one on this phone: one that can play it back. */
    val canRemuxOpus: Boolean get() = Build.VERSION.SDK_INT >= 29

    /** The address a download's comment names its video by; see [videoIdOf]. */
    fun videoAddress(videoId: String) = "https://music.youtube.com/watch?v=$videoId"

    /** The video a download came from, from the comment [writeTags] gave it. */
    fun videoIdOf(comment: String?): String? =
        comment?.let { Regex("""music\.youtube\.com/watch\?v=([A-Za-z0-9_-]{6,})""").find(it)?.groupValues?.get(1) }

    /**
     * Makes a finished download ready to be saved: copied into a plain MP4 file in [workDir],
     * tagged and checked. If that cannot be done the download is kept as it came, under the
     * name of what it really is - an Opus download on Android 9 is a .webm file. The caller
     * deletes [Prepared.file] when it is not [source].
     */
    fun prepare(source: File, workDir: File, tags: Tags): Prepared {
        val kind = PublicDownloads.sniffMimeType(source)
        val asItCame = when (kind) {
            "audio/webm" -> Prepared(source, "webm", "audio/webm")
            else -> Prepared(source, "m4a", "audio/mp4")
        }
        if (kind != "audio/mp4" && kind != "audio/webm") return asItCame
        if (kind == "audio/webm" && !canRemuxOpus) return asItCame
        val out = File.createTempFile("yt-mp4-", ".m4a", workDir)
        val made = runCatching {
            val sourceUs = remux(source, out) ?: return@runCatching false
            if (!writeTags(out, tags)) Log.w(TAG, "could not tag ${tags.title}")
            verify(out, sourceUs)
        }.onFailure { Log.w(TAG, "could not make ${tags.title} an MP4 file", it) }.getOrDefault(false)
        if (made) return Prepared(out, "m4a", "audio/mp4")
        out.delete()
        if (kind == "audio/mp4") writeTags(source, tags)
        return asItCame
    }

    /**
     * Copies the audio of [source] into a new MP4 file at [out], sample for sample. Returns
     * the length of the original in microseconds, or null if it could not be copied - then
     * [out] is left for the caller to delete.
     */
    @SuppressLint("WrongConstant")
    fun remux(source: File, out: File): Long? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source.path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            val size = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
            val buffer = ByteBuffer.allocateDirect(maxOf(size, 256 * 1024))
            if (format.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_OPUS) {
                return remuxOpus(extractor, format, buffer, out)
            }
            val info = MediaCodec.BufferInfo()
            var lastUs = 0L
            var samples = 0
            val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                val outTrack = muxer.addTrack(format)
                muxer.start()
                while (true) {
                    buffer.clear()
                    val read = extractor.readSampleData(buffer, 0)
                    if (read < 0) break
                    val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    info.set(0, read, extractor.sampleTime, flags)
                    muxer.writeSampleData(outTrack, buffer, info)
                    lastUs = maxOf(lastUs, extractor.sampleTime)
                    samples++
                    extractor.advance()
                }
                if (samples == 0) return null
                muxer.stop()
            } finally {
                runCatching { muxer.release() }
            }
            return format.durationUs() ?: lastUs
        } catch (e: Exception) {
            Log.w(TAG, "could not copy ${source.name}: ${e.message}")
            return null
        } finally {
            extractor.release()
        }
    }

    /** Opus goes through [OpusMp4Writer]: Android's own MP4 writer refuses it before Android 12. */
    private fun remuxOpus(extractor: MediaExtractor, format: MediaFormat, buffer: ByteBuffer, out: File): Long? {
        val csd = format.getByteBuffer("csd-0") ?: return null
        val opusHead = ByteArray(csd.remaining()).also { csd.duplicate().get(it) }
        val writer = OpusMp4Writer(out, opusHead)
        var lastUs = 0L
        var packet = ByteArray(0)
        try {
            while (true) {
                buffer.clear()
                val read = extractor.readSampleData(buffer, 0)
                if (read < 0) break
                if (read > 0) {
                    if (packet.size < read) packet = ByteArray(read)
                    buffer.position(0)
                    buffer.get(packet, 0, read)
                    writer.write(packet, read)
                }
                lastUs = maxOf(lastUs, extractor.sampleTime)
                extractor.advance()
            }
            writer.finish()
        } catch (e: Exception) {
            writer.abandon()
            throw e
        }
        return format.durationUs() ?: lastUs
    }

    /**
     * Whether [file] is a whole, playable MP4 song: one track, audio, as long as [expectedUs]
     * to within a second when that is known, and its first samples decode.
     */
    fun verify(file: File, expectedUs: Long?): Boolean {
        if (PublicDownloads.sniffMimeType(file) != "audio/mp4") return false
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            if (extractor.trackCount != 1) return false
            val format = extractor.getTrackFormat(0)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return false
            if (!mime.startsWith("audio/")) return false
            val durationUs = format.durationUs() ?: return false
            if (expectedUs != null && expectedUs > 0 && abs(durationUs - expectedUs) > 1_000_000L) {
                Log.w(TAG, "${file.name} is ${durationUs / 1000} ms long, not ${expectedUs / 1000}")
                return false
            }
            extractor.selectTrack(0)
            return decodes(extractor, format, mime)
        } catch (e: Exception) {
            Log.w(TAG, "could not check ${file.name}: ${e.message}")
            return false
        } finally {
            extractor.release()
        }
    }

    /** Whether the decoder for [mime] gives sound back for the first samples [extractor] has. */
    private fun decodes(extractor: MediaExtractor, format: MediaFormat, mime: String): Boolean {
        val codec = MediaCodec.createDecoderByType(mime)
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var ended = false
            repeat(400) {
                if (!ended) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val input = codec.getInputBuffer(inIndex) ?: return false
                        val read = extractor.readSampleData(input, 0)
                        if (read < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            ended = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, read, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outIndex >= 0) {
                    val got = info.size > 0
                    codec.releaseOutputBuffer(outIndex, false)
                    if (got) return true
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return false
                }
            }
            return false
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
    }

    /**
     * Writes [tags] into the MP4 file [file], in place. The tag library's usual way in reads
     * the audio first and gives up on Opus, so the MP4 tag reader and writer are used directly.
     */
    fun writeTags(file: File, tags: Tags): Boolean = runCatching {
        TagOptionSingleton.getInstance().isAndroid = true
        val path = file.toPath()
        val tag = Mp4TagReader().read(path)
        tag.put(FieldKey.TITLE, tags.title)
        tag.put(FieldKey.ARTIST, tags.artist)
        tag.put(FieldKey.ALBUM, tags.album)
        tag.put(FieldKey.ALBUM_ARTIST, tags.albumArtist)
        tag.put(FieldKey.TRACK, tags.trackNumber?.toString())
        tag.put(FieldKey.DISC_NO, tags.discNumber?.toString())
        tag.put(FieldKey.COMMENT, tags.videoId?.let(::videoAddress))
        runCatching { tag.deleteArtworkField() }
        Mp4TagWriter(file.name).write(tag, path)
        Mp4TagReader().read(path).getFirst(FieldKey.TITLE) == tags.title
    }.onFailure { Log.w(TAG, "could not tag ${file.name}: ${it.message}") }.getOrDefault(false)

    /** A file's tags, an MP4 one's read the way [writeTags] writes them. */
    fun readTag(file: File): Tag? = runCatching {
        TagOptionSingleton.getInstance().isAndroid = true
        if (PublicDownloads.sniffMimeType(file) == "audio/mp4") Mp4TagReader().read(file.toPath())
        else AudioFileIO.read(file).tag
    }.getOrNull()

    private fun Tag.put(key: FieldKey, value: String?) {
        if (!value.isNullOrBlank()) setField(key, value)
    }

    private fun MediaFormat.durationUs(): Long? =
        if (containsKey(MediaFormat.KEY_DURATION)) getLong(MediaFormat.KEY_DURATION).takeIf { it > 0 } else null
}
