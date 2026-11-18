package com.example.xr.model

enum class VirtualEnvironmentType(
    val id: String,
    val displayName: String,
    val subtitle: String,
    val defaultTintHex: Long,
    val isOledBlack: Boolean
) {
    MINIMAL_LAB(
        id = "minimal_lab",
        displayName = "Minimal Futuristic Lab",
        subtitle = "Clean horizon grid & architectural light rings",
        defaultTintHex = 0xFF38BDF8,
        isOledBlack = false
    ),
    SPACE_STATION(
        id = "space_station",
        displayName = "Space Station",
        subtitle = "True-black OLED starfield & orbital dock rings",
        defaultTintHex = 0xFF818CF8,
        isOledBlack = true
    ),
    DIGITAL_WORKSPACE(
        id = "digital_workspace",
        displayName = "Modern Digital Workspace",
        subtitle = "Architectural monoliths & subtle ambient floor",
        defaultTintHex = 0xFF38BDF8,
        isOledBlack = false
    ),
    DARK_ROOM(
        id = "dark_room",
        displayName = "Dark Futuristic Room",
        subtitle = "Zero-glare OLED obsidian studio for cinema",
        defaultTintHex = 0xFF64748B,
        isOledBlack = true
    ),
    NATURE_DUSK(
        id = "nature_dusk",
        displayName = "Nature Dusk",
        subtitle = "Low-poly twilight ridges & drifting fireflies",
        defaultTintHex = 0xFFF59E0B,
        isOledBlack = false
    ),
    FLOATING_GRID(
        id = "floating_grid",
        displayName = "Abstract Floating Grid",
        subtitle = "Dual-plane cyan matrix & depth markers",
        defaultTintHex = 0xFF22D3EE,
        isOledBlack = false
    );

    companion object {
        fun fromId(id: String): VirtualEnvironmentType =
            entries.firstOrNull { it.id == id } ?: MINIMAL_LAB
    }
}

data class EnvironmentConfig(
    val type: VirtualEnvironmentType = VirtualEnvironmentType.MINIMAL_LAB,
    val tintHex: Long = type.defaultTintHex,
    val ambientLight: Float = 0.65f,
    val particleDensity: Float = 0.70f,
    val effectsEnabled: Boolean = true,
    val brightness: Float = 0.90f
)
