// Copyright (c) 2025 Todd Margolis. All rights reserved.
// Delayed Vision - Cosmically Inspired Video Mirrors. See LICENSE.md for terms.
package com.toddmargolis.astroandroidapp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.util.Log;

/**
 * Composites a celestial body overlay onto each delayed video frame.
 *
 * Each call to renderFrame() produces a finished bitmap ready for display:
 *   1. The delayed video frame is drawn with a color tint (PorterDuff.MULTIPLY).
 *   2. The celestial body image is drawn on top at the configured position and alpha.
 *
 * To avoid per-frame allocations, output bitmaps are pre-allocated and double-buffered,
 * and the overlay image is scaled once on the first frame rather than every frame.
 */
public class OverlayRenderer {
    private static final String TAG = "OverlayRenderer";

    private Bitmap overlayImage;        // original, as decoded from resources
    private Paint tintPaint;            // applies color tint to the video frame via MULTIPLY filter
    private Paint overlayPaint;         // draws the overlay image with per-call alpha
    private ColorFilter colorFilter;    // PorterDuff MULTIPLY filter at the mode's tint color
    private int tintColor;              // resolved tint (either manual or auto-calculated)
    private CelestialMode mode;

    // Pre-allocated output bitmaps (double-buffer).
    // Eliminates 8MB Bitmap.createBitmap() on every renderFrame() call, which was
    // causing ~120MB/sec of allocations for Sun mode (15 fps × 8MB) and triggering
    // repeated GC pauses that froze the camera thread at the countdown→delay transition.
    // Both bitmaps are null until the first renderFrame() call (lazy init at frame dimensions).
    private Bitmap outA, outB;
    private boolean useOutA = true;

    // Pre-scaled overlay image and its draw position.
    // Previously createScaledBitmap() was called inside renderFrame() on every frame —
    // another large allocation each call. Now computed once on the first frame.
    private Bitmap scaledOverlayImage; // null until first renderFrame()
    private int overlayLeft, overlayTop;

    public OverlayRenderer(Context context, CelestialMode mode) {
        this.mode = mode;

        // Load overlay image.
        // Saturn: disable density scaling so it loads at exact pixel dimensions (512×512).
        // All other modes: allow normal density scaling; the image is then manually
        // scaled to fit the frame in initScaledOverlay().
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inScaled = !mode.name.equals("Saturn");
        overlayImage = BitmapFactory.decodeResource(context.getResources(),
                mode.overlayResourceId, opts);

        // Determine the tint color — either computed from the overlay or set manually.
        // Auto-tint (Moon) samples the overlay image and picks its average color so the
        // video tint always visually matches the overlay, even if the image changes.
        if (mode.useAutoTint) {
            tintColor = calculateAverageColor(overlayImage);
            Log.d(TAG, "Auto-calculated tint color: " + String.format("#%06X", (0xFFFFFF & tintColor)));
        } else {
            tintColor = mode.tintColor;
            Log.d(TAG, "Using manual tint color: " + String.format("#%06X", (0xFFFFFF & tintColor)));
        }

        // PorterDuff MULTIPLY darkens the video by the tint color. A white tint leaves
        // the video unchanged; a colored tint pushes it toward that hue.
        colorFilter = new PorterDuffColorFilter(tintColor, PorterDuff.Mode.MULTIPLY);

        tintPaint = new Paint();
        tintPaint.setColorFilter(colorFilter);

        // High-quality paint for scaling the overlay image onto the canvas.
        overlayPaint = new Paint();
        overlayPaint.setAntiAlias(true);
        overlayPaint.setFilterBitmap(true);
        overlayPaint.setDither(true);
    }

    /**
     * Returns the resolved tint color (manual or auto-calculated).
     * CameraActivity uses this to apply the same tint to the ghost-stack ImageView
     * during the queuing phase, before the overlay compositor takes over.
     */
    public int getResolvedTintColor() {
        return tintColor;
    }

    /**
     * Composites the delayed video frame and the celestial overlay into a single bitmap.
     *
     * @param videoFrame  The delayed frame from FrameBuffer.getDelayedFrame().
     * @param overlayAlpha Opacity of the celestial body image (0.0 = invisible, 1.0 = opaque).
     *                     CameraActivity fades this in logarithmically as the buffer fills.
     * @return A composited bitmap ready for display. The returned bitmap is owned by this
     *         renderer (double-buffered internally) — the caller must not recycle it.
     */
    public Bitmap renderFrame(Bitmap videoFrame, float overlayAlpha) {
        if (videoFrame == null) return null;

        try {
            int w = videoFrame.getWidth();
            int h = videoFrame.getHeight();

            // Lazy-init pre-allocated output bitmaps at the real frame dimensions.
            // Recreate only if dimensions change (shouldn't happen in normal use).
            if (outA == null || outA.getWidth() != w || outA.getHeight() != h) {
                if (outA != null && !outA.isRecycled()) outA.recycle();
                if (outB != null && !outB.isRecycled()) outB.recycle();
                outA = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                outB = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                // Reset scaled overlay so it's re-computed at the new dimensions
                if (scaledOverlayImage != null && scaledOverlayImage != overlayImage
                        && !scaledOverlayImage.isRecycled()) {
                    scaledOverlayImage.recycle();
                }
                scaledOverlayImage = null;
            }

            // Alternate between the two output bitmaps each call (double-buffer).
            // While outA is being displayed by the ImageView, outB is being drawn to here,
            // and vice versa — matching the same pattern used by the EMA ghost stack.
            Bitmap out = useOutA ? outA : outB;
            useOutA = !useOutA;

            // Lazy-init scaled overlay once per session (deferred until frame dimensions are known).
            if (overlayImage != null && scaledOverlayImage == null) {
                initScaledOverlay(w, h);
            }

            Canvas canvas = new Canvas(out);

            // Step 1: draw the delayed video frame with the color tint applied.
            canvas.drawBitmap(videoFrame, 0, 0, tintPaint);

            // Step 2: draw the pre-scaled celestial body overlay on top.
            if (scaledOverlayImage != null) {
                overlayPaint.setAlpha((int)(255 * overlayAlpha));
                canvas.drawBitmap(scaledOverlayImage, overlayLeft, overlayTop, overlayPaint);
            }

            return out;

        } catch (Exception e) {
            Log.e(TAG, "Error rendering frame", e);
            return videoFrame; // fall back to unmodified frame rather than crashing
        }
    }

    /**
     * Pre-computes scaledOverlayImage and its draw position at the given frame dimensions.
     * Called once on the first renderFrame() invocation (lazy, because frame size isn't
     * known until CameraX delivers the first frame).
     *
     * Positioning rules per mode:
     *   Saturn         — native pixel size (512×512), top-left corner. No scaling.
     *   Moon           — scaled to 50% of the "fit" size, centered in the frame.
     *   Sun/Proxima    — scaled to fit the full frame (longest edge fills the frame), top-left.
     */
    private void initScaledOverlay(int frameW, int frameH) {
        if (mode.name.equals("Saturn")) {
            // Saturn overlay is a small graphic (512×512) intended to sit unscaled
            // in the corner. Assign the same reference — do NOT recycle this separately
            // in release(), since overlayImage and scaledOverlayImage would be the same object.
            scaledOverlayImage = overlayImage;
            overlayLeft = 0;
            overlayTop  = 0;
        } else {
            // Compute the scale factor that fits the image within the frame (aspect-correct).
            float scale = Math.min(
                    (float) frameW / overlayImage.getWidth(),
                    (float) frameH / overlayImage.getHeight());

            // Moon overlay is drawn at half the fit size so it doesn't dominate the frame.
            if (mode.name.equals("Moon")) scale *= 0.5f;

            int scaledW = (int)(overlayImage.getWidth()  * scale);
            int scaledH = (int)(overlayImage.getHeight() * scale);
            scaledOverlayImage = Bitmap.createScaledBitmap(overlayImage, scaledW, scaledH, true);

            // Sun/Proxima: top-left aligned (the overlay fills the frame edge-to-edge).
            // Moon: centered so the disc sits in the middle of the reflection.
            overlayLeft = mode.name.equals("Sun") ? 0 : (frameW - scaledW) / 2;
            overlayTop  = mode.name.equals("Sun") ? 0 : (frameH - scaledH) / 2;
        }
        Log.d(TAG, "Overlay scaled: " + scaledOverlayImage.getWidth()
                + "x" + scaledOverlayImage.getHeight()
                + " at (" + overlayLeft + "," + overlayTop + ")");
    }

    /**
     * Computes the average color of non-transparent pixels in the bitmap.
     * Used by Moon mode (useAutoTint=true) to derive a tint that matches the overlay image.
     * Samples every 10th pixel for speed — sufficient accuracy for a tint color.
     */
    private int calculateAverageColor(Bitmap bitmap) {
        if (bitmap == null) return Color.WHITE;

        long redSum = 0, greenSum = 0, blueSum = 0;
        int pixelCount = 0;
        int step = 10; // sample every 10th pixel — fast enough for init, accurate enough for tinting

        for (int x = 0; x < bitmap.getWidth(); x += step) {
            for (int y = 0; y < bitmap.getHeight(); y += step) {
                int pixel = bitmap.getPixel(x, y);
                // Skip fully or mostly transparent pixels (e.g. corners of the moon disc PNG).
                if (Color.alpha(pixel) > 50) {
                    redSum   += Color.red(pixel);
                    greenSum += Color.green(pixel);
                    blueSum  += Color.blue(pixel);
                    pixelCount++;
                }
            }
        }

        if (pixelCount == 0) return Color.WHITE;

        int avgRed   = (int)(redSum   / pixelCount);
        int avgGreen = (int)(greenSum / pixelCount);
        int avgBlue  = (int)(blueSum  / pixelCount);

        return Color.rgb(avgRed, avgGreen, avgBlue);
    }

    /**
     * Free all bitmaps held by this renderer. Must be called from CameraActivity.onDestroy().
     * Note: for Saturn, scaledOverlayImage == overlayImage (same reference), so we guard
     * against double-recycling with an identity check.
     */
    public void release() {
        if (overlayImage != null && !overlayImage.isRecycled()) overlayImage.recycle();
        // scaledOverlayImage == overlayImage for Saturn — skip to avoid double-recycle
        if (scaledOverlayImage != null && scaledOverlayImage != overlayImage
                && !scaledOverlayImage.isRecycled()) scaledOverlayImage.recycle();
        if (outA != null && !outA.isRecycled()) outA.recycle();
        if (outB != null && !outB.isRecycled()) outB.recycle();
        outA = null;
        outB = null;
    }
}
