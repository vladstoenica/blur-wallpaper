package com.example.blurwallpaper;

import android.Manifest;
import android.app.WallpaperManager;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicBlur;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;
import android.content.res.ColorStateList;
import androidx.palette.graphics.Palette;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends AppCompatActivity {

    // UI Components
    private ImageView wallpaperImageView;
    private com.google.android.material.slider.Slider blurSlider, darkenSlider;
    private Button setHomeButton, setLockButton, pickPhotoButton, saveButton;

    // Wallpaper and Image Processing
    private WallpaperManager wallpaperManager;
    private Bitmap originalBitmap;
    private Bitmap modifiedBitmap;
    private static final float MAX_RADIUS_PER_PASS = 11.0f;
    private final AtomicInteger requestCounter = new AtomicInteger(0);

    // Executor for background tasks
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // Modern launchers for results
    private ActivityResultLauncher<String> requestPermissionLauncher;
    private ActivityResultLauncher<String> pickMediaLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // --- 1. Initialize UI and Services ---
        initializeViews();
        wallpaperManager = WallpaperManager.getInstance(this);

        // --- 2. Initialize the Launchers ---
        setupLaunchers();

        // --- 3. Setup Listeners for Controls ---
        setupListeners();

        // --- 4. Start the Process ---
        loadWallpaperFromFile();
    }

    private void initializeViews() {
        wallpaperImageView = findViewById(R.id.wallpaperImageView);
        blurSlider = findViewById(R.id.blurSlider);
        darkenSlider = findViewById(R.id.darkenSlider);
        setHomeButton = findViewById(R.id.setHomeButton);
        setLockButton = findViewById(R.id.setLockButton);
        pickPhotoButton = findViewById(R.id.pickPhotoButton);
        saveButton = findViewById(R.id.saveButton);
    }

    private void setupLaunchers() {
        requestPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
            if (isGranted) {
                Toast.makeText(this, "Permission Granted!", Toast.LENGTH_SHORT).show();
                loadWallpaperFromFile();
            } else {
                Toast.makeText(this, "Permission Denied. Cannot access wallpaper.", Toast.LENGTH_LONG).show();
            }
        });

        pickMediaLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) {
                loadSelectedImage(uri);
            }
        });
    }

    private void loadSelectedImage(Uri uri) {
        executor.execute(() -> {
            try (InputStream inputStream = getContentResolver().openInputStream(uri)) {
                Bitmap bitmap = BitmapFactory.decodeStream(inputStream);
                if (bitmap != null) {
                    originalBitmap = bitmap;
                    modifiedBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true);
                    
                    updateDynamicColors(originalBitmap);
                    
                    runOnUiThread(() -> {
                        wallpaperImageView.setImageBitmap(modifiedBitmap);
                        applyEffects();
                    });
                }
            } catch (IOException e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(this, "Failed to load image.", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void updateDynamicColors(Bitmap bitmap) {
        Palette.from(bitmap).generate(palette -> {
            if (palette != null) {
                Palette.Swatch activeColorSwatch = palette.getVibrantSwatch();
                if (activeColorSwatch == null) {
                    activeColorSwatch = palette.getMutedSwatch();
                }

                if (activeColorSwatch != null) {
                    ColorStateList colorStateList = ColorStateList.valueOf(activeColorSwatch.getRgb());
                    blurSlider.setThumbTintList(colorStateList);
                    blurSlider.setTrackActiveTintList(colorStateList);
                    darkenSlider.setThumbTintList(colorStateList);
                    darkenSlider.setTrackActiveTintList(colorStateList);
                }
            }
        });
    }

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

                        updateDynamicColors(originalBitmap);

                        runOnUiThread(() -> {
                            wallpaperImageView.setImageBitmap(modifiedBitmap);
                            applyEffects();
                        });
                    }
                } catch (Exception e) {
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
        com.google.android.material.slider.Slider.OnChangeListener sliderListener = (slider, value, fromUser) -> {
            if (fromUser) {
                applyEffects();
            }
        };

        blurSlider.addOnChangeListener(sliderListener);
        darkenSlider.addOnChangeListener(sliderListener);

        setHomeButton.setOnClickListener(view -> setWallpaper(WallpaperManager.FLAG_SYSTEM));
        setLockButton.setOnClickListener(view -> setWallpaper(WallpaperManager.FLAG_LOCK));
        pickPhotoButton.setOnClickListener(view -> pickMediaLauncher.launch("image/*"));
        saveButton.setOnClickListener(view -> saveImageToGallery());
    }

    private void saveImageToGallery() {
        if (modifiedBitmap == null) {
            Toast.makeText(this, "No image to save.", Toast.LENGTH_SHORT).show();
            return;
        }

        executor.execute(() -> {
            ContentValues values = new ContentValues();
            String fileName = "blurred_wallpaper_" + System.currentTimeMillis() + ".png";
            values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/BlurWallpaper");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
            }

            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

            if (uri != null) {
                try (OutputStream outputStream = getContentResolver().openOutputStream(uri)) {
                    modifiedBitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream);
                    
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        values.clear();
                        values.put(MediaStore.Images.Media.IS_PENDING, 0);
                        getContentResolver().update(uri, values, null, null);
                    }
                    
                    runOnUiThread(() -> Toast.makeText(this, "Image saved to gallery!", Toast.LENGTH_SHORT).show());
                } catch (IOException e) {
                    e.printStackTrace();
                    runOnUiThread(() -> Toast.makeText(this, "Failed to save image.", Toast.LENGTH_SHORT).show());
                }
            } else {
                runOnUiThread(() -> Toast.makeText(this, "Failed to create MediaStore entry.", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void blurBitmapAdvanced(Bitmap bitmap, float radius, int iterations) {
        float scaleFactor = 3f;
        for (int i = 0; i < iterations; i++) {
            int smallWidth = (int) (bitmap.getWidth() / scaleFactor);
            int smallHeight = (int) (bitmap.getHeight() / scaleFactor);
            Bitmap tempBitmap = Bitmap.createScaledBitmap(bitmap, smallWidth, smallHeight, true);

            RenderScript rs = RenderScript.create(this);
            Allocation input = Allocation.createFromBitmap(rs, tempBitmap);
            Allocation output = Allocation.createTyped(rs, input.getType());
            ScriptIntrinsicBlur script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs));

            script.setRadius(Math.min(radius, 25.0f));
            script.setInput(input);
            script.forEach(output);

            output.copyTo(tempBitmap);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawBitmap(tempBitmap, null, new android.graphics.Rect(0, 0, bitmap.getWidth(), bitmap.getHeight()), null);

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
            if (currentRequest != requestCounter.get()) return;

            float blurValue = blurSlider.getValue();
            int darkenValue = (int) darkenSlider.getValue();

            Bitmap newModifiedBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true);
            darkenBitmap(newModifiedBitmap, darkenValue);

            if (blurValue > 0) {
                float totalDesiredRadius = blurValue;
                int iterations = (int) Math.ceil(totalDesiredRadius / MAX_RADIUS_PER_PASS);
                if (iterations < 1) iterations = 1;
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