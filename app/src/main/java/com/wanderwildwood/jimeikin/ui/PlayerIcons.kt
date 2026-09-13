package com.wanderwildwood.jimeikin.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The five transport glyphs, taken from Audio Reading so that the two players are the same
 * player.
 *
 * They are the **filled** cut of the Material Symbols, not the outlined one Material Icons
 * gives by default. A hollow arrowhead at this size on a low-contrast panel reads as a
 * smudge, and these are the marks pressed most often on the screen: they should be the most
 * solid ones on it.
 *
 * Not `Icons`, which is already the name Material Icons brings into every screen here.
 */
object PlayerIcons {

    /**
     * Material Symbols are authored in a 960 grid whose origin sits at the bottom left, so the
     * path data runs from -960 to 0 vertically. Shifting the whole thing down by 960 puts it in
     * the top-left grid everything else here uses.
     */
    private fun symbol(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 960f,
            viewportHeight = 960f,
        )
            .addGroup(name = name, translationY = 960f)
            .addPath(
                pathData = PathParser().parsePathString(pathData).toNodes(),
                fill = SolidColor(Color.Black),
            )
            .clearGroup()
            .build()

    val Previous: ImageVector = symbol("Previous", "M220-240v-480h80v480h-80Zm520 0L380-480l360-240v480Z")
    val Rewind: ImageVector = symbol("Rewind", "M860-240 500-480l360-240v480Zm-400 0L100-480l360-240v480Z")
    val Play: ImageVector = symbol("Play", "M320-200v-560l440 280-440 280Z")
    val Pause: ImageVector = symbol("Pause", "M560-200v-560h160v560H560Zm-320 0v-560h160v560H240Z")
    val Forward: ImageVector = symbol("Forward", "M100-240v-480l360 240-360 240Zm400 0v-480l360 240-360 240Z")
    val Next: ImageVector = symbol("Next", "M660-240v-480h80v480h-80Zm-440 0v-480l360 240-360 240Z")
}
