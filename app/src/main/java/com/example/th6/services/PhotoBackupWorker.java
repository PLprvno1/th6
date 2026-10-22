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

import com.example.th6.data.FirestoreRepository;
import com.example.th6.models.LocalMediaPhoto;
import com.example.th6.models.Photo;
import com.example.th6.utils.BackupPreferences;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

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
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            if (requestedUid != null) BackupWorkScheduler.cancelPeriodic(context, requestedUid);
            return Result.success(output(0, 0, 0, "Bạn cần đăng nhập để backup."));
        }
        String uid = user.getUid();
        if (requestedUid != null && !requestedUid.equals(uid)) {
            BackupWorkScheduler.cancelPeriodic(context, requestedUid);
            return Result.success(output(0, 0, 0, "Tài khoản đã thay đổi; bỏ qua lượt backup cũ."));
        }
        BackupPreferences prefs = new BackupPreferences(context, uid);
        if (!prefs.isBackupEnabled() || prefs.isPaused()) {
            BackupWorkScheduler.cancelPeriodic(context, uid);
            return Result.success(output(0, 0, 0, "Backup đang tắt hoặc tạm dừng."));
        }
        if (!hasImageReadPermission(context)) {
            return Result.success(output(0, 0, 0, "Chưa có quyền đọc ảnh; backup sẽ thử lại sau khi được cấp quyền."));
        }

        AtomicReference<List<LocalMediaPhoto>> pendingRef = new AtomicReference<>();
        AtomicReference<String> scanError = new AtomicReference<>();
        CountDownLatch scanLatch = new CountDownLatch(1);
        new PhotoBackupManager().processBackupState(context, uid, new PhotoBackupManager.ManagerCallback() {
            @Override public void onStatsReady(PhotoBackupManager.BackupStats stats, List<LocalMediaPhoto> all) {
                pendingRef.set(stats.pendingPhotos);
                scanLatch.countDown();
            }
            @Override public void onError(String error) { scanError.set(error); scanLatch.countDown(); }
        });
        if (!await(scanLatch, 45)) return Result.retry();
        if (scanError.get() != null) return Result.retry();
        List<LocalMediaPhoto> pending = pendingRef.get();
        if (!prefs.isBackupEnabled() || prefs.isPaused()) {
            BackupWorkScheduler.cancelPeriodic(context, uid);
            return Result.success(output(0, 0, pending == null ? 0 : pending.size(), "Backup đang tắt hoặc tạm dừng."));
        }
        if (pending == null || pending.isEmpty()) {
            prefs.setLastSyncTime(System.currentTimeMillis());
            return Result.success(output(0, 0, 0, "Không có ảnh cần backup."));
        }

        ImgBBUploadService uploader = new ImgBBUploadService();
        FirestoreRepository repository = new FirestoreRepository();
        BackupUploadLedger ledger = new BackupUploadLedger(context);
        String runKey = "run_" + uid + "_" + getId();
        android.content.SharedPreferences runPrefs = context.getSharedPreferences("photovault_backup_run_counts", Context.MODE_PRIVATE);
        int success = runPrefs.getInt(runKey, 0), failures = 0;
        boolean retryableFailure = false;
        String lastError = "";

        try {
            for (int index = 0; index < pending.size(); index++) {
                if (isStopped() || Thread.currentThread().isInterrupted()) return Result.retry();
                if (!prefs.isBackupEnabled() || prefs.isPaused()) {
                    BackupWorkScheduler.cancelPeriodic(context, uid);
                    runPrefs.edit().remove(runKey).apply();
                    return Result.success(output(success, failures, pending.size() - index, "Backup đã tạm dừng."));
                }
                if (!hasImageReadPermission(context)) {
                    if (success > 0) prefs.setLastSyncTime(System.currentTimeMillis());
                    runPrefs.edit().remove(runKey).apply();
                    return Result.success(output(success, failures, pending.size() - index,
                            "Quyền đọc ảnh đã bị thu hồi; các ảnh còn lại được giữ để thử lại sau."));
                }
                if (SystemClock.elapsedRealtime() - runStartedAt >= MAX_RUN_MILLIS) return Result.retry();
                LocalMediaPhoto local = pending.get(index);
                if (local.isVideo()) continue;
                boolean hasUploadedImage = LocalMediaPhoto.STATUS_METADATA_PENDING.equals(local.getBackupStatus())
                        && local.getCloudUrl() != null && !local.getCloudUrl().isEmpty();
                String url;
                if (hasUploadedImage) {
                    url = local.getCloudUrl();
                } else {
                    AtomicReference<String> uploadedUrl = new AtomicReference<>();
                    AtomicReference<String> uploadError = new AtomicReference<>();
                    AtomicBoolean uploadRetryable = new AtomicBoolean(false);
                    CountDownLatch uploadLatch = new CountDownLatch(1);
                    uploader.uploadImageWithRetry(context, Uri.parse(local.getContentUri()), new ImgBBUploadService.DetailedUploadCallback() {
                        @Override public void onSuccess(String imageUrl) {
                            // Persist immediately on callback so a Worker stop between the
                            // network response and metadata write will resume without reupload.
                            try (BackupUploadLedger callbackLedger = new BackupUploadLedger(context)) {
                                callbackLedger.recordUpload(uid, local.getContentUri(), local.getDateModified(), imageUrl);
                            } catch (RuntimeException ignored) { }
                            uploadedUrl.set(imageUrl);
                            uploadLatch.countDown();
                        }
                        @Override public void onError(String error, boolean retryable) {
                            uploadError.set(error); uploadRetryable.set(retryable); uploadLatch.countDown();
                        }
                    });
                    if (!await(uploadLatch, CALLBACK_TIMEOUT_SECONDS)) {
                        failures++; retryableFailure = true; lastError = "Hết thời gian upload: " + local.getDisplayName();
                        setProgress(index + 1, pending.size(), success, failures);
                        continue;
                    }
                    url = uploadedUrl.get();
                    if (url == null) {
                        failures++;
                        retryableFailure |= uploadRetryable.get();
                        lastError = uploadError.get() == null ? "ImgBB không trả về URL ảnh." : uploadError.get();
                        setProgress(index + 1, pending.size(), success, failures);
                        continue;
                    }
                    try {
                        ledger.recordUpload(uid, local.getContentUri(), local.getDateModified(), url);
                    } catch (RuntimeException e) {
                        // Do not discard a successful remote upload; the deterministic Firestore write remains safe to retry.
                    }
                }

                Photo photo = new Photo(null, uid,
                        local.getDisplayName() != null ? local.getDisplayName() : "Photo_" + local.getMediaId(),
                        url, System.currentTimeMillis());
                photo.setLocalMediaId(local.getMediaId());
                photo.setLocalMediaUri(local.getContentUri());
                photo.setLocalDateModified(local.getDateModified());
                photo.setContentHash(computeContentHash(context, Uri.parse(local.getContentUri())));
                CountDownLatch metadataLatch = new CountDownLatch(1);
                AtomicBoolean metadataSaved = new AtomicBoolean(false);
                AtomicReference<String> metadataError = new AtomicReference<>();
                repository.saveBackupPhoto(photo, new FirestoreRepository.RepositoryCallback<Void>() {
                    @Override public void onSuccess(Void result) { metadataSaved.set(true); metadataLatch.countDown(); }
                    @Override public void onError(String error) { metadataError.set(error); metadataLatch.countDown(); }
                });
                if (!await(metadataLatch, 45)) {
                    failures++; retryableFailure = true; lastError = "Hết thời gian lưu metadata: " + local.getDisplayName();
                } else if (metadataSaved.get()) {
                    success++;
                    runPrefs.edit().putInt(runKey, success).apply();
                    try { ledger.remove(uid, local.getContentUri()); } catch (RuntimeException ignored) { }
                } else {
                    failures++; retryableFailure = true;
                    lastError = metadataError.get() == null ? "Không lưu được metadata." : metadataError.get();
                }
                setProgress(index + 1, pending.size(), success, failures);
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
        Data resultData = output(success, failures, remaining, lastError);
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

    private boolean hasImageReadPermission(Context context) {
        if (Build.VERSION.SDK_INT >= 34) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
                    || ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED;
        }
        String permission = Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void setProgress(int completed, int total, int success, int failures) {
        setProgressAsync(new Data.Builder().putInt("completed", completed).putInt("total", total)
                .putInt("success", success).putInt("failures", failures).build());
    }

    private Data output(int success, int failures, int remaining, String error) {
        return new Data.Builder().putInt("success", success).putInt("failures", failures)
                .putInt("remaining", remaining).putString("error", error).build();
    }
}
