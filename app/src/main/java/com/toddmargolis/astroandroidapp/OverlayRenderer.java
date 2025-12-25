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
        this.mode = mode;  // Add this line

        // Load overlay image
        overlayImage = BitmapFactory.decodeResource(context.getResources(),
                mode.overlayResourceId);

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
        overlayPaint.setAlpha(250); // 50% transparency (0-255)
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

            // 2. Draw overlay image on top (scaled to fit)
            if (overlayImage != null) {
                // Center overlay without stretching
                float scale = Math.min(
                        (float)videoFrame.getWidth() / overlayImage.getWidth(),
                        (float)videoFrame.getHeight() / overlayImage.getHeight()
                );

                // Scale Moon by 2x
                if (mode.name.equals("Moon")) {
                    scale *= 0.5f;
                }

                int scaledWidth = (int)(overlayImage.getWidth() * scale);
                int scaledHeight = (int)(overlayImage.getHeight() * scale);

                Bitmap scaledOverlay = Bitmap.createScaledBitmap(overlayImage,
                        scaledWidth,
                        scaledHeight,
                        true);


                // Center horizontally
                int left = (videoFrame.getWidth() - scaledWidth) / 2;

                // Anchor to bottom for Sun, center for others
                int top;
                if (mode.name.equals("Sun")) {
                    int offset = (int)(videoFrame.getHeight() * 0.1);  // 10% of height
                    top = videoFrame.getHeight() - scaledHeight - offset;  // Bottom
                } else {
                    top = (videoFrame.getHeight() - scaledHeight) / 2;  // Center
                }

                canvas.drawBitmap(scaledOverlay, left, top, overlayPaint);

                // Set alpha
                //overlayPaint.setAlpha((int)(255 * overlayAlpha));

                if (scaledOverlay != overlayImage) {
                    scaledOverlay.recycle();
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