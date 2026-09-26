package com.wanderwildwood.jimeikin.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.jimeikin.R

/**
 * Where something came from, when that was not a file put on the phone.
 *
 * A file in a chosen folder carries no mark - it is most of any library and needs nothing
 * said about it. Anything from the music server or YouTube is marked with where it came
 * from: streamed, by that place's mark alone; downloaded, by the same mark with the
 * available-offline tick before it. A download is still worth telling apart from a file the
 * reader put there themselves, because it is the one that can be removed and fetched again.
 */
enum class Origin(val streams: Boolean) {
    SERVER(streams = true),
    YOUTUBE(streams = true),
    SERVER_DOWNLOAD(streams = false),
    YOUTUBE_DOWNLOAD(streams = false),
}

fun originOf(sourceType: String?): Origin? = when (sourceType) {
    "SUBSONIC" -> Origin.SERVER
    "YOUTUBE" -> Origin.YOUTUBE
    "SUBSONIC_DOWNLOAD" -> Origin.SERVER_DOWNLOAD
    "YOUTUBE_DOWNLOAD" -> Origin.YOUTUBE_DOWNLOAD
    else -> null
}

/**
 * For an album or an artist. Anything that streams outranks anything downloaded - the mark
 * that matters is whether it will play without a signal - and the server outranks YouTube.
 */
fun originOf(sourceTypes: Collection<String>): Origin? {
    val origins = sourceTypes.mapNotNull { originOf(it) }.toSet()
    return listOf(Origin.SERVER, Origin.YOUTUBE, Origin.SERVER_DOWNLOAD, Origin.YOUTUBE_DOWNLOAD)
        .firstOrNull { it in origins }
}

@Composable
fun OriginMark(origin: Origin) {
    val description = stringResource(
        when (origin) {
            Origin.SERVER -> R.string.origin_server
            Origin.YOUTUBE -> R.string.origin_youtube
            Origin.SERVER_DOWNLOAD -> R.string.origin_server_download
            Origin.YOUTUBE_DOWNLOAD -> R.string.origin_youtube_download
        },
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!origin.streams) {
            Icon(imageVector = Icons.OfflinePin, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(2.dp))
        }
        Icon(
            imageVector = if (origin == Origin.SERVER || origin == Origin.SERVER_DOWNLOAD) Icons.Dns else Icons.Cloud,
            contentDescription = description,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** A row's second line, with the mark before it when there is one. */
@Composable
fun SubtitleLine(text: String, origin: Origin?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (origin != null) {
            OriginMark(origin)
            Spacer(modifier = Modifier.width(8.dp))
        }
        TextMMD(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
