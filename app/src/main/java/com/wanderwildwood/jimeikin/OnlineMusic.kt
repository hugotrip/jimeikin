package com.wanderwildwood.jimeikin

import com.wanderwildwood.jimeikin.data.SongEntity
import com.wanderwildwood.jimeikin.ui.AlbumUiModel
import com.wanderwildwood.jimeikin.ui.OnlineArtistUiModel
import com.wanderwildwood.jimeikin.ui.SongUiModel
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient

/**
 * The part of the app that finds, streams and downloads songs from an online catalogue.
 *
 * The full edition (src/full) has one; the store edition (src/store) is built without it, and
 * its stand-in finds nothing, plays nothing and downloads nothing. The rest of the app reaches
 * it only through here, so the store edition carries none of that code.
 *
 * A song from the catalogue has sourceType "YOUTUBE", and its download "YOUTUBE_DOWNLOAD": those
 * names are in the library's rows and stay as they are, so a library carries over between
 * editions. A download is a file on the phone in either one.
 */
interface OnlineMusic {

    /** False in the store edition, which has no catalogue: nothing offers to stream or fetch. */
    val available: Boolean

    /** Which catalogue songs are already downloaded: the song's id to its download's id. */
    val recorded: StateFlow<Map<String, String>>
    fun recordCopy(onlineId: String, songId: String)
    fun renameCopy(oldSongId: String, newSongId: String)
    fun forgetCopy(songId: String)

    /** The download that holds [song], when it is a catalogue song already on the phone. */
    fun copyOf(song: SongUiModel, downloads: List<SongUiModel>): SongUiModel?

    /** [songs] with each already downloaded swapped for its download. */
    fun withCopies(songs: List<SongUiModel>, downloads: List<SongUiModel>): List<SongUiModel>

    /** The catalogue songs among [songs] not on the phone and not being fetched. */
    fun leftToDownload(songs: List<SongUiModel>, downloads: List<SongUiModel>, isUnderway: (String) -> Boolean): List<SongUiModel>

    /** As [copyOf] and [withCopies], from a [recorded] already read and without recording anything. */
    fun copyIn(song: SongUiModel, recorded: Map<String, String>, downloads: List<SongUiModel>): SongUiModel?
    fun withCopiesIn(songs: List<SongUiModel>, recorded: Map<String, String>, downloads: List<SongUiModel>): List<SongUiModel>

    /** An album page's songs in the album's own order, downloads among them. */
    fun inAlbumOrder(songs: List<SongUiModel>): List<SongUiModel>

    /** The catalogue song a downloaded file names in its comment tag, or null. */
    fun idInComment(comment: String?): String?

    /** A search of the catalogue; throws when it cannot be reached. */
    suspend fun search(query: String): OnlineSearch

    /** An album's songs as the catalogue lists them, or none. */
    suspend fun albumSongs(album: AlbumUiModel): List<SongUiModel>

    /** The catalogue's page for an artist named exactly [name], or null. */
    suspend fun findArtist(name: String): OnlineArtistUiModel?

    suspend fun artistPage(browseId: String, downloads: List<SongUiModel>): OnlineArtistPage

    /** What similar songs would be asked for after [seed], or null when it has none. */
    fun similarSeed(seed: SongUiModel): String?

    /** Up to [count] songs to play after [seedId] that are not in the queue (as it is once they are found). */
    suspend fun similar(seedId: String, queue: () -> List<SongUiModel>, count: Int): List<SongUiModel>

    fun precacheSearch(ids: List<String>)
    fun clearSearchWindow()
    fun updateQueueWindow(ids: List<String>)

    /** Sent with every stream the player fetches. */
    val streamHeaders: Map<String, String>

    /** Whether the player should hand an address on [host] to [resolveStream]. */
    fun isCatalogueHost(host: String): Boolean

    /** What the player is given for a catalogue song, for [resolveStream] to turn into a stream. */
    fun playAddress(id: String): String

    /** What the player's cache keeps a catalogue song under, or null for none. */
    fun cacheKey(id: String): String?

    /**
     * What to say when the player fails on a catalogue song that will never play, which is then
     * skipped; null for any other failure, which is said the usual way.
     */
    fun unplayableMessage(errorClassName: String, message: String): Int?

    /** The address to play a catalogue song from, and what found it. Blocks. */
    fun resolveStream(id: String): Pair<String, String?>

    /** Fetches a catalogue song into [targetDir]; true when it is on the phone. */
    suspend fun download(item: DownloadItem, targetDir: java.io.File, client: OkHttpClient, onProgress: (Float) -> Unit): Boolean

    /** Downloads to fetch again in a better format; see DownloadQueue's UPGRADE. */
    suspend fun upgradeCandidates(inUse: () -> Set<String>): List<SongEntity>
    suspend fun upgrade(songId: String, client: OkHttpClient, inUse: () -> Set<String>, onProgress: (Float) -> Unit): Pair<DownloadState, String?>

    /** Fill an album's gaps from the catalogue. */
    val completeAlbums: StateFlow<Boolean>
    fun setCompleteAlbums(enabled: Boolean)

    /** Keep the queue going with similar songs from the catalogue. */
    val keepPlayingSimilar: StateFlow<Boolean>
    fun setKeepPlayingSimilar(enabled: Boolean)
}

data class OnlineSearch(
    val songs: List<SongUiModel>,
    val albums: List<AlbumUiModel>,
    val artists: List<OnlineArtistUiModel>,
)

data class OnlineArtistPage(
    val name: String,
    val songs: List<SongUiModel>,
    val albums: List<AlbumUiModel>,
    val singles: List<AlbumUiModel>,
)
