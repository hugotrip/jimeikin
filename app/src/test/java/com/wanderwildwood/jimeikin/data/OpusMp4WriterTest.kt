package com.wanderwildwood.jimeikin.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OpusMp4WriterTest {

    // OpusHead: stereo, pre-skip 312, 48 kHz input, no gain, mapping family 0.
    private val head = byteArrayOf(
        'O'.code.toByte(), 'p'.code.toByte(), 'u'.code.toByte(), 's'.code.toByte(),
        'H'.code.toByte(), 'e'.code.toByte(), 'a'.code.toByte(), 'd'.code.toByte(),
        1, 2, 0x38, 0x01, 0x80.toByte(), 0xbb.toByte(), 0, 0, 0, 0, 0,
    )

    @Test
    fun packetLengthsComeFromTheirFirstByte() {
        // CELT 20 ms, one frame: config 31, code 0.
        assertEquals(960, OpusMp4Writer.packetSamples(byteArrayOf((31 shl 3).toByte())))
        // SILK 60 ms, two frames.
        assertEquals(5760, OpusMp4Writer.packetSamples(byteArrayOf(((3 shl 3) or 1).toByte())))
        // CELT 2.5 ms, code 3 with five frames.
        assertEquals(600, OpusMp4Writer.packetSamples(byteArrayOf(((16 shl 3) or 3).toByte(), 5)))
    }

    @Test
    fun opusHeadIsRead() {
        val parsed = OpusMp4Writer.OpusHead.parse(head)
        assertEquals(2, parsed.channels)
        assertEquals(312, parsed.preSkip)
        assertEquals(48000L, parsed.inputRate)
        assertEquals(0, parsed.mappingFamily)
    }

    @Test
    fun theFileHoldsEveryPacketAndItsIndex() {
        val out = File.createTempFile("opus", ".m4a")
        try {
            val writer = OpusMp4Writer(out, head)
            repeat(50) { writer.write(byteArrayOf((31 shl 3).toByte(), it.toByte(), 7)) }
            writer.finish()
            val bytes = out.readBytes()
            val text = String(bytes, Charsets.ISO_8859_1)
            assertEquals("ftyp", text.substring(4, 8))
            for (box in listOf("mdat", "moov", "mvhd", "trak", "elst", "mdhd", "stsd", "Opus", "dOps", "stts", "stsz", "stco")) {
                assertTrue("no $box", text.contains(box))
            }
            // mdat holds 50 packets of 3 bytes.
            val mdat = text.indexOf("mdat") - 4
            val size = ((bytes[mdat].toInt() and 0xff) shl 24) or ((bytes[mdat + 1].toInt() and 0xff) shl 16) or
                ((bytes[mdat + 2].toInt() and 0xff) shl 8) or (bytes[mdat + 3].toInt() and 0xff)
            assertEquals(8 + 150, size)
            assertEquals(50L * 960, writer.totalSamples)
        } finally {
            out.delete()
        }
    }
}
