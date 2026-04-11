# Cosmic Latency — Time-Delay Video Mirrors

*An installation by Todd Margolis*

---

Imagine seeing yourself not as you are now, but as you were moments, or even an hour, ago. This series of video mirrors forces a visceral encounter with a profound truth of our universe: because light travels at a finite speed, all observation is an act of looking into the past. By introducing a precise, scientifically-calibrated delay into your reflection, each mirror makes this cosmic latency immediate and deeply personal.

The work dismantles the illusion of a shared, instantaneous present, rendering the self in the mirror perpetually out of reach — a version of you that has already vanished. The experience scales dramatically, moving from the subtly disorienting to the profoundly estranged. The mirrors cease to be mere surfaces for self-recognition and transform into portals, translating abstract astronomical data into a concrete, lived experience.

This work is a potent *memento mori*, a reminder that even in our most immediate self-perception, we are always confronting a ghost. It is the culmination of decades of artistic practice by Todd Margolis, who has consistently explored the intersection of perception, technology, and science. His extensive career in virtual and augmented reality — from co-inventing VR systems at the Electronic Visualization Lab to creating telepresence performances — has always questioned the nature of presence and the delays inherent in mediated communication. This series is a direct extension of that inquiry, grounding the technological manipulation of time not in artistic whim, but in the physical laws of the universe.

This work finds a powerful precedent in Margolis's 2012 augmented reality piece *Moon Lust*, exhibited at the Adler Planetarium, which first combined celestial concepts with technologies of altered perception. By turning a core principle of observational astronomy into a deeply personal, philosophical, and unforgettable encounter, the work transforms the gallery into an observatory that looks inward to reveal the most fundamental truths of our place in the cosmos.

---

## The Four Mirrors

### The Moon — 1.3 second delay

The "Moon" mirror confronts you with a 1.3-second delay, a slight, uncanny hiccup in reality. This subtle temporal disjunction makes perceptible the distance between Earth and our nearest celestial neighbor, transforming a familiar reflection into something slightly, unsettlingly out of sync.

**Light travel time:** Earth → Moon ≈ 1.3 seconds (384,400 km at 299,792 km/s)

### The Sun — 8 minutes 20 seconds delay

In the "Sun" mirror, an 8-minute and 20-second gap separates you from your reflection, translating the immense scale of our solar system into a tangible temporal disjunction. The delay represents the time it takes for light to travel from the Sun to Earth, making the vastness of space immediately, personally felt.

**Light travel time:** Sun → Earth ≈ 8 minutes 20 seconds (149.6 million km at 299,792 km/s)

### Saturn — 79 minute delay

The "Saturn" mirror introduces a delay of nearly an hour and a half; the reflected self is so temporally removed it feels like another person, a phantom of your own recent history.

**Light travel time:** Saturn → Earth ≈ 79 minutes at mean opposition distance (~1.28 billion km at 299,792 km/s)

### Proxima Centauri — 4.24 year delay

The "Proxima Centauri" mirror is the most extreme: a delay of over four years. Because no single device can hold four years of video, the mirror stores just one frame every four hours — but not an arbitrary frame. Motion detection continuously evaluates the scene across each four-hour window, selecting the single most-changed moment as the representative image. The result is a mosaic of significant instants drawn from across years, a self-portrait assembled from the peaks of lived experience. The viewer confronts not a continuous record of their past, but a curated archaeology of their most active moments.

**Light travel time:** Proxima Centauri → Earth ≈ 4.24 years (4.013 × 10¹³ km at 299,792 km/s)

---

## Technical Overview

The app runs on a Samsung Galaxy Tab A9+ (Android 13+) mounted as a full-screen video mirror. The front-facing camera captures live video; frames are stored in a precisely-timed buffer and played back after the scientifically-calibrated delay. A celestial body overlay is composited onto the delayed video, and a ghost-stack mini-view shows all buffered frames accumulated over time.

### Core Data Flow

```
Camera (CameraX)
    └── ImageAnalysis (RGBA_8888, front camera)
            └── FrameBuffer.addFrame()
                    ├── Store frame (JPEG on disk via app-private external storage)
                    ├── Return thumbnail → EMA ghost-stack composite + barcode strip
                    └── getDelayedFrame() → oldest frame in buffer
                            └── OverlayRenderer.renderFrame()
                                    └── mainView (fullscreen ImageView)
```

### Frame Buffering

Each mode uses disk-based JPEG storage. Buffer depth and capture rate are tuned per mode:

| Mode | Delay | Buffer depth | Capture rate | Resolution | Storage |
|------|-------|-------------|--------------|------------|---------|
| Moon | 1.3 s | ~10 frames | 8 fps | 1920×1080 | JPEG on disk |
| Sun | 8m 20s | ~7,500 frames | 15 fps | 1920×1080 | JPEG on disk |
| Saturn | 79 min | ~4,740 frames | 1 fps | 1280×720 | JPEG on disk |
| Proxima Centauri | 4.24 years | grows indefinitely | 1 frame / 4 hours | 1280×720 | JPEG on disk |

All modes compress frames to JPEG (60% quality) and write to the app's private external storage directory (`getExternalFilesDir()`). This avoids triggering Android's media scanner, which caused multi-minute freezes when thousands of buffer files accumulated. A parallel timestamp buffer tracks the exact capture time of each stored frame.

Frame skipping (`frameSkip = targetFps / bufferFps`) ensures the buffer fills at the intended rate. A decoded-frame cache (`cachedFrame` / `cachedFile`) prevents redundant JPEG decodes at the display frame rate — the same file is only decoded once per buffer head advance.

Sessions survive app restarts: on launch, the most recent session directory for the current mode is detected, its JPEG files are scanned back into the frame queue, and a checkpoint file records the buffer head so playback resumes at the correct temporal position.

### Proxima Centauri: Motion-Based Frame Selection

Because a 4.24-year delay cannot be accumulated on a single device, Proxima mode stores one frame per four-hour window. Rather than capturing an arbitrary frame, the system runs continuous motion detection at 1 fps and selects the frame with the highest change score within the window.

**Algorithm:**
1. Sample one frame per second (1fps gate inside `handleProximaFrame()`).
2. Each sample is compared against the previous second's thumbnail using luma-delta motion scoring (`calculateMotionScore()`). The score is the fraction of pixels whose luma changed by more than a threshold.
3. Candidate frames are written to a `candidates/` subdirectory with the motion score embedded in the filename: `yyyy.MM.dd.HH.mm.ss.SSS_m=X.XXXXX.jpg`.
4. At the end of each four-hour window, `selectBestProximaFrame()` scans all candidate filenames, identifies the highest score, moves the winner to the session directory as a permanent frame, and deletes all losers.
5. Permanent winner filenames retain the `_m=` score suffix so motion level is visible on disk.

If the app is force-closed mid-window, existing candidates are reloaded from `candidates/` on relaunch and the window continues accumulating.

### Celestial Overlay

`OverlayRenderer` composites a celestial body image onto each delayed frame using Android `Canvas`:

1. Draw the delayed video frame with a color tint (`PorterDuff.MULTIPLY`)
2. Scale and position the overlay image:
   - **Moon**: centered, scaled to 50% of frame-fit size
   - **Sun**: top-left aligned, scaled to fit the frame
   - **Saturn**: native pixel size (512×512), top-left corner
   - **Proxima Centauri**: full-frame Milky Way image, scaled to fit

The tint is applied both during **playback** (composited by `OverlayRenderer`) and during **queueing** (applied as a `ColorFilter` directly on the ghost-stack `ImageView`), so the color palette is consistent from first frame onward.

### Ghost-Stack Mini-View (EMA Composite)

A small preview (25% of screen width, 16:9) in the bottom-right corner shows all buffered frames accumulated into a single ghost image. The newest frame is the brightest; older frames are progressively dimmer — approximating a long-exposure or "light painting" effect.

The composite uses an **Exponential Moving Average (EMA)**:

```
stack_new = stack_old × (1 − α) + new_frame × α
```

where `α = ln(100) / N` (N = buffer depth). This gives a ~100:1 newest-to-oldest brightness ratio and requires only **2 GPU canvas operations per stored frame** regardless of buffer size:

- **Pass 1** — `ColorMatrix.setScale(1−α, 1−α, 1−α, 1.0)` fades the existing composite
- **Pass 2** — `PorterDuff.ADD` blends the new thumbnail at `α` opacity

Because Sun, Saturn, and Proxima Centauri have α values below 1/255 (the minimum representable as an integer paint alpha), the implementation batches K stored frames per update, where `K = ceil(1 / (α × 255))`, using effective α = K×α. This guarantees a non-zero blend contribution while keeping the update rate visually reasonable.

### Movie Barcode Strip

A full-width strip at the bottom of the screen shows recent frame history as a "movie barcode":

- **Moon**: displays the past 10 seconds of frames as fixed-width (~32 px) thumbnail slices, scrolling left as new frames arrive. Each slice is a vertically cropped and squished thumbnail of the actual frame — not an averaged color. Tick marks at 1-second intervals are drawn on a separate overlay layer so they remain fixed (non-scrolling). When no new frame is available at a given tick, the previous frame repeats and a debug log entry is recorded.
- **Sun / Saturn / Proxima Centauri**: displays the full delay window as single-pixel color slits (average color of each stored frame), oldest on the left. During queueing the strip fills left to right; during playback it scrolls left as new frames arrive.

The barcode uses a time-proportional accumulator (`barcodeAccum += dtSec / barcodeHistorySeconds * barcodeW`) to guarantee wall-clock accuracy regardless of the actual capture frame rate. Output bitmaps are double-buffered to avoid allocations in the render loop.

### UI

- **Mode name** — top-center label with rounded bottom corners showing the mode name (e.g. "Proxima Centauri")
- **QUEUEING label** — large centered text shown while the buffer fills during the delay window
- **Countdown / timestamp** — bottom-left monospace label with a rounded top-right corner; shows a live countdown during queueing and the exact capture timestamp (`MM/DD/YYYY HH:MM:SS.MLS`) of the displayed frame during playback
- **Movie barcode** — full-width strip at bottom showing frame history (see above)
- **Ghost-stack mini-view** — bottom-right corner, visible during playback only

---

## Architecture

```
com.toddmargolis.astroandroidapp/
├── MainActivity.java        — Mode selection (Moon / Sun / Saturn / Proxima Centauri),
│                              camera permission
├── CameraActivity.java      — Camera pipeline, frame processing loop, fullscreen display,
│                              EMA ghost-stack composite, movie barcode, UI labels
├── CelestialMode.java       — Configuration model: delay, resolution, FPS, tint, overlay
│                              resource, barcode history window, storage interval
├── FrameBuffer.java         — Frame queue (JPEG on disk), timestamp tracking, thumbnail
│                              generation, session persistence, Proxima motion-based
│                              best-frame selection
└── OverlayRenderer.java     — Canvas-based compositing: tint + scaled overlay image,
                               double-buffered output bitmaps
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
