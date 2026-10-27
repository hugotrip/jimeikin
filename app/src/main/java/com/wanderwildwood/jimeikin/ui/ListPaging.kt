package com.wanderwildwood.jimeikin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The shared [PagedColumn], turning a page on whole rows: the row a page cut at its foot opens
 * the next one, and the last page keeps its rows whole with blank space under them.
 *
 * Every list in the app goes through this rather than calling the component directly, so each
 * fills the room it is given and runs the full width. Each one keeps its own scroll state, which
 * is what makes it safe on a screen whose tabs hold two different lists.
 */
@Composable
fun PagedColumnMMD(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    /** A swipe down on the first page; see [PagedColumn]. Null where the list lives on the phone. */
    onPullAtTop: (() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    PagedColumn(
        modifier = modifier.fillMaxSize(),
        state = rememberLazyListState(),
        contentPadding = contentPadding,
        verticalArrangement = verticalArrangement,
        horizontalAlignment = horizontalAlignment,
        onPullAtTop = onPullAtTop,
        content = content,
    )
}
