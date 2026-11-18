package com.example.xr

import com.example.xr.windows.SpatialWindowManager
import com.example.xr.tracking.Ray
import com.example.xr.windows.WindowHitRegion
import com.example.xr.windows.WindowType
import com.example.xr.windows.RaycastTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialWindowManagerTest {

    @Test
    fun defaultWorkspaceKeepsDockOutsideTheLowRamContentLimit() {
        val manager = SpatialWindowManager(maxActiveContentWindows = 1)

        val dock = manager.windows.value.singleOrNull { it.type == WindowType.SPATIAL_DOCK }
        assertNotNull(dock)
        assertFalse(dock!!.minimized)
        assertFalse(dock.suspended)
        assertEquals(
            1,
            manager.windows.value.count {
                it.type.countsAgainstActiveLimit && !it.minimized && !it.suspended
            }
        )
    }

    @Test
    fun dockCannotBeMinimizedOrClosed() {
        val manager = SpatialWindowManager()
        val dock = manager.windows.value.single { it.type == WindowType.SPATIAL_DOCK }

        manager.minimizeWindow(dock.id)
        manager.closeWindow(dock.id)
        manager.moveWindow(dock.id, 12f, 8f, 0.5f)
        manager.resizeWindow(dock.id, 1.5f)
        manager.recenterWindowsToFront()

        assertEquals(dock, manager.windows.value.single { it.type == WindowType.SPATIAL_DOCK })
    }

    @Test
    fun dockRaycastsTreatItsEntireSurfaceAsInteractiveContent() {
        val manager = SpatialWindowManager()
        val dock = manager.windows.value.single { it.type == WindowType.SPATIAL_DOCK }
        val basis = dock.computeWorldBasis()
        val normalUpDot = basis.normal.dot(basis.up)
        val topOffset = dock.heightMeters * 0.45f / (1f - normalUpDot * normalUpDot)
        val origin = basis.center + basis.normal + basis.up * topOffset

        val hit = manager.raycast(Ray(origin, basis.normal * -1f))

        val windowHit = hit as RaycastTarget.WindowHit
        assertEquals(dock.id, windowHit.windowId)
        org.junit.Assert.assertEquals(0.05f, windowHit.v, 0.02f)
        assertEquals(WindowHitRegion.CONTENT_BODY, windowHit.region)
    }

    @Test
    fun raycastsIgnoreContentWindowsSuspendedByTheActiveWindowLimit() {
        val manager = SpatialWindowManager(maxActiveContentWindows = 0)
        val suspendedApp = manager.windows.value.single { it.type == WindowType.APP_LIBRARY }
        val basis = suspendedApp.computeWorldBasis()
        val ray = Ray(basis.center + basis.normal, basis.normal * -1f)

        assertNull(manager.raycast(ray))
    }

    @Test
    fun minimizingFocusedContentResumesTheNextSuspendedWindow() {
        val manager = SpatialWindowManager(maxActiveContentWindows = 1)
        val appLibrary = manager.windows.value.single { it.type == WindowType.APP_LIBRARY }
        val browser = manager.openOrFocusWindow(WindowType.BROWSER)
        assertTrue(manager.windows.value.single { it.id == appLibrary.id }.suspended)

        manager.minimizeWindow(browser.id)

        assertFalse(manager.windows.value.single { it.id == appLibrary.id }.suspended)
        assertEquals(appLibrary.id, manager.focusedWindowId.value)
    }
}
