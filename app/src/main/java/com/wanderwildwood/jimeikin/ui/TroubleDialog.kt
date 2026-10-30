package com.wanderwildwood.jimeikin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.jimeikin.R
import com.wanderwildwood.jimeikin.playback.Trouble

private fun Trouble.title(): Int = when (this) {
    Trouble.SERVER_SIGNED_OUT -> R.string.trouble_signed_out_title
    Trouble.SERVER_UNREACHABLE -> R.string.trouble_unreachable_title
    Trouble.SERVER_REFUSED -> R.string.trouble_refused_title
    Trouble.NO_INTERNET -> R.string.trouble_no_internet_title
    Trouble.FILE_MISSING -> R.string.trouble_file_missing_title
    Trouble.NO_FILE_ACCESS -> R.string.trouble_no_access_title
    Trouble.STILL_LOADING -> R.string.trouble_still_loading_title
    Trouble.CANT_PLAY -> R.string.trouble_cant_play_title
}

private fun Trouble.body(): Int = when (this) {
    Trouble.SERVER_SIGNED_OUT -> R.string.trouble_signed_out
    Trouble.SERVER_UNREACHABLE -> R.string.trouble_unreachable
    Trouble.SERVER_REFUSED -> R.string.trouble_refused
    Trouble.NO_INTERNET -> R.string.trouble_no_internet
    Trouble.FILE_MISSING -> R.string.trouble_file_missing
    Trouble.NO_FILE_ACCESS -> R.string.trouble_no_access
    Trouble.STILL_LOADING -> R.string.trouble_still_loading
    Trouble.CANT_PLAY -> R.string.trouble_cant_play
}

/** The button that puts it right, where there is somewhere to go. */
private fun Trouble.fix(): Int? = when (this) {
    Trouble.SERVER_SIGNED_OUT, Trouble.SERVER_REFUSED, Trouble.SERVER_UNREACHABLE -> R.string.trouble_fix_server
    Trouble.NO_INTERNET -> R.string.trouble_fix_internet
    Trouble.NO_FILE_ACCESS -> R.string.trouble_fix_access
    else -> null
}

/**
 * Says why a song is not playing, in place of the silence there used to be. [onFix] opens the
 * place that puts it right (the music server, the phone's internet, Music Box's permissions);
 * a trouble with nowhere to go has only the close button.
 */
@Composable
fun TroubleDialog(trouble: Trouble, onFix: (Trouble) -> Unit, onDismiss: () -> Unit) {
    EInkDialog(onDismiss = onDismiss) {
        TextMMD(
            text = stringResource(trouble.title()),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(10.dp))
        TextMMD(text = stringResource(trouble.body()), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(18.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            trouble.fix()?.let { fix ->
                OutlinedButtonMMD(
                    onClick = { onFix(trouble) },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) { TextMMD(text = stringResource(fix), style = MaterialTheme.typography.bodySmall) }
            }
            OutlinedButtonMMD(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { TextMMD(text = stringResource(R.string.trouble_close), style = MaterialTheme.typography.bodySmall) }
        }
    }
}
