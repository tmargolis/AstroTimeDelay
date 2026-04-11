// Copyright (c) 2025 Todd Margolis. All rights reserved.
// Delayed Vision - Cosmically Inspired Video Mirrors. See LICENSE.md for terms.
package com.toddmargolis.astroandroidapp;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    private static final int CAMERA_PERMISSION_CODE = 100;
    private String selectedMode = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button moonButton = findViewById(R.id.moonButton);
        Button sunButton = findViewById(R.id.sunButton);
        Button saturnButton = findViewById(R.id.saturnButton);
        Button proximaButton = findViewById(R.id.proximaButton);

        moonButton.setOnClickListener(v -> {
            selectedMode = "MOON";
            checkCameraPermission();
        });

        sunButton.setOnClickListener(v -> {
            selectedMode = "SUN";
            checkCameraPermission();
        });

        saturnButton.setOnClickListener(v -> {
            selectedMode = "SATURN";
            checkCameraPermission();
        });

        proximaButton.setOnClickListener(v -> {
            selectedMode = "PROXIMA_CENTAURI";
            checkCameraPermission();
        });
    }

    private void checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA},
                    CAMERA_PERMISSION_CODE);
        } else {
            launchCameraActivity();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                launchCameraActivity();
            } else {
                Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void launchCameraActivity() {
        Intent intent = new Intent(this, CameraActivity.class);
        intent.putExtra("MODE", selectedMode);
        startActivity(intent);
    }
}