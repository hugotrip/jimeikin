package com.wanderwildwood.jimeikin

import android.os.Environment
import android.util.Log
import com.wanderwildwood.jimeikin.data.CalmMusicDatabase
import com.wanderwildwood.jimeikin.data.SubsonicDownloader
import com.wanderwildwood.jimeikin.data.SubsonicResult
import com.wanderwildwood.jimeikin.data.SubsonicSync
import com.wanderwildwood.jimeikin.ui.SongUiModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.UUID

/**
 * Every download, from YouTube and from the music server alike, one at a time and in the
 * order asked for.
 *
 * The server's songs used to be fetched where the button was pressed, reported only in a
 * snackbar, and never appeared in Downloads; YouTube's went into a list that was forgotten when
 * the app closed. Both now go through here: one list, which Downloads shows and remembers, and
 * whose progress the line along the bottom of every screen and the notification both say. A
 * [DownloadService] holds the app up while anything is waiting, so a download carries on with
 * the screen off or the app closed.
 *
 * Every change to the list happens on the main thread except the percent, so that the worker
 * deciding there is nothing left to do and a song being asked for cannot pass each other.
 */
@OptIn(FlowPreview::class)
class DownloadQueue(private val app: CalmMusic) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val client = OkHttpClient()
    private val file = File(app.filesDir, "downloads.txt")

    private val _items = MutableStateFlow(load())
    /** Oldest first: the order they were asked for, which is the order they are fetched in. */
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()

    // The songs asked for since the queue was last empty, which is what "4 of 12" counts.
    private val _run = MutableStateFlow<List<String>>(emptyList())
    val run: StateFlow<List<String>> = _run.asStateFlow()

    // Screens showing the download line. When none is, the end of a run is told in a
    // notification instead, since nobody is looking at the line to read it.
    private val _watchers = MutableStateFlow(0)
    val isWatched: Boolean get() = _watchers.value > 0

    private var worker: Job? = null
    private var current: Pair<String, Job>? = null

    init {
        // Written whenever a download changes state, and not at each step of its percent,
        // which says nothing worth keeping across a restart.
        scope.launch {
            _items
                .map { list -> list.map { it.copy(percent = null) } }
                .distinctUntilChanged()
                .drop(1)
                .debounce(500)
                .collectLatest { list ->
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val part = File(file.path + ".part")
                            part.writeText(Downloads.encode(Downloads.prune(list, System.currentTimeMillis())))
                            part.renameTo(file)
                        }.onFailure { Log.w(TAG, "could not write the download list", it) }
                    }
                }
        }
    }

    private fun load(): List<DownloadItem> = runCatching {
        if (!file.exists()) return@runCatching emptyList()
        Downloads.restored(Downloads.prune(Downloads.decode(file.readText()), System.currentTimeMillis()))
    }.getOrElse {
        Log.w(TAG, "could not read the download list", it)
        emptyList()
    }

    fun isUnderway(songId: String): Boolean =
        _items.value.any { it.songId == songId && it.state.isActive }

    fun enqueueYouTube(song: SongUiModel, albumArtist: String? = null) {
        add(
            DownloadItem(
                id = UUID.randomUUID().toString(),
                kind = DownloadKind.YOUTUBE,
                songId = song.id,
                title = song.title,
                artist = song.artist,
                state = DownloadState.WAITING,
                album = song.album,
                albumArtist = albumArtist,
                trackNumber = song.trackNumber,
                discNumber = song.discNumber,
                durationMillis = song.durationMillis,
                audioUri = song.audioUri,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    fun enqueueServer(song: SongUiModel) {
        add(
            DownloadItem(
                id = UUID.randomUUID().toString(),
                kind = DownloadKind.SERVER,
                songId = song.id,
                title = song.title,
                artist = song.artist,
                state = DownloadState.WAITING,
                album = song.album,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private fun add(item: DownloadItem) {
        // One already waiting or underway is not asked for twice.
        if (isUnderway(item.songId)) return
        startRunIfIdle()
        _items.update { list ->
            Downloads.prune(list.filterNot { it.songId == item.songId && !it.state.isActive }, item.updatedAt) + item
        }
        _run.update { it + item.id }
        startWorker()
    }

    /** Asks again for one that failed, was canceled, or never finished. */
    fun retry(id: String) {
        val item = _items.value.firstOrNull { it.id == id && it.state.canRetry } ?: return
        if (isUnderway(item.songId)) return
        startRunIfIdle()
        _items.update { list ->
            val again = item.copy(state = DownloadState.WAITING, percent = null, reason = null, updatedAt = System.currentTimeMillis())
            list.filterNot { it.id == id } + again
        }
        _run.update { it + id }
        startWorker()
    }

    fun retryAll() {
        _items.value.filter { it.state.canRetry }.forEach { retry(it.id) }
    }

    fun cancel(id: String) {
        val item = _items.value.firstOrNull { it.id == id } ?: return
        if (!item.state.isActive) return
        set(id) { it.copy(state = DownloadState.CANCELED, percent = null) }
        current?.takeIf { it.first == id }?.second?.cancel()
    }

    /** Stops the song's download, if it is waiting or underway. */
    fun cancelSong(songId: String) {
        _items.value.filter { it.songId == songId && it.state.isActive }.forEach { cancel(it.id) }
    }

    fun cancelAll() {
        _items.value.filter { it.state.isActive }.forEach { cancel(it.id) }
    }

    /** Forgets everything finished. What is still to do stays. */
    fun clearFinished() {
        _items.update { list -> list.filter { it.state.isActive } }
        _run.update { ids -> ids.filter { id -> _items.value.any { it.id == id } } }
    }

    /** The run has been told, on the line or in a notification; the next one starts afresh. */
    fun forgetRun() {
        if (_items.value.none { it.state.isActive }) _run.value = emptyList()
    }

    /** Held for as long as a screen showing the line is on view. */
    suspend fun watch() {
        _watchers.update { it + 1 }
        try {
            awaitCancellation()
        } finally {
            _watchers.update { it - 1 }
        }
    }

    private fun startRunIfIdle() {
        if (_items.value.none { it.state.isActive }) _run.value = emptyList()
    }

    private fun startWorker() {
        if (worker?.isActive == true) return
        DownloadService.start(app)
        worker = scope.launch {
            while (true) {
                val next = _items.value.firstOrNull { it.state == DownloadState.WAITING } ?: break
                set(next.id) { it.copy(state = DownloadState.DOWNLOADING, percent = null, updatedAt = System.currentTimeMillis()) }
                val job = scope.launch { fetch(next) }
                current = next.id to job
                job.join()
                current = null
            }
        }
    }

    private suspend fun fetch(item: DownloadItem) {
        val outcome: Pair<DownloadState, String?> = try {
            when (item.kind) {
                DownloadKind.YOUTUBE -> fetchYouTube(item)
                DownloadKind.SERVER -> fetchFromServer(item)
            }
        } catch (e: CancellationException) {
            // Canceled: the row already says so.
            return
        } catch (e: Exception) {
            Log.w(TAG, "download failed: ${item.songId}", e)
            DownloadState.FAILED to (e.message ?: e.javaClass.simpleName)
        }
        set(item.id) { status ->
            if (status.state != DownloadState.DOWNLOADING) {
                status
            } else {
                status.copy(
                    state = outcome.first,
                    percent = null,
                    reason = outcome.second,
                    updatedAt = System.currentTimeMillis(),
                )
            }
        }
    }

    private fun progress(id: String, fraction: Float) {
        val stepped = Downloads.step(fraction)
        _items.update { list ->
            list.map { if (it.id == id && it.state == DownloadState.DOWNLOADING) it.copy(percent = stepped) else it }
        }
    }

    private suspend fun fetchYouTube(item: DownloadItem): Pair<DownloadState, String?> {
        val musicDir = app.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: return DownloadState.FAILED to app.getString(R.string.service_subsonic_download_no_storage)
        if (!musicDir.exists()) musicDir.mkdirs()
        val song = SongUiModel(
            id = item.songId,
            title = item.title,
            artist = item.artist,
            durationMillis = item.durationMillis,
            discNumber = item.discNumber,
            trackNumber = item.trackNumber,
            sourceType = "YOUTUBE",
            audioUri = item.audioUri,
            album = item.album,
        )
        val ok = performYouTubeDownloadInternal(
            app = app,
            song = song,
            albumArtist = item.albumArtist,
            targetDir = musicDir,
            context = app,
            client = client,
            onProgress = { progress(item.id, it) },
        )
        return if (ok) DownloadState.DOWNLOADED to null else DownloadState.FAILED to null
    }

    /**
     * The row is not duplicated: once the file is whole the song's source changes from a
     * pointer to the server into a file, which makes its rule solid and lets it play with the
     * network off. Removing the download later puts the pointer back.
     */
    private suspend fun fetchFromServer(item: DownloadItem): Pair<DownloadState, String?> {
        val songDao = CalmMusicDatabase.getDatabase(app).songDao()
        val row = withContext(Dispatchers.IO) { songDao.getSongById(item.songId) }
        return when {
            row?.sourceType == SubsonicDownloader.SOURCE_TYPE -> DownloadState.DOWNLOADED to null
            row == null || row.sourceType != SubsonicSync.SOURCE_TYPE ->
                DownloadState.FAILED to app.getString(R.string.main_not_on_a_server)
            else -> when (val result = SubsonicDownloader.download(app, row) { progress(item.id, it) }) {
                is SubsonicResult.Failure -> DownloadState.FAILED to result.message
                is SubsonicResult.Success -> {
                    withContext(Dispatchers.IO) {
                        songDao.upsertAll(
                            listOf(
                                row.copy(
                                    sourceType = SubsonicDownloader.SOURCE_TYPE,
                                    audioUri = android.net.Uri.fromFile(result.value).toString(),
                                ),
                            ),
                        )
                    }
                    DownloadState.DOWNLOADED to null
                }
            }
        }
    }

    private fun set(id: String, transform: (DownloadItem) -> DownloadItem) {
        _items.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    private companion object {
        const val TAG = "DownloadQueue"
    }
}
