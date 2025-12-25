package com.toddmargolis.astroandroidapp;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
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
        modeIndicator = findViewById(R.id.modeIndicator);  // ADD THIS


        // Get selected mode
        String modeString = getIntent().getStringExtra("MODE");
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

        modeIndicator.setText(mode.name + " - " + formatDelay(mode.delaySeconds));
        modeLabel.setVisibility(View.GONE);

        // Initialize components
        frameBuffer = new FrameBuffer(this, mode);
        renderer = new OverlayRenderer(this, mode);

        // Start camera
        cameraExecutor = Executors.newSingleThreadExecutor();
        initializeCamera();
    }

    private String formatDelay(float seconds) {
        if (seconds < 60) {
            return String.format("%.1f sec delay", seconds);
        } else if (seconds < 3600) {
            int minutes = (int)(seconds / 60);
            int secs = (int)(seconds % 60);
            return String.format("%d min %d sec delay", minutes, secs);
        } else {
            int hours = (int)(seconds / 3600);
            int minutes = (int)((seconds % 3600) / 60);
            return String.format("%d hr %d min delay", hours, minutes);
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
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)  // ADD THIS LINE
                .build();

        imageAnalysis.setAnalyzer(cameraExecutor, new ImageAnalysis.Analyzer() {
            @Override
            public void analyze(@NonNull ImageProxy imageProxy) {
                // Add frame to buffer
                frameBuffer.addFrame(imageProxy);

                // Get delayed frame and render
                Bitmap delayedFrame = frameBuffer.getDelayedFrame();
                if (delayedFrame != null) {
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
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
        if (frameBuffer != null) {
            frameBuffer.release();
        }
        if (renderer != null) {
            renderer.release();
        }
    }
}