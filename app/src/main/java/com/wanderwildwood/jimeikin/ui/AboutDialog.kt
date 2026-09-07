package com.wanderwildwood.jimeikin.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.jimeikin.BuildConfig

/**
 * What this is, what it sends, what it is made of, and where the source lives.
 *
 * The sending line is here because a reader could not guess the answer: most of the app is
 * a local music player that touches nothing, and then one part of it talks to YouTube and
 * can hold a signed-in account. An app that only read the card would owe nobody this.
 */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    EInkDialog(onDismiss = onDismiss) {
        TextMMD(
            text = "Music Box ${BuildConfig.VERSION_NAME}",
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
        )

        Spacer(Modifier.height(14.dp))
        TextMMD(
            text = "Your library and your settings stay on the phone. Searching, streaming " +
                "and downloading talk to YouTube; connecting an account stores its cookie " +
                "here until you disconnect it. The FM radio reads the tuner's notification " +
                "and, if you allow it, presses play in the tuner for you. There are no ads, " +
                "no analytics and nothing is counted.",
            fontSize = 14.sp,
        )

        Spacer(Modifier.height(14.dp))
        TextMMD(text = "GNU General Public License v3", fontSize = 14.sp)
        TextMMD(
            text = "Built on CalmMusic by David Ray Wilson, same licence",
            fontSize = 14.sp,
        )
        TextMMD(
            text = "NewPipeExtractor 0.26.5 — TeamNewPipe, GPL-3.0",
            fontSize = 14.sp,
        )
        TextMMD(text = "MMD — Mudita, Apache 2.0", fontSize = 14.sp)

        Spacer(Modifier.height(14.dp))
        TextMMD(text = "github.com/wanderwildwood/jimeikin", fontSize = 14.sp)

        Spacer(Modifier.height(18.dp))
        OutlinedButtonMMD(
            onClick = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) { TextMMD(text = "Close", fontSize = 15.sp) }
    }
}
