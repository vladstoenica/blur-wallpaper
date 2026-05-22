package com.example.blurwallpaper;

import android.Manifest;
import android.app.WallpaperManager;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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
import java.util.Random;
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

        initializeViews();
        wallpaperManager = WallpaperManager.getInstance(this);
        setupLaunchers();
        setupListeners();
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
                Palette.Swatch activeColorSwatch = palette.getVibrantSwatch() != null ? 
                        palette.getVibrantSwatch() : palette.getMutedSwatch();

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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
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
                        try { pfd.close(); } catch (IOException ignored) {}
                    }
                }
            });
        } else {
            requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
    }

    private void setupListeners() {
        com.google.android.material.slider.Slider.OnChangeListener sliderListener = (slider, value, fromUser) -> {
            if (fromUser) applyEffects();
        };
        blurSlider.addOnChangeListener(sliderListener);
        darkenSlider.addOnChangeListener(sliderListener);
        setHomeButton.setOnClickListener(view -> setWallpaper(WallpaperManager.FLAG_SYSTEM));
        setLockButton.setOnClickListener(view -> setWallpaper(WallpaperManager.FLAG_LOCK));
        pickPhotoButton.setOnClickListener(view -> pickMediaLauncher.launch("image/*"));
        saveButton.setOnClickListener(view -> saveImageToGallery());
    }

    /**
     * HIGH QUALITY BLUR IMPLEMENTATION
     * Uses dynamic sampling and multi-pass intrinsic blur to achieve massive radii
     * while maintaining smooth gradients and high performance on modern screens.
     */
    private void blurBitmapHighQuality(Bitmap bitmap, float totalRadius) {
        if (totalRadius <= 0) return;

        // 1. Determine optimal sampling factor based on radius
        // For larger blurs, we can scale down more without detail loss
        float sampling = 1.0f;
        if (totalRadius > 50) sampling = 4.0f;
        else if (totalRadius > 20) sampling = 2.0f;

        float scaledRadius = totalRadius / sampling;
        
        // 2. Scale down for performance and effective radius boost
        int width = Math.round(bitmap.getWidth() / sampling);
        int height = Math.round(bitmap.getHeight() / sampling);
        
        Bitmap smallBitmap = Bitmap.createScaledBitmap(bitmap, width, height, true);
        
        // 3. Apply hardware-accelerated blur on the small bitmap
        RenderScript rs = RenderScript.create(this);
        Allocation input = Allocation.createFromBitmap(rs, smallBitmap);
        Allocation output = Allocation.createTyped(rs, input.getType());
        ScriptIntrinsicBlur script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs));

        // ScriptIntrinsicBlur max radius is 25. If scaledRadius > 25, we use multiple passes.
        int passes = (int) Math.ceil(scaledRadius / 25.0f);
        float radiusPerPass = scaledRadius / passes;
        
        script.setRadius(radiusPerPass);
        for (int i = 0; i < passes; i++) {
            script.setInput(input);
            script.forEach(output);
            output.copyTo(smallBitmap);
            input.copyFrom(smallBitmap); // Prepare for next pass
        }

        // 4. Scale up with filtering (Bilinear)
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(smallBitmap, null, new android.graphics.Rect(0, 0, bitmap.getWidth(), bitmap.getHeight()), paint);

        // 5. Anti-Banding / Dithering (High Quality Touch)
        // Adds a minute amount of noise to prevent visible color steps in large blurs
        applyDither(bitmap);

        // Cleanup
        smallBitmap.recycle();
        input.destroy();
        output.destroy();
        script.destroy();
        rs.destroy();
    }

    private void applyDither(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        
        Random random = new Random();
        for (int i = 0; i < pixels.length; i++) {
            int color = pixels[i];
            int r = Color.red(color);
            int g = Color.green(color);
            int b = Color.blue(color);
            
            // Add tiny random noise (-1 to 1) to break up bands
            int noise = random.nextInt(3) - 1;
            r = Math.max(0, Math.min(255, r + noise));
            g = Math.max(0, Math.min(255, g + noise));
            b = Math.max(0, Math.min(255, b + noise));
            
            pixels[i] = Color.rgb(r, g, b);
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
    }

    private void applyEffects() {
        if (originalBitmap == null) return;
        final int currentRequest = requestCounter.incrementAndGet();
        executor.execute(() -> {
            if (currentRequest != requestCounter.get()) return;

            float blurValue = blurSlider.getValue();
            int darkenValue = (int) darkenSlider.getValue();

            Bitmap newModifiedBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true);
            
            // Apply Darken first (usually better for color depth)
            darkenBitmap(newModifiedBitmap, darkenValue);

            // Apply High Quality Blur
            if (blurValue > 0) {
                blurBitmapHighQuality(newModifiedBitmap, blurValue);
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
        if (modifiedBitmap == null) return;
        executor.execute(() -> {
            try {
                wallpaperManager.setBitmap(modifiedBitmap, null, true, flag);
                runOnUiThread(() -> {
                    String loc = (flag == WallpaperManager.FLAG_SYSTEM) ? "Home screen" : "Lock screen";
                    Toast.makeText(this, "Wallpaper set for " + loc, Toast.LENGTH_SHORT).show();
                });
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    private void saveImageToGallery() {
        if (modifiedBitmap == null) return;
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
                    runOnUiThread(() -> Toast.makeText(this, "Saved to gallery!", Toast.LENGTH_SHORT).show());
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
    }
}