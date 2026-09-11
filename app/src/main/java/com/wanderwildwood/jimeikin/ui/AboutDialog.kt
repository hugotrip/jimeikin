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
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.wanderwildwood.jimeikin.R

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
            fontWeight = FontWeight.Bold,
        )

        Spacer(Modifier.height(14.dp))
        TextMMD(
            text = "Your library and your settings stay on the phone. Searching, streaming " +
                "and downloading talk to YouTube; connecting an account stores its cookie " +
                "here until you disconnect it. A music server you point this at is asked " +
                "for its library and its songs; its password is kept on this phone, because " +
                "each request is signed with it rather than carrying it. That password and " +
                "the YouTube cookie are sealed with a key held in this phone's keystore and " +
                "are left out of Android's backups, so neither leaves with one. " +
                "Radio stations come from radio.garden, which is asked what is on the air " +
                "in a place; a station you keep is stored as its own address and plays " +
                "without asking anything further. The FM row opens the phone's own tuner and " +
                "does nothing else. This app does not read your notifications and does not " +
                "ask for an accessibility service. There are no ads, no analytics and " +
                "nothing is counted.",
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
        TextMMD(
            text = "Music servers are reached over the Subsonic API, which Navidrome, " +
                "Airsonic and Gonic all speak.",
            fontSize = 14.sp,
        )

        Spacer(Modifier.height(14.dp))
        TextMMD(text = "github.com/wanderwildwood/jimeikin", fontSize = 14.sp)

        Spacer(Modifier.height(14.dp))
        Llama()

        Spacer(Modifier.height(18.dp))
        OutlinedButtonMMD(
            onClick = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) { TextMMD(text = "Close", fontSize = 15.sp) }
    }
}

/**
 * A llama at the foot of the About, which opens the page a donation goes to.
 *
 * Three words rather than an address: a verb and an object, so what happens when you press
 * them is not a surprise even though the page is not named. The drawing is his own, and it is
 * ink rather than an emoji, which is a colour glyph and reaches the panel as a pale smudge.
 *
 * The Kompakt may have nothing registered for a web address, so the intent is allowed to fail
 * quietly rather than take the dialog down with it.
 */
@Composable
private fun Llama() {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://hotspringsllamas.org/donate/")),
                    )
                }
            }
            .padding(vertical = 4.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.llama),
            contentDescription = null,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(10.dp))
        TextMMD(text = "Feed the llamas", fontSize = 14.sp)
    }
}
