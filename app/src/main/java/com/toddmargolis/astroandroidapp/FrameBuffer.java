package com.toddmargolis.astroandroidapp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.util.Log;
import androidx.camera.core.ImageProxy;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Queue;
import android.content.Context;

public class FrameBuffer {
    private static final String TAG = "FrameBuffer";

    private Context context;

    private CelestialMode mode;
    private Queue<Bitmap> bitmapBuffer;
    private int maxFrames;
    private long frameCount = 0;

    public FrameBuffer(Context context, CelestialMode mode) {
        this.context = context;  // Add this line
        this.mode = mode;

        // For now, we'll use uncompressed for all modes to keep it simple
        // Calculate max frames needed for delay
        maxFrames = (int)(mode.delaySeconds * mode.targetFps) + 10; // +10 buffer
        bitmapBuffer = new ArrayDeque<>();

        Log.d(TAG, "FrameBuffer initialized for " + mode.name +
                " with max " + maxFrames + " frames");
    }

    public void addFrame(ImageProxy imageProxy) {
        try {
            // Convert ImageProxy to Bitmap
            Bitmap bitmap = imageProxyToBitmap(imageProxy);

            if (bitmap != null) {
                bitmapBuffer.add(bitmap);
                frameCount++;

                // Remove old frames if buffer is full
                while (bitmapBuffer.size() > maxFrames) {
                    Bitmap old = bitmapBuffer.poll();
                    if (old != null && !old.isRecycled()) {
                        old.recycle();
                    }
                }

                if (frameCount % 30 == 0) {
                    Log.d(TAG, "Buffer size: " + bitmapBuffer.size() + " frames");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error adding frame", e);
        }
    }

    public Bitmap getDelayedFrame() {
        // Return the oldest frame (head of queue)
        // Don't remove it - we keep it in the buffer
        if (bitmapBuffer.isEmpty()) {
            return null;
        }
        return bitmapBuffer.peek();
    }

    private Bitmap imageProxyToBitmap(ImageProxy imageProxy) {
        try {
            Image image = imageProxy.getImage();
            if (image == null) {
                return null;
            }

            // With RGBA format, conversion is straightforward
            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * image.getWidth();

            Bitmap bitmap = Bitmap.createBitmap(
                    image.getWidth() + rowPadding / pixelStride,
                    image.getHeight(),
                    Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(buffer);

            // Crop if there's padding
            if (rowPadding != 0) {
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, image.getWidth(), image.getHeight());
            }

            return bitmap;

        } catch (Exception e) {
            Log.e(TAG, "Error converting RGBA image", e);
            return null;
        }
    }

    public void release() {
        // Clean up all bitmaps
        for (Bitmap bitmap : bitmapBuffer) {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
        bitmapBuffer.clear();
        Log.d(TAG, "FrameBuffer released");
    }
}