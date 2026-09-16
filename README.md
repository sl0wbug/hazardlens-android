# HazardLens 🔍⚠️

HazardLens is an Android-based intelligent hazard detection and safety awareness application developed with Kotlin.

## Overview
HazardLens utilizes mobile camera and vision capabilities to scan, identify, and alert users to potential environmental, workplace, or infrastructural hazards in real time.

## Tech Stack
- **Platform:** Android
- **Language:** Kotlin
- **Minimum SDK:** API 26 (Android 8.0)
- **Target SDK:** API 36 (Android 16)
- **Build System:** Gradle (Kotlin DSL) with Version Catalogs (`libs.versions.toml`)

## Project Structure
```text
HazardLens/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/droidlinkstd/hazardlens/   # Source code
│   │   │   └── res/                                # Layouts, values, and assets
│   └── build.gradle.kts                            # App-level build config
├── gradle/
│   └── libs.versions.toml                          # Dependency version catalog
└── settings.gradle.kts                             # Gradle project settings
```

## Getting Started
1. Clone the repository:
   ```bash
   git clone https://github.com/sl0wbug/hazardlens-android.git
   ```
2. Open the project in Android Studio (Ladybug or newer recommended).
3. Let Gradle sync and build the project.
4. Run on an Android device or emulator running API 26+.
