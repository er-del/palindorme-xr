package com.example.xr.windows

import com.example.xr.tracking.Ray
import com.example.xr.tracking.Vec3
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SpatialWindowManager(
    private val maxActiveContentWindows: Int = 4
) {
    private val _windows = MutableStateFlow<List<SpatialWindowState>>(emptyList())
    val windows: StateFlow<List<SpatialWindowState>> = _windows.asStateFlow()

    private val _focusedWindowId = MutableStateFlow<String?>(null)
    val focusedWindowId: StateFlow<String?> = _focusedWindowId.asStateFlow()

    private val dragUndoMap = mutableMapOf<String, SpatialWindowState>()
    private var zCounter = 10

    init {
        openDefaultWorkspace()
    }

    fun openDefaultWorkspace() {
        val defaults = listOf(
            SpatialWindowState(
                id = "win_dock",
                type = WindowType.SPATIAL_DOCK,
                azimuthDeg = 0f,
                elevationDeg = -24f,
                distanceMeters = 1.55f,
                zOrder = ++zCounter
            ),
            SpatialWindowState(
                id = "win_apps",
                type = WindowType.APP_LIBRARY,
                azimuthDeg = 0f,
                elevationDeg = 6f,
                distanceMeters = 2.20f,
                zOrder = ++zCounter
            ),
            SpatialWindowState(
                id = "win_browser",
                type = WindowType.BROWSER,
                azimuthDeg = -32f,
                elevationDeg = 5f,
                distanceMeters = 2.30f,
                zOrder = ++zCounter
            ),
            SpatialWindowState(
                id = "win_settings",
                type = WindowType.SETTINGS,
                azimuthDeg = 32f,
                elevationDeg = 5f,
                distanceMeters = 2.25f,
                zOrder = ++zCounter
            )
        )
        _windows.value = enforceContentLimit(defaults.toMutableList(), "win_apps")
        _focusedWindowId.value = "win_apps"
    }

    fun openOrFocusWindow(type: WindowType): SpatialWindowState {
        val current = _windows.value.toMutableList()
        val existingIdx = current.indexOfFirst { it.type == type }
        if (existingIdx >= 0) {
            val updated = current[existingIdx].copy(
                minimized = false,
                suspended = false,
                zOrder = ++zCounter
            )
            current[existingIdx] = updated
            _windows.value = enforceContentLimit(current, updated.id)
            _focusedWindowId.value = updated.id
            return updated
        }

        val slotAzimuth = when (type) {
            WindowType.SPATIAL_DOCK -> 0f
            WindowType.SPATIAL_KEYBOARD -> 0f
            WindowType.CONTROL_CENTER -> 0f
            WindowType.HAND_LAB -> 26f
            WindowType.PC_DESKTOP -> 0f
            else -> ((current.count { !it.minimized } % 5) - 2) * 22f
        }
        val slotElevation = when (type) {
            WindowType.SPATIAL_DOCK -> -24f
            WindowType.SPATIAL_KEYBOARD -> -15f
            WindowType.CONTROL_CENTER -> -2f
            WindowType.PC_DESKTOP -> 7f
            else -> 6f
        }
        val slotDistance = when (type) {
            WindowType.SPATIAL_DOCK -> 1.55f
            WindowType.SPATIAL_KEYBOARD -> 1.45f
            WindowType.CONTROL_CENTER -> 1.85f
            WindowType.HAND_LAB -> 2.05f
            WindowType.PC_DESKTOP -> 2.35f
            else -> 2.25f
        }

        val created = SpatialWindowState(
            id = "win_${type.name.lowercase()}_${System.currentTimeMillis() % 10000}",
            type = type,
            azimuthDeg = slotAzimuth,
            elevationDeg = slotElevation,
            distanceMeters = slotDistance,
            zOrder = ++zCounter
        )
        current.add(created)
        _windows.value = enforceContentLimit(current, created.id)
        _focusedWindowId.value = created.id
        return created
    }

    private fun enforceContentLimit(
        list: MutableList<SpatialWindowState>,
        keepActiveId: String
    ): List<SpatialWindowState> {
        val activeContent = list
            .filter { !it.minimized && !it.suspended && it.type.countsAgainstActiveLimit }
            .sortedByDescending { if (it.id == keepActiveId) Int.MAX_VALUE else it.zOrder }

        val allowedIds = activeContent.take(maxActiveContentWindows).map { it.id }.toSet()
        return list.map { win ->
            if (!win.minimized && win.type.countsAgainstActiveLimit && win.id !in allowedIds) {
                win.copy(suspended = true)
            } else {
                win
            }
        }
    }

    fun focusWindow(id: String) {
        _focusedWindowId.value = id
        _windows.value = enforceContentLimit(
            _windows.value.map {
                if (it.id == id) it.copy(minimized = false, suspended = false, zOrder = ++zCounter) else it
            }.toMutableList(),
            id
        )
    }

    fun minimizeWindow(id: String) {
        if (_windows.value.any { it.id == id && it.type == WindowType.SPATIAL_DOCK }) return
        val updated = _windows.value.map {
            if (it.id == id) it.copy(minimized = true) else it
        }
        val preferredFocusId = if (_focusedWindowId.value == id) null else _focusedWindowId.value
        updateFocusAndWindowLimit(updated, preferredFocusId)
    }

    fun closeWindow(id: String) {
        if (_windows.value.any { it.id == id && it.type == WindowType.SPATIAL_DOCK }) return
        val updated = _windows.value.filterNot { it.id == id }
        dragUndoMap.remove(id)
        val preferredFocusId = if (_focusedWindowId.value == id) null else _focusedWindowId.value
        updateFocusAndWindowLimit(updated, preferredFocusId)
    }

    private fun updateFocusAndWindowLimit(
        windows: List<SpatialWindowState>,
        preferredFocusId: String?
    ) {
        val visibleCandidates = windows.filter {
            !it.minimized && it.type != WindowType.SPATIAL_DOCK
        }
        val focused = visibleCandidates.firstOrNull { it.id == preferredFocusId }
            ?: visibleCandidates
                .filter { it.type.countsAgainstActiveLimit }
                .maxByOrNull { it.zOrder }
            ?: visibleCandidates.maxByOrNull { it.zOrder }
        val activated = if (focused?.type?.countsAgainstActiveLimit == true) {
            windows.map { if (it.id == focused.id) it.copy(suspended = false) else it }
        } else {
            windows
        }
        _windows.value = enforceContentLimit(activated.toMutableList(), focused?.id.orEmpty())
        _focusedWindowId.value = focused?.id
    }

    fun snapshotForDrag(id: String) {
        _windows.value.firstOrNull { it.id == id }?.let { dragUndoMap[id] = it }
    }

    fun revertDrag(id: String) {
        val prior = dragUndoMap.remove(id) ?: return
        _windows.value = _windows.value.map { if (it.id == id) prior else it }
    }

    fun commitDrag(id: String) {
        dragUndoMap.remove(id)
    }

    fun moveWindow(id: String, deltaAzimuthDeg: Float, deltaElevationDeg: Float, depthDeltaMeters: Float) {
        if (_windows.value.any { it.id == id && it.type == WindowType.SPATIAL_DOCK }) return
        _windows.value = _windows.value.map { win ->
            if (win.id != id) win
            else win.copy(
                azimuthDeg = (win.azimuthDeg + deltaAzimuthDeg).coerceIn(-85f, 85f),
                elevationDeg = (win.elevationDeg + deltaElevationDeg).coerceIn(-40f, 48f),
                distanceMeters = (win.distanceMeters + depthDeltaMeters).coerceIn(1.2f, 4.5f)
            )
        }
    }

    fun resizeWindow(id: String, scaleRatio: Float, yawDeltaDeg: Float = 0f) {
        if (_windows.value.any { it.id == id && it.type == WindowType.SPATIAL_DOCK }) return
        _windows.value = _windows.value.map { win ->
            if (win.id != id) win
            else win.copy(
                widthMeters = (win.widthMeters * scaleRatio).coerceIn(0.75f, 2.8f),
                heightMeters = (win.heightMeters * scaleRatio).coerceIn(0.45f, 1.9f),
                yawOffsetDeg = (win.yawOffsetDeg + yawDeltaDeg).coerceIn(-35f, 35f)
            )
        }
    }

    fun cycleFocus(forward: Boolean = true) {
        val visible = _windows.value.filter {
            !it.minimized && !it.suspended && it.type != WindowType.SPATIAL_DOCK
        }
        if (visible.isEmpty()) return
        val curIdx = visible.indexOfFirst { it.id == _focusedWindowId.value }
        val nextIdx = if (curIdx < 0) 0 else {
            val step = if (forward) 1 else -1
            (curIdx + step + visible.size) % visible.size
        }
        focusWindow(visible[nextIdx].id)
    }

    fun recenterWindowsToFront() {
        val list = _windows.value
        val active = list.filter { !it.minimized && it.type != WindowType.SPATIAL_DOCK }
        if (active.isEmpty()) {
            openDefaultWorkspace()
            return
        }
        val spread = 28f
        val startAz = -((active.size - 1) * spread) * 0.5f
        var idx = 0
        _windows.value = list.map { win ->
            if (win.minimized || win.type == WindowType.SPATIAL_DOCK) win
            else {
                val az = startAz + (idx++) * spread
                win.copy(azimuthDeg = az.coerceIn(-75f, 75f), elevationDeg = 5f)
            }
        }
    }

    fun raycast(ray: Ray): RaycastTarget? {
        var bestWinHit: RaycastTarget.WindowHit? = null
        val visible = _windows.value.filter { !it.minimized && !it.suspended }.sortedByDescending { it.zOrder }
        for (win in visible) {
            val basis = win.computeWorldBasis()
            val hit = ray.intersectQuad(
                quadCenter = basis.center,
                quadRight = basis.right,
                quadUp = basis.up,
                quadNormal = basis.normal,
                width = win.widthMeters,
                height = win.heightMeters
            ) ?: continue

            if (bestWinHit == null || hit.distance < bestWinHit.distance) {
                val (magU, magV) = applyTargetMagnetism(hit.u, hit.v)
                val region = if (win.type == WindowType.SPATIAL_DOCK) {
                    WindowHitRegion.CONTENT_BODY
                } else {
                    classifyRegionStatic(magU, magV)
                }
                bestWinHit = RaycastTarget.WindowHit(
                    windowId = win.id,
                    region = region,
                    u = magU,
                    v = magV,
                    distance = hit.distance
                )
            }
        }

        return bestWinHit
    }

    private fun applyTargetMagnetism(u: Float, v: Float): Pair<Float, Float> {
        val magnets = arrayOf(
            0.94f to 0.055f, // Close button
            0.84f to 0.055f, // Minimize button
            0.95f to 0.94f,  // Resize handle
            0.50f to 0.94f   // Bottom grabber bar
        )
        for ((mx, my) in magnets) {
            val dx = u - mx
            val dy = v - my
            if (dx * dx + dy * dy <= 0.042f * 0.042f) {
                return (u * 0.25f + mx * 0.75f) to (v * 0.25f + my * 0.75f)
            }
        }
        return u to v
    }

    companion object {
        fun classifyRegionStatic(u: Float, v: Float): WindowHitRegion {
            if (v < 0.11f) {
                return when {
                    u > 0.90f -> WindowHitRegion.CLOSE_BUTTON
                    u > 0.80f -> WindowHitRegion.MINIMIZE_BUTTON
                    else -> WindowHitRegion.TITLE_BAR
                }
            }
            if (u > 0.90f && v > 0.87f) {
                return WindowHitRegion.RESIZE_HANDLE
            }
            if (v > 0.89f && u in 0.25f..0.75f) {
                return WindowHitRegion.GRABBER_BAR
            }
            return WindowHitRegion.CONTENT_BODY
        }
    }

}
