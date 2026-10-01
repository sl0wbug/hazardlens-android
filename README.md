# HazardLens 🔍⚠️

**HazardLens** is an on-device Android intelligent road hazard detection and driver awareness application developed in Kotlin. It is specifically tailored for navigating challenging road infrastructure and defect environments (including local Bangladeshi road conditions: potholes, unmarked speed breakers, broken manholes, and severe pavement fractures).

---

## 📌 Project Overview & Purpose

Road hazards cause critical vehicular damage, accidents, and travel delays, especially in regions with unpredictable infrastructure. **HazardLens** transforms any Android smartphone into an intelligent **Road Safety HUD (Head-Up Display)** and inspection tool.

The app is built to operate with **100% offline edge intelligence**, guaranteeing immediate proximity warnings without requiring active cellular internet on remote highways.

---

## 🚀 Key Features & UI Layout

HazardLens provides a cockpit HUD interface divided into modular functional areas:

### 1. Multi-Source Ingestion Modes
* **Live Camera Mode:** Uses Android Jetpack **CameraX** for continuous, low-latency road scanning from a windshield car mount.
* **Import Image Mode:** Uses Android's modern **PhotoPicker** and **Coil** for analyzing single high-resolution road defect photos.
* **Import Video Mode:** Uses **AndroidX Media3 (ExoPlayer)** for sequential frame playback and dashcam footage inspection.

### 2. Cockpit HUD & Visual Reticle Overlay
* **Custom Canvas Overlay (`BoundingBoxOverlay`):** Real-time projection of normalized detection bounding boxes `(0.0 to 1.0)` onto screen coordinates.
* **Severity-Based Color Coding:**
  * 🔴 **Danger Red (`#FF1744`):** Potholes, open manholes, and critical road pits.
  * 🟡 **Warning Amber (`#FFAB00`):** Unmarked speed breakers and asphalt humps.
  * 🟠 **Safety Orange (`#FF3D00`):** Surface fractures and general road defects.
* **HUD Targeting Guides:** Subtle center reticle crosshairs and corner brackets for target alignment.
* **Floating Warning Alert Banner:** Direct on-screen indicator triggered upon threat detection.

### 3. Integrated HUD Quick Controls
* **Night Flashlight / Torch Toggle:** One-tap flashlight activation linked directly to `CameraControl.enableTorch()` for nighttime road inspections.
* **Quick Media Switcher:** Swap test images or dashcam videos directly from the viewport without leaving the screen.
* **System Specs & Architecture Dialog:** In-app specifications modal detailing the pipeline and target classes.

### 4. Telemetry & Sensitivity Control Deck
* **Live Status Indicator:** Real-time state tracking (Idle, Camera Active, Image Loaded, Video Playing).
* **Latency & Threat Counter:** Live latency benchmarking in milliseconds (`ms`) and detected hazard tally.
* **Sensitivity Threshold Chips:** Interactive confidence filters:
  * `Recall (30%)`: High sensitivity for spotting all potential anomalies.
  * `Balanced (50%)`: Optimal trade-off between precision and recall.
  * `Strict (70%)`: High confidence, suppressing false positives.

---

## 📋 Formal System Requirements (SRS)

### Functional Requirements (FR)
| ID | Title | Description |
| :--- | :--- | :--- |
| **FR-1** | **Multi-Input Feed** | Ingest video streams from CameraX, local storage images, and pre-recorded dashcam MP4 video. |
| **FR-2** | **Hazard Classification** | Classify detected defects into target categories: `Pothole`, `Speed Breaker`, `Open Manhole`, and `Road Crack`. |
| **FR-3** | **HUD Coordinate Projection** | Transform normalized coordinates to device screen coordinates with dynamic bounding boxes and confidence badges. |
| **FR-4** | **Dual Warning Dispatcher** | Trigger visual banners, audio alerts, and haptic feedback when hazards exceed confidence thresholds. |
| **FR-5** | **Offline Standalone Execution** | Perform all inference on-device without cloud network roundtrips. |
| **FR-6** | **Telemetry Logging** | Monitor and display frame processing latency and threat statistics. |

### Non-Functional Requirements (NFR)
* **Real-time Performance:** 15–30 FPS on mid-range Android devices (e.g. Snapdragon 680, Helio G99, Exynos 1280).
* **Latency SLA:** Target end-to-end inference latency of **< 45–60 ms per frame**.
* **Model Footprint:** Model weight file **< 25 MB**; application RAM utilization **< 250 MB**.
* **Thermal Efficiency:** Sustained operation without triggering device thermal throttling over continuous 45-minute drives.
* **Compatibility:** Minimum SDK: API 26 (Android 8.0) | Target SDK: API 36 (Android 16).

---

## 🧠 Model Implementation & Technical Methodology

### Method Selection: On-Device Edge Inference (TFLite / ONNX)
* **Why NOT Cloud Inference?**
  * *Latency Risk:* A round-trip cloud API request takes 300–800 ms. At highway speeds (54 km/h / 15 m/s), an 800 ms delay means moving **12 meters** before receiving a warning, causing the vehicle to hit the hazard before alerting.
  * *Connectivity Issues:* Highway dead zones in rural regions cause detection failure.
  * *Bandwidth Costs:* Continuous high-frame-rate video streaming is impractical over mobile data.
* **Why On-Device YOLO via TensorFlow Lite?**
  * Deterministic low-latency inference (< 45 ms).
  * 100% offline reliability.
  * Hardware acceleration via **Android NNAPI** or **GPU Delegate**.

### Vision Pipeline Architecture
```
┌─────────────────┐       ┌────────────────────┐       ┌────────────────────────┐
│ CameraX Frame   │  ──▶  │ Letterbox Pre-proc │  ──▶  │ TFLite Interpreter     │
│ (YUV_420 / RGBA)│       │ (Resize to 640x640)│       │ (NNAPI / GPU Delegate) │
└─────────────────┘       └────────────────────┘       └───────────┬────────────┘
                                                                   │
┌─────────────────┐       ┌────────────────────┐                   ▼
│ BoundingBox HUD │  ◀──  │ NMS Postprocessing │  ◀──  ┌────────────────────────┐
│ & Alert Trigger │       │ (IoU > 0.45, > 50%)│       │ Raw Output Tensors     │
└─────────────────┘       └────────────────────┘       │ [1, 84, 8400]          │
                                                       └────────────────────────┘
```

1. **Preprocessing:** Convert `ImageProxy` to Bitmap, normalize orientation, and letterbox-resize to 640×640 with normalized float values `[0.0, 1.0]`.
2. **Inference:** Execute via `TFLite Interpreter` with FP16/INT8 quantized weights for maximum throughput.
3. **Postprocessing:** Decode candidate boxes, filter by class confidence, apply Non-Maximum Suppression (NMS), and push the `List<Detection>` to `BoundingBoxOverlay`.
4. **Active Warning:** Fire haptic pulse (`Vibrator`) and audio chime when a hazard is within proximity.

---

## 🛠️ Tech Stack & Dependencies

* **Language:** Kotlin
* **Architecture:** Single Activity with ViewBinding & Material Design 3
* **Camera Engine:** Android Jetpack CameraX (`camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`)
* **Media Playback:** AndroidX Media3 ExoPlayer (`media3-exoplayer`, `media3-ui`)
* **Image Loading:** Coil (`io.coil-kt:coil`)
* **Design System:** Material Components for Android (`com.google.android.material:material:1.10.0`)
* **Build System:** Gradle Kotlin DSL with Version Catalogs (`libs.versions.toml`)

---

## 📂 Project Structure

```text
HazardLens/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/droidlinkstd/hazardlens/
│   │   │   │   ├── MainActivity.kt               # Central coordinator & mode controller
│   │   │   │   ├── data/
│   │   │   │   │   └── Detection.kt              # Hazard bounding box & confidence data model
│   │   │   │   └── ui/
│   │   │   │       └── overlay/
│   │   │   │           └── BoundingBoxOverlay.kt # Custom HUD canvas with reticle & badges
│   │   │   └── res/
│   │   │       ├── drawable/                     # HUD vectors, alerts & shapes
│   │   │       ├── layout/
│   │   │       │   └── activity_main.xml         # Viewport, HUD action bar & telemetry card
│   │   │       └── values/                       # Color system, strings & theme tokens
│   │   └── build.gradle.kts                      # Module dependencies & build config
├── gradle/
│   └── libs.versions.toml                        # Dependency version catalog
└── README.md                                     # System documentation & roadmap
```

---

## 🚦 Getting Started

1. **Clone the repository:**
   ```bash
   git clone https://github.com/sl0wbug/hazardlens-android.git
   ```
2. **Open in Android Studio** (Ladybug or newer recommended).
3. **Sync Gradle** and build the project:
   ```bash
   ./gradlew assembleDebug
   ```
4. **Deploy and Run** on any Android device or emulator running API 26 or higher.
