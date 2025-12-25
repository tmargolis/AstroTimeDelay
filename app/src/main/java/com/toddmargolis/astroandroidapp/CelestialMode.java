package com.toddmargolis.astroandroidapp;

public class CelestialMode {
    public String name;
    public float delaySeconds;
    public int overlayResourceId;
    public int targetWidth;
    public int targetHeight;
    public int targetFps;
    public int tintColor;
    public boolean useAutoTint;
    public boolean useCompression;

    public CelestialMode(String name, float delaySeconds, int overlayResourceId,
                         int targetWidth, int targetHeight, int targetFps,
                         int tintColor, boolean useAutoTint, boolean useCompression) {
        this.name = name;
        this.delaySeconds = delaySeconds;
        this.overlayResourceId = overlayResourceId;
        this.targetWidth = targetWidth;
        this.targetHeight = targetHeight;
        this.targetFps = targetFps;
        this.tintColor = tintColor;
        this.useAutoTint = useAutoTint;
        this.useCompression = useCompression;
    }

    // Factory methods for each mode
    public static CelestialMode createMoonMode() {
        return new CelestialMode(
                "Moon",
                1.3f,
                R.drawable.moon_overlay,  // We'll add this image later
                1920, 1080, 30,
                0xFFFFFFFF,  // White (will be auto-calculated)
                true,   // Use auto tint
                false   // No compression for Moon
        );
    }

    public static CelestialMode createSunMode() {
        return new CelestialMode(
                "Sun",
                500f,  // 8 min 20 sec = 500 seconds
                R.drawable.sun_overlay,
                1920, 1080, 30,
                0xFFFFD54F,  // Orange - you can change this
                false,  // Manual tint color
                true    // Use compression
        );
    }

    public static CelestialMode createSaturnMode() {
        return new CelestialMode(
                "Saturn",
                4500f,  // 75 minutes = 4500 seconds
                R.drawable.saturn_placeholder,
                1280, 720, 24,  // Lower res/fps for Saturn
                0xFFD2691E,  // Goldenrod - placeholder
                false,   // Manual tint color
                true    // Use compression
        );
    }
}