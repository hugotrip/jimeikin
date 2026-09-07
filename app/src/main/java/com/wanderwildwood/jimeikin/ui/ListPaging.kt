package com.wanderwildwood.jimeikin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.lazy.LazyColumnMMD

/**
 * How far one swipe moves a list: a screen, less a row.
 *
 * MMD's list steps a fixed four rows per swipe, which is its measure of its own lists rather
 * than of these. A song row here carries a title and a line under it, so four rows is about
 * half a panel and a swipe leaves most of the screen unread; on the album and artist lists it
 * is nearer two thirds. Messaging reached the same conclusion from the other side and pages by
 * a screenful, and this is that behaviour taken into MMD's component rather than away from it —
 * the chevrons, the track and the step-and-stop all stay, only the number changes.
 *
 * The measure is taken from the list itself rather than written down: a row's height is read
 * from what is on screen, so a screen of tall rows steps fewer and a screen of short ones steps
 * more, and neither has a constant to go stale.
 *
 * **One row of overlap, deliberately.** The step is a screenful *minus one row*, so the last
 * row of the old page is the first row of the new one. It costs a row of the leap and buys a
 * line to read on from, which is the same bargain Messaging strikes.
 *
 * Stepping by index rather than by pixels means a page always opens on a row's edge for free.
 */
@Composable
fun rememberPageScrollStep(state: LazyListState): Int {
    val step by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            val viewport = info.viewportEndOffset - info.viewportStartOffset
            // The first visible row is a fair sample: these lists are uniform within a screen,
            // and where one row is unusually tall it is the one being stepped past anyway.
            val rowHeight = info.visibleItemsInfo.firstOrNull()?.size ?: 0
            if (viewport <= 0 || rowHeight <= 0) {
                DEFAULT_STEP
            } else {
                (viewport / rowHeight - 1).coerceAtLeast(1)
            }
        }
    }
    return step
}

/** What MMD uses, and what this falls back to before the list has measured itself. */
private const val DEFAULT_STEP = 4

/**
 * MMD's list, stepping a screen at a time instead of four rows.
 *
 * Every list in the app goes through this rather than calling the component directly, so the
 * measure is decided once. Each one keeps its own scroll state, which is what makes it safe on
 * a screen whose tabs hold two different lists.
 */
@Composable
fun PagedColumnMMD(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: LazyListScope.() -> Unit,
) {
    val state = rememberLazyListState()
    LazyColumnMMD(
        modifier = modifier,
        state = state,
        contentPadding = contentPadding,
        verticalArrangement = verticalArrangement,
        horizontalAlignment = horizontalAlignment,
        scrollStep = rememberPageScrollStep(state),
        content = content,
    )
}
