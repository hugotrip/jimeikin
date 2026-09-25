package com.wanderwildwood.jimeikin.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.jimeikin.BuildConfig
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
            text = stringResource(R.string.about_title, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
        )

        Spacer(Modifier.height(14.dp))
        // Paged, not scrolled, one block at a time. At 480x800 this runs taller than the panel
        // and used to run off the bottom with Close underneath it. MMD's list steps four items a
        // swipe, and four of these blocks are more than a screen.
        LazyColumnMMD(
            modifier = Modifier.heightIn(max = 400.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            scrollStep = 1,
        ) {
            item {
                TextMMD(
                    text = stringResource(R.string.about_privacy),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            item {
                Column {
                    TextMMD(text = stringResource(R.string.about_licence), style = MaterialTheme.typography.labelSmall)
                    TextMMD(
                        text = stringResource(R.string.about_built_on),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    TextMMD(
                        text = "NewPipeExtractor 0.26.5 — TeamNewPipe, GPL-3.0",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    TextMMD(text = "MMD — Mudita, Apache 2.0", style = MaterialTheme.typography.labelSmall)
                    TextMMD(
                        text = stringResource(R.string.about_subsonic),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            item { Llama() }
        }

        Spacer(Modifier.height(18.dp))
        OutlinedButtonMMD(
            onClick = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) { TextMMD(text = stringResource(R.string.about_close), style = MaterialTheme.typography.bodySmall) }
    }
}

/**
 * A llama at the foot of the About, which opens the page a donation goes to.
 *
 * Three words rather than an address: a verb and an object, so what happens when you press
 * them is not a surprise even though the page is not named. The drawing is his own, and it is
 * ink rather than an emoji, which is a colour glyph and reaches the panel as a pale smudge.
 * The site's address sits at the start of the same line, and only the llama and its words
 * open the page.
 *
 * The Kompakt may have nothing registered for a web address, so the intent is allowed to fail
 * quietly rather than take the dialog down with it.
 */
@Composable
private fun Llama() {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        TextMMD(text = "wanderthe.dev", style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.width(12.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable {
                    // Straight to the checkout. The Donate button on the site only leads
                    // here anyway, so the page in between is a press the reader does not need.
                    // The short square.link form, not the long checkout.square.site address it
                    // redirects to -- the short one is what the site itself links to, so a
                    // regenerated checkout follows it and a published app does not break.
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://square.link/u/AGu8oT10")),
                        )
                    }.onFailure {
                        Toast.makeText(context, context.getString(R.string.about_no_browser), Toast.LENGTH_SHORT).show()
                    }
                }
                .padding(vertical = 4.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.llama),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(6.dp))
            TextMMD(text = stringResource(R.string.about_feed_the_llamas), style = MaterialTheme.typography.labelSmall)
        }
    }
}
