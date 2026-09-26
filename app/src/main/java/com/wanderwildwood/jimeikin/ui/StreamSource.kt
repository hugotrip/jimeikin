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
 * Where something plays from, when that is not the phone.
 *
 * Music on the phone carries no mark - it is most of any library and needs nothing said about
 * it. Only what needs the network is marked, and with where it comes from, because a server
 * at home and YouTube are not the same promise when the phone is out of the house.
 */
enum class StreamSource { SERVER, YOUTUBE }

fun streamSourceOf(sourceType: String?): StreamSource? = when (sourceType) {
    "SUBSONIC" -> StreamSource.SERVER
    "YOUTUBE" -> StreamSource.YOUTUBE
    else -> null
}

/** For an album or an artist: marked if any of it streams, by the source most of it needs. */
fun streamSourceOf(sourceTypes: Collection<String>): StreamSource? {
    val sources = sourceTypes.mapNotNull { streamSourceOf(it) }
    return when {
        sources.isEmpty() -> null
        StreamSource.SERVER in sources -> StreamSource.SERVER
        else -> StreamSource.YOUTUBE
    }
}

@Composable
fun StreamSourceMark(source: StreamSource) {
    Icon(
        imageVector = if (source == StreamSource.SERVER) Icons.Dns else Icons.Cloud,
        contentDescription = stringResource(
            if (source == StreamSource.SERVER) R.string.stream_source_server else R.string.stream_source_youtube,
        ),
        modifier = Modifier.size(16.dp),
    )
}

/** A row's second line, with the mark before it when there is one. */
@Composable
fun SubtitleLine(text: String, source: StreamSource?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (source != null) {
            StreamSourceMark(source)
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
