package com.example.blurwallpaper;

import android.Manifest;
import android.app.WallpaperManager;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicBlur;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.Toast;
import android.content.res.ColorStateList;
import androidx.palette.graphics.Palette;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.FileDescriptor;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends AppCompatActivity {

    // UI Components
    private ImageView wallpaperImageView;
    private com.google.android.material.slider.Slider blurSlider, darkenSlider;
    private Button setHomeButton, setLockButton;

    // Wallpaper and Image Processing
    private WallpaperManager wallpaperManager;
    private Bitmap originalBitmap;
    private Bitmap modifiedBitmap;
    private static final float MAX_RADIUS_PER_PASS = 11.0f;
    private final AtomicInteger requestCounter = new AtomicInteger(0);

    // Executor for background tasks
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // Modern launcher for requesting permissions
    private ActivityResultLauncher<String> requestPermissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // --- 1. Initialize UI and Services ---
        initializeViews();
        wallpaperManager = WallpaperManager.getInstance(this);

        // --- 2. Initialize the Permission Launcher ---
        // This handles the result of the permission request dialog.
        requestPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
            if (isGranted) {
                // Permission was granted by the user. Let's try loading the wallpaper again.
                Toast.makeText(this, "Permission Granted! Loading wallpaper...", Toast.LENGTH_SHORT).show();
                loadWallpaperFromFile();
            } else {
                // Permission was denied. The app can't function without it.
                Toast.makeText(this, "Permission Denied. Cannot access wallpaper.", Toast.LENGTH_LONG).show();
            }
        });

        // --- 3. Setup Listeners for Controls ---
        setupListeners();

        // --- 4. Start the Process ---
        // This will either load the wallpaper or trigger the permission request.
        loadWallpaperFromFile();
    }

    private void initializeViews() {
        wallpaperImageView = findViewById(R.id.wallpaperImageView);
        blurSlider = findViewById(R.id.blurSlider);
        darkenSlider = findViewById(R.id.darkenSlider);
        setHomeButton = findViewById(R.id.setHomeButton);
        setLockButton = findViewById(R.id.setLockButton);
    }

    /**
     * This is the core "hack" method. It checks for storage permission.
     * If granted, it attempts to load the wallpaper using the getWallpaperFile() method.
     * If not granted, it launches the permission request dialog.
     */
    private void loadWallpaperFromFile() {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            executor.execute(() -> {
                ParcelFileDescriptor pfd = null;
                try {
                    pfd = wallpaperManager.getWallpaperFile(WallpaperManager.FLAG_SYSTEM);
                    if (pfd != null) {
                        FileDescriptor fd = pfd.getFileDescriptor();
                        originalBitmap = BitmapFactory.decodeFileDescriptor(fd);
                        modifiedBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true);

                        // --- DYNAMIC COLOR LOGIC START ---
                        // Generate a color palette from the wallpaper
                        Palette palette = Palette.from(originalBitmap).generate();
                        // Get a vibrant color swatch, with a fallback to a muted one
                        Palette.Swatch activeColorSwatch = palette.getVibrantSwatch();
                        if (activeColorSwatch == null) {
                            activeColorSwatch = palette.getMutedSwatch();
                        }
                        // --- DYNAMIC COLOR LOGIC END ---

                        // Update the UI on the main thread
                        Palette.Swatch finalActiveColorSwatch = activeColorSwatch;
                        runOnUiThread(() -> {
                            wallpaperImageView.setImageBitmap(modifiedBitmap);

                            // --- APPLY DYNAMIC COLORS ---
                            if (finalActiveColorSwatch != null) {
                                // Create a ColorStateList to tint our sliders
                                ColorStateList colorStateList = ColorStateList.valueOf(finalActiveColorSwatch.getRgb());
                                blurSlider.setThumbTintList(colorStateList);
                                blurSlider.setTrackActiveTintList(colorStateList);
                                darkenSlider.setThumbTintList(colorStateList);
                                darkenSlider.setTrackActiveTintList(colorStateList);
                            }
                        });
                    }
                } catch (Exception e) { // Catch generic exception for safety
                    e.printStackTrace();
                    runOnUiThread(() -> Toast.makeText(this, "Failed to load wallpaper.", Toast.LENGTH_SHORT).show());
                } finally {
                    if (pfd != null) {
                        try {
                            pfd.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            });

        } else {
            requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
    }

    private void setupListeners() {
        // This is the new listener for Material Sliders
        com.google.android.material.slider.Slider.OnChangeListener sliderListener = (slider, value, fromUser) -> {
            if (fromUser) {
                applyEffects();
            }
        };

        blurSlider.addOnChangeListener(sliderListener);
        darkenSlider.addOnChangeListener(sliderListener);

        setHomeButton.setOnClickListener(view -> setWallpaper(WallpaperManager.FLAG_SYSTEM));
        setLockButton.setOnClickListener(view -> setWallpaper(WallpaperManager.FLAG_LOCK));
    }

    private void blurBitmapAdvanced(Bitmap bitmap, float radius, int iterations) {
        // For this multi-pass technique, we want a high-quality blur on each pass.
        // A lower scaleFactor gives higher quality. Let's use 3f.
        float scaleFactor = 3f;

        // The main loop. We will apply the blur 'iterations' number of times.
        for (int i = 0; i < iterations; i++) {
            int smallWidth = (int) (bitmap.getWidth() / scaleFactor);
            int smallHeight = (int) (bitmap.getHeight() / scaleFactor);

            // We need a temporary bitmap to hold the downscaled image.
            Bitmap tempBitmap = Bitmap.createScaledBitmap(bitmap, smallWidth, smallHeight, true);

            RenderScript rs = RenderScript.create(this);
            Allocation input = Allocation.createFromBitmap(rs, tempBitmap);
            Allocation output = Allocation.createTyped(rs, input.getType());
            ScriptIntrinsicBlur script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs));

            script.setRadius(Math.min(radius, 25.0f));
            script.setInput(input);
            script.forEach(output);

            output.copyTo(tempBitmap);

            // Draw the blurred bitmap back onto the main, full-size bitmap.
            // On the next loop, this newly blurred version will be used as the source.
            Canvas canvas = new Canvas(bitmap);
            canvas.drawBitmap(tempBitmap, null, new android.graphics.Rect(0, 0, bitmap.getWidth(), bitmap.getHeight()), null);

            // Clean up memory for this pass
            tempBitmap.recycle();
            input.destroy();
            output.destroy();
            script.destroy();
            rs.destroy();
        }
    }

    private void applyEffects() {
        if (originalBitmap == null) return;

        final int currentRequest = requestCounter.incrementAndGet();

        executor.execute(() -> {
            if (currentRequest != requestCounter.get()) {
                return; // Abort this outdated task
            }

            // --- THIS IS THE CORRECTED PART ---
            // Use .getValue() which returns a float.
            float blurValue = blurSlider.getValue();
            // For the darken slider, we cast the float value to an int for our darkenBitmap method.
            int darkenValue = (int) darkenSlider.getValue();


            Bitmap newModifiedBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true);

            darkenBitmap(newModifiedBitmap, darkenValue);

            if (blurValue > 0) {
                // Let the full slider value be the total desired blur.
                float totalDesiredRadius = blurValue;

                // The rest of the smoothing logic remains the same
                int iterations = (int) Math.ceil(totalDesiredRadius / MAX_RADIUS_PER_PASS);
                if (iterations < 1) {
                    iterations = 1;
                }

                float radiusPerPass = totalDesiredRadius / iterations;

                blurBitmapAdvanced(newModifiedBitmap, radiusPerPass, iterations);
            }

            if (currentRequest == requestCounter.get()) {
                modifiedBitmap = newModifiedBitmap;
                runOnUiThread(() -> wallpaperImageView.setImageBitmap(modifiedBitmap));
            }
        });
    }

    private void darkenBitmap(Bitmap bitmap, int value) {
        int alpha = (int) ((float) value / 100.0f * 255.0f);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.argb(alpha, 0, 0, 0));
    }

    private void blurBitmap(Bitmap bitmap, float radius) {
        RenderScript rs = RenderScript.create(this);
        Allocation input = Allocation.createFromBitmap(rs, bitmap, Allocation.MipmapControl.MIPMAP_NONE, Allocation.USAGE_SCRIPT);
        Allocation output = Allocation.createTyped(rs, input.getType());
        ScriptIntrinsicBlur script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs));
        script.setRadius(radius);
        script.setInput(input);
        script.forEach(output);
        output.copyTo(bitmap);
        input.destroy();
        output.destroy();
        script.destroy();
        rs.destroy();
    }

    private void setWallpaper(int flag) {
        if (modifiedBitmap == null) {
            Toast.makeText(this, "Image not ready yet.", Toast.LENGTH_SHORT).show();
            return;
        }

        executor.execute(() -> {
            try {
                wallpaperManager.setBitmap(modifiedBitmap, null, true, flag);
                runOnUiThread(() -> {
                    String location = (flag == WallpaperManager.FLAG_SYSTEM) ? "Home screen" : "Lock screen";
                    Toast.makeText(MainActivity.this, "Wallpaper set for " + location, Toast.LENGTH_SHORT).show();
                });
            } catch (IOException e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Failed to set wallpaper.", Toast.LENGTH_SHORT).show());
            }
        });
    }
}