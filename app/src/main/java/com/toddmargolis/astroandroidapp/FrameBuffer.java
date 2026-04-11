// Copyright (c) 2025 Todd Margolis. All rights reserved.
// Delayed Vision - Cosmically Inspired Video Mirrors. See LICENSE.md for terms.
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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Stores time-delayed camera frames and provides the oldest frame on demand.
 *
 * Each mode uses one of two storage strategies:
 *   - RAM (useCompression=false): raw Bitmaps in an ArrayDeque, evicted by timestamp.
 *     Only used as a fallback; all current modes use disk storage.
 *   - Disk (useCompression=true): JPEG files in app-private external storage, one file
 *     per stored frame, evicted when older than mode.delaySeconds. Using getExternalFilesDir()
 *     instead of MediaStore avoids the media scanner, which caused multi-minute freezes
 *     when thousands of buffer files triggered a scanner backlog at mode transitions.
 *
 * Proxima Centauri is a special case: storageIntervalMs > 0 activates a separate path
 * (handleProximaFrame) that samples at 1fps, scores each second for motion, and promotes
 * only the highest-motion frame from each 4-hour window to a permanent file.
 *
 * Session persistence: on construction, any existing session directory for the current
 * mode is found and its files are reloaded into the buffer, so the delay continues across
 * app restarts without losing the accumulated history.
 *
 * Threading: addFrame() runs on the camera thread; getDelayedFrame() runs on the display
 * thread. Shared state (fileBuffer, diskTimestampBuffer, motion fields) is guarded by
 * synchronized blocks.
 */
public class FrameBuffer {
    private static final String TAG = "FrameBuffer";

    /** JPEG compression quality for all stored frames. 60% gives good visual quality at
     *  roughly ⅓ the file size of a raw bitmap — acceptable for a time-delay art installation. */
    private static final int JPEG_QUALITY = 60;

    // Proxima Centauri: sample rate for the motion-scoring pipeline
    private static final long PROXIMA_SAMPLE_INTERVAL_MS = 1000L; // 1 sample per second

    // Motion detection thresholds.
    // LUMA_DELTA_THRESHOLD: a pixel is "changed" if its luma shifts by more than this (0–255 scale).
    //   18 ≈ 7% of full range — ignores minor noise/compression artefacts while catching real motion.
    // MOTION_FRACTION_THRESHOLD: motion is "detected" when this fraction of pixels changed.
    //   0.005 = 0.5% of pixels — sensitive enough to catch a person entering frame.
    private static final int LUMA_DELTA_THRESHOLD = 18;
    private static final float MOTION_FRACTION_THRESHOLD = 0.005f;

    private final CelestialMode mode;

    // Thumbnail dimensions passed in from CameraActivity (screenW/4, 16:9).
    // All thumbnails returned by addFrame() use these dimensions.
    private final int thumbW;
    private final int thumbH;
    private final float emaAlpha; // passed through to CameraActivity for EMA stack init

    // -------------------------------------------------------------------------
    // RAM storage path (fallback — not used by any current mode)
    // -------------------------------------------------------------------------
    private ArrayDeque<Bitmap> bitmapBuffer;
    private ArrayDeque<Long> timestampBuffer;

    // -------------------------------------------------------------------------
    // Disk storage path (Sun, Saturn, Moon, Proxima Centauri)
    // -------------------------------------------------------------------------
    private ArrayDeque<File> fileBuffer;           // JPEG files in chronological order
    private ArrayDeque<Long> diskTimestampBuffer;  // capture timestamps matching fileBuffer
    private File sessionDir;   // e.g. AstroTimeDelay/Moon_2025-04-11_10-00-00/

    // Decoded-frame cache: avoids re-decoding the same JPEG on every display thread tick.
    // Only invalidated when fileBuffer.peek() advances to a new file.
    private Bitmap cachedFrame;
    private File cachedFile;

    private Bitmap lastStoredThumb; // deep copy of the most recent stored frame's thumbnail,
                                    // used by getLatestThumbnail() for presence detection

    // -------------------------------------------------------------------------
    // Motion detection state (updated on every stored frame; read by CameraActivity)
    // -------------------------------------------------------------------------
    private Bitmap previousThumb;          // thumbnail from the prior stored frame
    private boolean motionDetected = false;
    private float lastMotionScore = 0.0f;

    // Date format for session directory names (human-readable, filesystem-safe).
    private static final SimpleDateFormat FOLDER_FMT =
            new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US);

    // Date format for individual frame filenames. Used both when writing (addFrameToDisk,
    // writeProximaCandidate) and when reading back (extractTimestampFromFile).
    // Must sort lexicographically in chronological order — this format does.
    private static final SimpleDateFormat FILE_FMT =
            new SimpleDateFormat("yyyy.MM.dd.HH.mm.ss.SSS", Locale.US);

    private volatile boolean released = false; // set in release() to stop addFrame() ASAP

    // frameSkip: store 1 out of every N camera frames (N = targetFps / bufferFps).
    // skipCounter: counts frames since last stored frame.
    private int frameSkip;
    private int skipCounter = 0;

    private long frameCount = 0;       // total frames passed through addFrame() (stored + skipped)
    private long firstFrameTimeMs = 0; // wall-clock time of the very first stored frame;
                                       // used by CameraActivity to compute elapsed time for phase transition

    private long lastStoredFrameMs = 0; // wall-clock time of the last stored frame;
                                        // only used by the old storageIntervalMs path (now replaced by Proxima handler)

    // -------------------------------------------------------------------------
    // Proxima Centauri: best-frame selection state
    // (only active when mode.storageIntervalMs > 0)
    // -------------------------------------------------------------------------
    private long lastProximaSampleMs = 0;
    private Bitmap proximaRefThumb = null;    // previous 1-second sample thumbnail for motion comparison
    private long proximaWindowStartMs = 0;   // wall-clock start of the current storageIntervalMs window
    private List<File> proximaCandidates = new ArrayList<>();
    private File proximaCandidatesDir = null; // sessionDir/candidates/ — temp files for current window

    // -------------------------------------------------------------------------
    // Session persistence
    // -------------------------------------------------------------------------
    private static final String CHECKPOINT_FILE_NAME = "framebuffer_checkpoint.dat";
    private File checkpointFile; // records the timestamp of the buffer head for crash recovery

    /**
     * Constructs a FrameBuffer for the given mode.
     *
     * For disk-based modes, looks for an existing session directory on disk and restores
     * it if found (so the delay continues across app restarts). If no existing session is
     * found, creates a fresh directory. Only the most recent session is kept; older ones
     * are deleted to prevent unbounded disk accumulation.
     *
     * @param context Used to resolve getExternalFilesDir() for the session directory.
     * @param mode    The active celestial body configuration.
     * @param thumbW  Width of thumbnails returned by addFrame() — matches CameraActivity's miniW.
     * @param thumbH  Height of thumbnails returned by addFrame() — matches CameraActivity's miniH.
     */
    public FrameBuffer(Context context, CelestialMode mode, int thumbW, int thumbH) {
        this.mode = mode;
        this.thumbW = thumbW;
        this.thumbH = thumbH;
        this.frameSkip = Math.max(1, mode.targetFps / mode.bufferFps);
        // maxFrames is informational only — used in the log below; actual eviction is time-based
        int maxFrames = (int) (mode.delaySeconds * mode.bufferFps) + 1;
        this.emaAlpha = mode.emaAlpha;

        if (mode.useCompression) {
            String startTime = FOLDER_FMT.format(new Date());
            File baseDir = context.getExternalFilesDir(null);
            if (baseDir == null) baseDir = context.getFilesDir(); // fallback to internal storage

            // Find any existing session directories for this mode (e.g. from a previous launch).
            File[] existingSessions = findExistingSessions(baseDir, mode.name);
            File restoreSessionDir = null;

            if (existingSessions != null && existingSessions.length > 0) {
                // Keep only the most recent session; delete the rest to free disk space.
                restoreSessionDir = existingSessions[0];
                Log.d(TAG, "Found existing session to restore: " + restoreSessionDir.getAbsolutePath());
                for (int i = 1; i < existingSessions.length; i++) {
                    deleteDirectory(existingSessions[i]);
                    Log.d(TAG, "Deleted old session: " + existingSessions[i].getName());
                }
            }

            if (restoreSessionDir != null) {
                // Resume the existing session: scan its JPEG files back into the buffer.
                sessionDir = restoreSessionDir;
                checkpointFile = new File(sessionDir, CHECKPOINT_FILE_NAME);
                restoreFromExistingSession();
            } else {
                // Fresh start: create a new timestamped session directory.
                String sessionName = mode.name + "_" + startTime;
                sessionDir = new File(baseDir, "AstroTimeDelay/" + sessionName);
                sessionDir.mkdirs();

                // Proxima: create candidates/ subdir and record window start time
                if (mode.storageIntervalMs > 0) {
                    proximaCandidatesDir = new File(sessionDir, "candidates");
                    proximaCandidatesDir.mkdirs();
                    proximaWindowStartMs = System.currentTimeMillis();
                }

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
     * Scans AstroTimeDelay/ for directories matching "ModeName_*", sorted most-recent first.
     * Returns null if the AstroTimeDelay directory doesn't exist yet.
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
     * Reloads an existing session directory into the buffer queues after an app restart.
     *
     * Scans sessionDir for JPEG files (direct children only — candidates/ subdir is excluded
     * because listFiles() with the .jpg filter returns files, not subdirectories), parses each
     * filename for its capture timestamp, and enqueues them in chronological order.
     *
     * firstFrameTimeMs is set from the checkpoint file if present, otherwise from the oldest
     * frame's timestamp — this is what CameraActivity uses to decide whether to enter playback.
     */
    private void restoreFromExistingSession() {
        try {
            if (sessionDir == null || !sessionDir.exists()) return;

            fileBuffer = new ArrayDeque<>();
            diskTimestampBuffer = new ArrayDeque<>();

            // Scan direct JPEG children of sessionDir (sorted lexicographically = chronologically
            // because the FILE_FMT timestamp format sorts correctly as a string).
            File[] jpegFiles = sessionDir.listFiles((dir, name) ->
                name.toLowerCase().endsWith(".jpg"));

            if (jpegFiles != null) {
                java.util.Arrays.sort(jpegFiles, (a, b) -> a.getName().compareTo(b.getName()));

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

                // Seed lastStoredFrameMs from the newest frame so addFrameToDisk() doesn't
                // immediately write a duplicate on the very next camera frame.
                if (!diskTimestampBuffer.isEmpty()) {
                    Long newest = ((ArrayDeque<Long>) diskTimestampBuffer).peekLast();
                    if (newest != null) lastStoredFrameMs = newest;
                }
            }

            // Proxima: restore in-progress candidates from the candidates/ subdir so a
            // crash mid-window continues accumulating rather than restarting from scratch.
            if (mode.storageIntervalMs > 0) {
                proximaCandidatesDir = new File(sessionDir, "candidates");
                if (proximaCandidatesDir.exists()) {
                    File[] candidateFiles = proximaCandidatesDir.listFiles(
                            (dir, name) -> name.toLowerCase().endsWith(".jpg"));
                    if (candidateFiles != null) {
                        java.util.Arrays.sort(candidateFiles,
                                (a, b) -> a.getName().compareTo(b.getName()));
                        for (File f : candidateFiles) {
                            if (f.isFile()) proximaCandidates.add(f);
                        }
                        Log.d(TAG, "Restored " + proximaCandidates.size()
                                + " Proxima candidates from disk");
                    }
                } else {
                    proximaCandidatesDir.mkdirs();
                }
                // Continue the window timer from now (better than an arbitrary past time)
                proximaWindowStartMs = System.currentTimeMillis();
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
     * Parses the capture timestamp from a JPEG filename.
     * Handles both plain frames ("yyyy.MM.dd.HH.mm.ss.SSS.jpg") and
     * Proxima winner frames ("yyyy.MM.dd.HH.mm.ss.SSS_m=X.XXXXX.jpg").
     * Returns 0 if the name cannot be parsed (file is skipped during restore).
     */
    private long extractTimestampFromFile(File file) {
        try {
            String base = file.getName();
            // Strip .jpg extension
            if (base.endsWith(".jpg")) base = base.substring(0, base.length() - 4);
            // Strip _m=... score suffix present in Proxima winner filenames
            int mIdx = base.indexOf("_m=");
            if (mIdx >= 0) base = base.substring(0, mIdx);
            // Parse the remaining timestamp string
            Date date = FILE_FMT.parse(base);
            if (date != null) return date.getTime();
        } catch (Exception e) {
            Log.e(TAG, "Error parsing timestamp from file: " + file.getName(), e);
        }
        return 0;
    }

    /** Wall-clock time of the first stored frame. Used by CameraActivity to compute
     *  elapsed time and determine when to transition from queuing to playback. */
    public long getFirstFrameTimeMs() { return firstFrameTimeMs; }

    /** EMA blending coefficient, passed through from CelestialMode for the ghost stack init. */
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
            // Proxima Centauri: delegate entirely to best-frame selection handler.
            // This short-circuits all frameSkip / shouldStore / addFrameToDisk logic below.
            if (mode.storageIntervalMs > 0) {
                return handleProximaFrame(imageProxy);
            }

            boolean shouldStore;
            skipCounter++;
            shouldStore = (skipCounter >= frameSkip);
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
                Log.d(TAG, "Buffer size: " + size + " frames");
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

    /**
     * Compresses bitmap as JPEG and appends it to the disk buffer.
     *
     * The JPEG write happens outside the synchronized block because it is slow (50–100 ms
     * per frame at 1920×1080). Only the queue operations that follow are locked, keeping
     * the camera thread responsive to the display thread's concurrent getDelayedFrame() calls.
     *
     * Time-based eviction runs after each write: frames older than delaySeconds are deleted
     * from disk and dequeued. This ensures the buffer head always represents the correct
     * wall-clock delay, even when actual encode throughput is below bufferFps.
     */
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

    // -------------------------------------------------------------------------
    // Proxima Centauri: best-frame selection methods
    // -------------------------------------------------------------------------

    /**
     * Called for every camera frame in Proxima mode (storageIntervalMs > 0).
     * Samples at 1fps, scores motion vs. the previous sample, writes candidates to disk.
     * At the end of each storageIntervalMs window, promotes the highest-scoring candidate
     * to a permanent frame in sessionDir and deletes all losers.
     */
    private Bitmap handleProximaFrame(ImageProxy imageProxy) {
        long nowMs = System.currentTimeMillis();
        boolean isFirst = (frameCount == 0);
        boolean sampleDue = isFirst || (nowMs - lastProximaSampleMs >= PROXIMA_SAMPLE_INTERVAL_MS);
        if (!sampleDue) return null;

        Bitmap bitmap = imageProxyToBitmap(imageProxy);
        if (bitmap == null) return null;

        frameCount++;
        if (frameCount == 1 && firstFrameTimeMs == 0) {
            firstFrameTimeMs = nowMs;
            proximaWindowStartMs = nowMs;
        }
        lastProximaSampleMs = nowMs;

        Bitmap thumb = Bitmap.createScaledBitmap(bitmap, thumbW, thumbH, true);

        // Compute motion score vs. previous 1-second sample (0 for the very first sample)
        float score = 0f;
        if (proximaRefThumb != null && !proximaRefThumb.isRecycled()) {
            score = calculateMotionScore(thumb, proximaRefThumb);
        }

        // Update motion / screen-dimming fields read by CameraActivity
        synchronized (this) {
            motionDetected = score >= MOTION_FRACTION_THRESHOLD;
            lastMotionScore = score;
            if (lastStoredThumb != null && !lastStoredThumb.isRecycled()) lastStoredThumb.recycle();
            lastStoredThumb = thumb.copy(thumb.getConfig(), false);
        }

        // Write candidate to candidates/ dir with score embedded in the filename
        if (proximaCandidatesDir != null) {
            File candidate = writeProximaCandidate(bitmap, nowMs, score);
            if (candidate != null) {
                proximaCandidates.add(candidate);
                Log.d(TAG, "Proxima sample: score=" + String.format(Locale.US, "%.5f", score)
                        + "  candidates=" + proximaCandidates.size()
                        + "  file=" + candidate.getName());
            }
        }
        bitmap.recycle();

        // Update reference thumbnail for the next second's comparison
        if (proximaRefThumb != null && !proximaRefThumb.isRecycled()) proximaRefThumb.recycle();
        proximaRefThumb = thumb.copy(thumb.getConfig(), false);

        // Check whether the window has elapsed; if so, pick the best candidate
        if (!isFirst && (nowMs - proximaWindowStartMs >= mode.storageIntervalMs)) {
            selectBestProximaFrame(nowMs);
        }

        return thumb;
    }

    /**
     * Writes bitmap as JPEG to candidates/ with timestamp and motion score in the filename.
     * Filename format: yyyy.MM.dd.HH.mm.ss.SSS_m=X.XXXXX.jpg
     */
    private File writeProximaCandidate(Bitmap bitmap, long captureMs, float score) {
        String timestamp = FILE_FMT.format(new Date(captureMs));
        String filename = timestamp + "_m=" + String.format(Locale.US, "%.5f", score) + ".jpg";
        File file = new File(proximaCandidatesDir, filename);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos);
            return file;
        } catch (IOException e) {
            Log.e(TAG, "Error writing Proxima candidate: " + filename, e);
            return null;
        }
    }

    /**
     * Called at end of each storageIntervalMs window. Finds the highest-scoring candidate,
     * moves it to sessionDir as a permanent frame, and deletes all other candidates.
     */
    private void selectBestProximaFrame(long nowMs) {
        if (proximaCandidates.isEmpty()) {
            proximaWindowStartMs = nowMs;
            Log.d(TAG, "Proxima window ended with no candidates; resetting window");
            return;
        }

        // Find the candidate with the highest motion score
        File winner = null;
        float bestScore = -1f;
        for (File candidate : proximaCandidates) {
            float score = extractScoreFromFilename(candidate.getName());
            if (score > bestScore) {
                bestScore = score;
                winner = candidate;
            }
        }

        Log.d(TAG, "Proxima window ended: winner=" + (winner != null ? winner.getName() : "none")
                + "  score=" + String.format(Locale.US, "%.5f", bestScore)
                + "  from " + proximaCandidates.size() + " candidates");

        // Move winner from candidates/ to sessionDir/
        File winnerDest = null;
        if (winner != null) {
            winnerDest = new File(sessionDir, winner.getName());
            if (!winner.renameTo(winnerDest)) {
                // renameTo fails across filesystems — fall back to copy + delete
                try {
                    copyFile(winner, winnerDest);
                    winner.delete();
                } catch (IOException e) {
                    Log.e(TAG, "Failed to move Proxima winner to sessionDir", e);
                    winnerDest = null;
                }
            }
        }

        // Delete all remaining candidate files (winner already moved; others are losers)
        for (File candidate : proximaCandidates) {
            if (candidate.exists()) candidate.delete();
        }
        proximaCandidates.clear();

        // Add winner to the playback buffer
        if (winnerDest != null) {
            long winnerTs = extractTimestampFromFile(winnerDest);
            if (winnerTs <= 0) winnerTs = nowMs;
            synchronized (this) {
                fileBuffer.add(winnerDest);
                diskTimestampBuffer.add(winnerTs);
                saveCheckpoint();
            }
            Log.d(TAG, "Proxima winner added to fileBuffer: " + winnerDest.getName());
        }

        // Reset for the next window
        proximaWindowStartMs = nowMs;
    }

    /**
     * Parses the _m=X.XXXXX motion score from a Proxima candidate or winner filename.
     * Returns 0f if the suffix is absent (e.g. legacy files without a score).
     */
    private float extractScoreFromFilename(String name) {
        int idx = name.indexOf("_m=");
        if (idx < 0) return 0f;
        try {
            String scoreStr = name.substring(idx + 3); // e.g. "0.04712.jpg"
            if (scoreStr.endsWith(".jpg")) scoreStr = scoreStr.substring(0, scoreStr.length() - 4);
            return Float.parseFloat(scoreStr);
        } catch (NumberFormatException e) {
            return 0f;
        }
    }

    /** Copies src to dst byte-for-byte. Used as fallback when File.renameTo() fails. */
    private void copyFile(File src, File dst) throws IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    /**
     * Returns the oldest buffered frame — the one that should be displayed now.
     *
     * For disk modes: decodes the JPEG at the head of fileBuffer. A one-entry cache
     * (cachedFrame / cachedFile) avoids re-decoding the same file on consecutive display
     * ticks when no new permanent frame has been promoted. Cache is invalidated when
     * fileBuffer.peek() returns a different file (i.e., addFrameToDisk evicted the head).
     *
     * The JPEG decode is intentionally outside the synchronized block — it takes 50–200 ms
     * and must not block the camera thread. The synchronized block only reads/writes the
     * cache pointers and buffer head, which are fast.
     *
     * For RAM mode: simply peeks the head Bitmap; no lock needed (ArrayDeque.peek is safe
     * as long as eviction also runs on the same thread, which it does in addFrame).
     *
     * @return The oldest buffered Bitmap, or null if the buffer is empty or released.
     *         The returned bitmap is owned by this class (do not recycle it externally).
     */
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

    /**
     * Converts a CameraX ImageProxy (RGBA_8888 format) to a Bitmap.
     *
     * CameraX may pad each row in the image buffer to align it to a hardware-friendly
     * boundary (e.g. 64-byte alignment). The padding is visible as extra pixels on the
     * right edge of each row. This method accounts for it:
     *
     *   rowStride     — bytes per row including padding (may be > pixelStride × width)
     *   pixelStride   — bytes per pixel (4 for RGBA_8888)
     *   rowPadding    — extra bytes per row: rowStride − (pixelStride × width)
     *
     * When rowPadding > 0, the Bitmap is created wide enough to hold the padded rows
     * (width + rowPadding/pixelStride), pixels are copied from the ByteBuffer directly,
     * and then the Bitmap is cropped back to the real frame width. When rowPadding == 0
     * (no padding — common on many devices), the extra allocation and crop are skipped.
     *
     * @return A Bitmap of exactly image.getWidth() × image.getHeight(), or null on error.
     */
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

    /**
     * Tears down this FrameBuffer. Must be called from CameraActivity.onDestroy().
     *
     * Sets the released flag immediately so any in-flight addFrame() call returns null
     * without touching cleared state. For disk modes, the JPEG files are intentionally
     * left on disk — they are the persistent session that the next launch will restore.
     * Only the in-memory queues and the decoded-frame cache are freed here.
     *
     * Thumbnail bitmaps (lastStoredThumb, previousThumb, proximaRefThumb) are recycled
     * under the lock because the display thread may be reading them concurrently.
     */
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

            if (proximaRefThumb != null && !proximaRefThumb.isRecycled()) {
                proximaRefThumb.recycle();
            }
            proximaRefThumb = null;
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
                    for (File f : files) {
                        if (f.isDirectory()) {
                            deleteDirectory(f); // e.g. candidates/
                        } else {
                            f.delete();
                        }
                    }
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
        if (proximaRefThumb != null && !proximaRefThumb.isRecycled()) proximaRefThumb.recycle();
        proximaRefThumb = null;
        firstFrameTimeMs = 0;
        frameCount = 0;
        skipCounter = 0;
        motionDetected = false;
        lastMotionScore = 0f;
        lastProximaSampleMs = 0;
        proximaWindowStartMs = 0;
        proximaCandidates.clear();
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
                // Write the timestamp of the buffer head to the checkpoint file.
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