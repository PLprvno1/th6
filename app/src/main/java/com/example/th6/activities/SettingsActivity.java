package com.example.th6.activities;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.th6.R;
import com.example.th6.activities.SmartCleanupActivity;
import com.example.th6.data.AuthRepository;
import com.example.th6.databinding.ActivitySettingsBinding;
import com.example.th6.utils.PreferencesManager;

public class SettingsActivity extends AppCompatActivity {

    private ActivitySettingsBinding binding;
    private PreferencesManager prefsManager;
    private AuthRepository authRepository;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        prefsManager = new PreferencesManager(this);
        authRepository = new AuthRepository();

        setupUI();
        setupListeners();
    }

    private void setupUI() {
        binding.switchDarkMode.setChecked(prefsManager.isDarkMode());
    }

    private void setupListeners() {
        binding.btnBack.setOnClickListener(v -> finish());

        binding.switchDarkMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefsManager.setDarkMode(isChecked);
        });

        binding.cardBackupCenter.setOnClickListener(v -> {
            startActivity(new Intent(SettingsActivity.this, BackupCenterActivity.class));
        });

        binding.cardSmartCleanup.setOnClickListener(v ->
                startActivity(new Intent(SettingsActivity.this, SmartCleanupActivity.class)));

        binding.btnLogout.setOnClickListener(v -> confirmLogout());
    }

    private void confirmLogout() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_logout)
                .setMessage(R.string.confirm_logout)
                .setPositiveButton(R.string.btn_ok, (dialog, which) -> {
                    authRepository.signOutAsync(result -> {
                        if (isFinishing() || isDestroyed()) return;
                        if (!result.getLocalSessionCleared()) {
                            android.widget.Toast.makeText(this, "Không thể xóa phiên đăng nhập trên thiết bị. Thử lại.", android.widget.Toast.LENGTH_LONG).show();
                            return;
                        }
                        if (!result.getServerSignOutConfirmed()) {
                            android.widget.Toast.makeText(this, "Đã đăng xuất khỏi thiết bị. Máy chủ chưa xác nhận thu hồi phiên.", android.widget.Toast.LENGTH_LONG).show();
                        }
                        Intent intent = new Intent(this, LoginActivity.class);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(intent);
                        finish();
                    });
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }
}
