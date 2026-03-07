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
        // Removed hardcoded alpha to keep PNG transparency
    }

    public Bitmap renderFrame(Bitmap videoFrame, float overlayAlpha) {
        if (videoFrame == null) {
            return null;
        }

        try {
            // Create output bitmap
            Bitmap output = Bitmap.createBitmap(videoFrame.getWidth(),
                    videoFrame.getHeight(),
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(output);

            // 1. Draw delayed video with tint
            canvas.drawBitmap(videoFrame, 0, 0, tintPaint);

            // 2. Draw overlay image on top
            if (overlayImage != null) {
                final Bitmap overlayToDraw;
                final int left;
                final int top;

                if (mode.name.equals("Saturn")) {
                    // Draw at native size, top-left corner — no scaling
                    overlayToDraw = overlayImage;
                    left = 0;
                    top = 0;
                } else {
                    // Scale to fit frame
                    float scale = Math.min(
                            (float)videoFrame.getWidth() / overlayImage.getWidth(),
                            (float)videoFrame.getHeight() / overlayImage.getHeight()
                    );
                    if (mode.name.equals("Moon")) {
                        scale *= 0.5f;
                    }
                    int scaledWidth = (int)(overlayImage.getWidth() * scale);
                    int scaledHeight = (int)(overlayImage.getHeight() * scale);
                    overlayToDraw = Bitmap.createScaledBitmap(overlayImage,
                            scaledWidth, scaledHeight, true);

                    if (mode.name.equals("Sun")) {
                        left = (videoFrame.getWidth() - scaledWidth) / 2;
                        top = 0; // aligned to top edge
                    } else {
                        left = (videoFrame.getWidth() - scaledWidth) / 2;
                        top = (videoFrame.getHeight() - scaledHeight) / 2;
                    }
                }

                overlayPaint.setAlpha((int)(255 * overlayAlpha));
                canvas.drawBitmap(overlayToDraw, left, top, overlayPaint);

                if (overlayToDraw != overlayImage) {
                    overlayToDraw.recycle();
                }
            }

            return output;

        } catch (Exception e) {
            Log.e(TAG, "Error rendering frame", e);
            return videoFrame;
        }
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
        if (overlayImage != null && !overlayImage.isRecycled()) {
            overlayImage.recycle();
        }
    }
}