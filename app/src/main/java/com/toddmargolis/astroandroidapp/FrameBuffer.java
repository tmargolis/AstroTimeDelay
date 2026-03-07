package com.toddmargolis.astroandroidapp;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.Image;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;
import androidx.camera.core.ImageProxy;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

public class FrameBuffer {
    private static final String TAG = "FrameBuffer";
    private static final int JPEG_QUALITY = 60;

    private final CelestialMode mode;
    private final Context context;

    // Thumbnail dimensions for the EMA mini-view
    private final int thumbW;
    private final int thumbH;
    private final float emaAlpha; // α = ln(100) / maxFrames

    // RAM path (Moon)
    private ArrayDeque<Bitmap> bitmapBuffer;
    private ArrayDeque<Long> timestampBuffer;

    // Disk path (Sun, Saturn) — stored via MediaStore in Pictures/AstroTimeDelay/
    private ArrayDeque<Uri> uriBuffer;
    private ArrayDeque<Long> diskTimestampBuffer;
    private String sessionRelativePath;
    // Cache the last decoded frame so we only hit MediaStore when the buffer head actually advances
    private Bitmap cachedFrame;
    private Uri cachedUri;

    private static final SimpleDateFormat FOLDER_FMT =
            new SimpleDateFormat("yyyy-MM-dd:HH:mm:ss", Locale.US);
    private static final SimpleDateFormat FILE_FMT =
            new SimpleDateFormat("yyyy.MM.dd.HH.mm.ss.SSS", Locale.US);

    private int maxFrames;
    private int frameSkip;      // store 1 out of every N incoming frames
    private int skipCounter = 0;
    private long frameCount = 0;
    private long firstFrameTimeMs = 0;

    public FrameBuffer(Context context, CelestialMode mode, int thumbW, int thumbH) {
        this.context = context;
        this.mode = mode;
        this.thumbW = thumbW;
        this.thumbH = thumbH;
        this.frameSkip = Math.max(1, mode.targetFps / mode.bufferFps);
        this.maxFrames = (int)(mode.delaySeconds * mode.bufferFps) + 1;
        this.emaAlpha = (float)(Math.log(100.0) / maxFrames);

        if (mode.useCompression) {
            String startTime = FOLDER_FMT.format(new Date());
            sessionRelativePath = "Pictures/AstroTimeDelay/" + mode.name + "_" + startTime + "/";
            uriBuffer = new ArrayDeque<>();
            diskTimestampBuffer = new ArrayDeque<>();
        } else {
            bitmapBuffer = new ArrayDeque<>();
            timestampBuffer = new ArrayDeque<>();
        }

        Log.d(TAG, "FrameBuffer init: " + mode.name +
                " maxFrames=" + maxFrames +
                " frameSkip=" + frameSkip +
                " disk=" + mode.useCompression +
                (mode.useCompression ? " path=" + sessionRelativePath : ""));
    }

    public long getFirstFrameTimeMs() { return firstFrameTimeMs; }

    public float getEmaAlpha() { return emaAlpha; }

    /**
     * Adds a frame to the buffer. Returns a thumbnail Bitmap (thumbW × thumbH) if the frame
     * was stored, or null if it was skipped (frameSkip). Caller is responsible for recycling
     * the returned thumbnail after use.
     */
    public Bitmap addFrame(ImageProxy imageProxy) {
        try {
            skipCounter++;
            if (skipCounter < frameSkip) {
                return null;
            }
            skipCounter = 0;

            Bitmap bitmap = imageProxyToBitmap(imageProxy);
            if (bitmap == null) return null;

            frameCount++;
            if (frameCount == 1) firstFrameTimeMs = System.currentTimeMillis();

            // Generate thumbnail BEFORE any recycle call
            Bitmap thumb = Bitmap.createScaledBitmap(bitmap, thumbW, thumbH, true);

            if (mode.useCompression) {
                addFrameToDisk(bitmap);
                bitmap.recycle();
            } else {
                bitmapBuffer.add(bitmap);
                timestampBuffer.add(System.currentTimeMillis());
                while (bitmapBuffer.size() > maxFrames) {
                    Bitmap old = bitmapBuffer.poll();
                    if (old != null && !old.isRecycled()) old.recycle();
                    timestampBuffer.poll();
                }
            }

            if (frameCount % 30 == 0) {
                int size = mode.useCompression ? uriBuffer.size() : bitmapBuffer.size();
                Log.d(TAG, "Buffer: " + size + " frames");
            }

            return thumb;
        } catch (Exception e) {
            Log.e(TAG, "Error adding frame", e);
            return null;
        }
    }

    private void addFrameToDisk(Bitmap bitmap) {
        try {
            long captureTimeMs = System.currentTimeMillis();
            String filename = FILE_FMT.format(new Date(captureTimeMs)) + ".jpg";

            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, sessionRelativePath);

            Uri uri = context.getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                Log.e(TAG, "MediaStore insert returned null URI");
                return;
            }

            try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                if (out != null) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out);
                }
            }

            uriBuffer.add(uri);
            diskTimestampBuffer.add(captureTimeMs);

            while (uriBuffer.size() > maxFrames) {
                Uri old = uriBuffer.poll();
                if (old != null) context.getContentResolver().delete(old, null, null);
                diskTimestampBuffer.poll();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error writing frame to MediaStore", e);
        }
    }

    public Bitmap getDelayedFrame() {
        if (mode.useCompression) {
            Uri uri = uriBuffer.peek();
            if (uri == null) return null;

            // Only decode from MediaStore when the buffer head has actually advanced.
            // Without this, every camera frame (24fps for Saturn) would decode the same
            // JPEG from MediaStore, causing massive I/O load and eventual freeze.
            if (uri.equals(cachedUri) && cachedFrame != null && !cachedFrame.isRecycled()) {
                return cachedFrame;
            }

            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                if (in == null) return cachedFrame; // fall back to last good frame on I/O failure
                Bitmap decoded = BitmapFactory.decodeStream(in);
                if (decoded != null) {
                    if (cachedFrame != null && !cachedFrame.isRecycled()) cachedFrame.recycle();
                    cachedFrame = decoded;
                    cachedUri = uri;
                }
                return cachedFrame;
            } catch (Exception e) {
                Log.e(TAG, "Error reading frame from MediaStore", e);
                return cachedFrame; // fall back to last good frame on error
            }
        } else {
            if (bitmapBuffer.isEmpty()) return null;
            return bitmapBuffer.peek();
        }
    }

    /**
     * Returns the capture timestamp (ms since epoch) of the frame currently
     * at the head of the buffer — i.e. the frame being shown on screen.
     * For disk modes (Sun/Saturn): from diskTimestampBuffer (recorded at write time).
     * For RAM mode (Moon): from timestampBuffer (recorded at add time).
     * Returns 0 if no frame is available yet.
     */
    public long getCurrentFrameTimestamp() {
        if (mode.useCompression) {
            Long ts = diskTimestampBuffer.peek();
            return ts != null ? ts : 0;
        } else {
            Long ts = timestampBuffer.peek();
            return ts != null ? ts : 0;
        }
    }

    private Bitmap imageProxyToBitmap(ImageProxy imageProxy) {
        try {
            Image image = imageProxy.getImage();
            if (image == null) return null;

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

            if (rowPadding != 0) {
                Bitmap cropped = Bitmap.createBitmap(bitmap, 0, 0,
                        image.getWidth(), image.getHeight());
                bitmap.recycle();
                return cropped;
            }
            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "Error converting frame", e);
            return null;
        }
    }

    public void release() {
        if (mode.useCompression) {
            for (Uri uri : uriBuffer) {
                if (uri != null) context.getContentResolver().delete(uri, null, null);
            }
            uriBuffer.clear();
            diskTimestampBuffer.clear();
            if (cachedFrame != null && !cachedFrame.isRecycled()) cachedFrame.recycle();
            cachedFrame = null;
            cachedUri = null;
        } else {
            for (Bitmap b : bitmapBuffer) {
                if (b != null && !b.isRecycled()) b.recycle();
            }
            bitmapBuffer.clear();
            timestampBuffer.clear();
        }
        Log.d(TAG, "FrameBuffer released");
    }
}
