package com.example.th6.activities;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.format.Formatter;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.example.th6.data.AuthRepository;
import com.example.th6.data.PhotoRepository;
import com.example.th6.data.StorageRepository;
import com.example.th6.databinding.ActivityTrashDetailBinding;
import com.example.th6.models.Photo;
import com.example.th6.utils.PhotoVaultTrashCache;
import io.github.jan.supabase.auth.user.UserInfo;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import java.text.DateFormat;
import java.util.Collections;
import java.util.Date;

public class TrashDetailActivity extends AppCompatActivity {
    public static final String EXTRA_PHOTO = "trash_photo";

    private enum LocalState { EXISTS, MISSING, UNKNOWN }

    private ActivityTrashDetailBinding binding;
    private Photo photo;
    private PhotoRepository photoRepository;
    private StorageRepository storageRepository;
    private AuthRepository authRepository;
    private ActivityResultLauncher<IntentSenderRequest> deleteMediaLauncher;
    private ActivityResultLauncher<String> writePermissionLauncher;
    private ExoPlayer player;
    private boolean operationRunning;
    private boolean cloudObjectDeleted;
    private boolean usingLocalVideo;
    private boolean waitingForQDeleteConsent;

    public static Intent createIntent(android.content.Context context, Photo photo) {
        Intent intent = new Intent(context, TrashDetailActivity.class);
        intent.putExtra(EXTRA_PHOTO, photo);
        return intent;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityTrashDetailBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        photoRepository = new PhotoRepository();
        storageRepository = new StorageRepository();
        authRepository = new AuthRepository();

        deleteMediaLauncher = registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(), result -> {
            if (result.getResultCode() == Activity.RESULT_OK) {
                if (waitingForQDeleteConsent) {
                    waitingForQDeleteConsent = false;
                    deleteLocalOnQ(false);
                } else if (isLocalOnly()) {
                    completeLocalOnlyPermanentDelete();
                } else {
                    cloudObjectDeleted = false;
                    binding.tvSourceStatus.setText("Đã xóa bản local; đang xóa bản cloud…");
                    deleteCloudSource();
                }
            } else {
                finishOperation("Đã hủy xác nhận. Các bản còn lại vẫn được giữ.");
            }
        });
        writePermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) deleteLocalOnLegacy();
            else finishOperation("Chưa được cấp quyền xóa local. Bản cloud và metadata vẫn được giữ.");
        });

        if (Build.VERSION.SDK_INT >= 33) photo = getIntent().getSerializableExtra(EXTRA_PHOTO, Photo.class);
        else {
            //noinspection deprecation
            photo = (Photo) getIntent().getSerializableExtra(EXTRA_PHOTO);
        }
        if (photo == null) {
            Toast.makeText(this, "Không tìm thấy mục trong thùng rác", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        bindMetadata();
        binding.btnBack.setOnClickListener(v -> finish());
        binding.btnInfo.setOnClickListener(v -> showInfo());
        binding.btnRestore.setOnClickListener(v -> restore());
        binding.btnDeletePermanently.setOnClickListener(v -> confirmPermanentDelete());
        showMedia();
    }

    private void bindMetadata() {
        binding.tvItemName.setText(photo.getName() == null || photo.getName().isBlank() ? "Không có tên" : photo.getName());
        LocalState state = localState();
        String local = state == LocalState.EXISTS ? "Local: có thể truy cập"
                : state == LocalState.MISSING ? "Local: không còn trong MediaStore" : "Local: chưa xác minh được";
        binding.tvSourceStatus.setText(local + "  •  Cloud: " + (hasCloudUrl() ? "có metadata" : "không có URL"));
    }

    private boolean isVideo() { return "video".equalsIgnoreCase(photo.getMediaType()); }
    private boolean isLocalOnly() { return photo != null && "local".equals(photo.getOwnerUid()); }
    private boolean hasCloudUrl() { return photo.getImageUrl() != null && photo.getImageUrl().startsWith("http"); }

    private LocalState localState() {
        String localUri = photo.getLocalMediaUri();
        if (localUri == null || localUri.isBlank()) return LocalState.MISSING;
        Uri uri;
        try { uri = Uri.parse(localUri); }
        catch (Exception e) { return LocalState.UNKNOWN; }
        if (!"content".equalsIgnoreCase(uri.getScheme()) || uri.getAuthority() == null
                || !uri.getAuthority().contains("media")) return LocalState.UNKNOWN;
        try (Cursor cursor = getContentResolver().query(uri, new String[]{MediaStore.MediaColumns._ID}, null, null, null)) {
            return cursor != null && cursor.moveToFirst() ? LocalState.EXISTS : LocalState.MISSING;
        } catch (Exception e) { return LocalState.UNKNOWN; }
    }

    private void showMedia() {
        LocalState state = localState();
        Uri localUri = photo.getLocalMediaUri() == null || photo.getLocalMediaUri().isBlank()
                ? null : Uri.parse(photo.getLocalMediaUri());
        Uri cloudUri = hasCloudUrl() ? Uri.parse(photo.getImageUrl()) : null;
        Uri source = state == LocalState.EXISTS || state == LocalState.UNKNOWN ? localUri : null;
        if (source == null) source = cloudUri;
        if (source == null) {
            showPreviewError("Không thể truy cập bản local hoặc cloud. Thông tin và thao tác thùng rác vẫn khả dụng.");
            return;
        }
        if (isVideo()) showVideo(source, localUri, cloudUri);
        else showImage(source, localUri, cloudUri);
    }

    private void showImage(Uri source, Uri localUri, Uri cloudUri) {
        binding.playerView.setVisibility(View.GONE);
        binding.imgPreview.setVisibility(View.VISIBLE);
        binding.tvPreviewStatus.setText("Đang tải ảnh…");
        Glide.with(this).load(source).listener(new RequestListener<android.graphics.drawable.Drawable>() {
            @Override public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<android.graphics.drawable.Drawable> target, boolean isFirstResource) {
                if (localUri != null && source.equals(localUri) && cloudUri != null && !cloudUri.equals(localUri)) {
                    loadCloudImage(cloudUri);
                } else showPreviewError("Không thể tải ảnh từ các nguồn còn lại.");
                return true;
            }
            @Override public boolean onResourceReady(android.graphics.drawable.Drawable resource, Object model, Target<android.graphics.drawable.Drawable> target, DataSource dataSource, boolean isFirstResource) {
                binding.tvPreviewStatus.setVisibility(View.GONE);
                return false;
            }
        }).into(binding.imgPreview);
    }

    private void loadCloudImage(Uri cloudUri) {
        binding.tvPreviewStatus.setVisibility(View.VISIBLE);
        binding.tvPreviewStatus.setText("Bản local không mở được; đang thử bản cloud…");
        Glide.with(this).load(cloudUri).listener(new RequestListener<android.graphics.drawable.Drawable>() {
            @Override public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<android.graphics.drawable.Drawable> target, boolean isFirstResource) {
                showPreviewError("Không thể tải ảnh local hoặc cloud.");
                return true;
            }
            @Override public boolean onResourceReady(android.graphics.drawable.Drawable resource, Object model, Target<android.graphics.drawable.Drawable> target, DataSource dataSource, boolean isFirstResource) {
                binding.tvPreviewStatus.setVisibility(View.GONE);
                return false;
            }
        }).into(binding.imgPreview);
    }

    private void showVideo(Uri source, Uri localUri, Uri cloudUri) {
        binding.imgPreview.setVisibility(View.GONE);
        binding.playerView.setVisibility(View.VISIBLE);
        binding.tvPreviewStatus.setVisibility(View.VISIBLE);
        binding.tvPreviewStatus.setText("Đang mở video…");
        usingLocalVideo = localUri != null && source.equals(localUri);
        player = new ExoPlayer.Builder(this).build();
        binding.playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onRenderedFirstFrame() { binding.tvPreviewStatus.setVisibility(View.GONE); }
            @Override public void onPlayerError(PlaybackException error) {
                if (usingLocalVideo && cloudUri != null && !cloudUri.equals(source)) {
                    usingLocalVideo = false;
                    binding.tvPreviewStatus.setVisibility(View.VISIBLE);
                    binding.tvPreviewStatus.setText("Bản local không phát được; đang thử bản cloud…");
                    player.setMediaItem(MediaItem.fromUri(cloudUri));
                    player.prepare();
                    player.play();
                } else showPreviewError("Không thể phát video từ các nguồn còn lại.");
            }
        });
        player.setMediaItem(MediaItem.fromUri(source));
        player.prepare();
        player.play();
    }

    private void showPreviewError(String message) {
        binding.imgPreview.setVisibility(View.GONE);
        binding.playerView.setVisibility(View.GONE);
        binding.tvPreviewStatus.setVisibility(View.VISIBLE);
        binding.tvPreviewStatus.setText(message);
    }

    private void restore() {
        if (operationRunning) return;
        if (isLocalOnly()) {
            PhotoVaultTrashCache.remove(getApplicationContext(), "local", photo.getId());
            Toast.makeText(this, "Đã khôi phục mục local; file gốc vẫn ở trên thiết bị.", Toast.LENGTH_LONG).show();
            setResult(Activity.RESULT_OK);
            finish();
            return;
        }
        UserInfo user = authRepository.getCurrentUser();
        if (user == null) { showMessage("Đăng nhập lại để khôi phục bản cloud."); return; }
        LocalState local = localState();
        setBusy(true);
        binding.tvSourceStatus.setText("Đang khôi phục trạng thái cloud…");
        photoRepository.moveToTrashAsync(user.getId(), photo.getId(), false, new PhotoRepository.Callback<Void>() {
            @Override public void onSuccess(Void result) {
                PhotoVaultTrashCache.remove(getApplicationContext(), user.getId(), photo.getId());
                String message = local == LocalState.EXISTS
                        ? "Đã khôi phục bản cloud; file local vẫn còn trên thiết bị."
                        : local == LocalState.MISSING
                        ? "Đã khôi phục bản cloud. File local đã bị xóa và không được khôi phục về thiết bị."
                        : "Đã khôi phục bản cloud. Không xác minh được trạng thái file local.";
                Toast.makeText(TrashDetailActivity.this, message, Toast.LENGTH_LONG).show();
                setResult(Activity.RESULT_OK);
                finish();
            }
            @Override public void onError(String error) {
                setBusy(false);
                binding.tvSourceStatus.setText("Khôi phục thất bại. Mục vẫn ở trong thùng rác.");
                showMessage("Không khôi phục được bản cloud: " + error);
            }
        });
    }
    private void confirmPermanentDelete() {
        if (operationRunning) return;
        LocalState local = localState();
        String scope = isLocalOnly()
                ? (local == LocalState.EXISTS ? "File local sẽ bị xóa vĩnh viễn khỏi thiết bị; không có bản cloud."
                : local == LocalState.MISSING ? "File local không còn tồn tại; metadata Trash sẽ bị xóa."
                : "Không xác minh được file local; mục sẽ được giữ nếu không thể xóa an toàn.")
                : local == LocalState.EXISTS ? "Bản local và bản cloud sẽ bị xóa vĩnh viễn."
                : local == LocalState.MISSING ? "Bản cloud sẽ bị xóa vĩnh viễn. Không tìm thấy bản local trên thiết bị."
                : "Không xác minh được bản local. Xác nhận sẽ không bỏ qua lỗi xóa local; bản cloud chỉ được xử lý sau khi kiểm tra nguồn.";
        new AlertDialog.Builder(this)
                .setTitle("Xóa vĩnh viễn?")
                .setMessage(scope + " Thao tác này không thể hoàn tác.")
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Xóa vĩnh viễn", (dialog, which) -> beginPermanentDelete(local))
                .show();
    }
    private void beginPermanentDelete(LocalState local) {
        if (operationRunning) return;
        if (local == LocalState.UNKNOWN) {
            binding.tvSourceStatus.setText("Không xác minh được URI local; chưa xóa nguồn nào. Kiểm tra quyền truy cập rồi thử lại.");
            return;
        }
        setBusy(true);
        if (local == LocalState.MISSING) {
            if (isLocalOnly()) {
                PhotoVaultTrashCache.remove(getApplicationContext(), "local", photo.getId());
                Toast.makeText(this, "Đã xóa metadata thùng rác; file local không còn tồn tại.", Toast.LENGTH_LONG).show();
                setResult(Activity.RESULT_OK);
                finish();
                return;
            }
            binding.tvSourceStatus.setText("Không còn bản local; đang xóa bản cloud…");
            deleteCloudSource();
            return;
        }
        Uri uri = Uri.parse(photo.getLocalMediaUri());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                PendingIntent request = MediaStore.createDeleteRequest(getContentResolver(), Collections.singletonList(uri));
                binding.tvSourceStatus.setText("Chờ xác nhận xóa bản local của Android…");
                deleteMediaLauncher.launch(new IntentSenderRequest.Builder(request.getIntentSender()).build());
            } catch (Exception e) {
                setBusy(false);
                binding.tvSourceStatus.setText("Không thể yêu cầu quyền xóa local. Bản cloud chưa bị xóa.");
            }
        } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) deleteLocalOnQ(true);
        else if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            binding.tvSourceStatus.setText("Cần quyền Android để xóa bản local…");
            writePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        } else deleteLocalOnLegacy();
    }

    private void deleteLocalOnQ(boolean allowConsent) {
        Uri uri = Uri.parse(photo.getLocalMediaUri());
        try {
            int rows = getContentResolver().delete(uri, null, null);
            if (rows > 0 || localState() == LocalState.MISSING) {
                if (isLocalOnly()) { completeLocalOnlyPermanentDelete(); return; }
                binding.tvSourceStatus.setText("Đã xóa bản local; đang xóa bản cloud…");
                deleteCloudSource();
            } else localDeleteFailed("MediaStore không xóa được bản local.");
        } catch (android.app.RecoverableSecurityException recoverable) {
            if (!allowConsent) { localDeleteFailed("Android chưa cấp quyền xóa bản local."); return; }
            try {
                waitingForQDeleteConsent = Build.VERSION.SDK_INT == Build.VERSION_CODES.Q;
                deleteMediaLauncher.launch(new IntentSenderRequest.Builder(recoverable.getUserAction().getActionIntent().getIntentSender()).build());
            } catch (Exception e) { localDeleteFailed("Không thể mở xác nhận xóa của Android."); }
        } catch (Exception e) { localDeleteFailed("Lỗi MediaStore; bản cloud chưa bị xóa."); }
    }

    private void deleteLocalOnLegacy() {
        Uri uri = Uri.parse(photo.getLocalMediaUri());
        try {
            int rows = getContentResolver().delete(uri, null, null);
            if (rows > 0 || localState() == LocalState.MISSING) {
                if (isLocalOnly()) { completeLocalOnlyPermanentDelete(); return; }
                binding.tvSourceStatus.setText("Đã xóa bản local; đang xóa bản cloud…");
                deleteCloudSource();
            } else localDeleteFailed("MediaStore không xóa được bản local.");
        } catch (Exception e) { localDeleteFailed("Lỗi MediaStore; bản cloud chưa bị xóa."); }
    }

    private void localDeleteFailed(String message) {
        setBusy(false);
        binding.tvSourceStatus.setText(message + " Bản cloud và metadata vẫn được giữ; có thể thử lại.");
        showMessage(message);
    }

    private void completeLocalOnlyPermanentDelete() {
        LocalState state = localState();
        if (state != LocalState.MISSING) {
            finishOperation(state == LocalState.EXISTS
                    ? "MediaStore vẫn báo file tồn tại sau thao tác xóa; mục vẫn nằm trong thùng rác."
                    : "Không xác minh được file đã xóa; mục vẫn nằm trong thùng rác.");
            return;
        }
        PhotoVaultTrashCache.remove(getApplicationContext(), "local", photo.getId());
        Toast.makeText(this, "Đã xóa vĩnh viễn file local.", Toast.LENGTH_LONG).show();
        setResult(Activity.RESULT_OK);
        finish();
    }
    private void deleteCloudSource() {
        UserInfo user = authRepository.getCurrentUser();
        if (user == null) { cloudDeleteFailed("Đăng nhập lại để xóa bản cloud."); return; }
        String provider = photo.getStorageProvider();
        String path = photo.getStoragePath();
        if (path == null || path.isBlank()) path = storageRepository.objectPathFromPublicUrl(photo.getImageUrl());
        if (!"supabase".equalsIgnoreCase(provider) || path == null || path.isBlank()) {
            cloudDeleteFailed("Không có đường dẫn Supabase có thể xóa an toàn. Bản local đã xử lý nếu có; metadata vẫn được giữ.");
            return;
        }
        if (cloudObjectDeleted) { deleteMetadata(user.getId()); return; }
        binding.tvSourceStatus.setText("Đang xóa bản cloud…");
        storageRepository.deleteImageAsync(path, new StorageRepository.DeleteCallback() {
            @Override public void onSuccess() {
                cloudObjectDeleted = true;
                deleteMetadata(user.getId());
            }
            @Override public void onError(String error) { cloudDeleteFailed("Không xóa được bản cloud: " + error); }
        });
    }

    private void deleteMetadata(String uid) {
        binding.tvSourceStatus.setText("Đã xóa bản cloud; đang xóa metadata…");
        photoRepository.deletePhotoAsync(uid, photo.getId(), new PhotoRepository.Callback<Void>() {
            @Override public void onSuccess(Void result) {
                PhotoVaultTrashCache.remove(getApplicationContext(), uid, photo.getId());
                Toast.makeText(TrashDetailActivity.this, "Đã xóa vĩnh viễn các nguồn có thể truy cập.", Toast.LENGTH_LONG).show();
                setResult(Activity.RESULT_OK);
                finish();
            }
            @Override public void onError(String error) {
                setBusy(false);
                binding.tvSourceStatus.setText("Bản cloud đã xóa; metadata còn lại. Nhấn xóa lại để thử đồng bộ metadata.");
                showMessage("Không xóa được metadata: " + error);
            }
        });
    }

    private void cloudDeleteFailed(String message) {
        setBusy(false);
        binding.tvSourceStatus.setText(message + " Metadata vẫn được giữ để thử lại.");
        showMessage(message);
    }

    private void showInfo() {
        LocalState local = localState();
        String unknown = "Không có dữ liệu";
        String date = photo.getCreatedAt() > 0 ? DateFormat.getDateTimeInstance().format(new Date(photo.getCreatedAt())) : unknown;
        String size = getLocalSize();
        String type = photo.getMediaType() == null || photo.getMediaType().isBlank() ? unknown : photo.getMediaType();
        String sources = "Local: " + (local == LocalState.EXISTS ? "có thể truy cập" : local == LocalState.MISSING ? "không còn" : "chưa xác minh")
                + "\nCloud: " + (hasCloudUrl() ? "có URL trong metadata" : "không có URL");
        new AlertDialog.Builder(this)
                .setTitle("Thông tin mục trong thùng rác")
                .setMessage("Tên: " + (photo.getName() == null ? unknown : photo.getName())
                        + "\nLoại: " + type
                        + "\nNgày ghi nhận: " + date
                        + "\nDung lượng local: " + size
                        + "\n" + sources)
                .setPositiveButton("Đóng", null)
                .show();
    }

    private String getLocalSize() {
        if (localState() != LocalState.EXISTS) return "Không có dữ liệu đáng tin cậy";
        Uri uri = Uri.parse(photo.getLocalMediaUri());
        try (Cursor cursor = getContentResolver().query(uri, new String[]{MediaStore.MediaColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                long bytes = cursor.getLong(0);
                if (bytes > 0) return Formatter.formatFileSize(this, bytes);
            }
        } catch (Exception ignored) { }
        return "Không có dữ liệu đáng tin cậy";
    }

    private void setBusy(boolean busy) {
        operationRunning = busy;
        binding.btnRestore.setEnabled(!busy);
        binding.btnDeletePermanently.setEnabled(!busy);
        binding.btnInfo.setEnabled(!busy);
    }

    private void finishOperation(String message) {
        setBusy(false);
        binding.tvSourceStatus.setText(message);
    }

    private void showMessage(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }

    @Override protected void onStop() {
        super.onStop();
        if (player != null) player.pause();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (player != null) { player.release(); player = null; }
        if (binding != null) binding.playerView.setPlayer(null);
        binding = null;
    }
}
