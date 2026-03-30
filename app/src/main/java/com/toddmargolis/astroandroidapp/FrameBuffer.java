package com.toddmargolis.astroandroidapp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.Image;
import android.util.Log;

import androidx.annotation.OptIn;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageProxy;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

public class FrameBuffer {
    private static final String TAG = "FrameBuffer";
    private static final int JPEG_QUALITY = 60;

    // Motion detection constants
    private static final int LUMA_DELTA_THRESHOLD = 18;
    private static final float MOTION_FRACTION_THRESHOLD = 0.4f;

    private final CelestialMode mode;
    // private final Context context; // Not used, can be removed

    // Thumbnail dimensions for the EMA mini-view
    private final int thumbW;
    private final int thumbH;
    private final float emaAlpha;

    // RAM path (Moon)
    private ArrayDeque<Bitmap> bitmapBuffer;
    private ArrayDeque<Long> timestampBuffer;

    // Disk path (Sun, Saturn) — stored as JPEG files in app-private external storage.
    // Using getExternalFilesDir() avoids the MediaStore media scanner, which caused a
    // ~1-minute freeze when 7,500+ buffer files triggered scanner backlog at mode transition.
    private ArrayDeque<File> fileBuffer;
    private ArrayDeque<Long> diskTimestampBuffer;
    private File sessionDir;
    // Cache the last decoded frame so we only hit disk when the buffer head actually advances
    private Bitmap cachedFrame;
    private File cachedFile;
    
    private Bitmap lastStoredThumb; // Copy of the most recently stored frame's thumbnail

    // Motion detection state
    private Bitmap previousThumb;
    private boolean motionDetected = false;
    private float lastMotionScore = 0.0f;

    private static final SimpleDateFormat FOLDER_FMT =
            new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US);
    private static final SimpleDateFormat FILE_FMT =
            new SimpleDateFormat("yyyy.MM.dd.HH.mm.ss.SSS", Locale.US);

    private volatile boolean released = false; // set before bitmaps are recycled

    private int frameSkip;      // store 1 out of every N incoming frames
    private int skipCounter = 0;
    private long frameCount = 0;
    private long firstFrameTimeMs = 0;

    public FrameBuffer(Context context, CelestialMode mode, int thumbW, int thumbH) {
        this.mode = mode;
        this.thumbW = thumbW;
        this.thumbH = thumbH;
        this.frameSkip = Math.max(1, mode.targetFps / mode.bufferFps);
        int maxFrames = (int) (mode.delaySeconds * mode.bufferFps) + 1;
        this.emaAlpha = mode.emaAlpha;

        if (mode.useCompression) {
            String startTime = FOLDER_FMT.format(new Date());
            // App-private external storage: not visible in gallery, no media scanner,
            // no permissions needed on API 26+, cleaned up on uninstall.
            File baseDir = context.getExternalFilesDir(null);
            if (baseDir == null) baseDir = context.getFilesDir(); // fallback to internal
            sessionDir = new File(baseDir, "AstroTimeDelay/" + mode.name + "_" + startTime);
            sessionDir.mkdirs();
            fileBuffer = new ArrayDeque<>();
            diskTimestampBuffer = new ArrayDeque<>();
        } else {
            bitmapBuffer = new ArrayDeque<>();
            timestampBuffer = new ArrayDeque<>();
        }

        Log.d(TAG, "FrameBuffer init: " + mode.name +
                " maxFrames=" + maxFrames +
                " frameSkip=" + frameSkip +
                " disk=" + mode.useCompression +
                (mode.useCompression ? " path=" + sessionDir : ""));
    }

    public long getFirstFrameTimeMs() { return firstFrameTimeMs; }

    public float getEmaAlpha() { return emaAlpha; }

    /**
     * Returns true once the oldest buffered frame is at least delaySeconds old —
     * i.e., the display pipeline can begin showing time-delayed content.
     * Time-based (not count-based) so it works correctly even when the actual
     * stored frame rate is lower than bufferFps (e.g., due to JPEG encode overhead).
     */
    public synchronized boolean isBufferFull() {
        long thresholdMs = System.currentTimeMillis() - (long)(mode.delaySeconds * 1000);
        Long oldest;
        if (mode.useCompression) {
            oldest = diskTimestampBuffer.peek();
        } else {
            oldest = timestampBuffer.peek();
        }
        return oldest != null && oldest <= thresholdMs;
    }

    /**
     * Returns true if motion was detected in the most recently stored frame
     */
    public synchronized boolean isMotionDetected() {
        return motionDetected;
    }

    /**
     * Returns the last computed motion score (fraction of pixels changed)
     */
    public synchronized float getLastMotionScore() {
        return lastMotionScore;
    }

    /**
     * Adds a frame to the buffer. Returns a thumbnail Bitmap (thumbW × thumbH) if the frame
     * was stored, or null if it was skipped (frameSkip). Caller is responsible for recycling
     * the returned thumbnail after use.
     */
    public Bitmap addFrame(ImageProxy imageProxy) {
        if (released) return null;
        try {
            skipCounter++;
            boolean shouldStore = (skipCounter >= frameSkip);
            if (shouldStore) skipCounter = 0;

            // Always decode and return a thumbnail for the very first camera frame,
            // regardless of frameSkip. For Saturn (frameSkip=24), this means the ghost
            // stack seeds immediately instead of waiting ~1 second for the first stored frame.
            // On all subsequent non-stored frames, skip decoding entirely (return null).
            boolean isFirstFrame = (frameCount == 0);
            if (!shouldStore && !isFirstFrame) return null;

            Bitmap bitmap = imageProxyToBitmap(imageProxy);
            if (bitmap == null) return null;

            frameCount++;
            if (frameCount == 1) firstFrameTimeMs = System.currentTimeMillis();

            // First-frame-only seed: generate thumbnail, skip storage, return immediately
            if (!shouldStore) {
                Bitmap thumb = Bitmap.createScaledBitmap(bitmap, thumbW, thumbH, true);
                bitmap.recycle();
                return thumb;
            }

            // Generate thumbnail BEFORE any recycle call
            Bitmap thumb = Bitmap.createScaledBitmap(bitmap, thumbW, thumbH, true);

            // Keep a deep copy of the latest thumb for presence detection
            synchronized (this) {
                if (lastStoredThumb != null && !lastStoredThumb.isRecycled()) {
                    lastStoredThumb.recycle();
                }
                lastStoredThumb = thumb.copy(thumb.getConfig(), false);
            }

            if (mode.useCompression) {
                addFrameToDisk(bitmap);
                bitmap.recycle();
            } else {
                long nowMs = System.currentTimeMillis();
                bitmapBuffer.add(bitmap);
                timestampBuffer.add(nowMs);
                // Time-based eviction: discard frames older than delaySeconds so the
                // display head always reflects the correct wall-clock delay.
                long evictBefore = nowMs - (long)(mode.delaySeconds * 1000);
                while (!timestampBuffer.isEmpty() && timestampBuffer.peek() < evictBefore) {
                    Bitmap old = bitmapBuffer.poll();
                    if (old != null && !old.isRecycled()) old.recycle();
                    timestampBuffer.poll();
                }
            }

            // Perform motion detection using the thumbnail
            synchronized (this) {
                if (previousThumb != null && !previousThumb.isRecycled()) {
                    // Calculate motion score from difference between current and previous thumbnail
                    float motionScore = calculateMotionScore(thumb, previousThumb);
                    motionDetected = motionScore >= MOTION_FRACTION_THRESHOLD;
                    lastMotionScore = motionScore;
                } else {
                    // First frame - no previous thumb to compare to
                    motionDetected = false;
                    lastMotionScore = 0.0f;
                }
                
                // Update previous thumb for next frame
                if (previousThumb != null && !previousThumb.isRecycled()) {
                    previousThumb.recycle();
                }
                previousThumb = thumb.copy(thumb.getConfig(), false);
            }

            if (frameCount % 30 == 0) {
                int size = mode.useCompression ? fileBuffer.size() : bitmapBuffer.size();
                Log.d(TAG, "Buffer: " + size + " frames");
            }

            return thumb;
        } catch (Exception e) {
            Log.e(TAG, "Error adding frame", e);
            return null;
        }
    }

    /**
     * Returns a guaranteed deep copy of the most recently stored thumbnail. 
     * Caller is responsible for recycling the returned bitmap.
     */
    public synchronized Bitmap getLatestThumbnail() {
        if (lastStoredThumb == null || lastStoredThumb.isRecycled()) return null;
        return lastStoredThumb.copy(lastStoredThumb.getConfig(), false);
    }

    private void addFrameToDisk(Bitmap bitmap) {
        long captureTimeMs = System.currentTimeMillis();
        String filename = FILE_FMT.format(new Date(captureTimeMs)) + ".jpg";
        File file = new File(sessionDir, filename);

        // File write outside lock — this is the slow part (50-100 ms JPEG encode).
        // The display thread may call getDelayedFrame() concurrently; it only touches
        // the queue under its own synchronized block, so no conflict here.
        try (FileOutputStream fos = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos);
        } catch (IOException e) {
            Log.e(TAG, "Error writing frame to disk", e);
            return;
        }

        // Queue operations under lock — shared with getDelayedFrame() on display thread.
        // Time-based eviction: discard frames older than delaySeconds so the display
        // head always reflects the correct wall-clock delay, even when actual frame
        // storage rate is lower than bufferFps (e.g., due to JPEG encode overhead).
        synchronized (this) {
            fileBuffer.add(file);
            diskTimestampBuffer.add(captureTimeMs);
            long evictBefore = captureTimeMs - (long)(mode.delaySeconds * 1000);
            while (!diskTimestampBuffer.isEmpty() && diskTimestampBuffer.peek() < evictBefore) {
                File old = fileBuffer.poll();
                if (old != null) old.delete();
                diskTimestampBuffer.poll();
            }
        }
    }

    public Bitmap getDelayedFrame() {
        if (released) return null;
        if (mode.useCompression) {
            // Snapshot head file and check cache under lock (fast — no I/O).
            // Must be synchronized against addFrameToDisk() which evicts from the same deques.
            File file;
            synchronized (this) {
                file = fileBuffer.peek();
                if (file != null && file.equals(cachedFile)
                        && cachedFrame != null && !cachedFrame.isRecycled()) {
                    return cachedFrame; // cache hit — already decoded, no disk read needed
                }
            }
            if (file == null) return null;

            // Cache miss — decode outside lock (50-200 ms; must not block camera thread).
            Bitmap decoded = BitmapFactory.decodeFile(file.getAbsolutePath());

            synchronized (this) {
                if (decoded != null) {
                    if (cachedFrame != null && !cachedFrame.isRecycled()) cachedFrame.recycle();
                    cachedFrame = decoded;
                    cachedFile = file;
                }
                // Return cached frame even if decode failed (file may have been evicted mid-decode).
                return cachedFrame;
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
    public synchronized long getCurrentFrameTimestamp() {
        Long ts;
        if (mode.useCompression) {
            ts = diskTimestampBuffer.peek();
        } else {
            ts = timestampBuffer.peek();
        }
        return ts != null ? ts : 0;
    }

    /**
     * Calculate motion score by comparing pixels between current and previous thumbnails
     * @param currentThumb the current thumbnail bitmap
     * @param previousThumb the previous thumbnail bitmap
     * @return fraction of pixels with luma difference exceeding threshold
     */
    private float calculateMotionScore(Bitmap currentThumb, Bitmap previousThumb) {
        if (currentThumb == null || previousThumb == null) return 0.0f;
        
        int width = currentThumb.getWidth();
        int height = currentThumb.getHeight();
        int totalPixels = width * height;
        int changedPixels = 0;
        
        // Using a simple scan approach for better performance
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int currentPixel = currentThumb.getPixel(x, y);
                int previousPixel = previousThumb.getPixel(x, y);
                
                // Convert to luma using fast approximation: (r * 77 + g * 150 + b * 29) >> 8
                int currentR = (currentPixel >> 16) & 0xFF;
                int currentG = (currentPixel >> 8) & 0xFF;
                int currentB = currentPixel & 0xFF;
                
                int previousR = (previousPixel >> 16) & 0xFF;
                int previousG = (previousPixel >> 8) & 0xFF;
                int previousB = previousPixel & 0xFF;
                
                // Calculate luma for both pixels
                int currentLuma = (currentR * 77 + currentG * 150 + currentB * 29) >> 8;
                int previousLuma = (previousR * 77 + previousG * 150 + previousB * 29) >> 8;
                
                // Calculate absolute difference
                int delta = Math.abs(currentLuma - previousLuma);
                if (delta >= LUMA_DELTA_THRESHOLD) {
                    changedPixels++;
                }
            }
        }

        return (float) changedPixels / totalPixels;
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
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
        released = true; // stop addFrame() ASAP if camera thread is still running
        if (mode.useCompression) {
            for (File f : fileBuffer) {
                if (f != null) f.delete();
            }
            fileBuffer.clear();
            diskTimestampBuffer.clear();
            if (cachedFrame != null && !cachedFrame.isRecycled()) cachedFrame.recycle();
            cachedFrame = null;
            cachedFile = null;
            // Remove session directory (succeeds if empty after deletes above)
            if (sessionDir != null) sessionDir.delete();
        } else {
            for (Bitmap b : bitmapBuffer) {
                if (b != null && !b.isRecycled()) b.recycle();
            }
            bitmapBuffer.clear();
            timestampBuffer.clear();
        }
        
        synchronized (this) {
            if (lastStoredThumb != null && !lastStoredThumb.isRecycled()) {
                lastStoredThumb.recycle();
            }
            lastStoredThumb = null;
            
            if (previousThumb != null && !previousThumb.isRecycled()) {
                previousThumb.recycle();
            }
            previousThumb = null;
        }

        Log.d(TAG, "FrameBuffer released");
    }
}