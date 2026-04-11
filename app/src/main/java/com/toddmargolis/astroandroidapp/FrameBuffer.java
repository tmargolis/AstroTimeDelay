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
    private static final float MOTION_FRACTION_THRESHOLD = 0.005f;

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
    private long lastStoredFrameMs = 0; // used when mode.storageIntervalMs > 0

    // Checkpoint support for persistence
    private static final String CHECKPOINT_FILE_NAME = "framebuffer_checkpoint.dat";
    private File checkpointFile;

    public FrameBuffer(Context context, CelestialMode mode, int thumbW, int thumbH) {
        this.mode = mode;
        this.thumbW = thumbW;
        this.thumbH = thumbH;
        this.frameSkip = Math.max(1, mode.targetFps / mode.bufferFps);
        int maxFrames = (int) (mode.delaySeconds * mode.bufferFps) + 1;
        this.emaAlpha = mode.emaAlpha;

        if (mode.useCompression) {
            // Check for existing checkpoint files to restore previous session
            String startTime = FOLDER_FMT.format(new Date());
            File baseDir = context.getExternalFilesDir(null);
            if (baseDir == null) baseDir = context.getFilesDir(); // fallback to internal
            
            // Look for existing sessions for this mode
            File[] existingSessions = findExistingSessions(baseDir, mode.name);
            File restoreSessionDir = null;
            
            if (existingSessions != null && existingSessions.length > 0) {
                // Use the most recent session; delete any older ones to prevent accumulation
                restoreSessionDir = existingSessions[0];
                Log.d(TAG, "Found existing session to restore: " + restoreSessionDir.getAbsolutePath());
                for (int i = 1; i < existingSessions.length; i++) {
                    deleteDirectory(existingSessions[i]);
                    Log.d(TAG, "Deleted old session: " + existingSessions[i].getName());
                }
            }
            
            if (restoreSessionDir != null) {
                // Restore from existing session directory
                sessionDir = restoreSessionDir;
                checkpointFile = new File(sessionDir, CHECKPOINT_FILE_NAME);
                
                // Initialize buffers by scanning existing files
                restoreFromExistingSession();
            } else {
                // Create new session directory
                String sessionName = mode.name + "_" + startTime;
                sessionDir = new File(baseDir, "AstroTimeDelay/" + sessionName);
                sessionDir.mkdirs();
                
                // Create checkpoint file for this session
                checkpointFile = new File(sessionDir, CHECKPOINT_FILE_NAME);
                
                fileBuffer = new ArrayDeque<>();
                diskTimestampBuffer = new ArrayDeque<>();
            }
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
    
    /**
     * Find existing sessions for a given mode name, sorted by most recent
     */
    private File[] findExistingSessions(File baseDir, String modeName) {
        try {
            File astroDir = new File(baseDir, "AstroTimeDelay");
            if (!astroDir.exists()) return null;
            
            File[] sessions = astroDir.listFiles((dir, name) -> 
                name.startsWith(modeName + "_"));
            
            if (sessions != null && sessions.length > 0) {
                // Sort by modification time - most recent first
                java.util.Arrays.sort(sessions, (a, b) -> {
                    long timeA = a.lastModified();
                    long timeB = b.lastModified();
                    return Long.compare(timeB, timeA); // Most recent first
                });
                return sessions;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error finding existing sessions", e);
        }
        return null;
    }
    
    /**
     * Restore buffer state from an existing session directory
     */
    private void restoreFromExistingSession() {
        try {
            if (sessionDir == null || !sessionDir.exists()) return;
            
            fileBuffer = new ArrayDeque<>();
            diskTimestampBuffer = new ArrayDeque<>();
            
            // Get list of all JPEG files in the session directory, sorted by name
            File[] jpegFiles = sessionDir.listFiles((dir, name) -> 
                name.toLowerCase().endsWith(".jpg"));
            
            if (jpegFiles != null) {
                java.util.Arrays.sort(jpegFiles, (a, b) -> a.getName().compareTo(b.getName()));
                
                // Add all files to buffer (will be evicted properly based on timestamps)
                for (File file : jpegFiles) {
                    if (file.isFile()) {
                        long timestamp = extractTimestampFromFile(file);
                        if (timestamp > 0) {
                            fileBuffer.add(file);
                            diskTimestampBuffer.add(timestamp);
                        }
                    }
                }
                
                Log.d(TAG, "Restored " + fileBuffer.size() + " frames from existing session");

                // Seed lastStoredFrameMs from the newest frame so we don't store again immediately
                if (!diskTimestampBuffer.isEmpty()) {
                    Long newest = ((ArrayDeque<Long>) diskTimestampBuffer).peekLast();
                    if (newest != null) lastStoredFrameMs = newest;
                }
            }

            // Check if there's a valid checkpoint to determine the starting point
            long checkpointTimestamp = getCheckpointTimestamp();
            if (checkpointTimestamp > 0) {
                Log.d(TAG, "Using checkpoint timestamp: " + checkpointTimestamp);
                // Set the first frame time based on checkpoint for proper delay calculation
                this.firstFrameTimeMs = checkpointTimestamp;
            } else {
                Log.d(TAG, "No checkpoint found in restored session");
                // If no checkpoint and we have frames, use the oldest frame as start time
                if (!diskTimestampBuffer.isEmpty()) {
                    Long oldest = diskTimestampBuffer.peek();
                    if (oldest != null) {
                        this.firstFrameTimeMs = oldest;
                        Log.d(TAG, "Using oldest frame timestamp as start time: " + firstFrameTimeMs);
                    }
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error restoring from existing session", e);
            // If restoration fails, fall back to empty buffers
            fileBuffer = new ArrayDeque<>();
            diskTimestampBuffer = new ArrayDeque<>();
        }
    }
    
    /**
     * Extract timestamp from filename
     */
    private long extractTimestampFromFile(File file) {
        try {
            String filename = file.getName();
            int dotIndex = filename.lastIndexOf('.');
            if (dotIndex > 0) {
                String timestampPart = filename.substring(0, dotIndex);
                Date date = FILE_FMT.parse(timestampPart);
                if (date != null) {
                    return date.getTime();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing timestamp from file: " + file.getName(), e);
        }
        return 0;
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
            boolean shouldStore;
            if (mode.storageIntervalMs > 0) {
                long nowMs = System.currentTimeMillis();
                shouldStore = (frameCount == 0) || (nowMs - lastStoredFrameMs >= mode.storageIntervalMs);
                if (shouldStore) lastStoredFrameMs = nowMs;
            } else {
                skipCounter++;
                shouldStore = (skipCounter >= frameSkip);
                if (shouldStore) skipCounter = 0;
            }

            // Always decode and return a thumbnail for the very first camera frame,
            // regardless of frameSkip. For Saturn (frameSkip=24), this means the ghost
            // stack seeds immediately instead of waiting ~1 second for the first stored frame.
            // On all subsequent non-stored frames, skip decoding entirely (return null).
            boolean isFirstFrame = (frameCount == 0);
            if (!shouldStore && !isFirstFrame) return null;

            Bitmap bitmap = imageProxyToBitmap(imageProxy);
            if (bitmap == null) return null;

            frameCount++;
            // Only set firstFrameTimeMs from live camera if not already restored from disk
            if (frameCount == 1 && firstFrameTimeMs == 0) firstFrameTimeMs = System.currentTimeMillis();

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
            
            // Save checkpoint regularly to maintain persistence
            saveCheckpoint();
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
            // Clear in-memory state only — leave JPEG files on disk so the next
            // session can restore the barcode and buffer from them.
            fileBuffer.clear();
            diskTimestampBuffer.clear();
            if (cachedFrame != null && !cachedFrame.isRecycled()) cachedFrame.recycle();
            cachedFrame = null;
            cachedFile = null;
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
    
    /**
     * Returns a snapshot of the timestamps currently in the disk buffer, oldest first.
     * Only meaningful for disk-based modes (Sun/Saturn).
     */
    public synchronized long[] getBufferedTimestamps() {
        if (!mode.useCompression || diskTimestampBuffer == null) return new long[0];
        long[] result = new long[diskTimestampBuffer.size()];
        int i = 0;
        for (long ts : diskTimestampBuffer) result[i++] = ts;
        return result;
    }

    /**
     * Returns a snapshot of the files currently in the disk buffer, oldest first.
     * Only meaningful for disk-based modes (Sun/Saturn).
     */
    public synchronized File[] getBufferedFiles() {
        if (!mode.useCompression || fileBuffer == null) return new File[0];
        return fileBuffer.toArray(new File[0]);
    }

    /**
     * Clears all buffered frames and resets state, as if the session just started.
     * For disk modes: deletes all JPEG files and the checkpoint from the session directory.
     * For RAM mode: recycles all bitmaps.
     */
    public synchronized void clearAllFrames() {
        if (mode.useCompression) {
            fileBuffer.clear();
            diskTimestampBuffer.clear();
            if (cachedFrame != null && !cachedFrame.isRecycled()) cachedFrame.recycle();
            cachedFrame = null;
            cachedFile = null;
            // Delete all files in the session directory (JPEGs + checkpoint)
            if (sessionDir != null && sessionDir.exists()) {
                File[] files = sessionDir.listFiles();
                if (files != null) {
                    for (File f : files) f.delete();
                }
            }
        } else {
            for (Bitmap b : bitmapBuffer) {
                if (b != null && !b.isRecycled()) b.recycle();
            }
            bitmapBuffer.clear();
            timestampBuffer.clear();
        }
        if (lastStoredThumb != null && !lastStoredThumb.isRecycled()) lastStoredThumb.recycle();
        lastStoredThumb = null;
        if (previousThumb != null && !previousThumb.isRecycled()) previousThumb.recycle();
        previousThumb = null;
        firstFrameTimeMs = 0;
        frameCount = 0;
        skipCounter = 0;
        motionDetected = false;
        lastMotionScore = 0f;
        Log.d(TAG, "FrameBuffer cleared");
    }

    /** Recursively delete a directory and all its contents. */
    private void deleteDirectory(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) child.delete();
        }
        dir.delete();
    }

    /**
     * Save checkpoint information for persistence
     */
    private void saveCheckpoint() {
        // Only do this for disk-based modes (Sun/Saturn) to avoid unnecessary overhead
        if (!mode.useCompression) {
            return;
        }
        
        try {
            long headTimestamp = getCurrentFrameTimestamp();
            if (headTimestamp > 0) {
                // Write timestamp of first frame in buffer as checkpoint - this preserves our position
                Log.d(TAG, "Saving checkpoint: head timestamp=" + headTimestamp);
                
                // Write the timestamp to our checkpoint file
                java.io.FileWriter writer = new java.io.FileWriter(checkpointFile);
                writer.write(String.valueOf(headTimestamp));
                writer.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error saving checkpoint", e);
        }
    }

    /**
     * Check if there's a saved checkpoint and return timestamp information for recovery
     * @return timestamp of checkpointed head frame, or 0 if no checkpoint exists
     */
    public long getCheckpointTimestamp() {
        if (!mode.useCompression) {
            return 0;
        }
        
        try {
            if (checkpointFile.exists()) {
                java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(checkpointFile));
                String line = reader.readLine();
                reader.close();
                
                if (line != null && !line.isEmpty()) {
                    return Long.parseLong(line);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading checkpoint", e);
        }

        return 0;
    }
}