package com.wanderwildwood.jimeikin.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.jimeikin.BuildConfig
import com.wanderwildwood.jimeikin.R

/**
 * What this is, what it sends where, its terms, what it is built with and where its source is.
 * The words are a list that pages, so the dialog fits any screen; Close stays under it.
 */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    EInkDialog(onDismiss = onDismiss) {
        PagedColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
            item {
                TextMMD(
                    text = stringResource(R.string.about_title, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            item { Para(stringResource(R.string.about_what)) }
            item { Heading(stringResource(R.string.about_privacy_heading)) }
            item { Para(stringResource(R.string.about_privacy_phone), top = 4.dp) }
            item { Para(stringResource(R.string.about_privacy_online)) }
            item { Para(stringResource(R.string.about_privacy_close)) }
            item { Heading(stringResource(R.string.about_terms_heading)) }
            item { Para(stringResource(R.string.about_terms), top = 4.dp) }
            item { Heading(stringResource(R.string.about_open_source_heading)) }
            item { Para(stringResource(R.string.about_built_on), top = 4.dp) }
            item { Para(stringResource(R.string.about_bundled)) }
            item { WebLink(stringResource(R.string.about_source), "https://github.com/wanderwildwood/jimeikin") }
            item { Spacer(Modifier.height(6.dp)); Llama() }
        }

        Spacer(Modifier.height(14.dp))
        OutlinedButtonMMD(
            onClick = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) { TextMMD(text = stringResource(R.string.about_close), style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun Heading(text: String) {
    TextMMD(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 14.dp),
    )
}

@Composable
private fun Para(text: String, top: Dp = 10.dp) {
    TextMMD(text = text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = top))
}

/** Words that open a web address, underlined so they read as a link, on a 48dp row. */
@Composable
private fun WebLink(text: String, url: String) {
    val context = LocalContext.current
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable { openWeb(context, url) },
    ) {
        TextMMD(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(textDecoration = TextDecoration.Underline),
        )
    }
}

/** The Kompakt may have nothing that opens a web address; it says so rather than doing nothing. */
private fun openWeb(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.onFailure {
        Toast.makeText(context, context.getString(R.string.about_no_browser), Toast.LENGTH_SHORT).show()
    }
}

/**
 * A llama at the foot of the About, which opens the page a donation goes to.
 *
 * Three words rather than an address: a verb and an object, so what happens when you press
 * them is not a surprise even though the page is not named. The drawing is his own, and it is
 * ink rather than an emoji, which is a colour glyph and reaches the panel as a pale smudge.
 * The site's address sits at the start of the same line and opens the site; the llama and its words
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
        TextMMD(
            text = "wanderthe.dev",
            style = MaterialTheme.typography.labelSmall.copy(textDecoration = TextDecoration.Underline),
            // The site's address opens the site, the way the llama beside it opens its page.
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clickable { openWeb(context, "https://wanderthe.dev") }
                .wrapContentHeight(),
        )
        Spacer(Modifier.width(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = 48.dp)
                // Straight to the checkout. The Donate button on the site only leads
                // here anyway, so the page in between is a press the reader does not need.
                // The short square.link form, not the long checkout.square.site address it
                // redirects to -- the short one is what the site itself links to, so a
                // regenerated checkout follows it and a published app does not break.
                .clickable { openWeb(context, "https://square.link/u/AGu8oT10") },
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
