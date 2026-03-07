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

    public CelestialMode(String name, float delaySeconds, int overlayResourceId,
                         int targetWidth, int targetHeight, int targetFps, int bufferFps,
                         int tintColor, boolean useAutoTint, boolean useCompression) {
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
    }

    // Factory methods for each mode
    public static CelestialMode createMoonMode() {
        return new CelestialMode(
                "Moon",
                1.3f,
                R.drawable.moon_overlay,
                1920, 1080, 30, 30,
                0xFFFFFFFF,
                true,
                false
        );
    }

    public static CelestialMode createSunMode() {
        return new CelestialMode(
                "Sun",
                500f,
                R.drawable.sun_overlay,
                1920, 1080, 30, 15,
                0xFFFFD54F,
                false,
                true
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
                true
        );
    }
}