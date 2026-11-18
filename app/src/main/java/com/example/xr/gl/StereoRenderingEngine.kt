package com.example.xr.gl

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.example.xr.hand.HandFrame
import com.example.xr.hand.HandFrameTransform
import com.example.xr.hand.HandLandmarkIndex
import com.example.xr.hand.HandRayInteractor
import com.example.xr.model.CinemaProjectionMode
import com.example.xr.model.OpticsProfile
import com.example.xr.model.XRSettings
import com.example.xr.system.AdaptiveQualityTier
import com.example.xr.system.PerformanceMonitor
import com.example.xr.tracking.HeadPoseSnapshot
import com.example.xr.tracking.HeadTrackingEngine
import com.example.xr.tracking.Ray
import com.example.xr.tracking.Vec3
import com.example.xr.windows.DockActionId
import com.example.xr.windows.RaycastTarget
import com.example.xr.windows.SpatialWindowManager
import com.example.xr.windows.SpatialWindowState
import com.example.xr.windows.VirtualContentHost
import com.example.xr.windows.WindowHitRegion
import com.example.xr.windows.WindowType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * Custom OpenGL ES 3.0 Stereo Rendering Engine:
 * Dual eye offscreen FBOs -> 40x40 Brown-Conrady distortion + rotational timewarp pass -> side-by-side display.
 * Safely resets EGL-context-bound resources whenever onSurfaceCreated establishes a new EGLContext.
 */
class StereoRenderingEngine(
    private val headTracking: HeadTrackingEngine,
    private val windowManager: SpatialWindowManager,
    private val contentHost: VirtualContentHost,
    private val performanceMonitor: PerformanceMonitor,
    private val onArFrameTick: (Boolean) -> Unit
) : GLSurfaceView.Renderer {

    @Volatile
    var currentSettings: XRSettings = XRSettings()
    @Volatile
    var currentRaycast: RaycastTarget? = null
    @Volatile
    var currentRay: Ray? = null
    @Volatile
    var dwellProgress: Float = 0f
    @Volatile
    var palmHoldProgress: Float = 0f
    @Volatile
    var latestHands: List<HandFrame> = emptyList()
    @Volatile
    var passthroughActive: Boolean = false
    @Volatile
    var arCoreActive: Boolean = false
    @Volatile
    var lastPokeWorldPoint: Vec3? = null
    @Volatile
    var onFrameInteractionTick: ((HeadPoseSnapshot) -> Unit)? = null
    @Volatile
    var onPassthroughSurfaceReady: ((SurfaceTexture, Int) -> Unit)? = null
    @Volatile
    var onDesktopStreamSurfaceReady: ((SurfaceTexture, Int, Int) -> Unit)? = null
    @Volatile
    var onSurfaceDimensionsChanged: ((Int, Int) -> Unit)? = null
    @Volatile
    var displayDensity: Float = 2.625f
    @Volatile
    var mediaVideoWidthPx: Int = 1280
    @Volatile
    var mediaVideoHeightPx: Int = 720
    @Volatile
    var pcStreamWidthPx: Int = 1280
    @Volatile
    var pcStreamHeightPx: Int = 720

    @Volatile
    private var camSensorOrientDeg: Int = 90
    @Volatile
    private var camLensFacingBack: Boolean = true
    @Volatile
    private var camDisplayRotation: Int = Surface.ROTATION_90
    @Volatile
    private var camReportedBaseRotDeg: Int? = null
    @Volatile
    private var camHasCameraTransform: Boolean = true
    @Volatile
    private var camStreamMirroring: Boolean = false
    @Volatile
    private var camSrcWidthPx: Int = 960
    @Volatile
    private var camSrcHeightPx: Int = 720

    private val handRayInteractor = HandRayInteractor()

    private var screenWidth = 1920
    private var screenHeight = 1080

    // Programs & cached uniform locations (zero glGetUniformLocation calls in onDrawFrame)
    private var distortionProg = 0
    private var distEyeTexLoc = -1
    private var distTimewarpLoc = -1

    private var oesWindowProg = 0
    private var oesMvpLoc = -1
    private var oesBorderLoc = -1
    private var oesOpacityLoc = -1
    private var oesAspectLoc = -1
    private var oesCinemaModeLoc = -1
    private var oesEyeIndexLoc = -1
    private var oesContentFitLoc = -1
    private var oesTexLoc = -1

    private var tex2dWindowProg = 0
    private var tex2dMvpLoc = -1
    private var tex2dBorderLoc = -1
    private var tex2dOpacityLoc = -1
    private var tex2dAspectLoc = -1
    private var tex2dTexLoc = -1

    private var passthroughProg = 0
    private var passMvpLoc = -1
    private var passCamMatLoc = -1
    private var passRotStepsLoc = -1
    private var passMirrorXLoc = -1
    private var passBrightLoc = -1
    private var passUndistortLoc = -1
    private var passImmersionAlphaLoc = -1
    private var passCamTexLoc = -1

    private var color3dProg = 0
    private var colMvpLoc = -1
    private var colPointSizeLoc = -1
    private var colBrightnessLoc = -1

    private var litMeshProg = 0
    private var litMvpLoc = -1
    private var litModelLoc = -1
    private var litTintLoc = -1

    // Offscreen Eye FBOs
    private var leftFbo = 0
    private var leftColorTex = 0
    private var leftDepthRbo = 0
    private var rightFbo = 0
    private var rightColorTex = 0
    private var rightDepthRbo = 0
    private var fboWidth = 0
    private var fboHeight = 0

    // Distortion Meshes
    private var leftMeshVbo = 0
    private var leftMeshIbo = 0
    private var rightMeshVbo = 0
    private var rightMeshIbo = 0
    private var distortionIndexCount = 0
    private var lastOpticsSnapshot: OpticsProfile? = null
    private var lastGridSize = 40

    // Unit Quad VBO for windows / dock / passthrough
    private val unitQuadBuffer: FloatBuffer = createUnitQuadBuffer()
    private val passthroughQuadBuffer: FloatBuffer = createFullScreenQuadBuffer()

    // Window textures: 2D textures for View-backed windows, OES SurfaceTextures strictly for Media3 video
    private val window2dTextures = mutableMapOf<String, Window2dTex>()
    private val windowOesTextures = mutableMapOf<String, Pair<Int, SurfaceTexture>>()

    // Lazy passthrough OES texture (only allocated when passthrough or ARCore is actually turned on)
    private var passthroughTexId = 0
    private var passthroughSurfaceTexture: SurfaceTexture? = null

    private data class Window2dTex(
        val texId: Int,
        var uploadedSeq: Long = -1L,
        var initialized: Boolean = false
    )

    // Environment & glTF
    private val environmentRenderer = EnvironmentRenderer()
    @Volatile
    private var pendingMeshGeometry: MeshGeometry? = GltfModelLoader.loadPreset(0)
    private var gltfPosBuf: FloatBuffer? = null
    private var gltfNormBuf: FloatBuffer? = null
    private var gltfIdxBuf: ShortBuffer? = null
    private var gltfIdxCount: Int = 0
    private var gltfSpinAngle: Float = 0f

    // Pre-allocated scratch buffers (zero GC in onDrawFrame)
    private val projMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val headRotMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val vpMatrix = FloatArray(16)
    private val camTexMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
    private val timewarpMat = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    private val reticleBuffer = ByteBuffer.allocateDirect(128 * 7 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val calibBuffer = ByteBuffer.allocateDirect(256 * 7 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val handSkelBuffer = ByteBuffer.allocateDirect(192 * 7 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val handRayBuffer = ByteBuffer.allocateDirect(32 * 7 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val dockColorBuffer = ByteBuffer.allocateDirect(64 * 7 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val windowHoverSprings = HashMap<String, Float>(8)
    private val windowAppearSprings = HashMap<String, Float>(8)
    private val visibleWindowsScratch = ArrayList<SpatialWindowState>(12)
    private val staleIdsScratch = ArrayList<String>(8)
    private val glHandleScratch1 = IntArray(1)
    private val glHandleScratch3 = IntArray(3)
    private val glHandleScratch4 = IntArray(4)
    private var lastFrameStartNs: Long = 0L

    fun updateCameraFeedOrientation(
        sensorOrientationDeg: Int,
        lensFacingBack: Boolean,
        displayRotation: Int,
        reportedBaseRotationDeg: Int?,
        hasCameraTransform: Boolean,
        streamMirroring: Boolean,
        srcWidthPx: Int,
        srcHeightPx: Int
    ) {
        camSensorOrientDeg = sensorOrientationDeg
        camLensFacingBack = lensFacingBack
        camDisplayRotation = displayRotation
        camReportedBaseRotDeg = reportedBaseRotationDeg
        camHasCameraTransform = hasCameraTransform
        camStreamMirroring = streamMirroring
        if (srcWidthPx > 0 && srcHeightPx > 0) {
            camSrcWidthPx = srcWidthPx
            camSrcHeightPx = srcHeightPx
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Reset any stale handles from an earlier EGLContext
        window2dTextures.clear()
        windowOesTextures.values.forEach { (_, st) -> runCatching { st.release() } }
        windowOesTextures.clear()
        runCatching { passthroughSurfaceTexture?.release() }
        passthroughSurfaceTexture = null
        passthroughTexId = 0
        leftFbo = 0
        rightFbo = 0
        leftMeshVbo = 0
        rightMeshVbo = 0
        lastOpticsSnapshot = null
        lastFrameStartNs = 0L
        contentHost.unbindAllSurfaces()

        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        distortionProg = ShaderPrograms.compileProgram(ShaderPrograms.DISTORTION_VERT, ShaderPrograms.DISTORTION_FRAG)
        distEyeTexLoc = GLES30.glGetUniformLocation(distortionProg, "uEyeTexture")
        distTimewarpLoc = GLES30.glGetUniformLocation(distortionProg, "uTimewarpMat")

        oesWindowProg = ShaderPrograms.compileProgram(ShaderPrograms.OES_WINDOW_VERT, ShaderPrograms.OES_WINDOW_FRAG)
        oesMvpLoc = GLES30.glGetUniformLocation(oesWindowProg, "uMvp")
        oesBorderLoc = GLES30.glGetUniformLocation(oesWindowProg, "uBorderColor")
        oesOpacityLoc = GLES30.glGetUniformLocation(oesWindowProg, "uOpacity")
        oesAspectLoc = GLES30.glGetUniformLocation(oesWindowProg, "uAspect")
        oesCinemaModeLoc = GLES30.glGetUniformLocation(oesWindowProg, "uCinemaStereoMode")
        oesEyeIndexLoc = GLES30.glGetUniformLocation(oesWindowProg, "uEyeIndex")
        oesContentFitLoc = GLES30.glGetUniformLocation(oesWindowProg, "uContentFitScale")
        oesTexLoc = GLES30.glGetUniformLocation(oesWindowProg, "uOesTex")

        tex2dWindowProg = ShaderPrograms.compileProgram(ShaderPrograms.OES_WINDOW_VERT, ShaderPrograms.TEX_2D_WINDOW_FRAG)
        tex2dMvpLoc = GLES30.glGetUniformLocation(tex2dWindowProg, "uMvp")
        tex2dBorderLoc = GLES30.glGetUniformLocation(tex2dWindowProg, "uBorderColor")
        tex2dOpacityLoc = GLES30.glGetUniformLocation(tex2dWindowProg, "uOpacity")
        tex2dAspectLoc = GLES30.glGetUniformLocation(tex2dWindowProg, "uAspect")
        tex2dTexLoc = GLES30.glGetUniformLocation(tex2dWindowProg, "uTex2d")

        passthroughProg = ShaderPrograms.compileProgram(ShaderPrograms.PASSTHROUGH_VERT, ShaderPrograms.PASSTHROUGH_OES_FRAG)
        passMvpLoc = GLES30.glGetUniformLocation(passthroughProg, "uMvp")
        passCamMatLoc = GLES30.glGetUniformLocation(passthroughProg, "uCamTexMatrix")
        passRotStepsLoc = GLES30.glGetUniformLocation(passthroughProg, "uRotationSteps")
        passMirrorXLoc = GLES30.glGetUniformLocation(passthroughProg, "uMirrorX")
        passBrightLoc = GLES30.glGetUniformLocation(passthroughProg, "uBrightness")
        passUndistortLoc = GLES30.glGetUniformLocation(passthroughProg, "uUndistort")
        passImmersionAlphaLoc = GLES30.glGetUniformLocation(passthroughProg, "uImmersionAlpha")
        passCamTexLoc = GLES30.glGetUniformLocation(passthroughProg, "uCamTex")

        color3dProg = ShaderPrograms.compileProgram(ShaderPrograms.COLOR_3D_VERT, ShaderPrograms.COLOR_3D_FRAG)
        colMvpLoc = GLES30.glGetUniformLocation(color3dProg, "uMvp")
        colPointSizeLoc = GLES30.glGetUniformLocation(color3dProg, "uPointSize")
        colBrightnessLoc = GLES30.glGetUniformLocation(color3dProg, "uBrightness")

        litMeshProg = ShaderPrograms.compileProgram(ShaderPrograms.LIT_MESH_VERT, ShaderPrograms.LIT_MESH_FRAG)
        litMvpLoc = GLES30.glGetUniformLocation(litMeshProg, "uMvp")
        litModelLoc = GLES30.glGetUniformLocation(litMeshProg, "uModel")
        litTintLoc = GLES30.glGetUniformLocation(litMeshProg, "uTint")

        val rendererStr = gl?.glGetString(GL10.GL_RENDERER)
        val vendorStr = gl?.glGetString(GL10.GL_VENDOR)
        val versionStr = gl?.glGetString(GL10.GL_VERSION)
        Log.i(
            "StereoRenderer",
            "onSurfaceCreated renderer=$rendererStr vendor=$vendorStr version=$versionStr progs=[dist=$distortionProg oes=$oesWindowProg tex2d=$tex2dWindowProg pass=$passthroughProg col=$color3dProg lit=$litMeshProg]"
        )
        performanceMonitor.recordGlInfoOnRenderThread(
            renderer = rendererStr,
            vendor = vendorStr,
            version = versionStr
        )
        performanceMonitor.initAdpfOnRenderThread()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        screenWidth = width.coerceAtLeast(2)
        screenHeight = height.coerceAtLeast(2)
        Log.i("StereoRenderer", "onSurfaceChanged ${screenWidth}x${screenHeight}")
        rebuildEyeFbosIfNeeded(force = true)
        val gridSize = performanceMonitor.telemetry.value.qualityTier.meshGridSize
        rebuildDistortionMeshes(currentSettings.optics, gridSize)
        onSurfaceDimensionsChanged?.invoke(screenWidth, screenHeight)
    }

    fun setGltfMesh(mesh: MeshGeometry) {
        pendingMeshGeometry = mesh
    }

    override fun onDrawFrame(gl: GL10?) {
        // Guard against transient portrait frame while rotating to landscape so BLASTBufferQueue doesn't reject mismatched buffers
        if (screenWidth < screenHeight) {
            GLES30.glClearColor(0.024f, 0.035f, 0.075f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            return
        }

        val frameStartNs = SystemClock.elapsedRealtimeNanos()
        val dtSec = if (lastFrameStartNs == 0L) 0.016f else ((frameStartNs - lastFrameStartNs) * 1e-9f).coerceIn(0.001f, 0.05f)
        lastFrameStartNs = frameStartNs
        val settings = currentSettings
        val qualityTier = performanceMonitor.telemetry.value.qualityTier

        if (passthroughActive || arCoreActive) {
            ensurePassthroughSurfaceTexture()
        }

        if (arCoreActive) {
            onArFrameTick(settings.arCoreSixDofEnabled)
        }

        val pose = headTracking.getPredictedPose()
        onFrameInteractionTick?.invoke(pose)

        if (lastOpticsSnapshot != settings.optics || lastGridSize != qualityTier.meshGridSize) {
            rebuildDistortionMeshes(settings.optics, qualityTier.meshGridSize)
        }
        rebuildEyeFbosIfNeeded(force = false)
        uploadPendingGltfIfNeeded()
        syncWindowTextures()

        if (passthroughActive || arCoreActive) {
            runCatching {
                passthroughSurfaceTexture?.let { st ->
                    st.updateTexImage()
                    st.getTransformMatrix(camTexMatrix)
                }
            }
        }

        // Gather & insertion-sort visible windows once per frame (zero heap allocation)
        val allWindows = windowManager.windows.value
        visibleWindowsScratch.clear()
        for (i in allWindows.indices) {
            val w = allWindows[i]
            if (!w.minimized) visibleWindowsScratch.add(w)
        }
        for (i in 1 until visibleWindowsScratch.size) {
            val key = visibleWindowsScratch[i]
            var j = i - 1
            while (j >= 0 && visibleWindowsScratch[j].zOrder > key.zOrder) {
                visibleWindowsScratch[j + 1] = visibleWindowsScratch[j]
                j--
            }
            visibleWindowsScratch[j + 1] = key
        }

        // Step damped spring physics ONCE per frame with clamped dt (not once per eye!)
        val rayHit = currentRaycast
        val hoveredWindowId = (rayHit as? RaycastTarget.WindowHit)?.windowId
        val springAlpha = if (settings.reduceMotion) 1f else (1f - exp(-12f * dtSec)).coerceIn(0f, 1f)
        for (i in visibleWindowsScratch.indices) {
            val win = visibleWindowsScratch[i]
            val targetHover = if (win.id == hoveredWindowId) 1f else 0f
            val curHover = windowHoverSprings[win.id] ?: 0f
            windowHoverSprings[win.id] = curHover + (targetHover - curHover) * springAlpha

            val curAppear = windowAppearSprings[win.id] ?: 0.86f
            windowAppearSprings[win.id] = curAppear + (1f - curAppear) * springAlpha
        }

        pose.orientation.conjugate().toMatrix4x4(headRotMatrix)

        val centerGapPx = ((settings.optics.centerGapDp * displayDensity).roundToInt()).coerceIn(0, screenWidth / 8)
        val eyeWidth = ((screenWidth - centerGapPx) / 2).coerceAtLeast(1)
        val aspect = eyeWidth.toFloat() / screenHeight.toFloat()
        val near = 0.08f
        val far = 60f
        val fovRad = settings.optics.fovDegrees * (PI.toFloat() / 180f)
        val tanHalfFov = tan(fovRad * 0.5f)
        val top = near * tanHalfFov
        val right = top * aspect
        Matrix.frustumM(projMatrix, 0, -right, right, -top, top, near, far)

        val halfIpd = settings.optics.eyeSeparationMeters() * 0.5f

        renderEye(
            fbo = leftFbo,
            eyeOffsetX = -halfIpd,
            isLeftEye = true,
            pose = pose,
            settings = settings,
            qualityTier = qualityTier
        )

        renderEye(
            fbo = rightFbo,
            eyeOffsetX = halfIpd,
            isLeftEye = false,
            pose = pose,
            settings = settings,
            qualityTier = qualityTier
        )

        // Late pose sampling right before distortion pass -> rotational timewarp matrix
        val latePose = headTracking.getPredictedPose()
        computeRotationalTimewarpMat(pose, latePose, tanHalfFov, aspect)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        GLES30.glViewport(0, 0, eyeWidth, screenHeight)
        drawDistortedEye(leftMeshVbo, leftMeshIbo, leftColorTex)

        val rightX = eyeWidth + centerGapPx
        val rightW = (screenWidth - rightX).coerceAtLeast(1)
        GLES30.glViewport(rightX, 0, rightW, screenHeight)
        drawDistortedEye(rightMeshVbo, rightMeshIbo, rightColorTex)

        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        gltfSpinAngle = (gltfSpinAngle + 39f * dtSec) % 360f

        val frameEndNs = SystemClock.elapsedRealtimeNanos()
        performanceMonitor.recordFrameDurationNs(frameEndNs - frameStartNs, settings.predictionMs)
    }

    private fun computeRotationalTimewarpMat(
        startPose: HeadPoseSnapshot,
        latePose: HeadPoseSnapshot,
        tanHalfFov: Float,
        aspect: Float
    ) {
        val dq = (startPose.orientation.conjugate() * latePose.orientation).normalized()
        val dPitch = (2f * dq.x).coerceIn(-0.08f, 0.08f)
        val dYaw = (2f * dq.y).coerceIn(-0.08f, 0.08f)
        val dRoll = (2f * dq.z).coerceIn(-0.08f, 0.08f)

        val cosR = cos(dRoll)
        val sinR = sin(dRoll)
        val shiftU = (-dYaw / (2f * tanHalfFov * aspect)).coerceIn(-0.10f, 0.10f)
        val shiftV = (dPitch / (2f * tanHalfFov)).coerceIn(-0.10f, 0.10f)

        // Column-major 3x3
        timewarpMat[0] = cosR; timewarpMat[1] = sinR; timewarpMat[2] = 0f
        timewarpMat[3] = -sinR; timewarpMat[4] = cosR; timewarpMat[5] = 0f
        timewarpMat[6] = shiftU; timewarpMat[7] = shiftV; timewarpMat[8] = 1f
    }

    private fun ensurePassthroughSurfaceTexture() {
        if (passthroughSurfaceTexture != null) return
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        passthroughTexId = tex[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, passthroughTexId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        val ptSt = SurfaceTexture(passthroughTexId)
        passthroughSurfaceTexture = ptSt
        onPassthroughSurfaceReady?.invoke(ptSt, passthroughTexId)
    }

    private fun renderEye(
        fbo: Int,
        eyeOffsetX: Float,
        isLeftEye: Boolean,
        pose: HeadPoseSnapshot,
        settings: XRSettings,
        qualityTier: AdaptiveQualityTier
    ) {
        val posePos = pose.position
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, fboWidth, fboHeight)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)

        val visibleCameraBg = (passthroughActive || arCoreActive) && passthroughTexId != 0
        if (settings.environment.type.isOledBlack && (!visibleCameraBg || settings.immersionLevel > 0.9f)) {
            GLES30.glClearColor(0f, 0f, 0f, 1f)
        } else {
            GLES30.glClearColor(0.024f, 0.035f, 0.075f, 1f)
        }
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        Matrix.setIdentityM(viewMatrix, 0)
        Matrix.translateM(viewMatrix, 0, -eyeOffsetX, 0f, 0f)
        Matrix.multiplyMM(mvpMatrix, 0, viewMatrix, 0, headRotMatrix, 0)
        System.arraycopy(mvpMatrix, 0, viewMatrix, 0, 16)
        Matrix.translateM(viewMatrix, 0, -posePos.x, -posePos.y, -posePos.z)
        Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)

        // Draw virtual environment when not completely overtaken by passthrough
        if (!visibleCameraBg || settings.immersionLevel > 0.02f) {
            val cinemaDim = if (settings.cinemaModeEnabled) 0.22f else 1.0f
            environmentRenderer.draw(
                colorProg = color3dProg,
                mvpLoc = colMvpLoc,
                pointSizeLoc = colPointSizeLoc,
                brightnessLoc = colBrightnessLoc,
                mvpMatrix = vpMatrix,
                config = settings.environment,
                particlesAllowed = qualityTier.particlesAllowed && !settings.reduceMotion && !settings.cinemaModeEnabled,
                dimMultiplier = cinemaDim
            )
        }

        // Feathered Immersion Dial blend for live camera passthrough
        if (visibleCameraBg && settings.immersionLevel < 0.99f) {
            drawPassthroughQuad(settings, isLeftEye)
        }

        val focusedId = windowManager.focusedWindowId.value
        val rayHit = currentRaycast

        // Draw glTF 3D meshes before translucent glass windows so windows glass-composite cleanly
        for (i in visibleWindowsScratch.indices) {
            val win = visibleWindowsScratch[i]
            if (win.type == WindowType.GLTF_VIEWER) {
                drawGltfStage(win)
            }
        }

        // Premultiplied alpha blending for Android Canvas Bitmaps & frosted SDF glass windows
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        for (i in visibleWindowsScratch.indices) {
            val win = visibleWindowsScratch[i]
            drawWindowContactShadow(win, settings)
            val isHovered = rayHit is RaycastTarget.WindowHit && rayHit.windowId == win.id
            drawSpatialWindow(
                win = win,
                isFocused = win.id == focusedId,
                isHovered = isHovered,
                isLeftEye = isLeftEye,
                settings = settings,
                passthrough = visibleCameraBg
            )
        }
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        for (i in visibleWindowsScratch.indices) {
            val win = visibleWindowsScratch[i]
            val isGrabHovered = rayHit is RaycastTarget.WindowHit &&
                rayHit.windowId == win.id &&
                rayHit.region == WindowHitRegion.GRABBER_BAR
            if (win.id == focusedId || (rayHit is RaycastTarget.WindowHit && rayHit.windowId == win.id)) {
                drawWindowGrabberBar(win, isGrabHovered, settings)
            }
        }

        drawSpatialDock(rayHit)

        if (settings.handControlEnabled && settings.handSkeletonOverlay) {
            if (latestHands.isNotEmpty()) {
                drawHandSkeletons(pose, settings)
            } else {
                drawReachZoneGuide(pose)
            }
        }
        if (settings.handControlEnabled && settings.handRayModeEnabled && latestHands.isNotEmpty()) {
            drawHandRayBeam(rayHit)
        }

        if (settings.showCalibrationGrid) {
            drawCalibrationGrid(isLeftEye)
        }

        drawReticle(settings, rayHit)
    }

    private fun drawPassthroughQuad(settings: XRSettings, isLeftEye: Boolean) {
        if (passthroughProg == 0) return
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glUseProgram(passthroughProg)

        val resolved = HandFrameTransform.resolveCameraOrientation(
            sensorOrientationDeg = camSensorOrientDeg,
            lensFacingBack = camLensFacingBack,
            displayRotation = camDisplayRotation,
            reportedBaseRotationDeg = camReportedBaseRotDeg,
            hasCameraTransform = camHasCameraTransform,
            streamMirroring = camStreamMirroring,
            userRotationOffsetDeg = settings.cameraRotationDegrees,
            userMirrorHorizontal = settings.cameraMirrorHorizontal
        )
        val fitScale = HandFrameTransform.computeAspectFitScale(
            srcWidthPx = camSrcWidthPx,
            srcHeightPx = camSrcHeightPx,
            rotationDeg = resolved.finalRotationDeg,
            dstWidth = fboWidth.toFloat(),
            dstHeight = fboHeight.toFloat()
        )

        Matrix.setIdentityM(modelMatrix, 0)
        // Subtle stereo convergence shift so monocular passthrough feels comfortable at 2m focus plane
        Matrix.translateM(modelMatrix, 0, if (isLeftEye) 0.015f else -0.015f, 0f, 0f)
        Matrix.scaleM(modelMatrix, 0, fitScale.scaleX * 1.02f, fitScale.scaleY * 1.02f, 1f)
        GLES30.glUniformMatrix4fv(passMvpLoc, 1, false, modelMatrix, 0)
        GLES30.glUniformMatrix4fv(passCamMatLoc, 1, false, camTexMatrix, 0)
        val rotSteps = ((resolved.glPassthroughFinalDeg / 90) % 4 + 4) % 4
        GLES30.glUniform1i(passRotStepsLoc, rotSteps)
        GLES30.glUniform1f(passMirrorXLoc, if (resolved.mirrorHorizontal) 1f else 0f)
        GLES30.glUniform1f(passBrightLoc, settings.passthroughBrightness)
        GLES30.glUniform1f(passUndistortLoc, if (settings.passthroughUndistort) 1f else 0f)
        val immersionAlpha = (1.0f - settings.immersionLevel).coerceIn(0f, 1f)
        GLES30.glUniform1f(passImmersionAlphaLoc, immersionAlpha)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, passthroughTexId)
        GLES30.glUniform1i(passCamTexLoc, 0)

        drawTexturedQuad(passthroughQuadBuffer)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    private fun drawSpatialWindow(
        win: SpatialWindowState,
        isFocused: Boolean,
        isHovered: Boolean,
        isLeftEye: Boolean,
        settings: XRSettings,
        passthrough: Boolean
    ) {
        val hoverSpring = windowHoverSprings[win.id] ?: if (isHovered) 1f else 0f
        val appearSpring = windowAppearSprings[win.id] ?: 1f

        val basis = win.computeWorldBasis()
        val cinemaScale = if (win.type == WindowType.MEDIA_PLAYER && settings.cinemaModeEnabled &&
            (settings.cinemaProjectionMode == CinemaProjectionMode.VR_180 || settings.cinemaProjectionMode == CinemaProjectionMode.VR_360)
        ) 1.32f else 1.0f
        val depthScale = if (settings.angularScaleWindowsByDepth) {
            (win.distanceMeters / 2.25f).coerceIn(0.68f, 1.55f)
        } else 1.0f
        val animScale = appearSpring * (1f + 0.022f * hoverSpring) * cinemaScale * depthScale
        val liftPull = 0.045f * hoverSpring
        val w = win.widthMeters * animScale
        val h = win.heightMeters * animScale

        modelMatrix[0] = basis.right.x * w
        modelMatrix[1] = basis.right.y * w
        modelMatrix[2] = basis.right.z * w
        modelMatrix[3] = 0f

        modelMatrix[4] = basis.up.x * h
        modelMatrix[5] = basis.up.y * h
        modelMatrix[6] = basis.up.z * h
        modelMatrix[7] = 0f

        modelMatrix[8] = -basis.normal.x
        modelMatrix[9] = -basis.normal.y
        modelMatrix[10] = -basis.normal.z
        modelMatrix[11] = 0f

        modelMatrix[12] = basis.center.x + basis.normal.x * liftPull
        modelMatrix[13] = basis.center.y + basis.normal.y * liftPull
        modelMatrix[14] = basis.center.z + basis.normal.z * liftPull
        modelMatrix[15] = 1f

        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)
        val aspect = (w / h).coerceIn(0.6f, 2.5f)

        if (win.type == WindowType.MEDIA_PLAYER || win.type == WindowType.PC_DESKTOP) {
            if (oesWindowProg == 0) return
            val oesPair = windowOesTextures[win.id] ?: return
            GLES30.glUseProgram(oesWindowProg)
            GLES30.glUniformMatrix4fv(oesMvpLoc, 1, false, mvpMatrix, 0)
            setWindowUniforms(
                borderLoc = oesBorderLoc,
                opacityLoc = oesOpacityLoc,
                aspectLoc = oesAspectLoc,
                isFocused = isFocused,
                isHovered = isHovered,
                highContrast = settings.highContrastGlass,
                passthrough = passthrough,
                aspect = aspect
            )
            val stereoModeInt = if (win.type == WindowType.PC_DESKTOP) {
                0
            } else {
                when (settings.cinemaProjectionMode) {
                    CinemaProjectionMode.MONO_2D -> 0
                    CinemaProjectionMode.SBS_3D -> 1
                    CinemaProjectionMode.VR_180 -> 2
                    CinemaProjectionMode.VR_360 -> 3
                }
            }
            val srcW = if (win.type == WindowType.MEDIA_PLAYER) mediaVideoWidthPx else pcStreamWidthPx
            val srcH = if (win.type == WindowType.MEDIA_PLAYER) mediaVideoHeightPx else pcStreamHeightPx
            val fit = if (stereoModeInt == 0) {
                HandFrameTransform.computeAspectFitScale(srcW, srcH, 0, w, h)
            } else {
                HandFrameTransform.AspectFitScale(1f, 1f)
            }
            GLES30.glUniform2f(oesContentFitLoc, fit.scaleX, fit.scaleY)
            GLES30.glUniform1i(oesCinemaModeLoc, stereoModeInt)
            GLES30.glUniform1f(oesEyeIndexLoc, if (isLeftEye) 0f else 1f)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesPair.first)
            GLES30.glUniform1i(oesTexLoc, 0)
            drawTexturedQuad(unitQuadBuffer)
        } else {
            if (tex2dWindowProg == 0) return
            val tex2d = window2dTextures[win.id] ?: return
            GLES30.glUseProgram(tex2dWindowProg)
            GLES30.glUniformMatrix4fv(tex2dMvpLoc, 1, false, mvpMatrix, 0)
            setWindowUniforms(
                borderLoc = tex2dBorderLoc,
                opacityLoc = tex2dOpacityLoc,
                aspectLoc = tex2dAspectLoc,
                isFocused = isFocused,
                isHovered = isHovered,
                highContrast = settings.highContrastGlass,
                passthrough = passthrough,
                aspect = aspect
            )
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex2d.texId)
            GLES30.glUniform1i(tex2dTexLoc, 0)
            drawTexturedQuad(unitQuadBuffer)
        }
    }

    private fun drawWindowContactShadow(win: SpatialWindowState, settings: XRSettings) {
        val basis = win.computeWorldBasis()
        val depthScale = if (settings.angularScaleWindowsByDepth) {
            (win.distanceMeters / 2.25f).coerceIn(0.68f, 1.55f)
        } else 1.0f
        val hw = win.widthMeters * depthScale * 0.51f
        val hh = win.heightMeters * depthScale * 0.51f
        val offsetCenter = basis.center - basis.normal * 0.035f - basis.up * 0.025f
        val r = basis.right * hw
        val u = basis.up * hh

        GLES30.glUseProgram(color3dProg)
        GLES30.glUniformMatrix4fv(colMvpLoc, 1, false, vpMatrix, 0)
        GLES30.glUniform1f(colPointSizeLoc, 1f)
        GLES30.glUniform1f(colBrightnessLoc, 1f)
        dockColorBuffer.clear()
        putColorTriVert(dockColorBuffer, offsetCenter - r - u, 0f, 0f, 0f, 0.24f)
        putColorTriVert(dockColorBuffer, offsetCenter + r - u, 0f, 0f, 0f, 0.24f)
        putColorTriVert(dockColorBuffer, offsetCenter + r + u, 0f, 0f, 0f, 0.24f)
        putColorTriVert(dockColorBuffer, offsetCenter - r - u, 0f, 0f, 0f, 0.24f)
        putColorTriVert(dockColorBuffer, offsetCenter + r + u, 0f, 0f, 0f, 0.24f)
        putColorTriVert(dockColorBuffer, offsetCenter - r + u, 0f, 0f, 0f, 0.24f)
        drawColorVerts(dockColorBuffer, 6, GLES30.GL_TRIANGLES)
    }

    private fun setWindowUniforms(
        borderLoc: Int,
        opacityLoc: Int,
        aspectLoc: Int,
        isFocused: Boolean,
        isHovered: Boolean,
        highContrast: Boolean,
        passthrough: Boolean,
        aspect: Float
    ) {
        when {
            isHovered -> GLES30.glUniform4f(borderLoc, 0.35f, 0.84f, 1.0f, 0.96f)
            isFocused -> GLES30.glUniform4f(borderLoc, 0.65f, 0.78f, 1.0f, 0.78f)
            else -> GLES30.glUniform4f(borderLoc, 0.75f, 0.88f, 1.0f, 0.36f)
        }
        val opacity = when {
            highContrast -> 0.96f
            passthrough -> 0.84f
            else -> 0.88f
        }
        GLES30.glUniform1f(opacityLoc, opacity)
        GLES30.glUniform1f(aspectLoc, aspect)
    }

    private fun drawWindowGrabberBar(win: SpatialWindowState, hovered: Boolean, settings: XRSettings) {
        val basis = win.computeWorldBasis()
        val depthScale = if (settings.angularScaleWindowsByDepth) {
            (win.distanceMeters / 2.25f).coerceIn(0.68f, 1.55f)
        } else 1.0f
        val barCenter = basis.center - basis.up * (win.heightMeters * depthScale * 0.54f) + basis.normal * 0.02f
        val r = basis.right * ((if (hovered) 0.16f else 0.13f) * depthScale)
        val u = basis.up * ((if (hovered) 0.011f else 0.008f) * depthScale)

        GLES30.glUseProgram(color3dProg)
        GLES30.glUniformMatrix4fv(colMvpLoc, 1, false, vpMatrix, 0)
        GLES30.glUniform1f(colPointSizeLoc, 1f)
        GLES30.glUniform1f(colBrightnessLoc, 1f)

        val cr = if (hovered) 0.35f else 0.72f
        val cg = if (hovered) 0.86f else 0.84f
        val cb = 0.98f
        val ca = if (hovered) 0.95f else 0.52f
        dockColorBuffer.clear()
        putColorTriVert(dockColorBuffer, barCenter - r - u, cr, cg, cb, ca)
        putColorTriVert(dockColorBuffer, barCenter + r - u, cr, cg, cb, ca)
        putColorTriVert(dockColorBuffer, barCenter + r + u, cr, cg, cb, ca)
        putColorTriVert(dockColorBuffer, barCenter - r - u, cr, cg, cb, ca)
        putColorTriVert(dockColorBuffer, barCenter + r + u, cr, cg, cb, ca)
        putColorTriVert(dockColorBuffer, barCenter - r + u, cr, cg, cb, ca)

        // Ornament action pills flanking the grabber bar (Minimize / Close visual indicators)
        val dotHalf = 0.018f * depthScale
        val dotUp = basis.up * (0.010f * depthScale)
        val minCenter = barCenter - r - basis.right * (0.045f * depthScale)
        val dr = basis.right * dotHalf
        putColorTriVert(dockColorBuffer, minCenter - dr - dotUp, 0.35f, 0.84f, 0.98f, 0.75f)
        putColorTriVert(dockColorBuffer, minCenter + dr - dotUp, 0.35f, 0.84f, 0.98f, 0.75f)
        putColorTriVert(dockColorBuffer, minCenter + dr + dotUp, 0.35f, 0.84f, 0.98f, 0.75f)
        putColorTriVert(dockColorBuffer, minCenter - dr - dotUp, 0.35f, 0.84f, 0.98f, 0.75f)
        putColorTriVert(dockColorBuffer, minCenter + dr + dotUp, 0.35f, 0.84f, 0.98f, 0.75f)
        putColorTriVert(dockColorBuffer, minCenter - dr + dotUp, 0.35f, 0.84f, 0.98f, 0.75f)

        val closeCenter = barCenter + r + basis.right * (0.045f * depthScale)
        putColorTriVert(dockColorBuffer, closeCenter - dr - dotUp, 0.96f, 0.45f, 0.56f, 0.78f)
        putColorTriVert(dockColorBuffer, closeCenter + dr - dotUp, 0.96f, 0.45f, 0.56f, 0.78f)
        putColorTriVert(dockColorBuffer, closeCenter + dr + dotUp, 0.96f, 0.45f, 0.56f, 0.78f)
        putColorTriVert(dockColorBuffer, closeCenter - dr - dotUp, 0.96f, 0.45f, 0.56f, 0.78f)
        putColorTriVert(dockColorBuffer, closeCenter + dr + dotUp, 0.96f, 0.45f, 0.56f, 0.78f)
        putColorTriVert(dockColorBuffer, closeCenter - dr + dotUp, 0.96f, 0.45f, 0.56f, 0.78f)

        drawColorVerts(dockColorBuffer, 18, GLES30.GL_TRIANGLES)
    }

    private fun drawHandRayBeam(rayHit: RaycastTarget?) {
        val ray = currentRay ?: return
        val dist = when (rayHit) {
            is RaycastTarget.WindowHit -> rayHit.distance
            is RaycastTarget.DockHit -> rayHit.distance
            null -> 2.3f
        }.coerceIn(0.25f, 4.5f)
        val endPt = ray.origin + ray.direction * dist
        GLES30.glUseProgram(color3dProg)
        GLES30.glUniformMatrix4fv(colMvpLoc, 1, false, vpMatrix, 0)
        GLES30.glUniform1f(colPointSizeLoc, 12f)
        GLES30.glUniform1f(colBrightnessLoc, 1f)

        handRayBuffer.clear()
        putColorTriVert(handRayBuffer, ray.origin, 0.22f, 0.74f, 0.97f, 0.22f)
        putColorTriVert(handRayBuffer, endPt, 0.45f, 0.92f, 1.0f, if (rayHit != null) 0.92f else 0.45f)
        drawColorVerts(handRayBuffer, 2, GLES30.GL_LINES)

        handRayBuffer.clear()
        putColorTriVert(handRayBuffer, endPt, 0.55f, 0.96f, 1.0f, 0.98f)
        drawColorVerts(handRayBuffer, 1, GLES30.GL_POINTS)
    }

    private fun drawGltfStage(win: SpatialWindowState) {
        val pos = gltfPosBuf ?: return
        val norm = gltfNormBuf ?: return
        val idx = gltfIdxBuf ?: return
        val basis = win.computeWorldBasis()

        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, basis.center.x, basis.center.y - 0.15f, basis.center.z + 0.45f)
        Matrix.rotateM(modelMatrix, 0, gltfSpinAngle, 0f, 1f, 0f)
        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)

        GLES30.glUseProgram(litMeshProg)
        GLES30.glUniformMatrix4fv(litMvpLoc, 1, false, mvpMatrix, 0)
        GLES30.glUniformMatrix4fv(litModelLoc, 1, false, modelMatrix, 0)
        GLES30.glUniform3f(litTintLoc, 0.22f, 0.74f, 0.97f)

        pos.position(0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, pos)
        norm.position(0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 12, norm)

        idx.position(0)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, gltfIdxCount, GLES30.GL_UNSIGNED_SHORT, idx)
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
    }

    private fun drawSpatialDock(rayHit: RaycastTarget?) {
        GLES30.glUseProgram(color3dProg)
        GLES30.glUniformMatrix4fv(colMvpLoc, 1, false, vpMatrix, 0)
        GLES30.glUniform1f(colPointSizeLoc, 1f)
        GLES30.glUniform1f(colBrightnessLoc, 1f)

        val targets = windowManager.dockTargets
        if (targets.size >= 2) {
            // Subtle translucent Vision-style dock tray backing segments between icons
            for (i in 0 until targets.size - 1) {
                val a = targets[i]
                val b = targets[i + 1]
                val backDir = (a.normal + b.normal).normalized() * -0.02f
                val au = a.up * (a.height * 0.65f)
                val bu = b.up * (b.height * 0.65f)
                val p0 = a.center - au + backDir
                val p1 = b.center - bu + backDir
                val p2 = b.center + bu + backDir
                val p3 = a.center + au + backDir
                dockColorBuffer.clear()
                putColorTriVert(dockColorBuffer, p0, 0.06f, 0.11f, 0.20f, 0.48f)
                putColorTriVert(dockColorBuffer, p1, 0.06f, 0.11f, 0.20f, 0.48f)
                putColorTriVert(dockColorBuffer, p2, 0.06f, 0.11f, 0.20f, 0.48f)
                putColorTriVert(dockColorBuffer, p0, 0.06f, 0.11f, 0.20f, 0.48f)
                putColorTriVert(dockColorBuffer, p2, 0.06f, 0.11f, 0.20f, 0.48f)
                putColorTriVert(dockColorBuffer, p3, 0.06f, 0.11f, 0.20f, 0.48f)
                drawColorVerts(dockColorBuffer, 6, GLES30.GL_TRIANGLES)
            }
        }

        for (dock in targets) {
            val hovered = rayHit is RaycastTarget.DockHit && rayHit.action == dock.action
            val isArActive = dock.action == DockActionId.AR_PASSTHROUGH && (passthroughActive || arCoreActive)
            val hw = dock.width * (if (hovered) 0.56f else 0.46f)
            val hh = dock.height * (if (hovered) 0.56f else 0.46f)
            val c = dock.center
            val r = dock.right * hw
            val u = dock.up * hh

            val cr = when {
                hovered -> 0.22f
                isArActive -> 0.20f
                dock.action == DockActionId.HANDS_OFF -> 0.92f
                else -> 0.11f
            }
            val cg = when {
                hovered -> 0.74f
                isArActive -> 0.83f
                dock.action == DockActionId.HANDS_OFF -> 0.58f
                else -> 0.18f
            }
            val cb = when {
                hovered -> 0.97f
                isArActive -> 0.60f
                dock.action == DockActionId.HANDS_OFF -> 0.12f
                else -> 0.32f
            }
            val ca = if (hovered) 0.86f else 0.58f

            dockColorBuffer.clear()
            putColorTriVert(dockColorBuffer, c - r - u, cr, cg, cb, ca)
            putColorTriVert(dockColorBuffer, c + r - u, cr, cg, cb, ca)
            putColorTriVert(dockColorBuffer, c + r + u, cr, cg, cb, ca)
            putColorTriVert(dockColorBuffer, c - r - u, cr, cg, cb, ca)
            putColorTriVert(dockColorBuffer, c + r + u, cr, cg, cb, ca)
            putColorTriVert(dockColorBuffer, c - r + u, cr, cg, cb, ca)
            drawColorVerts(dockColorBuffer, 6, GLES30.GL_TRIANGLES)

            // Specular cyan rim outline for each translucent dock pill
            dockColorBuffer.clear()
            val edgeA = if (hovered) 0.98f else 0.45f
            val er = if (hovered) 0.45f else 0.32f
            val eg = if (hovered) 0.90f else 0.74f
            val eb = 0.99f
            putColorTriVert(dockColorBuffer, c - r - u, er, eg, eb, edgeA)
            putColorTriVert(dockColorBuffer, c + r - u, er, eg, eb, edgeA)
            putColorTriVert(dockColorBuffer, c + r - u, er, eg, eb, edgeA)
            putColorTriVert(dockColorBuffer, c + r + u, er, eg, eb, edgeA)
            putColorTriVert(dockColorBuffer, c + r + u, er, eg, eb, edgeA)
            putColorTriVert(dockColorBuffer, c - r + u, er, eg, eb, edgeA)
            putColorTriVert(dockColorBuffer, c - r + u, er, eg, eb, edgeA)
            putColorTriVert(dockColorBuffer, c - r - u, er, eg, eb, edgeA)
            drawColorVerts(dockColorBuffer, 8, GLES30.GL_LINES)
        }
    }

    private fun drawCalibrationGrid(isLeftEye: Boolean) {
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glUseProgram(color3dProg)
        Matrix.setIdentityM(modelMatrix, 0)
        GLES30.glUniformMatrix4fv(colMvpLoc, 1, false, modelMatrix, 0)
        GLES30.glUniform1f(colPointSizeLoc, 2f)
        GLES30.glUniform1f(colBrightnessLoc, 1f)

        calibBuffer.clear()
        var verts = 0
        fun line(x0: Float, y0: Float, x1: Float, y1: Float, r: Float, g: Float, b: Float, a: Float) {
            if (verts + 2 > 250) return
            calibBuffer.put(x0).put(y0).put(0f).put(r).put(g).put(b).put(a)
            calibBuffer.put(x1).put(y1).put(0f).put(r).put(g).put(b).put(a)
            verts += 2
        }

        // Crosshairs & horizon alignment lines
        line(-0.85f, 0f, 0.85f, 0f, 0.22f, 0.74f, 0.97f, 0.75f)
        line(0f, -0.85f, 0f, 0.85f, 0.22f, 0.74f, 0.97f, 0.75f)
        // Concentric distortion check rings
        val segs = 28
        for (radius in floatArrayOf(0.36f, 0.72f)) {
            for (i in 0 until segs) {
                val a0 = (i.toFloat() / segs) * 2f * PI.toFloat()
                val a1 = ((i + 1).toFloat() / segs) * 2f * PI.toFloat()
                line(
                    cos(a0) * radius, sin(a0) * radius,
                    cos(a1) * radius, sin(a1) * radius,
                    0.50f, 0.55f, 0.97f, 0.65f
                )
            }
        }
        // Per-eye L / R marker near center so binocular convergence is obvious
        if (isLeftEye) {
            line(-0.14f, 0.14f, -0.14f, 0.04f, 0.20f, 0.83f, 0.60f, 0.95f)
            line(-0.14f, 0.04f, -0.07f, 0.04f, 0.20f, 0.83f, 0.60f, 0.95f)
        } else {
            line(0.08f, 0.14f, 0.08f, 0.04f, 0.98f, 0.75f, 0.14f, 0.95f)
            line(0.08f, 0.14f, 0.14f, 0.14f, 0.98f, 0.75f, 0.14f, 0.95f)
            line(0.14f, 0.14f, 0.14f, 0.09f, 0.98f, 0.75f, 0.14f, 0.95f)
            line(0.08f, 0.09f, 0.14f, 0.09f, 0.98f, 0.75f, 0.14f, 0.95f)
            line(0.08f, 0.09f, 0.14f, 0.04f, 0.98f, 0.75f, 0.14f, 0.95f)
        }

        drawColorVerts(calibBuffer, verts, GLES30.GL_LINES)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    private fun drawHandSkeletons(pose: HeadPoseSnapshot, settings: XRSettings) {
        val hands = latestHands
        if (hands.isEmpty()) return
        GLES30.glUseProgram(color3dProg)
        GLES30.glUniformMatrix4fv(colMvpLoc, 1, false, vpMatrix, 0)
        GLES30.glUniform1f(colBrightnessLoc, 1f)

        val palmMeters = settings.handCalibration.palmWidthMeters
        val pinchClosed = settings.handCalibration.pinchClosedThreshold
        for (i in hands.indices) {
            val hand = hands[i]
            if (hand.imageLandmarks.size < 21) continue
            // Match landmarks to head pose AT CAMERA CAPTURE TIMESTAMP for stereo rock-solid stabilization
            val framePose = if (hand.frameTimestampNs > 0L) {
                headTracking.getPoseAtTimestamp(hand.frameTimestampNs)
            } else pose
            val palmDepth = handRayInteractor.estimatePalmDepthMeters(hand, palmMeters)
            val isPinching = hand.normalizedPinchRatio() <= pinchClosed
            val alpha = if (hand.isHeldDropout) 0.48f else 0.90f

            // 1. Bones
            GLES30.glUniform1f(colPointSizeLoc, 10f)
            handSkelBuffer.clear()
            var lineVerts = 0
            val bones = HandLandmarkIndex.BONE_CONNECTIONS
            for (bIdx in bones.indices) {
                val (a, b) = bones[bIdx]
                val pa = handRayInteractor.imageLandmarkToWorld(
                    hand.imageLandmarks[a], framePose, palmDepth, hand.cameraHfovDeg, hand.cameraVfovDeg
                )
                val pb = handRayInteractor.imageLandmarkToWorld(
                    hand.imageLandmarks[b], framePose, palmDepth, hand.cameraHfovDeg, hand.cameraVfovDeg
                )
                putColorTriVert(handSkelBuffer, pa, 0.22f, 0.78f, 0.99f, alpha)
                putColorTriVert(handSkelBuffer, pb, 0.45f, 0.58f, 0.99f, alpha)
                lineVerts += 2
            }
            drawColorVerts(handSkelBuffer, lineVerts, GLES30.GL_LINES)

            // 2. Fingertip & joint nodes
            GLES30.glUniform1f(colPointSizeLoc, 14f)
            handSkelBuffer.clear()
            var jointVerts = 0
            for (idx in 0 until 21) {
                val pt = handRayInteractor.imageLandmarkToWorld(
                    hand.imageLandmarks[idx], framePose, palmDepth, hand.cameraHfovDeg, hand.cameraVfovDeg
                )
                val isTip = idx == 4 || idx == 8 || idx == 12 || idx == 16 || idx == 20
                if (isTip) {
                    putColorTriVert(
                        handSkelBuffer,
                        pt,
                        if (isPinching && (idx == 4 || idx == 8)) 1.0f else 0.25f,
                        if (isPinching && (idx == 4 || idx == 8)) 0.85f else 0.95f,
                        if (isPinching && (idx == 4 || idx == 8)) 0.20f else 0.72f,
                        0.98f
                    )
                } else {
                    putColorTriVert(handSkelBuffer, pt, 0.72f, 0.88f, 1.0f, alpha * 0.85f)
                }
                jointVerts++
            }
            drawColorVerts(handSkelBuffer, jointVerts, GLES30.GL_POINTS)

            // 3. Pinch contact glow sphere-dot
            if (isPinching) {
                GLES30.glUniform1f(colPointSizeLoc, 24f)
                val contact = handRayInteractor.imageLandmarkToWorld(
                    hand.pinchContactPointImage(), framePose, palmDepth, hand.cameraHfovDeg, hand.cameraVfovDeg
                )
                handSkelBuffer.clear()
                putColorTriVert(handSkelBuffer, contact, 1.0f, 0.84f, 0.18f, 0.96f)
                drawColorVerts(handSkelBuffer, 1, GLES30.GL_POINTS)
            }
        }

        // 4. Direct poke glow point
        lastPokeWorldPoint?.let { pokePt ->
            GLES30.glUniform1f(colPointSizeLoc, 28f)
            handSkelBuffer.clear()
            putColorTriVert(handSkelBuffer, pokePt, 0.20f, 0.96f, 0.64f, 0.96f)
            drawColorVerts(handSkelBuffer, 1, GLES30.GL_POINTS)
        }
    }

    private fun drawReachZoneGuide(pose: HeadPoseSnapshot) {
        GLES30.glUseProgram(color3dProg)
        GLES30.glUniformMatrix4fv(colMvpLoc, 1, false, vpMatrix, 0)
        GLES30.glUniform1f(colPointSizeLoc, 2f)
        GLES30.glUniform1f(colBrightnessLoc, 1f)

        val z = -0.45f
        val hw = 0.24f
        val hh = 0.16f
        val c0 = pose.position + pose.orientation.rotate(Vec3(-hw, -hh, z))
        val c1 = pose.position + pose.orientation.rotate(Vec3(hw, -hh, z))
        val c2 = pose.position + pose.orientation.rotate(Vec3(hw, hh, z))
        val c3 = pose.position + pose.orientation.rotate(Vec3(-hw, hh, z))

        handSkelBuffer.clear()
        putColorTriVert(handSkelBuffer, c0, 0.22f, 0.74f, 0.97f, 0.34f)
        putColorTriVert(handSkelBuffer, c1, 0.22f, 0.74f, 0.97f, 0.34f)
        putColorTriVert(handSkelBuffer, c1, 0.22f, 0.74f, 0.97f, 0.34f)
        putColorTriVert(handSkelBuffer, c2, 0.22f, 0.74f, 0.97f, 0.34f)
        putColorTriVert(handSkelBuffer, c2, 0.22f, 0.74f, 0.97f, 0.34f)
        putColorTriVert(handSkelBuffer, c3, 0.22f, 0.74f, 0.97f, 0.34f)
        putColorTriVert(handSkelBuffer, c3, 0.22f, 0.74f, 0.97f, 0.34f)
        putColorTriVert(handSkelBuffer, c0, 0.22f, 0.74f, 0.97f, 0.34f)
        drawColorVerts(handSkelBuffer, 8, GLES30.GL_LINES)
    }

    private fun drawReticle(settings: XRSettings, rayHit: RaycastTarget?) {
        val progress = maxOf(dwellProgress, palmHoldProgress).coerceIn(0f, 1f)
        val hasHands = latestHands.isNotEmpty() && settings.handControlEnabled
        // No permanent crosshair over hovered targets unless charging dwell/palm or hand is actively tracked
        if (rayHit != null && progress <= 0.02f && !hasHands) return

        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glUseProgram(color3dProg)
        Matrix.setIdentityM(modelMatrix, 0)
        GLES30.glUniformMatrix4fv(colMvpLoc, 1, false, modelMatrix, 0)
        GLES30.glUniform1f(colPointSizeLoc, 6f)
        GLES30.glUniform1f(colBrightnessLoc, 1f)

        val segs = 32
        val radius = if (progress > 0.02f) 0.026f else if (hasHands) 0.020f else 0.012f
        val activeSegs = if (progress > 0.02f) (segs * progress).toInt().coerceAtLeast(1) else segs
        val alpha = if (progress > 0.02f) 0.95f else if (hasHands) 0.55f else 0.28f

        reticleBuffer.clear()
        var vertCount = 0
        for (i in 0 until activeSegs) {
            val a0 = (i.toFloat() / segs) * 2f * PI.toFloat()
            val a1 = ((i + 1).toFloat() / segs) * 2f * PI.toFloat()
            val cr = if (palmHoldProgress > 0.05f) 0.50f else 0.22f
            val cg = if (palmHoldProgress > 0.05f) 0.55f else 0.74f
            val cb = 0.97f
            reticleBuffer.put(cos(a0) * radius).put(sin(a0) * radius).put(0f)
                .put(cr).put(cg).put(cb).put(alpha)
            reticleBuffer.put(cos(a1) * radius).put(sin(a1) * radius).put(0f)
                .put(cr).put(cg).put(cb).put(alpha)
            vertCount += 2
        }
        drawColorVerts(reticleBuffer, vertCount, GLES30.GL_LINES)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    private fun drawColorVerts(buf: FloatBuffer, count: Int, mode: Int) {
        val stride = 7 * 4
        buf.position(0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, buf)
        buf.position(3)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, stride, buf)
        GLES30.glDrawArrays(mode, 0, count)
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
    }

    private fun putColorTriVert(buf: FloatBuffer, p: Vec3, r: Float, g: Float, b: Float, a: Float) {
        buf.put(p.x).put(p.y).put(p.z).put(r).put(g).put(b).put(a)
    }

    private fun drawTexturedQuad(quadBuf: FloatBuffer) {
        val stride = 5 * 4
        quadBuf.position(0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, quadBuf)
        quadBuf.position(3)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, stride, quadBuf)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
    }

    private fun drawDistortedEye(vbo: Int, ibo: Int, colorTex: Int) {
        if (distortionProg == 0 || vbo == 0 || ibo == 0 || colorTex == 0) return
        GLES30.glUseProgram(distortionProg)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, colorTex)
        GLES30.glUniform1i(distEyeTexLoc, 0)
        GLES30.glUniformMatrix3fv(distTimewarpLoc, 1, false, timewarpMat, 0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)

        val stride = DistortionMesh.FLOATS_PER_VERTEX * 4
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, stride, 8)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, stride, 16)
        GLES30.glEnableVertexAttribArray(3)
        GLES30.glVertexAttribPointer(3, 2, GLES30.GL_FLOAT, false, stride, 24)
        GLES30.glEnableVertexAttribArray(4)
        GLES30.glVertexAttribPointer(4, 1, GLES30.GL_FLOAT, false, stride, 32)

        GLES30.glDrawElements(GLES30.GL_TRIANGLES, distortionIndexCount, GLES30.GL_UNSIGNED_SHORT, 0)

        for (i in 0..4) GLES30.glDisableVertexAttribArray(i)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    private fun hasActiveWindow(windows: List<SpatialWindowState>, id: String): Boolean {
        for (i in windows.indices) {
            if (windows[i].id == id) return true
        }
        return false
    }

    private fun syncWindowTextures() {
        val currentWindows = windowManager.windows.value

        staleIdsScratch.clear()
        for (id in windowOesTextures.keys) {
            if (!hasActiveWindow(currentWindows, id)) staleIdsScratch.add(id)
        }
        for (i in staleIdsScratch.indices) {
            val id = staleIdsScratch[i]
            windowOesTextures.remove(id)?.let { (texId, st) ->
                contentHost.unbindWindow(id)
                st.release()
                glHandleScratch1[0] = texId
                GLES30.glDeleteTextures(1, glHandleScratch1, 0)
            }
        }

        staleIdsScratch.clear()
        for (id in window2dTextures.keys) {
            if (!hasActiveWindow(currentWindows, id)) staleIdsScratch.add(id)
        }
        for (i in staleIdsScratch.indices) {
            val id = staleIdsScratch[i]
            window2dTextures.remove(id)?.let { entry ->
                contentHost.unbindWindow(id)
                glHandleScratch1[0] = entry.texId
                GLES30.glDeleteTextures(1, glHandleScratch1, 0)
                windowHoverSprings.remove(id)
                windowAppearSprings.remove(id)
            }
        }

        for (i in currentWindows.indices) {
            val win = currentWindows[i]
            if (win.type == WindowType.MEDIA_PLAYER || win.type == WindowType.PC_DESKTOP) {
                if (!windowOesTextures.containsKey(win.id)) {
                    GLES30.glGenTextures(1, glHandleScratch1, 0)
                    val texId = glHandleScratch1[0]
                    GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
                    GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                    GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                    val st = SurfaceTexture(texId)
                    windowOesTextures[win.id] = texId to st
                    if (win.type == WindowType.MEDIA_PLAYER) {
                        contentHost.bindMediaSurface(win, st)
                    } else {
                        st.setDefaultBufferSize(win.pixelWidth, win.pixelHeight)
                        onDesktopStreamSurfaceReady?.invoke(st, win.pixelWidth, win.pixelHeight)
                    }
                }
                windowOesTextures[win.id]?.second?.let { st ->
                    runCatching { st.updateTexImage() }
                }
            } else {
                contentHost.ensureViewWindowHosted(win)
                val entry = window2dTextures[win.id] ?: run {
                    GLES30.glGenTextures(1, glHandleScratch1, 0)
                    val texId = glHandleScratch1[0]
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
                    Window2dTex(texId).also { window2dTextures[win.id] = it }
                }
                entry.uploadedSeq = contentHost.uploadViewBitmapIfNewer(win.id, entry.uploadedSeq) { bmp ->
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, entry.texId)
                    if (!entry.initialized) {
                        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
                        entry.initialized = true
                    } else {
                        GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, bmp)
                    }
                }
            }
        }
    }

    private fun uploadPendingGltfIfNeeded() {
        val mesh = pendingMeshGeometry ?: return
        pendingMeshGeometry = null
        gltfPosBuf = toFloatBuf(mesh.positions)
        gltfNormBuf = toFloatBuf(mesh.normals)
        gltfIdxBuf = ByteBuffer.allocateDirect(mesh.indices.size * 2)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
            .apply { put(mesh.indices); position(0) }
        gltfIdxCount = mesh.indices.size
    }

    private fun rebuildEyeFbosIfNeeded(force: Boolean) {
        val tierScale = performanceMonitor.telemetry.value.qualityTier.renderScaleMultiplier
        val userScale = currentSettings.optics.renderScale
        val scale = (userScale * tierScale).coerceIn(0.55f, 1.0f)
        val desiredW = ((screenWidth / 2) * scale).toInt().coerceAtLeast(128)
        val desiredH = (screenHeight * scale).toInt().coerceAtLeast(128)
        if (!force && desiredW == fboWidth && desiredH == fboHeight && leftFbo != 0) return

        deleteFbos()
        fboWidth = desiredW
        fboHeight = desiredH
        createEyeFbo(desiredW, desiredH)
        leftFbo = glHandleScratch3[0]; leftColorTex = glHandleScratch3[1]; leftDepthRbo = glHandleScratch3[2]
        createEyeFbo(desiredW, desiredH)
        rightFbo = glHandleScratch3[0]; rightColorTex = glHandleScratch3[1]; rightDepthRbo = glHandleScratch3[2]
    }

    private fun createEyeFbo(w: Int, h: Int) {
        GLES30.glGenFramebuffers(1, glHandleScratch1, 0)
        val fbo = glHandleScratch1[0]
        GLES30.glGenTextures(1, glHandleScratch1, 0)
        val tex = glHandleScratch1[0]
        GLES30.glGenRenderbuffers(1, glHandleScratch1, 0)
        val rbo = glHandleScratch1[0]

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, rbo)
        GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT16, w, h)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex, 0)
        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_RENDERBUFFER, rbo)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        glHandleScratch3[0] = fbo
        glHandleScratch3[1] = tex
        glHandleScratch3[2] = rbo
    }

    private fun deleteFbos() {
        if (leftFbo != 0) {
            glHandleScratch1[0] = leftFbo
            GLES30.glDeleteFramebuffers(1, glHandleScratch1, 0)
        }
        if (leftColorTex != 0) {
            glHandleScratch1[0] = leftColorTex
            GLES30.glDeleteTextures(1, glHandleScratch1, 0)
        }
        if (leftDepthRbo != 0) {
            glHandleScratch1[0] = leftDepthRbo
            GLES30.glDeleteRenderbuffers(1, glHandleScratch1, 0)
        }
        if (rightFbo != 0) {
            glHandleScratch1[0] = rightFbo
            GLES30.glDeleteFramebuffers(1, glHandleScratch1, 0)
        }
        if (rightColorTex != 0) {
            glHandleScratch1[0] = rightColorTex
            GLES30.glDeleteTextures(1, glHandleScratch1, 0)
        }
        if (rightDepthRbo != 0) {
            glHandleScratch1[0] = rightDepthRbo
            GLES30.glDeleteRenderbuffers(1, glHandleScratch1, 0)
        }
        leftFbo = 0; rightFbo = 0
    }

    private fun rebuildDistortionMeshes(optics: OpticsProfile, gridSize: Int = 40) {
        lastOpticsSnapshot = optics
        lastGridSize = gridSize
        if (leftMeshVbo != 0) {
            glHandleScratch4[0] = leftMeshVbo
            glHandleScratch4[1] = leftMeshIbo
            glHandleScratch4[2] = rightMeshVbo
            glHandleScratch4[3] = rightMeshIbo
            GLES30.glDeleteBuffers(4, glHandleScratch4, 0)
        }

        GLES30.glGenBuffers(4, glHandleScratch4, 0)
        leftMeshVbo = glHandleScratch4[0]; leftMeshIbo = glHandleScratch4[1]
        rightMeshVbo = glHandleScratch4[2]; rightMeshIbo = glHandleScratch4[3]

        val leftData = DistortionMesh.buildEyeMesh(optics, isLeftEye = true, gridSize = gridSize)
        val rightData = DistortionMesh.buildEyeMesh(optics, isLeftEye = false, gridSize = gridSize)
        distortionIndexCount = leftData.indices.size

        uploadMeshBuffers(leftMeshVbo, leftMeshIbo, leftData)
        uploadMeshBuffers(rightMeshVbo, rightMeshIbo, rightData)
    }

    private fun uploadMeshBuffers(vbo: Int, ibo: Int, data: DistortionMesh.MeshData) {
        val vb = toFloatBuf(data.vertices)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.vertices.size * 4, vb, GLES30.GL_STATIC_DRAW)

        val ib = ByteBuffer.allocateDirect(data.indices.size * 2)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
            .apply { put(data.indices); position(0) }
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, data.indices.size * 2, ib, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    private fun toFloatBuf(arr: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(arr.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(arr); position(0)
        }

    private fun createUnitQuadBuffer(): FloatBuffer = toFloatBuf(
        floatArrayOf(
            -0.5f, -0.5f, 0f, 0f, 0f,
            0.5f, -0.5f, 0f, 1f, 0f,
            -0.5f, 0.5f, 0f, 0f, 1f,
            0.5f, 0.5f, 0f, 1f, 1f
        )
    )

    private fun createFullScreenQuadBuffer(): FloatBuffer = toFloatBuf(
        floatArrayOf(
            -1f, -1f, 0f, 0f, 0f,
            1f, -1f, 0f, 1f, 0f,
            -1f, 1f, 0f, 0f, 1f,
            1f, 1f, 0f, 1f, 1f
        )
    )
}
