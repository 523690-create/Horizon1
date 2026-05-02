# Implementation Plan - Phase 1: Camera Overlay with Sensors and GPS

Implement a camera-based overlay with sensor-driven green lines (artificial horizon) and GPS-driven white crosshairs (true heading/horizon).

## User Review Required

> [!IMPORTANT]
> - **Line Width**: The prompt specifies green lines "approx 5% screen width". On a typical phone (1080px wide), this is ~54px. This is quite thick (more like bars). I will implement them as thick bars as requested.
> - **GPS "True" Vectors**: Constructing "true horizontal" and "true vertical" from GPS movement vector. I'll interpret this as the orientation relative to the velocity vector.
> - **Yellow Circle Transparency**: 89% transparent yellow circle. This means 11% opacity (`0.11f`).

## Proposed Changes

### [Permissions & Config]

#### [AndroidManifest.xml](file:///C:/AndroidStudioProjects/app/src/main/AndroidManifest.xml)
- Add `CAMERA`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` permissions.
- Declare `MainActivity`.

---

### [Core Logic]

#### [NEW] [SensorViewModel.kt](file:///C:/AndroidStudioProjects/app/src/main/java/com/example/horizon1/SensorViewModel.kt)
- Collect data from Accelerometer, Gyroscope, and Magnetometer.
- Implement Low-Pass Filter (LPF) for jitter reduction.
- Calculate device pitch and roll for the green "artificial horizon" lines.
- Calculate magnetic heading.

#### [NEW] [LocationViewModel.kt](file:///C:/AndroidStudioProjects/app/src/main/java/com/example/horizon1/LocationViewModel.kt)
- Collect GPS data using `FusedLocationProviderClient`.
- Provide "true bearing" based on movement.

---

### [UI Components]

#### [NEW] [MainActivity.kt](file:///C:/AndroidStudioProjects/app/src/main/java/com/example/horizon1/MainActivity.kt)
- Entry point, permission handling (using `ActivityResultContracts`).
- Root Compose UI.

#### [NEW] [CameraPreview.kt](file:///C:/AndroidStudioProjects/app/src/main/java/com/example/horizon1/ui/CameraPreview.kt)
- Implement CameraX `PreviewView` wrapper for Compose.

#### [NEW] [OverlayView.kt](file:///C:/AndroidStudioProjects/app/src/main/java/com/example/horizon1/ui/OverlayView.kt)
- Canvas-based drawing of all requested overlays:
    - 20% transparent black background.
    - 20% transparent green lines (Artificial Horizon).
    - 89% transparent yellow circle at POV center.
    - 20% transparent yellow magnetic heading text.
    - 90% opaque white crosshairs (True Heading).
    - 90% opaque white true bearing text.

## Verification Plan

### Automated Tests
- N/A for Phase 1 (primarily UI and sensor-driven). I can add unit tests for the LPF logic if needed.

### Manual Verification
1.  **Permission Flow**: Verify app requests Camera and Location on startup.
2.  **Camera Feed**: Verify live camera is visible behind the overlay.
3.  **Black Overlay**: Verify the 20% black tint is applied.
4.  **Green Lines**: Verify they tilt and move according to device rotation (Artificial Horizon).
5.  **Yellow Circle**: Verify it's at the center and highly transparent.
6.  **Magnetic Heading**: Verify the text (e.g., "ENE 67°") updates when rotating the device.
7.  **GPS Crosshairs**: Verify white crosshairs appear when moving (requiring GPS lock and movement).
8.  **True Bearing**: Verify white text shows the movement-based bearing.

#### Verification Commands
- `gradle_build(":app:assembleDebug")` to ensure everything compiles.
- `deploy(ANDROID_APP, "app", "MainActivity", RUN)` to run on device/emulator.
