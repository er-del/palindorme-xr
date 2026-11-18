package com.example.xr.windows

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.xr.apps.LaunchableApp
import com.example.xr.companion.CompanionLinkStats
import com.example.xr.hand.HandFrame
import com.example.xr.hand.HandLandmarkIndex
import com.example.xr.hand.HandTrackingTelemetry
import com.example.xr.model.EnvironmentConfig
import com.example.xr.model.VirtualEnvironmentType
import com.example.xr.model.XRSettings
import com.example.xr.perception.ArPerceptionTelemetry
import com.example.xr.system.DeviceHardwareDiagnostics
import com.example.xr.system.PerformanceTelemetry
import com.example.xr.tracking.TrackingTelemetry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Off-screen host owning Android Views, WebView, and Media3 ExoPlayer instances.
 * View-backed windows draw onto their Surface via Surface.lockHardwareCanvas() (with software fallback).
 * Media3 ExoPlayer renders directly to its window Surface via setVideoSurface(surface).
 */
class VirtualContentHost(
    private val context: Context,
    private val containerView: FrameLayout
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mediaSurfaceEntries = mutableMapOf<String, HostedMediaSurface>()
    private val viewBitmapEntries = java.util.concurrent.ConcurrentHashMap<String, HostedViewBitmap>()

    private var exoPlayer: ExoPlayer? = null
    private var activeMediaSurfaceId: String? = null
    private var currentMediaTitle: String = "Big Buck Bunny (Open Sample Stream)"
    private var currentMediaStatus: String = "Ready — tap Play or pick a local video"

    private var latestSettings: XRSettings = XRSettings()
    private var latestApps: List<LaunchableApp> = emptyList()
    private var latestPerf: PerformanceTelemetry = PerformanceTelemetry()
    private var latestTracking: TrackingTelemetry = TrackingTelemetry()
    private var latestHand: HandTrackingTelemetry = HandTrackingTelemetry()
    private var latestAr: ArPerceptionTelemetry = ArPerceptionTelemetry()
    private var latestDevice: DeviceHardwareDiagnostics = DeviceHardwareDiagnostics()
    private var latestPcLink: CompanionLinkStats = CompanionLinkStats()
    private var latestDebugBitmap: android.graphics.Bitmap? = null
    private var latestDebugHands: List<HandFrame> = emptyList()
    private var latestGalleryUris: List<Uri> = emptyList()
    private var currentBrowserUrl: String = "https://en.wikipedia.org/wiki/Spatial_computing"
    private var selectedGltfName: String = "Palindorme Gyro Prism"
    private var lastTouchDownTime: Long = 0L

    var onLaunchAppRequested: ((LaunchableApp) -> Unit)? = null
    var onOpenKeyboardRequested: ((initialText: String, onSubmit: (String) -> Unit) -> Unit)? = null
    var onPickPhotoRequested: (() -> Unit)? = null
    var onPickVideoRequested: (() -> Unit)? = null
    var onPickGltfRequested: (() -> Unit)? = null
    var onGltfPresetSelected: ((Int) -> Unit)? = null
    var onMediaVideoSizeChanged: ((Int, Int) -> Unit)? = null
    var onSettingsMutated: (((XRSettings) -> XRSettings) -> Unit)? = null
    var onActionCommand: ((String) -> Unit)? = null

    private var keyboardSubmitCallback: ((String) -> Unit)? = null
    private var browserWebView: WebView? = null
    private var browserUrlLabel: TextView? = null
    @Volatile
    private var browserDirty: Boolean = true
    @Volatile
    private var browserLoading: Boolean = false
    private var lastBrowserTickMs: Long = 0L

    private var drawLoopRunning = false
    private val drawRunnable = object : Runnable {
        override fun run() {
            if (!drawLoopRunning) return
            renderAllViewBitmaps()
            mainHandler.postDelayed(this, 50L) // ~20 fps view surface updates
        }
    }

    private data class HostedMediaSurface(
        val windowId: String,
        val surfaceTexture: SurfaceTexture,
        val surface: Surface,
        val widthPx: Int,
        val heightPx: Int
    )

    private class HostedViewBitmap(
        val windowId: String,
        val type: WindowType,
        val widthPx: Int,
        val heightPx: Int,
        val rootView: View,
        val bitmap: android.graphics.Bitmap,
        val canvas: Canvas
    ) {
        val lock = Any()
        @Volatile
        var sequence: Long = 0L
        @Volatile
        var dirty: Boolean = true
        var lastRenderSignature: String = ""
    }

    fun start() {
        if (drawLoopRunning) return
        drawLoopRunning = true
        mainHandler.post(drawRunnable)
    }

    fun stop() {
        drawLoopRunning = false
        mainHandler.removeCallbacks(drawRunnable)
    }

    fun releaseAll() {
        stop()
        mainHandler.post {
            exoPlayer?.release()
            exoPlayer = null
            mediaSurfaceEntries.values.forEach { runCatching { it.surface.release() } }
            mediaSurfaceEntries.clear()
            viewBitmapEntries.values.forEach { entry ->
                containerView.removeView(entry.rootView)
                runCatching { entry.bitmap.recycle() }
            }
            viewBitmapEntries.clear()
        }
    }

    fun unbindAllSurfaces() {
        mainHandler.post {
            exoPlayer?.setVideoSurface(null)
            activeMediaSurfaceId = null
            mediaSurfaceEntries.values.forEach { runCatching { it.surface.release() } }
            mediaSurfaceEntries.clear()
        }
    }

    fun updateSnapshots(
        settings: XRSettings,
        apps: List<LaunchableApp>,
        perf: PerformanceTelemetry,
        tracking: TrackingTelemetry,
        hand: HandTrackingTelemetry,
        ar: ArPerceptionTelemetry,
        galleryUris: List<Uri>,
        device: DeviceHardwareDiagnostics = latestDevice,
        pcLink: CompanionLinkStats = latestPcLink,
        debugBitmap: android.graphics.Bitmap? = latestDebugBitmap,
        debugHands: List<HandFrame> = latestDebugHands
    ) {
        latestSettings = settings
        latestApps = apps
        latestPerf = perf
        latestTracking = tracking
        latestHand = hand
        latestAr = ar
        latestGalleryUris = galleryUris
        latestDevice = device
        latestPcLink = pcLink
        latestDebugBitmap = debugBitmap
        latestDebugHands = debugHands
    }

    fun ensureViewWindowHosted(window: SpatialWindowState) {
        if (window.type == WindowType.MEDIA_PLAYER || window.type == WindowType.PC_DESKTOP) return
        if (viewBitmapEntries.containsKey(window.id)) return
        mainHandler.post {
            if (viewBitmapEntries.containsKey(window.id)) return@post
            runCatching {
                val view = buildViewForWindow(window)
                layoutOffscreenView(view, window.pixelWidth, window.pixelHeight)
                containerView.addView(
                    view,
                    FrameLayout.LayoutParams(window.pixelWidth, window.pixelHeight)
                )
                val bmp = android.graphics.Bitmap.createBitmap(
                    window.pixelWidth,
                    window.pixelHeight,
                    android.graphics.Bitmap.Config.ARGB_8888
                )
                val canvas = Canvas(bmp)
                val entry = HostedViewBitmap(
                    windowId = window.id,
                    type = window.type,
                    widthPx = window.pixelWidth,
                    heightPx = window.pixelHeight,
                    rootView = view,
                    bitmap = bmp,
                    canvas = canvas
                )
                drawViewToBitmap(entry, force = true)
                viewBitmapEntries[window.id] = entry
            }.onFailure { err ->
                Log.e("VirtualContentHost", "Failed to host spatial window ${window.id} (${window.type})", err)
            }
        }
    }

    fun uploadViewBitmapIfNewer(windowId: String, lastUploadedSeq: Long, onUpload: (android.graphics.Bitmap) -> Unit): Long {
        val entry = viewBitmapEntries[windowId] ?: return lastUploadedSeq
        val seq = entry.sequence
        if (seq <= lastUploadedSeq) return lastUploadedSeq
        synchronized(entry.lock) {
            if (!entry.bitmap.isRecycled) {
                onUpload(entry.bitmap)
            }
            return entry.sequence
        }
    }

    fun bindMediaSurface(window: SpatialWindowState, surfaceTexture: SurfaceTexture) {
        if (window.type != WindowType.MEDIA_PLAYER) return
        mainHandler.post {
            val existing = mediaSurfaceEntries[window.id]
            if (existing != null && existing.surfaceTexture === surfaceTexture) return@post
            existing?.let { runCatching { it.surface.release() } }

            surfaceTexture.setDefaultBufferSize(window.pixelWidth, window.pixelHeight)
            val surface = Surface(surfaceTexture)
            val player = ensureExoPlayer()
            activeMediaSurfaceId = window.id
            player.setVideoSurface(surface)
            mediaSurfaceEntries[window.id] = HostedMediaSurface(
                windowId = window.id,
                surfaceTexture = surfaceTexture,
                surface = surface,
                widthPx = window.pixelWidth,
                heightPx = window.pixelHeight
            )
        }
    }

    fun unbindWindow(windowId: String) {
        mainHandler.post {
            mediaSurfaceEntries.remove(windowId)?.let { entry ->
                if (activeMediaSurfaceId == windowId) {
                    exoPlayer?.setVideoSurface(null)
                    activeMediaSurfaceId = null
                }
                runCatching { entry.surface.release() }
            }
            viewBitmapEntries.remove(windowId)?.let { entry ->
                containerView.removeView(entry.rootView)
                synchronized(entry.lock) {
                    runCatching { entry.bitmap.recycle() }
                }
            }
        }
    }

    fun dispatchTouch(windowId: String, u: Float, v: Float, action: Int) {
        mainHandler.post {
            if (mediaSurfaceEntries.containsKey(windowId)) {
                if (action == MotionEvent.ACTION_UP) {
                    toggleMediaPlayback()
                }
                return@post
            }
            val entry = viewBitmapEntries[windowId] ?: return@post
            val x = u.coerceIn(0f, 1f) * entry.widthPx
            val y = v.coerceIn(0f, 1f) * entry.heightPx
            val now = SystemClock.uptimeMillis()
            if (action == MotionEvent.ACTION_DOWN) {
                lastTouchDownTime = now
            }
            val event = MotionEvent.obtain(lastTouchDownTime, now, action, x, y, 0)
            entry.rootView.dispatchTouchEvent(event)
            event.recycle()
            entry.dirty = true
            drawViewToBitmap(entry, force = true)
        }
    }

    fun dispatchScroll(windowId: String, deltaY: Float) {
        mainHandler.post {
            val entry = viewBitmapEntries[windowId] ?: return@post
            findScrollable(entry.rootView)?.scrollBy(0, (deltaY * 220f).toInt())
            entry.dirty = true
            drawViewToBitmap(entry, force = true)
        }
    }

    private fun findScrollable(v: View): View? {
        if (v is ScrollView || v is WebView) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                findScrollable(v.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    fun playMediaUri(uri: Uri, title: String) {
        mainHandler.post {
            val player = ensureExoPlayer()
            currentMediaTitle = title
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
            player.playWhenReady = true
            currentMediaStatus = "Playing: $title"
        }
    }

    fun toggleMediaPlayback() {
        mainHandler.post {
            val player = ensureExoPlayer()
            if (player.mediaItemCount == 0) {
                val sampleUri = Uri.parse("https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4")
                playMediaUri(sampleUri, "Big Buck Bunny (Sample Stream)")
            } else if (player.isPlaying) {
                player.pause()
                currentMediaStatus = "Paused: $currentMediaTitle"
            } else {
                player.play()
                currentMediaStatus = "Playing: $currentMediaTitle"
            }
        }
    }

    fun loadBrowserUrl(rawUrl: String) {
        mainHandler.post {
            var target = rawUrl.trim()
            if (!target.startsWith("http://") && !target.startsWith("https://")) {
                target = if ('.' in target && ' ' !in target) {
                    "https://$target"
                } else {
                    "https://en.wikipedia.org/w/index.php?search=${Uri.encode(target)}"
                }
            }
            if (latestSettings.browserHttpsOnly && target.startsWith("http://")) {
                target = target.replaceFirst("http://", "https://")
            }
            currentBrowserUrl = target
            browserUrlLabel?.text = target
            browserLoading = true
            browserDirty = true
            browserWebView?.loadUrl(target)
        }
    }

    fun openKeyboardFor(initial: String, onSubmit: (String) -> Unit) {
        keyboardSubmitCallback = onSubmit
        onOpenKeyboardRequested?.invoke(initial, onSubmit)
    }

    private fun ensureExoPlayer(): ExoPlayer {
        exoPlayer?.let { return it }
        return ExoPlayer.Builder(context).build().apply {
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    currentMediaStatus = "Playback error (check network or unsupported DRM): ${error.errorCodeName}"
                }

                override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        onMediaVideoSizeChanged?.invoke(videoSize.width, videoSize.height)
                    }
                }
            })
            exoPlayer = this
        }
    }

    private fun renderAllViewBitmaps() {
        for (entry in viewBitmapEntries.values) {
            drawViewToBitmap(entry, force = false)
        }
    }

    private val glassBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9C0D1628")
        style = Paint.Style.FILL
    }
    private val glassSheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#18E2F1FF")
        style = Paint.Style.FILL
    }
    private val glassStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#597DD3FC")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val glassRect = RectF()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private fun drawViewToBitmap(entry: HostedViewBitmap, force: Boolean = false) {
        synchronized(entry.lock) {
            if (entry.bitmap.isRecycled) return
            val contentChanged = refreshDynamicPanels(entry)
            if (!force && !entry.dirty && !contentChanged) {
                return
            }
            entry.dirty = false
            layoutOffscreenView(entry.rootView, entry.widthPx, entry.heightPx)
            entry.canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            glassRect.set(2f, 2f, entry.widthPx - 2f, entry.heightPx - 2f)
            glassBgPaint.color = if (latestSettings.highContrastGlass) {
                Color.parseColor("#DF091120")
            } else {
                Color.parseColor("#920E182D")
            }
            entry.canvas.drawRoundRect(glassRect, 28f, 28f, glassBgPaint)
            entry.canvas.drawRoundRect(glassRect, 28f, 28f, glassSheenPaint)
            entry.rootView.draw(entry.canvas)
            drawWindowTitleChrome(entry.canvas, entry)
            entry.canvas.drawRoundRect(glassRect, 28f, 28f, glassStrokePaint)
            entry.sequence++
        }
    }

    private val chromeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#66182844") }
    private val chromeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F8FAFC")
        textSize = 27f
        isFakeBoldText = true
    }
    private val chromeBtnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7DD3FC")
        textSize = 26f
        isFakeBoldText = true
    }
    private val chromeClosePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FDA4AF")
        textSize = 26f
        isFakeBoldText = true
    }

    private fun drawWindowTitleChrome(canvas: Canvas, entry: HostedViewBitmap) {
        val headerH = entry.heightPx * 0.105f
        canvas.drawRoundRect(2f, 2f, entry.widthPx - 2f, headerH, 24f, 24f, chromeBgPaint)
        canvas.drawText(entry.type.defaultTitle, 26f, headerH * 0.65f, chromeTextPaint)
        canvas.drawText("—", entry.widthPx * 0.84f, headerH * 0.65f, chromeBtnPaint)
        canvas.drawText("✕", entry.widthPx * 0.93f, headerH * 0.65f, chromeClosePaint)
        canvas.drawText("◢", entry.widthPx - 36f, entry.heightPx - 14f, chromeBtnPaint)
    }

    private fun layoutOffscreenView(view: View, width: Int, height: Int) {
        val wSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val hSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        view.measure(wSpec, hSpec)
        view.layout(0, 0, width, height)
    }

    private fun buildViewForWindow(window: SpatialWindowState): View {
        return when (window.type) {
            WindowType.APP_LIBRARY -> buildAppLibraryView()
            WindowType.BROWSER -> buildBrowserView()
            WindowType.GALLERY -> buildGalleryView()
            WindowType.GLTF_VIEWER -> buildGltfControlsView()
            WindowType.SETTINGS -> buildQuickSettingsView()
            WindowType.DIAGNOSTICS -> buildDiagnosticsView()
            WindowType.HAND_LAB -> buildHandDebugLabWindowView()
            WindowType.PC_DESKTOP -> FrameLayout(context)
            WindowType.CONTROL_CENTER -> buildControlCenterView()
            WindowType.SPATIAL_KEYBOARD -> SpatialKeyboardView(
                context = context,
                onTextChange = {},
                onSubmit = { submitted ->
                    keyboardSubmitCallback?.invoke(submitted) ?: loadBrowserUrl(submitted)
                    onActionCommand?.invoke("close_keyboard")
                },
                onVoiceDictate = { onActionCommand?.invoke("voice_dictate") }
            )
            WindowType.INFO -> buildInfoGuideView()
            WindowType.MEDIA_PLAYER -> FrameLayout(context)
        }
    }

    private var lastGalleryRenderSignature: String = ""
    private var lastLauncherAppsSignature: Int = -1
    private var lastQuickSettingsSignature: String = ""

    private fun refreshDynamicPanels(entry: HostedViewBitmap): Boolean {
        val contrastFlag = latestSettings.highContrastGlass
        return when (entry.type) {
            WindowType.DIAGNOSTICS -> {
                val body = buildDiagnosticsText()
                val sig = "$body|$contrastFlag"
                if (sig != entry.lastRenderSignature) {
                    entry.lastRenderSignature = sig
                    entry.rootView.findViewWithTag<TextView>("diag_body")?.text = body
                    true
                } else false
            }
            WindowType.INFO -> {
                val sig = "$currentMediaStatus|$contrastFlag"
                if (sig != entry.lastRenderSignature) {
                    entry.lastRenderSignature = sig
                    entry.rootView.findViewWithTag<TextView>("info_media_status")?.text = currentMediaStatus
                    true
                } else false
            }
            WindowType.APP_LIBRARY -> {
                val statusText =
                    "${timeFormat.format(Date())} · ${latestPerf.batteryPercent}% · AR:${if (latestSettings.passthroughEnabled) "ON" else "OFF"} · Cam:${latestSettings.cameraRotationDegrees}°"
                val appsCount = latestApps.size
                if (appsCount != lastLauncherAppsSignature) {
                    lastLauncherAppsSignature = appsCount
                    entry.rootView.findViewWithTag<LinearLayout>("launcher_apps_grid")?.let { grid ->
                        populateLauncherGrid(grid)
                    }
                }
                val sig = "$statusText|$appsCount|${latestSettings.favoriteApps.size}|$contrastFlag"
                if (sig != entry.lastRenderSignature) {
                    entry.lastRenderSignature = sig
                    entry.rootView.findViewWithTag<TextView>("launcher_status_pill")?.text = statusText
                    true
                } else false
            }
            WindowType.GALLERY -> {
                val sig = "${latestGalleryUris.joinToString(",")}|${latestSettings.galleryRotationDegrees}|$contrastFlag"
                if (sig != entry.lastRenderSignature) {
                    entry.lastRenderSignature = sig
                    entry.rootView.findViewWithTag<Button>("gallery_rotate_btn")?.text =
                        "Rotate Photos (${latestSettings.galleryRotationDegrees}°)"
                    val innerSig = "${latestGalleryUris.joinToString(",")}|${latestSettings.galleryRotationDegrees}"
                    if (innerSig != lastGalleryRenderSignature) {
                        lastGalleryRenderSignature = innerSig
                        entry.rootView.findViewWithTag<LinearLayout>("gallery_photo_col")?.let { col ->
                            populateGalleryPhotos(col)
                        }
                    }
                    true
                } else false
            }
            WindowType.SETTINGS -> {
                val sig = "${latestSettings.environment.type.id}|${latestSettings.cameraRotationDegrees}|${latestSettings.cameraMirrorHorizontal}|${latestSettings.showCalibrationGrid}|${latestSettings.passthroughEnabled}|$contrastFlag"
                if (sig != entry.lastRenderSignature) {
                    entry.lastRenderSignature = sig
                    lastQuickSettingsSignature = sig
                    entry.rootView.findViewWithTag<TextView>("quick_cam_status")?.text =
                        "Camera Rotation: ${latestSettings.cameraRotationDegrees}° · Mirror: ${if (latestSettings.cameraMirrorHorizontal) "ON" else "OFF"} · Grid: ${if (latestSettings.showCalibrationGrid) "ON" else "OFF"}"
                    true
                } else false
            }
            WindowType.HAND_LAB -> {
                val summary = buildHandLabSummaryText()
                val traceTxt = if (latestHand.traceRecording) "Stop & Save Trace (${latestHand.traceFrameCount}f)" else "Capture Trace (.jsonl)"
                val sig = "$summary|$traceTxt|${latestDebugHands.size}|${latestDebugBitmap?.generationId ?: 0}|$contrastFlag"
                if (sig != entry.lastRenderSignature) {
                    entry.lastRenderSignature = sig
                    entry.rootView.findViewWithTag<HandOverlaySurfaceView>("hand_lab_canvas")
                        ?.updateState(latestDebugBitmap, latestDebugHands, latestHand)
                    entry.rootView.findViewWithTag<TextView>("hand_lab_summary")?.text = summary
                    entry.rootView.findViewWithTag<Button>("hand_lab_trace_btn")?.text = traceTxt
                    true
                } else false
            }
            WindowType.BROWSER -> {
                val nowMs = SystemClock.uptimeMillis()
                val loadingTick = browserLoading && (nowMs - lastBrowserTickMs >= 250L)
                val sig = "$currentBrowserUrl|$contrastFlag"
                val sigChanged = sig != entry.lastRenderSignature
                if (sigChanged) entry.lastRenderSignature = sig
                if (browserDirty || loadingTick || sigChanged) {
                    browserDirty = false
                    lastBrowserTickMs = nowMs
                    true
                } else false
            }
            else -> {
                val sig = "$contrastFlag"
                if (sig != entry.lastRenderSignature) {
                    entry.lastRenderSignature = sig
                    true
                } else false
            }
        }
    }

    private fun createSpatialContainer(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.TRANSPARENT)
        setPadding(28, 78, 28, 24)
    }

    private fun glassPillDrawable(accent: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 20f
        if (accent) {
            setColor(Color.parseColor("#D938BDF8"))
            setStroke(2, Color.parseColor("#BAE6FD"))
        } else {
            setColor(Color.parseColor("#851E2E4A"))
            setStroke(2, Color.parseColor("#597DD3FC"))
        }
    }

    private fun spatialButton(label: String, accent: Boolean = false, onClick: () -> Unit): Button =
        Button(context).apply {
            text = label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(Color.parseColor(if (accent) "#06101F" else "#F8FAFC"))
            background = glassPillDrawable(accent)
            setPadding(18, 12, 18, 12)
            setOnClickListener { onClick() }
        }

    private fun spatialText(msg: String, sizeSp: Float = 19f, colorHex: String = "#E2E8F0"): TextView =
        TextView(context).apply {
            text = msg
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(Color.parseColor(colorHex))
        }

    private fun buildAppLibraryView(): View {
        val root = createSpatialContainer()

        // Top Vision-style status strip
        val statusRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 10)
        }
        val statusPill = spatialText(
            "${timeFormat.format(Date())} · ${latestPerf.batteryPercent}% · AR:${if (latestSettings.passthroughEnabled) "ON" else "OFF"} · Cam:${latestSettings.cameraRotationDegrees}°",
            17f,
            "#7DD3FC"
        ).apply {
            tag = "launcher_status_pill"
        }
        val arBtn = spatialButton("◎ AR", accent = latestSettings.passthroughEnabled) {
            onActionCommand?.invoke("toggle_passthrough")
        }
        val rotCamBtn = spatialButton("↻ Cam") {
            onActionCommand?.invoke("rotate_camera_90")
        }
        val hubBtn = spatialButton("2D Hub", accent = true) {
            onActionCommand?.invoke("open_2d_hub")
        }
        statusRow.addView(statusPill, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        statusRow.addView(arBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = 8 })
        statusRow.addView(rotCamBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = 8 })
        statusRow.addView(hubBtn)
        root.addView(statusRow)

        // Spatial App Quick Launch Bar
        val quickRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 12)
        }
        val spatialShortcuts = listOf(
            "🌐 Web" to "open_browser",
            "▶ Cinema" to "open_media",
            "🖼 Photos" to "open_gallery",
            "◈ 3D Stage" to "open_gltf",
            "⚙ Optics" to "open_settings"
        )
        spatialShortcuts.forEach { (label, cmd) ->
            quickRow.addView(
                spatialButton(label) { onActionCommand?.invoke(cmd) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 6 }
            )
        }
        root.addView(quickRow)

        root.addView(spatialText("Android Phone Apps (VR Spatial Grid)", 18f, "#BAE6FD"))

        val scroll = ScrollView(context)
        val grid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = "launcher_apps_grid"
            setPadding(0, 8, 0, 8)
        }
        lastLauncherAppsSignature = latestApps.size
        populateLauncherGrid(grid)
        scroll.addView(grid)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun populateLauncherGrid(grid: LinearLayout) {
        grid.removeAllViews()
        val apps = latestApps.take(24)
        if (apps.isEmpty()) {
            grid.addView(spatialText("No external launcher apps discovered yet.", 18f))
            return
        }
        val columns = 3
        apps.chunked(columns).forEach { rowApps ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 6, 0, 6)
            }
            for (c in 0 until columns) {
                val app = rowApps.getOrNull(c)
                if (app == null) {
                    row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                    continue
                }
                val tile = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    background = glassPillDrawable(accent = app.packageName in latestSettings.favoriteApps)
                    setPadding(12, 14, 12, 14)
                    setOnClickListener { onLaunchAppRequested?.invoke(app) }
                }
                val iconView = ImageView(context).apply {
                    setImageDrawable(app.icon)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                val labelView = TextView(context).apply {
                    text = app.label
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setTextColor(Color.parseColor("#F8FAFC"))
                    setPadding(0, 8, 0, 0)
                }
                tile.addView(iconView, LinearLayout.LayoutParams(62, 62))
                tile.addView(labelView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                row.addView(tile, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = if (c < columns - 1) 10 else 0
                })
            }
            grid.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildBrowserView(): View {
        val root = createSpatialContainer()
        val navRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val urlText = spatialText(currentBrowserUrl, 18f, "#7DD3FC").apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        browserUrlLabel = urlText

        val backBtn = spatialButton("◀") {
            browserDirty = true
            browserWebView?.goBack()
        }
        val reloadBtn = spatialButton("↻") {
            browserLoading = true
            browserDirty = true
            browserWebView?.reload()
        }
        val typeBtn = spatialButton("URL / Keyboard", accent = true) {
            openKeyboardFor(currentBrowserUrl) { entered -> loadBrowserUrl(entered) }
        }
        navRow.addView(backBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = 6 })
        navRow.addView(reloadBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = 6 })
        navRow.addView(typeBtn)
        navRow.addView(urlText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = 12
        })
        root.addView(navRow)

        val webViewOrFallback: View = runCatching {
            WebView(context).apply {
                setBackgroundColor(Color.parseColor("#CC0A1222"))
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                @Suppress("DEPRECATION")
                settings.saveFormData = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    settings.safeBrowsingEnabled = true
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        browserDirty = true
                        if (newProgress >= 100) browserLoading = false
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        browserLoading = true
                        browserDirty = true
                    }

                    override fun onPageCommitVisible(view: WebView?, url: String?) {
                        browserDirty = true
                    }

                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val uri = request?.url ?: return false
                        if (latestSettings.browserHttpsOnly && uri.scheme == "http") {
                            loadBrowserUrl(uri.toString().replaceFirst("http://", "https://"))
                            return true
                        }
                        return false
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        browserLoading = false
                        browserDirty = true
                        url?.let {
                            currentBrowserUrl = it
                            urlText.text = it
                        }
                    }
                }
                browserLoading = true
                browserDirty = true
                loadUrl(currentBrowserUrl)
            }.also { browserWebView = it }
        }.getOrElse { err ->
            Log.e("VirtualContentHost", "WebView initialization failed — showing fallback notice", err)
            spatialText(
                "Spatial Browser unavailable on this device (Android System WebView disabled or updating). Other VR windows remain active.",
                18f,
                "#FDA4AF"
            )
        }
        root.addView(
            webViewOrFallback,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = 10 }
        )
        return root
    }

    private fun buildGalleryView(): View {
        val root = createSpatialContainer()
        root.addView(spatialText("Spatial Photo Gallery (Zero-Permission Picker)", 20f, "#7DD3FC"))

        val actionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 10, 0, 10)
        }
        val pickBtn = spatialButton("Pick Photos", accent = true) {
            onPickPhotoRequested?.invoke()
        }
        val rotateBtn = spatialButton("Rotate Photos (${latestSettings.galleryRotationDegrees}°)") {
            onSettingsMutated?.invoke { s ->
                s.copy(galleryRotationDegrees = (s.galleryRotationDegrees + 90) % 360)
            }
        }.apply {
            tag = "gallery_rotate_btn"
        }
        val pickVidBtn = spatialButton("Cinema Video") {
            onPickVideoRequested?.invoke()
        }
        actionsRow.addView(pickBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 8 })
        actionsRow.addView(rotateBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f).apply { marginEnd = 8 })
        actionsRow.addView(pickVidBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(actionsRow)

        val scroll = ScrollView(context)
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = "gallery_photo_col"
        }
        lastGalleryRenderSignature = "${latestGalleryUris.joinToString(",")}|${latestSettings.galleryRotationDegrees}"
        populateGalleryPhotos(col)
        scroll.addView(col)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun populateGalleryPhotos(col: LinearLayout) {
        col.removeAllViews()
        if (latestGalleryUris.isEmpty()) {
            col.addView(spatialText("No photos selected yet. Pick horizontal or vertical photos — tap Rotate Photos above to orient them cleanly in VR.", 19f))
            return
        }
        val rotDeg = latestSettings.galleryRotationDegrees.toFloat()
        latestGalleryUris.forEach { uri ->
            val iv = RotatablePhotoView(context, rotDeg).apply {
                setImageURI(uri)
            }
            col.addView(iv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 300).apply {
                bottomMargin = 12
            })
        }
    }

    private class RotatablePhotoView(context: Context, private val rotDeg: Float) : ImageView(context) {
        init {
            scaleType = ScaleType.FIT_CENTER
        }

        override fun onDraw(canvas: Canvas) {
            val d = drawable
            if (d == null || width == 0 || height == 0) {
                super.onDraw(canvas)
                return
            }
            val dw = d.intrinsicWidth.takeIf { it > 0 }?.toFloat() ?: width.toFloat()
            val dh = d.intrinsicHeight.takeIf { it > 0 }?.toFloat() ?: height.toFloat()
            val swap = (rotDeg.toInt() % 180) != 0
            val effW = if (swap) dh else dw
            val effH = if (swap) dw else dh
            val scale = minOf(width / effW, height / effH)

            canvas.save()
            canvas.translate(width * 0.5f, height * 0.5f)
            canvas.rotate(rotDeg)
            canvas.scale(scale, scale)
            d.setBounds((-dw * 0.5f).toInt(), (-dh * 0.5f).toInt(), (dw * 0.5f).toInt(), (dh * 0.5f).toInt())
            d.draw(canvas)
            canvas.restore()
        }
    }

    private fun buildGltfControlsView(): View {
        val root = createSpatialContainer()
        root.addView(spatialText("glTF 2.0 Spatial Stage", 21f, "#7DD3FC"))
        root.addView(spatialText("Active Model: $selectedGltfName (OpenGL ES 3.0)", 19f))
        listOf("Palindorme Gyro Prism", "Orbital Telemetry Ring", "Crystal Polyhedron").forEachIndexed { idx, name ->
            root.addView(
                spatialButton("Load $name", accent = idx == 0) {
                    selectedGltfName = name
                    onGltfPresetSelected?.invoke(idx)
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = 10
                }
            )
        }
        root.addView(
            spatialButton("Import .glb / .gltf File (SAF)", accent = true) {
                onPickGltfRequested?.invoke()
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 14
            }
        )
        return root
    }

    private fun buildQuickSettingsView(): View {
        val root = createSpatialContainer()
        val scroll = ScrollView(context)
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        col.addView(spatialText("Camera Rotation & AR Calibration", 20f, "#7DD3FC"))
        col.addView(
            spatialText(
                "Camera Rotation: ${latestSettings.cameraRotationDegrees}° · Mirror: ${if (latestSettings.cameraMirrorHorizontal) "ON" else "OFF"} · Grid: ${if (latestSettings.showCalibrationGrid) "ON" else "OFF"}",
                17f,
                "#BAE6FD"
            ).apply {
                tag = "quick_cam_status"
                setPadding(0, 4, 0, 8)
            }
        )

        val rotRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(0, 90, 180, 270).forEach { deg ->
            rotRow.addView(
                spatialButton("${deg}°", accent = latestSettings.cameraRotationDegrees == deg) {
                    onSettingsMutated?.invoke { it.copy(cameraRotationDegrees = deg) }
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 6 }
            )
        }
        col.addView(rotRow)

        val arRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 8)
        }
        arRow.addView(
            spatialButton("AR Passthrough", accent = latestSettings.passthroughEnabled) {
                onActionCommand?.invoke("toggle_passthrough")
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 6 }
        )
        arRow.addView(
            spatialButton("Peek AR", accent = latestSettings.passthroughEnabled && latestSettings.immersionLevel < 0.3f) {
                onActionCommand?.invoke("toggle_peek")
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f).apply { marginEnd = 6 }
        )
        arRow.addView(
            spatialButton("Mirror Cam", accent = latestSettings.cameraMirrorHorizontal) {
                onSettingsMutated?.invoke { it.copy(cameraMirrorHorizontal = !it.cameraMirrorHorizontal) }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.9f).apply { marginEnd = 6 }
        )
        arRow.addView(
            spatialButton("Calib Grid", accent = latestSettings.showCalibrationGrid) {
                onActionCommand?.invoke("toggle_calibration_grid")
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.9f)
        )
        col.addView(arRow)

        val cinemaRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 12)
        }
        cinemaRow.addView(
            spatialButton("Cinema Dim", accent = latestSettings.cinemaModeEnabled) {
                onActionCommand?.invoke("toggle_cinema")
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 6 }
        )
        cinemaRow.addView(
            spatialButton("3D: ${latestSettings.cinemaProjectionMode.label}") {
                onActionCommand?.invoke("cycle_cinema_proj")
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.1f).apply { marginEnd = 6 }
        )
        cinemaRow.addView(
            spatialButton("▶/⏸ Video") {
                onActionCommand?.invoke("media_play_pause")
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.9f)
        )
        col.addView(cinemaRow)

        col.addView(spatialText("Spatial Environments", 20f, "#7DD3FC"))
        VirtualEnvironmentType.entries.forEach { env ->
            col.addView(
                spatialButton(env.displayName, accent = env == latestSettings.environment.type) {
                    onSettingsMutated?.invoke { s ->
                        s.copy(environment = EnvironmentConfig(type = env, tintHex = env.defaultTintHex))
                    }
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = 6
                }
            )
        }

        col.addView(
            spatialButton("Open Full 2D Settings Hub", accent = true) {
                onActionCommand?.invoke("open_2d_hub")
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 12
            }
        )

        scroll.addView(col)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun buildDiagnosticsView(): View {
        val root = createSpatialContainer()
        root.addView(spatialText("Live Spatial Telemetry & Budgets", 21f, "#7DD3FC"))
        val tv = spatialText(buildDiagnosticsText(), 19f, "#F8FAFC").apply {
            tag = "diag_body"
            typeface = android.graphics.Typeface.MONOSPACE
        }
        root.addView(tv)
        return root
    }

    private fun buildDiagnosticsText(): String = buildString {
        appendLine("Device: ${latestDevice.deviceModel} · ${latestDevice.socInfo}")
        appendLine("GPU: ${latestPerf.glRendererString} · RAM: ${latestDevice.totalRamMb} MB")
        appendLine("FPS: ${"%.1f".format(latestPerf.fps)} (${"%.0f".format(latestPerf.activeRefreshRateHz)}Hz) Avg: ${"%.1f".format(latestPerf.frameTimeAvgMs)}ms P95: ${"%.1f".format(latestPerf.frameTimeP95Ms)}ms P99: ${"%.1f".format(latestPerf.frameTimeP99Ms)}ms Drop: ${latestPerf.droppedFrames}")
        appendLine("M2P Est: ${"%.1f".format(latestPerf.motionToPhotonEstMs)}ms  ADPF: ${if (latestPerf.adpfActive) "ON" else "OFF"}")
        appendLine("Thermal: ${latestPerf.thermalStatusLabel}  Tier: ${latestPerf.qualityTier.label}")
        appendLine("Cam: ${latestHand.activeCameraLabel} (${"%.0f".format(latestHand.activeCameraHfovDeg)}° HFOV)  Del: ${latestHand.delegateName}")
        appendLine("Cam Orient: sensor=${latestHand.camSensorOrientationDeg}° disp=${latestHand.camDisplayRotationDeg}° camRot=${latestHand.camRotationDegrees}° mirror=${latestHand.camMirror} userOff=${latestHand.camUserOffsetDeg}° -> final=${latestHand.camFinalRotationDeg}°")
        appendLine("Hand Infer Avg/P95: ${"%.1f".format(latestHand.inferenceAvgMs)}/${"%.1f".format(latestHand.inferenceP95Ms)}ms  E2E: ${"%.1f".format(latestHand.endToEndLatencyMs)}ms  Lum: ${"%.0f".format(latestHand.meanLuminance)}")
        appendLine("ARCore: ${latestAr.availability.label} | ${latestAr.trackingState} | Planes: ${latestAr.detectedPlanesCount}")
        appendLine("PC Link: ${latestPcLink.state.name}  RTT: ${"%.1f".format(latestPcLink.rttMs)}ms  Loss: ${"%.1f".format(latestPcLink.packetLossPercent)}%")
    }

    private fun buildHandLabSummaryText(): String = buildString {
        appendLine("Lens: ${latestHand.activeCameraLabel} (${"%.0f".format(latestHand.activeCameraHfovDeg)}° HFOV) · ${latestHand.delegateName}")
        appendLine("Cam Orient: sensor=${latestHand.camSensorOrientationDeg}° disp=${latestHand.camDisplayRotationDeg}° camRot=${latestHand.camRotationDegrees}° mirror=${latestHand.camMirror} userOff=${latestHand.camUserOffsetDeg}° -> final=${latestHand.camFinalRotationDeg}°")
        appendLine("Infer Avg/P95: ${"%.1f".format(latestHand.inferenceAvgMs)}/${"%.1f".format(latestHand.inferenceP95Ms)} ms · E2E: ${"%.1f".format(latestHand.endToEndLatencyMs)} ms · ${"%.0f".format(latestHand.effectiveFps)} FPS")
        val h0 = latestDebugHands.firstOrNull()
        if (h0 != null) {
            appendLine("Hand: ${h0.side.name} conf=${"%.2f".format(h0.confidence)} pinchRatio=${"%.2f".format(h0.normalizedPinchRatio())}${if (h0.isHeldDropout) " [HOLD]" else ""}")
        } else {
            appendLine("No hand landmarks · Luma: ${"%.0f".format(latestHand.meanLuminance)} (var ${"%.0f".format(latestHand.luminanceVariance)})")
        }
        append(latestHand.diagnosticAdvice)
    }

    private fun buildHandDebugLabWindowView(): View {
        val root = createSpatialContainer()
        val header = spatialText(buildHandLabSummaryText(), 17f, "#BAE6FD").apply {
            tag = "hand_lab_summary"
            typeface = android.graphics.Typeface.MONOSPACE
        }
        root.addView(header)

        val preview = HandOverlaySurfaceView(context).apply {
            tag = "hand_lab_canvas"
        }
        root.addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = 8
            bottomMargin = 8
        })

        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val traceBtn = spatialButton("Capture Trace (.jsonl)", accent = true) {
            onActionCommand?.invoke("toggle_trace_capture")
        }.apply { tag = "hand_lab_trace_btn" }
        val benchBtn = spatialButton("Re-Bench GPU/CPU") {
            onActionCommand?.invoke("rebenchmark_hand_delegate")
        }
        val hubBtn = spatialButton("Open 2D Lab") {
            onActionCommand?.invoke("open_2d_hand_lab")
        }
        row.addView(traceBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 6 })
        row.addView(benchBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 6 })
        row.addView(hubBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)
        return root
    }

    private class HandOverlaySurfaceView(context: Context) : View(context) {
        private var bmp: android.graphics.Bitmap? = null
        private var hands: List<HandFrame> = emptyList()
        private val dstRect = RectF()
        private val bonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#38BDF8")
            strokeWidth = 4f
            style = Paint.Style.STROKE
        }
        private val tipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#34D399")
            style = Paint.Style.FILL
        }
        private val pinchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FACC15")
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        private val zonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#6638BDF8")
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

        fun updateState(bitmap: android.graphics.Bitmap?, handList: List<HandFrame>, telemetry: HandTrackingTelemetry) {
            bmp = bitmap
            hands = handList
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.parseColor("#0A1324"))
            val vw = width.toFloat().coerceAtLeast(1f)
            val vh = height.toFloat().coerceAtLeast(1f)
            val b = bmp
            val fit = if (b != null && !b.isRecycled) {
                com.example.xr.hand.HandFrameTransform.computeAspectFitScale(
                    srcWidthPx = b.width,
                    srcHeightPx = b.height,
                    rotationDeg = 0,
                    dstWidth = vw,
                    dstHeight = vh
                )
            } else {
                com.example.xr.hand.HandFrameTransform.AspectFitScale(1f, 1f)
            }
            val drawW = vw * fit.scaleX
            val drawH = vh * fit.scaleY
            val left = (vw - drawW) * 0.5f
            val top = (vh - drawH) * 0.5f
            if (b != null && !b.isRecycled) {
                dstRect.set(left, top, left + drawW, top + drawH)
                canvas.drawBitmap(b, null, dstRect, null)
            }
            // Reach zone box
            canvas.drawRect(
                left + drawW * 0.12f,
                top + drawH * 0.12f,
                left + drawW * 0.88f,
                top + drawH * 0.88f,
                zonePaint
            )

            for (hand in hands) {
                if (hand.imageLandmarks.size < 21) continue
                for ((a, c) in HandLandmarkIndex.BONE_CONNECTIONS) {
                    val pa = hand.imageLandmarks[a]
                    val pb = hand.imageLandmarks[c]
                    canvas.drawLine(
                        left + pa.x * drawW,
                        top + pa.y * drawH,
                        left + pb.x * drawW,
                        top + pb.y * drawH,
                        bonePaint
                    )
                }
                for (i in 0 until 21) {
                    val p = hand.imageLandmarks[i]
                    val r = if (i == 4 || i == 8 || i == 12 || i == 16 || i == 20) 8f else 4f
                    canvas.drawCircle(left + p.x * drawW, top + p.y * drawH, r, tipPaint)
                }
                val pinchPt = hand.pinchContactPointImage()
                canvas.drawCircle(left + pinchPt.x * drawW, top + pinchPt.y * drawH, 18f, pinchPaint)
            }
        }
    }

    private fun buildControlCenterView(): View {
        val root = createSpatialContainer()
        root.addView(spatialText("Control Center", 21f, "#7DD3FC"))
        val scroll = ScrollView(context)
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val buttons = listOf(
            "Re-Center Head Pose" to "recenter",
            "Align Windows to Front Arc" to "recenter_windows",
            "Open Hand Debug Lab" to "open_hand_lab",
            "Open PC Companion Stream" to "open_pc_desktop",
            "Peek Real World (Immersion Toggle)" to "toggle_peek",
            "Toggle Cinema Dimming" to "toggle_cinema",
            "Cycle Cinema 2D / 3D / 180° / 360°" to "cycle_cinema_proj",
            "Play / Pause Cinema Media" to "media_play_pause",
            "Rotate Camera View (+90°)" to "rotate_camera_90",
            "Toggle Stereo Calibration Grid" to "toggle_calibration_grid",
            "Hands Control On / Off" to "toggle_hands",
            "Toggle ARCore 6-DOF" to "toggle_arcore",
            "Switch to 2D Settings Hub" to "open_2d_hub"
        )
        buttons.forEach { (label, cmd) ->
            col.addView(
                spatialButton(label, accent = cmd == "recenter") {
                    onActionCommand?.invoke(cmd)
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = 8
                }
            )
        }
        scroll.addView(col)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun buildInfoGuideView(): View {
        val root = createSpatialContainer()
        root.addView(spatialText("PALINDORME XR — Comfort & Honesty", 21f, "#7DD3FC"))
        root.addView(spatialText(
            "• Stationary seated use only when passthrough/camera is active.\n" +
            "• 3-DOF rotational tracking uses Game Rotation Vector (magnet-safe).\n" +
            "• 6-DOF positional tracking requires ARCore + unobstructed rear camera.\n" +
            "• Use Camera Rotation (0/90/180/270°) & Calibration Grid in Settings for exact lens fit.",
            19f
        ))
        val mediaTv = spatialText(currentMediaStatus, 18f, "#34D399").apply {
            tag = "info_media_status"
        }
        root.addView(mediaTv)
        root.addView(
            spatialButton("Play / Pause Media3 Cinema", accent = true) { toggleMediaPlayback() },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 12
            }
        )
        return root
    }
}
