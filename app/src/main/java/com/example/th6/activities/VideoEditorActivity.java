package com.example.th6.activities;

import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.Manifest;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import com.example.th6.R;
import com.example.th6.databinding.ActivityVideoEditorBinding;
import com.example.th6.models.LocalMediaPhoto;
import com.example.th6.utils.VideoDurationFormatter;
import com.example.th6.utils.MutablePreviewRgbMatrix;
import com.example.th6.utils.VideoSaveUtils;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VideoEditorActivity extends AppCompatActivity {
    private static final String STATE_TRIM_START = "video_editor_trim_start";
    private static final String STATE_TRIM_END = "video_editor_trim_end";
    private static final String STATE_ADJUSTMENTS = "video_editor_adjustments";
    private static final String STATE_FILTER = "video_editor_filter";
    private static final String STATE_MUTE = "video_editor_mute";
    private static final String STATE_POSITION = "video_editor_position";
    private static final String STATE_MODE = "video_editor_mode";
    private static final String STATE_PLAYING = "video_editor_playing";

    private static final int[] ADJUSTMENT_DEFAULTS = {100, 50, 100, 30, 100, 100, 100};
    private static final int[] ADJUSTMENT_MAXIMUMS = {200, 150, 200, 60, 200, 200, 200};
    private static final int[] ADJUSTMENT_LABELS = {
            R.string.video_editor_adjustment_brightness,
            R.string.video_editor_adjustment_contrast,
            R.string.video_editor_adjustment_saturation,
            R.string.video_editor_adjustment_exposure,
            R.string.video_editor_adjustment_highlights,
            R.string.video_editor_adjustment_shadows,
            R.string.video_editor_adjustment_temperature
    };
    private static final int[] FILTER_LABELS = {
            R.string.video_editor_filter_original,
            R.string.video_editor_filter_vivid,
            R.string.video_editor_filter_warm,
            R.string.video_editor_filter_cool,
            R.string.video_editor_filter_mono,
            R.string.video_editor_filter_contrast,
            R.string.video_editor_filter_blur
    };

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService thumbnailExecutor = Executors.newSingleThreadExecutor();
    private final int[] adjustmentValues = ADJUSTMENT_DEFAULTS.clone();
    private final List<MaterialCardView> filterCards = new ArrayList<>();
    private final List<Bitmap> timelineBitmaps = new ArrayList<>();
    private final List<Bitmap> filterBitmaps = new ArrayList<>();
    private final Runnable playbackTicker = new Runnable() {
        @Override public void run() {
            if (player != null && binding != null && player.getPlaybackState() == Player.STATE_READY) {
                long position = player.getCurrentPosition();
                if (player.isPlaying() && trimEndMs > trimStartMs
                        && (position < trimStartMs || position >= trimEndMs)) {
                    player.seekTo(trimStartMs);
                    position = trimStartMs;
                }
                binding.trimTimeline.setPlayhead(position);
                mainHandler.postDelayed(this, 100L);
            }
        }
    };

    private ActivityVideoEditorBinding binding;
    private ExoPlayer player;
    private final MutablePreviewRgbMatrix previewColorMatrix = new MutablePreviewRgbMatrix();
    private LocalMediaPhoto videoItem;
    private Uri videoUri;
    private String activeVideoSource;
    private boolean cloudFallbackAttempted;
    private Bitmap filterSourceBitmap;
    private long totalDurationMs;
    private long trimStartMs;
    private long trimEndMs;
    private long restoredPositionMs;
    private int selectedFilter;
    private int activeAdjustment;
    private int restoredMode = R.id.btnTabTrim;
    private boolean timelineInitialized;
    private boolean restoringState;
    private boolean exporting;
    private boolean resumePlaybackOnStart;
    private boolean shouldResumeAfterRestore;
    private boolean restoredMuteAudio;
    private boolean overwritePermissionGranted;
    private boolean resumeAfterOverwriteError;
    private boolean restorePlayerAfterOverwriteError;
    private ActivityResultLauncher<IntentSenderRequest> overwriteConsentLauncher;
    private ActivityResultLauncher<String> legacyWritePermissionLauncher;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        overwriteConsentLauncher = registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(), result -> {
            if (result.getResultCode() == RESULT_OK) {
                overwritePermissionGranted = true;
                beginOverwrite();
            }
            else Toast.makeText(this, "Chưa cấp quyền ghi đè; video gốc chưa bị thay đổi.", Toast.LENGTH_LONG).show();
        });
        legacyWritePermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) beginOverwrite();
            else Toast.makeText(this, "Cần quyền ghi bộ nhớ để lưu đè video local.", Toast.LENGTH_LONG).show();
        });
        binding = ActivityVideoEditorBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        if (getIntent().hasExtra("video")) {
            videoItem = (LocalMediaPhoto) getIntent().getSerializableExtra("video");
            if (videoItem != null) {
                totalDurationMs = Math.max(0L, videoItem.getDurationMs());
                trimEndMs = totalDurationMs;
            }
        }
        String source = getIntent().getStringExtra("video_uri");
        if (source != null && !source.isEmpty()) videoUri = Uri.parse(source);
        else if (videoItem != null && videoItem.getContentUri() != null && !videoItem.getContentUri().isEmpty()) {
            videoUri = Uri.parse(videoItem.getContentUri());
        }
        if (videoUri == null) {
            Toast.makeText(this, "Không tìm thấy video để chỉnh sửa", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        if (state != null) restoreState(state);
        setupControls();
        binding.btnOverwrite.setVisibility(View.VISIBLE);
        binding.btnOverwrite.setEnabled(canOverwriteLocal());
        binding.btnOverwrite.setAlpha(canOverwriteLocal() ? 1f : 0.55f);
        if (!canOverwriteLocal()) binding.btnOverwrite.setContentDescription(getString(R.string.video_editor_overwrite_local_only));
        binding.btnOverwrite.setOnClickListener(v -> confirmOverwrite());
        if (VideoSaveUtils.hasPendingOverwriteRecovery(this)) {
            binding.btnSaveCopy.setEnabled(false);
            binding.btnOverwrite.setEnabled(false);
            binding.progressBar.setVisibility(View.VISIBLE);
            binding.tvExportProgress.setText("Đang khôi phục video gốc…");
            VideoSaveUtils.recoverInterruptedOverwriteAsync(this, error -> {
                if (binding == null || isFinishing() || isDestroyed()) return;
                binding.progressBar.setVisibility(View.GONE);
                if (error != null) {
                    binding.tvExportProgress.setText("Khôi phục video cần xử lý");
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show();
                } else {
                    binding.btnSaveCopy.setEnabled(true);
                    restoreOverwriteButtonState();
                    setupPlayer();
                }
            });
        } else {
            setupPlayer();
        }
    }

    private void restoreState(Bundle state) {
        restoringState = true;
        trimStartMs = Math.max(0L, state.getLong(STATE_TRIM_START, trimStartMs));
        long savedEnd = state.getLong(STATE_TRIM_END, trimEndMs);
        if (savedEnd > 0L) trimEndMs = savedEnd;
        int[] savedAdjustments = state.getIntArray(STATE_ADJUSTMENTS);
        if (savedAdjustments != null && savedAdjustments.length == adjustmentValues.length) {
            System.arraycopy(savedAdjustments, 0, adjustmentValues, 0, adjustmentValues.length);
        }
        selectedFilter = Math.max(0, Math.min(FILTER_LABELS.length - 1, state.getInt(STATE_FILTER, 0)));
        restoredPositionMs = Math.max(0L, state.getLong(STATE_POSITION, 0L));
        shouldResumeAfterRestore = state.getBoolean(STATE_PLAYING, false);
        restoredMode = state.getInt(STATE_MODE, R.id.btnTabTrim);
        activeAdjustment = Math.max(0, Math.min(ADJUSTMENT_LABELS.length - 1, state.getInt("video_editor_active_adjustment", 0)));
        restoredMuteAudio = state.getBoolean(STATE_MUTE, false);
    }

    private void setupControls() {
        binding.btnCancel.setOnClickListener(v -> confirmCancel());
        binding.btnSaveCopy.setOnClickListener(v -> export());
        binding.modeTabs.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            if (checkedId == R.id.btnTabTrim) showMode(0);
            else if (checkedId == R.id.btnTabAdjust) showMode(1);
            else if (checkedId == R.id.btnTabFilters) showMode(2);
        });

        binding.trimTimeline.setListener(new com.example.th6.views.VideoTrimTimelineView.Listener() {
            @Override public void onRangeChanged(long startMs, long endMs) {
                trimStartMs = startMs;
                trimEndMs = endMs;
                updateTrimLabels();
                if (player != null && (player.getCurrentPosition() < trimStartMs || player.getCurrentPosition() > trimEndMs)) {
                    player.seekTo(trimStartMs);
                }
            }

            @Override public void onPlayheadChanged(long positionMs) {
                if (player != null) player.seekTo(Math.max(trimStartMs, Math.min(trimEndMs, positionMs)));
            }
        });

        setupAdjustmentChips();
        binding.sbActiveAdjustment.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                adjustmentValues[activeAdjustment] = progress;
                updateAdjustmentValue();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { schedulePreviewEffects(); }
        });
        binding.btnResetAdjustments.setOnClickListener(v -> resetAdjustments());
        binding.switchMuteAudio.setOnCheckedChangeListener((button, checked) -> {
            if (player != null) player.setVolume(checked ? 0f : 1f);
        });

        setupFilterCards();
        binding.switchMuteAudio.setChecked(restoredMuteAudio);
        if (restoringState) binding.modeTabs.check(restoredMode);
        updateTrimLabels();
        showMode(restoredMode == R.id.btnTabAdjust ? 1 : restoredMode == R.id.btnTabFilters ? 2 : 0);
        restoringState = false;
    }

    private void setupAdjustmentChips() {
        for (int i = 0; i < ADJUSTMENT_LABELS.length; i++) {
            final int index = i;
            Chip chip = new Chip(this);
            chip.setId(View.generateViewId());
            chip.setText(ADJUSTMENT_LABELS[i]);
            chip.setCheckable(true);
            chip.setClickable(true);
            chip.setEnsureMinTouchTargetSize(true);
            chip.setOnClickListener(v -> selectAdjustment(index));
            binding.adjustmentChooser.addView(chip);
        }
        selectAdjustment(activeAdjustment);
    }

    private void selectAdjustment(int index) {
        activeAdjustment = Math.max(0, Math.min(ADJUSTMENT_LABELS.length - 1, index));
        for (int i = 0; i < binding.adjustmentChooser.getChildCount(); i++) {
            View child = binding.adjustmentChooser.getChildAt(i);
            if (child instanceof Chip) ((Chip) child).setChecked(i == activeAdjustment);
        }
        binding.sbActiveAdjustment.setMax(ADJUSTMENT_MAXIMUMS[activeAdjustment]);
        binding.sbActiveAdjustment.setProgress(adjustmentValues[activeAdjustment]);
        updateAdjustmentValue();
    }

    private void updateAdjustmentValue() {
        if (binding == null) return;
        binding.tvActiveAdjustment.setText(ADJUSTMENT_LABELS[activeAdjustment]);
        int value = adjustmentValues[activeAdjustment];
        String label;
        switch (activeAdjustment) {
            case 0: label = Integer.toString(value - 100); break;
            case 1: label = String.format(Locale.getDefault(), "%.2f", .5f + value / 100f); break;
            case 2: label = String.format(Locale.getDefault(), "%.2f", value / 100f); break;
            case 3: label = String.format(Locale.getDefault(), "%.1f EV", (value - 30) / 10f); break;
            default: label = Integer.toString(value - 100); break;
        }
        binding.tvActiveAdjustmentValue.setText(label);
    }

    private void resetAdjustments() {
        System.arraycopy(ADJUSTMENT_DEFAULTS, 0, adjustmentValues, 0, adjustmentValues.length);
        selectAdjustment(activeAdjustment);
        schedulePreviewEffects();
    }

    private void setupFilterCards() {
        int horizontalMargin = dp(4);
        for (int i = 0; i < FILTER_LABELS.length; i++) {
            final int index = i;
            MaterialCardView card = new MaterialCardView(this);
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(dp(92), dp(100));
            cardParams.setMarginEnd(horizontalMargin);
            card.setLayoutParams(cardParams);
            card.setRadius(dp(12));
            card.setCardElevation(dp(1));
            card.setClickable(true);
            card.setFocusable(true);
            card.setContentDescription(getString(FILTER_LABELS[i]));

            android.widget.LinearLayout content = new android.widget.LinearLayout(this);
            content.setOrientation(android.widget.LinearLayout.VERTICAL);
            android.widget.ImageView thumbnail = new android.widget.ImageView(this);
            thumbnail.setId(View.generateViewId());
            thumbnail.setTag("filter_thumbnail_" + i);
            thumbnail.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            content.addView(thumbnail, new android.widget.LinearLayout.LayoutParams(-1, dp(68)));
            android.widget.TextView title = new android.widget.TextView(this);
            title.setText(FILTER_LABELS[i]);
            title.setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnSurface));
            title.setTextSize(11f);
            title.setGravity(android.view.Gravity.CENTER);
            title.setMaxLines(1);
            content.addView(title, new android.widget.LinearLayout.LayoutParams(-1, 0, 1f));
            card.addView(content);
            card.setOnClickListener(v -> {
                selectedFilter = index;
                updateFilterCardSelection();
                schedulePreviewEffects();
            });
            filterCards.add(card);
            binding.filterOptions.addView(card);
        }
        updateFilterCardSelection();
    }

    private void updateFilterCardSelection() {
        if (binding == null) return;
        int selectedColor = resolveThemeColor(androidx.appcompat.R.attr.colorPrimary);
        int surfaceColor = resolveThemeColor(com.google.android.material.R.attr.colorSurface);
        int outlineColor = resolveThemeColor(com.google.android.material.R.attr.colorOutline);
        for (int i = 0; i < filterCards.size(); i++) {
            MaterialCardView card = filterCards.get(i);
            boolean selected = i == selectedFilter;
            card.setStrokeWidth(dp(selected ? 2 : 1));
            card.setStrokeColor(selected ? selectedColor : outlineColor);
            card.setCardBackgroundColor(selected
                    ? resolveThemeColor(com.google.android.material.R.attr.colorPrimaryContainer) : surfaceColor);
        }
    }

    private void showMode(int mode) {
        if (binding == null) return;
        binding.panelTrim.setVisibility(mode == 0 ? View.VISIBLE : View.GONE);
        binding.panelAdjust.setVisibility(mode == 1 ? View.VISIBLE : View.GONE);
        binding.panelFilters.setVisibility(mode == 2 ? View.VISIBLE : View.GONE);
    }

    private void setupPlayer() {
        player = new ExoPlayer.Builder(this).build();
        binding.editorPlayerView.setPlayer(player);
        activeVideoSource = videoUri == null ? null : videoUri.toString();
        cloudFallbackAttempted = false;
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_READY && !timelineInitialized) initializeTimeline();
                else if (playbackState == Player.STATE_READY && restorePlayerAfterOverwriteError) {
                    player.seekTo(Math.min(restoredPositionMs, totalDurationMs));
                    if (resumeAfterOverwriteError) player.play();
                    resumeAfterOverwriteError = false;
                    restorePlayerAfterOverwriteError = false;
                }
            }

            @Override public void onPlayerError(PlaybackException error) {
                if (switchEditorToCloudSource()) return;
                if (binding != null) binding.tvExportProgress.setText("Không thể phát video này.");
                Toast.makeText(VideoEditorActivity.this, "Không thể tải hoặc phát video: " + error.getErrorCodeName(), Toast.LENGTH_LONG).show();
            }
        });
        player.setMediaItem(MediaItem.fromUri(videoUri));
        player.setVolume(binding.switchMuteAudio.isChecked() ? 0f : 1f);
        // Install one mutable shader before prepare. Updating its matrix does not tear down the
        // decoder or rebuild Media3's GL graph while the user adjusts color controls.
        applyPreviewEffects();
        player.setVideoEffects(Collections.singletonList(previewColorMatrix));
        player.prepare();
        mainHandler.post(playbackTicker);
    }

    private boolean switchEditorToCloudSource() {
        String cloudUrl = videoItem == null ? null : videoItem.getCloudUrl();
        if (cloudFallbackAttempted || cloudUrl == null || cloudUrl.isEmpty()
                || cloudUrl.equals(activeVideoSource) || player == null) return false;
        cloudFallbackAttempted = true;
        activeVideoSource = cloudUrl;
        videoUri = Uri.parse(cloudUrl);
        timelineInitialized = false;
        player.setMediaItem(MediaItem.fromUri(videoUri));
        player.prepare();
        return true;
    }

    private void initializeTimeline() {
        timelineInitialized = true;
        if (totalDurationMs <= 0L) {
            long duration = player.getDuration();
            totalDurationMs = duration > 0L ? duration : 1L;
            trimEndMs = totalDurationMs;
        }
        trimStartMs = Math.max(0L, Math.min(trimStartMs, Math.max(0L, totalDurationMs - 500L)));
        trimEndMs = Math.max(trimStartMs, Math.min(trimEndMs, totalDurationMs));
        if (trimEndMs - trimStartMs < 500L && totalDurationMs >= 500L) {
            trimEndMs = Math.min(totalDurationMs, trimStartMs + 500L);
        }
        binding.trimTimeline.setDuration(totalDurationMs);
        binding.trimTimeline.setRange(trimStartMs, trimEndMs);
        updateTrimLabels();
        long requestedPosition = restoredPositionMs >= trimStartMs && restoredPositionMs <= trimEndMs
                ? restoredPositionMs : trimStartMs;
        player.seekTo(Math.min(requestedPosition, totalDurationMs));
        if (shouldResumeAfterRestore) player.play();
        loadThumbnails();
    }

    private void loadThumbnails() {
        final Uri source = videoUri;
        final long duration = totalDurationMs;
        thumbnailExecutor.execute(() -> {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            List<Drawable> frames = new ArrayList<>();
            List<Bitmap> ownedTimelineFrames = new ArrayList<>();
            Bitmap baseFrame = null;
            List<Bitmap> filterFrames = new ArrayList<>();
            try {
                String scheme = source.getScheme();
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                    retriever.setDataSource(source.toString(), Collections.emptyMap());
                } else {
                    retriever.setDataSource(this, source);
                }
                int count = (int) Math.max(4L, Math.min(12L, duration / 4000L));
                for (int i = 0; i < count; i++) {
                    long timeUs = (long) ((i + .5d) * duration / count) * 1000L;
                    Bitmap frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    if (frame == null) continue;
                    Bitmap scaled = centerCrop(frame, 160, 90);
                    if (scaled != frame) frame.recycle();
                    ownedTimelineFrames.add(scaled);
                    frames.add(new BitmapDrawable(getResources(), scaled));
                    if (baseFrame == null) baseFrame = scaled.copy(Bitmap.Config.ARGB_8888, false);
                }
                if (baseFrame != null) {
                    for (int i = 0; i < FILTER_LABELS.length; i++) filterFrames.add(makeFilterThumbnail(baseFrame, i));
                }
            } catch (Exception ignored) {
            } finally {
                try { retriever.release(); } catch (Exception ignored) { }
            }
            final Bitmap finalBaseFrame = baseFrame;
            final List<Bitmap> finalTimelineFrames = ownedTimelineFrames;
            final List<Bitmap> finalFilterFrames = filterFrames;
            runOnUiThread(() -> {
                if (binding == null || isFinishing() || isDestroyed()) {
                    recycleBitmaps(finalTimelineFrames);
                    recycleBitmaps(finalFilterFrames);
                    if (finalBaseFrame != null && !finalBaseFrame.isRecycled()) finalBaseFrame.recycle();
                    return;
                }
                timelineBitmaps.addAll(finalTimelineFrames);
                filterBitmaps.addAll(finalFilterFrames);
                filterSourceBitmap = finalBaseFrame;
                binding.trimTimeline.setFrames(frames);
                for (int i = 0; i < filterCards.size() && i < finalFilterFrames.size(); i++) {
                    android.widget.ImageView image = binding.filterOptions.findViewWithTag("filter_thumbnail_" + i);
                    if (image != null) image.setImageBitmap(finalFilterFrames.get(i));
                }
            });
        });
    }

    private Bitmap centerCrop(Bitmap source, int width, int height) {
        float scale = Math.max(width / (float) source.getWidth(), height / (float) source.getHeight());
        int scaledWidth = Math.max(width, Math.round(source.getWidth() * scale));
        int scaledHeight = Math.max(height, Math.round(source.getHeight() * scale));
        Bitmap scaled = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true);
        int x = Math.max(0, (scaledWidth - width) / 2);
        int y = Math.max(0, (scaledHeight - height) / 2);
        Bitmap result = Bitmap.createBitmap(scaled, x, y, width, height);
        if (scaled != source && scaled != result) scaled.recycle();
        return result;
    }

    private Bitmap makeFilterThumbnail(Bitmap source, int filter) {
        int width = source.getWidth(), height = source.getHeight();
        int[] pixels = new int[width * height];
        int[] output = new int[pixels.length];
        source.getPixels(pixels, 0, width, 0, 0, width, height);
        if (filter == 6) {
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int red = 0, green = 0, blue = 0, count = 0;
                for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                    int sx = Math.max(0, Math.min(width - 1, x + dx));
                    int sy = Math.max(0, Math.min(height - 1, y + dy));
                    int pixel = pixels[sy * width + sx];
                    red += (pixel >> 16) & 255; green += (pixel >> 8) & 255; blue += pixel & 255; count++;
                }
                output[y * width + x] = 0xff000000 | ((red / count) << 16) | ((green / count) << 8) | (blue / count);
            }
        } else {
            for (int i = 0; i < pixels.length; i++) {
                int pixel = pixels[i];
                float r = (pixel >> 16) & 255, g = (pixel >> 8) & 255, b = pixel & 255;
                if (filter == 1) {
                    float luma = .2126f * r + .7152f * g + .0722f * b;
                    r = luma + (r - luma) * 1.25f; g = luma + (g - luma) * 1.25f; b = luma + (b - luma) * 1.25f;
                } else if (filter == 2) { r *= 1.08f; g *= 1.01f; b *= .94f; }
                else if (filter == 3) { r *= .94f; g *= 1.01f; b *= 1.08f; }
                else if (filter == 4) { float gray = .2126f * r + .7152f * g + .0722f * b; r = gray; g = gray; b = gray; }
                else if (filter == 5) {
                    r = 128f + (r - 128f) * 1.35f;
                    g = 128f + (g - 128f) * 1.35f;
                    b = 128f + (b - 128f) * 1.35f;
                }
                output[i] = 0xff000000 | (clampChannel(r) << 16) | (clampChannel(g) << 8) | clampChannel(b);
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(output, 0, width, 0, 0, width, height);
        return bitmap;
    }

    private int clampChannel(float value) { return Math.max(0, Math.min(255, Math.round(value))); }

    private List<androidx.media3.common.Effect> effects() {
        return VideoSaveUtils.createEffects(
                adjustmentValues[0] - 100,
                adjustmentValues[1],
                adjustmentValues[2],
                adjustmentValues[3] - 30,
                adjustmentValues[4] - 100,
                adjustmentValues[5] - 100,
                adjustmentValues[6] - 100,
                selectedFilter);
    }

    private void applyPreviewEffects() {
        if (binding == null) return;
        previewColorMatrix.update(
                adjustmentValues[0] - 100,
                adjustmentValues[1],
                adjustmentValues[2],
                adjustmentValues[3] - 30,
                adjustmentValues[4] - 100,
                adjustmentValues[5] - 100,
                adjustmentValues[6] - 100,
                selectedFilter);
    }

    private void schedulePreviewEffects() {
        mainHandler.removeCallbacks(previewEffectsUpdate);
        mainHandler.postDelayed(previewEffectsUpdate, 55L);
    }

    private final Runnable previewEffectsUpdate = this::applyPreviewEffects;

    private boolean canOverwriteLocal() {
        if (videoItem == null || !videoItem.isVideo() || videoItem.getContentUri() == null
                || videoItem.getMimeType() == null || !"video/mp4".equalsIgnoreCase(videoItem.getMimeType())
                || !videoUri.toString().equals(videoItem.getContentUri())) return false;
        return "content".equalsIgnoreCase(videoUri.getScheme())
                && "media".equalsIgnoreCase(videoUri.getAuthority())
                && videoUri.getPath() != null && videoUri.getPath().contains("/video/media/");
    }

    private void confirmOverwrite() {
        if (!canOverwriteLocal()) {
            Toast.makeText(this, "Lưu đè chỉ hỗ trợ video local trong MediaStore. Video cloud có thể xuất thành bản mới.", Toast.LENGTH_LONG).show();
            return;
        }
        if (trimEndMs - trimStartMs < 500L) {
            Toast.makeText(this, "Đoạn video phải dài ít nhất nửa giây", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Lưu đè video gốc?")
                .setMessage("Nội dung video local hiện tại sẽ bị thay bằng đoạn đã chỉnh sửa. Ứng dụng sẽ giữ bản khôi phục tạm trong lúc ghi.")
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Tiếp tục", (dialog, which) -> requestOverwriteAccess())
                .show();
    }

    private void requestOverwriteAccess() {
        if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.P
                && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            legacyWritePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            return;
        }
        try {
            IntentSender sender = overwritePermissionGranted ? null : VideoSaveUtils.getWriteConsentRequest(this, videoUri);
            if (sender != null) overwriteConsentLauncher.launch(new IntentSenderRequest.Builder(sender).build());
            else beginOverwrite();
        } catch (Exception error) {
            Toast.makeText(this, "Android không cấp quyền ghi an toàn cho video này. Video gốc chưa bị thay đổi; hãy xuất video mới.", Toast.LENGTH_LONG).show();
        }
    }

    private void beginOverwrite() {
        if (exporting || binding == null) return;
        exporting = true;
        binding.btnSaveCopy.setEnabled(false);
        binding.btnOverwrite.setEnabled(false);
        binding.progressBar.setVisibility(View.VISIBLE);
        binding.tvExportProgress.setText("Đang xử lý và kiểm tra video…");
        if (player != null) {
            restoredPositionMs = Math.max(trimStartMs, Math.min(trimEndMs, player.getCurrentPosition()));
            resumeAfterOverwriteError = player.isPlaying();
            restorePlayerAfterOverwriteError = true;
            binding.editorPlayerView.setPlayer(null);
            player.release();
            player = null;
        }
        VideoSaveUtils.overwriteVideo(this, videoUri, videoItem.getDateModified(), trimStartMs, trimEndMs,
                binding.switchMuteAudio.isChecked(), effects(), new VideoSaveUtils.SaveVideoCallback() {
                    @Override public void onSuccess(Uri uri, String name) {
                        finishSave("Đã lưu đè video local.");
                    }
                    @Override public void onError(String message) {
                        exporting = false;
                        if (binding == null) return;
                        binding.progressBar.setVisibility(View.GONE);
                        binding.btnSaveCopy.setEnabled(true);
                        restoreOverwriteButtonState();
                        binding.tvExportProgress.setText("Lưu đè thất bại");
                        if (!VideoSaveUtils.hasPendingOverwriteRecovery(VideoEditorActivity.this)) setupPlayer();
                        else {
                            binding.btnSaveCopy.setEnabled(false);
                            binding.btnOverwrite.setEnabled(false);
                        }
                        Toast.makeText(VideoEditorActivity.this, message, Toast.LENGTH_LONG).show();
                    }
                    @Override public void onPermissionRequired(IntentSender sender) {
                        exporting = false;
                        if (binding != null) {
                            binding.progressBar.setVisibility(View.GONE);
                            binding.btnSaveCopy.setEnabled(true);
                            restoreOverwriteButtonState();
                        }
                        try { overwriteConsentLauncher.launch(new IntentSenderRequest.Builder(sender).build()); }
                        catch (Exception error) { Toast.makeText(VideoEditorActivity.this, "Không mở được yêu cầu quyền ghi. Video gốc chưa bị thay đổi.", Toast.LENGTH_LONG).show(); }
                    }
                });
    }

    private void finishSave(String message) {
        exporting = false;
        if (binding == null) return;
        binding.progressBar.setVisibility(View.GONE);
        binding.tvExportProgress.setText("Hoàn tất");
        setResult(RESULT_OK);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        finish();
    }

    private void restoreOverwriteButtonState() {
        if (binding == null) return;
        boolean available = canOverwriteLocal();
        binding.btnOverwrite.setEnabled(available);
        binding.btnOverwrite.setAlpha(available ? 1f : 0.55f);
        if (!available) binding.btnOverwrite.setContentDescription(getString(R.string.video_editor_overwrite_local_only));
    }

    private void updateTrimLabels() {
        if (binding == null) return;
        binding.tvTrimStart.setText(getString(R.string.video_editor_trim_start, VideoDurationFormatter.formatDuration(trimStartMs)));
        binding.tvTrimEnd.setText(getString(R.string.video_editor_trim_end, VideoDurationFormatter.formatDuration(trimEndMs)));
        binding.tvTrimDuration.setText(getString(R.string.video_editor_trim_duration,
                VideoDurationFormatter.formatDuration(Math.max(0L, trimEndMs - trimStartMs))));
        binding.trimTimeline.setRange(trimStartMs, trimEndMs);
    }

    private void export() {
        if (exporting) return;
        if (trimEndMs - trimStartMs < 500L) {
            Toast.makeText(this, "Đoạn video phải dài ít nhất nửa giây", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Xuất video mới?")
                .setMessage("Video gốc sẽ được giữ nguyên.")
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Xuất", (dialog, which) -> {
                    exporting = true;
                    binding.btnSaveCopy.setEnabled(false);
                    binding.btnOverwrite.setEnabled(false);
                    binding.progressBar.setVisibility(View.VISIBLE);
                    binding.tvExportProgress.setText("Đang xuất…");
                    VideoSaveUtils.trimAndExportVideo(this, videoUri, trimStartMs, trimEndMs,
                            binding.switchMuteAudio.isChecked(), effects(), new VideoSaveUtils.SaveVideoCallback() {
                                @Override public void onSuccess(Uri uri, String name) {
                                    exporting = false;
                                    if (binding == null) return;
                                    binding.progressBar.setVisibility(View.GONE);
                                    binding.btnSaveCopy.setEnabled(true);
                                    restoreOverwriteButtonState();
                                    binding.tvExportProgress.setText("Hoàn tất");
                                    setResult(RESULT_OK);
                                    Toast.makeText(VideoEditorActivity.this, "Đã xuất video: " + name, Toast.LENGTH_LONG).show();
                                    finish();
                                }

                                @Override public void onError(String message) {
                                    exporting = false;
                                    if (binding == null) return;
                                    binding.progressBar.setVisibility(View.GONE);
                                    binding.btnSaveCopy.setEnabled(true);
                                    restoreOverwriteButtonState();
                                    binding.tvExportProgress.setText("Xuất thất bại");
                                    Toast.makeText(VideoEditorActivity.this, message, Toast.LENGTH_LONG).show();
                                }
                            });
                }).show();
    }

    private void confirmCancel() {
        new AlertDialog.Builder(this)
                .setTitle("Hủy chỉnh sửa?")
                .setMessage("Các thay đổi chưa xuất sẽ bị mất.")
                .setNegativeButton("Tiếp tục chỉnh sửa", null)
                .setPositiveButton("Thoát", (dialog, which) -> finish())
                .show();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private int resolveThemeColor(int attribute) {
        android.util.TypedValue value = new android.util.TypedValue();
        getTheme().resolveAttribute(attribute, value, true);
        if (value.resourceId != 0) return ContextCompat.getColor(this, value.resourceId);
        return value.data;
    }

    private void recycleBitmaps(List<Bitmap> bitmaps) {
        for (Bitmap bitmap : bitmaps) if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        bitmaps.clear();
    }

    @Override protected void onStart() {
        super.onStart();
        if (resumePlaybackOnStart && player != null) player.play();
        resumePlaybackOnStart = false;
    }

    @Override protected void onStop() {
        if (player != null) {
            resumePlaybackOnStart = player.isPlaying();
            player.pause();
        }
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putLong(STATE_TRIM_START, trimStartMs);
        outState.putLong(STATE_TRIM_END, trimEndMs);
        outState.putIntArray(STATE_ADJUSTMENTS, adjustmentValues.clone());
        outState.putInt(STATE_FILTER, selectedFilter);
        outState.putInt("video_editor_active_adjustment", activeAdjustment);
        outState.putBoolean(STATE_MUTE, binding != null && binding.switchMuteAudio.isChecked());
        outState.putLong(STATE_POSITION, player == null ? restoredPositionMs : player.getCurrentPosition());
        outState.putInt(STATE_MODE, binding == null ? R.id.btnTabTrim : binding.modeTabs.getCheckedButtonId());
        outState.putBoolean(STATE_PLAYING, player != null && player.isPlaying());
    }

    @Override protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        thumbnailExecutor.shutdownNow();
        if (binding != null) {
            binding.editorPlayerView.setPlayer(null);
            binding.trimTimeline.setFrames(Collections.emptyList());
            for (MaterialCardView card : filterCards) {
                android.widget.ImageView image = card.findViewWithTag("filter_thumbnail_" + filterCards.indexOf(card));
                if (image != null) image.setImageDrawable(null);
            }
        }
        if (player != null) {
            player.release();
            player = null;
        }
        recycleBitmaps(timelineBitmaps);
        recycleBitmaps(filterBitmaps);
        if (filterSourceBitmap != null && !filterSourceBitmap.isRecycled()) filterSourceBitmap.recycle();
        binding = null;
        super.onDestroy();
    }
}
