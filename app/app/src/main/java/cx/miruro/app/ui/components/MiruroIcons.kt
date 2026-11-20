package cx.miruro.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * `material-icons-core` carries no bookmark, and the watchlist needs a filled/outlined pair so a
 * saved title reads differently from an unsaved one. Paths are the Material 24dp glyphs.
 */
object MiruroIcons {
    val Bookmark: ImageVector =
        bookmark(
            "Bookmark",
            "M17,3H7c-1.1,0 -1.99,0.9 -1.99,2L5,21l7,-3 7,3V5c0,-1.1 -0.9,-2 -2,-2z"
        )

    val BookmarkBorder: ImageVector =
        bookmark(
            "BookmarkBorder",
            "M17,3H7c-1.1,0 -2,0.9 -2,2v16l7,-3 7,3V5c0,-1.1 -0.9,-2 -2,-2zM17,18l-5,-2.18L7,18V5h10v13z"
        )

    private fun bookmark(name: String, pathData: String): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    )
        .addPath(addPathNodes(pathData), fill = SolidColor(Color.Black))
        .build()
}
