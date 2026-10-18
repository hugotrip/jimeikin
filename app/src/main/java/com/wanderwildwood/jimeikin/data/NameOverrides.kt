package com.wanderwildwood.jimeikin.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A name the reader gave something in this app: a song's title, an album's title and artist,
 * an artist's name, or the artist a credit is filed under.
 *
 * Kept beside the library rather than written into it. A song streamed from YouTube or a music
 * server has no file to write a tag to, and a server sync or a scan rebuilds its rows from what
 * the source says, so a name written into a row would not last the next refresh. Laid over the
 * rows each time they are read instead, it lasts, and taking it away gives back the name the
 * song came with.
 */
@Entity(tableName = "name_overrides", primaryKeys = ["kind", "targetId"])
data class NameOverrideEntity(
    /** One of [NameOverrides.SONG], [NameOverrides.ALBUM], [NameOverrides.ARTIST], [NameOverrides.ARTIST_UNDER]. */
    val kind: String,
    /** The library row's id: a song's, an album's or an artist's. */
    val targetId: String,
    val name: String?,
    /** An album's artist; unused for the other kinds. */
    val artist: String?,
    /**
     * What the artist was called when the reader renamed them. A song credited to exactly that
     * name is renamed with them; a song crediting them with a guest keeps its own credit.
     */
    val originalName: String?,
)

@Dao
interface NameOverrideDao {
    @Query("SELECT * FROM name_overrides")
    suspend fun getAll(): List<NameOverrideEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<NameOverrideEntity>)

    @Query("DELETE FROM name_overrides WHERE kind = :kind AND targetId IN (:ids)")
    suspend fun delete(kind: String, ids: List<String>)
}

/**
 * Songs the reader has hearted, with enough of each kept to play it from the Hearted list
 * when it is not in the library: a YouTube song heard from a search is known by nothing else.
 */
@Entity(tableName = "hearted_songs")
data class HeartedSongEntity(
    @PrimaryKey val songId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val sourceType: String,
    val audioUri: String?,
    val durationMillis: Long?,
    val heartedAtMillis: Long,
)

@Dao
interface HeartedSongDao {
    @Query("SELECT * FROM hearted_songs ORDER BY heartedAtMillis DESC")
    suspend fun getAll(): List<HeartedSongEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: HeartedSongEntity)

    @Query("DELETE FROM hearted_songs WHERE songId IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}

/**
 * A YouTube song is one song whether it streams or has been downloaded, but it has two ids: the
 * video's, and its file's once it is here. [recorded] is the download record (video id to the
 * downloaded song's id); this gives every id a song is known by, so a name or a heart given to
 * one is found from the other.
 */
internal fun sameSongIds(id: String, recorded: Map<String, String>): List<String> = buildList {
    add(id)
    recorded[id]?.let(::add)
    recorded.forEach { (video, copy) -> if (copy == id) add(video) }
}

/** The names the reader has given, as one snapshot laid over rows as they are read. */
class Names(
    private val byKey: Map<Pair<String, String>, NameOverrideEntity>,
    private val recorded: () -> Map<String, String>,
) {
    fun isEmpty(): Boolean = byKey.isEmpty()

    fun get(kind: String, id: String): NameOverrideEntity? = byKey[kind to id]

    /** The title the reader gave this song, under any id it is known by. */
    fun songTitle(id: String): String? {
        if (byKey.isEmpty()) return null
        return sameSongIds(id, recorded()).firstNotNullOfOrNull { byKey[NameOverrides.SONG to it]?.name }
    }

    private fun matches(credit: String?, original: String?): Boolean =
        credit != null && original != null && ArtistNames.artistKey(credit) == ArtistNames.artistKey(original)

    /** A song's own credit, renamed only where it is exactly the artist the reader renamed. */
    private fun songCredit(artistId: String?, credit: String?): String? {
        val o = artistId?.let { byKey[NameOverrides.ARTIST to it] } ?: return null
        return o.name?.takeIf { matches(credit, o.originalName) }
    }

    /**
     * An album's artist: renamed with its artist, and filed with them where the artist was
     * filed under someone else - an album is filed, where a song keeps its credit.
     */
    private fun albumCredit(artistId: String?, credit: String?): String? {
        val id = artistId ?: return null
        byKey[NameOverrides.ARTIST to id]?.let { o -> if (matches(credit, o.originalName)) return o.name }
        byKey[NameOverrides.ARTIST_UNDER to id]?.let { o -> if (matches(credit, o.originalName)) return artistName(id, credit!!) }
        return null
    }

    fun song(e: SongEntity): SongEntity {
        if (byKey.isEmpty()) return e
        val title = songTitle(e.id)
        val album = e.albumId?.let { byKey[NameOverrides.ALBUM to it]?.name }
        val artist = songCredit(e.artistId, e.artist)
        if (title == null && album == null && artist == null) return e
        return e.copy(title = title ?: e.title, album = album ?: e.album, artist = artist ?: e.artist)
    }

    fun album(e: AlbumEntity): AlbumEntity {
        if (byKey.isEmpty()) return e
        val o = byKey[NameOverrides.ALBUM to e.id]
        val artist = o?.artist ?: albumCredit(e.artistId, e.artist)
        if (o?.name == null && artist == null) return e
        return e.copy(name = o?.name ?: e.name, artist = artist ?: e.artist)
    }

    fun artistName(id: String, name: String): String =
        byKey[NameOverrides.ARTIST to id]?.name ?: byKey[NameOverrides.ARTIST_UNDER to id]?.name ?: name

    fun artist(e: ArtistEntity): ArtistEntity =
        if (byKey.isEmpty()) e else artistName(e.id, e.name).let { if (it == e.name) e else e.copy(name = it) }

    fun artist(e: ArtistWithCounts): ArtistWithCounts =
        if (byKey.isEmpty()) e else artistName(e.id, e.name).let { if (it == e.name) e else e.copy(name = it) }
}

class NameOverrides(
    private val dao: () -> NameOverrideDao,
    private val recorded: () -> Map<String, String>,
) {
    private val mutex = Mutex()
    private val _names = MutableStateFlow<Names?>(null)

    /** Null until first read; [current] reads it. */
    val names: StateFlow<Names?> = _names.asStateFlow()

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { current() }
    }

    suspend fun current(): Names {
        _names.value?.let { return it }
        return mutex.withLock {
            _names.value ?: load().also { _names.value = it }
        }
    }

    private suspend fun load(): Names =
        Names(runCatching { dao().getAll() }.getOrDefault(emptyList()).associateBy { it.kind to it.targetId }, recorded)

    suspend fun write(set: List<NameOverrideEntity>, clear: Map<String, List<String>> = emptyMap()) {
        mutex.withLock {
            clear.forEach { (kind, ids) -> if (ids.isNotEmpty()) dao().delete(kind, ids) }
            if (set.isNotEmpty()) dao().upsertAll(set)
            _names.value = load()
        }
    }

    companion object {
        const val SONG = "song"
        const val ALBUM = "album"
        /** The artist renamed; credits spelled as they were are renamed with them. */
        const val ARTIST = "artist"
        /** A credit filed under another artist inside the app; each song keeps its own credit. */
        const val ARTIST_UNDER = "artist_under"
    }
}

/** The songs the reader has hearted. Never sent anywhere. */
class Hearts(
    private val dao: () -> HeartedSongDao,
    private val recorded: () -> Map<String, String>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _hearted = MutableStateFlow<List<HeartedSongEntity>>(emptyList())
    val hearted: StateFlow<List<HeartedSongEntity>> = _hearted.asStateFlow()

    init {
        scope.launch { mutex.withLock { reload() } }
    }

    private suspend fun reload() {
        _hearted.value = runCatching { dao().getAll() }.getOrDefault(emptyList())
    }

    /** The hearted entry for [id], under any id the song is known by. */
    fun entryFor(id: String, hearted: List<HeartedSongEntity> = _hearted.value): HeartedSongEntity? {
        if (hearted.isEmpty()) return null
        val ids = sameSongIds(id, recorded())
        return hearted.firstOrNull { it.songId in ids }
    }

    fun heart(row: HeartedSongEntity) {
        scope.launch {
            mutex.withLock {
                dao().upsert(row)
                reload()
            }
        }
    }

    fun unheart(id: String) {
        scope.launch {
            mutex.withLock {
                dao().deleteByIds(sameSongIds(id, recorded()))
                reload()
            }
        }
    }
}
