package com.wanderwildwood.jimeikin.data

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Reads the two things the app needs to know about an MP4 file without reading the file: what
 * its audio is (the first track's sample entry, "mp4a" for AAC or "Opus") and its comment tag,
 * where a YouTube download names its video.
 *
 * The tag library reads a whole file by its path. A download written before a reinstall is
 * reached through the folder the reader chose, by a content:// address with no path, and the
 * tag library would need every one copied out first; this walks the box headers in place,
 * jumping over the audio, and reads only the index at the top or foot of the file.
 *
 * Buffers are moved as java.nio.Buffer, here and in [YouTubeAudioFile]: built against a newer
 * Android, ByteBuffer's own clear, flip and position are named, and older Android lacks them.
 */
object Mp4Boxes {

    data class Info(
        /** The first track's sample entry: "mp4a" for AAC, "Opus", and so on. */
        val codec: String?,
        /** The comment tag (©cmt), as [YouTubeAudioFile.writeTags] writes it. */
        val comment: String?,
    )

    // Boxes that hold only other boxes, on the way down to what is read.
    private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl", "udta", "ilst")
    private const val COMMENT = "©cmt"
    private const val MAX_BOXES = 4096

    fun read(file: File): Info? = runCatching {
        RandomAccessFile(file, "r").use { read(it.channel) }
    }.getOrNull()

    fun read(context: Context, uri: Uri): Info? = runCatching {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
        // The stream owns the descriptor and closes it, so it is not closed twice.
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { read(it.channel) }
    }.getOrNull()

    /** Null when the file is not an MP4 file or its boxes do not add up. */
    fun read(channel: FileChannel): Info? {
        val end = channel.size()
        val head = ByteBuffer.allocate(16)
        if (end < 8) return null
        if (type(channel, 0, head) != "ftyp") return null
        var codec: String? = null
        var comment: String? = null
        var count = 0

        fun walk(start: Long, stop: Long, parent: String?) {
            var at = start
            while (at + 8 <= stop && count++ < MAX_BOXES) {
                (head as java.nio.Buffer).clear().limit(8)
                if (channel.read(head, at) < 8) return
                (head as java.nio.Buffer).flip()
                var size = head.int.toLong() and 0xffffffffL
                val type = fourcc(head)
                var body = at + 8
                when (size) {
                    0L -> size = stop - at
                    1L -> {
                        (head as java.nio.Buffer).clear().limit(8)
                        if (channel.read(head, body) < 8) return
                        (head as java.nio.Buffer).flip()
                        size = head.long
                        body += 8
                    }
                }
                if (size < body - at || at + size > stop) return
                val boxEnd = at + size
                when {
                    type in CONTAINERS -> {
                        // Only the first track's sample entry is wanted; an audio file has one.
                        if (!(type == "trak" && codec != null)) walk(body, boxEnd, type)
                    }
                    type == "meta" -> {
                        // ISO's meta carries a version and flags before its boxes; QuickTime's
                        // does not. Told apart by whether a box type follows at once.
                        val inner = if (type(channel, body, head) == "hdlr") body else body + 4
                        walk(inner, boxEnd, type)
                    }
                    type == "stsd" && codec == null -> {
                        // Version and flags, the entry count, then the first entry's size and kind.
                        (head as java.nio.Buffer).clear().limit(16)
                        if (channel.read(head, body) == 16) {
                            (head as java.nio.Buffer).flip()
                            (head as java.nio.Buffer).position(12)
                            codec = fourcc(head)
                        }
                    }
                    type == COMMENT && parent == "ilst" && comment == null -> comment = text(channel, body, boxEnd)
                }
                at = boxEnd
            }
        }

        walk(0, end, null)
        return Info(codec, comment)
    }

    /** The text of an ilst item: its "data" box, past the type and locale. */
    private fun text(channel: FileChannel, start: Long, stop: Long): String? {
        val head = ByteBuffer.allocate(16)
        (head as java.nio.Buffer).limit(16)
        if (channel.read(head, start) < 16) return null
        (head as java.nio.Buffer).flip()
        val size = head.int.toLong() and 0xffffffffL
        if (fourcc(head) != "data" || size < 16 || start + size > stop || size > 64 * 1024) return null
        val body = ByteBuffer.allocate((size - 16).toInt())
        channel.read(body, start + 16)
        return String(body.array(), 0, body.position(), Charsets.UTF_8)
    }

    private fun type(channel: FileChannel, at: Long, buffer: ByteBuffer): String? {
        (buffer as java.nio.Buffer).clear().limit(8)
        if (channel.read(buffer, at) < 8) return null
        (buffer as java.nio.Buffer).flip()
        (buffer as java.nio.Buffer).position(4)
        return fourcc(buffer)
    }

    private fun fourcc(buffer: ByteBuffer): String {
        val bytes = ByteArray(4)
        buffer.get(bytes)
        return String(bytes, Charsets.ISO_8859_1)
    }
}
