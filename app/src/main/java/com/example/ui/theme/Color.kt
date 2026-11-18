package com.example.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/*
 * Liquid Glass · Spatial Obsidian
 *
 * Layering model (back to front):
 *   1. Canvas        – deep obsidian with soft aurora light behind it
 *   2. Glass panels  – frosted fills that let the aurora bleed through
 *   3. Rim light     – a thin specular edge, brightest at the top-left
 *   4. Content       – crisp text and accents on top
 *
 * Alpha reference: 4% = 0A · 8% = 14 · 14% = 24 · 28% = 47 · 40% = 66 · 72% = B8 · 95% = F2
 */

// ─────────────────────────────────────────────────────────────
// 1. Canvas & surfaces
// ─────────────────────────────────────────────────────────────
val CanvasObsidian = Color(0xFF060913)
val SurfaceObsidian = Color(0xFF0C1222)
val SurfaceRaised = Color(0xFF131C31)        // cards that sit above SurfaceObsidian
val SurfaceOverlay = Color(0xFF18233D)       // sheets, menus, dialogs
val ScrimObsidian = Color(0xB3030610)        // ~70% modal scrim

// ─────────────────────────────────────────────────────────────
// 2. Glass fills
// ─────────────────────────────────────────────────────────────

// Tinted glass (original tokens, refined)
val SpatialGlass = Color(0xB8141D33)             // ~72%: default panel, blur shows through
val SpatialGlassOpaque = Color(0xFF141D33)       // fallback when blur is unavailable
val SpatialGlassHighContrast = Color(0xF20A101E) // ~95%: text-heavy surfaces, dialogs

// Frost layers (white-tinted, stack on top of any glass)
val GlassFrostSoft = Color(0x0AFFFFFF)           // 4%  – resting chips, inactive items
val GlassFrost = Color(0x14FFFFFF)               // 8%  – default frosted layer
val GlassFrostStrong = Color(0x24FFFFFF)         // 14% – hovered, selected, pressed

// ─────────────────────────────────────────────────────────────
// 3. Edges & rim light
// ─────────────────────────────────────────────────────────────
val SkyPrimaryDim = Color(0x4738BDF8)            // 28%: 1dp glass edge, tinted
val OutlineSubtle = Color(0x3338BDF8)            // 20%: dividers, inactive outlines
val GlassHighlight = Color(0x66FFFFFF)           // 40%: specular rim, top-left edge
val GlassHighlightSoft = Color(0x1FFFFFFF)       // 12%: secondary rim, opposite edge
val GlassInnerShadow = Color(0x40000000)         // 25%: bottom edge depth

// ─────────────────────────────────────────────────────────────
// 4. Accents
// ─────────────────────────────────────────────────────────────
val SkyPrimary = Color(0xFF38BDF8)
val SkyPrimaryBright = Color(0xFF7DD3FC)         // highlights, pressed glow
val SkyPrimaryDeep = Color(0xFF0EA5E9)           // pressed state, gradient end
val SkyGlow = Color(0x2638BDF8)                  // 15%: focus halos, selected backdrop

val IndigoSecondary = Color(0xFF818CF8)
val IndigoBright = Color(0xFFA5B4FC)
val IndigoGlow = Color(0x26818CF8)

val OnAccent = Color(0xFF04121F)                 // text/icons on SkyPrimary fills

// ─────────────────────────────────────────────────────────────
// 5. Status
// ─────────────────────────────────────────────────────────────
val AmberWarning = Color(0xFFF59E0B)
val RoseError = Color(0xFFFB7185)
val EmeraldActive = Color(0xFF34D399)

// Soft containers (badge, banner, and chip backgrounds)
val AmberContainer = Color(0x29F59E0B)           // 16%
val RoseContainer = Color(0x29FB7185)
val EmeraldContainer = Color(0x2934D399)

// ─────────────────────────────────────────────────────────────
// 6. Text
// ─────────────────────────────────────────────────────────────
val TextPrimary = Color(0xFFF8FAFC)
val TextSecondary = Color(0xFFCBD5E1)
val TextMuted = Color(0xFF94A3B8)
val TextDisabled = Color(0xFF64748B)

// ─────────────────────────────────────────────────────────────
// 7. Aurora light (sits behind glass so the frost has something to refract)
// ─────────────────────────────────────────────────────────────
val AuroraSky = Color(0x4D38BDF8)                // 30%
val AuroraIndigo = Color(0x4D818CF8)             // 30%
val AuroraViolet = Color(0x33A78BFA)             // 20%
val AuroraTeal = Color(0x262DD4BF)               // 15%

// ─────────────────────────────────────────────────────────────
// 8. Brushes
// ─────────────────────────────────────────────────────────────
object LiquidGlass {

    /** Full-screen background. Layer aurora orbs over this. */
    val Canvas: Brush = Brush.verticalGradient(
        colors = listOf(Color(0xFF0A0F1F), CanvasObsidian, Color(0xFF03050B))
    )

    /** Frosted panel fill: brighter at the top-left, fading toward the bottom-right. */
    val GlassFill: Brush = Brush.linearGradient(
        colors = listOf(Color(0x26FFFFFF), Color(0x0DFFFFFF), Color(0x08FFFFFF))
    )

    /** 1dp border: a bright specular rim on one corner, a faint sky tint on the other. */
    val GlassBorder: Brush = Brush.linearGradient(
        colors = listOf(
            GlassHighlight,
            GlassHighlightSoft,
            Color(0x0DFFFFFF),
            SkyPrimaryDim
        )
    )

    /** Primary buttons and active indicators. */
    val PrimaryAction: Brush = Brush.linearGradient(
        colors = listOf(SkyPrimaryBright, SkyPrimary, IndigoSecondary)
    )

    /** Soft radial light for aurora orbs. Draw large (300–500dp) with heavy blur. */
    fun orb(color: Color): Brush = Brush.radialGradient(
        colors = listOf(color, Color.Transparent)
    )
}