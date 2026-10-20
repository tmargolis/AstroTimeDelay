// Copyright (c) 2025 Todd Margolis. All rights reserved.
// Delayed Vision - Cosmically Inspired Video Mirrors. See LICENSE.md for terms.
package com.toddmargolis.astroandroidapp;

import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Uploads high-motion Proxima Centauri frames to Google Drive (via the Apps Script receiver)
 * and sends an ntfy.sh push notification when a new motion event starts.
 *
 * Every sample scoring >= UPLOAD_THRESHOLD is uploaded. Only the first such sample of an
 * event also notifies, with a Click link to that frame in Drive. The motion score measures
 * change versus the previous 1-second sample, so the first frame over threshold is the one
 * showing the person arriving — the highest-scoring frame is often the empty scene just
 * after they leave. An event ends after REARM_QUIET_MS with no sample over threshold.
 *
 * Tuned against a 2-hour hallway capture (7069 samples): background noise stays <= 0.0005,
 * a person walking the hallway scores 0.03–0.15, close-ups and handling the tablet 0.3–0.9.
 * At 0.05 that capture had 160 qualifying frames; re-arming after 5s of quiet would have
 * sent 17 notifications, 30s sent 7 — single visits contain pauses longer than 5s.
 *
 * Threading: onSample() runs on the camera thread and only updates debounce state and
 * enqueues work. All file reads and network I/O run on a single background thread with a
 * bounded queue, so a slow or offline network drops frames instead of stalling the camera
 * or growing memory.
 *
 * Configured from local.properties via BuildConfig (alert.uploadUrl, alert.secret,
 * alert.ntfyTopic). createIfConfigured() returns null — alerts disabled — when the upload
 * URL or secret is blank. A blank ntfy topic uploads to Drive without notifying.
 */
class MotionAlerter {
    private static final String TAG = "MotionAlerter";

    static final float UPLOAD_THRESHOLD = 0.05f;
    static final long REARM_QUIET_MS = 30_000L;

    private static final int MAX_PENDING_UPLOADS = 20;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 60_000; // Apps Script cold starts can take several seconds

    private final String uploadUrl;
    private final String secret;
    private final String ntfyTopic;
    private final String modeName;
    private final ThreadPoolExecutor executor;

    // Debounce state — camera thread only.
    private boolean armed = true;
    private long lastMotionMs = 0;

    static MotionAlerter createIfConfigured(String modeName) {
        if (BuildConfig.ALERT_UPLOAD_URL.isEmpty() || BuildConfig.ALERT_SECRET.isEmpty()) {
            Log.d(TAG, "Motion alerts disabled: alert.uploadUrl / alert.secret not set in local.properties");
            return null;
        }
        return new MotionAlerter(BuildConfig.ALERT_UPLOAD_URL, BuildConfig.ALERT_SECRET,
                BuildConfig.ALERT_NTFY_TOPIC, modeName);
    }

    private MotionAlerter(String uploadUrl, String secret, String ntfyTopic, String modeName) {
        this.uploadUrl = uploadUrl;
        this.secret = secret;
        this.ntfyTopic = ntfyTopic;
        this.modeName = modeName;
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_PENDING_UPLOADS),
                (task, pool) -> Log.w(TAG, "Upload queue full; dropping frame"));
    }

    /**
     * Called on the camera thread for each scored sample. frame may be null if the candidate
     * JPEG failed to write — the notification still goes out, without a Drive link.
     */
    void onSample(File frame, float score, long nowMs) {
        if (score < UPLOAD_THRESHOLD) {
            if (!armed && nowMs - lastMotionMs >= REARM_QUIET_MS) armed = true;
            return;
        }
        lastMotionMs = nowMs;
        boolean notify = armed;
        armed = false;
        executor.execute(() -> send(frame, score, nowMs, notify));
    }

    void shutdown() {
        executor.shutdownNow();
    }

    private void send(File frame, float score, long captureMs, boolean notify) {
        String driveUrl = frame != null ? uploadToDrive(frame, score) : null;
        if (notify && !ntfyTopic.isEmpty()) sendNotification(score, captureMs, driveUrl);
    }

    /** Returns the Drive file URL from the Apps Script response, or null on failure. */
    private String uploadToDrive(File frame, float score) {
        try {
            byte[] jpeg = readFrame(frame);
            if (jpeg == null) {
                Log.w(TAG, "Frame no longer on disk, skipping upload: " + frame.getName());
                return null;
            }
            JSONObject body = new JSONObject()
                    .put("secret", secret)
                    .put("image", Base64.encodeToString(jpeg, Base64.NO_WRAP))
                    .put("filename", frame.getName())
                    .put("mode", modeName)
                    .put("score", score);
            String response = post(uploadUrl, "application/json", body.toString(), null);
            if (response != null && response.startsWith("https://")) {
                Log.d(TAG, "Uploaded " + frame.getName() + " -> " + response);
                return response;
            }
            Log.w(TAG, "Upload rejected for " + frame.getName() + ": " + response);
        } catch (Exception e) {
            Log.w(TAG, "Upload failed for " + frame.getName(), e);
        }
        return null;
    }

    private void sendNotification(float score, long captureMs, String driveUrl) {
        String time = new SimpleDateFormat("h:mm:ss a", Locale.US).format(new Date(captureMs));
        String message = String.format(Locale.US, "Motion at %s (score %.3f)", time, score);
        String[][] headers = driveUrl != null
                ? new String[][]{{"Title", modeName + " motion"}, {"Tags", "camera"}, {"Click", driveUrl}}
                : new String[][]{{"Title", modeName + " motion"}, {"Tags", "camera"}};
        try {
            post("https://ntfy.sh/" + ntfyTopic, "text/plain; charset=utf-8", message, headers);
            Log.d(TAG, "Notification sent: " + message);
        } catch (Exception e) {
            Log.w(TAG, "Notification failed", e);
        }
    }

    /**
     * Reads the candidate JPEG. If it was the window winner, selectBestProximaFrame() may have
     * moved it from candidates/ up into the session directory before this upload ran.
     */
    private byte[] readFrame(File frame) throws IOException {
        File source = frame;
        if (!source.exists() && frame.getParentFile() != null) {
            source = new File(frame.getParentFile().getParentFile(), frame.getName());
        }
        return source.exists() ? Files.readAllBytes(source.toPath()) : null;
    }

    /**
     * POSTs body and returns the trimmed response text. Apps Script runs doPost() and then
     * answers 302 with the result at a googleusercontent.com URL; that redirect is followed
     * manually with a GET because HttpURLConnection won't re-follow a streamed POST body.
     */
    private String post(String url, String contentType, String body, String[][] headers) throws IOException {
        HttpURLConnection conn = open(url);
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", contentType);
            if (headers != null) {
                for (String[] h : headers) conn.setRequestProperty(h[0], h[1]);
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(bytes);
            }
            int code = conn.getResponseCode();
            String location = conn.getHeaderField("Location");
            if (code >= 300 && code < 400 && location != null) {
                HttpURLConnection redirect = open(location);
                try {
                    return readResponse(redirect);
                } finally {
                    redirect.disconnect();
                }
            }
            return readResponse(conn);
        } finally {
            conn.disconnect();
        }
    }

    private HttpURLConnection open(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        return conn;
    }

    private String readResponse(HttpURLConnection conn) throws IOException {
        int code = conn.getResponseCode();
        InputStream in = code < 400 ? conn.getInputStream() : conn.getErrorStream();
        if (in == null) return "HTTP " + code;
        try (InputStream stream = in; ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            int n;
            while ((n = stream.read(chunk)) != -1) buf.write(chunk, 0, n);
            String text = buf.toString("UTF-8").trim();
            return code < 400 ? text : "HTTP " + code + ": " + text;
        }
    }
}
