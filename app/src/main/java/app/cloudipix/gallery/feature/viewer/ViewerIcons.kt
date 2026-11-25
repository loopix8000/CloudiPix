package app.cloudipix.gallery.feature.viewer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

// Adapted from Lucide 1.78.0 with squared strokes for the compact viewer controls.
internal object ViewerIcons {
    val Back = lucide("m15 18-6-6 6-6", mirrored = true)
    val Panorama = lucide(
        "M22 12a10 10 0 1 0 -20 0a10 10 0 1 0 20 0z",
        "M12 2a14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0-20",
        "M2 12h20",
    )
    val Info = lucide("M22 12a10 10 0 1 0 -20 0a10 10 0 1 0 20 0z", "M12 16v-4", "M12 8h.01")
    val Close = lucide("M18 6 6 18", "m6 6 12 12")
    val Play = lucide("M6 4L20 12L6 20Z")
    val Pause = lucide(
        "M14 4H18V20H14Z",
        "M6 4H10V20H6Z",
    )
    val VolumeOn = lucide(
        "M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298z",
        "M16 9a5 5 0 0 1 0 6",
        "M19.364 18.364a9 9 0 0 0 0-12.728",
    )
    val VolumeOff = lucide(
        "M11 4.702a.7.7 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.7.7 0 0 0 11 19.298z",
        "m16.5 14.5 5-5",
        "m16.5 9.5 5 5",
    )
    val Edit = lucide(
        "M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z",
        "m15 5 4 4",
    )
}

private fun lucide(vararg paths: String, mirrored: Boolean = false): ImageVector =
    ImageVector.Builder(
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = mirrored,
    ).apply {
        val ink = SolidColor(Color.Black)
        paths.forEach { path ->
            addPath(
                pathData = PathParser().parsePathString(path).toNodes(),
                stroke = ink,
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Square,
                strokeLineJoin = StrokeJoin.Miter,
                strokeLineMiter = 2.5f,
            )
        }
    }.build()
