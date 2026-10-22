package com.example.th6.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;

import com.example.th6.databinding.ActivityVideoPlayerBinding;
import com.example.th6.models.LocalMediaPhoto;

public class VideoPlayerActivity extends AppCompatActivity {
    private static final String TAG = "VideoPlayerActivity";

    private ActivityVideoPlayerBinding binding;
    private ExoPlayer player;
    private LocalMediaPhoto videoItem;
    private Uri videoUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityVideoPlayerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        if (getIntent().hasExtra("video")) {
            videoItem = (LocalMediaPhoto) getIntent().getSerializableExtra("video");
            if (videoItem != null) {
                videoUri = Uri.parse(videoItem.getContentUri());
            }
        } else if (getIntent().hasExtra("video_uri")) {
            videoUri = Uri.parse(getIntent().getStringExtra("video_uri"));
        }

        if (videoUri == null || videoUri.toString().isEmpty()) {
            Toast.makeText(this, "Không tìm thấy dữ liệu video", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        setupUI();
        setupListeners();
        initPlayer();
    }

    private void setupUI() {
        if (videoItem != null && videoItem.getDisplayName() != null) {
            binding.tvVideoTitle.setText(videoItem.getDisplayName());
        } else {
            binding.tvVideoTitle.setText("Xem Video");
        }
    }

    private void setupListeners() {
        binding.btnBack.setOnClickListener(v -> finish());

        binding.btnEditVideo.setOnClickListener(v -> {
            Intent intent = new Intent(VideoPlayerActivity.this, VideoEditorActivity.class);
            if (videoItem != null) {
                intent.putExtra("video", videoItem);
            }
            intent.putExtra("video_uri", videoUri.toString());
            startActivity(intent);
        });

        binding.btnShare.setOnClickListener(v -> {
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("video/*");
            shareIntent.putExtra(Intent.EXTRA_STREAM, videoUri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, "Chia sẻ video qua"));
        });

        binding.btnDelete.setOnClickListener(v -> {
            Toast.makeText(this, "Có thể chuyển video vào Thùng rác từ màn hình thư viện", Toast.LENGTH_SHORT).show();
        });
    }

    @OptIn(markerClass = UnstableApi.class)
    private void initPlayer() {
        player = new ExoPlayer.Builder(this).build();
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                Log.e(TAG, "Video playback failed for " + videoUri, error);
                if (!isFinishing()) {
                    Toast.makeText(VideoPlayerActivity.this,
                            "Không thể phát video này. Tệp có thể không được hỗ trợ hoặc không còn truy cập được.",
                            Toast.LENGTH_LONG).show();
                }
            }
        });
        binding.playerView.setPlayer(player);

        MediaItem mediaItem = MediaItem.fromUri(videoUri);
        player.setMediaItem(mediaItem);
        player.prepare();
        player.setPlayWhenReady(true);
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
