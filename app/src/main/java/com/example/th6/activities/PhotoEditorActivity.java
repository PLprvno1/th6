package com.example.th6.activities;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.BitmapDrawable;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentSender;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.FutureTarget;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.request.transition.Transition;
import com.example.th6.R;
import com.example.th6.databinding.ActivityPhotoEditorBinding;
import com.example.th6.models.AdjustmentSettings;
import com.example.th6.models.Photo;
import com.example.th6.models.PhotoEditState;
import com.example.th6.utils.BitmapEditor;
import com.example.th6.utils.ImageSaveUtils;

import java.util.Locale;
import java.util.Collections;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

public class PhotoEditorActivity extends AppCompatActivity {

    private ActivityPhotoEditorBinding binding;
    private Uri imageUri;

    private Bitmap originalBitmap;
    private Bitmap currentBitmap;

    private PhotoEditState editState = new PhotoEditState();
    private AdjustmentSettings adjustmentSettings = editState.adjustments;
    private int selectedFilter = BitmapEditor.FILTER_ORIGINAL;
    private boolean hasUnsavedEdits;
    private final Deque<PhotoEditState> undoStates = new ArrayDeque<>();
    private final Deque<PhotoEditState> redoStates = new ArrayDeque<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService renderExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger renderVersion = new AtomicInteger();
    private Runnable pendingPreviewRender;
    private Future<?> previewFuture;
    private boolean renderingPreview;
    private boolean restoringState;
    private boolean saveInProgress;
    private boolean comparingOriginal;
    private int compareCropVisibility = View.GONE;
    private int compareDrawVisibility = View.GONE;
    private int compareBitmapWidth;
    private int compareBitmapHeight;
    private final Matrix compareSavedMatrix = new Matrix();
    private ActivityResultLauncher<IntentSenderRequest> overwritePermissionLauncher;
    private final Matrix previewMatrix = new Matrix();
    private ScaleGestureDetector scaleGestureDetector;
    private float previewScale = 1f;
    private float minimumPreviewScale = 1f;
    private float lastOverlayFocusX;
    private float lastOverlayFocusY;
    private boolean hasOverlayFocus;
    private float lastPanX;
    private float lastPanY;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        overwritePermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.StartIntentSenderForResult(), result -> {
                    if (result.getResultCode() == RESULT_OK) overwriteOriginal();
                    else Toast.makeText(this, "Chưa cấp quyền ghi đè ảnh. Bạn vẫn có thể lưu bản sao.", Toast.LENGTH_LONG).show();
                });
        binding = ActivityPhotoEditorBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        if (getIntent().hasExtra("photo")) {
            Photo photo = (Photo) getIntent().getSerializableExtra("photo");
            if (photo != null && photo.getImageUrl() != null) {
                imageUri = Uri.parse(photo.getImageUrl());
            }
        } else if (getIntent().hasExtra("image_uri")) {
            imageUri = Uri.parse(getIntent().getStringExtra("image_uri"));
        }

        if (imageUri == null) {
            Toast.makeText(this, "Không tìm thấy dữ liệu ảnh để chỉnh sửa", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        setupUI();
        binding.btnOverwrite.setEnabled(canOverwriteImage());
        binding.btnOverwrite.setAlpha(binding.btnOverwrite.isEnabled() ? 1f : 0.5f);
        setupListeners();
        setupPreviewGestures();
        loadImageBitmap();
    }

    private void setupUI() {
        binding.tvEditorTitle.setText("Chỉnh sửa");
        binding.btnUndo.setText("Hoàn tác");
        binding.btnRedo.setText("Làm lại");
        binding.btnSaveCopy.setText("Lưu bản sao");
        binding.btnOverwrite.setText("Ghi đè ảnh gốc");
        binding.btnCompare.setText("Giữ để xem gốc");
        binding.filterOriginal.setText("Original");
        binding.filterVivid.setText("Vivid");
        binding.filterWarm.setText("Warm");
        binding.filterCool.setText("Cool");
        binding.filterGrayscale.setText("Mono");
        binding.filterSepia.setText("Dramatic");
        binding.filterFade.setText("Fade");
        binding.panelCrop.setVisibility(View.GONE);
        binding.panelDraw.setVisibility(View.GONE);
        binding.panelRotate.setVisibility(View.GONE);
        binding.panelAdjust.setVisibility(View.GONE);
        binding.panelFilter.setVisibility(View.GONE);
        // Saving is launched from the top-bar Done action; keeping duplicate save buttons
        // permanently visible needlessly reduced the canvas height on compact screens.
        binding.layoutSaveOptions.setVisibility(View.GONE);

        addParameterReset(binding.tvExposureLabel, binding.sbExposure, 300, "Exposure");
        addParameterReset(binding.tvBrightnessLabel, binding.sbBrightness, 100, "Brightness");
        addParameterReset(binding.tvHighlightsLabel, binding.sbHighlights, 100, "Highlights");
        addParameterReset(binding.tvShadowsLabel, binding.sbShadows, 100, "Shadows");
        addParameterReset(binding.tvContrastLabel, binding.sbContrast, 100, "Contrast");
        addParameterReset(binding.tvSaturationLabel, binding.sbSaturation, 100, "Saturation");
        addParameterReset(binding.tvTemperatureLabel, binding.sbTemperature, 100, "Temperature");
        addParameterReset(binding.tvTintLabel, binding.sbTint, 100, "Tint");

        binding.cropOverlay.setVisibility(View.GONE);
        binding.drawingOverlay.setVisibility(View.GONE);
    }

    private void addParameterReset(TextView label, SeekBar seekBar, int neutralProgress, String parameter) {
        if (!(label.getParent() instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) label.getParent();
        int index = parent.indexOfChild(label);
        parent.removeView(label);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView reset = new TextView(this);
        reset.setText("Đặt lại");
        reset.setTextSize(11f);
        reset.setTextColor(ContextCompat.getColor(this, R.color.editor_accent));
        reset.setGravity(android.view.Gravity.CENTER);
        reset.setMinWidth(dp(56));
        reset.setPadding(dp(6), 0, dp(6), 0);
        reset.setBackgroundResource(android.R.drawable.list_selector_background);
        reset.setClickable(true);
        reset.setFocusable(true);
        reset.setContentDescription("Đặt lại " + parameter);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(reset, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)));
        parent.addView(row, index, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        reset.setOnClickListener(v -> {
            if (seekBar.getProgress() == neutralProgress) return;
            pushUndoState();
            seekBar.setProgress(neutralProgress);
        });
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private boolean canOverwriteImage() {
        if (imageUri == null || !"content".equalsIgnoreCase(imageUri.getScheme())) return false;
        try {
            String mime = getContentResolver().getType(imageUri);
            return "image/jpeg".equalsIgnoreCase(mime) || "image/jpg".equalsIgnoreCase(mime)
                    || "image/png".equalsIgnoreCase(mime) || "image/webp".equalsIgnoreCase(mime);
        } catch (SecurityException revoked) {
            return false;
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupListeners() {
        binding.btnCancel.setOnClickListener(v -> confirmCancel());
        binding.btnDone.setOnClickListener(v -> showSaveOptions());

        binding.chipToolCrop.setOnClickListener(v -> {
            showPanel(binding.panelCrop);
            enableCropMode();
        });

        binding.chipToolDraw.setOnClickListener(v -> {
            showPanel(binding.panelDraw);
            enableDrawMode();
        });

        binding.chipToolRotate.setOnClickListener(v -> {
            showPanel(binding.panelRotate);
            disableOverlays();
        });

        binding.chipToolAdjust.setOnClickListener(v -> {
            showPanel(binding.panelAdjust);
            disableOverlays();
        });

        binding.chipToolFilter.setOnClickListener(v -> {
            showPanel(binding.panelFilter);
            disableOverlays();
        });

        // Crop Ratios & Apply
        binding.cropFree.setOnClickListener(v -> binding.cropOverlay.resetCropRect());
        binding.crop11.setOnClickListener(v -> applyCropRatio(1, 1));
        binding.crop43.setOnClickListener(v -> applyCropRatio(4, 3));
        binding.crop34.setOnClickListener(v -> applyCropRatio(3, 4));
        binding.crop169.setOnClickListener(v -> applyCropRatio(16, 9));
        binding.crop916.setOnClickListener(v -> applyCropRatio(9, 16));

        binding.btnApplyCrop.setOnClickListener(v -> executeCrop());

        // Drawing Pen Tools
        binding.colorRed.setOnClickListener(v -> binding.drawingOverlay.setPenColor(Color.parseColor("#EF4444")));
        binding.colorYellow.setOnClickListener(v -> binding.drawingOverlay.setPenColor(Color.parseColor("#EAB308")));
        binding.colorGreen.setOnClickListener(v -> binding.drawingOverlay.setPenColor(Color.parseColor("#22C55E")));
        binding.colorBlue.setOnClickListener(v -> binding.drawingOverlay.setPenColor(Color.parseColor("#3B82F6")));
        binding.colorWhite.setOnClickListener(v -> binding.drawingOverlay.setPenColor(Color.WHITE));
        binding.colorBlack.setOnClickListener(v -> binding.drawingOverlay.setPenColor(Color.BLACK));
        binding.colorRosewood.setOnClickListener(v -> binding.drawingOverlay.setPenColor(Color.parseColor("#9F5967")));

        binding.sbPenWidth.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float strokeWidth = Math.max(4f, progress);
                binding.drawingOverlay.setPenWidth(strokeWidth);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { pushUndoState(); }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        binding.btnPenUndo.setOnClickListener(v -> undoEdit());
        binding.btnPenErase.setOnClickListener(v -> {
            boolean enable = !binding.btnPenErase.isSelected();
            binding.btnPenErase.setSelected(enable);
            binding.drawingOverlay.setEraser(enable);
            binding.btnPenErase.setText(enable ? "Vẽ" : "Tẩy");
        });
        binding.btnPenClear.setOnClickListener(v -> {
            boolean hasStroke = false;
            for (PhotoEditState.Operation operation : editState.operations) {
                if (operation.type == PhotoEditState.STROKE) { hasStroke = true; break; }
            }
            if (!hasStroke) return;
            pushUndoState();
            editState.operations.removeIf(operation -> operation.type == PhotoEditState.STROKE);
            binding.drawingOverlay.clear();
            markEdited();
            schedulePreviewRender(false);
        });
        binding.drawingOverlay.setStrokeListener(new com.example.th6.views.DrawingOverlayView.StrokeListener() {
            @Override public void onStrokeStarted() { pushUndoState(); }
            @Override public void onStrokeFinished() {
                java.util.List<com.example.th6.views.DrawingOverlayView.StrokeData> strokes = binding.drawingOverlay.getStrokes();
                if (!strokes.isEmpty()) {
                    com.example.th6.views.DrawingOverlayView.StrokeData stroke = strokes.get(strokes.size() - 1);
                    editState.operations.add(PhotoEditState.Operation.stroke(stroke.points, stroke.color, stroke.normalizedWidth, stroke.erase));
                    binding.drawingOverlay.clear();
                    markEdited();
                    schedulePreviewRender(false);
                }
            }
        });

        // Rotate / Flip
        binding.btnRotateLeft.setOnClickListener(v -> {
            if (currentBitmap != null) {
                pushUndoState();
                editState.operations.add(PhotoEditState.Operation.rotate(-90));
                markEdited(); schedulePreviewRender(true);
            }
        });

        binding.btnRotateRight.setOnClickListener(v -> {
            if (currentBitmap != null) {
                pushUndoState();
                editState.operations.add(PhotoEditState.Operation.rotate(90));
                markEdited(); schedulePreviewRender(true);
            }
        });

        binding.btnFlipHorizontal.setOnClickListener(v -> {
            if (currentBitmap != null) {
                pushUndoState();
                editState.operations.add(PhotoEditState.Operation.flip(true, false));
                markEdited(); schedulePreviewRender(true);
            }
        });

        binding.btnFlipVertical.setOnClickListener(v -> {
            if (currentBitmap != null) {
                pushUndoState();
                editState.operations.add(PhotoEditState.Operation.flip(false, true));
                markEdited(); schedulePreviewRender(true);
            }
        });

        // Advanced Adjustments SeekBars
        binding.sbExposure.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float ev = (progress - 300) / 100.0f; // -3.0 to +3.0 EV
                adjustmentSettings.setExposure(ev);
                binding.tvExposureLabel.setText(String.format(Locale.getDefault(), "Phơi sáng (Exposure): %.1f EV", ev));
                applyColorAdjustments();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { pushUndoState(); }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        binding.sbBrightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float b = (progress - 100);
                adjustmentSettings.setBrightness(b);
                binding.tvBrightnessLabel.setText(String.format(Locale.getDefault(), "Độ sáng (Brightness): %.0f", b));
                applyColorAdjustments();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { pushUndoState(); }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        binding.sbHighlights.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float hl = (progress - 100);
                adjustmentSettings.setHighlights(hl);
                binding.tvHighlightsLabel.setText(String.format(Locale.getDefault(), "Vùng sáng (Highlights): %.0f", hl));
                applyColorAdjustments();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { pushUndoState(); }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        binding.sbShadows.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float sh = (progress - 100);
                adjustmentSettings.setShadows(sh);
                binding.tvShadowsLabel.setText(String.format(Locale.getDefault(), "Vùng tối (Shadows): %.0f", sh));
                applyColorAdjustments();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { pushUndoState(); }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        binding.sbContrast.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float c = progress / 100.0f;
                adjustmentSettings.setContrast(c);
                binding.tvContrastLabel.setText(String.format(Locale.getDefault(), "Độ tương phản (Contrast): %.1f", c));
                applyColorAdjustments();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { pushUndoState(); }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        binding.sbSaturation.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float s = progress / 100.0f;
                adjustmentSettings.setSaturation(s);
                binding.tvSaturationLabel.setText(String.format(Locale.getDefault(), "Độ bão hòa (Saturation): %.1f", s));
                applyColorAdjustments();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { pushUndoState(); }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        binding.sbTemperature.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float t = (progress - 100);
                adjustmentSettings.setTemperature(t);
                binding.tvTemperatureLabel.setText(String.format(Locale.getDefault(), "Nhiệt độ màu (Temperature): %.0f", t));
                applyColorAdjustments();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { pushUndoState(); }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        binding.sbTint.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float tint = (progress - 100);
                adjustmentSettings.setTint(tint);
                binding.tvTintLabel.setText(String.format(Locale.getDefault(), "Sắc độ (Tint): %.0f", tint));
                applyColorAdjustments();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { pushUndoState(); }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        binding.btnResetAll.setOnClickListener(v -> confirmResetAll());

        // Filters
        binding.filterOriginal.setOnClickListener(v -> applyFilter(BitmapEditor.FILTER_ORIGINAL));
        binding.filterVivid.setOnClickListener(v -> applyFilter(BitmapEditor.FILTER_VIVID));
        binding.filterWarm.setOnClickListener(v -> applyFilter(BitmapEditor.FILTER_WARM));
        binding.filterCool.setOnClickListener(v -> applyFilter(BitmapEditor.FILTER_COOL));
        binding.filterGrayscale.setOnClickListener(v -> applyFilter(BitmapEditor.FILTER_MONO));
        binding.filterSepia.setOnClickListener(v -> applyFilter(BitmapEditor.FILTER_DRAMATIC));
        binding.filterFade.setOnClickListener(v -> applyFilter(BitmapEditor.FILTER_FADE));
        binding.sbFilterIntensity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                editState.filterIntensity = progress / 100f;
                binding.tvFilterIntensity.setText("Cường độ bộ lọc · " + progress + "%");
                if (fromUser) markEdited();
                schedulePreviewRender(false);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { pushUndoState(); }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        binding.btnResetFilter.setOnClickListener(v -> {
            pushUndoState();
            editState.filter = BitmapEditor.FILTER_ORIGINAL;
            editState.filterIntensity = 1f;
            binding.sbFilterIntensity.setProgress(100);
            binding.chipGroupFilters.clearCheck();
            markEdited(); schedulePreviewRender(false);
        });

        // Compare Button
        binding.btnCompare.setOnTouchListener((v, event) -> {
            if (originalBitmap == null || binding == null) return false;
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                comparingOriginal = true;
                compareCropVisibility = binding.cropOverlay.getVisibility();
                compareDrawVisibility = binding.drawingOverlay.getVisibility();
                compareBitmapWidth = currentBitmap == null ? 0 : currentBitmap.getWidth();
                compareBitmapHeight = currentBitmap == null ? 0 : currentBitmap.getHeight();
                compareSavedMatrix.set(binding.imgEditPreview.getImageMatrix());
                binding.cropOverlay.setVisibility(View.GONE);
                binding.drawingOverlay.setVisibility(View.GONE);
                binding.imgEditPreview.setImageBitmap(originalBitmap);
                binding.btnCompare.setText("Ảnh gốc");
                showBitmapFitForCompare(originalBitmap);
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                restoreEditedPreviewAfterCompare();
                return true;
            }
            return true;
        });

        // Undo / Reset
        binding.btnUndo.setOnClickListener(v -> undoEdit());
        binding.btnRedo.setOnClickListener(v -> redoEdit());

        // Save Options
        binding.btnSaveCopy.setOnClickListener(v -> saveCopy());
        binding.btnOverwrite.setOnClickListener(v -> confirmOverwrite());
    }

    private void setupPreviewGestures() {
        binding.imgEditPreview.setScaleType(ImageView.ScaleType.MATRIX);
        binding.imgEditPreview.addOnLayoutChangeListener((view, left, top, right, bottom,
                                                          oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                resetPreviewTransform();
            }
        });
        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                float targetScale = Math.max(minimumPreviewScale,
                        Math.min(minimumPreviewScale * 5f, previewScale * detector.getScaleFactor()));
                float factor = targetScale / previewScale;
                previewMatrix.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
                previewScale = targetScale;
                applyPreviewMatrix();
                return true;
            }
        });
        binding.imgEditPreview.setOnTouchListener((view, event) -> {
            if (overlaysAreActive()) return false;
            scaleGestureDetector.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastPanX = event.getX();
                    lastPanY = event.getY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (event.getPointerCount() == 1 && previewScale > minimumPreviewScale * 1.001f
                            && !scaleGestureDetector.isInProgress()) {
                        previewMatrix.postTranslate(event.getX() - lastPanX, event.getY() - lastPanY);
                        applyPreviewMatrix();
                    }
                    lastPanX = event.getX();
                    lastPanY = event.getY();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    return true;
                default:
                    return true;
            }
        });
        // Let a two-finger gesture zoom the image beneath crop/draw overlays. Their own
        // touch handlers cancel the active one-finger gesture once a second pointer arrives.
        binding.cropOverlay.setOnTouchListener((view, event) -> {
            handleOverlayTransformGesture(event);
            return false;
        });
        binding.drawingOverlay.setOnTouchListener((view, event) -> {
            handleOverlayTransformGesture(event);
            return false;
        });
        binding.btnFit.setOnClickListener(v -> resetPreviewTransform());
    }

    private boolean overlaysAreActive() {
        return binding.cropOverlay.getVisibility() == View.VISIBLE
                || binding.drawingOverlay.getVisibility() == View.VISIBLE;
    }

    private void handleOverlayTransformGesture(MotionEvent event) {
        scaleGestureDetector.onTouchEvent(event);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            hasOverlayFocus = false;
        } else if (event.getPointerCount() >= 2 && action == MotionEvent.ACTION_MOVE) {
            float focusX = 0f, focusY = 0f;
            for (int i = 0; i < event.getPointerCount(); i++) {
                focusX += event.getX(i);
                focusY += event.getY(i);
            }
            focusX /= event.getPointerCount();
            focusY /= event.getPointerCount();
            if (hasOverlayFocus) {
                previewMatrix.postTranslate(focusX - lastOverlayFocusX, focusY - lastOverlayFocusY);
                applyPreviewMatrix();
            }
            lastOverlayFocusX = focusX;
            lastOverlayFocusY = focusY;
            hasOverlayFocus = true;
        } else if (action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_CANCEL) {
            hasOverlayFocus = false;
        }
    }

    private void resetPreviewTransform() {
        if (binding == null || binding.imgEditPreview.getDrawable() == null) return;
        binding.imgEditPreview.post(() -> {
            if (binding == null || binding.imgEditPreview.getDrawable() == null) return;
            int imageWidth = binding.imgEditPreview.getDrawable().getIntrinsicWidth();
            int imageHeight = binding.imgEditPreview.getDrawable().getIntrinsicHeight();
            int viewWidth = binding.imgEditPreview.getWidth();
            int viewHeight = binding.imgEditPreview.getHeight();
            if (imageWidth <= 0 || imageHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return;
            minimumPreviewScale = Math.min((float) viewWidth / imageWidth, (float) viewHeight / imageHeight);
            previewScale = minimumPreviewScale;
            float left = (viewWidth - imageWidth * minimumPreviewScale) / 2f;
            float top = (viewHeight - imageHeight * minimumPreviewScale) / 2f;
            previewMatrix.setScale(minimumPreviewScale, minimumPreviewScale);
            previewMatrix.postTranslate(left, top);
            applyPreviewMatrix();
        });
    }

    private void applyPreviewMatrix() {
        if (binding == null) return;
        clampPreviewTranslation();
        binding.imgEditPreview.setImageMatrix(previewMatrix);
        updateImageBoundsForOverlays();
    }

    private void clampPreviewTranslation() {
        if (binding == null || binding.imgEditPreview.getDrawable() == null) return;
        float[] values = new float[9];
        previewMatrix.getValues(values);
        float width = binding.imgEditPreview.getDrawable().getIntrinsicWidth() * values[Matrix.MSCALE_X];
        float height = binding.imgEditPreview.getDrawable().getIntrinsicHeight() * values[Matrix.MSCALE_Y];
        float viewWidth = binding.imgEditPreview.getWidth();
        float viewHeight = binding.imgEditPreview.getHeight();
        float minX = width > viewWidth ? viewWidth - width : (viewWidth - width) / 2f;
        float maxX = width > viewWidth ? 0f : minX;
        float minY = height > viewHeight ? viewHeight - height : (viewHeight - height) / 2f;
        float maxY = height > viewHeight ? 0f : minY;
        float dx = Math.max(minX, Math.min(maxX, values[Matrix.MTRANS_X])) - values[Matrix.MTRANS_X];
        float dy = Math.max(minY, Math.min(maxY, values[Matrix.MTRANS_Y])) - values[Matrix.MTRANS_Y];
        previewMatrix.postTranslate(dx, dy);
    }

    private void showPanel(View panelToShow) {
        binding.panelCrop.setVisibility(panelToShow == binding.panelCrop ? View.VISIBLE : View.GONE);
        binding.panelDraw.setVisibility(panelToShow == binding.panelDraw ? View.VISIBLE : View.GONE);
        binding.panelRotate.setVisibility(panelToShow == binding.panelRotate ? View.VISIBLE : View.GONE);
        binding.panelAdjust.setVisibility(panelToShow == binding.panelAdjust ? View.VISIBLE : View.GONE);
        binding.panelFilter.setVisibility(panelToShow == binding.panelFilter ? View.VISIBLE : View.GONE);
    }

    private void disableOverlays() {
        binding.cropOverlay.setVisibility(View.GONE);
        binding.drawingOverlay.setVisibility(View.GONE);
    }

    private void enableCropMode() {
        binding.drawingOverlay.setVisibility(View.GONE);
        binding.cropOverlay.setVisibility(View.VISIBLE);
        updateImageBoundsForOverlays();
        binding.cropOverlay.resetCropRect();
    }

    private void enableDrawMode() {
        binding.cropOverlay.setVisibility(View.GONE);
        binding.drawingOverlay.setVisibility(View.VISIBLE);
        updateImageBoundsForOverlays();
    }

    private void updateImageBoundsForOverlays() {
        binding.imgEditPreview.post(() -> {
            RectF bounds = getImageDisplayBounds(binding.imgEditPreview);
            if (bounds != null) {
                binding.cropOverlay.setImageBounds(bounds);
                binding.drawingOverlay.setImageBounds(bounds);
            }
        });
    }

    private RectF getImageDisplayBounds(ImageView imageView) {
        if (imageView == null || imageView.getDrawable() == null) return null;

        Drawable d = imageView.getDrawable();
        int origW = d.getIntrinsicWidth();
        int origH = d.getIntrinsicHeight();
        if (origW <= 0 || origH <= 0) return null;
        RectF bounds = new RectF(0f, 0f, origW, origH);
        imageView.getImageMatrix().mapRect(bounds);
        return bounds;
    }

    private void executeCrop() {
        if (currentBitmap == null || pendingPreviewRender != null || renderingPreview) return;
        Rect cropRect = binding.cropOverlay.getCroppedBitmapArea(currentBitmap, binding.imgEditPreview);
        if (cropRect != null && cropRect.width() > 10 && cropRect.height() > 10) {
            pushUndoState();
            RectF normalized = new RectF((float) cropRect.left / currentBitmap.getWidth(),
                    (float) cropRect.top / currentBitmap.getHeight(),
                    (float) cropRect.right / currentBitmap.getWidth(),
                    (float) cropRect.bottom / currentBitmap.getHeight());
            editState.operations.add(PhotoEditState.Operation.crop(normalized));
            markEdited();
            binding.cropOverlay.setVisibility(View.GONE);
            schedulePreviewRender(true);
            Toast.makeText(this, "Đã cắt ảnh!", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Vùng cắt không hợp lệ", Toast.LENGTH_SHORT).show();
        }
    }

    private void applyCropRatio(float ratioW, float ratioH) {
        if (currentBitmap == null) return;
        RectF bounds = getImageDisplayBounds(binding.imgEditPreview);
        if (bounds == null) return;

        float targetRatio = ratioW / ratioH;
        float boundsRatio = bounds.width() / bounds.height();

        float cropW = bounds.width();
        float cropH = bounds.height();

        if (boundsRatio > targetRatio) {
            cropW = bounds.height() * targetRatio;
        } else {
            cropH = bounds.width() / targetRatio;
        }

        float left = bounds.left + (bounds.width() - cropW) / 2f;
        float top = bounds.top + (bounds.height() - cropH) / 2f;

        RectF newRect = new RectF(left, top, left + cropW, top + cropH);
        binding.cropOverlay.getCropRect().set(newRect);
        binding.cropOverlay.invalidate();
    }

    private void loadImageBitmap() {
        binding.progressBar.setVisibility(View.VISIBLE);

        Glide.with(this)
                .asBitmap()
                .load(imageUri)
                .override(1800, 1800)
                .centerInside()
                .into(new CustomTarget<Bitmap>() {
                    @Override
                    public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                        binding.progressBar.setVisibility(View.GONE);
                        originalBitmap = resource.copy(Bitmap.Config.ARGB_8888, true);
                        adjustmentSettings = editState.adjustments;
                        updateFilterThumbnails();
                        schedulePreviewRender(true);
                    }

                    @Override
                    public void onLoadCleared(@Nullable Drawable placeholder) {}

                    @Override
                    public void onLoadFailed(@Nullable Drawable errorDrawable) {
                        binding.progressBar.setVisibility(View.GONE);
                        Toast.makeText(PhotoEditorActivity.this, "Lỗi tải ảnh để chỉnh sửa", Toast.LENGTH_SHORT).show();
                        finish();
                    }
                });
    }

    private void applyColorAdjustments() {
        if (originalBitmap == null) return;
        if (!restoringState) markEdited();
        schedulePreviewRender(false);
    }

    private void applyFilter(int filterType) {
        if (editState.filter == filterType) return;
        pushUndoState();
        editState.filter = filterType;
        selectedFilter = filterType;
        editState.filterIntensity = 1f;
        binding.sbFilterIntensity.setProgress(100);
        if (filterType == BitmapEditor.FILTER_ORIGINAL) binding.chipGroupFilters.check(binding.filterOriginal.getId());
        else if (filterType == BitmapEditor.FILTER_VIVID) binding.chipGroupFilters.check(binding.filterVivid.getId());
        else if (filterType == BitmapEditor.FILTER_WARM) binding.chipGroupFilters.check(binding.filterWarm.getId());
        else if (filterType == BitmapEditor.FILTER_COOL) binding.chipGroupFilters.check(binding.filterCool.getId());
        else if (filterType == BitmapEditor.FILTER_MONO) binding.chipGroupFilters.check(binding.filterGrayscale.getId());
        else if (filterType == BitmapEditor.FILTER_DRAMATIC) binding.chipGroupFilters.check(binding.filterSepia.getId());
        else if (filterType == BitmapEditor.FILTER_FADE) binding.chipGroupFilters.check(binding.filterFade.getId());
        markEdited();
        schedulePreviewRender(false);
    }

    private void resetToOriginal() {
        if (originalBitmap == null) return;
        pushUndoState();
        editState = new PhotoEditState();
        adjustmentSettings = editState.adjustments;
        selectedFilter = editState.filter;

        binding.drawingOverlay.clear();
        disableOverlays();
        syncControlsFromCurrentState();
        markEdited();
        schedulePreviewRender(true);

        Toast.makeText(this, "Đã khôi phục ảnh gốc", Toast.LENGTH_SHORT).show();
    }

    private void confirmResetAll() {
        if (editState.isNeutral()) return;
        new AlertDialog.Builder(this)
                .setTitle("Đặt lại tất cả?")
                .setMessage("Điều chỉnh, bộ lọc, crop, xoay/lật và nét vẽ sẽ được xóa. Bạn vẫn có thể hoàn tác.")
                .setPositiveButton("Đặt lại", (dialog, which) -> resetToOriginal())
                .setNegativeButton("Tiếp tục chỉnh sửa", null)
                .show();
    }

    private void syncControlsFromCurrentState() {
        restoringState = true;
        binding.sbExposure.setProgress(Math.round(300 + adjustmentSettings.getExposure() * 100));
        binding.sbBrightness.setProgress(Math.round(100 + adjustmentSettings.getBrightness()));
        binding.sbHighlights.setProgress(Math.round(100 + adjustmentSettings.getHighlights()));
        binding.sbShadows.setProgress(Math.round(100 + adjustmentSettings.getShadows()));
        binding.sbContrast.setProgress(Math.round(adjustmentSettings.getContrast() * 100));
        binding.sbSaturation.setProgress(Math.round(adjustmentSettings.getSaturation() * 100));
        binding.sbTemperature.setProgress(Math.round(100 + adjustmentSettings.getTemperature()));
        binding.sbTint.setProgress(Math.round(100 + adjustmentSettings.getTint()));
        binding.sbFilterIntensity.setProgress(Math.round(editState.filterIntensity * 100));
        syncFilterSelection();
        restoringState = false;
    }

    private void restoreEditedPreviewAfterCompare() {
        if (!comparingOriginal || binding == null) return;
        comparingOriginal = false;
        binding.imgEditPreview.setImageBitmap(currentBitmap);
        binding.cropOverlay.setVisibility(compareCropVisibility);
        binding.drawingOverlay.setVisibility(compareDrawVisibility);
        binding.btnCompare.setText("Giữ để xem gốc");
        if (currentBitmap != null && (currentBitmap.getWidth() != compareBitmapWidth
                || currentBitmap.getHeight() != compareBitmapHeight)) resetPreviewTransform();
        else binding.imgEditPreview.setImageMatrix(compareSavedMatrix);
    }

    private void showBitmapFitForCompare(Bitmap bitmap) {
        if (binding == null || bitmap == null) return;
        int viewWidth = binding.imgEditPreview.getWidth();
        int viewHeight = binding.imgEditPreview.getHeight();
        if (viewWidth <= 0 || viewHeight <= 0) {
            binding.imgEditPreview.post(() -> showBitmapFitForCompare(bitmap));
            return;
        }
        float scale = Math.min((float) viewWidth / bitmap.getWidth(), (float) viewHeight / bitmap.getHeight());
        Matrix matrix = new Matrix();
        matrix.setScale(scale, scale);
        matrix.postTranslate((viewWidth - bitmap.getWidth() * scale) / 2f,
                (viewHeight - bitmap.getHeight() * scale) / 2f);
        binding.imgEditPreview.setImageMatrix(matrix);
    }

    private void pushUndoState() {
        if (restoringState) return;
        undoStates.push(editState.copy());
        while (undoStates.size() > 40) undoStates.removeLast();
        redoStates.clear();
    }

    private void markEdited() {
        hasUnsavedEdits = !editState.isNeutral();
    }

    private void undoEdit() {
        if (undoStates.isEmpty()) return;
        redoStates.push(editState.copy());
        editState = undoStates.pop();
        restoreEditState();
    }

    private void redoEdit() {
        if (redoStates.isEmpty()) return;
        undoStates.push(editState.copy());
        editState = redoStates.pop();
        restoreEditState();
    }

    private void restoreEditState() {
        restoringState = true;
        adjustmentSettings = editState.adjustments;
        selectedFilter = editState.filter;
        binding.sbExposure.setProgress(Math.round(300 + adjustmentSettings.getExposure() * 100));
        binding.sbBrightness.setProgress(Math.round(100 + adjustmentSettings.getBrightness()));
        binding.sbHighlights.setProgress(Math.round(100 + adjustmentSettings.getHighlights()));
        binding.sbShadows.setProgress(Math.round(100 + adjustmentSettings.getShadows()));
        binding.sbContrast.setProgress(Math.round(adjustmentSettings.getContrast() * 100));
        binding.sbSaturation.setProgress(Math.round(adjustmentSettings.getSaturation() * 100));
        binding.sbTemperature.setProgress(Math.round(100 + adjustmentSettings.getTemperature()));
        binding.sbTint.setProgress(Math.round(100 + adjustmentSettings.getTint()));
        binding.sbFilterIntensity.setProgress(Math.round(editState.filterIntensity * 100));
        syncFilterSelection();
        binding.drawingOverlay.clear();
        markEdited();
        restoringState = false;
        schedulePreviewRender(true);
    }

    private void syncFilterSelection() {
        int checked = -1;
        switch (editState.filter) {
            case BitmapEditor.FILTER_VIVID: checked = binding.filterVivid.getId(); break;
            case BitmapEditor.FILTER_WARM: checked = binding.filterWarm.getId(); break;
            case BitmapEditor.FILTER_COOL: checked = binding.filterCool.getId(); break;
            case BitmapEditor.FILTER_MONO: checked = binding.filterGrayscale.getId(); break;
            case BitmapEditor.FILTER_DRAMATIC: checked = binding.filterSepia.getId(); break;
            case BitmapEditor.FILTER_FADE: checked = binding.filterFade.getId(); break;
            default: checked = binding.filterOriginal.getId(); break;
        }
        binding.chipGroupFilters.check(checked);
    }

    private Bitmap renderEditRecipe(Bitmap source, PhotoEditState state) {
        if (source == null) return null;
        Bitmap working = source;
        boolean ownsWorking = false;
        Bitmap strokeLayer = null;
        for (PhotoEditState.Operation operation : state.operations) {
            if (Thread.currentThread().isInterrupted()) {
                if (strokeLayer != null && !strokeLayer.isRecycled()) strokeLayer.recycle();
                if (ownsWorking && !working.isRecycled()) working.recycle();
                return null;
            }
            Bitmap next = working;
            if (operation.type != PhotoEditState.STROKE && strokeLayer != null) {
                next = working.copy(Bitmap.Config.ARGB_8888, true);
                new android.graphics.Canvas(next).drawBitmap(strokeLayer, 0f, 0f, null);
                strokeLayer.recycle(); strokeLayer = null;
                if (ownsWorking && !working.isRecycled()) working.recycle();
                working = next; ownsWorking = true;
            }
            if (operation.type == PhotoEditState.ROTATE) next = BitmapEditor.rotate(working, operation.value);
            else if (operation.type == PhotoEditState.FLIP) next = BitmapEditor.flip(working, operation.horizontal, operation.vertical);
            else if (operation.type == PhotoEditState.CROP) next = BitmapEditor.cropNormalized(working, operation.crop);
            else if (operation.type == PhotoEditState.STROKE) {
                if (strokeLayer == null) strokeLayer = Bitmap.createBitmap(working.getWidth(), working.getHeight(), Bitmap.Config.ARGB_8888);
                BitmapEditor.drawNormalizedStroke(strokeLayer, operation.points, operation.color, operation.strokeWidth, operation.erase);
                continue;
            }
            if (next != working) {
                if (ownsWorking && !working.isRecycled()) working.recycle();
                working = next;
                ownsWorking = true;
            }
        }
        if (strokeLayer != null) {
            Bitmap next = working.copy(Bitmap.Config.ARGB_8888, true);
            new android.graphics.Canvas(next).drawBitmap(strokeLayer, 0f, 0f, null);
            strokeLayer.recycle();
            if (ownsWorking && !working.isRecycled()) working.recycle();
            working = next; ownsWorking = true;
        }
        if (Thread.currentThread().isInterrupted()) {
            if (ownsWorking && !working.isRecycled()) working.recycle();
            return null;
        }
        Bitmap output = BitmapEditor.renderColor(working, state.adjustments, state.filter, state.filterIntensity);
        if (ownsWorking && output != working && !working.isRecycled()) working.recycle();
        return output;
    }

    private void schedulePreviewRender(boolean resetTransform) {
        if (originalBitmap == null || binding == null) return;
        if (pendingPreviewRender != null) mainHandler.removeCallbacks(pendingPreviewRender);
        if (previewFuture != null) previewFuture.cancel(true);
        int version = renderVersion.incrementAndGet();
        PhotoEditState snapshot = editState.copy();
        Bitmap source = originalBitmap;
        pendingPreviewRender = () -> {
            pendingPreviewRender = null;
            renderingPreview = true;
            previewFuture = renderExecutor.submit(() -> {
                Bitmap result = renderEditRecipe(source, snapshot);
                mainHandler.post(() -> {
                    if (version != renderVersion.get() || binding == null) {
                        if (result != null && !result.isRecycled()) result.recycle();
                        return;
                    }
                    renderingPreview = false;
                    if (result == null) return;
                    Bitmap previous = currentBitmap;
                    currentBitmap = result;
                    if (!comparingOriginal) {
                        binding.imgEditPreview.setImageBitmap(result);
                        if (resetTransform) resetPreviewTransform();
                        else updateImageBoundsForOverlays();
                    }
                    if (previous != null && previous != originalBitmap && previous != result && !previous.isRecycled()) previous.recycle();
                });
            });
        };
        mainHandler.postDelayed(pendingPreviewRender, 55);
    }

    private void updateFilterThumbnails() {
        if (originalBitmap == null) return;
        Bitmap sample = Bitmap.createScaledBitmap(originalBitmap, 52, 52, true);
        setFilterThumbnail(binding.filterOriginal, BitmapEditor.FILTER_ORIGINAL, sample);
        setFilterThumbnail(binding.filterVivid, BitmapEditor.FILTER_VIVID, sample);
        setFilterThumbnail(binding.filterWarm, BitmapEditor.FILTER_WARM, sample);
        setFilterThumbnail(binding.filterCool, BitmapEditor.FILTER_COOL, sample);
        setFilterThumbnail(binding.filterGrayscale, BitmapEditor.FILTER_MONO, sample);
        setFilterThumbnail(binding.filterSepia, BitmapEditor.FILTER_DRAMATIC, sample);
        setFilterThumbnail(binding.filterFade, BitmapEditor.FILTER_FADE, sample);
        if (sample != originalBitmap && !sample.isRecycled()) sample.recycle();
    }

    private void setFilterThumbnail(com.google.android.material.chip.Chip chip, int filter, Bitmap sample) {
        Bitmap thumbnail = filter == BitmapEditor.FILTER_ORIGINAL
                ? sample.copy(Bitmap.Config.ARGB_8888, false)
                : BitmapEditor.renderColor(sample, new AdjustmentSettings(), filter, 1f);
        chip.setChipIcon(new BitmapDrawable(getResources(), thumbnail));
        chip.setChipIconVisible(true);
        chip.setChipIconSize(getResources().getDisplayMetrics().density * 28f);
    }

    private void saveCopy() { saveEditedImage(false); }

    private void saveEditedImage(boolean overwrite) {
        if (saveInProgress || currentBitmap == null) return;
        saveInProgress = true;
        PhotoEditState snapshot = editState.copy();
        setSavingUi(true);
        renderExecutor.execute(() -> {
            FutureTarget<Bitmap> sourceTarget = null;
            Bitmap output;
            try {
                sourceTarget = Glide.with(getApplicationContext()).asBitmap().load(imageUri)
                        .submit(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL);
                Bitmap fullSource = sourceTarget.get();
                output = renderEditRecipe(fullSource, snapshot);
                if (output == fullSource) output = fullSource.copy(Bitmap.Config.ARGB_8888, true);
                Glide.with(getApplicationContext()).clear(sourceTarget);
                if (output == null) throw new IllegalStateException("Không thể kết xuất ảnh đã chỉnh sửa.");
            } catch (Exception error) {
                if (sourceTarget != null) Glide.with(getApplicationContext()).clear(sourceTarget);
                mainHandler.post(() -> finishSaveWithError(error.getMessage()));
                return;
            } catch (OutOfMemoryError exhausted) {
                if (sourceTarget != null) Glide.with(getApplicationContext()).clear(sourceTarget);
                mainHandler.post(() -> finishSaveWithError("Ảnh quá lớn để xuất an toàn trên bộ nhớ hiện có. Hãy thử ảnh nhỏ hơn hoặc giảm thao tác."));
                return;
            }
            final Bitmap renderedOutput = output;
            ImageSaveUtils.SaveCallback callback = new ImageSaveUtils.SaveCallback() {
                private void releaseOutput() { if (!renderedOutput.isRecycled()) renderedOutput.recycle(); }
                @Override public void onSuccess(Uri savedUri, String filePath) {
                    releaseOutput();
                    if (binding == null) return;
                    Intent result = new Intent();
                    result.setData(savedUri);
                    result.putExtra("saved_uri", savedUri.toString());
                    result.putExtra("saved_name", filePath);
                    setResult(RESULT_OK, result);
                    Toast.makeText(PhotoEditorActivity.this,
                            overwrite ? "Đã cập nhật ảnh gốc." : "Đã lưu bản sao vào thư viện.", Toast.LENGTH_LONG).show();
                    finish();
                }
                @Override public void onError(String error) {
                    releaseOutput();
                    finishSaveWithError(error);
                }
                @Override public void onPermissionRequired(IntentSender sender) {
                    releaseOutput();
                    saveInProgress = false;
                    setSavingUi(false);
                    try {
                        overwritePermissionLauncher.launch(new IntentSenderRequest.Builder(sender).build());
                    } catch (Exception launchError) {
                        Toast.makeText(PhotoEditorActivity.this,
                                "Không mở được yêu cầu quyền sửa ảnh. Ảnh gốc chưa được đổi; hãy lưu bản sao.", Toast.LENGTH_LONG).show();
                    }
                }
            };
            if (overwrite) ImageSaveUtils.overwriteOriginalBitmap(this, renderedOutput, imageUri, callback);
            else ImageSaveUtils.saveBitmapAsCopy(this, renderedOutput, callback);
        });
    }

    private void finishSaveWithError(String message) {
        saveInProgress = false;
        if (binding == null) return;
        setSavingUi(false);
        Toast.makeText(this, "Không lưu được ảnh: " + message, Toast.LENGTH_LONG).show();
    }

    private void setSavingUi(boolean saving) {
        if (binding == null) return;
        binding.progressBar.setVisibility(saving ? View.VISIBLE : View.GONE);
        setChildrenEnabled(binding.topBar, !saving);
        setChildrenEnabled(binding.bottomControlsContainer, !saving);
        binding.previewContainer.setEnabled(!saving);
        binding.imgEditPreview.setEnabled(!saving);
        binding.cropOverlay.setEnabled(!saving);
        binding.drawingOverlay.setEnabled(!saving);
        if (!saving) {
            binding.btnOverwrite.setEnabled(canOverwriteImage());
            binding.btnOverwrite.setAlpha(binding.btnOverwrite.isEnabled() ? 1f : 0.5f);
        }
    }

    private void setChildrenEnabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) setChildrenEnabled(group.getChildAt(i), enabled);
        }
    }

    private void confirmOverwrite() {
        if (!canOverwriteImage()) {
            Toast.makeText(this, "Chỉ có thể ghi đè ảnh gốc trong thư viện thiết bị.", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Ghi đè ảnh gốc?")
                .setMessage("Thao tác này sẽ thay thế nội dung ảnh hiện tại bằng phiên bản đã chỉnh sửa. Bạn có muốn tiếp tục?")
                .setPositiveButton("Tiếp tục", (dialog, which) -> requestOverwritePermission())
                .setNegativeButton("Hủy", null)
                .show();
    }

    private void requestOverwritePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                PendingIntent request = android.provider.MediaStore.createWriteRequest(
                        getContentResolver(), Collections.singletonList(imageUri));
                overwritePermissionLauncher.launch(new IntentSenderRequest.Builder(request.getIntentSender()).build());
            } catch (Exception error) {
                Toast.makeText(this, "Không thể xin quyền ghi ảnh này. Hãy lưu bản sao.", Toast.LENGTH_LONG).show();
            }
        } else {
            overwriteOriginal();
        }
    }

    private void overwriteOriginal() { saveEditedImage(true); }

    private void confirmCancel() {
        if (!hasUnsavedEdits && !binding.drawingOverlay.hasPaths()) {
            finish();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Hủy chỉnh sửa?")
                .setMessage("Các thay đổi chưa lưu sẽ bị mất. Bạn có muốn thoát?")
                .setPositiveButton("Bỏ thay đổi", (dialog, which) -> finish())
                .setNegativeButton("Hủy", null)
                .setNeutralButton("Tiếp tục chỉnh sửa", null)
                .show();
    }

    private void showSaveOptions() {
        String[] options = binding.btnOverwrite.isEnabled()
                ? new String[]{"Lưu bản sao", "Ghi đè ảnh gốc"}
                : new String[]{"Lưu bản sao"};
        new AlertDialog.Builder(this)
                .setTitle("Lưu chỉnh sửa")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) saveCopy(); else confirmOverwrite();
                })
                .setNegativeButton("Tiếp tục chỉnh sửa", null)
                .show();
    }

    private boolean hasUnsavedChanges() {
        return hasUnsavedEdits || (binding != null && binding.drawingOverlay.hasPaths());
    }

    @Override
    protected void onDestroy() {
        renderVersion.incrementAndGet();
        if (pendingPreviewRender != null) mainHandler.removeCallbacks(pendingPreviewRender);
        if (previewFuture != null) previewFuture.cancel(true);
        renderExecutor.shutdownNow();
        if (binding != null) binding = null;
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (hasUnsavedChanges()) confirmCancel();
        else super.onBackPressed();
    }
}
