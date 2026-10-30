package com.wanderwildwood.jimeikin.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.jimeikin.R
import com.wanderwildwood.jimeikin.data.LicenceDocument
import com.wanderwildwood.jimeikin.data.LicenceText

const val SOURCE_ADDRESS = "https://github.com/wanderwildwood/jimeikin"

/**
 * Where the source is, what this app is licensed under, and everything built into it with its
 * licence. A licence's name opens its whole text in the reader.
 */
@Composable
fun LicencesDialog(onDismiss: () -> Unit) {
    var reading by remember { mutableStateOf<LicenceDocument?>(null) }
    val open = reading
    if (open != null) {
        LicenceReader(open, onBack = { reading = null })
        return
    }
    val components = LicenceText.components(
        stringArrayResource(R.array.licence_components_main).toList() +
            stringArrayResource(R.array.licence_components_extra).toList(),
    )
    val documents = LicenceText.documents(
        stringArrayResource(R.array.licence_texts_main).toList() +
            stringArrayResource(R.array.licence_texts_extra).toList(),
    )
    val context = LocalContext.current
    EInkDialog(onDismiss = onDismiss) {
        PagedColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
            item { TextMMD(stringResource(R.string.licences_title), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold) }
            item { Heading(stringResource(R.string.licences_source_heading)) }
            item { LicenceRow(stringResource(R.string.licences_source_link)) { openWeb(context, SOURCE_ADDRESS) } }
            item { Heading(stringResource(R.string.licences_licence_heading)) }
            item { TextMMD(stringResource(R.string.licences_licence_body), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp)) }
            item { Heading(stringResource(R.string.licences_built_with_heading)) }
            items(components) { c ->
                Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).then(
                    if (c.url != null) Modifier.clickable { openWeb(context, c.url) } else Modifier,
                ), contentAlignment = Alignment.CenterStart) {
                    TextMMD(
                        text = c.name + ", " + c.licence,
                        style = MaterialTheme.typography.labelSmall.copy(
                            textDecoration = if (c.url != null) TextDecoration.Underline else TextDecoration.None,
                        ),
                    )
                }
            }
            item { Heading(stringResource(R.string.licences_texts_heading)) }
            items(documents) { d -> LicenceRow(d.title) { reading = d } }
        }
        Spacer(Modifier.height(14.dp))
        OutlinedButtonMMD(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) { TextMMD(text = stringResource(R.string.about_close), style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun LicenceReader(document: LicenceDocument, onBack: () -> Unit) {
    val context = LocalContext.current
    val pieces = remember(document.asset) {
        runCatching { context.assets.open(document.asset).bufferedReader().use { it.readText() } }
            .map { LicenceText.chunks(it) }
            .getOrDefault(emptyList())
    }
    EInkDialog(onDismiss = onBack) {
        PagedColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
            item { TextMMD(document.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold) }
            items(pieces) { p ->
                TextMMD(p, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
            }
        }
        Spacer(Modifier.height(14.dp))
        OutlinedButtonMMD(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) { TextMMD(text = stringResource(R.string.about_close), style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun Heading(text: String) {
    TextMMD(text = text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp))
}

@Composable
internal fun LicenceRow(text: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick), contentAlignment = Alignment.CenterStart) {
        TextMMD(text = text, style = MaterialTheme.typography.labelSmall.copy(textDecoration = TextDecoration.Underline))
    }
}

/** The Kompakt may have nothing that opens a web address; it says so rather than doing nothing. */
internal fun openWeb(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.onFailure {
        Toast.makeText(context, context.getString(R.string.about_no_browser), Toast.LENGTH_SHORT).show()
    }
}
