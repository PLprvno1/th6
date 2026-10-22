package com.example.th6.activities;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.th6.R;
import com.example.th6.databinding.ActivitySettingsBinding;
import com.example.th6.utils.PreferencesManager;
import com.google.firebase.auth.FirebaseAuth;

public class SettingsActivity extends AppCompatActivity {

    private ActivitySettingsBinding binding;
    private PreferencesManager prefsManager;
    private FirebaseAuth mAuth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        prefsManager = new PreferencesManager(this);
        mAuth = FirebaseAuth.getInstance();

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

        binding.btnLogout.setOnClickListener(v -> confirmLogout());
    }

    private void confirmLogout() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_logout)
                .setMessage(R.string.confirm_logout)
                .setPositiveButton(R.string.btn_ok, (dialog, which) -> {
                    mAuth.signOut();
                    Intent intent = new Intent(SettingsActivity.this, LoginActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    finish();
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }
}
