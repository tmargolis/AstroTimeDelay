# Cosmic Latency — Time-Delay Video Mirrors

*An installation by Todd Margolis*

---

Imagine seeing yourself not as you are now, but as you were moments, or even an hour, ago. This series of video mirrors forces a visceral encounter with a profound truth of our universe: because light travels at a finite speed, all observation is an act of looking into the past. By introducing a precise, scientifically-calibrated delay into your reflection, each mirror makes this cosmic latency immediate and deeply personal.

The work dismantles the illusion of a shared, instantaneous present, rendering the self in the mirror perpetually out of reach — a version of you that has already vanished. The experience scales dramatically, moving from the subtly disorienting to the profoundly estranged. The mirrors cease to be mere surfaces for self-recognition and transform into portals, translating abstract astronomical data into a concrete, lived experience.

This work is a potent *memento mori*, a reminder that even in our most immediate self-perception, we are always confronting a ghost. It is the culmination of decades of artistic practice by Todd Margolis, who has consistently explored the intersection of perception, technology, and science. His extensive career in virtual and augmented reality — from co-inventing VR systems at the Electronic Visualization Lab to creating telepresence performances — has always questioned the nature of presence and the delays inherent in mediated communication. This series is a direct extension of that inquiry, grounding the technological manipulation of time not in artistic whim, but in the physical laws of the universe.

This work finds a powerful precedent in Margolis's 2012 augmented reality piece *Moon Lust*, exhibited at the Adler Planetarium, which first combined celestial concepts with technologies of altered perception. By turning a core principle of observational astronomy into a deeply personal, philosophical, and unforgettable encounter, the work transforms the gallery into an observatory that looks inward to reveal the most fundamental truths of our place in the cosmos.

---

## The Three Mirrors

### The Moon — 1.3 second delay

The "Moon" mirror confronts you with a 1.3-second delay, a slight, uncanny hiccup in reality. This subtle temporal disjunction makes perceptible the distance between Earth and our nearest celestial neighbor, transforming a familiar reflection into something slightly, unsettlingly out of sync.

**Light travel time:** Earth → Moon ≈ 1.3 seconds (384,400 km at 299,792 km/s)

### The Sun — 8 minutes 20 seconds delay

In the "Sun" mirror, an 8-minute and 20-second gap separates you from your reflection, translating the immense scale of our solar system into a tangible temporal disjunction. The delay represents the time it takes for light to travel from the Sun to Earth, making the vastness of space immediately, personally felt.

**Light travel time:** Sun → Earth ≈ 8 minutes 20 seconds (149.6 million km at 299,792 km/s)

### Saturn — 79 minute delay

The "Saturn" mirror introduces a delay of nearly an hour and a half; the reflected self is so temporally removed it feels like another person, a phantom of your own recent history.

**Light travel time:** Saturn → Earth ≈ 79 minutes at mean opposition distance (~1.28 billion km at 299,792 km/s)

---

## Technical Overview

The app runs on a Samsung Galaxy Tab A9+ (Android 13+) mounted as a full-screen video mirror. The front-facing camera captures live video; frames are stored in a precisely-timed buffer and played back after the scientifically-calibrated delay. A celestial body overlay is composited onto the delayed video, and a ghost-stack mini-view shows all buffered frames accumulated over time.

### Core Data Flow

```
Camera (CameraX)
    └── ImageAnalysis (RGBA_8888, front camera)
            └── FrameBuffer.addFrame()
                    ├── Store frame (RAM or JPEG on disk via MediaStore)
                    ├── Return thumbnail → EMA ghost-stack composite
                    └── getDelayedFrame() → oldest frame in buffer
                            └── OverlayRenderer.renderFrame()
                                    └── displayView (fullscreen ImageView)
```

### Frame Buffering

Each mode uses a different storage strategy determined by buffer size:

| Mode | Delay | Buffer size | Storage | Resolution |
|------|-------|-------------|---------|------------|
| Moon | 1.3 s | ~40 frames | RAM (`ArrayDeque<Bitmap>`) | 1920×1080 |
| Sun | 8m 20s | ~7,501 frames | JPEG on disk (MediaStore) | 1920×1080 |
| Saturn | 79 min | ~4,741 frames | JPEG on disk (MediaStore) | 1280×720 |

**Moon** keeps all frames in RAM as raw Bitmaps. **Sun** and **Saturn** compress each frame to JPEG (60% quality) and write to `Pictures/AstroTimeDelay/` via Android's `MediaStore` API — making sessions browsable in the device's native file manager. A parallel timestamp buffer tracks the exact capture time of each stored frame; this is displayed as `MM/DD/YYYY HH:MM:SS.MLS` during playback.

Frame skipping (`frameSkip = targetFps / bufferFps`) ensures the buffer fills at the right rate regardless of camera output rate. MediaStore I/O is guarded by a cache (`cachedFrame` / `cachedUri`) so the same JPEG is only decoded once per buffer head advance rather than at the full camera frame rate.

### Celestial Overlay

`OverlayRenderer` composites a celestial body image onto each delayed frame using Android `Canvas`:

1. Draw the delayed video frame with a color tint (`PorterDuff.MULTIPLY`)
2. Scale and position the overlay image:
   - **Moon**: centered, scaled to 50% of frame-fit size
   - **Sun**: horizontally centered, top-aligned to the frame edge
   - **Saturn**: native pixel size (512×512), top-left corner

### Ghost-Stack Mini-View (EMA Composite)

A small preview (25% of screen width, 16:9) in the bottom-right corner shows all buffered frames accumulated into a single ghost image. The newest frame is the brightest; older frames are progressively dimmer — approximating a long-exposure or "light painting" effect.

The composite uses an **Exponential Moving Average (EMA)**:

```
stack_new = stack_old × (1 − α) + new_frame × α
```

where `α = ln(100) / N` (N = buffer depth). This gives a ~100:1 newest-to-oldest brightness ratio and requires only **2 GPU canvas operations per stored frame** regardless of buffer size:

- **Pass 1** — `ColorMatrix.setScale(1−α, 1−α, 1−α, 1.0)` fades the existing composite
- **Pass 2** — `PorterDuff.ADD` blends the new thumbnail at `α` opacity

Because Sun and Saturn have α values below 1/255 (the minimum representable as an integer paint alpha), the implementation batches K stored frames per update, where `K = ceil(1 / (α × 255))`, using effective α = K×α. This guarantees a non-zero blend contribution while keeping the update rate visually reasonable (Sun: ~2×/sec, Saturn: ~1 update per 5 seconds). The mini-view is seeded with the first captured frame at full opacity so it is immediately visible in all modes.

### UI

- **QUEUEING label** — large centered text shown while the buffer fills during the delay window
- **Timestamp** — bottom-right monospace label showing the exact capture time of the currently displayed frame (MM/DD/YYYY HH:MM:SS.MLS), updated every 100 ms
- **Mode label** — bottom-left label showing the mode name and delay (e.g. "Moon / 1.3 sec delay")

---

## Architecture

```
com.toddmargolis.astroandroidapp/
├── MainActivity.java        — Mode selection (Moon / Sun / Saturn), camera permission
├── CameraActivity.java      — Camera pipeline, frame processing loop, fullscreen display,
│                              EMA ghost-stack composite, UI labels
├── CelestialMode.java       — Configuration model: delay, resolution, FPS, tint, overlay resource
├── FrameBuffer.java         — Frame queue (RAM or MediaStore JPEG), timestamp tracking,
│                              thumbnail generation, cached MediaStore reads
└── OverlayRenderer.java     — Canvas-based compositing: tint + scaled overlay image
```

### Key Dependencies

- **CameraX 1.3.1** — `camera-camera2`, `camera-lifecycle`, `camera-view`
- **Material Design 1.10.0**
- **Java 11** / minSdk 26 / targetSdk 36

---

## Build & Install

```bash
# Build debug APK
./gradlew assembleDebug

# Build and install on connected device
./gradlew installDebug

# Run unit tests
./gradlew test
```

Requires Android SDK and a connected device or emulator running Android 8.0+ (API 26+). A physical device with a front-facing camera is required for full operation.
