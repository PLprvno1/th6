package com.example.th6.services;

import android.content.Context;
import android.content.pm.PackageManager;
import android.Manifest;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.example.th6.data.AuthRepository;
import com.example.th6.data.PhotoRepository;
import com.example.th6.data.StorageRepository;
import com.example.th6.models.LocalMediaPhoto;
import com.example.th6.models.Photo;
import com.example.th6.utils.BackupPreferences;
import io.github.jan.supabase.auth.user.UserInfo;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

public class PhotoBackupWorker extends Worker {
    private static final long CALLBACK_TIMEOUT_SECONDS = 210;
    private static final long MAX_RUN_MILLIS = TimeUnit.MINUTES.toMillis(8);
    private static final ReentrantLock RUN_LOCK = new ReentrantLock();

    public PhotoBackupWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    static boolean acquireIdleRunLock() { return RUN_LOCK.tryLock(); }
    static void releaseIdleRunLock() { RUN_LOCK.unlock(); }

    @NonNull @Override public Result doWork() {
        if (!RUN_LOCK.tryLock()) return Result.retry();
        try {
            return runBackup();
        } finally {
            RUN_LOCK.unlock();
        }
    }

    @NonNull private Result runBackup() {
        long runStartedAt = SystemClock.elapsedRealtime();
        Context context = getApplicationContext();
        String requestedUid = getInputData().getString("backup_uid");
        AuthRepository authRepository = new AuthRepository();
        UserInfo user = authRepository.isLoggedIn() ? authRepository.getCurrentUser() : null;
        if (user == null) {
            if (requestedUid != null) BackupWorkScheduler.cancelPeriodic(context, requestedUid);
            return Result.success(output(0, 0, 0, "Bạn cần đăng nhập để backup."));
        }
        String uid = user.getId();
        if (requestedUid != null && !requestedUid.equals(uid)) {
            BackupWorkScheduler.cancelPeriodic(context, requestedUid);
            return Result.success(output(0, 0, 0, "Tài khoản đã thay đổi; bỏ qua lượt backup cũ."));
        }
        BackupPreferences prefs = new BackupPreferences(context, uid);
        if (!prefs.isBackupEnabled() || prefs.isPaused()) {
            BackupWorkScheduler.cancelPeriodic(context, uid);
            return Result.success(output(0, 0, 0, "Backup đang tắt hoặc tạm dừng."));
        }
        if (!hasAnyMediaReadPermission(context)) {
            return Result.success(output(0, 0, 0, "Chưa có quyền đọc ảnh; backup sẽ thử lại sau khi được cấp quyền."));
        }

        AtomicReference<List<LocalMediaPhoto>> pendingRef = new AtomicReference<>();
        AtomicReference<PhotoBackupManager.BackupStats> statsRef = new AtomicReference<>();
        AtomicReference<String> scanError = new AtomicReference<>();
        CountDownLatch scanLatch = new CountDownLatch(1);
        new PhotoBackupManager().processBackupState(context, uid, new PhotoBackupManager.ManagerCallback() {
            @Override public void onStatsReady(PhotoBackupManager.BackupStats stats, List<LocalMediaPhoto> all) {
                statsRef.set(stats);
                pendingRef.set(stats.pendingPhotos);
                scanLatch.countDown();
            }
            @Override public void onError(String error) { scanError.set(error); scanLatch.countDown(); }
        });
        if (!await(scanLatch, 45)) return Result.retry();
        if (scanError.get() != null) return Result.retry();
        List<LocalMediaPhoto> pending = pendingRef.get();
        PhotoBackupManager.BackupStats initialStats = statsRef.get();
        if (!prefs.isBackupEnabled() || prefs.isPaused()) {
            BackupWorkScheduler.cancelPeriodic(context, uid);
            return Result.success(output(0, 0, pending == null ? 0 : pending.size(), "Backup đang tắt hoặc tạm dừng."));
        }
        if (pending == null || pending.isEmpty()) {
            prefs.setLastSyncTime(System.currentTimeMillis());
            return Result.success(output(0, 0, 0, "Không có ảnh cần backup."));
        }

        StorageRepository storageRepository = new StorageRepository();
        PhotoRepository photoRepository = new PhotoRepository();
        BackupUploadLedger ledger = new BackupUploadLedger(context);
        String runKey = "run_" + uid + "_" + getId();
        android.content.SharedPreferences runPrefs = context.getSharedPreferences("photovault_backup_run_counts", Context.MODE_PRIVATE);
        int success = runPrefs.getInt(runKey, 0), failures = 0;
        int imageSuccess = 0;
        int videoSuccess = 0;
        boolean retryableFailure = false;
        String lastError = "";

        try {
            for (int index = 0; index < pending.size(); index++) {
                if (isStopped() || Thread.currentThread().isInterrupted()) return Result.retry();
                UserInfo activeUser = authRepository.isLoggedIn() ? authRepository.getCurrentUser() : null;
                if (activeUser == null || !uid.equals(activeUser.getId())) {
                    BackupWorkScheduler.cancelPeriodic(context, uid);
                    return Result.success(output(success, failures, pending.size() - index,
                            "Phiên hoặc tài khoản đã thay đổi; đã dừng backup để tránh dùng nhầm tài khoản."));
                }
                if (!prefs.isBackupEnabled() || prefs.isPaused()) {
                    BackupWorkScheduler.cancelPeriodic(context, uid);
                    runPrefs.edit().remove(runKey).apply();
                    return Result.success(output(success, failures, pending.size() - index, "Backup đã tạm dừng."));
                }
                LocalMediaPhoto local = pending.get(index);
                if (!hasMediaReadPermission(context, local.isVideo())) {
                    if (success > 0) prefs.setLastSyncTime(System.currentTimeMillis());
                    runPrefs.edit().remove(runKey).apply();
                    return Result.success(output(success, failures, pending.size() - index,
                            "Quyền đọc ảnh đã bị thu hồi; các ảnh còn lại được giữ để thử lại sau."));
                }
                if (SystemClock.elapsedRealtime() - runStartedAt >= MAX_RUN_MILLIS) return Result.retry();
                boolean hasUploadedObject = LocalMediaPhoto.STATUS_METADATA_PENDING.equals(local.getBackupStatus())
                        && local.getCloudUrl() != null && !local.getCloudUrl().isEmpty();
                String url;
                String storagePath;
                if (hasUploadedObject) {
                    url = local.getCloudUrl();
                    storagePath = storageRepository.objectPathFromPublicUrl(url);
                    if (storagePath.isEmpty()) storagePath = stableStoragePath(uid, local);
                } else {
                    AtomicReference<String> uploadedUrl = new AtomicReference<>();
                    AtomicReference<String> uploadError = new AtomicReference<>();
                    CountDownLatch uploadLatch = new CountDownLatch(1);
                    String fileName = stableStoragePath(uid, local);
                    setUploadingProgress(index, pending.size(), success, failures, local, imageSuccess, videoSuccess);
                    storageRepository.uploadMediaAsync(context, Uri.parse(local.getContentUri()), fileName,
                            resolveMimeType(local), new StorageRepository.UploadCallback() {
                        @Override public void onSuccess(String publicUrl) {
                            try (BackupUploadLedger callbackLedger = new BackupUploadLedger(context)) {
                                callbackLedger.recordUpload(uid, local.getContentUri(), local.getDateModified(), publicUrl);
                            } catch (RuntimeException ignored) { }
                            uploadedUrl.set(publicUrl);
                            uploadLatch.countDown();
                        }
                        @Override public void onError(String error) {
                            uploadError.set(error);
                            uploadLatch.countDown();
                        }
                    });
                    if (!await(uploadLatch, CALLBACK_TIMEOUT_SECONDS)) {
                        failures++; retryableFailure = true; lastError = "Hết thời gian upload: " + local.getDisplayName();
                        setProgress(index + 1, pending.size(), success, failures, imageSuccess, videoSuccess);
                        continue;
                    }
                    url = uploadedUrl.get();
                    storagePath = fileName;
                    if (url == null) {
                        failures++;
                        retryableFailure = true;
                        lastError = uploadError.get() == null ? "Supabase Storage không trả về URL ảnh." : uploadError.get();
                        setProgress(index + 1, pending.size(), success, failures, imageSuccess, videoSuccess);
                        continue;
                    }
                    try {
                        ledger.recordUpload(uid, local.getContentUri(), local.getDateModified(), url);
                    } catch (RuntimeException ignored) { }
                }

                setUploadingProgress(index, pending.size(), success, failures, local, imageSuccess, videoSuccess);
                Photo photo = new Photo(
                        local.getCloudPhotoId() == null ? "" : local.getCloudPhotoId(),
                        uid,
                        local.getDisplayName() != null ? local.getDisplayName() : "Photo_" + local.getMediaId(),
                        url,
                        url,
                        System.currentTimeMillis(),
                        local.isVideo() ? "video" : "image",
                        false,
                        false,
                        0L,
                        local.getMediaId(),
                        local.getContentUri(),
                        local.getDateModified(),
                        java.util.Collections.emptyList(),
                        "supabase",
                        computeContentHash(context, Uri.parse(local.getContentUri())),
                        storagePath
                );
                if (!prefs.isBackupEnabled() || prefs.isPaused()) {
                    // The Storage object, if already uploaded, is recoverable from the
                    // UID+URI ledger. Leave its metadata pending for a later enabled run.
                    runPrefs.edit().remove(runKey).apply();
                    if (success > 0) prefs.setLastSyncTime(System.currentTimeMillis());
                    return Result.success(output(success, failures, pending.size() - index,
                            "Backup Ä‘Ã£ táº¡m dá»«ng; media hiá»‡n táº¡i sáº½ Ä‘Æ°á»£c tiáº¿p tá»¥c sau."));
                }
                activeUser = authRepository.isLoggedIn() ? authRepository.getCurrentUser() : null;
                if (activeUser == null || !uid.equals(activeUser.getId())) {
                    // The Storage object is retained and the ledger can recover metadata on a later login.
                    return Result.success(output(success, failures + 1, pending.size() - index,
                            "Tài khoản đổi giữa lúc tải; metadata được giữ để thử lại sau."));
                }
                CountDownLatch metadataLatch = new CountDownLatch(1);
                AtomicBoolean metadataSaved = new AtomicBoolean(false);
                AtomicReference<String> metadataError = new AtomicReference<>();
                photoRepository.saveBackupPhotoAsync(photo, new PhotoRepository.Callback<Void>() {
                    @Override public void onSuccess(Void result) { metadataSaved.set(true); metadataLatch.countDown(); }
                    @Override public void onError(String error) { metadataError.set(error); metadataLatch.countDown(); }
                });
                if (!await(metadataLatch, 45)) {
                    failures++; retryableFailure = true; lastError = "Hết thời gian lưu metadata: " + local.getDisplayName();
                } else if (metadataSaved.get()) {
                    success++;
                    runPrefs.edit().putInt(runKey, success).apply();
                    if (local.isVideo()) videoSuccess++; else imageSuccess++;
                    try { ledger.remove(uid, local.getContentUri()); } catch (RuntimeException ignored) { }
                } else {
                    failures++; retryableFailure = true;
                    lastError = metadataError.get() == null ? "Không lưu được metadata." : metadataError.get();
                }
                setProgress(index + 1, pending.size(), success, failures, imageSuccess, videoSuccess);
            }
        } finally {
            ledger.close();
        }

        if (!prefs.isBackupEnabled() || prefs.isPaused()) {
            BackupWorkScheduler.cancelPeriodic(context, uid);
            runPrefs.edit().remove(runKey).apply();
            if (success > 0) prefs.setLastSyncTime(System.currentTimeMillis());
            return Result.success(output(success, failures, Math.max(0, pending.size() - success), "Backup đã tạm dừng."));
        }
        if (success > 0) prefs.setLastSyncTime(System.currentTimeMillis());
        if (retryableFailure) return Result.retry();

        AtomicReference<PhotoBackupManager.BackupStats> finalStats = new AtomicReference<>();
        CountDownLatch finalScanLatch = new CountDownLatch(1);
        new PhotoBackupManager().processBackupState(context, uid, new PhotoBackupManager.ManagerCallback() {
            @Override public void onStatsReady(PhotoBackupManager.BackupStats stats, List<LocalMediaPhoto> all) {
                finalStats.set(stats); finalScanLatch.countDown();
            }
            @Override public void onError(String error) { finalScanLatch.countDown(); }
        });
        int remaining = Math.max(0, pending.size() - success - failures);
        if (await(finalScanLatch, 45) && finalStats.get() != null) remaining = finalStats.get().remainingCount;
        failures = remaining;
        Data resultData = output(success, failures, remaining, lastError,
                (initialStats == null ? 0 : initialStats.backedUpImages) + imageSuccess,
                (initialStats == null ? 0 : initialStats.backedUpVideos) + videoSuccess);
        runPrefs.edit().remove(runKey).apply();
        return Result.success(resultData);
    }

    private boolean await(CountDownLatch latch, long timeoutSeconds) {
        try { return latch.await(timeoutSeconds, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
    }

    private String computeContentHash(Context context, Uri uri) {
        try (java.io.InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) return null;
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) result.append(String.format(java.util.Locale.US, "%02x", value & 0xff));
            return result.toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String stableStoragePath(String uid, LocalMediaPhoto local) {
        String identity = local.getContentUri() + "|" + local.getDateModified() + "|" + (local.isVideo() ? "video" : "image");
        String hash;
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte value : digest) out.append(String.format(java.util.Locale.US, "%02x", value & 0xff));
            hash = out.toString();
        } catch (Exception e) {
            hash = java.util.UUID.nameUUIDFromBytes(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString().replace("-", "");
        }
        String mime = resolveMimeType(local);
        String extension = "bin";
        if (mime != null) {
            if (mime.equalsIgnoreCase("image/jpeg")) extension = "jpg";
            else if (mime.equalsIgnoreCase("image/png")) extension = "png";
            else if (mime.equalsIgnoreCase("image/webp")) extension = "webp";
            else if (mime.equalsIgnoreCase("image/gif")) extension = "gif";
            else if (mime.equalsIgnoreCase("video/mp4")) extension = "mp4";
            else if (mime.equalsIgnoreCase("video/quicktime")) extension = "mov";
            else if (mime.equalsIgnoreCase("video/webm")) extension = "webm";
            else if (mime.equalsIgnoreCase("video/3gpp")) extension = "3gp";
        }
        if (extension.equals("bin") && local.getDisplayName() != null) {
            int dot = local.getDisplayName().lastIndexOf('.');
            if (dot >= 0 && dot + 1 < local.getDisplayName().length()) {
                String candidate = local.getDisplayName().substring(dot + 1).toLowerCase(java.util.Locale.US);
                if (candidate.matches("[a-z0-9]{1,8}")) extension = candidate;
            }
        }
        return uid + "/" + (local.isVideo() ? "videos/" : "photos/") + hash + "." + extension;
    }

    private String resolveMimeType(LocalMediaPhoto local) {
        String mime = local.getMimeType();
        if (mime != null && mime.contains("/")) return mime;
        String name = local.getDisplayName();
        if (name != null) {
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && dot + 1 < name.length()) {
                String inferred = android.webkit.MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(java.util.Locale.US));
                if (inferred != null) return inferred;
            }
        }
        return "application/octet-stream";
    }

    private boolean hasImageReadPermission(Context context) {
        if (Build.VERSION.SDK_INT >= 34) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
                    || ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED;
        }
        String permission = Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasMediaReadPermission(Context context, boolean video) {
        if (Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED) return true;
        if (Build.VERSION.SDK_INT >= 33) {
            String permission = video ? Manifest.permission.READ_MEDIA_VIDEO : Manifest.permission.READ_MEDIA_IMAGES;
            return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasAnyMediaReadPermission(Context context) {
        if (Build.VERSION.SDK_INT >= 34
                && ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED) return true;
        if (Build.VERSION.SDK_INT >= 33) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
                    || ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void setProgress(int completed, int total, int success, int failures, int images, int videos) {
        setProgressAsync(new Data.Builder().putInt("completed", completed).putInt("total", total)
                .putInt("success", success).putInt("failures", failures).putInt("uploading", 0)
                .putInt("uploaded_images", images).putInt("uploaded_videos", videos).build());
    }

    private void setUploadingProgress(int completed, int total, int success, int failures, LocalMediaPhoto media,
                                     int images, int videos) {
        setProgressAsync(new Data.Builder().putInt("completed", completed).putInt("total", total)
                .putInt("success", success).putInt("failures", failures).putInt("uploading", 1)
                .putInt("uploaded_images", images).putInt("uploaded_videos", videos)
                .putString("current_name", media.getDisplayName()).putString("current_type", media.isVideo() ? "video" : "image").build());
    }

    private Data output(int success, int failures, int remaining, String error) {
        return new Data.Builder().putInt("success", success).putInt("failures", failures)
                .putInt("remaining", remaining).putString("error", error).build();
    }

    private Data output(int success, int failures, int remaining, String error, int images, int videos) {
        return new Data.Builder().putInt("success", success).putInt("failures", failures)
                .putInt("remaining", remaining).putString("error", error)
                .putInt("uploaded_images", images).putInt("uploaded_videos", videos).build();
    }
}
