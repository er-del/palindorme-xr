# PALINDORME XR — Honest 3-State Testing Report

Every verification claim below uses exactly one of three verified states:
- **Verified in unit / Robolectric tests**: executed directly via `gradle :app:testDebugUnitTest`.
- **Verified in build environment**: compiled cleanly via `compile_applet` / Gradle.
- **Not tested on physical device**: requires physical Android GPU, IMU, camera, or headset optics (no emulator or ADB exists in the cloud build container).

## 3-State Verification Matrix

| Subsystem / Requirement | Verification State | Evidence & Test File |
| :--- | :--- | :--- |
| APK Compilation (`compileDebugKotlin` / `assembleDebug`) | **Verified in build environment** | Clean Gradle build with bundled `assets/hand_landmarker.task`, `noCompress`, AGP, MediaPipe, CameraX, Media3, ARCore, DataStore. |
| Quaternion angular prediction, ray-quad intersection, One-Euro jitter dampening | **Verified in unit tests** | `SpatialMathTest` (`JUnit`). |
| 40×40 Brown-Conrady barrel pre-distortion (`1 - k1 * r²`) & R/G/B chromatic separation | **Verified in unit tests** | `SpatialMathTest` (`JUnit`). |
| Target magnetism, `GRABBER_BAR` / button classification, max active window limit, depth clamp & drag revert | **Verified in unit tests** | `SpatialMathTest` (`JUnit`). |
| `HandRayInteractor` camera-to-world pointer ray translation & direct near-field poke proximity | **Verified in unit tests** | `SpatialMathTest` (`JUnit`). |
| Hand gesture state machine (tap on release, pinch drag, open-palm cancel, two-hand resize, two-finger scroll, mirroring) | **Verified in unit tests** | `GestureRecognizerTest` (`JUnit`). |
| 100 scripted Look + Pinch tap trials (>= 95% accuracy) & 5-minute (9,000-frame) false-pinch stress replay | **Verified in unit tests** | `GestureRecognizerTest` (`JUnit`). |
| `CameraModeCoordinator` 100 consecutive Mode A ↔ Mode B switches | **Verified in Robolectric** | `CameraCoordinatorAndSettingsTest` (`Robolectric`). |
| `XRSettingsManager` DataStore persistence (Optics, Immersion Dial, Cinema 3D, Camera/Gallery rotation, Hand Ray default) | **Verified in Robolectric** | `CameraCoordinatorAndSettingsTest` (`Robolectric`). |
| Bundled `hand_landmarker.task` asset integrity readable from APK `AssetManager` | **Verified in Robolectric** | `CameraCoordinatorAndSettingsTest` (`Robolectric`). |
| Compose UI & Launcher MainActivity smoke verification | **Verified in Robolectric** | `ExampleRobolectricTest` (`Robolectric`). |
| OpenGL ES 3.0 shaders (rotational timewarp `mat3`, frosted glass, Cinema SBS/180, immersion feathering) | **Not tested on physical device** | Compiled GLSL/Kotlin; requires physical phone GPU for visual & FPS verification. |
| Live IMU sample rate, thermal throttling, ADPF `PerformanceHintManager`, Android 13+ `Spatializer` head tracker | **Not tested on physical device** | Requires physical device sensors and headset/headphones. |
| MediaPipe live camera hand tracking FPS/latency, CameraX passthrough rotation, & ARCore 6-DOF occlusion fallback | **Not tested on physical device** | Requires physical camera hardware inside passive VR holder. |
