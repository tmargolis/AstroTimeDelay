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

public class OverlayRenderer {
    private static final String TAG = "OverlayRenderer";

    private Bitmap overlayImage;
    private Paint tintPaint;
    private Paint overlayPaint;
    private ColorFilter colorFilter;
    private int tintColor;
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
        // Saturn: disable density scaling so it loads at exact pixel dimensions (512x512).
        // Sun/Moon: allow normal density scaling (images are then manually scaled to fit the frame).
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inScaled = !mode.name.equals("Saturn");
        overlayImage = BitmapFactory.decodeResource(context.getResources(),
                mode.overlayResourceId, opts);

        // Setup tint color
        if (mode.useAutoTint) {
            tintColor = calculateAverageColor(overlayImage);
            Log.d(TAG, "Auto-calculated tint color: " + String.format("#%06X", (0xFFFFFF & tintColor)));
        } else {
            tintColor = mode.tintColor;
            Log.d(TAG, "Using manual tint color: " + String.format("#%06X", (0xFFFFFF & tintColor)));
        }

        // Create color filter for tinting
        colorFilter = new PorterDuffColorFilter(tintColor, PorterDuff.Mode.MULTIPLY);

        // Setup paints
        tintPaint = new Paint();
        tintPaint.setColorFilter(colorFilter);

        overlayPaint = new Paint();
        overlayPaint.setAntiAlias(true);
        overlayPaint.setFilterBitmap(true);
        overlayPaint.setDither(true);
    }

    public int getResolvedTintColor() {
        return tintColor;
    }

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
                // Also reset scaled overlay so it's re-computed at new dimensions
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

            // Lazy-init scaled overlay (once per session, once dimensions are known).
            if (overlayImage != null && scaledOverlayImage == null) {
                initScaledOverlay(w, h);
            }

            Canvas canvas = new Canvas(out);

            // 1. Draw delayed video frame with color tint
            canvas.drawBitmap(videoFrame, 0, 0, tintPaint);

            // 2. Draw pre-scaled overlay on top
            if (scaledOverlayImage != null) {
                overlayPaint.setAlpha((int)(255 * overlayAlpha));
                canvas.drawBitmap(scaledOverlayImage, overlayLeft, overlayTop, overlayPaint);
            }

            return out;

        } catch (Exception e) {
            Log.e(TAG, "Error rendering frame", e);
            return videoFrame;
        }
    }

    /**
     * Pre-computes scaledOverlayImage and its draw position at the given frame dimensions.
     * Called once on the first renderFrame() invocation.
     */
    private void initScaledOverlay(int frameW, int frameH) {
        if (mode.name.equals("Saturn")) {
            // Saturn: draw at native pixel size, top-left corner — no scaling needed.
            // Use the same overlayImage reference (do NOT recycle separately in release()).
            scaledOverlayImage = overlayImage;
            overlayLeft = 0;
            overlayTop = 0;
        } else {
            // Moon / Sun: scale to fit the frame, maintaining aspect ratio
            float scale = Math.min(
                    (float) frameW / overlayImage.getWidth(),
                    (float) frameH / overlayImage.getHeight());
            if (mode.name.equals("Moon")) scale *= 0.5f; // Moon overlay at 50% of fit size
            int scaledW = (int)(overlayImage.getWidth()  * scale);
            int scaledH = (int)(overlayImage.getHeight() * scale);
            scaledOverlayImage = Bitmap.createScaledBitmap(overlayImage, scaledW, scaledH, true);
            overlayLeft = mode.name.equals("Sun") ? 0 : (frameW - scaledW) / 2;  // Sun: left; Moon: centered
            overlayTop  = mode.name.equals("Sun") ? 0 : (frameH - scaledH) / 2;  // Sun: top; Moon: centered
        }
        Log.d(TAG, "Overlay scaled: " + scaledOverlayImage.getWidth()
                + "x" + scaledOverlayImage.getHeight()
                + " at (" + overlayLeft + "," + overlayTop + ")");
    }

    private int calculateAverageColor(Bitmap bitmap) {
        if (bitmap == null) {
            return Color.WHITE;
        }

        long redSum = 0;
        long greenSum = 0;
        long blueSum = 0;
        int pixelCount = 0;

        // Sample every 10th pixel for performance
        int step = 10;
        for (int x = 0; x < bitmap.getWidth(); x += step) {
            for (int y = 0; y < bitmap.getHeight(); y += step) {
                int pixel = bitmap.getPixel(x, y);

                // Skip transparent pixels
                if (Color.alpha(pixel) > 50) {
                    redSum += Color.red(pixel);
                    greenSum += Color.green(pixel);
                    blueSum += Color.blue(pixel);
                    pixelCount++;
                }
            }
        }

        if (pixelCount == 0) {
            return Color.WHITE;
        }

        int avgRed = (int)(redSum / pixelCount);
        int avgGreen = (int)(greenSum / pixelCount);
        int avgBlue = (int)(blueSum / pixelCount);

        return Color.rgb(avgRed, avgGreen, avgBlue);
    }

    public void release() {
        if (overlayImage != null && !overlayImage.isRecycled()) overlayImage.recycle();
        // scaledOverlayImage == overlayImage for Saturn — avoid double-recycle
        if (scaledOverlayImage != null && scaledOverlayImage != overlayImage
                && !scaledOverlayImage.isRecycled()) scaledOverlayImage.recycle();
        if (outA != null && !outA.isRecycled()) outA.recycle();
        if (outB != null && !outB.isRecycled()) outB.recycle();
        outA = null;
        outB = null;
    }
}
