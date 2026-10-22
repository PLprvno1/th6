package com.example.th6.activities;

import android.os.Bundle;
import android.text.TextUtils;
import android.util.Patterns;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.example.th6.databinding.ActivityForgotPasswordBinding;
import com.example.th6.viewmodels.PasswordRecoveryViewModel;

public class ForgotPasswordActivity extends AppCompatActivity {
    private ActivityForgotPasswordBinding binding;
    private PasswordRecoveryViewModel viewModel;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityForgotPasswordBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        viewModel = new ViewModelProvider(this).get(PasswordRecoveryViewModel.class);
        setLoading(viewModel.getRequestInFlight());
        binding.btnSendReset.setOnClickListener(v -> sendResetEmail());
        binding.tvBackToLogin.setOnClickListener(v -> finish());
    }

    private void sendResetEmail() {
        String email = binding.etEmail.getText() == null ? "" : binding.etEmail.getText().toString().trim();
        if (TextUtils.isEmpty(email) || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.tilEmail.setError("Vui lòng nhập email hợp lệ");
            return;
        }
        binding.tilEmail.setError(null);
        setLoading(true);
        viewModel.send(email, (success, message) -> {
            setLoading(false);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            if (success) finish();
        });
    }

    private void setLoading(boolean loading) {
        binding.progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        binding.btnSendReset.setEnabled(!loading);
    }
}
