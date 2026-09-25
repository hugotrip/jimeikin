package com.wanderwildwood.jimeikin.data

import android.content.Context
import android.os.Environment
import com.wanderwildwood.jimeikin.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Keeps a song from a music server on the phone, so it plays away from the network.
 *
 * The file lands in the app's own music folder, and the song's row changes source: it stops
 * being a pointer to the server and becomes a file, which is what makes its rule solid and
 * what lets it play with the wifi off. It is still the same row — the library does not gain a
 * second copy — and forgetting the server or a later sync leaves it alone.
 */
object SubsonicDownloader {

    const val SOURCE_TYPE = "SUBSONIC_DOWNLOAD"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** Where kept songs live: the app's own folder, so removing the app removes them too. */
    private fun folder(context: Context): File? =
        context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)?.resolve("server")?.apply { mkdirs() }

    fun fileFor(context: Context, songRowId: String): File? {
        // The row id is namespaced and can carry characters a filesystem will not take.
        val safe = songRowId.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        return folder(context)?.resolve("$safe.audio")
    }

    /**
     * Fetches one song. Downloads to a temporary name and moves it into place only once it is
     * whole, so an interrupted download cannot leave a file that looks playable and is not.
     */
    suspend fun download(
        context: Context,
        song: SongEntity,
        onProgress: (Float) -> Unit = {},
    ): SubsonicResult<File> = withContext(Dispatchers.IO) {
        val target = fileFor(context, song.id)
            ?: return@withContext SubsonicResult.Failure(context.getString(R.string.service_subsonic_download_no_storage))
        // Where the song streams from, kept beside its copy so removing the copy can put the
        // song back as it was. Keeping rewrites the row to point at the file, and nothing else
        // remembers the address.
        if (song.sourceType == SubsonicSync.SOURCE_TYPE) {
            runCatching { File(target.path + STREAM_SUFFIX).writeText(song.audioUri) }
        }
        if (target.exists() && target.length() > 0) return@withContext SubsonicResult.Success(target)

        val partial = File(target.absolutePath + ".part")
        try {
            // The row already carries the url playback would use, which is the file
            // untouched where this phone can decode it and a transcode where it cannot.
            // Fetching that exact url is what makes the kept copy play the same way.
            val request = Request.Builder().url(song.audioUri).build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext SubsonicResult.Failure(context.getString(R.string.service_subsonic_download_server_answered, response.code))
                }
                val body = response.body
                    ?: return@withContext SubsonicResult.Failure(context.getString(R.string.service_subsonic_download_empty))
                val total = body.contentLength()
                var written = 0L
                var lastPercent = -1
                partial.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            out.write(buffer, 0, read)
                            written += read
                            if (total > 0) {
                                // A whole percent at a time; this panel cannot show more.
                                val percent = ((written * 100) / total).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress(percent / 100f)
                                }
                            }
                        }
                    }
                }
            }
            if (!partial.renameTo(target)) {
                partial.delete()
                return@withContext SubsonicResult.Failure(context.getString(R.string.service_subsonic_download_not_saved))
            }
            onProgress(1f)
            SubsonicResult.Success(target)
        } catch (e: Exception) {
            partial.delete()
            android.util.Log.w("SubsonicDownloader", "download failed for ${song.id}", e)
            SubsonicResult.Failure(context.getString(R.string.service_subsonic_download_unfinished))
        }
    }

    /** Removes the kept copy. The song stays in the library, as a pointer to the server again. */
    fun remove(context: Context, songRowId: String): Boolean =
        fileFor(context, songRowId)?.takeIf { it.exists() }?.delete() ?: false

    /**
     * Takes a kept song off the phone: its copy goes, and the row it becomes -- streamed from
     * the server again, as it was before it was kept -- comes back. Null when the address it
     * streamed from was never written down (a song kept before that was done), in which case
     * the next sync adds it back.
     *
     * ⚠ This did not exist to be called. A kept song fell through to "streamed, nothing to
     * delete", so the server folder only ever grew.
     */
    fun unkeep(context: Context, row: SongEntity): SongEntity? {
        val file = fileFor(context, row.id)
        file?.takeIf { it.exists() }?.delete()
        val stream = file?.let { File(it.path + STREAM_SUFFIX) }
        val url = stream?.takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }
        stream?.delete()
        return url?.takeIf { it.isNotBlank() }?.let { row.copy(sourceType = SubsonicSync.SOURCE_TYPE, audioUri = it) }
    }

    private const val STREAM_SUFFIX = ".stream"

}
