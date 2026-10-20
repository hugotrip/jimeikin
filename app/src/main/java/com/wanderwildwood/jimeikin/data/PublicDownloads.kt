package com.wanderwildwood.jimeikin.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Where downloads go: the phone's own Music folder, in "Music/Music Box", or the same folder on
 * the memory card when there is one.
 *
 * They used to live in the app's private folder, which Android deletes with the app and which
 * no file manager or other player can see. Written through MediaStore instead, they are
 * ordinary music files: they outlast the app, and anything that plays music finds them. A file
 * the app wrote is its own to read and delete without asking for any permission, from
 * Android 11 (API 30); before that, writing there needs a storage permission this app does
 * not hold, so on Android 9 and 10 downloads stay in the private folder as before.
 *
 * Rows go on naming the file by its path (file://), as they always did, so playback, the
 * player's notification, Glance and everything else that reads a download see no change.
 */
object PublicDownloads {

    private const val TAG = "PublicDownloads"

    /** The folder under Music, on whichever storage it is on. */
    const val FOLDER = "Music Box"

    val relativePath: String = Environment.DIRECTORY_MUSIC + "/" + FOLDER + "/"

    /** Whether new downloads go to the public folder at all; see the class comment. */
    val isAvailable: Boolean get() = Build.VERSION.SDK_INT >= 30

    data class Saved(val file: File, val uri: Uri)

    /**
     * The memory card if one is in and readable, and the phone's own storage otherwise. A
     * card is where a phone like this keeps its music, and where there is room for it.
     */
    @RequiresApi(30)
    fun volume(context: Context): String {
        val known = runCatching { MediaStore.getExternalVolumeNames(context) }.getOrDefault(emptySet())
        val storage = context.getSystemService(StorageManager::class.java)
        val card = runCatching {
            storage?.storageVolumes.orEmpty().firstOrNull { volume ->
                volume.isRemovable &&
                    volume.state == Environment.MEDIA_MOUNTED &&
                    volume.mediaStoreVolumeName?.let { it in known } == true
            }?.mediaStoreVolumeName
        }.getOrNull()
        return card ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
    }

    /** Whether [path] is a file in a Music Box folder, on any storage. */
    fun isPublic(path: String?): Boolean =
        path != null && path.contains("/" + relativePath)

    /** The file a row's audio uri names, when it is a file:// path. */
    fun fileOf(audioUri: String?): File? {
        val uri = audioUri?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return null
        if (uri.scheme != null && uri.scheme != "file") return null
        return uri.path?.let(::File)
    }

    /**
     * Copies [source] into the Music Box folder as [displayName], and checks the copy is the
     * same file - its size and its sha256, read back - before it is let out of pending. A copy
     * that fails anywhere is removed again and this throws; [source] is never touched.
     *
     * MediaStore numbers the name, "Song (1).m4a", when the folder already has one.
     */
    @RequiresApi(30)
    fun save(context: Context, source: File, displayName: String, mimeType: String): Saved {
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(volume(context))
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore would not take $displayName")
        try {
            val expected = sha256(source)
            val size = source.length()
            resolver.openOutputStream(uri, "w")?.use { out ->
                source.inputStream().use { it.copyTo(out, 64 * 1024) }
            } ?: throw IOException("could not open $displayName for writing")
            val written = resolver.openInputStream(uri)?.use { sha256(it) }
                ?: throw IOException("could not read $displayName back")
            if (written.second != size || written.first != expected.first) {
                throw IOException("the copy of $displayName is not the same file")
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            val file = pathOf(context, uri)?.let(::File) ?: throw IOException("no path for $displayName")
            if (!file.exists() || file.length() != size) throw IOException("$file is not where MediaStore says")
            return Saved(file, uri)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    /**
     * Gives the saved file [name] in place of the one MediaStore chose for it. Used when a
     * download replaces an earlier copy of the same song: the new copy is written beside it,
     * the old one goes, and the new one takes its name, so the song keeps its id. Null if the
     * rename was refused, in which case the file is still where it was.
     */
    @RequiresApi(30)
    fun rename(context: Context, saved: Saved, name: String): Saved? = runCatching {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name) }
        if (context.contentResolver.update(saved.uri, values, null, null) <= 0) return null
        val file = pathOf(context, saved.uri)?.let(::File) ?: return null
        if (file.name != name || !file.exists()) null else Saved(file, saved.uri)
    }.getOrNull()

    /** [rename] for a file found by its path; see there. */
    @RequiresApi(30)
    fun rename(context: Context, file: File, name: String): File? {
        val uri = uriOf(context, file) ?: return null
        return rename(context, Saved(file, uri), name)?.file
    }

    sealed class Deletion {
        /** The file is gone, or was never there. */
        object Done : Deletion()

        /**
         * The file is not this app's - it was written before a reinstall - and Android asks
         * the reader first. Launching [request] shows that question; the file goes on yes.
         */
        data class NeedsConsent(val request: IntentSender, val uri: Uri) : Deletion()

        /** It could not be deleted and there is no one to ask. The file is still there. */
        object Failed : Deletion()
    }

    /**
     * Deletes a download's file. A file in the private folder is simply deleted. One in the
     * public folder is deleted through MediaStore, which takes its entry with it; if it is
     * not this app's to delete, Android's own question is handed back to be asked.
     */
    fun delete(context: Context, file: File): Deletion {
        if (!file.exists()) return Deletion.Done
        if (Build.VERSION.SDK_INT < 30 || !isPublic(file.path)) {
            return if (file.delete() || !file.exists()) Deletion.Done else Deletion.Failed
        }
        val uri = uriOf(context, file)
        if (uri != null) {
            try {
                if (context.contentResolver.delete(uri, null, null) > 0 || !file.exists()) return Deletion.Done
            } catch (e: SecurityException) {
                // RecoverableSecurityException among them: a file another install wrote.
                return consent(context, uri)
            }
        }
        if (file.delete() || !file.exists()) return Deletion.Done
        return uri?.let { consent(context, it) } ?: Deletion.Failed
    }

    private fun consent(context: Context, uri: Uri): Deletion = runCatching {
        if (Build.VERSION.SDK_INT < 30) return Deletion.Failed
        Deletion.NeedsConsent(MediaStore.createDeleteRequest(context.contentResolver, listOf(uri)).intentSender, uri)
    }.getOrElse {
        Log.w(TAG, "could not ask to delete $uri", it)
        Deletion.Failed
    }

    /** One question for several files at once; see [Deletion.NeedsConsent]. */
    fun consentFor(context: Context, uris: List<Uri>): IntentSender? = runCatching {
        if (Build.VERSION.SDK_INT < 30 || uris.isEmpty()) return null
        MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender
    }.getOrNull()

    /**
     * Every file in a Music Box folder that this app can see: its own always, and the rest
     * only where it has been allowed to read music files. Pending files are left out.
     */
    fun listAll(context: Context): List<File> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        val found = mutableListOf<File>()
        val volumes = runCatching { MediaStore.getExternalVolumeNames(context) }.getOrDefault(emptySet())
        for (volume in volumes) {
            runCatching {
                context.contentResolver.query(
                    MediaStore.Audio.Media.getContentUri(volume),
                    arrayOf(MediaStore.MediaColumns.DATA),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                    arrayOf(relativePath),
                    null,
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        cursor.getString(0)?.let { found += File(it) }
                    }
                }
            }.onFailure { Log.w(TAG, "could not list $volume", it) }
        }
        return found.filter { it.isFile }
    }

    /**
     * The document id the phone's file picker gives [file] - "primary:Music/Music Box/x.m4a",
     * or the card's id in place of "primary" - which is how a folder chosen in Settings names
     * it. Null for a path outside shared storage.
     */
    fun documentIdOf(file: File): String? {
        val path = file.absolutePath
        val primary = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/') + "/"
        if (path.startsWith(primary)) return "primary:" + path.removePrefix(primary)
        val match = Regex("^/storage/([^/]+)/(.+)$").find(path) ?: return null
        val (volume, rest) = match.destructured
        if (volume == "emulated" || volume == "self") return null
        return "$volume:$rest"
    }

    /**
     * Whether this install wrote [file] and may replace it without asking. A file in the
     * private folder always is; one in Music Box only if MediaStore names this app as its
     * owner, which it does not for a file written before a reinstall.
     */
    fun isOwn(context: Context, file: File): Boolean {
        if (Build.VERSION.SDK_INT < 30 || !isPublic(file.path)) return true
        val uri = uriOf(context, file) ?: return false
        return runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null } == context.packageName
        }.getOrDefault(false)
    }

    private fun pathOf(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }

    private fun uriOf(context: Context, file: File): Uri? {
        val volumes = runCatching { MediaStore.getExternalVolumeNames(context) }.getOrDefault(emptySet())
        for (volume in volumes) {
            val collection = MediaStore.Audio.Media.getContentUri(volume)
            val id = runCatching {
                context.contentResolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.DATA} = ?",
                    arrayOf(file.absolutePath),
                    null,
                )?.use { if (it.moveToFirst()) it.getLong(0) else null }
            }.getOrNull()
            if (id != null) return ContentUris.withAppendedId(collection, id)
        }
        return null
    }

    /** The mime type a file of this name is saved with; MediaStore files it by this. */
    fun mimeTypeOf(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "m4a", "mp4" -> "audio/mp4"
        "aac" -> "audio/aac"
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "ogg", "oga" -> "audio/ogg"
        "opus" -> "audio/opus"
        "wav" -> "audio/x-wav"
        "webm" -> "audio/webm"
        else -> null
    }

    /**
     * What a file really is, from its first bytes, for a download whose name does not say (a
     * kept server song was "<id>.audio"). The few kinds a server sends are told by their first
     * bytes, which needs no decoder and no name to go by.
     */
    fun sniffMimeType(file: File): String? = runCatching {
        val head = ByteArray(64)
        val read = file.inputStream().use { it.read(head) }
        if (read < 12) return null
        fun at(offset: Int, text: String) = text.indices.all { head[offset + it] == text[it].code.toByte() }
        val b0 = head[0].toInt() and 0xff
        val b1 = head[1].toInt() and 0xff
        when {
            at(0, "ID3") -> "audio/mpeg"
            b0 == 0xff && (b1 and 0xf6) == 0xf0 -> "audio/aac" // ADTS
            b0 == 0xff && (b1 and 0xe0) == 0xe0 -> "audio/mpeg"
            at(0, "fLaC") -> "audio/flac"
            at(0, "OggS") -> if (String(head, Charsets.ISO_8859_1).contains("OpusHead")) "audio/opus" else "audio/ogg"
            at(4, "ftyp") -> "audio/mp4"
            at(0, "RIFF") && at(8, "WAVE") -> "audio/x-wav"
            b0 == 0x1a && b1 == 0x45 && (head[2].toInt() and 0xff) == 0xdf && (head[3].toInt() and 0xff) == 0xa3 -> "audio/webm"
            else -> null
        }
    }.getOrNull()

    /** The extension for an audio mime type, for a download whose name does not say. */
    fun extensionOf(mimeType: String?): String? = when (mimeType?.substringBefore(';')?.trim()?.lowercase()) {
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a"
        "audio/aac", "audio/aacp" -> "aac"
        "audio/flac", "audio/x-flac" -> "flac"
        "audio/ogg", "application/ogg", "audio/vorbis" -> "ogg"
        "audio/opus" -> "opus"
        "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
        "audio/webm" -> "webm"
        else -> null
    }

    /** A name a filesystem will take. */
    fun safeName(text: String): String =
        text.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").trim().trimStart('.').take(120)

    fun sha256(file: File): Pair<String, Long> = file.inputStream().use { sha256(it) }

    private fun sha256(input: java.io.InputStream): Pair<String, Long> {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            digest.update(buffer, 0, read)
            total += read
        }
        return digest.digest().joinToString("") { "%02x".format(it) } to total
    }
}
