# PALINDORME XR — Architecture & Honest 3-State Testing Report

## 1. Implemented Architecture (Stages 1, 2, and 3)
- **Stereo Rendering Pipeline (`StereoRenderingEngine`, `DistortionMesh`, `ShaderPrograms`, `EnvironmentRenderer`, `GltfModelLoader`)**:
  - OpenGL ES 3.0 dual-viewport pipeline rendering Left/Right offscreen eye FBOs (render scale `0.60–1.00`) followed by a pre-computed 40×40 Brown-Conrady distortion + RGB chromatic aberration mesh pass.
  - Six procedural GPU-light environments (`MINIMAL_LAB`, `SPACE_STATION`, `DIGITAL_WORKSPACE`, `DARK_ROOM`, `NATURE_DUSK`, `FLOATING_GRID`), including true-black OLED presets.
  - Built-in glTF 2.0 `.glb` / `.gltf` parser plus 3 procedural 3D meshes (`Palindorme Gyro Prism`, `Orbital Telemetry Ring`, `Crystal Polyhedron`).
- **Head Tracking & Math (`HeadTrackingEngine`, `SpatialMath`)**:
  - Dedicated sensor `HandlerThread` preferring `TYPE_GAME_ROTATION_VECTOR` (magnet-safe), with fallbacks to `TYPE_ROTATION_VECTOR`, Accelerometer+Magnetometer fusion, and touch/gamepad manual look.
  - One-Euro adaptive filtering, angular velocity estimation, motion-to-photon prediction (`0–45 ms`), yaw/horizon recenter, and optional ARCore 6-DOF override.
- **Spatial Windows & Content Host (`SpatialWindowManager`, `VirtualContentHost`, `SpatialKeyboardView`)**:
  - Spherical-arc floating windows (`APP_LIBRARY`, `BROWSER`, `MEDIA_PLAYER`, `GALLERY`, `GLTF_VIEWER`, `SETTINGS`, `DIAGNOSTICS`, `SPATIAL_KEYBOARD`, `CONTROL_CENTER`, `INFO`) with move, push/pull depth (`1.2–4.5 m`), resize, minimize, close, and max-active-window auto-suspend.
  - `SurfaceTexture`/`Surface` + `Surface.lockHardwareCanvas()` (with software fallback flag) for Android Views/WebView and direct `ExoPlayer.setVideoSurface(surface)` for Media3 playback.
- **Input & Hand Interaction (`InputInteractionManager`, `HandTrackingEngine`, `GestureRecognizer`, `HandRayInteractor`)**:
  - Unified head-gaze dwell (`0.5–2.0 s`), Bluetooth gamepad/DPAD, touch-drag/tap, opt-in voice commands (`SpeechRecognizer` on-device preferred on API 33+), and camera-based hand interaction (Look + Pinch tap on release, pinch-hold drag/scroll/push-pull, two-hand resize, open-palm Control Center hold, dropout/open-palm cancel).
- **Camera & Optional ARCore (`CameraModeCoordinator`, `SharedCameraFrameSource`, `ARPerceptionEngine`)**:
  - Serialized `NONE -> RELEASING -> ACQUIRING_* -> ACTIVE` coordinator with 2 s release timeout and forced-close recovery.
  - Context-gated `CAMERA` permission with stationary-use safety modal, Photo Picker gallery, targeted `<queries>` app discovery, and DataStore persistence (`XRSettingsManager`).

---

## 2. Honest 3-State Testing Matrix

| Feature / Subsystem | Status | Notes |
| :--- | :--- | :--- |
| APK Compilation (`compileDebugKotlin` / `assembleDebug`) | **Verified in build environment** | Clean Gradle build with AGP 9.1.1, Kotlin, Compose, CameraX, Media3, ARCore, MediaPipe, DataStore. |
| Quaternion prediction, ray-quad intersection, One-Euro filter | **Verified in unit tests** | Covered by `SpatialMathTest` (`JUnit`). |
| 40×40 Brown-Conrady distortion mesh & chromatic UVs | **Verified in unit tests** | Covered by `SpatialMathTest` (`JUnit`). |
| Spatial window placement, active limit, depth clamp & drag revert | **Verified in unit tests** | Covered by `SpatialMathTest` (`JUnit`). |
| Hand gesture state machine (pinch hysteresis, debounce, tap/drag, two-hand resize, palm menu, mirroring, 5-min false-positive replay) | **Verified in unit tests** | Covered by `GestureRecognizerTest` (`JUnit`). |
| `CameraModeCoordinator` 100 consecutive Mode A↔B switches | **Verified in Robolectric** | Covered by `CameraCoordinatorAndSettingsTest` (`Robolectric`). |
| DataStore `XRSettingsManager` persistence & `AppManager` query | **Verified in Robolectric** | Covered by `CameraCoordinatorAndSettingsTest` (`Robolectric`). |
| OpenGL ES 3.0 shaders, FBOs, `SurfaceTexture` rendering | **Not tested on physical GPU** | No emulator/ADB/GPU execution in build container; requires on-device verification. |
| IMU sensor rate, thermal throttling, ADPF, CameraX passthrough latency, ARCore 6-DOF occlusion fallback | **Not tested on physical device** | Requires physical phone matrix testing. |
| MediaPipe `.task` inference latency & live hand lighting checks | **Not tested on physical device** | `HandTrackingEngine` requires bundling `hand_landmarker.task` in `app/src/main/assets/` and testing on physical camera hardware; gracefully falls back to gaze dwell & gamepad when absent. |
