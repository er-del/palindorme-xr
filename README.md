# PALINDORME XR

Stereoscopic OpenGL ES 3.0 spatial launcher & XR workspace for Android smartphones used inside passive VR headsets.

## Highlights
- **Custom OpenGL ES 3.0 Stereo Pipeline**: Dual eye FBOs, 40×40 Brown-Conrady barrel pre-distortion + R/G/B chromatic aberration mesh, DPI-scaled optics presets, edge vignette, and late-pose rotational timewarp (`uTimewarpMat`).
- **World-Locked Frosted Glass Windows**: Spatial Phone Launcher (3-column Android app grid), Spatial Browser (`WebView`), Media3 Cinema (`2D`, `3D Side-by-Side`, `180° Wide` + Android `Spatializer` status), Photo Gallery (`PickVisualMedia` + rotation), glTF 2.0 3D Stage, Quick Optics, Diagnostics, Control Center, and Spatial Keyboard.
- **Hand Control & Gaze Interaction**: Bundled on-device MediaPipe `hand_landmarker.task` with Hand-Ray pointer visualization, direct near-field poke, target magnetism, pinch tap/drag/depth/inertia-scroll, two-hand resize, open-palm Control Center hold, 60 s rest hint, automatic gaze dwell fallback, and smart center reticle.
- **Immersion Dial & Monocular Passthrough**: Feathered transition from full CameraX/ARCore passthrough (`0%`) to any of the 6 procedural GPU-light virtual environments (`100%`), plus one-tap **Peek AR** and view rotation (`0/90/180/270°` + mirror).

## Android XR (Galaxy XR)

The app runs on Android XR headsets as a resizable spatial panel — no Jetpack XR
rewrite required. Two behaviours adapt automatically via
`com.example.xr.XrEnvironment`:

| Behaviour | Phone | Android XR headset |
|---|---|---|
| Start mode | `STEREO_VR` (phone-in-a-viewer) | `HUB_2D` (spatial panel) |
| Orientation | locked sensor-landscape | free / resizable |
| Sensor head tracking | on | off (the platform owns head pose) |

The manifest declares `android.hardware.touchscreen` and `faketouch` as **not
required**, which is mandatory for Play installability on headsets.

## PC Companion (offload hand tracking)

`pc-companion/` contains a Python server that receives the phone's camera as
encrypted H.264, runs MediaPipe Hands on the PC, and streams the 21 landmarks
back. Enable it in the app under **PC Companion**. See
[pc-companion/README.md](pc-companion/README.md) for setup, pairing, and the
wired-vs-wireless details (`adb reverse` is TCP-only, so use Wi-Fi or USB
tethering for the camera stream).

## Build & Test Commands
Run unit & Robolectric tests on JVM:
```bash
gradle :app:testDebugUnitTest
```

Build debug APK:
```bash
gradle :app:assembleDebug
```

Sideload to a connected physical Android phone (`minSdk 24`, `targetSdk 36`):
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Documentation
- [Technical Feasibility & Honest Limits](docs/FEASIBILITY.md)
- [System Architecture](docs/ARCHITECTURE.md)
- [Honest 3-State Testing Report](docs/HONEST_TESTING.md)
