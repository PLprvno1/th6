package com.example.th6.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;

import com.example.th6.databinding.ActivityVideoPlayerBinding;
import com.example.th6.models.LocalMediaPhoto;
import com.example.th6.models.Photo;
import com.example.th6.data.AuthRepository;
import com.example.th6.data.PhotoRepository;
import com.example.th6.utils.PhotoTrashManager;
import com.example.th6.utils.LocalVideoFavorites;
import io.github.jan.supabase.auth.user.UserInfo;

public class VideoPlayerActivity extends AppCompatActivity {
    private static final String TAG = "VideoPlayerActivity";

    private ActivityVideoPlayerBinding binding;
    private ExoPlayer player;
    private LocalMediaPhoto videoItem;
    private Uri videoUri;
    private boolean cloudFallbackAttempted;
    private PhotoRepository photoRepository;
    private boolean favoriteUpdating;
    private ActivityResultLauncher<Intent> videoEditorLauncher;
    private boolean resumeAfterEditor;
    private long playerPositionBeforeEditor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        videoEditorLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (player == null) return;
            player.setMediaItem(MediaItem.fromUri(videoUri), playerPositionBeforeEditor);
            player.prepare();
            player.setPlayWhenReady(resumeAfterEditor);
        });
        binding = ActivityVideoPlayerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        if (getIntent().hasExtra("video")) {
            videoItem = (LocalMediaPhoto) getIntent().getSerializableExtra("video");
            if (videoItem != null) {
                String source = videoItem.getContentUri();
                if (source == null || source.isEmpty()) source = videoItem.getCloudUrl();
                if (source != null && !source.isEmpty()) videoUri = Uri.parse(source);
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
        photoRepository = new PhotoRepository();
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
        binding.btnVideoInfo.setOnClickListener(v -> showVideoInfo());
        binding.btnFavorite.setOnClickListener(v -> toggleFavorite());

        binding.btnEditVideo.setOnClickListener(v -> {
            Intent intent = new Intent(VideoPlayerActivity.this, VideoEditorActivity.class);
            if (videoItem != null) {
                intent.putExtra("video", videoItem);
            }
            intent.putExtra("video_uri", videoUri.toString());
            resumeAfterEditor = player != null && player.isPlaying();
            playerPositionBeforeEditor = player == null ? 0L : player.getCurrentPosition();
            if (player != null) {
                player.pause();
                player.clearMediaItems();
            }
            videoEditorLauncher.launch(intent);
        });

        binding.btnShare.setOnClickListener(v -> {
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("video/*");
            shareIntent.putExtra(Intent.EXTRA_STREAM, videoUri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, "Chia sẻ video qua"));
        });

        binding.btnDelete.setOnClickListener(v -> {
            if (videoItem == null) {
                Toast.makeText(this, "Không xác định được media để chuyển vào thùng rác", Toast.LENGTH_LONG).show();
                return;
            }
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Chuyển video vào thùng rác?")
                    .setMessage("File gốc trên thiết bị sẽ được giữ nguyên.")
                    .setNegativeButton("Hủy", null)
                    .setPositiveButton("Chuyển vào thùng rác", (dialog, which) -> moveVideoToTrash())
                    .show();
        });
    }

    private boolean isFavorite() {
        if (videoItem == null) return false;
        Photo cloud = videoItem.getCloudPhoto();
        if (cloud != null) return cloud.isFavorite();
        UserInfo user = new AuthRepository().getCurrentUser();
        return user != null && LocalVideoFavorites.contains(this, user.getId(), videoItem.getContentUri());
    }

    private void updateFavoriteButton() {
        if (binding == null) return;
        boolean favorite = isFavorite();
        binding.btnFavorite.setImageResource(favorite ? com.example.th6.R.drawable.ic_favorite : com.example.th6.R.drawable.ic_favorite_border);
        binding.btnFavorite.setColorFilter(androidx.core.content.ContextCompat.getColor(this,
                favorite ? com.example.th6.R.color.favorite_red : android.R.color.white));
        binding.btnFavorite.setContentDescription(favorite ? "Bỏ yêu thích video" : "Yêu thích video");
    }

    private void toggleFavorite() {
        UserInfo user = new AuthRepository().getCurrentUser();
        if (user == null || videoItem == null || favoriteUpdating) {
            Toast.makeText(this, "Không thể cập nhật yêu thích lúc này", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean value = !isFavorite();
        Photo cloud = videoItem.getCloudPhoto();
        if (cloud == null) {
            LocalVideoFavorites.set(this, user.getId(), videoItem.getContentUri(), value);
            updateFavoriteButton();
            Toast.makeText(this, value ? "Đã thêm vào yêu thích" : "Đã bỏ khỏi yêu thích", Toast.LENGTH_SHORT).show();
            return;
        }
        favoriteUpdating = true;
        binding.btnFavorite.setEnabled(false);
        photoRepository.toggleFavoriteAsync(user.getId(), cloud.getId(), value, new PhotoRepository.Callback<Void>() {
            @Override public void onSuccess(Void result) {
                cloud.setFavorite(value);
                LocalVideoFavorites.set(VideoPlayerActivity.this, user.getId(), videoItem.getContentUri(), false);
                favoriteUpdating = false;
                if (binding != null) { binding.btnFavorite.setEnabled(true); updateFavoriteButton(); }
                Toast.makeText(VideoPlayerActivity.this, value ? "Đã thêm vào yêu thích" : "Đã bỏ khỏi yêu thích", Toast.LENGTH_SHORT).show();
            }
            @Override public void onError(String error) {
                favoriteUpdating = false;
                if (binding != null) binding.btnFavorite.setEnabled(true);
                Toast.makeText(VideoPlayerActivity.this, "Không cập nhật được yêu thích: " + error, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void moveVideoToTrash() {
        Photo photo = videoItem.getCloudPhoto() == null ? new Photo() : videoItem.getCloudPhoto();
        if (videoItem.getCloudPhoto() == null) {
            photo.setId("local:" + videoItem.getMediaId());
            photo.setOwnerUid("local");
            photo.setName(videoItem.getDisplayName());
            photo.setMediaType("video");
            photo.setImageUrl(videoItem.getContentUri());
            photo.setCreatedAt(videoItem.getTimelineTimestamp());
        }
        photo.setLocalMediaUri(videoItem.getContentUri());
        photo.setLocalMediaId(videoItem.getMediaId());
        photo.setLocalDateModified(videoItem.getDateModified());
        UserInfo user = new AuthRepository().getCurrentUser();
        PhotoTrashManager.moveToTrash(this, user == null ? null : user.getId(), photo,
                new PhotoTrashManager.Callback() {
                    @Override public void onSuccess(Photo moved) {
                        Toast.makeText(VideoPlayerActivity.this, "Đã chuyển video vào thùng rác", Toast.LENGTH_SHORT).show();
                        finish();
                    }
                    @Override public void onError(String error) {
                        Toast.makeText(VideoPlayerActivity.this, "Không chuyển được video: " + error, Toast.LENGTH_LONG).show();
                    }
                });
    }
    private void showVideoInfo() {
        String unknown = "Không có dữ liệu";
        String name = videoItem != null && videoItem.getDisplayName() != null ? videoItem.getDisplayName() : unknown;
        String mime = videoItem != null && videoItem.getMimeType() != null ? videoItem.getMimeType() : unknown;
        String resolution = videoItem != null && videoItem.getWidth() > 0 && videoItem.getHeight() > 0
                ? videoItem.getWidth() + " × " + videoItem.getHeight() : unknown;
        String duration = videoItem != null && videoItem.getDurationMs() > 0
                ? com.example.th6.utils.VideoDurationFormatter.formatDuration(videoItem.getDurationMs()) : unknown;
        String size = videoItem != null && videoItem.getSizeBytes() > 0
                ? android.text.format.Formatter.formatFileSize(this, videoItem.getSizeBytes()) : unknown;
        String source = resolveStorageSource();
        String local = videoItem != null && videoItem.getContentUri() != null ? videoItem.getContentUri() : unknown;
        String date = videoItem != null && videoItem.getTimelineTimestamp() > 0
                ? java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(videoItem.getTimelineTimestamp())) : unknown;
        String backup = videoItem != null && LocalMediaPhoto.STATUS_UPLOADED.equals(videoItem.getBackupStatus()) ? "Đã sao lưu"
                : videoItem != null && videoItem.getContentUri() != null ? "Chỉ có trên thiết bị" : "Không xác định";
        new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Thông tin video")
                .setMessage("Tên: " + name + "\nLoại: Video\nĐộ phân giải: " + resolution + "\nThời lượng: " + duration
                        + "\nKích thước: " + size + "\nNgày ghi nhận: " + date + "\nĐịnh dạng: " + mime
                        + "\nNguồn lưu trữ: " + source + "\nSao lưu: " + backup + "\nURI thiết bị: " + local)
                .setPositiveButton("Đóng", null).show();
    }

    private String resolveStorageSource() {
        boolean local = videoItem != null && videoItem.getContentUri() != null && !videoItem.getContentUri().isEmpty();
        com.example.th6.models.Photo cloud = videoItem == null ? null : videoItem.getCloudPhoto();
        boolean verifiedCloud = cloud != null && cloud.getStoragePath() != null && !cloud.getStoragePath().isEmpty();
        String provider = verifiedCloud ? cloud.getStorageProvider() : null;
        String cloudName = "supabase".equalsIgnoreCase(provider) ? "Supabase Cloud"
                : "imgbb".equalsIgnoreCase(provider) ? "ImgBB Cloud" : null;
        if (local && cloudName != null) return "Thiết bị + " + cloudName;
        if (local) return "Thiết bị";
        return cloudName == null ? "Không xác định" : cloudName;
    }

    @OptIn(markerClass = UnstableApi.class)
    private void initPlayer() {
        player = new ExoPlayer.Builder(this).build();
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                if (!cloudFallbackAttempted && videoItem != null && videoItem.getCloudUrl() != null
                        && !videoItem.getCloudUrl().isEmpty() && !videoItem.getCloudUrl().equals(videoUri.toString())) {
                    cloudFallbackAttempted = true;
                    videoUri = Uri.parse(videoItem.getCloudUrl());
                    player.setMediaItem(MediaItem.fromUri(videoUri));
                    player.prepare();
                    player.setPlayWhenReady(true);
                    return;
                }
                Log.e(TAG, "Video playback failed", error);
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

    @Override protected void onResume() {
        super.onResume();
        updateFavoriteButton();
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
