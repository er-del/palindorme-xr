# PALINDORME XR — Technical Feasibility & Honest Limits

## 1. Platform Reality vs. Dedicated XR Headsets
Dedicated XR headsets (Apple Vision Pro, Samsung Galaxy XR) ship with dual outward-facing stereo passthrough cameras, infrared eye-tracking cameras, multi-camera SLAM ASICs, and 90–120 Hz micro-OLED panels. **An ordinary Android smartphone placed inside a passive VR headset does not have those sensors.**

PALINDORME XR explicitly avoids parity claims and enforces honesty in both its UI and documentation:

| Capability | Dedicated XR Headset | Ordinary Phone in Passive VR Holder | How PALINDORME XR Handles It |
| :--- | :--- | :--- | :--- |
| **Eye Tracking** | Per-eye IR gaze tracking | Unavailable | Uses head-gaze dwell (`0.5–2.0 s`), Bluetooth gamepad, or MediaPipe Hand-Ray pointer. |
| **Passthrough** | Low-latency dual-eye stereo | Single rear monocular camera (~20–40 ms) | Experimental CameraX/ARCore monocular passthrough converged at 2 m with distortion compensation, rotation controls (`0/90/180/270°`), and stationary-use safety gate. |
| **Hand Tracking** | Wide-FOV stereo IR/depth cameras | Narrow rear RGB camera (~55–70° HFOV) | Bundled MediaPipe `hand_landmarker.task` with GPU/CPU fallback, target magnetism, 60 s fatigue rest hint, and automatic gaze dwell fallback when hands exit FOV. |
| **Head Tracking** | Multi-camera 6-DOF VIO | 3-DOF IMU (`TYPE_GAME_ROTATION_VECTOR`) | Magnet-safe 3-DOF rotational tracking by default + late-pose rotational timewarp. Experimental ARCore 6-DOF auto-falls back to 3-DOF when occluded. |
| **Display & Optics** | Dual micro-OLED pancake lenses | Single phone LCD/OLED behind biconvex lenses | DPI-scaled stereo viewports + 40×40 Brown-Conrady pre-distortion + RGB chromatic aberration compensation + in-headset alignment calibration grid. |

## 2. Privacy & Play Policy Guarantees
- Camera frames stay strictly in RAM (`ImageAnalysis` / GL `SurfaceTexture`) and are never written to disk or transmitted.
- An always-visible HUD badge indicates when the camera, passthrough, or ARCore is active.
- Zero broad permissions: uses targeted `<queries>` for launcher discovery and Android Photo Picker (`PickVisualMedia`) / SAF for photos, videos, and `.glb`/`.gltf` files.
- No analytics SDKs, no ad SDKs, and no external XR SDK lock-in.
