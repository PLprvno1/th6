package com.example.th6.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.th6.databinding.DialogBackupOnboardingBinding;
import com.example.th6.utils.BackupPreferences;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

public class BackupOnboardingDialog extends BottomSheetDialogFragment {

    private DialogBackupOnboardingBinding binding;
    private OnBackupDecisionListener listener;

    public interface OnBackupDecisionListener {
        void onEnableBackup(boolean isWifiOnly);
        void onSkipBackup();
    }

    public static BackupOnboardingDialog newInstance(OnBackupDecisionListener listener) {
        BackupOnboardingDialog dialog = new BackupOnboardingDialog();
        dialog.listener = listener;
        return dialog;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = DialogBackupOnboardingBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        binding.btnEnableBackup.setOnClickListener(v -> {
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user != null && getContext() != null) {
                BackupPreferences prefs = new BackupPreferences(requireContext(), user.getUid());
                boolean wifiOnly = binding.switchWifiOnly.isChecked();
                prefs.setBackupEnabled(true);
                prefs.setWifiOnly(wifiOnly);
                prefs.setOnboardingShown(true);
                if (listener != null) listener.onEnableBackup(wifiOnly);
            }
            dismiss();
        });

        binding.btnNotNow.setOnClickListener(v -> {
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user != null && getContext() != null) {
                BackupPreferences prefs = new BackupPreferences(requireContext(), user.getUid());
                prefs.setBackupEnabled(false);
                prefs.setOnboardingShown(true);
                if (listener != null) listener.onSkipBackup();
            }
            dismiss();
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
