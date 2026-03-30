package com.toddmargolis.astroandroidapp;

import android.graphics.Bitmap;
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
import android.view.View;
import android.view.WindowManager;
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

import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class CameraActivity extends AppCompatActivity {
    private static final String TAG = "CameraActivity";

    private ImageView displayView;
    private TextView modeLabel;
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
    private int barcodeFrameCount = 0;
    private Bitmap barcodeDisplay1, barcodeDisplay2;
    private Canvas barcodeCanvas1, barcodeCanvas2;
    private boolean useBarcodeDisplay1 = true;
    private int[] slitPixels;
    private int[] rowAvgScratch;
    private float barcodeHistorySeconds;

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

        displayView = findViewById(R.id.displayView);
        TextView modeLabel = findViewById(R.id.modeLabel);
        modeIndicator = findViewById(R.id.modeIndicator);
        modeNameLabel = findViewById(R.id.modeNameLabel);
        queuingLabel = findViewById(R.id.queuingLabel);

        String modeString = getIntent().getStringExtra("MODE");
        if (modeString == null) modeString = "MOON";
        switch (modeString) {
            case "MOON": mode = CelestialMode.createMoonMode(); break;
            case "SUN": mode = CelestialMode.createSunMode(); break;
            case "SATURN": mode = CelestialMode.createSaturnMode(); break;
        }

        modeLabel.setVisibility(View.GONE);
        modeIndicator.setVisibility(View.VISIBLE);

        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(dm);
        miniW = dm.widthPixels / 4;
        miniH = miniW * 9 / 16;
        int cornerH = miniH * 3 / 4;
        int cornerW = cornerH * 16 / 9;

        frameBuffer = new FrameBuffer(this, mode, miniW, miniH);
        renderer = new OverlayRenderer(this, mode);

        stackView = findViewById(R.id.stackView);
        countdownOverlayView = findViewById(R.id.countdownOverlayView);
        countdownOverlayView.setImageResource(mode.overlayResourceId);
        if (mode.name.equals("Saturn")) {
            countdownOverlayView.setScaleType(ImageView.ScaleType.MATRIX);
            countdownOverlayView.setImageMatrix(new Matrix());
        } else if (mode.name.equals("Sun")) {
            countdownOverlayView.setScaleType(ImageView.ScaleType.FIT_START);
        }

        String nameText;
        switch (mode.name) {
            case "Sun": nameText = "Sun / 8 minute 20 second delay"; break;
            case "Saturn": nameText = "Saturn / 1.3 hour delay"; break;
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

        barcodeW = dm.widthPixels - cornerW;
        barcodeH = cornerH;
        barcodeHistorySeconds = mode.name.equals("Moon") ? 80.0f : mode.delaySeconds;

        barcodeDisplay1 = Bitmap.createBitmap(barcodeW, barcodeH, Bitmap.Config.ARGB_8888);
        barcodeDisplay2 = Bitmap.createBitmap(barcodeW, barcodeH, Bitmap.Config.ARGB_8888);
        barcodeCanvas1 = new Canvas(barcodeDisplay1);
        barcodeCanvas2 = new Canvas(barcodeDisplay2);
        barcodeDisplay1.eraseColor(0xFF000000);
        barcodeDisplay2.eraseColor(0xFF000000);

        slitPixels = new int[barcodeH];
        rowAvgScratch = new int[miniW * barcodeH];

        tickView = findViewById(R.id.tickView);
        barcodeView = findViewById(R.id.barcodeView);
        FrameLayout.LayoutParams barcodeLp = (FrameLayout.LayoutParams) barcodeView.getLayoutParams();
        barcodeLp.width = barcodeW;
        barcodeLp.height = barcodeH;
        barcodeLp.gravity = Gravity.BOTTOM | Gravity.START;
        barcodeView.setLayoutParams(barcodeLp);
        barcodeView.setImageBitmap(barcodeDisplay1);

        LinearLayout bottomLabelRow = findViewById(R.id.bottomLabelRow);
        FrameLayout.LayoutParams rowLp = (FrameLayout.LayoutParams) bottomLabelRow.getLayoutParams();
        rowLp.bottomMargin = barcodeH;
        bottomLabelRow.setLayoutParams(rowLp);

        FrameLayout.LayoutParams stackLp = new FrameLayout.LayoutParams(cornerW, cornerH);
        stackLp.gravity = Gravity.BOTTOM | Gravity.END;
        stackLp.setMargins(0, 0, 0, 0);
        stackView.setLayoutParams(stackLp);

        updateLayoutForPhase(false);
        timerHandler.post(timerRunnable);

        cameraExecutor = Executors.newSingleThreadExecutor();
        displayExecutor = Executors.newSingleThreadExecutor();
        initializeCamera();
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
                modeIndicator.setText(String.format("%02d/%02d/%04d %02d:%02d:%02d.%03d", month, day, year, h, m, s, ms));
            }
        }
    }

    private void updateLayoutForPhase(boolean playback) {
        countdownOverlayView.setVisibility(playback ? View.GONE : View.VISIBLE);
        float tickIntervalSec;
        String labelFormat;
        if (mode.name.equals("Sun")) {
            tickIntervalSec = 60f;
            labelFormat = playback ? "-%dm" : "%dm";
        } else if (mode.name.equals("Saturn")) {
            tickIntervalSec = 600f;
            labelFormat = playback ? "-%dm" : "%dm";
        } else {
            tickIntervalSec = 10f;
            labelFormat = playback ? "-%ds" : "%ds";
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

        float pixelsPerSec = (float)barcodeW / barcodeHistorySeconds;
        for (int i = 1; ; i++) {
            float timeVal = i * tickIntervalSec;
            float tx = playback ? barcodeW - (timeVal * pixelsPerSec) : timeVal * pixelsPerSec;
            if (tx < 0 || tx >= barcodeW) break;
            tickCanvas.drawLine(tx, 0, tx, barcodeH, tickPaint);
            String label = mode.name.equals("Moon") ? String.format(labelFormat, (int)timeVal) : String.format(labelFormat, (int)(timeVal / 60));
            tickCanvas.drawText(label, tx + 4, textPaint.getTextSize(), textPaint);
        }
        tickView.setImageBitmap(tickBmp);
        FrameLayout.LayoutParams tickLp = (FrameLayout.LayoutParams) tickView.getLayoutParams();
        tickLp.width = barcodeW;
        tickLp.height = barcodeH;
        tickLp.gravity = Gravity.BOTTOM | Gravity.START;
        tickView.setLayoutParams(tickLp);
    }

    private String formatCountdown(int totalSeconds) {
        if (totalSeconds >= 3600) {
            int h = totalSeconds / 3600;
            int m = (totalSeconds % 3600) / 60;
            int s = totalSeconds % 60;
            return String.format("%02d:%02d:%02d", h, m, s);
        } else {
            int m = totalSeconds / 60;
            int s = totalSeconds % 60;
            return String.format("%02d:%02d", m, s);
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
                                runOnUiThread(() -> displayView.setImageBitmap(composited));
                            }
                        });
                    } else {
                        Bitmap delayedFrame = frameBuffer.getDelayedFrame();
                        if (delayedFrame != null) {
                            Bitmap composited = renderer.renderFrame(delayedFrame, 0.5f);
                            runOnUiThread(() -> displayView.setImageBitmap(composited));
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
                        runOnUiThread(() -> stackView.setImageBitmap(toPost));
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
                        runOnUiThread(() -> stackView.setImageBitmap(toPost));
                    }

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
    }

    private void updateBarcode(Bitmap thumb) {
        long nowMs = System.currentTimeMillis();
        if (lastBarcodeFrameMs == 0) {
            lastBarcodeFrameMs = nowMs;
            return;
        }
        float dtSec = (nowMs - lastBarcodeFrameMs) / 1000f;
        lastBarcodeFrameMs = nowMs;

        barcodeAccum += dtSec / barcodeHistorySeconds * barcodeW;
        int slitsToDraw = (int)barcodeAccum;
        if (slitsToDraw <= 0) return;
        barcodeAccum -= slitsToDraw;

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

        Bitmap writeBmp = useBarcodeDisplay1 ? barcodeDisplay1 : barcodeDisplay2;
        Bitmap readBmp = useBarcodeDisplay1 ? barcodeDisplay2 : barcodeDisplay1;
        Canvas writeCanvas = useBarcodeDisplay1 ? barcodeCanvas1 : barcodeCanvas2;
        useBarcodeDisplay1 = !useBarcodeDisplay1;

        if (barcodeFrameCount < barcodeW) {
            writeCanvas.drawBitmap(readBmp, 0f, 0f, null);
        } else {
            Rect src = new Rect(slitsToDraw, 0, barcodeW, barcodeH);
            Rect dst = new Rect(0, 0, barcodeW - slitsToDraw, barcodeH);
            writeCanvas.drawBitmap(readBmp, src, dst, null);
        }

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

        final Bitmap toPost = writeBmp;
        runOnUiThread(() -> barcodeView.setImageBitmap(toPost));
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
}