package com.wanderwildwood.jimeikin.data

import org.jaudiotagger.audio.mp4.Mp4TagReader
import org.jaudiotagger.audio.mp4.Mp4TagWriter
import org.jaudiotagger.tag.FieldKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class Mp4BoxesTest {

    // OpusHead: stereo, pre-skip 312, 48 kHz input, no gain, mapping family 0.
    private val head = byteArrayOf(
        'O'.code.toByte(), 'p'.code.toByte(), 'u'.code.toByte(), 's'.code.toByte(),
        'H'.code.toByte(), 'e'.code.toByte(), 'a'.code.toByte(), 'd'.code.toByte(),
        1, 2, 0x38, 0x01, 0x80.toByte(), 0xbb.toByte(), 0, 0, 0, 0, 0,
    )

    private fun opusFile(): File {
        val out = File.createTempFile("boxes", ".m4a")
        val writer = OpusMp4Writer(out, head)
        repeat(50) { writer.write(byteArrayOf((31 shl 3).toByte(), it.toByte(), 7)) }
        writer.finish()
        return out
    }

    @Test
    fun anOpusFileSaysSoAndHasNoComment() {
        val file = opusFile()
        try {
            val info = Mp4Boxes.read(file)!!
            assertEquals("Opus", info.codec)
            assertNull(info.comment)
        } finally {
            file.delete()
        }
    }

    @Test
    fun theCommentADownloadIsTaggedWithIsRead() {
        val file = opusFile()
        try {
            val path = file.toPath()
            val tag = Mp4TagReader().read(path)
            tag.setField(FieldKey.TITLE, "Lantern Road")
            tag.setField(FieldKey.ARTIST, "Mara Quill")
            tag.setField(FieldKey.COMMENT, "https://music.youtube.com/watch?v=abcDEF12345")
            Mp4TagWriter(file.name).write(tag, path)

            val info = Mp4Boxes.read(file)!!
            assertEquals("Opus", info.codec)
            assertEquals("https://music.youtube.com/watch?v=abcDEF12345", info.comment)
        } finally {
            file.delete()
        }
    }

    @Test
    fun anythingButMp4IsNotRead() {
        val file = File.createTempFile("boxes", ".webm")
        try {
            file.writeBytes(byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte()) + ByteArray(60))
            assertNull(Mp4Boxes.read(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun onlyFilesDirectlyInMusicBoxAreLookedAt() {
        // Environment.DIRECTORY_MUSIC, which is not there off a phone.
        fun isInMusicBox(id: String) = DownloadRecognition.isInMusicBox(id, "Music/Music Box/")
        assertEquals(true, isInMusicBox("primary:Music/Music Box/Lantern Road.m4a"))
        assertEquals(true, isInMusicBox("1A2B-3C4D:Music/Music Box/Song (1).m4a"))
        assertEquals(false, isInMusicBox("primary:Music/Lantern Road.m4a"))
        assertEquals(false, isInMusicBox("primary:Music/Music Box/Old/Lantern Road.m4a"))
        assertEquals(false, isInMusicBox("primary:Download/Music Box/Lantern Road.m4a"))
    }
}
