package com.example.th6.activities;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.format.Formatter;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.th6.R;
import com.example.th6.adapters.SmartCleanupAdapter;
import com.example.th6.data.AuthRepository;
import com.example.th6.data.PhotoRepository;
import com.example.th6.databinding.ActivitySmartCleanupBinding;
import com.example.th6.models.LocalMediaPhoto;
import com.example.th6.models.Photo;
import com.example.th6.models.SmartCleanupMedia;
import com.example.th6.services.SmartCleanupScanner;
import com.example.th6.utils.PhotoTrashManager;
import io.github.jan.supabase.auth.user.UserInfo;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class SmartCleanupActivity extends AppCompatActivity implements SmartCleanupAdapter.Listener {
    private ActivitySmartCleanupBinding binding;
    private SmartCleanupAdapter adapter;
    private AuthRepository authRepository;
    private PhotoRepository repository;
    private SmartCleanupScanner.Session scanSession;
    private SmartCleanupScanner.Result currentResult;
    private final List<SmartCleanupMedia> currentMedia = new ArrayList<>();
    private ActivityResultLauncher<String[]> permissionLauncher;
    private String scanUid;
    private boolean scanRunning;
    private boolean trashRunning;
    private int scanGeneration;

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        binding = ActivitySmartCleanupBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        authRepository = new AuthRepository(); repository = new PhotoRepository();
        adapter = new SmartCleanupAdapter(this, this);
        binding.rvCleanupResults.setLayoutManager(new LinearLayoutManager(this));
        binding.rvCleanupResults.setAdapter(adapter);
        binding.toolbarSmartCleanup.setNavigationOnClickListener(v -> finish());
        binding.btnCleanupScan.setOnClickListener(v -> requestOrStartScan());
        binding.btnCleanupCancel.setOnClickListener(v -> cancelScan());
        binding.btnCleanupTrash.setOnClickListener(v -> confirmTrashSelected());
        binding.tvCleanupEmpty.setVisibility(View.VISIBLE);
        permissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
            if (hasAnyMediaReadPermission()) startScan();
            else setStatus("Chưa được cấp quyền đọc thư viện. Chỉ có thể quét media được cấp quyền truy cập.");
        });
        UserInfo signedIn = authRepository.getCurrentUser();
        if (signedIn != null) {
            SmartCleanupScanner.Result latest = SmartCleanupScanner.getLatest(signedIn.getId());
            if (latest != null) {
                for (SmartCleanupMedia media : latest.allMedia) media.selected = false;
                scanUid = signedIn.getId(); scanRunning = true;
                showScanResult(latest);
            }
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (scanUid != null && !sameActiveUid()) {
            SmartCleanupScanner.clearLatestForUid(scanUid);
            scanGeneration++;
            if (scanSession != null) scanSession.cancel();
            scanSession = null; scanRunning = false; currentResult = null; currentMedia.clear();
            if (adapter != null) adapter.setResults(null);
            if (binding != null) {
                binding.progressCleanup.setVisibility(View.GONE); binding.btnCleanupCancel.setVisibility(View.GONE);
                binding.btnCleanupScan.setEnabled(true); binding.tvCleanupStats.setText(R.string.smart_cleanup_not_scanned);
                binding.tvCleanupEmpty.setVisibility(View.VISIBLE); updateSelectionUi();
                setStatus("Tài khoản đã đổi; kết quả cũ đã được loại bỏ. Hãy quét lại.");
            }
        }
    }

    private void requestOrStartScan() {
        if (scanRunning || trashRunning) return;
        if (hasAnyMediaReadPermission()) { startScan(); return; }
        if (Build.VERSION.SDK_INT >= 33) {
            List<String> permissions = new ArrayList<>();
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES);
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO);
            if (Build.VERSION.SDK_INT >= 34) permissions.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
            permissionLauncher.launch(permissions.toArray(new String[0]));
        } else permissionLauncher.launch(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE});
    }

    private boolean hasAnyMediaReadPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            boolean images = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED;
            boolean videos = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED;
            boolean selected = Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED;
            return images || videos || selected;
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void startScan() {
        UserInfo user = authRepository.getCurrentUser();
        if (user == null || user.getId() == null || user.getId().isEmpty()) {
            setStatus("Đăng nhập để quét thư viện cloud của tài khoản."); return;
        }
        // A new scan replaces the previous session and clears its selectable results immediately.
        if (scanSession != null) scanSession.cancel();
        currentResult = null; currentMedia.clear(); adapter.setResults(null); updateSelectionUi();
        scanUid = user.getId(); scanRunning = true;
        final int generation = ++scanGeneration;
        binding.progressCleanup.setVisibility(View.VISIBLE); binding.btnCleanupCancel.setVisibility(View.VISIBLE);
        binding.btnCleanupScan.setEnabled(false); binding.tvCleanupEmpty.setVisibility(View.GONE);
        binding.tvCleanupStats.setText("Đang quét media trên thiết bị và cloud…");
        setStatus("Đang bắt đầu quét");
        scanSession = SmartCleanupScanner.scan(getApplicationContext(), scanUid, new SmartCleanupScanner.Listener() {
            @Override public void onProgress(String stage, int processed, int total) {
                if (isFinishing() || isDestroyed()) return;
                runOnUiThread(() -> { if (scanRunning && generation == scanGeneration) setStatus(stage + " • " + processed + "/" + total); });
            }
            @Override public void onComplete(SmartCleanupScanner.Result result) {
                if (isFinishing() || isDestroyed()) return;
                runOnUiThread(() -> { if (generation == scanGeneration) showScanResult(result); });
            }
            @Override public void onCancelled() {
                if (isFinishing() || isDestroyed()) return;
                runOnUiThread(() -> { if (generation == scanGeneration) finishCancelledScan(); });
            }
        });
    }

    private void showScanResult(SmartCleanupScanner.Result result) {
        if (!scanRunning) return;
        UserInfo active = authRepository.getCurrentUser();
        if (active == null || !scanUid.equals(active.getId())) { finishCancelledScan(); setStatus("Phiên đăng nhập đã đổi; bỏ kết quả quét cũ."); return; }
        currentResult = result; currentMedia.clear(); currentMedia.addAll(result.allMedia);
        scanRunning = false; scanSession = null;
        binding.progressCleanup.setVisibility(View.GONE); binding.btnCleanupCancel.setVisibility(View.GONE); binding.btnCleanupScan.setEnabled(true);
        adapter.setResults(result);
        String summary = result.allMedia.size() + " media • dung lượng đã xác định " + Formatter.formatFileSize(this, result.analyzedBytes)
                + " • quét lúc " + android.text.format.DateFormat.format("dd/MM/yyyy HH:mm", new Date(result.completedAt));
        binding.tvCleanupStats.setText(summary);
        String warning = result.warning == null ? "" : result.warning;
        if (!result.localScanned) warning = append(warning, "MediaStore chưa được quét đầy đủ.");
        if (!result.cloudScanned) warning = append(warning, "Kết quả chỉ gồm local; cloud chưa được quét.");
        if (!hasFullImageAndVideoPermission()) warning = append(warning, "Quyền media bị giới hạn; một số ảnh/video có thể không xuất hiện.");
        if (result.partialComparison) warning = append(warning, "Một phần ảnh không thể so sánh nội dung.");
        setStatus(warning.isEmpty() ? "Hoàn tất quét local và cloud." : "Quét một phần: " + warning);
        boolean empty = result.duplicates.isEmpty() && result.similar.isEmpty() && result.largeVideos.isEmpty() && result.unknownSizeVideos.isEmpty();
        binding.tvCleanupEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        updateSelectionUi();
    }

    private boolean hasFullImageAndVideoPermission() {
        if (Build.VERSION.SDK_INT >= 33) return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED;
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private String append(String original, String extra) { return original == null || original.isEmpty() ? extra : original + " " + extra; }
    private void setStatus(String text) { binding.tvCleanupStatus.setText(text); }

    private void cancelScan() {
        if (!scanRunning) return;
        scanGeneration++;
        if (scanSession != null) scanSession.cancel();
        SmartCleanupScanner.clearLatestForUid(scanUid);
        finishCancelledScan();
    }
    private void finishCancelledScan() {
        scanRunning = false; scanSession = null; currentResult = null; currentMedia.clear(); adapter.setResults(null);
        binding.progressCleanup.setVisibility(View.GONE); binding.btnCleanupCancel.setVisibility(View.GONE); binding.btnCleanupScan.setEnabled(true);
        binding.tvCleanupStats.setText("Phiên quét đã hủy; chưa có kết quả hiện hành."); binding.tvCleanupEmpty.setVisibility(View.VISIBLE); updateSelectionUi();
        if (!isFinishing()) setStatus("Đã hủy quét");
    }

    @Override public void onToggle(SmartCleanupMedia item) {
        if (trashRunning || scanRunning) return;
        item.selected = !item.selected; updateSelectionUi(); adapter.notifyDataSetChanged();
    }
    @Override public void onOpen(SmartCleanupMedia item) {
        if (item.video) {
            LocalMediaPhoto video = item.local;
            if (video == null) {
                video = new LocalMediaPhoto(); video.setContentUri(""); video.setDisplayName(item.getName()); video.setVideo(true);
                video.setCloudUrl(item.cloud == null ? "" : item.cloud.getImageUrl()); video.setCloudPhoto(item.cloud);
            }
            startActivity(new Intent(this, VideoPlayerActivity.class).putExtra("video", video));
            return;
        }
        Photo photo = item.cloud;
        if (photo == null) {
            photo = new Photo(); photo.setOwnerUid("local"); photo.setId(localTrashId(item.local));
            photo.setName(item.getName()); photo.setMediaType("image"); photo.setCreatedAt(item.local == null ? System.currentTimeMillis() : item.local.getTimelineTimestamp());
            photo.setImageUrl(item.local == null ? "" : item.local.getContentUri());
        }
        if (item.local != null) { photo.setLocalMediaUri(item.local.getContentUri()); photo.setLocalMediaId(item.local.getMediaId()); photo.setLocalDateModified(item.local.getDateModified()); }
        List<Photo> collection = new ArrayList<>();
        for (SmartCleanupMedia media : currentMedia) if (!media.video) collection.add(photoFor(media));
        startActivity(PhotoDetailActivity.createIntent(this, photo, collection));
    }

    private Photo photoFor(SmartCleanupMedia item) {
        if (item.cloud != null) return item.cloud;
        Photo photo = new Photo(); photo.setOwnerUid("local"); photo.setId(localTrashId(item.local)); photo.setName(item.getName());
        photo.setImageUrl(item.local.getContentUri()); photo.setLocalMediaUri(item.local.getContentUri()); photo.setLocalMediaId(item.local.getMediaId()); photo.setMediaType("image");
        photo.setLocalDateModified(item.local.getDateModified()); photo.setCreatedAt(item.local.getTimelineTimestamp()); return photo;
    }

    private void updateSelectionUi() {
        if (binding == null) return;
        int selected = 0; for (SmartCleanupMedia item : currentMedia) if (item.selected) selected++;
        if (adapter != null) adapter.setInteractionEnabled(!scanRunning && !trashRunning);
        binding.btnCleanupTrash.setEnabled(selected > 0 && !scanRunning && !trashRunning);
        binding.btnCleanupTrash.setText(selected == 0 ? getString(R.string.smart_cleanup_trash_selected)
                : getString(R.string.smart_cleanup_trash_count, selected));
    }

    private void confirmTrashSelected() {
        List<SmartCleanupMedia> chosen = new ArrayList<>(); for (SmartCleanupMedia item : currentMedia) if (item.selected) chosen.add(item);
        if (chosen.isEmpty() || currentResult == null || scanUid == null) return;
        new AlertDialog.Builder(this).setTitle(R.string.smart_cleanup_trash_title)
                .setMessage(getString(R.string.smart_cleanup_confirm_trash, chosen.size()))
                .setNegativeButton(R.string.btn_cancel, null)
                .setPositiveButton(R.string.smart_cleanup_trash_confirm, (d, w) -> revalidateAndTrash(chosen))
                .show();
    }

    private void revalidateAndTrash(List<SmartCleanupMedia> chosen) {
        UserInfo active = authRepository.getCurrentUser();
        if (active == null || !scanUid.equals(active.getId())) { setStatus("Tài khoản đã đổi. Hãy quét lại trước khi dọn."); return; }
        trashRunning = true; updateSelectionUi(); setStatus("Đang xác minh kết quả và chuyển mục đã chọn vào Thùng rác…");
        processTrashItem(chosen, 0, 0, new ArrayList<>());
    }

    private void processTrashItem(List<SmartCleanupMedia> chosen, int index, int successes, List<String> errors) {
        if (index >= chosen.size()) { finishTrash(successes, chosen.size() - successes, errors.isEmpty() ? "" : errors.get(0)); return; }
        if (!sameActiveUid()) { errors.add("Tài khoản đã đổi."); finishTrash(successes, chosen.size() - successes, errors.get(0)); return; }
        SmartCleanupMedia item = chosen.get(index);
        if (item.local != null && !verifyLocalStillSame(item.local)) {
            errors.add(item.getName() + ": file local đã biến mất, đổi hoặc không còn quyền truy cập.");
            processTrashItem(chosen, index + 1, successes, errors); return;
        }
        if (item.cloud != null) {
            repository.getPhotoByUserAsync(scanUid, item.cloud.getId(), new PhotoRepository.Callback<Photo>() {
                @Override public void onSuccess(Photo latest) {
                    if (!sameActiveUid()) { errors.add("Tài khoản đã đổi."); processTrashItem(chosen, index + 1, successes, errors); return; }
                    if (latest == null || !scanUid.equals(latest.getOwnerUid()) || latest.isTrashed() || !sameCloudRevision(item.cloud, latest)
                            || (item.local != null && !isStillLinked(item, latest))) {
                        errors.add(item.getName() + ": cloud đã mất, vào Trash, thay đổi hoặc không còn liên kết sau lần quét.");
                        processTrashItem(chosen, index + 1, successes, errors); return;
                    }
                    moveVerifiedItem(chosen, index, successes, errors, item, latest);
                }
                @Override public void onError(String error) {
                    errors.add(item.getName() + ": không thể xác minh cloud; không thay đổi mục này. " + error);
                    processTrashItem(chosen, index + 1, successes, errors);
                }
            });
            return;
        }
        Photo target = null;
        if (item.local != null) {
            target = new Photo(); target.setId(localTrashId(item.local)); target.setOwnerUid("local"); target.setName(item.getName());
            target.setImageUrl(item.local.getContentUri()); target.setMediaType(item.video ? "video" : "image");
            target.setCreatedAt(item.local.getTimelineTimestamp()); target.setLocalMediaUri(item.local.getContentUri());
            target.setLocalMediaId(item.local.getMediaId()); target.setLocalDateModified(item.local.getDateModified());
        }
        if (target == null) {
            errors.add(item.getName() + ": không có tham chiếu hợp lệ.");
            processTrashItem(chosen, index + 1, successes, errors); return;
        }
        moveVerifiedItem(chosen, index, successes, errors, item, target);
    }

    private void moveVerifiedItem(List<SmartCleanupMedia> chosen, int index, int successes, List<String> errors,
                                  SmartCleanupMedia item, Photo target) {
        PhotoTrashManager.moveToTrash(this, scanUid, target, new PhotoTrashManager.Callback() {
            @Override public void onSuccess(Photo moved) {
                item.selected = false;
                processTrashItem(chosen, index + 1, successes + 1, errors);
            }
            @Override public void onError(String error) {
                errors.add(item.getName() + ": " + error);
                processTrashItem(chosen, index + 1, successes, errors);
            }
        });
    }

    private boolean verifyLocalStillSame(LocalMediaPhoto local) {
        Uri uri = Uri.parse(local.getContentUri());
        try (CursorHolder ignored = queryLocal(uri); InputStream input = getContentResolver().openInputStream(uri)) {
            if (ignored.cursor == null || input == null) return false;
            if (!ignored.cursor.moveToFirst()) return false;
            long id = value(ignored.cursor, MediaStore.MediaColumns._ID, -1);
            long size = value(ignored.cursor, MediaStore.MediaColumns.SIZE, -1);
            long modified = value(ignored.cursor, MediaStore.MediaColumns.DATE_MODIFIED, -1);
            if (id != local.getMediaId() || (local.getSizeBytes() > 0 && size != local.getSizeBytes())) return false;
            return local.getDateModified() <= 0 || modified <= 0 || modified * 1000L == local.getDateModified();
        } catch (Exception e) { return false; }
    }

    private CursorHolder queryLocal(Uri uri) { return new CursorHolder(getContentResolver().query(uri, new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED}, null, null, null)); }
    private long value(android.database.Cursor cursor, String column, long fallback) { int i = cursor.getColumnIndex(column); return i >= 0 && !cursor.isNull(i) ? cursor.getLong(i) : fallback; }
    private static final class CursorHolder implements AutoCloseable {
        final android.database.Cursor cursor; CursorHolder(android.database.Cursor cursor) { this.cursor = cursor; }
        @Override public void close() { if (cursor != null) cursor.close(); }
    }

    private boolean sameCloudRevision(Photo scanned, Photo latest) {
        if ((scanned.getContentHash() != null || latest.getContentHash() != null)
                && (scanned.getContentHash() == null || latest.getContentHash() == null
                || !scanned.getContentHash().equalsIgnoreCase(latest.getContentHash()))) return false;
        if (!java.util.Objects.equals(scanned.getLocalMediaUri(), latest.getLocalMediaUri())) return false;
        if (scanned.getLocalMediaId() != latest.getLocalMediaId()) return false;
        return scanned.getLocalDateModified() == latest.getLocalDateModified()
                && java.util.Objects.equals(scanned.getImageUrl(), latest.getImageUrl())
                && java.util.Objects.equals(scanned.getStoragePath(), latest.getStoragePath())
                && java.util.Objects.equals(scanned.getMediaType(), latest.getMediaType())
                && java.util.Objects.equals(scanned.getName(), latest.getName());
    }
    private boolean isStillLinked(SmartCleanupMedia item, Photo latest) {
        LocalMediaPhoto local = item.local;
        return java.util.Objects.equals(latest.getLocalMediaUri(), local.getContentUri())
                || (latest.getLocalMediaId() == local.getMediaId() && latest.getLocalDateModified() == local.getDateModified())
                || (item.contentHash != null && latest.getContentHash() != null && item.contentHash.equalsIgnoreCase(latest.getContentHash()));
    }
    private boolean sameActiveUid() { UserInfo active = authRepository.getCurrentUser(); return active != null && scanUid != null && scanUid.equals(active.getId()); }

    private String localTrashId(LocalMediaPhoto local) {
        String uri = local == null || local.getContentUri() == null ? "missing" : local.getContentUri();
        return "local:" + UUID.nameUUIDFromBytes(uri.getBytes(StandardCharsets.UTF_8));
    }

    private void finishTrash(int success, int failure, String firstError) {
        trashRunning = false; updateSelectionUi();
        if (adapter != null) adapter.notifyDataSetChanged();
        String message = "Đã chuyển " + success + " mục vào Thùng rác" + (failure > 0 ? "; " + failure + " mục chưa xử lý được." : ".");
        if (firstError != null && !firstError.isEmpty()) message += " " + firstError;
        setStatus(message); Toast.makeText(this, message, failure > 0 ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
    }

    @Override protected void onDestroy() {
        if (scanSession != null) scanSession.cancel();
        if (scanUid != null && !sameActiveUid()) SmartCleanupScanner.clearLatestForUid(scanUid);
        scanGeneration++;
        binding = null;
        super.onDestroy();
    }
}
