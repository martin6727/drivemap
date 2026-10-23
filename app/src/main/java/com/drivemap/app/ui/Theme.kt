package com.drivemap.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

object Palette {
    val Blue = Color(0xFF1A73E8)
    val BlueDark = Color(0xFF1557B0)
    val Green = Color(0xFF188038)
    val NavGreen = Color(0xFF0F7B45)
    val NavGreenDark = Color(0xFF0B5E34)
    val Red = Color(0xFFD93025)
    val Orange = Color(0xFFE37400)
    val Purple = Color(0xFF8E24AA)
    val Gray = Color(0xFF5F6368)
    val LightGray = Color(0xFFF1F3F4)
    val Divider = Color(0xFFDADCE0)
}

private val light = lightColorScheme(
    primary = Palette.Blue,
    onPrimary = Color.White,
    secondary = Palette.Green,
    surface = Color.White,
    onSurface = Color(0xFF202124),
    surfaceVariant = Color(0xFFF1F3F4),
    onSurfaceVariant = Color(0xFF5F6368),
    background = Color.White,
    outline = Color(0xFFDADCE0),
    error = Palette.Red,
)

// Night theme is OLED black: panels are pure #000 (pixels off) with a thin edge (see panelBorder).
private val dark = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF062E6F),
    secondary = Color(0xFF81C995),
    surface = Color.Black,
    onSurface = Color(0xFFE8EAED),
    surfaceVariant = Color(0xFF1A1A1C),
    onSurfaceVariant = Color(0xFF9AA0A6),
    background = Color.Black,
    onBackground = Color(0xFFE8EAED),
    outline = Color(0xFF3C4043),
    outlineVariant = Color(0xFF26262A),
    error = Color(0xFFF28B82),
    // no grey/blue lift on raised panels, dialogs, menus and sheets
    surfaceTint = Color.Black,
    surfaceDim = Color.Black,
    surfaceBright = Color(0xFF1A1A1C),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color.Black,
    surfaceContainer = Color.Black,
    surfaceContainerHigh = Color.Black,
    surfaceContainerHighest = Color(0xFF141416),
)

@Composable
fun DriveMapTheme(darkMode: Boolean, glass: Boolean = false, lightGlass: Boolean = false, clearGlass: Boolean = false, content: @Composable () -> Unit) {
    val base = if (darkMode) dark else light
    // Liquid glass: see-through frosted panels over the map; full-screen pages stay solid
    val scheme = when {
        !glass -> base
        // clear glass (like iOS): no tint at all, just the glass rim; text gets a soft shadow
        clearGlass -> base.copy(
            surface = if (darkMode) Color(0x1A000000) else Color(0x26FFFFFF),
            surfaceVariant = if (darkMode) Color(0x14FFFFFF) else Color(0x12000000),
        )
        // light glass: milky frosted white that lets the map show through; dark text stays crisp
        lightGlass -> base.copy(
            surface = Color(0xB8F4F6FA),
            surfaceVariant = Color(0x1A3C4043),
            surfaceContainer = Color(0xD9F4F6FA),
            surfaceContainerHigh = Color(0xE6F4F6FA),
            outline = Color(0x403C4043),
        )
        else -> base.copy(
            surface = if (darkMode) Color(0xC70C0D10) else Color(0xCCFFFFFF),
            surfaceVariant = if (darkMode) Color(0x1FFFFFFF) else Color(0x14000000),
        )
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalGlass provides glass) {
        if (glass && clearGlass) {
            // readable over any map: a soft halo behind every text (dark at night, light by day)
            val halo = androidx.compose.ui.graphics.Shadow(
                if (darkMode) Color(0xCC000000) else Color(0xE6FFFFFF), androidx.compose.ui.geometry.Offset(0f, 1f), 6f,
            )
            val t = androidx.compose.material3.Typography()
            fun androidx.compose.ui.text.TextStyle.h() = copy(shadow = halo)
            val typo = t.copy(
                displayLarge = t.displayLarge.h(), displayMedium = t.displayMedium.h(), displaySmall = t.displaySmall.h(),
                headlineLarge = t.headlineLarge.h(), headlineMedium = t.headlineMedium.h(), headlineSmall = t.headlineSmall.h(),
                titleLarge = t.titleLarge.h(), titleMedium = t.titleMedium.h(), titleSmall = t.titleSmall.h(),
                bodyLarge = t.bodyLarge.h(), bodyMedium = t.bodyMedium.h(), bodySmall = t.bodySmall.h(),
                labelLarge = t.labelLarge.h(), labelMedium = t.labelMedium.h(), labelSmall = t.labelSmall.h(),
            )
            MaterialTheme(colorScheme = scheme, typography = typo, content = content)
        } else {
            MaterialTheme(colorScheme = scheme, content = content)
        }
    }
}

/** True when the "Liquid glass" look is chosen in Settings. */
val LocalGlass = androidx.compose.runtime.staticCompositionLocalOf { false }

/** True while the night (OLED black) theme is active. */
val isNight: Boolean
    @Composable get() = MaterialTheme.colorScheme.surface.luminance() < 0.5f

/** Thin edge that keeps black panels visible on the dark map (none by day, shadows do that). */
val panelBorder: BorderStroke?
    @Composable get() = when {
        // glass: a bright edge fading downwards, like light catching the rim of a glass pane
        LocalGlass.current -> BorderStroke(
            1.2.dp,
            androidx.compose.ui.graphics.Brush.verticalGradient(
                listOf(Color.White.copy(alpha = if (isNight) 0.45f else 0.9f), Color.White.copy(alpha = if (isNight) 0.06f else 0.25f)),
            ),
        )
        isNight -> BorderStroke(1.dp, Color(0xFF2E2E32))
        else -> null
    }


/** Status backgrounds: soft pastel by day, deep tone at night so nothing glares. */
object Tints {
    val blue: Color @Composable get() = if (isNight) Color(0xFF0D2240) else Color(0xFFE8F0FE)
    val onBlue: Color @Composable get() = if (isNight) Color(0xFF8AB4F8) else Palette.BlueDark
    val orange: Color @Composable get() = if (isNight) Color(0xFF2B1C05) else Color(0xFFFFF4E5)
    val onOrange: Color @Composable get() = if (isNight) Color(0xFFFDB863) else Color(0xFF7A4100)
    val green: Color @Composable get() = if (isNight) Color(0xFF0A2614) else Color(0xFFE6F4EA)
    val onGreen: Color @Composable get() = if (isNight) Color(0xFF81C995) else Palette.Green
    val red: Color @Composable get() = if (isNight) Color(0xFF3A0D0A) else Color(0xFFFCE8E6)
}

/** Material icon paths for glyphs not in material-icons-core. */
object Glyphs {
    private fun icon(name: String, path: String): ImageVector = ImageVector.Builder(
        name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black)).build()

    val MyLocation = icon("myloc", "M12,8c-2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4 -1.79,-4 -4,-4zM20.94,11c-0.46,-4.17 -3.77,-7.48 -7.94,-7.94L13,1h-2v2.06C6.83,3.52 3.52,6.83 3.06,11L1,11v2h2.06c0.46,4.17 3.77,7.48 7.94,7.94L11,23h2v-2.06c4.17,-0.46 7.48,-3.77 7.94,-7.94L23,13v-2h-2.06zM12,19c-3.87,0 -7,-3.13 -7,-7s3.13,-7 7,-7 7,3.13 7,7 -3.13,7 -7,7z")
    val Directions = icon("dir", "M21.71,11.29l-9,-9c-0.39,-0.39 -1.02,-0.39 -1.41,0l-9,9c-0.39,0.39 -0.39,1.02 0,1.41l9,9c0.39,0.39 1.02,0.39 1.41,0l9,-9c0.39,-0.38 0.39,-1.01 0,-1.41zM14,14.5V12h-4v3H8v-4c0,-0.55 0.45,-1 1,-1h5V7.5l3.5,3.5 -3.5,3.5z")
    val Car = icon("car", "M18.92,6.01C18.72,5.42 18.16,5 17.5,5h-11c-0.66,0 -1.21,0.42 -1.42,1.01L3,12v8c0,0.55 0.45,1 1,1h1c0.55,0 1,-0.45 1,-1v-1h12v1c0,0.55 0.45,1 1,1h1c0.55,0 1,-0.45 1,-1v-8l-2.08,-5.99zM6.5,16c-0.83,0 -1.5,-0.67 -1.5,-1.5S5.67,13 6.5,13s1.5,0.67 1.5,1.5S7.33,16 6.5,16zM17.5,16c-0.83,0 -1.5,-0.67 -1.5,-1.5s0.67,-1.5 1.5,-1.5 1.5,0.67 1.5,1.5 -0.67,1.5 -1.5,1.5zM5,11l1.5,-4.5h11L19,11H5z")
    val Navigation = icon("nav", "M12,2L4.5,20.29l0.71,0.71L12,18l6.79,3 0.71,-0.71z")
    val VolumeUp = icon("vol", "M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02zM14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z")
    val VolumeOff = icon("voloff", "M16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v2.21l2.45,2.45c0.03,-0.2 0.05,-0.41 0.05,-0.63zM19,12c0,0.94 -0.2,1.82 -0.54,2.64l1.51,1.51C20.63,14.91 21,13.5 21,12c0,-4.28 -2.99,-7.86 -7,-8.77v2.06c2.89,0.86 5,3.54 5,6.71zM4.27,3L3,4.27 7.73,9H3v6h4l5,5v-6.73l4.25,4.25c-0.67,0.52 -1.42,0.93 -2.25,1.18v2.06c1.38,-0.31 2.63,-0.95 3.69,-1.81L19.73,21 21,19.73l-9,-9L4.27,3zM12,4L9.91,6.09 12,8.18V4z")
    val Tune = icon("tune", "M3,17v2h6v-2H3zM3,5v2h10V5H3zM13,21v-2h8v-2h-8v-2h-2v6h2zM7,9v2H3v2h4v2h2V9H7zM21,13v-2H11v2h10zM15,9h2V7h4V5h-4V3h-2v6z")
    val Group = icon("group", "M16,11c1.66,0 2.99,-1.34 2.99,-3S17.66,5 16,5c-1.66,0 -3,1.34 -3,3s1.34,3 3,3zM8,11c1.66,0 2.99,-1.34 2.99,-3S9.66,5 8,5C6.34,5 5,6.34 5,8s1.34,3 3,3zM8,13c-2.33,0 -7,1.17 -7,3.5V19h14v-2.5c0,-2.33 -4.67,-3.5 -7,-3.5zM16,13c-0.29,0 -0.62,0.02 -0.97,0.05 1.16,0.84 1.97,1.97 1.97,3.45V19h6v-2.5c0,-2.33 -4.67,-3.5 -7,-3.5z")
    val Camera = icon("cam", "M9,2L7.17,4H4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2h-3.17L15,2H9zM12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5zM12,9a3,3 0,1 0,0 6a3,3 0,1 0,0 -6z")
    val Compass = icon("compass", "M12,10.9c-0.61,0 -1.1,0.49 -1.1,1.1s0.49,1.1 1.1,1.1c0.61,0 1.1,-0.49 1.1,-1.1s-0.49,-1.1 -1.1,-1.1zM12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM14.19,14.19L6,18l3.81,-8.19L18,6l-3.81,8.19z")
    val Flag = icon("flag", "M14.4,6L14,4H5v17h2v-7h5.6l0.4,2h7V6z")
    val Swap = icon("swap", "M16,17.01V10h-2v7.01h-3L15,21l4,-3.99h-3zM9,3L5,6.99h3V14h2V6.99h3L9,3z")
    val Fuel = icon("fuel", "M19.77,7.23l0.01,-0.01 -3.72,-3.72L15,4.56l2.11,2.11c-0.94,0.36 -1.61,1.26 -1.61,2.33 0,1.38 1.12,2.5 2.5,2.5 0.36,0 0.69,-0.08 1,-0.21v7.21c0,0.55 -0.45,1 -1,1s-1,-0.45 -1,-1V14c0,-1.1 -0.9,-2 -2,-2h-1V5c0,-1.1 -0.9,-2 -2,-2H6c-1.1,0 -2,0.9 -2,2v16h10v-7.5h1.5v5c0,1.38 1.12,2.5 2.5,2.5s2.5,-1.12 2.5,-2.5V9c0,-0.69 -0.28,-1.32 -0.73,-1.77zM12,10H6V5h6v5zM18,10c-0.55,0 -1,-0.45 -1,-1s0.45,-1 1,-1 1,0.45 1,1 -0.45,1 -1,1z")
    val Parking = icon("parking", "M13,3H6v18h4v-6h3c3.31,0 6,-2.69 6,-6s-2.69,-6 -6,-6zM13.2,11H10V7h3.2c1.1,0 2,0.9 2,2s-0.9,2 -2,2z")
    val Restaurant = icon("food", "M11,9H9V2H7v7H5V2H3v7c0,2.12 1.66,3.84 3.75,3.97V22h2.5v-9.03C11.34,12.84 13,11.12 13,9V2h-2v7zM16,6v8h2.5v8H21V2c-2.76,0 -5,2.24 -5,4z")
    val Cafe = icon("cafe", "M20,3H4v10c0,2.21 1.79,4 4,4h6c2.21,0 4,-1.79 4,-4v-3h2c1.11,0 2,-0.9 2,-2V5c0,-1.11 -0.89,-2 -2,-2zM20,8h-2V5h2v3zM4,19h16v2H4z")
    val EvStation = icon("ev", "M19.77,7.23l0.01,-0.01 -3.72,-3.72L15,4.56l2.11,2.11c-0.94,0.36 -1.61,1.26 -1.61,2.33 0,1.38 1.12,2.5 2.5,2.5 0.36,0 0.69,-0.08 1,-0.21v7.21c0,0.55 -0.45,1 -1,1s-1,-0.45 -1,-1V14c0,-1.1 -0.9,-2 -2,-2h-1V5c0,-1.1 -0.9,-2 -2,-2H6c-1.1,0 -2,0.9 -2,2v16h10v-7.5h1.5v5c0,1.38 1.12,2.5 2.5,2.5s2.5,-1.12 2.5,-2.5V9c0,-0.69 -0.28,-1.32 -0.73,-1.77zM18,10c-0.55,0 -1,-0.45 -1,-1s0.45,-1 1,-1 1,0.45 1,1 -0.45,1 -1,1zM8,18v-4.5H6L10,6v5h2l-4,7z")
    val Hotel = icon("hotel", "M7,13c1.66,0 3,-1.34 3,-3S8.66,7 7,7s-3,1.34 -3,3 1.34,3 3,3zM19,7h-8v7H3V5H1v15h2v-3h18v3h2v-9c0,-2.21 -1.79,-4 -4,-4z")
    val Copy = icon("copy", "M16,1H4c-1.1,0 -2,0.9 -2,2v14h2V3h12V1zM19,5H8c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h11c1.1,0 2,-0.9 2,-2V7c0,-1.1 -0.9,-2 -2,-2zM19,21H8V7h11v14z")
    val Ticket = icon("ticket", "M22,10V6c0,-1.11 -0.9,-2 -2,-2H4c-1.1,0 -1.99,0.89 -1.99,2v4c1.1,0 1.99,0.9 1.99,2s-0.89,2 -2,2v4c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2v-4c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2zM13,17.5h-2v-2h2v2zM13,13h-2v-2h2v2zM13,8.5h-2v-2h2v2z")
    val Route = icon("route", "M19,15.18V7c0,-2.21 -1.79,-4 -4,-4s-4,1.79 -4,4v10c0,1.1 -0.9,2 -2,2s-2,-0.9 -2,-2V8.82C8.16,8.4 9,7.3 9,6c0,-1.66 -1.34,-3 -3,-3S3,4.34 3,6c0,1.3 0.84,2.4 2,2.82V17c0,2.21 1.79,4 4,4s4,-1.79 4,-4V7c0,-1.1 0.9,-2 2,-2s2,0.9 2,2v8.18c-1.16,0.41 -2,1.51 -2,2.82 0,1.66 1.34,3 3,3s3,-1.34 3,-3c0,-1.3 -0.84,-2.4 -2,-2.82z")
    val Speed = icon("speed", "M20.38,8.57l-1.23,1.85a8,8 0,0 1,-0.22 7.58H5.07A8,8 0,0 1,15.58 6.85l1.85,-1.23A10,10 0,0 0,3.35 19a2,2 0,0 0,1.72 1h13.85a2,2 0,0 0,1.74 -1,10 10,0 0,0 -0.27,-10.44zM10.59,15.41a2,2 0,0 0,2.83 0l5.66,-8.49 -8.49,5.66a2,2 0,0 0,0 2.83z")
}

/** Width of the side panels when the phone is held sideways. */
val LANDSCAPE_PANEL = 380.dp

/** True when the phone is held sideways (wider than tall). */
@Composable
fun isLandscape(): Boolean {
    val c = androidx.compose.ui.platform.LocalConfiguration.current
    return c.screenWidthDp > c.screenHeightDp
}

/** Drop shadow for panels/buttons: none on glass, where it would show through as a dark blob. */
@Composable
fun glassShadow(d: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp = if (LocalGlass.current) 0.dp else d
