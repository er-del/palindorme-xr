# PALINDORME XR — System Architecture

## Module Layout (`com.example.xr.*`)

```
app/src/main/java/com/example/
├── MainActivity.kt                  # Landscape edge-to-edge stereo/2D host, PickVisualMedia, SAF, permissions, gamepad
├── ui/theme/
│   ├── Color.kt / Type.kt / Theme.kt # "Atmospheric Spatial Obsidian" Material 3 theme + local Space Grotesk, Plus Jakarta Sans, JetBrains Mono
└── xr/
    ├── XRLauncherCoordinator.kt     # ViewModel coordinating stereo/2D mode, engines, DataStore, AudioSpatializer, thermal hand scaling
    ├── model/                       # OpticsProfile, EnvironmentConfig, XRSettings (Immersion Dial, Cinema Mode, Hand Ray)
    ├── settings/XRSettingsManager.kt# DataStore Preferences persistence & schema migration
    ├── tracking/
    │   ├── SpatialMath.kt           # Vec3, Quat prediction, Ray-Quad intersection, One-Euro adaptive filter
    │   └── HeadTrackingEngine.kt    # Game Rotation Vector / Rotation Vector / Accel+Mag IMU thread + recenter + prediction
    ├── gl/
    │   ├── DistortionMesh.kt        # 40x40 Brown-Conrady barrel pre-distortion + R/G/B chromatic UV offsets + edge vignette
    │   ├── ShaderPrograms.kt        # Rotational timewarp mat3, feathered immersion passthrough, frosted glass blur, Cinema SBS/180 shaders
    │   ├── EnvironmentRenderer.kt   # 6 procedural GPU-light environments + Cinema Mode dimming
    │   ├── GltfModelLoader.kt       # Built-in glTF 2.0 / GLB mesh loader + 3 procedural 3D meshes
    │   └── StereoRenderingEngine.kt # Dual FBO renderer, late-pose rotational timewarp, spring window hover, grabber bar, live hand ray, smart reticle
    ├── windows/
    │   ├── SpatialWindowModels.kt   # Spherical-arc window states & hit regions (incl. GRABBER_BAR)
    │   ├── SpatialWindowManager.kt  # World-locked arc windows, max active limit suspend, target magnetism, recenter
    │   ├── VirtualContentHost.kt    # Hardware-Canvas View bitmaps (App Library, WebView, Gallery, Quick Settings, Diagnostics, Control Center) & Media3 ExoPlayer OES
    │   └── SpatialKeyboardView.kt   # Floating QWERTY + voice dictation panel
    ├── hand/
    │   ├── HandModels.kt            # 21-landmark HandFrame, pinch ratio, open-palm & two-finger scroll detectors
    │   ├── HandTrackingEngine.kt    # Bundled MediaPipe hand_landmarker.task (GPU/CPU), user rotation/mirror matrix, One-Euro smoothing, 60s rest hint
    │   ├── GestureRecognizer.kt     # Pinch hysteresis, 3-frame debounce, tap vs hold-drag, push/pull depth, two-hand resize, palm Control Center
    │   └── HandRayInteractor.kt     # Hand-Ray world projection & near-field direct poke (~4.5 cm)
    ├── input/InputInteractionManager.kt # Raycasting, direct poke, grabber/title drag, flick-scroll inertia, gaze dwell, gamepad/DPAD, audio/haptics
    ├── perception/
    │   ├── CameraModeCoordinator.kt # Mutual exclusion state machine (CameraX Shared vs ARCore Mode B) with 2s timeout
    │   ├── SharedCameraFrameSource.kt # CameraX single owner for passthrough OES Preview & rotated HandLandmarker ImageAnalysis
    │   └── ARPerceptionEngine.kt    # Optional ARCore planes/HDR light/depth/6-DOF with auto 3-DOF fallback & CPU luma frames for hands
    ├── apps/                        # Targeted <queries> Android launcher discovery, pinned/recents, SpeechRecognizer voice commands
    ├── system/                      # PerformanceMonitor (ADPF + 60-frame history + thermal tiers), ComfortManager, AudioSpatializerHelper
    └── ui/                          # StereoViewportScreen, Hub2DScreen (with frame-time graph & immersion dial), HubDialogs
```

## Key Dataflows
1. **Rendering & Timewarp**: `onDrawFrame` samples `HeadTrackingEngine.getPredictedPose()`, executes interaction raycast using live `handTracking.getLatestHands()`, draws Left/Right eye offscreen FBOs (`renderScale * thermalMultiplier`), re-samples `latePose`, computes `uTimewarpMat` rotational delta, and executes the Brown-Conrady distortion pass to `framebuffer 0`.
2. **MediaPipe Hand Pointer**: CameraX `ImageAnalysis` (or ARCore `acquireCameraImage()` in Mode B) passes frames to `HandTrackingEngine` on `HandTrackingThread` -> transforms by `(sensorRotation + cameraRotationDegrees)` & `cameraMirrorHorizontal` -> MediaPipe `HandLandmarker` (`assets/hand_landmarker.task`) -> per-landmark One-Euro filters -> `GestureRecognizer` + `HandRayInteractor` -> zero-delay sync into `StereoRenderingEngine` & `InputInteractionManager`.
