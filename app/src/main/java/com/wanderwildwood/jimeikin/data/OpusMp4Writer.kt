package com.wanderwildwood.jimeikin.data

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * Writes Opus packets into an MP4 file, as "Opus in ISO Base Media File Format" lays it out,
 * without decoding or encoding anything.
 *
 * Android's own MP4 writer takes Opus only on newer phones (Android 11 refuses it), so a
 * YouTube download in WebM could not be made an MP4 file there. This writes the few boxes a
 * single audio track needs: the samples go into one run in the file, followed by the index of
 * where each one is and how long it plays. Each packet's length is read from its own first
 * byte, at 48 kHz, which is exact; WebM's timestamps are rounded to the millisecond.
 */
class OpusMp4Writer(private val out: File, opusHead: ByteArray) {

    private val head = OpusHead.parse(opusHead)
    private val file = RandomAccessFile(out, "rw")
    private val sizes = ArrayList<Int>()
    private val durations = ArrayList<Int>()
    private val dataStart: Long

    init {
        file.setLength(0)
        file.write(box("ftyp") {
            writeBytes("isom")
            writeInt(512)
            writeBytes("isom"); writeBytes("iso2"); writeBytes("mp41"); writeBytes("Opus")
        })
        // The data box's size is filled in once every sample is in.
        file.writeInt(0)
        file.writeBytes("mdat")
        dataStart = file.filePointer
    }

    /** Adds one Opus packet. */
    fun write(packet: ByteArray, length: Int = packet.size) {
        require(length > 0) { "empty packet" }
        file.write(packet, 0, length)
        sizes += length
        durations += packetSamples(packet)
    }

    /** Finishes the file. Throws if there were no packets or the file could not be written. */
    fun finish() {
        try {
            check(sizes.isNotEmpty()) { "no packets" }
            val dataEnd = file.filePointer
            val dataSize = dataEnd - dataStart + 8
            check(dataSize <= 0xffffffffL) { "too large" }
            file.seek(dataStart - 8)
            file.writeInt(dataSize.toInt())
            file.seek(dataEnd)
            file.write(moov())
        } finally {
            file.close()
        }
    }

    /** Closes the file without finishing it, for a copy given up part way. */
    fun abandon() {
        runCatching { file.close() }
    }

    /** Samples at 48 kHz, as written, before the decoder's pre-skip is taken off. */
    val totalSamples: Long get() = durations.sumOf { it.toLong() }

    private fun moov(): ByteArray {
        val total = totalSamples
        val playable = (total - head.preSkip).coerceAtLeast(0)
        return box("moov") {
            write(box("mvhd") {
                writeInt(0) // version 0, flags
                writeInt(0); writeInt(0) // created, modified
                writeInt(TIMESCALE)
                writeInt(playable.toInt())
                writeInt(0x00010000) // rate 1.0
                writeShort(0x0100) // volume 1.0
                write(ByteArray(10))
                writeMatrix()
                write(ByteArray(24))
                writeInt(2) // next track id
            })
            write(box("trak") {
                write(box("tkhd") {
                    writeInt(0x00000003) // version 0, enabled and in the movie
                    writeInt(0); writeInt(0)
                    writeInt(1) // track id
                    writeInt(0)
                    writeInt(playable.toInt())
                    write(ByteArray(8))
                    writeShort(0); writeShort(0) // layer, alternate group
                    writeShort(0x0100) // volume
                    writeShort(0)
                    writeMatrix()
                    writeInt(0); writeInt(0) // width, height
                })
                // The pre-skip is the decoder's start-up, not the song: the edit list starts
                // playing after it, so the file is as long as the song.
                write(box("edts") {
                    write(box("elst") {
                        writeInt(0)
                        writeInt(1)
                        writeInt(playable.toInt())
                        writeInt(head.preSkip)
                        writeInt(0x00010000)
                    })
                })
                write(box("mdia") {
                    write(box("mdhd") {
                        writeInt(0)
                        writeInt(0); writeInt(0)
                        writeInt(TIMESCALE)
                        writeInt(total.toInt())
                        writeShort(0x55c4) // "und"
                        writeShort(0)
                    })
                    write(box("hdlr") {
                        writeInt(0)
                        writeInt(0)
                        writeBytes("soun")
                        write(ByteArray(12))
                        writeBytes("SoundHandler"); writeByte(0)
                    })
                    write(box("minf") {
                        write(box("smhd") { writeInt(0); writeInt(0) })
                        write(box("dinf") {
                            write(box("dref") {
                                writeInt(0)
                                writeInt(1)
                                write(box("url ") { writeInt(1) })
                            })
                        })
                        write(stbl())
                    })
                })
            })
        }
    }

    private fun stbl(): ByteArray = box("stbl") {
        write(box("stsd") {
            writeInt(0)
            writeInt(1)
            write(box("Opus") {
                write(ByteArray(6))
                writeShort(1) // data reference
                write(ByteArray(8))
                writeShort(head.channels)
                writeShort(16)
                writeShort(0); writeShort(0)
                writeInt(TIMESCALE shl 16)
                write(box("dOps") {
                    writeByte(0)
                    writeByte(head.channels)
                    writeShort(head.preSkip)
                    writeInt(head.inputRate.toInt())
                    writeShort(head.gain)
                    writeByte(head.mappingFamily)
                    if (head.mappingFamily != 0) write(head.mapping)
                })
            })
        })
        write(box("stts") {
            val runs = ArrayList<Pair<Int, Int>>()
            for (d in durations) {
                if (runs.isNotEmpty() && runs.last().second == d) runs[runs.size - 1] = runs.last().first + 1 to d
                else runs += 1 to d
            }
            writeInt(0)
            writeInt(runs.size)
            runs.forEach { (count, delta) -> writeInt(count); writeInt(delta) }
        })
        write(box("stsc") {
            writeInt(0)
            writeInt(1)
            writeInt(1); writeInt(sizes.size); writeInt(1)
        })
        write(box("stsz") {
            writeInt(0)
            writeInt(0)
            writeInt(sizes.size)
            sizes.forEach { writeInt(it) }
        })
        write(box("stco") {
            writeInt(0)
            writeInt(1)
            writeInt(dataStart.toInt())
        })
    }

    private fun DataOutputStream.writeMatrix() {
        intArrayOf(0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000).forEach { writeInt(it) }
    }

    /** What the WebM's OpusHead says, which MP4 keeps in its dOps box (big-endian there). */
    data class OpusHead(
        val channels: Int,
        val preSkip: Int,
        val inputRate: Long,
        val gain: Int,
        val mappingFamily: Int,
        val mapping: ByteArray,
    ) {
        companion object {
            fun parse(bytes: ByteArray): OpusHead {
                require(bytes.size >= 19 && String(bytes, 0, 8, Charsets.US_ASCII) == "OpusHead") { "not an OpusHead" }
                fun u8(i: Int) = bytes[i].toInt() and 0xff
                fun u16(i: Int) = u8(i) or (u8(i + 1) shl 8)
                val family = u8(18)
                val mapping = if (family != 0) {
                    val channels = u8(9)
                    require(bytes.size >= 21 + channels) { "short OpusHead" }
                    bytes.copyOfRange(19, 21 + channels)
                } else {
                    ByteArray(0)
                }
                return OpusHead(
                    channels = u8(9),
                    preSkip = u16(10),
                    inputRate = (u16(12).toLong() or (u16(14).toLong() shl 16)),
                    gain = u16(16),
                    mappingFamily = family,
                    mapping = mapping,
                )
            }
        }
    }

    companion object {
        const val TIMESCALE = 48000

        /** How many 48 kHz samples an Opus packet plays, from its first byte (RFC 6716, 3.1). */
        fun packetSamples(packet: ByteArray): Int {
            val toc = packet[0].toInt() and 0xff
            val config = toc shr 3
            val frame = when {
                config < 12 -> intArrayOf(480, 960, 1920, 2880)[config and 3]
                config < 16 -> intArrayOf(480, 960)[config and 1]
                else -> intArrayOf(120, 240, 480, 960)[config and 3]
            }
            val frames = when (toc and 3) {
                0 -> 1
                3 -> if (packet.size > 1) packet[1].toInt() and 0x3f else 1
                else -> 2
            }
            return frame * frames
        }

        private fun box(type: String, body: DataOutputStream.() -> Unit): ByteArray {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use(body)
            val content = bytes.toByteArray()
            val outBytes = ByteArrayOutputStream(content.size + 8)
            DataOutputStream(outBytes).use {
                it.writeInt(content.size + 8)
                it.writeBytes(type)
                it.write(content)
            }
            return outBytes.toByteArray()
        }
    }
}
