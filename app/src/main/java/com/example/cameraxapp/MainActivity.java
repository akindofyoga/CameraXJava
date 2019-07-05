package com.example.cameraxapp;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraX;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageAnalysisConfig;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureConfig;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.PreviewConfig;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Rational;
import android.util.Size;
import android.graphics.Matrix;

import java.io.File;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.util.concurrent.TimeUnit;

import android.os.Bundle;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

public class MainActivity extends AppCompatActivity {

    private final int REQUEST_CODE_PERMISSIONS = 10;
    private static final String TAG = "CameraXApp";

    private String[] REQUIRED_PERMISSIONS = new String[] {Manifest.permission.CAMERA};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        viewFinder = findViewById(R.id.view_finder);
        // Request camera permissions
        if (allPermissionsGranted()) {
            viewFinder.post(startCamera);
        } else {
            ActivityCompat.requestPermissions(
                    this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }

        // Every time the provided texture view changes, recompute layout
        viewFinder.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(
                    View v, int left, int top, int right, int bottom, int oldLeft, int oldTop,
                    int oldRight, int oldBottom) {
                updateTransform();
            }
        });
    }

    private TextureView viewFinder;

    private Runnable startCamera = new Runnable() {
        @Override
        public void run() {
            // Create configuration object for the viewfinder use case
            PreviewConfig previewConfig = new PreviewConfig.Builder()
                    .setTargetAspectRatio(new Rational(1, 1))
                    .setTargetResolution(new Size(640, 640))
                    .build();

            // Build the viewfinder use case
            Preview preview = new Preview(previewConfig);

            // Every time the viewfinder is updated, recompute layout
            preview.setOnPreviewOutputUpdateListener(
                previewOutput -> {
                    // To update the SurfaceTexture, we have to remove it and re-add it
                    ViewGroup parent = (ViewGroup) viewFinder.getParent();
                    parent.removeView(viewFinder);
                    parent.addView(viewFinder, 0);

                    viewFinder.setSurfaceTexture(previewOutput.getSurfaceTexture());
                    updateTransform();
                });

            // Create configuration object for the image capture use case
            ImageCaptureConfig imageCaptureConfig = new ImageCaptureConfig.Builder()
                    .setTargetAspectRatio(new Rational(1, 1))
                    // We don't set a resolution for image capture; instead, we
                    // select a capture mode which will infer the appropriate
                    // resolution based on aspect ration and requested mode
                    .setCaptureMode(ImageCapture.CaptureMode.MIN_LATENCY)
                    .build();

            // Build the image capture use case and attach button click listener
            ImageCapture imageCapture = new ImageCapture(imageCaptureConfig);
            findViewById(R.id.capture_button).setOnClickListener(view -> {
                File file = new File(getExternalMediaDirs()[0], System.currentTimeMillis() + ".jpg");
                imageCapture.takePicture(file, new ImageCapture.OnImageSavedListener(){
                    @Override
                    public void onError(ImageCapture.UseCaseError error, String message,
                                        @Nullable Throwable exc) {
                        String msg = "Photo capture failed: " + message;
                        Toast.makeText(getBaseContext(), msg, Toast.LENGTH_SHORT).show();
                        Log.e(TAG, msg);
                        if (exc != null) {
                            exc.printStackTrace();
                        }
                    }

                    @Override
                    public void onImageSaved(File file) {
                        String msg = "Photo capture succeeded: " + file.getAbsolutePath();
                        Toast.makeText(getBaseContext(), msg, Toast.LENGTH_SHORT).show();
                        Log.d(TAG, msg);
                    }
                });

            });

            // Setup image analysis pipeline that computes average pixel luminance
            HandlerThread analyzerThread = new HandlerThread("LuminosityAnalysis");
            analyzerThread.start();
            ImageAnalysisConfig analyzerConfig =
                    new ImageAnalysisConfig.Builder()
                            .setCallbackHandler(new Handler(analyzerThread.getLooper()))
                            // In our analysis, we care more about the latest image than
                            // analyzing *every* image
                            .setImageReaderMode(ImageAnalysis.ImageReaderMode.ACQUIRE_LATEST_IMAGE)
                            .build();

            ImageAnalysis analyzerUseCase = new ImageAnalysis(analyzerConfig);
            analyzerUseCase.setAnalyzer(new LuminosityAnalyzer());

            // Bind use cases to lifecycle
            CameraX.bindToLifecycle((LifecycleOwner) MainActivity.this, preview, imageCapture,
                    analyzerUseCase);
        }
    };

    private void updateTransform() {
        Matrix matrix = new Matrix();

        float centerX = viewFinder.getWidth() / 2f;
        float centerY = viewFinder.getHeight() / 2f;

        // Correct preview output to account for display rotation
        float rotationDegrees;
        switch (viewFinder.getDisplay().getRotation()) {
            case Surface.ROTATION_0:
                rotationDegrees = 0f;
                break;
            case Surface.ROTATION_90:
                rotationDegrees = 90f;
                break;
            case Surface.ROTATION_180:
                rotationDegrees = 180f;
                break;
            case Surface.ROTATION_270:
                rotationDegrees = 270f;
                break;
            default:
                return;
        }

        matrix.postRotate(-rotationDegrees, centerX, centerY);

        // Finally, apply transformations to our TextureView
        viewFinder.setTransform(matrix);
    }

    /**
     * Process result from permission request dialog box, has the request
     * been granted? If yes, start Camera. Otherwise display a toast
     */
    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                viewFinder.post(startCamera);
            } else {
                Toast.makeText(this,
                        "Permissions not granted by the user.",
                        Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(getBaseContext(), permission) !=
                    PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private static byte[] toByteArray(ByteBuffer buffer) {
        buffer.rewind();
        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);
        return data;
    }

    private class LuminosityAnalyzer implements ImageAnalysis.Analyzer {
        private long lastAnalyzedTimestamp = 0L;

        @Override
        public void analyze(ImageProxy image, int rotationDegrees) {
            long currentTimestamp = System.currentTimeMillis();
            if (currentTimestamp - lastAnalyzedTimestamp >=
                    TimeUnit.SECONDS.toMillis(1)) {
                // Since format in ImageAnalysis is YUV, image.planes[0]
                // contains the Y (luminance) plane
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                // Extract image data from callback object
                byte[] data = toByteArray(buffer);

                // Convert the data into an array of pixel values
                int sum = 0;
                for (byte val : data) {
                    // Add pixel value
                    sum += (((int)val) & 0xFF);
                }

                // Compute average luminance for the image
                double luma = sum / ((double) data.length);
                Log.d(TAG, "Average Luminosity " + luma);
                // Update timestamp of last analyzed frame
                lastAnalyzedTimestamp = currentTimestamp;
            }
        }
    }
}
