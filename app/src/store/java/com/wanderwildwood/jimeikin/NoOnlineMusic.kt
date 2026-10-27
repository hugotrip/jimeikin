package com.wanderwildwood.jimeikin

import com.wanderwildwood.jimeikin.data.SongEntity
import com.wanderwildwood.jimeikin.ui.AlbumUiModel
import com.wanderwildwood.jimeikin.ui.OnlineArtistUiModel
import com.wanderwildwood.jimeikin.ui.SongUiModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import java.io.File

/** The store edition has no online catalogue; see [OnlineMusic]. */
fun createOnlineMusic(@Suppress("UNUSED_PARAMETER") app: CalmMusic): OnlineMusic = NoOnlineMusic

/**
 * Finds nothing, plays nothing, downloads nothing. A library carried over from the full edition
 * may still hold catalogue songs; they are left out of the library's lists (see
 * [OnlineMusic.available]), and one that reaches the player anyway is skipped like any song that
 * cannot play. Downloads already made are files, and play as files.
 */
private const val NO_HOST = "online.invalid"

object NoOnlineMusic : OnlineMusic {
    override val available: Boolean = false

    override val recorded: StateFlow<Map<String, String>> = MutableStateFlow(emptyMap())
    override fun recordCopy(onlineId: String, songId: String) = Unit
    override fun renameCopy(oldSongId: String, newSongId: String) = Unit
    override fun forgetCopy(songId: String) = Unit
    override fun copyOf(song: SongUiModel, downloads: List<SongUiModel>): SongUiModel? = null
    override fun withCopies(songs: List<SongUiModel>, downloads: List<SongUiModel>): List<SongUiModel> = songs
    override fun leftToDownload(songs: List<SongUiModel>, downloads: List<SongUiModel>, isUnderway: (String) -> Boolean): List<SongUiModel> =
        emptyList()
    override fun copyIn(song: SongUiModel, recorded: Map<String, String>, downloads: List<SongUiModel>): SongUiModel? = null
    override fun withCopiesIn(songs: List<SongUiModel>, recorded: Map<String, String>, downloads: List<SongUiModel>): List<SongUiModel> = songs
    override fun inAlbumOrder(songs: List<SongUiModel>): List<SongUiModel> = songs
    override fun idInComment(comment: String?): String? = null

    override suspend fun search(query: String): OnlineSearch = OnlineSearch(emptyList(), emptyList(), emptyList())
    override suspend fun albumSongs(album: AlbumUiModel): List<SongUiModel> = emptyList()
    override suspend fun findArtist(name: String): OnlineArtistUiModel? = null
    override suspend fun artistPage(browseId: String, downloads: List<SongUiModel>): OnlineArtistPage =
        OnlineArtistPage(name = "", songs = emptyList(), albums = emptyList(), singles = emptyList())
    override fun similarSeed(seed: SongUiModel): String? = null
    override suspend fun similar(seedId: String, queue: () -> List<SongUiModel>, count: Int): List<SongUiModel> = emptyList()

    override fun precacheSearch(ids: List<String>) = Unit
    override fun clearSearchWindow() = Unit
    override fun updateQueueWindow(ids: List<String>) = Unit
    override val streamHeaders: Map<String, String> = emptyMap()
    // Addressed so the player hands it to resolveStream, which refuses it (see below).
    override fun isCatalogueHost(host: String): Boolean = host == NO_HOST
    override fun playAddress(id: String): String = "https://$NO_HOST/$id"

    // Not the key the full edition cached the song's opening under, or the cache would play
    // that much of it before the refusal came.
    override fun cacheKey(id: String): String? = null
    // A song the full edition streamed, still in a playlist, the queue or the hearts: the
    // player is told it cannot be had, and it is skipped with a word, like any song that
    // will never play.
    override fun unplayableMessage(errorClassName: String, message: String): Int? =
        if (errorClassName == NotInThisEdition::class.java.name) R.string.main_not_in_this_edition else null
    override fun resolveStream(id: String): Pair<String, String?> = throw NotInThisEdition()

    override suspend fun download(item: DownloadItem, targetDir: File, client: OkHttpClient, onProgress: (Float) -> Unit): Boolean = false
    override suspend fun upgradeCandidates(inUse: () -> Set<String>): List<SongEntity> = emptyList()
    override suspend fun upgrade(
        songId: String,
        client: OkHttpClient,
        inUse: () -> Set<String>,
        onProgress: (Float) -> Unit,
    ): Pair<DownloadState, String?> = DownloadState.FAILED to null

    override val completeAlbums: StateFlow<Boolean> = MutableStateFlow(false)
    override fun setCompleteAlbums(enabled: Boolean) = Unit
    override val keepPlayingSimilar: StateFlow<Boolean> = MutableStateFlow(false)
    override fun setKeepPlayingSimilar(enabled: Boolean) = Unit
}

/** Thrown for a catalogue song the store edition has no way to play. */
class NotInThisEdition : java.io.IOException("not in this edition")
