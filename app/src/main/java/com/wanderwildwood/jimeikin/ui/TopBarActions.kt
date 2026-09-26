package com.wanderwildwood.jimeikin.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The buttons a screen puts in the top bar, beside the headphones.
 *
 * They were floating buttons stacked in the bottom right, over the last rows of the list: a
 * row there could not be reached without scrolling it out from under them, and each one was
 * a filled disc the panel had to draw and undraw on every page. The screen still owns what
 * its buttons do; the top bar only draws them.
 */
class TopBarActionsSlot {
    var content by mutableStateOf<(@Composable () -> Unit)?>(null)
}

val LocalTopBarActions = staticCompositionLocalOf { TopBarActionsSlot() }

@Composable
fun TopBarActions(content: @Composable () -> Unit) {
    val slot = LocalTopBarActions.current
    val latest by rememberUpdatedState(content)
    DisposableEffect(slot) {
        val mine: @Composable () -> Unit = { latest() }
        slot.content = mine
        // Only clear what is still ours: on the way between two screens the arriving one
        // registers before the leaving one is disposed.
        onDispose { if (slot.content === mine) slot.content = null }
    }
}
