# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
./gradlew assembleDebug          # Build debug APK
./gradlew assembleRelease        # Build release APK
./gradlew installDebug           # Build and install on connected device
./gradlew test                   # Run unit tests
./gradlew connectedAndroidTest   # Run instrumented tests on device/emulator
./gradlew clean                  # Clean build artifacts
```

Run a single test class:
```bash
./gradlew test --tests "com.toddmargolis.astroandroidapp.ExampleUnitTest"
```

## Architecture

AstroTimeDelay is an astronomy observation app that overlays celestial body images on time-delayed camera video.

**Core Data Flow:**
1. CameraX captures frames via ImageAnalysis (RGBA_8888 format, front camera)
2. FrameBuffer queues frames with timestamps, maintaining a delay window
3. OverlayRenderer composites the delayed frame with color tint and overlay image
4. Result displays in fullscreen ImageView

**Key Classes:**
- `MainActivity` - Mode selection screen (Moon/Sun/Saturn), handles camera permissions
- `CameraActivity` - Camera pipeline setup, frame processing loop, fullscreen display
- `CelestialMode` - Configuration model with factory methods for each mode (delay, resolution, fps, tint, overlay resource)
- `FrameBuffer` - ArrayDeque-based frame queue with automatic purging, converts ImageProxy to Bitmap
- `OverlayRenderer` - Canvas-based compositing with color tinting and overlay positioning

**Mode Configurations:**
| Mode | Delay | Resolution | Overlay Position |
|------|-------|------------|------------------|
| Moon | 1.3s | 1920x1080 | Centered |
| Sun | 8m 20s | 1920x1080 | Bottom |
| Saturn | 75m | 1280x720 | Centered |

## Project Structure

- Single module project (`:app`)
- Source: `app/src/main/java/com/toddmargolis/astroandroidapp/`
- Resources: `app/src/main/res/`
- Version catalog: `gradle/libs.versions.toml`
- Java 11, minSdk 26, targetSdk 36

## Key Dependencies

- CameraX 1.3.1 (camera-camera2, camera-lifecycle, camera-view)
- Material Design 1.10.0
- JUnit 4.13.2 / Espresso 3.5.1 for testing
