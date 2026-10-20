// Copyright (c) 2025 Todd Margolis. All rights reserved.
// Delayed Vision - Cosmically Inspired Video Mirrors. See LICENSE.md for terms.
package com.toddmargolis.astroandroidapp;

/**
 * Configuration model for a single celestial body mode.
 *
 * Each mode represents one artwork: Moon (1.3 s), Sun (8m 20s), Saturn (79 min),
 * or Proxima Centauri (4.24 years). All parameters that govern the camera pipeline,
 * frame buffer, display cadence, and visual appearance live here.
 *
 * Use the static factory methods (createMoonMode(), etc.) rather than constructing
 * this class directly — the factory methods document the intent of each value.
 */
public class CelestialMode {

    /** Display name shown in the UI and used to identify session directories on disk. */
    public String name;

    /** How many seconds behind live the displayed frame should be (the light-travel delay). */
    public float delaySeconds;

    /** Drawable resource ID for the celestial body overlay image. */
    public int overlayResourceId;

    /** Camera capture target resolution. CameraX will select the nearest supported size. */
    public int targetWidth;
    public int targetHeight;

    /**
     * Camera output frame rate target. Only frames that pass the frameSkip gate are stored;
     * frameSkip = targetFps / bufferFps, so bufferFps controls actual storage throughput.
     */
    public int targetFps;

    /**
     * How many frames per second are written to the buffer.
     * frameSkip = targetFps / bufferFps is computed in FrameBuffer; one frame is stored
     * for every frameSkip incoming camera frames, keeping encode/decode load manageable.
     */
    public int bufferFps;

    /**
     * Base tint color applied to the video frame via PorterDuff.MULTIPLY during both
     * queuing (on the ghost ImageView) and playback (composited by OverlayRenderer).
     * Ignored when useAutoTint=true; in that case the tint is computed from the overlay image.
     */
    public int tintColor;

    /**
     * When true, the tint color is computed automatically from the average non-transparent
     * pixel color of the overlay image (see OverlayRenderer.calculateAverageColor()).
     * Use for Moon, where the tint should match whatever overlay image is loaded.
     */
    public boolean useAutoTint;

    /**
     * When true, frames are compressed to JPEG and written to app-private external storage.
     * When false, raw Bitmap objects are kept in an ArrayDeque in RAM.
     * Moon uses the RAM path: its 1.3s window is only ~10 frames (~90MB of native bitmap
     * memory), and holding them as Bitmaps eliminates a continuous ~7.5 JPEG encodes plus
     * ~7.5 decodes per second at 1080p. The other modes buffer minutes-to-years of history
     * and must use disk. Disk is also what makes a session survive an app restart — RAM
     * mode starts fresh, which costs nothing when the whole delay is 1.3 seconds.
     */
    public boolean useCompression;

    /**
     * EMA blending coefficient for the ghost-stack accumulator.
     * Controls how quickly new frames dominate the composite: higher = newer frames brighter.
     * alpha = ln(100) / N where N is the buffer depth gives a ~100:1 newest-to-oldest ratio.
     * Values below ~1/255 require multi-frame batching (see CameraActivity.emaUpdateEvery).
     */
    public float emaAlpha;

    /**
     * Minimum milliseconds between display frame refreshes on the display thread.
     * Throttles how often getDelayedFrame() + OverlayRenderer.renderFrame() is called.
     * Lower values = smoother display but more JPEG decode pressure on the camera thread.
     */
    public int displayIntervalMs;

    /**
     * If > 0, store one frame per this many milliseconds rather than using frameSkip.
     * Intended for Proxima Centauri, which needs one frame every 4 hours — far too sparse
     * for frameSkip to handle cleanly. When this is set, FrameBuffer.handleProximaFrame()
     * takes over the entire addFrame() dispatch.
     */
    public long storageIntervalMs = 0;

    /**
     * How many seconds of live-feed history the barcode strip represents.
     * 0 means "use delaySeconds". Override when the visual barcode window should differ
     * from the delay (e.g. Moon has a 1.3s delay but a 10s barcode window so the strip
     * shows meaningful recent motion rather than just 1.3 seconds of history).
     */
    public float barcodeHistorySeconds = 0;

    public CelestialMode(String name, float delaySeconds, int overlayResourceId,
                         int targetWidth, int targetHeight, int targetFps, int bufferFps,
                         int tintColor, boolean useAutoTint, boolean useCompression,
                         int displayIntervalMs, float emaAlpha) {
        this.name = name;
        this.delaySeconds = delaySeconds;
        this.overlayResourceId = overlayResourceId;
        this.targetWidth = targetWidth;
        this.targetHeight = targetHeight;
        this.targetFps = targetFps;
        this.bufferFps = bufferFps;
        this.tintColor = tintColor;
        this.useAutoTint = useAutoTint;
        this.useCompression = useCompression;
        this.displayIntervalMs = displayIntervalMs;
        this.emaAlpha = emaAlpha;
    }

    // -------------------------------------------------------------------------
    // Factory methods — one per artwork mode
    // -------------------------------------------------------------------------

    /**
     * Moon mode: 1.3-second delay (Earth–Moon light-travel time).
     *
     * bufferFps=8 keeps JPEG encode load manageable (~8 writes/sec) while still
     * capturing enough frames for a smooth 1.3-second buffer.
     * targetFps=16 caps the camera sensor via Camera2Interop (see CameraActivity.bindCamera)
     * to cut camera subsystem power; 16/8 = frameSkip 2 keeps the stored rate at 8 fps.
     * displayIntervalMs=125 = 8 fps display rate, matching bufferFps.
     * useAutoTint=true derives the tint from the moon overlay image colors.
     * emaAlpha=0.3 gives a responsive ghost that visibly reacts to motion over ~3 frames.
     * barcodeHistorySeconds=10 makes the barcode strip show a wider window than the
     * delay itself, giving the barcode visual interest beyond the tiny 1.3s window.
     */
    public static CelestialMode createMoonMode() {
        CelestialMode m = new CelestialMode(
                "Moon",
                1.3f,               // 384,400 km / 299,792 km/s
                R.drawable.moon_overlay,
                1920, 1080,
                16,                    // camera target fps (capped in hardware via Camera2Interop)
                8,                              // store 1 in 2 frames (16/8 = frameSkip 2)
                0xFFD1D1D1,                     // light grey (overridden by autoTint)
                true,                           // derive tint from overlay image average color
                false,                          // RAM buffering — ~10 frames; no JPEG churn (see useCompression docs)
                125,                            // display update every 125ms = 8 fps
                0.3f                            // fast-responding EMA — tuned 2.5× from baseline ~0.118
        );
        m.barcodeHistorySeconds = 10.0f; // show 10s of history in the barcode strip
        return m;
    }

    /**
     * Sun mode: 500-second delay (8 minutes 20 seconds, Sun–Earth light-travel time).
     *
     * bufferFps=4 stores ~2,000 frames over 500 seconds at 1920×1080, matching the 4 fps
     * display rate — storing faster than the display consumes frames wastes JPEG encode
     * power and flash writes on frames that are never shown.
     * targetFps=16 caps the camera sensor via Camera2Interop (see CameraActivity.bindCamera)
     * to cut camera subsystem power; 16/4 = frameSkip 4 keeps the stored rate at 4 fps.
     * displayIntervalMs=250 = 4 fps display; with a 500s delay the frame barely changes
     * second-to-second so high display rate would waste JPEG decode bandwidth.
     * tintColor 0xFFFFD54F is a warm amber yellow evoking sunlight.
     * emaAlpha=0.006 is very slow — at 4 stored fps over 500s the buffer is ~2,000 deep,
     * so alpha must be tiny to keep older frames visible in the ghost.
     * (ln(100)/2000 ≈ 0.0023 baseline × 2.5 tuning ≈ 0.006 — same formula and tuning
     * factor as the previous 0.0015 value used at the old ~7,500-frame buffer depth.)
     */
    public static CelestialMode createSunMode() {
        return new CelestialMode(
                "Sun",
                500f,                   // 149.6M km / 299,792 km/s ≈ 499.7s
                R.drawable.sun_overlay,
                1920, 1080,
                16,                     // camera target fps (capped in hardware via Camera2Interop)
                4,                      // store 1 in 4 frames (16/4 = frameSkip 4)
                0xFFFFD54F,             // warm amber yellow — sunlight
                false,                  // use manual tint color above
                true,                   // JPEG disk storage
                250,                    // display update every 250ms = 4 fps
                0.006f                  // very slow ghost — tuned 2.5× from baseline ~0.0023
        );
    }

    /**
     * Saturn mode: 4,740-second delay (~79 minutes, Saturn–Earth light-travel time at mean opposition).
     *
     * bufferFps=1 stores one frame per second (~4,740 frames total at 1280×720).
     * displayIntervalMs=2000 = 0.5 fps display; a 79-min delay changes imperceptibly
     * second-to-second, so very infrequent display updates are fine.
     * tintColor 0xFFD2691E is a warm chocolate/rust tone evoking Saturn's banded atmosphere.
     * emaAlpha=0.0024 is extremely slow — buffer is ~4,740 frames deep.
     */
    public static CelestialMode createSaturnMode() {
        return new CelestialMode(
                "Saturn",
                4740f,                  // ~1.28 billion km / 299,792 km/s ≈ 4,270s; padded to 4,740s (79 min)
                R.drawable.saturn_placeholder,
                1280, 720,
                15,                     // camera target fps — Tab A9+ front camera pins at [15,15]
                                        // (no lower fixed range); 15/1 = frameSkip 15 keeps 1 fps stored
                1,                      // store 1 frame per second (15/1 = frameSkip 15)
                0xFFD2691E,             // chocolate/rust — Saturn's atmosphere
                false,                  // use manual tint color above
                true,                   // JPEG disk storage
                2000,                   // display update every 2s = 0.5 fps
                0.0024f                 // very slow ghost — tuned 2.5× from baseline ~0.00096
        );
    }

    /**
     * Proxima Centauri mode (Collector's Edition): 4.24-year delay.
     *
     * storageIntervalMs overrides the normal frameSkip mechanism entirely — FrameBuffer
     * delegates to handleProximaFrame() which samples at 1fps, scores each second for
     * motion, and keeps only the highest-motion frame from each 4-hour window.
     * bufferFps=1 is declared but effectively unused (storageIntervalMs takes precedence).
     * displayIntervalMs=60_000 = one display update per minute; with a 4+ year delay,
     * more frequent updates would be meaningless and waste battery.
     * tintColor 0xFFE0FFFF is a pale blue-white evoking infrared/starlight.
     * emaAlpha=0.001 keeps the ghost extremely slow-accumulating — the buffer grows
     * over years, so each new frame should barely shift the composite.
     */
    public static CelestialMode createProximaCentauriMode() {
        CelestialMode m = new CelestialMode(
                "Proxima Centauri",
                133_848_480f,                       // 4.24 years × 365.25 × 24 × 3600 s
                R.drawable.proxima_centauri_milky_way,
                1280, 720,
                15,                                 // camera target fps — pins at [15,15] on the Tab A9+;
                                                    // sampling is time-based (1/sec) so only power is affected
                1,                                  // bufferFps — overridden by storageIntervalMs
                0xFFE0FFFF,                         // pale blue-white — infrared/starlight
                false,                              // use manual tint color above
                true,                               // JPEG disk storage
                60_000,                             // display update every 60s (4yr delay; no need for more)
                0.001f                              // very slow ghost accumulation over years
        );
        // Store the best-motion frame from each 4-hour window (not simply every 4 hours).
        // See FrameBuffer.handleProximaFrame() for the motion-scoring and selection logic.
        // 16,436,250 ms = yearMs / barcodeW (31,557,600,000 / 1920) — one pixel per window,
        // so any frame timestamp within window N maps to exactly xPos N in the barcode.
        m.storageIntervalMs = 16_436_250L;
        return m;
    }
}
