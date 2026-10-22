package com.example.th6.activities;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.work.Data;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.example.th6.data.AuthRepository;
import com.example.th6.data.StorageRepository;
import com.example.th6.R;
import com.example.th6.databinding.ActivityBackupCenterBinding;
import com.example.th6.models.LocalMediaPhoto;
import com.example.th6.services.BackupWorkScheduler;
import com.example.th6.services.PhotoBackupManager;
import com.example.th6.utils.BackupPreferences;
import com.example.th6.utils.CloudStorageUsage;
import com.example.th6.utils.DateFormatter;
import io.github.jan.supabase.auth.user.UserInfo;

import java.util.List;
import java.text.DateFormat;
import java.util.Date;

public class BackupCenterActivity extends AppCompatActivity {
    private ActivityBackupCenterBinding binding;
    private AuthRepository authRepository;
    private BackupPreferences prefs;
    private final PhotoBackupManager backupManager = new PhotoBackupManager();
    private final StorageRepository storageRepository = new StorageRepository();
    private ActivityResultLauncher<String[]> requestPermissionLauncher;
    private boolean scanAfterPermission;
    private PhotoBackupManager.BackupStats latestStats;
    private boolean periodicWorkWasRunning;
    private int storageUsageGeneration;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityBackupCenterBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        authRepository = new AuthRepository();
        UserInfo user = authRepository.getCurrentUser();
        if (user == null) {
            Toast.makeText(this, "Chưa đăng nhập", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        prefs = new BackupPreferences(this, user.getId());
        requestPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), grants -> {
            boolean granted = hasMediaPermission();
            if (granted) {
                scanQueueAndEnqueue(false);
                UserInfo currentUser = authRepository.getCurrentUser();
                if (currentUser != null && (scanAfterPermission || prefs.isBackupEnabled())) {
                    BackupWorkScheduler.reconcile(this, currentUser.getId(), true);
                }
            }
            else Toast.makeText(this, "Cần quyền truy cập ảnh để quét thiết bị", Toast.LENGTH_LONG).show();
            scanAfterPermission = false;
        });
        setupUI();
        setupListeners();
        observeWork();
        if (prefs.isBackupEnabled()) BackupWorkScheduler.reconcile(this, user.getId(), true);
        checkPermissionAndLoad();
    }

    private void checkPermissionAndLoad() {
        if (hasMediaPermission()) {
            loadBackupStats();
            return;
        }
        if (Build.VERSION.SDK_INT >= 34) {
            requestPermissionLauncher.launch(new String[]{Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED});
        } else if (Build.VERSION.SDK_INT >= 33) {
            requestPermissionLauncher.launch(new String[]{Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO});
        } else {
            requestPermissionLauncher.launch(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE});
        }
    }

    private boolean hasMediaPermission() {
        if (Build.VERSION.SDK_INT >= 34) {
            return (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED)
                    || ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void setupUI() {
        binding.switchAutoBackup.setChecked(prefs.isBackupEnabled());
        binding.switchWifiOnly.setChecked(prefs.isWifiOnly());
        if (prefs.isBackupEnabled()) binding.tvBackupStatusBadge.setText(prefs.isPaused() ? "TẠM DỪNG" : "SAO LƯU ĐANG BẬT");
        else binding.tvBackupStatusBadge.setText("SAO LƯU ĐANG TẮT");
        long lastSync = prefs.getLastSyncTime();
        binding.tvLastSyncTime.setText(lastSync > 0 ? DateFormatter.formatTimestamp(lastSync) : "Chưa sao lưu");
    }

    private void setupListeners() {
        binding.btnBack.setOnClickListener(v -> finish());
        binding.btnRefreshCloudStorage.setOnClickListener(v -> loadCloudStorageUsage());
        binding.switchAutoBackup.setOnCheckedChangeListener((button, checked) -> {
            prefs.setBackupEnabled(checked);
            setupUI();
            UserInfo currentUser = authRepository.getCurrentUser();
            if (currentUser != null) {
                if (checked) {
                    BackupWorkScheduler.reconcile(this, currentUser.getId(), false);
                    checkPermissionAndScanQueue();
                } else {
                    BackupWorkScheduler.disable(this, currentUser.getId());
                }
            }
        });
        binding.switchWifiOnly.setOnCheckedChangeListener((button, checked) -> {
            prefs.setWifiOnly(checked);
            UserInfo currentUser = authRepository.getCurrentUser();
            if (currentUser != null && prefs.isBackupEnabled()) {
                BackupWorkScheduler.reconcile(this, currentUser.getId(), true);
            }
        });
        binding.btnPauseResume.setOnClickListener(v -> {
            boolean paused = !prefs.isPaused();
            prefs.setPaused(paused);
            Toast.makeText(this, paused ? "Đã tạm dừng sao lưu" : "Tiếp tục sao lưu", Toast.LENGTH_SHORT).show();
            setupUI();
            UserInfo currentUser = authRepository.getCurrentUser();
            if (currentUser != null) {
                if (paused) BackupWorkScheduler.disable(this, currentUser.getId());
                else if (prefs.isBackupEnabled()) BackupWorkScheduler.reconcile(this, currentUser.getId(), true);
            }
        });
        binding.btnScanAndBackup.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Quét và sao lưu ảnh")
                .setMessage("Ứng dụng sẽ quét ảnh trên thiết bị và sao lưu các ảnh chưa đồng bộ lên Supabase Cloud. Bạn có muốn tiếp tục?")
                .setPositiveButton("Bắt đầu quét", (dialog, which) -> startBackup())
                .setNegativeButton("Hủy", null).show());
        binding.btnBackupNow.setOnClickListener(v -> startBackup());
    }

    private void startBackup() {
        if (!binding.switchAutoBackup.isChecked()) binding.switchAutoBackup.setChecked(true);
        else checkPermissionAndScanQueue();
    }

    private void checkPermissionAndScanQueue() {
        if (!hasMediaPermission()) {
            scanAfterPermission = true;
            checkPermissionAndLoad();
            return;
        }
        scanQueueAndEnqueue(true);
    }

    private void loadBackupStats() { scanQueueAndEnqueue(false); }

    private void scanQueueAndEnqueue(boolean startBackup) {
        UserInfo user = authRepository.getCurrentUser();
        if (user == null) return;
        binding.progressBar.setVisibility(View.VISIBLE);
        backupManager.processBackupState(this, user.getId(), new PhotoBackupManager.ManagerCallback() {
            @Override public void onStatsReady(PhotoBackupManager.BackupStats stats, List<LocalMediaPhoto> all) {
                latestStats = stats;
                binding.progressBar.setVisibility(View.GONE);
                binding.tvStatTotalLocal.setText(String.valueOf(stats.totalLocalPhotos));
                binding.tvStatBackedUp.setText(String.valueOf(stats.backedUpCount));
                binding.tvStatRemaining.setText(String.valueOf(stats.remainingCount));
                binding.tvStatUploading.setText("0");
                renderMediaTypeStats(0, 0, null);
                binding.tvBackupDetail.setText("Số media được đọc từ thiết bị hiện tại.");
                binding.pbBackupProgress.setProgress(stats.totalLocalPhotos == 0 ? 0
                        : stats.backedUpCount * 100 / stats.totalLocalPhotos);
                if (startBackup) {
                    if (stats.pendingPhotos.isEmpty()) {
                        Toast.makeText(BackupCenterActivity.this,
                                stats.totalLocalPhotos == 0 ? "Không tìm thấy media trên thiết bị." : "Tất cả media đã được sao lưu.",
                                Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(BackupCenterActivity.this, "Đang đưa " + stats.pendingPhotos.size() + " media vào hàng đợi backup.", Toast.LENGTH_SHORT).show();
                        enqueueBackup();
                    }
                }
            }
            @Override public void onError(String error) {
                binding.progressBar.setVisibility(View.GONE);
                Toast.makeText(BackupCenterActivity.this, "Lỗi quét: " + error, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void enqueueBackup() {
        if (!prefs.isBackupEnabled() || prefs.isPaused()) return;
        UserInfo user = authRepository.getCurrentUser();
        if (user != null) BackupWorkScheduler.enqueueIfEligible(this, user.getId());
    }

    private void observeWork() {
        UserInfo user = authRepository.getCurrentUser();
        if (user == null) return;
        WorkManager workManager = WorkManager.getInstance(this);
        workManager.getWorkInfosForUniqueWorkLiveData(BackupWorkScheduler.immediateWorkName(user.getId()))
                .observe(this, infos -> updateWorkInfo(infos, false));
        workManager.getWorkInfosForUniqueWorkLiveData(BackupWorkScheduler.periodicWorkName(user.getId()))
                .observe(this, infos -> updateWorkInfo(infos, true));
    }

    private void updateWorkInfo(List<WorkInfo> infos, boolean periodic) {
        if (infos == null || infos.isEmpty() || binding == null) return;
        WorkInfo info = infos.get(0);
        if (info.getState() == WorkInfo.State.RUNNING) {
            if (periodic) periodicWorkWasRunning = true;
            binding.progressBar.setVisibility(View.VISIBLE);
            Data progress = info.getProgress();
            int uploading = progress.getInt("uploading", 0);
            int failures = progress.getInt("failures", 0);
            int uploadedImages = progress.getInt("uploaded_images", 0);
            int uploadedVideos = progress.getInt("uploaded_videos", 0);
            String uploadingType = uploading > 0 ? progress.getString("current_type") : null;
            renderMediaTypeStats(uploadedImages, uploadedVideos, uploadingType);
            binding.tvStatUploading.setText(String.valueOf(uploading));
            String current = progress.getString("current_name");
            binding.tvBackupDetail.setText((current == null ? "Đang xử lý media" : "Đang tải: " + current)
                    + " • Lỗi trong lượt này: " + failures);
        } else if (info.getState().isFinished()) {
            loadCloudStorageUsage();
            binding.progressBar.setVisibility(View.GONE);
            binding.tvStatUploading.setText("0");
            if (info.getState() == WorkInfo.State.SUCCEEDED) {
                Data data = info.getOutputData();
                renderMediaTypeStats(data.getInt("uploaded_images", 0), data.getInt("uploaded_videos", 0), null);
                int success = data.getInt("success", 0), failures = data.getInt("failures", 0);
                if (success > 0 || failures > 0) {
                    String error = data.getString("error");
                    String message = "Hoàn tất: " + success + " media thành công, " + failures + " media lỗi.";
                    if (failures > 0 && error != null && !error.isEmpty()) message += " " + error;
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                }
                binding.tvBackupDetail.setText(failures > 0
                        ? "Hoàn tất một phần; còn " + failures + " media cần kiểm tra hoặc thử lại."
                        : "Lượt backup đã kết thúc.");
                setupUI();
                loadBackupStats();
            } else if (info.getState() == WorkInfo.State.FAILED) {
                binding.tvBackupDetail.setText("Backup thất bại. Kiểm tra mạng, quyền media và phiên đăng nhập rồi thử lại.");
            }
        } else if (periodic && periodicWorkWasRunning && info.getState() == WorkInfo.State.ENQUEUED) {
            periodicWorkWasRunning = false;
            binding.progressBar.setVisibility(View.GONE);
            binding.tvStatUploading.setText("0");
            loadBackupStats();
        }
    }

    private void loadCloudStorageUsage() {
        if (binding == null) return;
        UserInfo user = authRepository.getCurrentUser();
        if (user == null) {
            clearCloudStorageUsage();
            binding.tvCloudStorageStatus.setText(R.string.cloud_storage_refresh_failed);
            binding.progressCloudStorage.setVisibility(View.GONE);
            binding.btnRefreshCloudStorage.setEnabled(true);
            binding.tvCloudStorageUpdated.setVisibility(View.GONE);
            return;
        }
        String uid = user.getId();
        int generation = ++storageUsageGeneration;
        android.content.SharedPreferences cache = getSharedPreferences("cloud_storage_usage", MODE_PRIVATE);
        String bytesKey = "bytes:" + uid;
        String updatedKey = "updated:" + uid;
        boolean hasCache = cache.contains(bytesKey);
        final boolean hasValidCache;
        if (hasCache) {
            hasValidCache = renderCloudStorageUsage(cache.getLong(bytesKey, 0L), cache.getLong(updatedKey, 0L));
            binding.tvCloudStorageStatus.setText(hasValidCache
                    ? R.string.cloud_storage_refreshing : R.string.cloud_storage_refresh_failed);
        } else {
            hasValidCache = false;
            clearCloudStorageUsage();
            binding.tvCloudStorageUpdated.setVisibility(View.GONE);
            binding.tvCloudStorageStatus.setText(R.string.cloud_storage_loading);
        }
        binding.progressCloudStorage.setVisibility(View.VISIBLE);
        binding.btnRefreshCloudStorage.setEnabled(false);
        storageRepository.getCurrentUserStorageBytesAsync(uid, new StorageRepository.UsageCallback() {
            private boolean isCurrent() {
                UserInfo active = authRepository.getCurrentUser();
                return binding != null && !isFinishing() && !isDestroyed()
                        && generation == storageUsageGeneration && active != null && uid.equals(active.getId());
            }

            @Override public void onSuccess(long bytes) {
                if (!isCurrent()) return;
                if (!renderCloudStorageUsage(bytes, 0L)) {
                    onError("Invalid Storage usage");
                    return;
                }
                long updatedAt = System.currentTimeMillis();
                cache.edit().putLong(bytesKey, bytes).putLong(updatedKey, updatedAt).apply();
                renderCloudStorageUsage(bytes, updatedAt);
                binding.tvCloudStorageStatus.setText(R.string.cloud_storage_quota_note);
                binding.progressCloudStorage.setVisibility(View.GONE);
                binding.btnRefreshCloudStorage.setEnabled(true);
            }

            @Override public void onError(String error) {
                if (!isCurrent()) return;
                binding.progressCloudStorage.setVisibility(View.GONE);
                binding.btnRefreshCloudStorage.setEnabled(true);
                binding.tvCloudStorageStatus.setText(hasValidCache
                        ? R.string.cloud_storage_refresh_failed_cached
                        : R.string.cloud_storage_refresh_failed);
            }
        });
    }

    private boolean renderCloudStorageUsage(long bytes, long updatedAt) {
        if (binding == null) return false;
        final CloudStorageUsage usage;
        try {
            usage = CloudStorageUsage.fromBytes(bytes);
        } catch (IllegalArgumentException invalidUsage) {
            clearCloudStorageUsage();
            return false;
        }
        binding.tvCloudStorageUsed.setText(getString(R.string.cloud_storage_used_total, usage.usedAmount));
        binding.tvCloudStoragePercent.setText(getString(R.string.cloud_storage_percent_used, usage.percentText));
        binding.pbCloudStorageUsage.setProgress(usage.progress);
        binding.tvCloudStorageRemaining.setText(getString(R.string.cloud_storage_remaining, usage.remainingAmount));
        if (updatedAt > 0L) {
            String date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(new Date(updatedAt));
            binding.tvCloudStorageUpdated.setText(getString(R.string.cloud_storage_updated, date));
            binding.tvCloudStorageUpdated.setVisibility(View.VISIBLE);
        } else {
            binding.tvCloudStorageUpdated.setVisibility(View.GONE);
        }
        return true;
    }

    private void clearCloudStorageUsage() {
        if (binding == null) return;
        binding.tvCloudStorageUsed.setText(R.string.cloud_storage_usage_unavailable);
        binding.tvCloudStoragePercent.setText(R.string.cloud_storage_usage_unavailable);
        binding.pbCloudStorageUsage.setProgress(0);
        binding.tvCloudStorageRemaining.setText(R.string.cloud_storage_usage_unavailable);
        binding.tvCloudStorageUpdated.setVisibility(View.GONE);
    }

    private void renderMediaTypeStats(int uploadedImagesThisRun, int uploadedVideosThisRun, String uploadingType) {
        if (latestStats == null) {
            binding.tvImageBackupStats.setText("Ảnh • Chưa quét");
            binding.tvVideoBackupStats.setText("Video • Chưa quét");
            return;
        }
        int uploadedImages = Math.min(latestStats.totalImages,
                latestStats.backedUpImages + uploadedImagesThisRun);
        int uploadedVideos = Math.min(latestStats.totalVideos,
                latestStats.backedUpVideos + uploadedVideosThisRun);
        int uploadedTotal = uploadedImages + uploadedVideos;
        int inFlight = uploadingType == null ? 0 : 1;
        binding.tvStatBackedUp.setText(String.valueOf(uploadedTotal));
        binding.tvStatRemaining.setText(String.valueOf(Math.max(0, latestStats.totalLocalPhotos - uploadedTotal - inFlight)));
        binding.pbBackupProgress.setProgress(latestStats.totalLocalPhotos == 0 ? 0
                : uploadedTotal * 100 / latestStats.totalLocalPhotos);
        int waitingImages = Math.max(0, latestStats.totalImages - uploadedImages - ("image".equals(uploadingType) ? 1 : 0));
        int waitingVideos = Math.max(0, latestStats.totalVideos - uploadedVideos - ("video".equals(uploadingType) ? 1 : 0));
        binding.tvImageBackupStats.setText("Ảnh • Tổng " + latestStats.totalImages
                + " · Đã sao lưu " + uploadedImages + " · Đang chờ "
                + waitingImages);
        binding.tvVideoBackupStats.setText("Video • Tổng " + latestStats.totalVideos
                + " · Đã sao lưu " + uploadedVideos + " · Đang chờ "
                + waitingVideos);
    }

    @Override protected void onResume() {
        super.onResume();
        loadCloudStorageUsage();
        if (prefs != null && hasMediaPermission()) {
            loadBackupStats();
            UserInfo user = authRepository.getCurrentUser();
            if (user != null && prefs.isBackupEnabled()) BackupWorkScheduler.reconcile(this, user.getId(), true);
        }
    }
}
