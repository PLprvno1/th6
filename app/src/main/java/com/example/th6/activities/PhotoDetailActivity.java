package com.example.th6.activities;

import android.content.Intent;
import android.os.Bundle;
import android.os.Build;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.th6.R;
import com.example.th6.adapters.PhotoThumbnailAdapter;
import com.example.th6.data.FirestoreRepository;
import com.example.th6.databinding.ActivityPhotoDetailBinding;
import com.example.th6.fragments.PhotoInfoBottomSheetDialog;
import com.example.th6.models.Album;
import com.example.th6.models.Photo;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

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
    private FirestoreRepository repository;
    private FirebaseAuth mAuth;
    private List<Album> userAlbums = new ArrayList<>();
    private final List<Photo> photos = new ArrayList<>();
    private PhotoThumbnailAdapter thumbnailAdapter;
    private int currentIndex;
    private int imageLoadVersion;
    private boolean albumsLoaded;
    private boolean albumsLoading;

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
        if (first.getId() != null && first.getId().equals(second.getId())) return true;
        return first.getImageUrl() != null && first.getImageUrl().equals(second.getImageUrl());
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityPhotoDetailBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        mAuth = FirebaseAuth.getInstance();
        repository = new FirestoreRepository();

        if (Build.VERSION.SDK_INT >= 33) {
            photo = getIntent().getSerializableExtra(EXTRA_PHOTO, Photo.class);
        } else {
            //noinspection deprecation
            photo = (Photo) getIntent().getSerializableExtra(EXTRA_PHOTO);
        }
        String collectionToken = getIntent().getStringExtra(EXTRA_COLLECTION_TOKEN);
        List<Photo> initialPhotos = collectionToken == null ? null : COLLECTIONS.get(collectionToken);
        if (initialPhotos != null) photos.addAll(initialPhotos);
        currentIndex = Math.max(0, Math.min(getIntent().getIntExtra(EXTRA_PHOTO_INDEX, 0), Math.max(0, photos.size() - 1)));
        if (photo != null && photos.isEmpty()) photos.add(photo);
        if (!photos.isEmpty()) photo = photos.get(currentIndex);

        if (photo == null) {
            Toast.makeText(this, "Không tìm thấy dữ liệu ảnh", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        setupUI();
        setupThumbnailStrip();
        setupListeners();
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) {
            String token = getIntent().getStringExtra(EXTRA_COLLECTION_TOKEN);
            if (token != null) COLLECTIONS.remove(token);
        }
        super.onDestroy();
    }

    private void setupUI() {
        updateCurrentPhoto();
    }

    private void setupThumbnailStrip() {
        thumbnailAdapter = new PhotoThumbnailAdapter(this, this::showPhotoAt);
        binding.rvThumbnails.setLayoutManager(new LinearLayoutManager(this, RecyclerView.HORIZONTAL, false));
        binding.rvThumbnails.setAdapter(thumbnailAdapter);
        thumbnailAdapter.setPhotos(photos, currentIndex);
        binding.rvThumbnails.post(() -> binding.rvThumbnails.scrollToPosition(currentIndex));
    }

    private void updateCurrentPhoto() {
        if (photo == null || binding == null) return;
        boolean cloudManaged = photo.getOwnerUid() != null && !"local".equals(photo.getOwnerUid());
        binding.btnFavorite.setVisibility(cloudManaged ? View.VISIBLE : View.GONE);
        binding.btnDelete.setVisibility(cloudManaged ? View.VISIBLE : View.GONE);
        updateFavoriteIcon();
        int requestVersion = ++imageLoadVersion;
        binding.progressBar.setVisibility(View.VISIBLE);
        Glide.with(this).clear(binding.imgLarge);
        binding.imgLarge.setImageDrawable(null);
        Glide.with(this)
                .load(photo.getDisplayUrl() == null || photo.getDisplayUrl().isEmpty() ? photo.getImageUrl() : photo.getDisplayUrl())
                .placeholder(R.drawable.ic_photo_placeholder)
                .error(R.drawable.ic_photo_placeholder)
                .listener(new RequestListener<Drawable>() {
                    @Override public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Drawable> target, boolean isFirstResource) {
                        if (requestVersion == imageLoadVersion && binding != null) {
                            binding.progressBar.setVisibility(View.GONE);
                            Toast.makeText(PhotoDetailActivity.this, "Không thể tải ảnh này", Toast.LENGTH_SHORT).show();
                        }
                        return false;
                    }
                    @Override public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                        if (requestVersion == imageLoadVersion && binding != null) binding.progressBar.setVisibility(View.GONE);
                        return false;
                    }
                })
                .into(binding.imgLarge);
        if (thumbnailAdapter != null) thumbnailAdapter.setSelectedPosition(currentIndex);
        if (cloudManaged && !albumsLoaded && !albumsLoading) loadUserAlbums();
    }

    private void showPhotoAt(int position) {
        if (position < 0 || position >= photos.size() || position == currentIndex) return;
        currentIndex = position;
        photo = photos.get(position);
        thumbnailAdapter.setSelectedPosition(position);
        binding.rvThumbnails.smoothScrollToPosition(position);
        updateCurrentPhoto();
    }

    private void movePhotoBy(int delta) {
        showPhotoAt(currentIndex + delta);
    }

    private void updateFavoriteIcon() {
        if (photo.isFavorite()) {
            binding.btnFavorite.setImageResource(R.drawable.ic_favorite);
            binding.btnFavorite.setColorFilter(ContextCompat.getColor(this, R.color.favorite_red));
        } else {
            binding.btnFavorite.setImageResource(R.drawable.ic_favorite_border);
            binding.btnFavorite.setColorFilter(ContextCompat.getColor(this, android.R.color.white));
        }
    }

    private void setupListeners() {
        binding.btnBack.setOnClickListener(v -> finish());
        binding.btnEdit.setOnClickListener(v -> {
            Intent editIntent = new Intent(PhotoDetailActivity.this, PhotoEditorActivity.class);
            editIntent.putExtra("photo", photo);
            startActivity(editIntent);
        });
        binding.btnFavorite.setOnClickListener(v -> toggleFavorite());
        binding.btnShare.setOnClickListener(v -> sharePhoto());
        binding.btnDelete.setOnClickListener(v -> confirmMoveToTrash());
        binding.btnMore.setOnClickListener(this::showMoreMenu);
        GestureDetector swipeDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onFling(MotionEvent first, MotionEvent last, float velocityX, float velocityY) {
                if (first != null && last != null && Math.abs(first.getX() - last.getX()) > 70
                        && Math.abs(velocityX) > Math.abs(velocityY)) {
                    movePhotoBy(first.getX() > last.getX() ? 1 : -1);
                    return true;
                }
                return false;
            }
        });
        binding.imgLarge.setOnTouchListener((v, event) -> swipeDetector.onTouchEvent(event));
    }

    private void showMoreMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Thông tin ảnh").setOnMenuItemClickListener(item -> { showInfoBottomSheet(); return true; });
        if (photo != null && photo.getOwnerUid() != null && !"local".equals(photo.getOwnerUid())) {
            menu.getMenu().add(R.string.ctx_add_to_album).setOnMenuItemClickListener(item -> { showAddToAlbumDialog(); return true; });
        }
        menu.show();
    }

    private void showInfoBottomSheet() {
        PhotoInfoBottomSheetDialog dialog = PhotoInfoBottomSheetDialog.newInstance(photo);
        dialog.show(getSupportFragmentManager(), "photo_info_dialog");
    }

    private void toggleFavorite() {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) return;

        boolean newFav = !photo.isFavorite();
        photo.setFavorite(newFav);
        updateFavoriteIcon();

        repository.toggleFavorite(user.getUid(), photo.getId(), newFav, new FirestoreRepository.RepositoryCallback<Void>() {
            @Override
            public void onSuccess(Void result) {
                Toast.makeText(PhotoDetailActivity.this, newFav ? "Đã thêm vào yêu thích" : "Đã bỏ khỏi yêu thích", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String error) {
                photo.setFavorite(!newFav);
                updateFavoriteIcon();
                Toast.makeText(PhotoDetailActivity.this, "Lỗi: " + error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void sharePhoto() {
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, photo.getName());
        android.net.Uri uri = android.net.Uri.parse(photo.getImageUrl());
        if ("content".equalsIgnoreCase(uri.getScheme())) {
            shareIntent.setType(getContentResolver().getType(uri) != null ? getContentResolver().getType(uri) : "image/*");
            shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
            shareIntent.setClipData(android.content.ClipData.newUri(getContentResolver(), photo.getName(), uri));
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_TEXT, "Xem ảnh \"" + photo.getName() + "\" trên PhotoVault: " + photo.getImageUrl());
        }
        startActivity(Intent.createChooser(shareIntent, "Chia sẻ ảnh qua"));
    }

    private void confirmMoveToTrash() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.ctx_move_trash)
                .setMessage(R.string.confirm_move_trash)
                .setPositiveButton("Chuyển vào thùng rác", (dialog, which) -> {
                    FirebaseUser user = mAuth.getCurrentUser();
                    if (user != null) {
                        binding.progressBar.setVisibility(View.VISIBLE);
                        repository.moveToTrash(user.getUid(), photo.getId(), true, new FirestoreRepository.RepositoryCallback<Void>() {
                            @Override
                            public void onSuccess(Void result) {
                                binding.progressBar.setVisibility(View.GONE);
                                Toast.makeText(PhotoDetailActivity.this, "Đã chuyển ảnh vào thùng rác", Toast.LENGTH_SHORT).show();
                                finish();
                            }

                            @Override
                            public void onError(String error) {
                                binding.progressBar.setVisibility(View.GONE);
                                Toast.makeText(PhotoDetailActivity.this, "Lỗi: " + error, Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                })
                .setNegativeButton("Hủy", null)
                .show();
    }

    private void loadUserAlbums() {
        if (photo.getOwnerUid() == null || "local".equals(photo.getOwnerUid())) return;
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) return;
        albumsLoading = true;

        repository.getAlbums(user.getUid(), new FirestoreRepository.RepositoryCallback<List<Album>>() {
            @Override
            public void onSuccess(List<Album> albums) {
                userAlbums = albums != null ? albums : new ArrayList<>();
                albumsLoaded = true;
                albumsLoading = false;
            }

            @Override
            public void onError(String error) { albumsLoading = false; }
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
                    FirebaseUser user = mAuth.getCurrentUser();
                    if (user != null) {
                        repository.updatePhotoAlbums(user.getUid(), photo.getId(), photo.getAlbumIds(), new FirestoreRepository.RepositoryCallback<Void>() {
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
