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

import com.example.th6.databinding.ActivityBackupCenterBinding;
import com.example.th6.models.LocalMediaPhoto;
import com.example.th6.services.BackupWorkScheduler;
import com.example.th6.services.PhotoBackupManager;
import com.example.th6.utils.BackupPreferences;
import com.example.th6.utils.DateFormatter;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.util.List;

public class BackupCenterActivity extends AppCompatActivity {
    private ActivityBackupCenterBinding binding;
    private FirebaseAuth auth;
    private BackupPreferences prefs;
    private final PhotoBackupManager backupManager = new PhotoBackupManager();
    private ActivityResultLauncher<String[]> requestPermissionLauncher;
    private boolean scanAfterPermission;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityBackupCenterBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        auth = FirebaseAuth.getInstance();
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            Toast.makeText(this, "Chưa đăng nhập", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        prefs = new BackupPreferences(this, user.getUid());
        requestPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), grants -> {
            boolean granted = hasImagePermission();
            if (granted) {
                scanQueueAndEnqueue(false);
                FirebaseUser currentUser = auth.getCurrentUser();
                if (currentUser != null && (scanAfterPermission || prefs.isBackupEnabled())) {
                    BackupWorkScheduler.reconcile(this, currentUser.getUid(), true);
                }
            }
            else Toast.makeText(this, "Cần quyền truy cập ảnh để quét thiết bị", Toast.LENGTH_LONG).show();
            scanAfterPermission = false;
        });
        setupUI();
        setupListeners();
        observeWork();
        if (prefs.isBackupEnabled()) BackupWorkScheduler.reconcile(this, user.getUid(), true);
        checkPermissionAndLoad();
    }

    private void checkPermissionAndLoad() {
        if (hasImagePermission()) {
            loadBackupStats();
            return;
        }
        if (Build.VERSION.SDK_INT >= 34) {
            requestPermissionLauncher.launch(new String[]{Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED});
        } else if (Build.VERSION.SDK_INT >= 33) {
            requestPermissionLauncher.launch(new String[]{Manifest.permission.READ_MEDIA_IMAGES});
        } else {
            requestPermissionLauncher.launch(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE});
        }
    }

    private boolean hasImagePermission() {
        if (Build.VERSION.SDK_INT >= 34) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
                    || ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED;
        }
        String permission = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
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
        binding.switchAutoBackup.setOnCheckedChangeListener((button, checked) -> {
            prefs.setBackupEnabled(checked);
            setupUI();
            FirebaseUser currentUser = auth.getCurrentUser();
            if (currentUser != null) {
                if (checked) {
                    BackupWorkScheduler.reconcile(this, currentUser.getUid(), false);
                    checkPermissionAndScanQueue();
                } else {
                    BackupWorkScheduler.disable(this, currentUser.getUid());
                }
            }
        });
        binding.switchWifiOnly.setOnCheckedChangeListener((button, checked) -> {
            prefs.setWifiOnly(checked);
            FirebaseUser currentUser = auth.getCurrentUser();
            if (currentUser != null && prefs.isBackupEnabled()) {
                BackupWorkScheduler.reconcile(this, currentUser.getUid(), true);
            }
        });
        binding.btnPauseResume.setOnClickListener(v -> {
            boolean paused = !prefs.isPaused();
            prefs.setPaused(paused);
            Toast.makeText(this, paused ? "Đã tạm dừng sao lưu" : "Tiếp tục sao lưu", Toast.LENGTH_SHORT).show();
            setupUI();
            FirebaseUser currentUser = auth.getCurrentUser();
            if (currentUser != null) {
                if (paused) BackupWorkScheduler.disable(this, currentUser.getUid());
                else if (prefs.isBackupEnabled()) BackupWorkScheduler.reconcile(this, currentUser.getUid(), true);
            }
        });
        binding.btnScanAndBackup.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Quét và sao lưu ảnh")
                .setMessage("Ứng dụng sẽ quét ảnh trên thiết bị và sao lưu các ảnh chưa đồng bộ lên ImgBB Cloud. Bạn có muốn tiếp tục?")
                .setPositiveButton("Bắt đầu quét", (dialog, which) -> startBackup())
                .setNegativeButton("Hủy", null).show());
        binding.btnBackupNow.setOnClickListener(v -> startBackup());
    }

    private void startBackup() {
        if (!binding.switchAutoBackup.isChecked()) binding.switchAutoBackup.setChecked(true);
        else checkPermissionAndScanQueue();
    }

    private void checkPermissionAndScanQueue() {
        if (!hasImagePermission()) {
            scanAfterPermission = true;
            checkPermissionAndLoad();
            return;
        }
        scanQueueAndEnqueue(true);
    }

    private void loadBackupStats() { scanQueueAndEnqueue(false); }

    private void scanQueueAndEnqueue(boolean startBackup) {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) return;
        binding.progressBar.setVisibility(View.VISIBLE);
        backupManager.processBackupState(this, user.getUid(), new PhotoBackupManager.ManagerCallback() {
            @Override public void onStatsReady(PhotoBackupManager.BackupStats stats, List<LocalMediaPhoto> all) {
                binding.progressBar.setVisibility(View.GONE);
                binding.tvStatTotalLocal.setText(String.valueOf(stats.totalLocalPhotos));
                binding.tvStatBackedUp.setText(String.valueOf(stats.backedUpCount));
                binding.tvStatRemaining.setText(String.valueOf(stats.remainingCount));
                binding.pbBackupProgress.setProgress(stats.totalLocalPhotos == 0 ? 0
                        : stats.backedUpCount * 100 / stats.totalLocalPhotos);
                if (startBackup) {
                    if (stats.pendingPhotos.isEmpty()) {
                        Toast.makeText(BackupCenterActivity.this,
                                stats.totalLocalPhotos == 0 ? "Không tìm thấy ảnh trên thiết bị." : "Tất cả ảnh đã được sao lưu.",
                                Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(BackupCenterActivity.this, "Đang đưa " + stats.pendingPhotos.size() + " ảnh vào hàng đợi backup.", Toast.LENGTH_SHORT).show();
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
        FirebaseUser user = auth.getCurrentUser();
        if (user != null) BackupWorkScheduler.enqueueManual(this, user.getUid(), prefs.isWifiOnly());
    }

    private void observeWork() {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) return;
        WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData(BackupWorkScheduler.immediateWorkName(user.getUid()))
                .observe(this, infos -> {
                    if (infos == null || infos.isEmpty()) return;
                    WorkInfo info = infos.get(0);
                    if (info.getState() == WorkInfo.State.RUNNING) {
                        binding.progressBar.setVisibility(View.VISIBLE);
                        Data progress = info.getProgress();
                        int total = progress.getInt("total", 0);
                        int completed = progress.getInt("completed", 0);
                        if (total > 0) binding.tvStatRemaining.setText(String.valueOf(Math.max(0, total - completed)));
                    } else if (info.getState().isFinished()) {
                        binding.progressBar.setVisibility(View.GONE);
                        if (info.getState() == WorkInfo.State.SUCCEEDED) {
                            Data data = info.getOutputData();
                            int success = data.getInt("success", 0), failures = data.getInt("failures", 0);
                            if (success > 0 || failures > 0) {
                                String error = data.getString("error");
                                String message = "Hoàn tất: " + success + " ảnh thành công, " + failures + " ảnh lỗi.";
                                if (failures > 0 && error != null && !error.isEmpty()) message += " " + error;
                                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                            }
                            setupUI();
                            loadBackupStats();
                        }
                    }
                });
    }

    @Override protected void onResume() {
        super.onResume();
        if (prefs != null && hasImagePermission()) {
            loadBackupStats();
            FirebaseUser user = auth.getCurrentUser();
            if (user != null && prefs.isBackupEnabled()) BackupWorkScheduler.reconcile(this, user.getUid(), true);
        }
    }
}
