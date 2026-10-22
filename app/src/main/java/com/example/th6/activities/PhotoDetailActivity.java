package com.example.th6.activities;

import android.content.Intent;
import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.view.GestureDetector;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;

import com.bumptech.glide.Glide;
import com.example.th6.R;
import com.example.th6.adapters.PhotoThumbnailAdapter;
import com.example.th6.data.AuthRepository;
import com.example.th6.data.PhotoRepository;
import com.example.th6.databinding.ActivityPhotoDetailBinding;
import com.example.th6.fragments.PhotoInfoBottomSheetDialog;
import com.example.th6.models.Album;
import com.example.th6.models.Photo;
import com.example.th6.models.LocalMediaPhoto;
import com.example.th6.utils.LocalVideoFavorites;
import com.example.th6.utils.PhotoTrashManager;
import io.github.jan.supabase.auth.user.UserInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import android.graphics.drawable.Drawable;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.PopupMenu;

public class PhotoDetailActivity extends AppCompatActivity {

    public static final String EXTRA_PHOTO = "photo";
    public static final String EXTRA_COLLECTION_TOKEN = "photo_collection_token";
    public static final String EXTRA_PHOTO_INDEX = "photo_index";
    private static final ConcurrentHashMap<String, List<Photo>> COLLECTIONS = new ConcurrentHashMap<>();

    private ActivityPhotoDetailBinding binding;
    private Photo photo;
    private PhotoRepository repository;
    private AuthRepository authRepository;
    private List<Album> userAlbums = new ArrayList<>();
    private final List<Photo> photos = new ArrayList<>();
    private PhotoThumbnailAdapter thumbnailAdapter;
    private ExoPlayer videoPlayer;
    private int currentIndex;
    private int imageLoadVersion;
    private long stoppedVideoPositionMs;
    private boolean stoppedVideoWasPlaying;
    private boolean recreateVideoOnStart;
    private boolean videoCloudFallbackAttempted;
    private String activeVideoSource;
    private String collectionToken;

    public static Intent createIntent(android.content.Context context, Photo current, List<Photo> collection) {
        Intent intent = new Intent(context, PhotoDetailActivity.class);
        intent.putExtra(EXTRA_PHOTO, current);
        ArrayList<Photo> safeCollection = new ArrayList<>();
        if (collection != null) safeCollection.addAll(collection);
        int index = 0;
        boolean found = false;
        for (int i = 0; i < safeCollection.size(); i++) {
            if (samePhoto(current, safeCollection.get(i))) { index = i; found = true; break; }
        }
        if (!found) { safeCollection.add(current); index = safeCollection.size() - 1; }
        String collectionToken = UUID.randomUUID().toString();
        COLLECTIONS.put(collectionToken, safeCollection);
        intent.putExtra(EXTRA_COLLECTION_TOKEN, collectionToken);
        intent.putExtra(EXTRA_PHOTO_INDEX, index);
        return intent;
    }

    private static boolean samePhoto(Photo first, Photo second) {
        if (first == null || second == null) return false;
        if (first.getId() != null && !first.getId().isEmpty() && first.getId().equals(second.getId())) return true;
        String firstLocal = first.getLocalMediaUri(), secondLocal = second.getLocalMediaUri();
        if (firstLocal != null && !firstLocal.isEmpty() && firstLocal.equals(secondLocal)) return true;
        String firstUrl = first.getImageUrl(), secondUrl = second.getImageUrl();
        return firstUrl != null && !firstUrl.isEmpty() && firstUrl.equals(secondUrl);
    }

    /** Converts an already merged local/cloud library item into the detail-screen model. */
    public static Photo photoForLocalMedia(LocalMediaPhoto media) {
        if (media == null) return new Photo();
        Photo result = media.getCloudPhoto() == null ? new Photo() : copyPhoto(media.getCloudPhoto());
        String localUri = media.getContentUri();
        if (media.getCloudPhoto() == null) {
            result.setId("local:" + (media.isVideo() ? "video:" : "image:") + media.getMediaId());
            result.setOwnerUid("local");
            result.setImageUrl(localUri == null ? "" : localUri);
            result.setDisplayUrl(localUri == null ? "" : localUri);
        }
        result.setName(media.getDisplayName() == null ? result.getName() : media.getDisplayName());
        result.setMediaType(media.isVideo() ? "video" : "image");
        if (localUri != null && !localUri.isEmpty()) result.setLocalMediaUri(localUri);
        if (media.getMediaId() > 0) result.setLocalMediaId(media.getMediaId());
        if (media.getDateModified() > 0) result.setLocalDateModified(media.getDateModified());
        if (media.getTimelineTimestamp() > 0) result.setCreatedAt(media.getTimelineTimestamp());
        return result;
    }

    private static Photo copyPhoto(Photo source) {
        Photo copy = new Photo();
        copy.setId(source.getId()); copy.setOwnerUid(source.getOwnerUid()); copy.setName(source.getName());
        copy.setImageUrl(source.getImageUrl()); copy.setDisplayUrl(source.getDisplayUrl());
        copy.setCreatedAt(source.getCreatedAt()); copy.setMediaType(source.getMediaType());
        copy.setFavorite(source.isFavorite()); copy.setTrashed(source.isTrashed()); copy.setTrashedAt(source.getTrashedAt());
        copy.setLocalMediaId(source.getLocalMediaId()); copy.setLocalMediaUri(source.getLocalMediaUri());
        copy.setLocalDateModified(source.getLocalDateModified());
        copy.setAlbumIds(source.getAlbumIds() == null ? new ArrayList<>() : new ArrayList<>(source.getAlbumIds()));
        copy.setStorageProvider(source.getStorageProvider()); copy.setContentHash(source.getContentHash());
        copy.setStoragePath(source.getStoragePath());
        return copy;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityPhotoDetailBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        authRepository = new AuthRepository();
        repository = new PhotoRepository();
        if (Build.VERSION.SDK_INT >= 33) {
            photo = getIntent().getSerializableExtra(EXTRA_PHOTO, Photo.class);
        } else {
            //noinspection deprecation
            photo = (Photo) getIntent().getSerializableExtra(EXTRA_PHOTO);
        }
        collectionToken = getIntent().getStringExtra(EXTRA_COLLECTION_TOKEN);
        List<Photo> initialPhotos = collectionToken == null ? null : COLLECTIONS.get(collectionToken);
        if (initialPhotos != null) photos.addAll(initialPhotos);

        if (photos.isEmpty() && photo != null) photos.add(photo);
        currentIndex = getIntent().getIntExtra(EXTRA_PHOTO_INDEX, 0);
        if (currentIndex < 0 || currentIndex >= photos.size()) currentIndex = 0;
        if (!photos.isEmpty()) photo = photos.get(currentIndex);

        setupUI();
        loadUserAlbums();
    }

    private void setupUI() {
        if (photo == null) return;
        displayCurrentMedia(true);

        binding.btnBack.setOnClickListener(v -> finish());
        binding.btnEdit.setOnClickListener(v -> openEditor());
        binding.btnFavorite.setOnClickListener(v -> toggleFavorite());
        binding.btnDelete.setOnClickListener(v -> confirmMoveToTrash());
        binding.btnShare.setOnClickListener(v -> sharePhoto());
        binding.btnMore.setOnClickListener(this::showMoreMenu);

        binding.rvThumbnails.setLayoutManager(new LinearLayoutManager(this, RecyclerView.HORIZONTAL, false));
        thumbnailAdapter = new PhotoThumbnailAdapter(this, clickedIndex -> {
            if (clickedIndex != currentIndex && clickedIndex >= 0 && clickedIndex < photos.size()) {
                currentIndex = clickedIndex;
                photo = photos.get(currentIndex);
                thumbnailAdapter.setSelectedPosition(currentIndex);
                binding.rvThumbnails.smoothScrollToPosition(currentIndex);
                displayCurrentMedia(true);
            }
        });
        binding.rvThumbnails.setAdapter(thumbnailAdapter);
        thumbnailAdapter.setPhotos(photos, currentIndex);
        binding.rvThumbnails.scrollToPosition(currentIndex);

        GestureDetector gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(android.view.MotionEvent e) {
                toggleSystemBars();
                return true;
            }
        });
        binding.imgLarge.setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            return true;
        });
    }

    private void openEditor() {
        if (photo == null) return;
        if (isVideo(photo)) {
            // Cloud rows retain the URI from the device that originally backed them up.
            // That content:// URI is not portable, so use the cloud object when entering
            // the editor for a cloud-backed video.
            String cloudUrl = photo.getImageUrl();
            boolean cloudBacked = photo.getOwnerUid() != null
                    && !photo.getOwnerUid().isEmpty()
                    && !"local".equals(photo.getOwnerUid());
            Uri source = cloudBacked && cloudUrl != null && !cloudUrl.isEmpty()
                    ? Uri.parse(cloudUrl) : resolveMediaUri(photo);
            if (source == null) {
                Toast.makeText(this, "Không tìm thấy nguồn video", Toast.LENGTH_LONG).show();
                return;
            }
            LocalMediaPhoto video = new LocalMediaPhoto();
            video.setVideo(true);
            video.setContentUri(source.toString());
            video.setDisplayName(photo.getName());
            video.setMimeType("video/mp4");
            video.setMediaId(photo.getLocalMediaId());
            video.setCloudPhoto("local".equals(photo.getOwnerUid()) ? null : photo);
            video.setCloudUrl("local".equals(photo.getOwnerUid()) ? null : photo.getImageUrl());
            Intent intent = new Intent(this, VideoEditorActivity.class);
            intent.putExtra("video", video);
            intent.putExtra("video_uri", source.toString());
            if (videoPlayer != null) videoPlayer.pause();
            startActivity(intent);
            return;
        }
        Intent intent = new Intent(this, PhotoEditorActivity.class);
        intent.putExtra(PhotoEditorActivity.EXTRA_PHOTO, photo);
        Uri source = Uri.parse(photo.getLocalMediaUri() != null && !photo.getLocalMediaUri().isEmpty()
                ? photo.getLocalMediaUri() : photo.getImageUrl());
        if ("content".equalsIgnoreCase(source.getScheme())) {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        startActivity(intent);
    }

    private void displayCurrentMedia(boolean autoplay) {
        if (photo == null) return;
        updateFavoriteIcon();
        binding.btnEdit.setVisibility(View.VISIBLE);
        if (isVideo(photo)) {
            displayCurrentVideo(autoplay);
        } else {
            releaseVideoPlayer();
            binding.videoPlayerView.setVisibility(View.GONE);
            binding.imgLarge.setVisibility(View.VISIBLE);
            displayCurrentImage();
        }
    }

    private void displayCurrentVideo(boolean autoplay) {
        imageLoadVersion++;
        Glide.with(this).clear(binding.imgLarge);
        binding.imgLarge.setVisibility(View.GONE);
        binding.videoPlayerView.setVisibility(View.VISIBLE);
        Uri source = resolveMediaUri(photo);
        if (source == null) {
            binding.progressBar.setVisibility(View.GONE);
            Toast.makeText(this, "Không tìm thấy nguồn video này", Toast.LENGTH_LONG).show();
            return;
        }
        ensureVideoPlayer();
        videoCloudFallbackAttempted = false;
        activeVideoSource = source.toString();
        binding.progressBar.setVisibility(View.VISIBLE);
        videoPlayer.setMediaItem(MediaItem.fromUri(source));
        videoPlayer.prepare();
        videoPlayer.seekTo(stoppedVideoPositionMs);
        videoPlayer.setPlayWhenReady(autoplay || stoppedVideoWasPlaying);
        stoppedVideoPositionMs = 0L;
        stoppedVideoWasPlaying = false;
    }

    @UnstableApi
    private void ensureVideoPlayer() {
        if (videoPlayer != null) return;
        videoPlayer = new ExoPlayer.Builder(this).build();
        binding.videoPlayerView.setPlayer(videoPlayer);
        videoPlayer.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (binding == null) return;
                binding.progressBar.setVisibility(state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
            }
            @Override public void onPlayerError(PlaybackException error) {
                if (binding == null) return;
                binding.progressBar.setVisibility(View.GONE);
                String cloudUrl = photo == null ? null : photo.getImageUrl();
                if (!videoCloudFallbackAttempted && cloudUrl != null && !cloudUrl.isEmpty()
                        && !cloudUrl.equals(activeVideoSource)) {
                    videoCloudFallbackAttempted = true;
                    activeVideoSource = cloudUrl;
                    binding.progressBar.setVisibility(View.VISIBLE);
                    videoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(cloudUrl)));
                    videoPlayer.prepare();
                    videoPlayer.setPlayWhenReady(true);
                    return;
                }
                Toast.makeText(PhotoDetailActivity.this,
                        "Không thể phát video: " + error.getErrorCodeName(), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void releaseVideoPlayer() {
        if (videoPlayer == null) return;
        videoPlayer.pause();
        videoPlayer.clearMediaItems();
        binding.videoPlayerView.setPlayer(null);
        videoPlayer.release();
        videoPlayer = null;
        stoppedVideoPositionMs = 0L;
        stoppedVideoWasPlaying = false;
    }

    private boolean isVideo(Photo item) {
        return item != null && "video".equalsIgnoreCase(item.getMediaType());
    }

    private boolean isLocalVideo(Photo item) {
        return isVideo(item) && item != null && ("local".equals(item.getOwnerUid())
                || (item.getId() != null && item.getId().startsWith("local:")));
    }

    private Uri resolveMediaUri(Photo item) {
        if (item == null) return null;
        String local = item.getLocalMediaUri();
        if (local != null && !local.isEmpty()) return Uri.parse(local);
        String source = item.getImageUrl();
        if ((source == null || source.isEmpty()) && item.getDisplayUrl() != null) source = item.getDisplayUrl();
        return source == null || source.isEmpty() ? null : Uri.parse(source);
    }

    private void displayCurrentImage() {
        if (photo == null) return;
        updateFavoriteIcon();
        Uri sourceUri = resolveMediaUri(photo);
        String displayUri = sourceUri == null ? null : sourceUri.toString();

        imageLoadVersion++;
        final int currentVersion = imageLoadVersion;
        binding.progressBar.setVisibility(View.VISIBLE);

        Glide.with(this)
                .load(displayUri)
                .listener(new RequestListener<Drawable>() {
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Drawable> target, boolean isFirstResource) {
                        if (currentVersion != imageLoadVersion) return false;
                        binding.progressBar.setVisibility(View.GONE);
                        Photo failedPhoto = photo;
                        if (displayUri != null && failedPhoto.getImageUrl() != null && !displayUri.equals(failedPhoto.getImageUrl())) {
                            Glide.with(PhotoDetailActivity.this)
                                    .load(failedPhoto.getImageUrl())
                                    .listener(new RequestListener<Drawable>() {
                                        @Override public boolean onLoadFailed(@Nullable GlideException failure, Object model, Target<Drawable> target, boolean first) {
                                            Toast.makeText(PhotoDetailActivity.this, "Không tải được ảnh cloud. Kiểm tra mạng hoặc thử lại.", Toast.LENGTH_LONG).show();
                                            return false;
                                        }
                                        @Override public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource source, boolean first) { return false; }
                                    })
                                    .into(binding.imgLarge);
                        } else if (!isFinishing()) {
                            Toast.makeText(PhotoDetailActivity.this, "Không tải được ảnh cloud. Kiểm tra mạng hoặc thử lại.", Toast.LENGTH_LONG).show();
                        }
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                        if (currentVersion != imageLoadVersion) return false;
                        binding.progressBar.setVisibility(View.GONE);
                        return false;
                    }
                })
                .into(binding.imgLarge);
    }
    private void updateFavoriteIcon() {
        if (photo == null) return;
        boolean favorite = photo.isFavorite();
        UserInfo user = authRepository == null ? null : authRepository.getCurrentUser();
        if (isLocalVideo(photo) && user != null) {
            Uri source = resolveMediaUri(photo);
            favorite = source != null && LocalVideoFavorites.contains(this, user.getId(), source.toString());
            photo.setFavorite(favorite);
        }
        binding.btnFavorite.setImageResource(favorite ? R.drawable.ic_favorite : R.drawable.ic_favorite_border);
    }

    private boolean systemBarsVisible = true;
    private void toggleSystemBars() {
        systemBarsVisible = !systemBarsVisible;
        int visibility = systemBarsVisible ? View.VISIBLE : View.GONE;
        binding.topToolbar.setVisibility(visibility);
        binding.rvThumbnails.setVisibility(visibility);
    }

    private void showMoreMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Thông tin ảnh").setOnMenuItemClickListener(item -> { showInfoBottomSheet(); return true; });
        if (photo != null && photo.getOwnerUid() != null && !"local".equals(photo.getOwnerUid())
                && (photo.getId() == null || !photo.getId().startsWith("local:"))) {
            menu.getMenu().add("Thêm vào Album").setOnMenuItemClickListener(item -> { showAddToAlbumDialog(); return true; });
        }
        menu.show();
    }

    private void showInfoBottomSheet() {
        PhotoInfoBottomSheetDialog dialog = PhotoInfoBottomSheetDialog.newInstance(photo);
        dialog.show(getSupportFragmentManager(), "photo_info_dialog");
    }

    private void toggleFavorite() {
        UserInfo user = authRepository.getCurrentUser();
        if (user == null) return;

        Photo targetPhoto = photo;
        boolean newFav = !targetPhoto.isFavorite();
        if (isLocalVideo(targetPhoto)) {
            Uri source = resolveMediaUri(targetPhoto);
            if (source == null) return;
            LocalVideoFavorites.set(this, user.getId(), source.toString(), newFav);
            targetPhoto.setFavorite(newFav);
            updateFavoriteIcon();
            Toast.makeText(this, newFav ? "Đã thêm video vào yêu thích" : "Đã bỏ video khỏi yêu thích", Toast.LENGTH_SHORT).show();
            return;
        }
        targetPhoto.setFavorite(newFav);
        updateFavoriteIcon();

        repository.toggleFavoriteAsync(user.getId(), targetPhoto.getId(), newFav, new PhotoRepository.Callback<Void>() {
            @Override
            public void onSuccess(Void result) {
                Toast.makeText(PhotoDetailActivity.this, newFav ? "Đã thêm vào yêu thích" : "Đã bỏ khỏi yêu thích", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String error) {
                targetPhoto.setFavorite(!newFav);
                if (photo == targetPhoto) updateFavoriteIcon();
                Toast.makeText(PhotoDetailActivity.this, "Lỗi: " + error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void sharePhoto() {
        Uri mediaUri = resolveMediaUri(photo);
        if (mediaUri == null) return;
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, photo.getName());
        if ("content".equalsIgnoreCase(mediaUri.getScheme())) {
            String fallbackType = isVideo(photo) ? "video/*" : "image/*";
            shareIntent.setType(getContentResolver().getType(mediaUri) != null ? getContentResolver().getType(mediaUri) : fallbackType);
            shareIntent.putExtra(Intent.EXTRA_STREAM, mediaUri);
            shareIntent.setClipData(android.content.ClipData.newUri(getContentResolver(), photo.getName(), mediaUri));
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_TEXT, "Xem " + (isVideo(photo) ? "video" : "ảnh")
                    + " \"" + photo.getName() + "\" trên PhotoVault: " + mediaUri);
        }
        startActivity(Intent.createChooser(shareIntent, "Chia sẻ ảnh qua"));
    }
    private void confirmMoveToTrash() {
        if (photo == null) return;
        new AlertDialog.Builder(this)
                .setTitle("Chuyển vào thùng rác?")
                .setMessage("Mục này sẽ được chuyển vào thùng rác PhotoVault. File gốc trên thiết bị sẽ được giữ nguyên.")
                .setPositiveButton("Chuyển vào thùng rác", (dialog, which) -> {
                    UserInfo user = authRepository.getCurrentUser();
                    binding.progressBar.setVisibility(View.VISIBLE);
                    Photo trashTarget = photo;
                    if (isLocalVideo(photo) && !"local".equals(photo.getOwnerUid())) {
                        trashTarget = copyPhoto(photo);
                        trashTarget.setOwnerUid("local");
                    }
                    PhotoTrashManager.moveToTrash(this, user == null ? null : user.getId(), trashTarget,
                            new PhotoTrashManager.Callback() {
                                @Override public void onSuccess(Photo moved) {
                                    if (isFinishing()) return;
                                    binding.progressBar.setVisibility(View.GONE);
                                    Toast.makeText(PhotoDetailActivity.this, "Đã chuyển vào thùng rác", Toast.LENGTH_SHORT).show();
                                    finish();
                                }
                                @Override public void onError(String error) {
                                    if (isFinishing()) return;
                                    binding.progressBar.setVisibility(View.GONE);
                                    Toast.makeText(PhotoDetailActivity.this, "Không chuyển được vào thùng rác: " + error, Toast.LENGTH_LONG).show();
                                }
                            });
                })
                .setNegativeButton("Hủy", null)
                .show();
    }

    @Override protected void onStop() {
        if (videoPlayer != null) {
            stoppedVideoPositionMs = videoPlayer.getCurrentPosition();
            stoppedVideoWasPlaying = videoPlayer.getPlayWhenReady();
            videoPlayer.pause();
            binding.videoPlayerView.setPlayer(null);
            videoPlayer.release();
            videoPlayer = null;
            recreateVideoOnStart = isVideo(photo);
        }
        super.onStop();
    }

    @Override protected void onStart() {
        super.onStart();
        if (recreateVideoOnStart && isVideo(photo)) {
            recreateVideoOnStart = false;
            displayCurrentVideo(stoppedVideoWasPlaying);
        }
    }

    @Override protected void onDestroy() {
        if (binding != null) binding.videoPlayerView.setPlayer(null);
        if (videoPlayer != null) {
            videoPlayer.release();
            videoPlayer = null;
        }
        if (collectionToken != null && (isFinishing() || !isChangingConfigurations())) {
            COLLECTIONS.remove(collectionToken);
        }
        super.onDestroy();
    }
    private void loadUserAlbums() {
        if (photo.getOwnerUid() == null || "local".equals(photo.getOwnerUid())) return;
        UserInfo user = authRepository.getCurrentUser();
        if (user == null) return;

        repository.getAlbumsAsync(user.getId(), new PhotoRepository.Callback<List<Album>>() {
            @Override
            public void onSuccess(List<Album> albums) {
                userAlbums = albums != null ? albums : new ArrayList<>();
            }

            @Override
            public void onError(String error) {}
        });
    }

    private void showAddToAlbumDialog() {
        if (userAlbums == null || userAlbums.isEmpty()) {
            Toast.makeText(this, "Bạn chưa có album nào. Vui lòng tạo album trước!", Toast.LENGTH_LONG).show();
            return;
        }

        String[] albumNames = new String[userAlbums.size()];
        boolean[] checkedItems = new boolean[userAlbums.size()];

        for (int i = 0; i < userAlbums.size(); i++) {
            albumNames[i] = userAlbums.get(i).getName();
            checkedItems[i] = photo.getAlbumIds().contains(userAlbums.get(i).getId());
        }

        new AlertDialog.Builder(this)
                .setTitle("Thêm ảnh vào Album")
                .setMultiChoiceItems(albumNames, checkedItems, (dialog, which, isChecked) -> {
                    String albumId = userAlbums.get(which).getId();
                    if (isChecked) {
                        if (!photo.getAlbumIds().contains(albumId)) photo.getAlbumIds().add(albumId);
                    } else {
                        photo.getAlbumIds().remove(albumId);
                    }
                })
                .setPositiveButton("Lưu", (dialog, which) -> {
                    UserInfo user = authRepository.getCurrentUser();
                    if (user != null) {
                        repository.updatePhotoAlbumsAsync(user.getId(), photo.getId(), photo.getAlbumIds(), new PhotoRepository.Callback<Void>() {
                            @Override
                            public void onSuccess(Void result) {
                                Toast.makeText(PhotoDetailActivity.this, "Đã cập nhật album cho ảnh", Toast.LENGTH_SHORT).show();
                            }

                            @Override
                            public void onError(String error) {
                                Toast.makeText(PhotoDetailActivity.this, "Lỗi: " + error, Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                })
                .setNegativeButton("Hủy", null)
                .show();
    }
}
