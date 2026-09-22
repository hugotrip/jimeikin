package com.wanderwildwood.jimeikin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.jimeikin.R

enum class RepeatMode {
    OFF,
    QUEUE,
    ONE,
}

@Composable
fun NowPlayingScreen(
    title: String,
    artist: String,
    album: String? = null,
    isPlaying: Boolean,
    isLoading: Boolean,
    currentPosition: Long,
    duration: Long,
    repeatMode: RepeatMode,
    isShuffleOn: Boolean,
    onPlayPauseClick: () -> Unit,
    onSeek: (Long) -> Unit,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    onRewindClick: () -> Unit = {},
    onFastForwardClick: () -> Unit = {},
    onShuffleClick: () -> Unit,
    onRepeatClick: () -> Unit,
    onAddToPlaylistClick: () -> Unit,
    onBackClick: () -> Unit = {},
    onArtistClick: (() -> Unit)? = null,
    onAlbumClick: (() -> Unit)? = null,
    isVideo: Boolean = false,
    isLive: Boolean = false,
    player: Player? = null,
    canDownload: Boolean = false,
    isDownloadInProgress: Boolean = false,
    onDownloadClick: () -> Unit = {},
    onCancelDownloadClick: () -> Unit = {},
    isDownloaded: Boolean = false,
    onDeleteDownloadClick: () -> Unit = {},
    sourceType: String? = null,
    streamResolverLabel: String? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {}
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // In-content top bar with back affordance (no Scaffold top app bar here).
        // The queue actions sit on this line rather than in a row of their own at the bottom:
        // they belong to the queue, not to the track, and putting them here leaves the page a
        // single column of title, progress and transport.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // All the slack on this line belongs to the title. It used to be a weight(1f)
            // spacer that pushed the actions right, but a Row splits weight between every
            // weighted child: with the title asking for the slack as well, the two halved
            // it and the title came out as "N...". One weighted box holds the slack, and
            // the actions sit at the end of the row behind it.
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Only the arrow and the title navigate back, so the clickable row is the
                // words and not the empty space beside them.
                Row(
                    modifier = Modifier.clickable(onClick = onBackClick),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Back,
                        contentDescription = stringResource(R.string.player_back),
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    TextMMD(
                        text = stringResource(R.string.player_now_playing_title),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Download does not disappear once the song is on the phone: it inverts, and
            // the second press takes it back. A button that vanishes when you use it
            // cannot be undone, and leaves you unsure whether it worked or was never
            // there.
            //
            // There is no "add to library" here. Downloading puts the song in the library
            // as well, so the two buttons differed only in whether the song came with it -
            // and a saved song you cannot play off the network is a thin thing to carry.
            // Taking a song back out of the library is still on its row in the library.
            if (canDownload || isDownloaded) {
                if (isDownloadInProgress) {
                    // The spinner here was also the only way to cancel, which nothing said.
                    IconButton(onClick = onCancelDownloadClick) {
                        TextMMD(text = stringResource(R.string.player_download_stop), style = MaterialTheme.typography.labelSmall)
                    }
                } else {
                    IconButton(
                        onClick = if (isDownloaded) onDeleteDownloadClick else onDownloadClick,
                    ) {
                        Icon(
                            imageVector = if (isDownloaded) {
                                Icons.DownloadOff
                            } else {
                                Icons.Download
                            },
                            contentDescription = if (isDownloaded) {
                                stringResource(R.string.player_delete_download)
                            } else {
                                stringResource(R.string.player_download)
                            },
                        )
                    }
                }
            }

            IconButton(onClick = onAddToPlaylistClick) {
                Icon(
                    imageVector = Icons.PlaylistAdd,
                    contentDescription = stringResource(R.string.player_add_to_playlist),
                )
            }

            IconButton(onClick = onShuffleClick) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Shuffle,
                        contentDescription = stringResource(R.string.player_shuffle_queue),
                    )
                    if (isShuffleOn) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Box(
                            modifier = Modifier
                                .size(4.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = CircleShape,
                                ),
                        )
                    }
                }
            }

            IconButton(onClick = onRepeatClick) {
                val (icon, description, isActive) = when (repeatMode) {
                    RepeatMode.OFF -> Triple(Icons.Repeat, stringResource(R.string.player_repeat_off), false)
                    RepeatMode.QUEUE -> Triple(Icons.Repeat, stringResource(R.string.player_repeat_queue), true)
                    RepeatMode.ONE -> Triple(Icons.RepeatOne, stringResource(R.string.player_repeat_current_song), true)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = icon,
                        contentDescription = description,
                    )
                    if (isActive) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Box(
                            modifier = Modifier
                                .size(4.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = CircleShape,
                                ),
                        )
                    }
                }
            }
        }

        // Where this track is coming from, and whether it is kept. Under the top bar and
        // hard right, so it sits beneath the two buttons it describes rather than at the
        // far end of the page from them.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val isLocal = sourceType == "LOCAL_FILE" ||
                sourceType == "YOUTUBE_DOWNLOAD" ||
                sourceType == "SUBSONIC_DOWNLOAD"

            if (!isLocal) {
                // This was a solid black lozenge with a white cloud in it: the only inverted
                // thing on the page, and the heaviest mark on a screen whose subject is the
                // title. A word at the size of the timestamps says the same thing.
                //
                // "In the library" stood beside it and has gone with the button that put it
                // there: with downloading the only way to keep a song, a kept song is a song
                // that is not streaming, and the absence of this word already says so.
                TextMMD(text = stringResource(R.string.player_streaming), style = MaterialTheme.typography.labelSmall)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.Bottom,
        ) {
            // Audio Reading's block, to its measurements: the artist bold against the italic
            // title between them, so it reads as a name over a work rather than as three
            // lines of one weight. Artist and album are one size, as author and chapter are
            // there - 24sp lands on the ascender, 23 falls a pixel short.
            //
            // The artist and the album lead to their pages; the title leads nowhere, because
            // the song it names is the one already open.
            if (!isVideo) {
                TextMMD(
                    text = artist,
                    modifier = if (onArtistClick != null) Modifier.clickable(onClick = onArtistClick) else Modifier,
                    style = MaterialTheme.typography.titleLarge,
                    lineHeight = 29.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.height(4.dp))
            }

            TextMMD(
                text = title,
                fontSize = if (isVideo) 24.sp else 27.5.sp,
                lineHeight = if (isVideo) 29.5.sp else 34.sp,
                fontWeight = FontWeight.Normal,
                maxLines = if (isVideo) 1 else 2,
                overflow = TextOverflow.Ellipsis
            )

            if (!isVideo) {
                val hasAlbum = !album.isNullOrBlank()
                if (hasAlbum) {
                    Spacer(modifier = Modifier.height(12.dp))

                    TextMMD(
                        text = album!!,
                        modifier = if (onAlbumClick != null) Modifier.clickable(onClick = onAlbumClick) else Modifier,
                        style = MaterialTheme.typography.titleLarge,
                        lineHeight = 29.5.sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (isVideo && player != null) {
                Spacer(modifier = Modifier.height(16.dp))

                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f),
                    factory = { context ->
                        PlayerView(context).apply {
                            useController = false
                            this.player = player
                        }
                    },
                    update = { view ->
                        view.player = player
                    },
                )
            }
        }

        // The bar sits where it always did: what stands above and below gives up exactly what
        // the bar took for somewhere to be pressed.
        Spacer(modifier = Modifier.height(44.dp - (SeekBarHeight - KnobSize) / 2))

        // A radio stream has no length and no position to seek to, so it gets neither a bar
        // nor a pair of clocks. Both would have sat at 0:00 for as long as you listened, which
        // reads as something broken rather than as something live.
        if (isLive) {
            TextMMD(
                text = stringResource(R.string.player_live),
                style = MaterialTheme.typography.bodyLarge,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Bold,
            )

            Spacer(modifier = Modifier.height(20.dp - (SeekBarHeight - KnobSize) / 2))
        } else {
            SeekBar(
                positionMs = currentPosition,
                durationMs = duration,
                onSeekTo = onSeek,
            )

            Spacer(modifier = Modifier.height(20.dp - (SeekBarHeight - KnobSize) / 2))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Clock(currentPosition)
                Clock(duration)
            }
        }

        Spacer(modifier = Modifier.height(25.dp))

        // Audio Reading's transport, to its measurements. Plain glyphs on white rather than
        // the two filled black slabs that used to sit either side of play: on a page whose
        // subject is the title, the heaviest marks should not be the ones that skip past it.
        //
        // Its outer pair moves a chapter; here it moves a track, which is the same gesture
        // on a thing with the same shape. The inner pair is new - this screen had no way to
        // move within a song at all, only to leave it.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 19.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TransportButton(PlayerIcons.Previous, stringResource(R.string.player_previous_song), 36.dp, onPreviousClick)

            // Nothing to seek within on a live stream, so the pair that seeks is not drawn.
            if (!isLive) {
                val seekLabel = stringResource(R.string.player_seek_seconds_short, SEEK_SECONDS)
                TransportButton(PlayerIcons.Rewind, pluralStringResource(R.plurals.player_seek_back_seconds, SEEK_SECONDS, SEEK_SECONDS), 32.dp, onRewindClick) {
                    seekLabel
                }
            }

            // A fixed width whichever of the three things is in it, so that saying "Loading"
            // does not shove the four buttons around it sideways and back again.
            Box(
                modifier = Modifier.width(56.dp).fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                if (isLoading) {
                    // A word, not a spinner: this panel cannot animate without smearing.
                    TextMMD(
                        text = if (isLive) stringResource(R.string.player_waiting_live) else stringResource(R.string.player_loading),
                        style = MaterialTheme.typography.labelSmall,
                    )
                } else {
                    TransportButton(
                        icon = if (isPlaying) PlayerIcons.Pause else PlayerIcons.Play,
                        description = if (isPlaying) stringResource(R.string.player_pause) else stringResource(R.string.player_play),
                        size = 48.dp,
                        onClick = onPlayPauseClick,
                    )
                }
            }

            if (!isLive) {
                val seekLabel = stringResource(R.string.player_seek_seconds_short, SEEK_SECONDS)
                TransportButton(PlayerIcons.Forward, pluralStringResource(R.plurals.player_seek_on_seconds, SEEK_SECONDS, SEEK_SECONDS), 32.dp, onFastForwardClick) {
                    seekLabel
                }
            }

            TransportButton(PlayerIcons.Next, stringResource(R.string.player_next_song), 36.dp, onNextClick)
        }

        Spacer(modifier = Modifier.height(42.dp))
    }
}

/**
 * How far the inner pair moves. Audio Reading makes this a setting because a listener picks
 * up a book mid-sentence; a song is three minutes long and ten seconds is the step everything
 * else uses, so it is a number here and not a preference.
 */
private const val SEEK_SECONDS = 10

/**
 * One transport control, and under it whatever it has to say about itself - which is only
 * ever how far the two seeking arrows move.
 *
 * The label hangs off the bottom of the box rather than sitting in a column under the icon,
 * so that having one does not push an arrow up out of line with the play button beside it.
 */
@Composable
private fun TransportButton(
    icon: ImageVector,
    description: String,
    size: Dp,
    onClick: () -> Unit,
    label: (() -> String)? = null,
) {
    Box(
        modifier = Modifier.fillMaxHeight().clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(size),
        )
        if (label != null) {
            TextMMD(
                text = label(),
                style = MaterialTheme.typography.labelSmall,
                lineHeight = 14.sp,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun Clock(ms: Long) {
    TextMMD(
        text = formatDurationMillisNonNull(ms),
        style = MaterialTheme.typography.bodyLarge,
        lineHeight = 24.sp,
    )
}

/**
 * Where the song is, and a way to move it.
 *
 * Drawn rather than taken from the toolkit: the stock slider animates its thumb, grows it on
 * press and draws a halo around it, all of which are redraws the panel pays for and none of
 * which say anything a filled line does not.
 */
@Composable
private fun SeekBar(positionMs: Long, durationMs: Long, onSeekTo: (Long) -> Unit) {
    var width by remember { mutableIntStateOf(0) }
    val knob = with(LocalDensity.current) { KnobSize.toPx() }
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val travel = (width - knob).coerceAtLeast(0f)

    fun seek(x: Float) {
        if (durationMs > 0 && travel > 0f) {
            onSeekTo((durationMs * ((x - knob / 2) / travel).coerceIn(0f, 1f)).toLong())
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            // Sixteen dense pixels of line is something to look at, not something to catch: a
            // drag has to start inside it, and a thumb on a bus does not land that accurately.
            // The strip that takes the press is as tall as a button; the line stays a line.
            .height(SeekBarHeight)
            .onSizeChanged { width = it.width }
            .pointerInput(durationMs, width) {
                detectHorizontalDragGestures { change, _ -> seek(change.position.x) }
            }
            .pointerInput(durationMs, width) {
                detectTapGestures { seek(it.x) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        // The track stops where the knob's travel stops, half a knob in from each end, so that
        // a song at the very beginning or the very end has the knob sitting on the track rather
        // than hanging off it.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = KnobSize / 2)
                .height(2.dp)
                .background(TrackGrey),
        )
        Box(
            modifier = Modifier
                .offset { IntOffset((travel * fraction).toInt(), 0) }
                .size(KnobSize)
                .clip(CircleShape)
                .background(Color.Black),
        )
    }
}

private val KnobSize = 16.dp

/** How much of the screen listens for the bar. Twice a fingertip, and all of it pressable. */
private val SeekBarHeight = 44.dp

private val TrackGrey = Color(0xFFB2B2B2)

private fun formatDurationMillisNonNull(millis: Long): String {
    return com.wanderwildwood.jimeikin.formatDurationMillis(millis) ?: "0:00"
}

@Preview(showBackground = true)
@Composable
private fun NowPlayingScreenPreview() {
    NowPlayingScreen(
        title = "Song Title",
        artist = "Artist Name",
        album = "Album Name",
        isPlaying = false,
        isLoading = false,
        currentPosition = 0L,
        duration = 1000L,
        repeatMode = RepeatMode.OFF,
        isShuffleOn = false,
        onPlayPauseClick = {},
        onSeek = {},
        onPreviousClick = {},
        onNextClick = {},
        onRewindClick = {},
        onFastForwardClick = {},
        onShuffleClick = {},
        onRepeatClick = {},
        onAddToPlaylistClick = {},
        isVideo = false,
        player = null,
        canDownload = false,
        isDownloadInProgress = false,
        onDownloadClick = {},
        onCancelDownloadClick = {},
        sourceType = "YOUTUBE",
        streamResolverLabel = "Innertube",
    )
}

// Both buttons showing their undo at once. The app does not reach this state - a finished
// download replaces the streamed song, which takes the library button away - but it is the
// widest the top bar can ever be asked to be, so it is the one to look at before changing
// that row.
@Preview(showBackground = true, widthDp = 360, heightDp = 600)
@Composable
private fun NowPlayingScreenSavedPreview() {
    NowPlayingScreen(
        title = "Song Title",
        artist = "Artist Name",
        album = "Album Name",
        isPlaying = true,
        isLoading = false,
        currentPosition = 30_000L,
        duration = 210_000L,
        repeatMode = RepeatMode.OFF,
        isShuffleOn = false,
        onPlayPauseClick = {},
        onSeek = {},
        onPreviousClick = {},
        onNextClick = {},
        onRewindClick = {},
        onFastForwardClick = {},
        onShuffleClick = {},
        onRepeatClick = {},
        onAddToPlaylistClick = {},
        isVideo = false,
        player = null,
        canDownload = true,
        isDownloadInProgress = false,
        onDownloadClick = {},
        onCancelDownloadClick = {},
        isDownloaded = true,
        onDeleteDownloadClick = {},
        sourceType = "YOUTUBE",
        streamResolverLabel = "Innertube",
    )
}