package com.wanderwildwood.jimeikin.data

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The fallback for a video YouTube has no AAC for: an Opus download, decoded and encoded again
 * as AAC. The Opus is made here, three seconds of a tone, in WebM as the oldest downloads are
 * and in MP4 as 2.18.1 copied them.
 */
@RunWith(AndroidJUnit4::class)
class TranscodeTest {

    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "transcode-test")

    @Before
    fun setUp() {
        dir.deleteRecursively()
        dir.mkdirs()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun opusInWebmBecomesAac() {
        val webm = File(dir, "tone.webm")
        val sourceUs = makeOpusWebm(webm)
        assertTrue(YouTubeAudioFile.isOpus(webm))
        check(webm, sourceUs)
    }

    @Test
    fun opusInMp4BecomesAac() {
        val webm = File(dir, "tone.webm")
        val sourceUs = makeOpusWebm(webm)
        val mp4 = File(dir, "tone-opus.m4a")
        assertNotNull(YouTubeAudioFile.remux(webm, mp4))
        assertEquals("Opus", Mp4Boxes.read(mp4)?.codec)
        assertTrue(YouTubeAudioFile.isOpus(mp4))
        check(mp4, sourceUs)
    }

    private fun check(source: File, sourceUs: Long) {
        val aac = File(dir, "tone-aac.m4a")
        val read = YouTubeAudioFile.transcodeToAac({ it.setDataSource(source.path) }, aac)
        assertNotNull(read)
        assertTrue("within a second of the original", abs(read!! - sourceUs) <= 1_000_000L)
        assertEquals("mp4a", Mp4Boxes.read(aac)?.codec)
        assertFalse(YouTubeAudioFile.isOpus(aac))
        val tags = YouTubeAudioFile.Tags("A Tone", "Nobody In Particular", "Test Signals", null, 1, 1, "abcDEF12345")
        assertTrue(YouTubeAudioFile.writeTags(aac, tags))
        assertEquals("https://music.youtube.com/watch?v=abcDEF12345", Mp4Boxes.read(aac)?.comment)
        assertTrue(YouTubeAudioFile.verify(aac, sourceUs, MediaFormat.MIMETYPE_AUDIO_AAC))
    }

    /** Three seconds of 440 Hz, stereo at 48 kHz, encoded as Opus into WebM. Its length in microseconds. */
    private fun makeOpusWebm(out: File): Long {
        val rate = 48_000
        val channels = 2
        val seconds = 3
        val pcm = ByteArray(rate * seconds * channels * 2)
        for (i in 0 until rate * seconds) {
            val v = (sin(2 * PI * 440 * i / rate) * 8000).toInt()
            for (c in 0 until channels) {
                val at = (i * channels + c) * 2
                pcm[at] = (v and 0xff).toByte()
                pcm[at + 1] = ((v shr 8) and 0xff).toByte()
            }
        }
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, rate, channels).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM)
        val info = MediaCodec.BufferInfo()
        var at = 0
        var track = -1
        var inputDone = false
        var lastUs = 0L
        while (true) {
            if (!inputDone) {
                val index = encoder.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    val input = encoder.getInputBuffer(index)!!
                    input.clear()
                    val length = minOf(input.remaining() / 4 * 4, pcm.size - at)
                    val timeUs = at / 4 * 1_000_000L / rate
                    if (length <= 0) {
                        encoder.queueInputBuffer(index, 0, 0, timeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        input.put(pcm, at, length)
                        encoder.queueInputBuffer(index, 0, length, timeUs, 0)
                        at += length
                    }
                }
            }
            val index = encoder.dequeueOutputBuffer(info, 10_000)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track = muxer.addTrack(encoder.outputFormat)
                muxer.start()
            } else if (index >= 0) {
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && track >= 0) {
                    muxer.writeSampleData(track, encoder.getOutputBuffer(index)!!, info)
                    lastUs = maxOf(lastUs, info.presentationTimeUs)
                }
                encoder.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
        encoder.stop()
        encoder.release()
        muxer.stop()
        muxer.release()
        return seconds * 1_000_000L
    }
}
