// Copyright (c) 2025 Todd Margolis. All rights reserved.
// Delayed Vision - Cosmically Inspired Video Mirrors. See LICENSE for terms.
package com.toddmargolis.astroandroidapp;

public class CelestialMode {
    public String name;
    public float delaySeconds;
    public int overlayResourceId;
    public int targetWidth;
    public int targetHeight;
    public int targetFps;
    public int bufferFps;
    public int tintColor;
    public boolean useAutoTint;
    public boolean useCompression;
    public float emaAlpha;

    // Minimum milliseconds between displayView frame refreshes on the display thread.
    // Throttles display updates to limit JPEG decode frequency.
    public int displayIntervalMs;

    // If > 0, store one frame per this many milliseconds instead of using frameSkip.
    // Use for modes with very low capture rates (e.g. 1 frame every 4 hours).
    public long storageIntervalMs = 0;

    // How many seconds of live-feed history the barcode strip represents.
    // 0 means "use delaySeconds". Override in factory methods when the barcode window
    // should differ from the delay (e.g. Moon has a 1.3s delay but a 5s barcode window).
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

    // Factory methods for each mode
    public static CelestialMode createMoonMode() {
        CelestialMode m = new CelestialMode(
                "Moon",
                1.3f,
                R.drawable.moon_overlay,
                1920, 1080, 30, 8,
                0xFFD1D1D1,
                true,
                true,
                125,
                0.3f // Adjusted 2.5x from baseline ~0.118
        );
        m.barcodeHistorySeconds = 10.0f;
        return m;
    }

    public static CelestialMode createSunMode() {
        return new CelestialMode(
                "Sun",
                500f,
                R.drawable.sun_overlay,
                1920, 1080, 30, 15,
                0xFFFFD54F,
                false,
                true,
                250, // 4 fps display (500 s delay; reduces JPEG decode load on camera thread)
                0.0015f // Adjusted 2.5x from baseline ~0.0006
        );
    }

    public static CelestialMode createSaturnMode() {
        return new CelestialMode(
                "Saturn",
                4740f,
                R.drawable.saturn_placeholder,
                1280, 720, 24, 1,
                0xFFD2691E,
                false,
                true,
                2000, // 0.5 fps display (79 min delay — 1 decode every 2 s is plenty)
                0.0024f // Adjusted 2.5x from baseline ~0.00096
        );
    }

    public static CelestialMode createProximaCentauriMode() {
        CelestialMode m = new CelestialMode(
                "Proxima Centauri",
                133_848_480f,                         // 4.24 × 365.25 × 24 × 3600 s
                R.drawable.proxima_centauri_milky_way,
                1280, 720, 24, 1,                     // bufferFps overridden by storageIntervalMs
                0xFFE0FFFF,                           // infrared
                false,
                true,                   // disk storage
                60_000,                               // display update every 60 s
                0.001f                                // slow ghost accumulation
        );
        m.storageIntervalMs = 4L * 60 * 60 * 1000;       // 1 frame every 4 hours
        return m;
    }
}
