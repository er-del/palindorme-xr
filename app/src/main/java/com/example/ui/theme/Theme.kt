package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// ─────────────────────────────────────────────────────────────
// Theme-only tokens
// Opaque on purpose: Material components composite these on top of
// other surfaces, so solid colors stay predictable. Translucent glass
// lives in Color.kt and is applied per-composable.
// ─────────────────────────────────────────────────────────────
private val SkyContainer = Color(0xFF0E2942)
private val IndigoContainer = Color(0xFF1E2247)
private val AmberContainerSolid = Color(0xFF3A2A0A)
private val RoseContainerSolid = Color(0xFF3A1620)
private val AmberBright = Color(0xFFFCD34D)
private val RoseBright = Color(0xFFFDA4AF)
private val SurfaceHighest = Color(0xFF1F2C4A)

/**
 * Surface ladder (dark → light):
 * Canvas → SurfaceObsidian → SurfaceRaised → SurfaceOverlay → SurfaceHighest
 * Material's Card, NavigationBar, bottom sheets and menus pick from this
 * ladder, so they stay in the obsidian family instead of the default grey.
 *
 * Requires Material3 1.2+ for the surfaceContainer* roles.
 */
private val SpatialObsidianColorScheme = darkColorScheme(
    // Primary: sky
    primary = SkyPrimary,
    onPrimary = OnAccent,
    primaryContainer = SkyContainer,
    onPrimaryContainer = TextPrimary,
    inversePrimary = SkyPrimaryDeep,

    // Secondary: indigo
    secondary = IndigoSecondary,
    onSecondary = OnAccent,
    secondaryContainer = IndigoContainer,
    onSecondaryContainer = TextPrimary,

    // Tertiary: amber
    tertiary = AmberWarning,
    onTertiary = OnAccent,
    tertiaryContainer = AmberContainerSolid,
    onTertiaryContainer = AmberBright,

    // Canvas & surfaces
    background = CanvasObsidian,
    onBackground = TextPrimary,
    surface = SurfaceObsidian,
    onSurface = TextPrimary,
    surfaceVariant = SpatialGlassOpaque,
    onSurfaceVariant = TextSecondary,
    surfaceTint = SkyPrimary,

    surfaceDim = CanvasObsidian,
    surfaceBright = SurfaceOverlay,
    surfaceContainerLowest = CanvasObsidian,
    surfaceContainerLow = SurfaceObsidian,
    surfaceContainer = SurfaceRaised,
    surfaceContainerHigh = SurfaceOverlay,
    surfaceContainerHighest = SurfaceHighest,

    // Snackbars use inverseSurface. Dark glass fits better than a white bar.
    inverseSurface = SurfaceOverlay,
    inverseOnSurface = TextPrimary,

    // Error
    error = RoseError,
    onError = OnAccent,
    errorContainer = RoseContainerSolid,
    onErrorContainer = RoseBright,

    // Edges
    outline = OutlineSubtle,
    outlineVariant = GlassHighlightSoft,

    // Material applies its own alpha to the scrim, so keep this opaque.
    scrim = CanvasObsidian
)

val PalindormeShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun PalindormeXRTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = SpatialObsidianColorScheme,
        typography = PalindormeTypography,
        shapes = PalindormeShapes,
        content = content
    )
}