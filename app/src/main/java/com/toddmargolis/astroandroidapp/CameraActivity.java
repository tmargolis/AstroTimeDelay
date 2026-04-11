package com.toddmargolis.astroandroidapp;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.util.ArrayDeque;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class CameraActivity extends AppCompatActivity {
    private static final String TAG = "CameraActivity";

    private ImageView mainView;
    private CelestialMode mode;
    private FrameBuffer frameBuffer;
    private OverlayRenderer renderer;
    private ExecutorService cameraExecutor;
    private ProcessCameraProvider cameraProvider;
    private TextView modeIndicator;
    private TextView modeNameLabel;
    private LinearLayout bottomLabelRow;
    private TextView queuingLabel;
    private long lastDisplayUpdateMs = 0;
    private ExecutorService displayExecutor;
    private ImageView countdownOverlayView;

    // Presence Detection and Screen Dimming
    private long lastMovementTimeMs = System.currentTimeMillis();
    private boolean isDimmed = false;
    private static final long INACTIVITY_TIMEOUT_MS = 30000; // 30 seconds for testing
    private static final float BRIGHT_LEVEL = 1.0f;
    private static final float DIM_LEVEL = 0.05f;

    private volatile boolean isPlaybackPhase = false;
    private ImageView stackView;
    private float[] stackFloat;
    private Bitmap stackDisplay1, stackDisplay2;
    private boolean useDisplay1 = true;
    private int[] stackPixels;
    private float emaFade;
    private float emaAdd;
    private final float[] powCurve = new float[256];
    private int miniW, miniH;
    private boolean stackInitialized = false;
    private int emaUpdateEvery = 1;
    private int emaFrameCounter = 0;

    private ImageView barcodeView;
    private ImageView tickView;
    private int barcodeW, barcodeH;
    private float barcodeAccum = 0f;
    private long lastBarcodeFrameMs = 0;
    private volatile int barcodeFrameCount = 0;
    private Bitmap barcodeDisplay1, barcodeDisplay2;
    private Canvas barcodeCanvas1, barcodeCanvas2;
    private volatile boolean useBarcodeDisplay1 = true;
    private int[] slitPixels;
    private int[] rowAvgScratch;
    private float barcodeHistorySeconds;
    private volatile boolean barcodeHistoryLoading = false;
    private volatile boolean emaHistoryLoading = false;

    // Moon-mode barcode: thumbnail copies of each buffered frame, drawn as scaled tiles
    private ArrayDeque<Bitmap> moonBarcodeBuffer;
    private int maxMoonBarcodeFrames;
    private Paint moonBarcodePaint;
    private int moonBarcodeSlitWidth; // fixed px width per frame; keeps all slits equal-sized

    // Buffer-clear gesture: 10 taps in the top-right corner within 5 seconds
    private int clearTapCount = 0;
    private long clearTapWindowStartMs = 0;
    private static final int CLEAR_TAP_TARGET = 10;
    private static final long CLEAR_TAP_WINDOW_MS = 5000;
    private int screenW;

    // Checkpoint support
    private static final String PREFS_NAME = "AstroTimeDelayPrefs";
    private static final String CHECKPOINT_KEY_PREFIX = "checkpoint_";
    
    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private final Runnable timerRunnable = new Runnable() {
        @Override public void run() {
            updateTimeLabel();
            timerHandler.postDelayed(this, 100);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setFullscreen();

        mainView = findViewById(R.id.mainView);
        stackView = findViewById(R.id.stackView);
        barcodeView = findViewById(R.id.barcodeView);

        countdownOverlayView = findViewById(R.id.countdownOverlayView); // image overlay during countdown
        queuingLabel = findViewById(R.id.queuingLabel); // "QUEUEING" label
        tickView = findViewById(R.id.tickView); // ticks on barcode

        LinearLayout bottomLabelRow = findViewById(R.id.bottomLabelRow);
        modeNameLabel = findViewById(R.id.modeNameLabel); // Moon, Sun, Saturn eg "Moon / 1.3 second delay"
        modeIndicator = findViewById(R.id.modeIndicator); //  eg "04/10/2026 10:09:14.870"

        String modeString = getIntent().getStringExtra("MODE");
        if (modeString == null) modeString = "MOON";
        switch (modeString) {
            case "MOON": mode = CelestialMode.createMoonMode(); break;
            case "SUN": mode = CelestialMode.createSunMode(); break;
            case "SATURN": mode = CelestialMode.createSaturnMode(); break;
            case "PROXIMA_CENTAURI": mode = CelestialMode.createProximaCentauriMode(); break;
        }

        modeIndicator.setVisibility(View.VISIBLE);

        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(dm);
        screenW = dm.widthPixels;
        miniW = dm.widthPixels / 4;
        miniH = miniW * 9 / 16;
        int cornerH = miniH * 3 / 4;
        int cornerW = cornerH * 16 / 9;

        frameBuffer = new FrameBuffer(this, mode, miniW, miniH);
        renderer = new OverlayRenderer(this, mode);

        // Restore state if available for disk-based modes
        if (mode.useCompression) {
            restoreState();
        }

        countdownOverlayView.setImageResource(mode.overlayResourceId);
        if (mode.name.equals("Proxima Centauri")) {
            countdownOverlayView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        } else if (mode.name.equals("Saturn")) {
            countdownOverlayView.setScaleType(ImageView.ScaleType.CENTER);
        } else if (mode.name.equals("Moon")) {
            // Moon overlay is drawn at 50% of fit size in OverlayRenderer.
            // Constrain the queueing view to half the frame dimensions so it matches.
            countdownOverlayView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            FrameLayout.LayoutParams overlayLp = (FrameLayout.LayoutParams) countdownOverlayView.getLayoutParams();
            overlayLp.width  = mode.targetWidth  / 2;
            overlayLp.height = mode.targetHeight / 2;
            overlayLp.gravity = Gravity.CENTER;
            countdownOverlayView.setLayoutParams(overlayLp);
        } else {
            // Sun: fit centered (no cropping)
            countdownOverlayView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        }

        String nameText;
        switch (mode.name) {
            case "Sun": nameText = "Sun / 8 minute 20 second delay"; break;
            case "Saturn": nameText = "Saturn / 1.3 hour delay"; break;
            case "Proxima Centauri": nameText = "Proxima Centauri / 4.24 year delay"; break;
            default: nameText = "Moon / 1.3 second delay"; break;
        }
        modeNameLabel.setText(nameText);

        stackFloat = new float[miniW * miniH * 3];
        stackDisplay1 = Bitmap.createBitmap(miniW, miniH, Bitmap.Config.ARGB_8888);
        stackDisplay2 = Bitmap.createBitmap(miniW, miniH, Bitmap.Config.ARGB_8888);
        stackPixels = new int[miniW * miniH];

        float emaAlpha = frameBuffer.getEmaAlpha();
        emaUpdateEvery = Math.max(1, (int)Math.ceil(1.0 / (emaAlpha * 255)));
        float boostedAlpha = Math.min(0.99f, emaAlpha * 2.0f);
        emaFade = (float)Math.pow(1.0 - boostedAlpha, emaUpdateEvery);
        emaAdd  = emaUpdateEvery * boostedAlpha;

        for (int i = 0; i < 256; i++) {
            powCurve[i] = (float) (Math.pow(i / 255.0, 0.8) * 255.0);
        }

        barcodeW = dm.widthPixels;
        barcodeH = 120; // fills the 120px gap below the 16:9 ghost on Tab A9+ (1920×1200 screen)
        // Use the mode's declared barcode window, or fall back to delaySeconds.
        barcodeHistorySeconds = mode.barcodeHistorySeconds > 0 ? mode.barcodeHistorySeconds : mode.delaySeconds;
        // Fixed slit width for Moon: barcodeW / (window * 6fps) ≈ 32px at 1920/10s/6fps.
        // Using 6fps as the target display rate regardless of bufferFps.
        moonBarcodeSlitWidth = Math.max(1, (int)(barcodeW / barcodeHistorySeconds / 6));

        int ghostDisplayH = screenW * 9 / 16;
        int emptyBottomPx = dm.heightPixels - ghostDisplayH;
        Log.d(TAG, "Ghost aspect: screen=" + screenW + "x" + dm.heightPixels
                + "  ghost fills=" + screenW + "x" + ghostDisplayH
                + "  empty bottom=" + emptyBottomPx + "px");

        barcodeDisplay1 = Bitmap.createBitmap(barcodeW, barcodeH, Bitmap.Config.ARGB_8888);
        barcodeDisplay2 = Bitmap.createBitmap(barcodeW, barcodeH, Bitmap.Config.ARGB_8888);
        barcodeCanvas1 = new Canvas(barcodeDisplay1);
        barcodeCanvas2 = new Canvas(barcodeDisplay2);
        barcodeDisplay1.eraseColor(0xFF000000);
        barcodeDisplay2.eraseColor(0xFF000000);

        slitPixels = new int[barcodeH];
        rowAvgScratch = new int[miniW * barcodeH];

        if (!mode.useCompression) {
            maxMoonBarcodeFrames = (int)(mode.delaySeconds * mode.bufferFps) + 1;
            moonBarcodeBuffer = new ArrayDeque<>();
            moonBarcodePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        }

        FrameLayout.LayoutParams barcodeLp = (FrameLayout.LayoutParams) barcodeView.getLayoutParams();
        barcodeLp.width = barcodeW;
        barcodeLp.height = barcodeH;
        barcodeLp.gravity = Gravity.BOTTOM | Gravity.START;
        barcodeView.setLayoutParams(barcodeLp);
        barcodeView.setImageBitmap(barcodeDisplay1);

        FrameLayout.LayoutParams rowLp = (FrameLayout.LayoutParams) bottomLabelRow.getLayoutParams();
        rowLp.bottomMargin = barcodeH;
        bottomLabelRow.setLayoutParams(rowLp);

        FrameLayout.LayoutParams queuingLp = (FrameLayout.LayoutParams) queuingLabel.getLayoutParams();
        queuingLp.bottomMargin = barcodeH;
        queuingLabel.setLayoutParams(queuingLp);


        FrameLayout.LayoutParams stackLp = new FrameLayout.LayoutParams(cornerW, cornerH);
        stackLp.gravity = Gravity.BOTTOM | Gravity.END;
        stackLp.setMargins(0, 0, 0, barcodeH + 5);
        stackView.setLayoutParams(stackLp);

        updateLayoutForPhase(isPlaybackPhase);
        timerHandler.post(timerRunnable);

        cameraExecutor = Executors.newSingleThreadExecutor();
        displayExecutor = Executors.newSingleThreadExecutor();

        // For disk-based modes, seed the EMA ghost and barcode from persisted frames.
        // Both run on displayExecutor (sequential); EMA goes first so the ghost appears before the barcode.
        // emaHistoryLoading / barcodeHistoryLoading block live updates until each finishes.
        if (mode.useCompression) {
            emaHistoryLoading = true;
            displayExecutor.submit(this::initEmaFromHistory);
            barcodeHistoryLoading = true;
            displayExecutor.submit(this::initBarcodeFromHistory);
        }

        // Tap the top-right corner 10 times within 5 seconds to clear all stored frames.
        float cornerPx = 200 * dm.density;
        View rootLayout = findViewById(R.id.rootLayout);
        rootLayout.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN
                    && event.getX() >= screenW - cornerPx && event.getY() <= cornerPx) {
                long nowMs = System.currentTimeMillis();
                if (clearTapCount == 0 || nowMs - clearTapWindowStartMs > CLEAR_TAP_WINDOW_MS) {
                    clearTapCount = 1;
                    clearTapWindowStartMs = nowMs;
                } else {
                    clearTapCount++;
                    if (clearTapCount >= CLEAR_TAP_TARGET) {
                        clearTapCount = 0;
                        triggerClear();
                    }
                }
            }
            return false; // don't consume — let other listeners fire normally
        });

        initializeCamera();
    }

    private void triggerClear() {
        frameBuffer.clearAllFrames();
        isPlaybackPhase = false;
        lastDisplayUpdateMs = 0;
        stackInitialized = false;
        barcodeFrameCount = 0;
        barcodeAccum = 0f;
        lastBarcodeFrameMs = 0;
        barcodeHistoryLoading = false;
        barcodeDisplay1.eraseColor(0xFF000000);
        barcodeDisplay2.eraseColor(0xFF000000);
        useBarcodeDisplay1 = true;
        barcodeView.setImageBitmap(barcodeDisplay1);
        mainView.setImageBitmap(null);
        if (moonBarcodeBuffer != null) {
            for (Bitmap b : moonBarcodeBuffer) {
                if (b != null && !b.isRecycled()) b.recycle();
            }
            moonBarcodeBuffer.clear();
        }
        updateLayoutForPhase(false);
        Toast.makeText(this, "Buffer cleared", Toast.LENGTH_SHORT).show();
        Log.d(TAG, "Buffer cleared by gesture");
    }

    /**
     * Routes the EMA ghost bitmap to the right view depending on phase:
     * - Queuing: ghost fills mainView (barcode fills full-width bottom; no corner preview).
     * - Playback: ghost goes to corner stackView (mainView shows the delayed frame).
     */
    private void postEmaFrame(Bitmap toPost) {
        if (!isPlaybackPhase) {
            runOnUiThread(() -> mainView.setImageBitmap(toPost));
        } else {
            runOnUiThread(() -> stackView.setImageBitmap(toPost));
        }
    }

    private void updateScreenBrightness(boolean shouldDim) {
        if (isDimmed == shouldDim) return;
        isDimmed = shouldDim;
        runOnUiThread(() -> {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = shouldDim ? DIM_LEVEL : BRIGHT_LEVEL;
            getWindow().setAttributes(lp);
            float alpha = shouldDim ? 0.3f : 1.0f;
            modeIndicator.setAlpha(alpha);
            modeNameLabel.setAlpha(alpha);
            Log.d(TAG, "Brightness updated: isDimmed=" + shouldDim);
        });
    }

    private void updateTimeLabel() {
        long firstFrameMs = frameBuffer.getFirstFrameTimeMs();
        float elapsedSecs = (firstFrameMs == 0) ? 0 : (System.currentTimeMillis() - firstFrameMs) / 1000f;
        boolean nowPlayback = (firstFrameMs != 0 && elapsedSecs >= mode.delaySeconds);
        if (nowPlayback != isPlaybackPhase) {
            isPlaybackPhase = nowPlayback;
            updateLayoutForPhase(isPlaybackPhase);
        }
        if (!nowPlayback) {
            int remaining = (firstFrameMs == 0) ? (int) mode.delaySeconds : (int)(mode.delaySeconds - elapsedSecs);
            modeIndicator.setText(formatCountdown(remaining));
            queuingLabel.setVisibility(View.VISIBLE);
        } else {
            queuingLabel.setVisibility(View.GONE);
            long frameTimeMs = frameBuffer.getCurrentFrameTimestamp();
            if (frameTimeMs > 0) {
                Calendar cal = Calendar.getInstance();
                cal.setTimeInMillis(frameTimeMs);
                int month = cal.get(Calendar.MONTH) + 1;
                int day   = cal.get(Calendar.DAY_OF_MONTH);
                int year  = cal.get(Calendar.YEAR);
                int h     = cal.get(Calendar.HOUR_OF_DAY);
                int m     = cal.get(Calendar.MINUTE);
                int s     = cal.get(Calendar.SECOND);
                int ms    = cal.get(Calendar.MILLISECOND);
                modeIndicator.setText(String.format("Playback from %02d/%02d/%04d %02d:%02d:%02d.%03d", month, day, year, h, m, s, ms));
            }
        }
    }

    private void updateLayoutForPhase(boolean playback) {
        countdownOverlayView.setVisibility(playback ? View.GONE : View.VISIBLE);
        stackView.setVisibility(playback ? View.VISIBLE : View.GONE);
        // During queuing the EMA ghost fills mainView; tint it to match playback.
        // During playback mainView shows the already-tinted delayed frame, so clear.
        if (playback) {
            mainView.clearColorFilter();
        } else {
            mainView.setColorFilter(renderer.getResolvedTintColor(), android.graphics.PorterDuff.Mode.MULTIPLY);
        }
        Bitmap tickBmp = Bitmap.createBitmap(barcodeW, barcodeH, Bitmap.Config.ARGB_8888);
        tickBmp.eraseColor(0x00000000);
        Canvas tickCanvas = new Canvas(tickBmp);
        Paint tickPaint = new Paint();
        tickPaint.setColor(0xFF444444);
        tickPaint.setStrokeWidth(1f);
        Paint textPaint = new Paint();
        textPaint.setColor(0xFF888888);
        textPaint.setTextSize(12 * getResources().getDisplayMetrics().density);
        textPaint.setAntiAlias(true);

        if (mode.name.equals("Proxima Centauri")) {
            // 5-row grid: each row = 1 year (24px tall), month ticks every barcodeW/12
            int rowH = 24;
            for (int row = 0; row < 5; row++) {
                int year = 5 - row; // bottom row = year 1
                int rowTop = row * rowH;
                // Row separator line
                if (row > 0) tickCanvas.drawLine(0, rowTop, barcodeW, rowTop, tickPaint);
                // 11 month tick marks within each row
                for (int mo = 1; mo < 12; mo++) {
                    float tx = (float) mo / 12f * barcodeW;
                    tickCanvas.drawLine(tx, rowTop, tx, rowTop + rowH, tickPaint);
                }
                // Year label at far left of each row
                String label = "Y" + year;
                tickCanvas.drawText(label, 4, rowTop + textPaint.getTextSize(), textPaint);
            }
        } else {
            float tickIntervalSec;
            String labelFormat;
            if (mode.name.equals("Sun")) {
                tickIntervalSec = 60f;
                labelFormat = playback ? "-%dm" : "%dm";
            } else if (mode.name.equals("Saturn")) {
                tickIntervalSec = 600f;
                labelFormat = playback ? "-%dm" : "%dm";
            } else {
                tickIntervalSec = 1f;
                labelFormat = playback ? "-%ds" : "%ds";
            }

            float pixelsPerSec = (float)barcodeW / barcodeHistorySeconds;
            for (int i = 1; ; i++) {
                float timeVal = i * tickIntervalSec;
                float tx = playback ? barcodeW - (timeVal * pixelsPerSec) : timeVal * pixelsPerSec;
                if (tx < 0 || tx >= barcodeW) break;
                tickCanvas.drawLine(tx, 0, tx, barcodeH, tickPaint);
                String label = mode.name.equals("Moon") ? String.format(labelFormat, (int)timeVal) : String.format(labelFormat, (int)(timeVal / 60));
                tickCanvas.drawText(label, tx + 4, textPaint.getTextSize(), textPaint);
            }
        }

        tickView.setImageBitmap(tickBmp);
        FrameLayout.LayoutParams tickLp = (FrameLayout.LayoutParams) tickView.getLayoutParams();
        tickLp.width = barcodeW;
        tickLp.height = barcodeH;
        tickLp.gravity = Gravity.BOTTOM | Gravity.START;
        tickView.setLayoutParams(tickLp);
    }

    private String formatCountdown(int totalSeconds) {
        if (totalSeconds >= 86400) {
            int totalDays = totalSeconds / 86400;
            int years  = totalDays / 365;
            int months = (totalDays % 365) / 30;
            int days   = (totalDays % 365) % 30;
            String timeStr;
            if (years > 0)  timeStr = String.format("%dy %dm %dd", years, months, days);
            else if (months > 0) timeStr = String.format("%dm %dd", months, days);
            else timeStr = String.format("%dd", totalDays);
            return "buffering for " + timeStr;
        } else if (totalSeconds >= 3600) {
            int h = totalSeconds / 3600;
            int m = (totalSeconds % 3600) / 60;
            int s = totalSeconds % 60;
            return String.format("buffering for %02d:%02d:%02d", h, m, s);
        } else {
            int m = totalSeconds / 60;
            int s = totalSeconds % 60;
            return String.format("buffering for %02d:%02d", m, s);
        }
    }

    private void setFullscreen() {
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void initializeCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                bindCamera();
            } catch (Exception e) {
                Log.e(TAG, "Camera initialization failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCamera() {
        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setTargetResolution(new android.util.Size(mode.targetWidth, mode.targetHeight))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build();

        imageAnalysis.setAnalyzer(cameraExecutor, new ImageAnalysis.Analyzer() {
            @Override
            public void analyze(@NonNull ImageProxy imageProxy) {
                long nowMs = System.currentTimeMillis();
                if (isPlaybackPhase && nowMs - lastDisplayUpdateMs >= mode.displayIntervalMs) {
                    lastDisplayUpdateMs = nowMs;
                    if (mode.useCompression) {
                        displayExecutor.submit(() -> {
                            Bitmap delayedFrame = frameBuffer.getDelayedFrame();
                            if (delayedFrame != null) {
                                float historyProgress = 0f;
                                long firstMs = frameBuffer.getFirstFrameTimeMs();
                                if (firstMs > 0) {
                                    float elapsed = (System.currentTimeMillis() - firstMs) / 1000f;
                                    historyProgress = Math.min(1.0f, elapsed / mode.delaySeconds);
                                }
                                float baseline = 0.5f;
                                float dynamicAlpha = baseline + (1.0f - baseline) * (1.0f - (float)Math.log10(1.0 + 9.0 * historyProgress));
                                Bitmap composited = renderer.renderFrame(delayedFrame, dynamicAlpha);
                                runOnUiThread(() -> mainView.setImageBitmap(composited));
                            }
                        });
                    } else {
                        Bitmap delayedFrame = frameBuffer.getDelayedFrame();
                        if (delayedFrame != null) {
                            Bitmap composited = renderer.renderFrame(delayedFrame, 0.5f);
                            runOnUiThread(() -> mainView.setImageBitmap(composited));
                        }
                    }
                }

                Bitmap thumb = frameBuffer.addFrame(imageProxy);

                // Check for motion detection and handle screen dimming
                if (thumb != null) {
                    boolean motionDetected = frameBuffer.isMotionDetected();
                    float motionScore = frameBuffer.getLastMotionScore();
                    
                    // Log motion detection results (optional - can be removed)
                    if (motionDetected) {
                        Log.d(TAG, "Motion detected! Score: " + String.format("%.3f", motionScore));
                        lastMovementTimeMs = nowMs;  // Update the last movement time when motion is detected
                        updateScreenBrightness(false); // Wake up immediately on motion
                    } else if (nowMs - lastMovementTimeMs > INACTIVITY_TIMEOUT_MS) {
                        // NO MOVEMENT FOR INACTIVITY_TIMEOUT_MS
                        // Only dim if we're not already dimmed
                        if (!isDimmed) {
                            updateScreenBrightness(true); // Dim if timeout reached
                        }
                    }

                    updateBarcode(thumb);

                    if (!emaHistoryLoading) {
                    emaFrameCounter++;
                    if (!stackInitialized) {
                        thumb.getPixels(stackPixels, 0, miniW, 0, 0, miniW, miniH);
                        for (int i = 0; i < miniW * miniH; i++) {
                            int p = stackPixels[i];
                            int base = i * 3;
                            stackFloat[base]     = powCurve[(p >> 16) & 0xFF];
                            stackFloat[base + 1] = powCurve[(p >> 8)  & 0xFF];
                            stackFloat[base + 2] = powCurve[ p        & 0xFF];
                        }
                        stackInitialized = true;
                        emaFrameCounter = 0;
                        Bitmap posted = useDisplay1 ? stackDisplay1 : stackDisplay2;
                        useDisplay1 = !useDisplay1;
                        buildDisplayBitmap(posted);
                        final Bitmap toPost = posted;
                        postEmaFrame(toPost);
                    } else if (emaFrameCounter >= emaUpdateEvery) {
                        emaFrameCounter = 0;
                        thumb.getPixels(stackPixels, 0, miniW, 0, 0, miniW, miniH);
                        for (int i = 0; i < miniW * miniH; i++) {
                            int p = stackPixels[i];
                            int base = i * 3;
                            stackFloat[base]     = emaFade * stackFloat[base]     + emaAdd * powCurve[(p >> 16) & 0xFF];
                            stackFloat[base + 1] = emaFade * stackFloat[base + 1] + emaAdd * powCurve[(p >> 8)  & 0xFF];
                            stackFloat[base + 2] = emaFade * stackFloat[base + 2] + emaAdd * powCurve[ p        & 0xFF];
                        }
                        Bitmap posted = useDisplay1 ? stackDisplay1 : stackDisplay2;
                        useDisplay1 = !useDisplay1;
                        buildDisplayBitmap(posted);
                        final Bitmap toPost = posted;
                        postEmaFrame(toPost);
                    }
                    } // end !emaHistoryLoading

                    thumb.recycle();
                }

                imageProxy.close();
            }
        });

        CameraSelector cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;
        try {
            cameraProvider.unbindAll();
            cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis);
        } catch (Exception e) {
            Log.e(TAG, "Camera binding failed", e);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        timerHandler.removeCallbacks(timerRunnable);

        if (cameraProvider != null) cameraProvider.unbindAll();

        if (cameraExecutor != null) {
            cameraExecutor.shutdownNow();
            try {
                cameraExecutor.awaitTermination(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (displayExecutor != null) {
            displayExecutor.shutdownNow();
            try {
                displayExecutor.awaitTermination(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Save state when app is destroyed for disk-based modes
        if (mode.useCompression) {
            saveState();
        }

        if (frameBuffer != null) frameBuffer.release();
        if (renderer != null) renderer.release();
        if (stackDisplay1 != null && !stackDisplay1.isRecycled()) stackDisplay1.recycle();
        if (stackDisplay2 != null && !stackDisplay2.isRecycled()) stackDisplay2.recycle();
        stackDisplay1 = null;
        stackDisplay2 = null;
        if (barcodeDisplay1 != null && !barcodeDisplay1.isRecycled()) barcodeDisplay1.recycle();
        if (barcodeDisplay2 != null && !barcodeDisplay2.isRecycled()) barcodeDisplay2.recycle();
        barcodeDisplay1 = null;
        barcodeDisplay2 = null;
        if (moonBarcodeBuffer != null) {
            for (Bitmap b : moonBarcodeBuffer) {
                if (b != null && !b.isRecycled()) b.recycle();
            }
            moonBarcodeBuffer.clear();
        }
    }

    private void updateBarcode(Bitmap thumb) {
        if (barcodeHistoryLoading) return;
        if (!mode.useCompression) {
            updateMoonBarcode(thumb);
            return;
        }
        if (mode.name.equals("Proxima Centauri")) {
            updateProximaBarcode(thumb);
            return;
        }
        long nowMs = System.currentTimeMillis();
        if (lastBarcodeFrameMs == 0) {
            lastBarcodeFrameMs = nowMs;
            return;
        }
        float dtSec = (nowMs - lastBarcodeFrameMs) / 1000f;
        lastBarcodeFrameMs = nowMs;

        barcodeAccum += dtSec / barcodeHistorySeconds * barcodeW;

        // Moon: accumulate until we have at least one fixed-width slit. Consume all
        // complete slits at once so the barcode catches up correctly when updateBarcode
        // is called less frequently than bufferFps (e.g. frameSkip reduces call rate).
        int numMoonSlits = 0;
        if (mode.name.equals("Moon")) {
            numMoonSlits = (int)(barcodeAccum / moonBarcodeSlitWidth);
            if (numMoonSlits == 0) return;
            barcodeAccum -= numMoonSlits * moonBarcodeSlitWidth;
        }

        int slitsToDraw = (int)barcodeAccum;
        if (!mode.name.equals("Moon")) {
            if (slitsToDraw <= 0) return;
            barcodeAccum -= slitsToDraw;
        }

        if (!mode.name.equals("Moon")) {
            int slitStartY = (miniH - barcodeH) / 2;
            thumb.getPixels(rowAvgScratch, 0, miniW, 0, slitStartY, miniW, barcodeH);
            for (int row = 0; row < barcodeH; row++) {
                long rSum = 0, gSum = 0, bSum = 0;
                int base = row * miniW;
                for (int col = 0; col < miniW; col++) {
                    int px = rowAvgScratch[base + col];
                    rSum += (px >> 16) & 0xFF;
                    gSum += (px >> 8) & 0xFF;
                    bSum += px & 0xFF;
                }
                slitPixels[row] = 0xFF000000 | ((int)(rSum / miniW) << 16) | ((int)(gSum / miniW) << 8) | (int)(bSum / miniW);
            }
        }

        Bitmap writeBmp = useBarcodeDisplay1 ? barcodeDisplay1 : barcodeDisplay2;
        Bitmap readBmp = useBarcodeDisplay1 ? barcodeDisplay2 : barcodeDisplay1;
        Canvas writeCanvas = useBarcodeDisplay1 ? barcodeCanvas1 : barcodeCanvas2;
        useBarcodeDisplay1 = !useBarcodeDisplay1;

        int moonSlitTotal = numMoonSlits * moonBarcodeSlitWidth;
        int shiftAmount = mode.name.equals("Moon") ? moonSlitTotal : slitsToDraw;
        if (barcodeFrameCount < barcodeW) {
            writeCanvas.drawBitmap(readBmp, 0f, 0f, null);
        } else {
            Rect src = new Rect(shiftAmount, 0, barcodeW, barcodeH);
            Rect dst = new Rect(0, 0, barcodeW - shiftAmount, barcodeH);
            writeCanvas.drawBitmap(readBmp, src, dst, null);
        }

        if (mode.name.equals("Moon")) {
            // Draw each accumulated slit as a separate fixed-width thumbnail so all
            // frames appear the same size. When catch-up slits > 1 (frameSkip or stall),
            // the same thumbnail is repeated — equivalent to "show previous frame".
            if (numMoonSlits > 1) {
                Log.d(TAG, "Moon barcode: repeating frame x" + numMoonSlits + " (gap=" + (int)(dtSec * 1000) + "ms)");
            }
            int cropTop = (miniH - barcodeH) / 2;
            Rect thumbSrc = new Rect(0, cropTop, miniW, cropTop + barcodeH);
            for (int s = 0; s < numMoonSlits; s++) {
                int left = (barcodeFrameCount < barcodeW)
                        ? barcodeFrameCount
                        : barcodeW - moonSlitTotal + s * moonBarcodeSlitWidth;
                int right = Math.min(left + moonBarcodeSlitWidth, barcodeW);
                writeCanvas.drawBitmap(thumb, thumbSrc, new Rect(left, 0, right, barcodeH), null);
                if (barcodeFrameCount < barcodeW) barcodeFrameCount = right;
            }
        } else {
            for (int i = 0; i < slitsToDraw; i++) {
                int x;
                if (barcodeFrameCount < barcodeW) {
                    x = barcodeFrameCount;
                    barcodeFrameCount++;
                } else {
                    x = barcodeW - slitsToDraw + i;
                }
                if (x >= 0 && x < barcodeW) {
                    writeBmp.setPixels(slitPixels, 0, 1, x, 0, 1, barcodeH);
                }
            }
        }

        final Bitmap toPost = writeBmp;
        runOnUiThread(() -> barcodeView.setImageBitmap(toPost));
    }

    /**
     * Moon-mode barcode: keep a rolling deque of thumbnail copies and redraw all frames
     * as scaled tiles. Each of the maxMoonBarcodeFrames slots is a fixed-width column;
     * oldest frame is at the left, newest at the right.
     */
    private void updateMoonBarcode(Bitmap thumb) {
        Bitmap copy = thumb.copy(thumb.getConfig(), false);
        moonBarcodeBuffer.addLast(copy);
        while (moonBarcodeBuffer.size() > maxMoonBarcodeFrames) {
            Bitmap old = moonBarcodeBuffer.pollFirst();
            if (old != null && !old.isRecycled()) old.recycle();
        }

        Bitmap writeBmp = useBarcodeDisplay1 ? barcodeDisplay1 : barcodeDisplay2;
        Canvas writeCanvas = useBarcodeDisplay1 ? barcodeCanvas1 : barcodeCanvas2;
        useBarcodeDisplay1 = !useBarcodeDisplay1;

        writeCanvas.drawColor(0xFF000000);

        int slotW = barcodeW / maxMoonBarcodeFrames;
        if (slotW < 1) slotW = 1;
        int i = 0;
        for (Bitmap frame : moonBarcodeBuffer) {
            if (!frame.isRecycled()) {
                int x = i * slotW;
                int right = (i == maxMoonBarcodeFrames - 1) ? barcodeW : x + slotW;
                writeCanvas.drawBitmap(frame, null, new Rect(x, 0, right, barcodeH), moonBarcodePaint);
            }
            i++;
        }

        final Bitmap toPost = writeBmp;
        runOnUiThread(() -> barcodeView.setImageBitmap(toPost));
    }

    /**
     * Proxima Centauri barcode: maps wall-clock position to (yearRow, xPos) and draws a
     * 24-pixel-tall vertical slit. Year 1 is the bottom row; Year 5 is the top row.
     * Position within each year is proportional to elapsed time within that year.
     */
    private void updateProximaBarcode(Bitmap thumb) {
        long firstMs = frameBuffer.getFirstFrameTimeMs();
        if (firstMs == 0) return;
        long elapsedMs = System.currentTimeMillis() - firstMs;
        long yearMs = (long)(365.25 * 24 * 3600 * 1000);
        int yearIndex = (int) Math.min(elapsedMs / yearMs, 4); // 0 = year 1 (bottom)
        int xPos = (int)((float)(elapsedMs % yearMs) / yearMs * barcodeW);
        int rowH = 24;
        int rowTop = (4 - yearIndex) * rowH; // bitmap y: year1 at y=96, year5 at y=0

        // Extract center 24 rows from thumbnail and average across width
        int slitStartY = (miniH - rowH) / 2;
        int[] scratch24 = new int[miniW * rowH];
        thumb.getPixels(scratch24, 0, miniW, 0, slitStartY, miniW, rowH);
        int[] slit24 = new int[rowH];
        for (int row = 0; row < rowH; row++) {
            long r = 0, g = 0, b = 0;
            for (int col = 0; col < miniW; col++) {
                int px = scratch24[row * miniW + col];
                r += (px >> 16) & 0xFF;
                g += (px >> 8) & 0xFF;
                b += px & 0xFF;
            }
            slit24[row] = 0xFF000000 | ((int)(r / miniW) << 16) | ((int)(g / miniW) << 8) | (int)(b / miniW);
        }

        Bitmap writeBmp = useBarcodeDisplay1 ? barcodeDisplay1 : barcodeDisplay2;
        Bitmap readBmp  = useBarcodeDisplay1 ? barcodeDisplay2 : barcodeDisplay1;
        Canvas writeCanvas = useBarcodeDisplay1 ? barcodeCanvas1 : barcodeCanvas2;
        useBarcodeDisplay1 = !useBarcodeDisplay1;
        writeCanvas.drawBitmap(readBmp, 0f, 0f, null);
        if (xPos >= 0 && xPos < barcodeW) {
            writeBmp.setPixels(slit24, 0, 1, xPos, rowTop, 1, rowH);
        }
        final Bitmap toPost = writeBmp;
        runOnUiThread(() -> barcodeView.setImageBitmap(toPost));
    }

    /**
     * Reconstruct the barcode from previously persisted JPEG frames.
     * Runs on displayExecutor. Maps each barcode pixel to the nearest saved frame by
     * timestamp; leaves pixels black where no frame exists (gap while app was stopped).
     * Syncs both double-buffer bitmaps so the first live updateBarcode() call doesn't
     * flash the stale all-black buffer.
     */
    /**
     * Seeds the EMA ghost stack from previously stored frames on disk (Sun/Saturn).
     * Runs on displayExecutor. Live EMA updates are blocked by emaHistoryLoading until this finishes.
     */
    private void initEmaFromHistory() {
        File[] files = frameBuffer.getBufferedFiles();
        if (files == null || files.length == 0) {
            emaHistoryLoading = false;
            return;
        }

        // Decode at the largest power-of-2 sample that still meets miniW
        int sampleSize = 1;
        while (sampleSize * 2 * miniW <= mode.targetWidth) sampleSize *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sampleSize;

        boolean initialized = false;
        for (File file : files) {
            Bitmap decoded = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            if (decoded == null) continue;

            Bitmap thumb;
            if (decoded.getWidth() == miniW && decoded.getHeight() == miniH) {
                thumb = decoded;
            } else {
                thumb = Bitmap.createScaledBitmap(decoded, miniW, miniH, true);
                decoded.recycle();
            }

            thumb.getPixels(stackPixels, 0, miniW, 0, 0, miniW, miniH);

            if (!initialized) {
                for (int i = 0; i < miniW * miniH; i++) {
                    int p = stackPixels[i];
                    int base = i * 3;
                    stackFloat[base]     = powCurve[(p >> 16) & 0xFF];
                    stackFloat[base + 1] = powCurve[(p >> 8)  & 0xFF];
                    stackFloat[base + 2] = powCurve[ p        & 0xFF];
                }
                initialized = true;
            } else {
                for (int i = 0; i < miniW * miniH; i++) {
                    int p = stackPixels[i];
                    int base = i * 3;
                    stackFloat[base]     = emaFade * stackFloat[base]     + emaAdd * powCurve[(p >> 16) & 0xFF];
                    stackFloat[base + 1] = emaFade * stackFloat[base + 1] + emaAdd * powCurve[(p >> 8)  & 0xFF];
                    stackFloat[base + 2] = emaFade * stackFloat[base + 2] + emaAdd * powCurve[ p        & 0xFF];
                }
            }

            thumb.recycle();
        }

        if (initialized) {
            Bitmap posted = useDisplay1 ? stackDisplay1 : stackDisplay2;
            useDisplay1 = !useDisplay1;
            buildDisplayBitmap(posted);
            final Bitmap toPost = posted;
            stackInitialized = true;   // set before clearing flag so camera thread skips the seed branch
            emaHistoryLoading = false;
            postEmaFrame(toPost);
        } else {
            emaHistoryLoading = false;
        }
        Log.d(TAG, "EMA history init complete: " + files.length + " frames");
    }

    private void initBarcodeFromHistory() {
        long[] timestamps = frameBuffer.getBufferedTimestamps();
        File[] files = frameBuffer.getBufferedFiles();

        if (files == null || files.length == 0) {
            barcodeHistoryLoading = false;
            return;
        }

        if (mode.name.equals("Proxima Centauri")) {
            initProximaBarcodeFromHistory(files, timestamps);
            return;
        }

        long nowMs = System.currentTimeMillis();
        long totalMs = (long)(barcodeHistorySeconds * 1000);
        long oldestMs = nowMs - totalMs;
        // Accept a file as "covering" a pixel if it's within 3 pixel-widths of that pixel's time.
        long tolerance = Math.max(1500L, totalMs * 3 / barcodeW);

        Bitmap workBmp = Bitmap.createBitmap(barcodeW, barcodeH, Bitmap.Config.ARGB_8888);
        workBmp.eraseColor(0xFF000000);

        int lastFileIdx = -1;
        int[] lastSlit = null;

        for (int x = 0; x < barcodeW; x++) {
            long targetMs = oldestMs + (long)((double)x / barcodeW * totalMs);
            int idx = nearestTimestampIndex(timestamps, targetMs);
            if (idx < 0) continue;
            if (Math.abs(timestamps[idx] - targetMs) > tolerance) continue;

            if (idx != lastFileIdx) {
                lastSlit = decodeFileToSlit(files[idx]);
                lastFileIdx = idx;
            }
            if (lastSlit != null) {
                workBmp.setPixels(lastSlit, 0, 1, x, 0, 1, barcodeH);
            }
        }

        // Sync both double-buffer bitmaps so neither has stale black data
        barcodeCanvas1.drawBitmap(workBmp, 0f, 0f, null);
        barcodeCanvas2.drawBitmap(workBmp, 0f, 0f, null);
        workBmp.recycle();

        useBarcodeDisplay1 = true;
        barcodeFrameCount = barcodeW; // enter scrolling mode; new slits append at right edge

        final Bitmap toPost = barcodeDisplay1;
        runOnUiThread(() -> barcodeView.setImageBitmap(toPost));

        barcodeHistoryLoading = false;
    }

    /**
     * Reconstruct the 5-row Proxima Centauri barcode from stored files.
     * Each file is mapped to its year row and x position by timestamp.
     */
    private void initProximaBarcodeFromHistory(File[] files, long[] timestamps) {
        long firstMs = frameBuffer.getFirstFrameTimeMs();
        if (firstMs == 0) {
            barcodeHistoryLoading = false;
            return;
        }

        Bitmap workBmp = Bitmap.createBitmap(barcodeW, barcodeH, Bitmap.Config.ARGB_8888);
        workBmp.eraseColor(0xFF000000);

        long yearMs = (long)(365.25 * 24 * 3600 * 1000);
        int rowH = 24;

        for (int fi = 0; fi < files.length; fi++) {
            long ts = timestamps[fi];
            long elapsedMs = ts - firstMs;
            if (elapsedMs < 0) continue;
            int yearIndex = (int) Math.min(elapsedMs / yearMs, 4);
            int xPos = (int)((float)(elapsedMs % yearMs) / yearMs * barcodeW);
            int rowTop = (4 - yearIndex) * rowH;

            int[] slit = decodeFileToProximaSlit(files[fi], rowH);
            if (slit != null && xPos >= 0 && xPos < barcodeW) {
                workBmp.setPixels(slit, 0, 1, xPos, rowTop, 1, rowH);
            }
        }

        barcodeCanvas1.drawBitmap(workBmp, 0f, 0f, null);
        barcodeCanvas2.drawBitmap(workBmp, 0f, 0f, null);
        workBmp.recycle();

        useBarcodeDisplay1 = true;
        final Bitmap toPost = barcodeDisplay1;
        runOnUiThread(() -> barcodeView.setImageBitmap(toPost));
        barcodeHistoryLoading = false;
    }

    /**
     * Decode a JPEG file and return a 24-pixel-tall slit of averaged row colors,
     * used for Proxima Centauri barcode reconstruction.
     */
    private int[] decodeFileToProximaSlit(File file, int rowH) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 8;
        Bitmap decoded = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        if (decoded == null) return null;

        Bitmap thumb = Bitmap.createScaledBitmap(decoded, miniW, miniH, true);
        decoded.recycle();

        int slitStartY = (miniH - rowH) / 2;
        int[] scratch = new int[miniW * rowH];
        thumb.getPixels(scratch, 0, miniW, 0, slitStartY, miniW, rowH);
        thumb.recycle();

        int[] slit = new int[rowH];
        for (int row = 0; row < rowH; row++) {
            long r = 0, g = 0, b = 0;
            int base = row * miniW;
            for (int col = 0; col < miniW; col++) {
                int px = scratch[base + col];
                r += (px >> 16) & 0xFF;
                g += (px >> 8) & 0xFF;
                b += px & 0xFF;
            }
            slit[row] = 0xFF000000 | ((int)(r / miniW) << 16) | ((int)(g / miniW) << 8) | (int)(b / miniW);
        }
        return slit;
    }

    /**
     * Decode a JPEG file at reduced resolution and return a column of averaged row colors
     * suitable for stamping into the barcode as a single vertical slit.
     * Scales the decoded image to miniW × miniH so the center-row extraction matches
     * exactly what updateBarcode() does with live thumbnails.
     */
    private int[] decodeFileToSlit(java.io.File file) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 8;
        Bitmap decoded = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        if (decoded == null) return null;

        // Scale to thumbnail dimensions so height is guaranteed >= miniH
        Bitmap thumb = Bitmap.createScaledBitmap(decoded, miniW, miniH, true);
        decoded.recycle();

        // Extract center barcodeH rows — identical to updateBarcode()
        int slitStartY = (miniH - barcodeH) / 2;
        int[] scratch = new int[miniW * barcodeH];
        thumb.getPixels(scratch, 0, miniW, 0, slitStartY, miniW, barcodeH);
        thumb.recycle();

        int[] slit = new int[barcodeH];
        for (int row = 0; row < barcodeH; row++) {
            long rSum = 0, gSum = 0, bSum = 0;
            int base = row * miniW;
            for (int col = 0; col < miniW; col++) {
                int px = scratch[base + col];
                rSum += (px >> 16) & 0xFF;
                gSum += (px >> 8) & 0xFF;
                bSum += px & 0xFF;
            }
            slit[row] = 0xFF000000 | ((int)(rSum / miniW) << 16) | ((int)(gSum / miniW) << 8) | (int)(bSum / miniW);
        }
        return slit;
    }

    /**
     * Binary search returning the index of the element in {@code timestamps} whose value
     * is closest to {@code targetMs}. Returns -1 if the array is empty.
     */
    private int nearestTimestampIndex(long[] timestamps, long targetMs) {
        if (timestamps == null || timestamps.length == 0) return -1;
        int lo = 0, hi = timestamps.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) / 2;
            if (timestamps[mid] < targetMs) lo = mid + 1;
            else hi = mid;
        }
        // lo is the first index >= targetMs; compare with lo-1 to find nearest
        if (lo == 0) return 0;
        if (lo >= timestamps.length) return timestamps.length - 1;
        long diffLo = Math.abs(timestamps[lo] - targetMs);
        long diffPrev = Math.abs(timestamps[lo - 1] - targetMs);
        return diffLo <= diffPrev ? lo : lo - 1;
    }

    private void buildDisplayBitmap(Bitmap dst) {
        for (int i = 0; i < miniW * miniH; i++) {
            int base = i * 3;
            int r = Math.min(255, Math.max(0, (int)(stackFloat[base]     + 0.5f)));
            int g = Math.min(255, Math.max(0, (int)(stackFloat[base + 1] + 0.5f)));
            int b = Math.min(255, Math.max(0, (int)(stackFloat[base + 2] + 0.5f)));
            stackPixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        dst.setPixels(stackPixels, 0, miniW, 0, 0, miniW, miniH);
    }
    
    /**
     * Save the current state for recovery from crashes or app stops
     */
    private void saveState() {
        if (mode.useCompression) {
            // For disk-based modes (Sun/Saturn), the frames are already being saved
            // The system handles eviction and persistence automatically by design
            Log.d(TAG, "Saved checkpoint state for mode: " + mode.name);
            
            // Save timestamp to checkpoint file - this is handled automatically 
            // by the FrameBuffer's saveCheckpoint() method called during frame storage
        }
    }
    
    /**
     * Restore state from previous session if available
     */
    private void restoreState() {
        if (mode.useCompression) {
            // For disk-based modes, check if there are existing files to determine 
            // if we can continue from previous buffer state
            Log.d(TAG, "Restoring checkpoint state for mode: " + mode.name);
            
            // The system will naturally handle persistence based on file timestamps
            // We don't need complex recovery logic - the file system automatically
            // manages eviction and continues from where it left off.
            // The only thing we check is if the session exists and has data
            
            long checkpointTimestamp = frameBuffer.getCheckpointTimestamp();
            if (checkpointTimestamp > 0) {
                Log.d(TAG, "Found existing checkpoint with timestamp: " + checkpointTimestamp);
                // The system will continue normally from this point
                // All frames are automatically managed by timestamp-based eviction
                
                // Force a UI update to reflect playback state if needed
                long currentTime = System.currentTimeMillis();
                float elapsedSecs = (currentTime - checkpointTimestamp) / 1000f;
                boolean shouldPlayback = elapsedSecs >= mode.delaySeconds;
                
                if (shouldPlayback) {
                    isPlaybackPhase = true;
                    Log.d(TAG, "Forced playback phase due to restored checkpoint");
                }
            } else {
                Log.d(TAG, "No existing checkpoint found - starting fresh session");
            }
        }
    }
}