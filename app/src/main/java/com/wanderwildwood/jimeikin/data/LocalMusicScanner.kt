package com.wanderwildwood.jimeikin.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.net.toUri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import java.io.File
import java.io.FileOutputStream

/**
 * Wrapper for a scanned song that includes metadata not directly stored in [SongEntity],
 * such as the explicit [albumArtist] string. This is crucial for correctly populating
 * [AlbumEntity] rows (e.g. preserving "Various Artists" display text).
 */
/** What a walk of the chosen folders found, and which of them it could not read. */
data class LocalScanResult(
    val audio: List<ScannedLocalAudio>,
    val unreadableTreeUris: Set<String>,
    val playlistFiles: List<ScannedPlaylistFile> = emptyList(),
    val relativePathBySongId: Map<String, String> = emptyMap(),
)

/** An .m3u sitting in one of the chosen folders, not yet read. */
data class ScannedPlaylistFile(
    val uri: Uri,
    val name: String,
    /** The folder it sits in, relative to the chosen folder; "" at the root. */
    val directoryPath: String,
    val relativePath: String,
)

data class ScannedLocalAudio(
    val song: SongEntity,
    val albumArtist: String?,
)

object LocalMusicScanner {
    private val PLAYLIST_EXTENSIONS = setOf("m3u", "m3u8")

    /**
     * Formats where MediaMetadataRetriever will not give up an album artist, so the file has
     * to be copied out and read properly. Doing it for everything meant copying a whole card
     * through the cache on a first scan.
     */
    private val DEEP_READ_EXTENSIONS = setOf("flac", "ogg", "opus")

    private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus")

    /**
     * Scan the given folders for audio files.
     */
    suspend fun scanFolders(
        context: Context,
        folderUris: Set<String>,
        existingSongsByUri: Map<String, SongEntity> = emptyMap(),
        lastScanMillis: Long = 0L,
        onProgress: suspend (processed: Int, total: Int) -> Unit = { _, _ -> },
    ): LocalScanResult {
        val result = mutableListOf<ScannedLocalAudio>()
        val unreadableTreeUris = mutableSetOf<String>()
        // A file reached through two chosen folders - one inside the other - arrives with a
        // different tree-scoped uri each time and would otherwise be indexed twice. The
        // document id is the same either way, so it is what identifies the file here.
        val seenDocumentIds = mutableSetOf<String>()

        data class Candidate(
            val uri: Uri,
            val name: String,
            val relativePath: String,
            val lastModified: Long,
            val fileSize: Long,
            val existing: SongEntity?,
        )

        val candidates = mutableListOf<Candidate>()
        val playlistFiles = mutableListOf<ScannedPlaylistFile>()
        // Where each song sat inside the chosen folder. An .m3u names its entries by path,
        // and a song's id here is a content:// uri with no path in it to compare against.
        val relativePathBySongId = mutableMapOf<String, String>()

        for (uriString in folderUris) {
            val treeUri = try {
                uriString.toUri()
            } catch (_: Exception) {
                continue
            }
            val root = DocumentFile.fromTreeUri(context, treeUri)
            // DocumentFile swallows its own query failures and answers false, so a revoked
            // grant, an unmounted card or a folder that has gone away all look like this
            // rather than throwing. Whatever the cause, the folder was not read, and its
            // songs must not be taken for deleted.
            if (root == null || !root.exists() || !root.canRead()) {
                unreadableTreeUris.add(uriString)
                continue
            }
            // One query per folder, not five per file.
            //
            // DocumentFile answers every question with its own ContentResolver query -
            // isDirectory, isFile, name, lastModified and length are five round trips for a
            // single file, and a card with a few thousand songs on it is tens of thousands of
            // queries. Asking the children uri for the columns directly gets the same answers
            // in one cursor per folder. On his 256GB card the walk went from about fourteen
            // minutes to under one.
            val rootDocumentId = try {
                DocumentsContract.getTreeDocumentId(treeUri)
            } catch (_: Exception) {
                unreadableTreeUris.add(uriString)
                continue
            }

            val stack = ArrayDeque<Pair<String, String>>()
            stack.add(rootDocumentId to "")

            while (stack.isNotEmpty()) {
                val (documentId, dirPath) = stack.removeFirst()
                val childrenUri = try {
                    DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
                } catch (_: Exception) {
                    continue
                }

                data class Child(
                    val id: String,
                    val name: String,
                    val mimeType: String,
                    val lastModified: Long,
                    val size: Long,
                )

                val children = mutableListOf<Child>()
                try {
                    context.contentResolver.query(
                        childrenUri,
                        arrayOf(
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_MIME_TYPE,
                            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                            DocumentsContract.Document.COLUMN_SIZE,
                        ),
                        null,
                        null,
                        null,
                    )?.use { cursor ->
                        while (cursor.moveToNext()) {
                            children.add(
                                Child(
                                    id = cursor.getString(0) ?: continue,
                                    name = cursor.getString(1) ?: continue,
                                    mimeType = cursor.getString(2) ?: "",
                                    lastModified = cursor.getLong(3),
                                    size = cursor.getLong(4),
                                ),
                            )
                        }
                    }
                } catch (_: Exception) {
                    // A folder that will not answer is skipped rather than taken as empty;
                    // the tree-level check above is what decides a whole grant is unreadable.
                    continue
                }

                // A folder carrying .nomedia is asking not to be indexed: ringtones, voice
                // notes and podcast caches all use it, and someone who granted a whole card
                // would otherwise get every one of them in the Songs list.
                if (children.any { it.name == ".nomedia" }) continue

                for (child in children) {
                    val isDirectory = child.mimeType == DocumentsContract.Document.MIME_TYPE_DIR
                    if (isDirectory) {
                        stack.add(
                            child.id to if (dirPath.isEmpty()) child.name else "$dirPath/${child.name}",
                        )
                        continue
                    }

                    val name = child.name
                    val ext = name.substringAfterLast('.', "").lowercase()
                    val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.id)

                    if (ext in PLAYLIST_EXTENSIONS) {
                        playlistFiles.add(
                            ScannedPlaylistFile(
                                uri = uri,
                                name = name,
                                directoryPath = dirPath,
                                relativePath = if (dirPath.isEmpty()) name else "$dirPath/$name",
                            ),
                        )
                    }

                    if (ext in AUDIO_EXTENSIONS) {
                        if (!seenDocumentIds.add(child.id)) continue
                        val uriString2 = uri.toString()
                        val relativePath = if (dirPath.isEmpty()) name else "$dirPath/$name"
                        relativePathBySongId[uriString2] = relativePath

                        candidates.add(
                            Candidate(
                                uri = uri,
                                name = name,
                                relativePath = relativePath,
                                lastModified = child.lastModified,
                                fileSize = child.size,
                                existing = existingSongsByUri[uriString2],
                            ),
                        )
                    }
                }
            }
        }

        if (candidates.isEmpty()) {
            onProgress(0, 0)
            return LocalScanResult(emptyList(), unreadableTreeUris, playlistFiles, relativePathBySongId)
        }

        val (unchanged, changed) = candidates.partition { candidate ->
            val existing = candidate.existing
            existing != null &&
                    existing.sourceType == "LOCAL_FILE" &&
                    existing.localLastModifiedMillis == candidate.lastModified &&
                    existing.localFileSizeBytes == candidate.fileSize
        }

        val (recentChanged, olderChanged) = changed.partition { candidate ->
            candidate.lastModified > lastScanMillis
        }

        val orderedCandidates =
            (recentChanged.sortedByDescending { it.lastModified } +
                    olderChanged.sortedByDescending { it.lastModified } +
                    unchanged)

        val total = orderedCandidates.size
        var processed = 0
        var lastProgressUpdateTime = 0L

        suspend fun maybeReportProgress() {
            val now = System.currentTimeMillis()
            if (processed == total || now - lastProgressUpdateTime > 200L) {
                lastProgressUpdateTime = now
                onProgress(processed, total)
            }
        }

        for (candidate in orderedCandidates) {
            val existing = candidate.existing
            if (existing != null &&
                existing.sourceType == "LOCAL_FILE" &&
                existing.localLastModifiedMillis == candidate.lastModified &&
                existing.localFileSizeBytes == candidate.fileSize
            ) {
                result.add(ScannedLocalAudio(existing, null))
                processed++
                maybeReportProgress()
                continue
            }

            val scanned = buildSongEntityFromFile(
                context = context,
                uri = candidate.uri,
                name = candidate.name,
                lastModified = candidate.lastModified,
                fileSize = candidate.fileSize,
                existing = existing,
            )

            result.add(scanned)

            processed++
            maybeReportProgress()
        }

        onProgress(processed, total.coerceAtLeast(processed))

        return LocalScanResult(result, unreadableTreeUris, playlistFiles, relativePathBySongId)
    }

    fun buildSongEntityFromFile(
        context: Context,
        uri: Uri,
        name: String,
        lastModified: Long,
        fileSize: Long,
        existing: SongEntity? = null,
    ): ScannedLocalAudio {
        val meta = extractMetadata(context, uri)
        val titleFromName = name.substringBeforeLast('.', name)

        val existingArtist = existing?.artist?.takeIf { it.isNotBlank() }
        val existingAlbum = existing?.album?.takeIf { it.isNotBlank() }
        val existingArtistId = existing?.artistId
        val existingAlbumId = existing?.albumId

        val explicitAlbumArtist = meta.albumArtist?.trim()?.takeIf { it.isNotBlank() }

        val baseArtistCandidate = meta.artist?.takeIf { it.isNotBlank() }
            ?: existingArtist
            ?: explicitAlbumArtist

        val trackArtistDisplay = baseArtistCandidate?.trim().orEmpty()
        val trackArtistIdComponent = trackArtistDisplay.takeIf { it.isNotBlank() }?.normalizeForIdComponent()

        val artistId = trackArtistIdComponent?.let { "LOCAL_FILE:$it" } ?: existingArtistId

        val effectiveAlbumArtist = explicitAlbumArtist ?: trackArtistDisplay
        val albumArtistIdComponent = effectiveAlbumArtist.takeIf { it.isNotBlank() }?.normalizeForIdComponent()

        val albumNameDisplay = meta.album?.trim()?.takeIf { it.isNotBlank() } ?: existingAlbum
        val albumNameIdComponent = albumNameDisplay?.normalizeForIdComponent()

        val albumId = if (albumNameIdComponent != null && albumArtistIdComponent != null) {
            "LOCAL_FILE:${albumArtistIdComponent}:${albumNameIdComponent}"
        } else existingAlbumId

        val uriString = uri.toString()
        val song = SongEntity(
            id = uriString,
            title = meta.title ?: existing?.title ?: titleFromName,
            artist = trackArtistDisplay,
            album = albumNameDisplay,
            albumId = albumId,
            discNumber = meta.discNumber ?: existing?.discNumber,
            trackNumber = meta.trackNumber ?: existing?.trackNumber,
            durationMillis = meta.durationMillis ?: existing?.durationMillis,
            sourceType = "LOCAL_FILE",
            audioUri = uriString,
            artistId = artistId,
            releaseYear = meta.year ?: existing?.releaseYear,
            localLastModifiedMillis = lastModified,
            localFileSizeBytes = fileSize,
        )

        return ScannedLocalAudio(song, explicitAlbumArtist)
    }

    private data class LocalMetadata(
        val title: String?,
        val artist: String?,
        val albumArtist: String?,
        val album: String?,
        val discNumber: Int?,
        val trackNumber: Int?,
        val durationMillis: Long?,
        val year: Int?,
    )

    private fun extractMetadata(context: Context, uri: Uri): LocalMetadata {
        val retriever = MediaMetadataRetriever()
        var meta = try {
            retriever.setDataSource(context, uri)

            val rawTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val rawArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val rawAlbumArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            val rawAlbum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)

            val discStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)
            val trackStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val yearStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)

            LocalMetadata(
                title = rawTitle.normalizeTagString(),
                artist = rawArtist.normalizeTagString(),
                albumArtist = rawAlbumArtist.normalizeTagString(),
                album = rawAlbum.normalizeTagString(),
                discNumber = discStr?.substringBefore('/')?.trim()?.toIntOrNull(),
                trackNumber = trackStr?.substringBefore('/')?.trim()?.toIntOrNull(),
                durationMillis = durationStr?.toLongOrNull(),
                year = yearStr?.take(4)?.trim()?.toIntOrNull(),
            )
        } catch (_: Exception) {
            LocalMetadata(null, null, null, null, null, null, null, null)
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }

        if (meta.albumArtist.isNullOrBlank()) {
            val tempFile = copyUriToTempFile(context, uri)
            if (tempFile != null) {
                try {
                    TagOptionSingleton.getInstance().isAndroid = true

                    val audioFile = AudioFileIO.read(tempFile)
                    val tag = audioFile.tag
                    if (tag != null) {
                        val deepAlbumArtist = tag.getFirst(FieldKey.ALBUM_ARTIST)

                        if (!deepAlbumArtist.isNullOrBlank()) {
                            meta = meta.copy(albumArtist = deepAlbumArtist.normalizeTagString())
                        }

                        if (meta.artist.isNullOrBlank()) {
                            meta = meta.copy(artist = tag.getFirst(FieldKey.ARTIST).normalizeTagString())
                        }
                        if (meta.album.isNullOrBlank()) {
                            meta = meta.copy(album = tag.getFirst(FieldKey.ALBUM).normalizeTagString())
                        }
                    }
                } catch (_: Exception) {
                    // Ignore deep scan failures
                } finally {
                    tempFile.delete()
                }
            }
        }

        return meta
    }

    private fun copyUriToTempFile(context: Context, uri: Uri): File? {
        val tempFile = try {
            File.createTempFile("scanner_probe", ".tmp", context.cacheDir)
        } catch (e: Exception) {
            return null
        }
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output -> input.copyTo(output) }
            } ?: run {
                tempFile.delete()
                return null
            }
            tempFile
        } catch (e: Exception) {
            // A card pulled mid-scan used to leave its half-copied file in the cache for good.
            tempFile.delete()
            null
        }
    }
}

private fun String?.normalizeTagString(): String? {
    if (this == null) return null
    val trimmed = trim()
    if (trimmed.isEmpty()) return null
    return trimmed.fixCommonTagMojibake()
}

private fun String.fixCommonTagMojibake(): String {
    var fixed = this
    val replacements = mapOf(
        "â€™" to "’",
        "â€˜" to "‘",
        "â€œ" to "“",
        "â€ " to "”",
        "â€“" to "–",
        "â€”" to "—",
    )
    for ((bad, good) in replacements) {
        if (fixed.contains(bad)) {
            fixed = fixed.replace(bad, good)
        }
    }
    return fixed
}

/**
 * The same folding the artist grouping uses, so a song's artistId and the artist row it
 * belongs to agree. Album names go through it too: an album spelled two ways is the same
 * album for the same reasons.
 */
private fun String.normalizeForIdComponent(): String = ArtistNames.key(this)