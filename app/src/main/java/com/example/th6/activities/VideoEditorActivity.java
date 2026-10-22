package com.example.th6.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;

import com.example.th6.databinding.ActivityVideoEditorBinding;
import com.example.th6.models.LocalMediaPhoto;
import com.example.th6.utils.VideoDurationFormatter;
import com.example.th6.utils.VideoSaveUtils;

public class VideoEditorActivity extends AppCompatActivity {

    private ActivityVideoEditorBinding binding;
    private ExoPlayer player;
    private LocalMediaPhoto videoItem;
    private Uri videoUri;

    private long totalDurationMs = 0;
    private long trimStartMs = 0;
    private long trimEndMs = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityVideoEditorBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        if (getIntent().hasExtra("video")) {
            videoItem = (LocalMediaPhoto) getIntent().getSerializableExtra("video");
            if (videoItem != null) {
                videoUri = Uri.parse(videoItem.getContentUri());
                if (videoItem.getDurationMs() > 0) {
                    totalDurationMs = videoItem.getDurationMs();
                    trimEndMs = totalDurationMs;
                }
            }
        } else if (getIntent().hasExtra("video_uri")) {
            videoUri = Uri.parse(getIntent().getStringExtra("video_uri"));
        }

        if (videoUri == null) {
            Toast.makeText(this, "Không tìm thấy video để chỉnh sửa", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        setupListeners();
        initPlayer();
    }

    private void setupListeners() {
        binding.btnCancel.setOnClickListener(v -> confirmCancel());

        binding.sbTrimStart.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (totalDurationMs > 0 && fromUser) {
                    trimStartMs = (long) ((progress / 100f) * totalDurationMs);
                    if (trimStartMs >= trimEndMs) {
                        trimStartMs = Math.max(0, trimEndMs - 1000);
                    }
                    updateTrimLabels();
                    if (player != null) player.seekTo(trimStartMs);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        binding.sbTrimEnd.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (totalDurationMs > 0 && fromUser) {
                    trimEndMs = (long) ((progress / 100f) * totalDurationMs);
                    if (trimEndMs <= trimStartMs) {
                        trimEndMs = Math.min(totalDurationMs, trimStartMs + 1000);
                    }
                    updateTrimLabels();
                    if (player != null) player.seekTo(trimEndMs);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        binding.btnSaveCopy.setOnClickListener(v -> saveNewVideoCopy());
        binding.btnOverwrite.setOnClickListener(v -> confirmOverwrite());
    }

    private void updateTrimLabels() {
        binding.tvTrimStart.setText("Bắt đầu: " + VideoDurationFormatter.formatDuration(trimStartMs));
        binding.tvTrimEnd.setText("Kết thúc: " + VideoDurationFormatter.formatDuration(trimEndMs));
    }

    @OptIn(markerClass = UnstableApi.class)
    private void initPlayer() {
        player = new ExoPlayer.Builder(this).build();
        binding.editorPlayerView.setPlayer(player);

        MediaItem mediaItem = MediaItem.fromUri(videoUri);
        player.setMediaItem(mediaItem);
        player.prepare();

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_READY && totalDurationMs <= 0) {
                    totalDurationMs = player.getDuration();
                    if (totalDurationMs > 0) {
                        trimStartMs = 0;
                        trimEndMs = totalDurationMs;
                        binding.sbTrimStart.setProgress(0);
                        binding.sbTrimEnd.setProgress(100);
                        updateTrimLabels();
                    }
                }
            }
        });
    }

    private void saveNewVideoCopy() {
        boolean muteAudio = binding.switchMuteAudio.isChecked();
        binding.progressBar.setVisibility(View.VISIBLE);
        Toast.makeText(this, "Đang xuất video...", Toast.LENGTH_SHORT).show();

        VideoSaveUtils.trimAndExportVideo(this, videoUri, trimStartMs, trimEndMs, muteAudio, new VideoSaveUtils.SaveVideoCallback() {
            @Override
            public void onSuccess(Uri savedUri, String name) {
                binding.progressBar.setVisibility(View.GONE);
                Toast.makeText(VideoEditorActivity.this, "Đã xuất video mới vào thư viện!", Toast.LENGTH_LONG).show();
                setResult(RESULT_OK);
                finish();
            }

            @Override
            public void onError(String error) {
                binding.progressBar.setVisibility(View.GONE);
                Toast.makeText(VideoEditorActivity.this, "Lỗi xuất video: " + error, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void confirmOverwrite() {
        new AlertDialog.Builder(this)
                .setTitle("Ghi đè video gốc?")
                .setMessage("Thao tác này sẽ xuất video mới đã chỉnh sửa. Bạn có muốn tiếp tục?")
                .setPositiveButton("Xác nhận", (dialog, which) -> saveNewVideoCopy())
                .setNegativeButton("Hủy", null)
                .show();
    }

    private void confirmCancel() {
        new AlertDialog.Builder(this)
                .setTitle("Hủy chỉnh sửa?")
                .setMessage("Các thay đổi chưa xuất sẽ bị mất. Bạn có muốn thoát?")
                .setPositiveButton("Thoát", (dialog, which) -> finish())
                .setNegativeButton("Hủy", null)
                .show();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (player != null) {
            player.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (player != null) {
            player.release();
            player = null;
        }
        binding = null;
    }
}
