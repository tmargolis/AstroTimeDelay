package com.toddmargolis.astroandroidapp;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.Calendar;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private TextView queuingLabel;
    private Bitmap lastDelayedFrame = null; // skip re-render when frame hasn't changed

    // EMA mini-view (ghost stack composite)
    private ImageView stackView;
    private Bitmap stackSrc, stackDst;
    private Canvas stackCanvas;
    private Paint emaFadePaint;
    private Paint emaAddPaint;
    private int miniW, miniH;
    private int margin16dp; // dp→px conversion cached for layout phase transitions
    private boolean isPlaybackPhase = false; // tracks QUEUEING vs delay-playback phase
    private boolean stackInitialized = false; // seed first frame at full opacity
    private int emaUpdateEvery = 1;  // batch K stored frames per EMA update (for tiny-α modes)
    private int emaFrameCounter = 0;

    private long startTimeMs;
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

        // Keep screen on
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Set fullscreen
        setFullscreen();

        // Get views
        displayView = findViewById(R.id.displayView);
        modeLabel = findViewById(R.id.modeLabel);
        modeIndicator = findViewById(R.id.modeIndicator);
        modeNameLabel = findViewById(R.id.modeNameLabel);
        queuingLabel = findViewById(R.id.queuingLabel);


        // Get selected mode
        String modeString = getIntent().getStringExtra("MODE");
        if (modeString == null) modeString = "MOON";
        
        switch (modeString) {
            case "MOON":
                mode = CelestialMode.createMoonMode();
                break;
            case "SUN":
                mode = CelestialMode.createSunMode();
                break;
            case "SATURN":
                mode = CelestialMode.createSaturnMode();
                break;
            default:
                mode = CelestialMode.createMoonMode();
        }

        modeLabel.setVisibility(View.GONE);
        modeIndicator.setVisibility(View.VISIBLE);

        // Compute mini-view dimensions: 25% of screen width, 16:9 aspect ratio
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(dm);
        miniW = dm.widthPixels / 4;
        miniH = miniW * 9 / 16;
        margin16dp = (int)(16 * dm.density);

        // Initialize components (pass thumb dimensions to FrameBuffer)
        frameBuffer = new FrameBuffer(this, mode, miniW, miniH);
        renderer = new OverlayRenderer(this, mode);

        stackView = findViewById(R.id.stackView);

        // Set mode name label text
        String nameText;
        switch (mode.name) {
            case "Sun":    nameText = "Sun / 8 minute 20 second delay"; break;
            case "Saturn": nameText = "Saturn / 90 minute delay";       break;
            default:       nameText = "Moon / 1.3 sec delay";           break;
        }
        modeNameLabel.setText(nameText);

        // Initialize EMA double-buffer (both bitmaps start black/transparent)
        stackSrc = Bitmap.createBitmap(miniW, miniH, Bitmap.Config.ARGB_8888);
        stackDst = Bitmap.createBitmap(miniW, miniH, Bitmap.Config.ARGB_8888);
        stackCanvas = new Canvas(stackDst);

        // Configure EMA paints using α from FrameBuffer.
        // For Sun/Saturn, per-frame α is so tiny that (int)(α*255) == 0 — nothing gets blended.
        // Fix: batch K stored frames per EMA update, using effective α = K*α, where K is chosen
        // so that (int)(K*α*255) >= 1. Fade per update is then (1-α)^K ≈ 1-K*α.
        float emaAlpha = frameBuffer.getEmaAlpha();
        emaUpdateEvery = Math.max(1, (int)Math.ceil(1.0 / (emaAlpha * 255)));
        float effectiveAlpha = emaUpdateEvery * emaAlpha;

        // EMA fade paint: scale RGB channels by (1-α)^K, leave alpha untouched
        emaFadePaint = new Paint();
        emaFadePaint.setAntiAlias(false);
        emaFadePaint.setFilterBitmap(false);
        float fadeScale = (float)Math.pow(1.0 - emaAlpha, emaUpdateEvery);
        ColorMatrix cm = new ColorMatrix();
        cm.setScale(fadeScale, fadeScale, fadeScale, 1.0f);
        emaFadePaint.setColorFilter(new ColorMatrixColorFilter(cm));

        // EMA add paint: additive blend at effective α opacity (guaranteed >= 1/255)
        emaAddPaint = new Paint();
        emaAddPaint.setAntiAlias(false);
        emaAddPaint.setFilterBitmap(false);
        emaAddPaint.setAlpha((int)(effectiveAlpha * 255));
        emaAddPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));

        Log.d(TAG, "EMA mini-view: " + miniW + "x" + miniH +
                " perFrameAlpha=" + emaAlpha +
                " batchK=" + emaUpdateEvery +
                " effectiveAlpha=" + effectiveAlpha +
                " addPaintAlpha=" + (int)(effectiveAlpha * 255));

        // Start in QUEUEING layout (fullscreen ghost, countdown at bottom right)
        updateLayoutForPhase(false);

        // Start timer label
        startTimeMs = System.currentTimeMillis();
        timerHandler.post(timerRunnable);

        // Start camera
        cameraExecutor = Executors.newSingleThreadExecutor();
        initializeCamera();
    }

    private void updateTimeLabel() {
        long firstFrameMs = frameBuffer.getFirstFrameTimeMs();
        float elapsedSecs = (firstFrameMs == 0) ? 0 :
                (System.currentTimeMillis() - firstFrameMs) / 1000f;
        boolean nowPlayback = (firstFrameMs != 0 && elapsedSecs >= mode.delaySeconds);

        // Trigger layout switch on phase transition
        if (nowPlayback != isPlaybackPhase) {
            isPlaybackPhase = nowPlayback;
            updateLayoutForPhase(isPlaybackPhase);
        }

        if (!nowPlayback) {
            // Countdown phase: show time remaining and QUEUEING banner
            int remaining = (firstFrameMs == 0)
                    ? (int) mode.delaySeconds
                    : (int)(mode.delaySeconds - elapsedSecs);
            modeIndicator.setText(formatCountdown(remaining));
            queuingLabel.setVisibility(View.VISIBLE);
        } else {
            // Playback phase: hide QUEUEING banner, show exact capture time
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
                modeIndicator.setText(String.format(
                        "%02d/%02d/%04d %02d:%02d:%02d.%03d",
                        month, day, year, h, m, s, ms));
            }
        }
    }

    private void updateLayoutForPhase(boolean playback) {
        if (playback) {
            // Delay mode: ghost stack shrinks to mini bottom-right corner, flush to edge
            FrameLayout.LayoutParams stackLp =
                    new FrameLayout.LayoutParams(miniW, miniH);
            stackLp.gravity = Gravity.BOTTOM | Gravity.END;
            stackLp.setMargins(0, 0, 0, 0);
            stackView.setLayoutParams(stackLp);

            // Timestamp sits directly above the mini ghost view (no gap)
            FrameLayout.LayoutParams indLp =
                    (FrameLayout.LayoutParams) modeIndicator.getLayoutParams();
            indLp.bottomMargin = miniH;
            modeIndicator.setLayoutParams(indLp);
        } else {
            // Queueing mode: ghost stack expands to fullscreen
            FrameLayout.LayoutParams stackLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            stackLp.gravity = Gravity.CENTER;
            stackLp.setMargins(0, 0, 0, 0);
            stackView.setLayoutParams(stackLp);

            // Countdown at very bottom right, flush to edge
            FrameLayout.LayoutParams indLp =
                    (FrameLayout.LayoutParams) modeIndicator.getLayoutParams();
            indLp.bottomMargin = 0;
            modeIndicator.setLayoutParams(indLp);
        }
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
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

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
                // Add frame to buffer — returns thumbnail if frame was stored, null if skipped
                Bitmap thumb = frameBuffer.addFrame(imageProxy);

                // EMA mini-view update: only when a new frame was actually stored
                if (thumb != null) {
                    emaFrameCounter++;
                    if (!stackInitialized) {
                        // Seed both buffers with the first frame at full opacity so the
                        // mini-view is immediately visible even for modes with tiny α (Sun, Saturn)
                        Paint fullPaint = new Paint();
                        stackCanvas.drawBitmap(thumb, 0, 0, fullPaint);
                        Canvas srcCanvas = new Canvas(stackSrc);
                        srcCanvas.drawBitmap(thumb, 0, 0, fullPaint);
                        stackInitialized = true;
                        emaFrameCounter = 0;

                        final Bitmap posted = stackDst;
                        runOnUiThread(() -> stackView.setImageBitmap(posted));
                        Bitmap tmp = stackSrc; stackSrc = stackDst; stackDst = tmp;
                        stackCanvas.setBitmap(stackDst);
                    } else if (emaFrameCounter >= emaUpdateEvery) {
                        // Batch K stored frames reached — do one EMA update
                        emaFrameCounter = 0;
                        // Pass 1: fade old composite by (1-α)^K
                        stackCanvas.drawBitmap(stackSrc, 0, 0, emaFadePaint);
                        // Pass 2: add latest frame at effective α = K*α
                        stackCanvas.drawBitmap(thumb, 0, 0, emaAddPaint);

                        final Bitmap posted = stackDst;
                        runOnUiThread(() -> stackView.setImageBitmap(posted));
                        Bitmap tmp = stackSrc; stackSrc = stackDst; stackDst = tmp;
                        stackCanvas.setBitmap(stackDst);
                    }
                    thumb.recycle();
                }

                // Get delayed frame and render — only re-composite when the frame
                // actually changes (buffer head advances). For Saturn (1fps buffer)
                // this prevents 24 redundant JPEG decodes + canvas renders per second.
                Bitmap delayedFrame = frameBuffer.getDelayedFrame();
                if (delayedFrame != null && delayedFrame != lastDelayedFrame) {
                    lastDelayedFrame = delayedFrame;
                    Bitmap composited = renderer.renderFrame(delayedFrame, 0.5f);
                    runOnUiThread(() -> displayView.setImageBitmap(composited));
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
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
        if (frameBuffer != null) {
            frameBuffer.release();
        }
        if (renderer != null) {
            renderer.release();
        }
        if (stackSrc != null && !stackSrc.isRecycled()) stackSrc.recycle();
        if (stackDst != null && !stackDst.isRecycled()) stackDst.recycle();
        stackSrc = null;
        stackDst = null;
    }
}