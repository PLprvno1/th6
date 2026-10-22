package com.example.th6.activities;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.th6.databinding.ActivityForgotPasswordBinding;
import com.google.firebase.auth.FirebaseAuth;

public class ForgotPasswordActivity extends AppCompatActivity {

    private ActivityForgotPasswordBinding binding;
    private FirebaseAuth mAuth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityForgotPasswordBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        mAuth = FirebaseAuth.getInstance();

        binding.btnSendReset.setOnClickListener(v -> sendResetEmail());
        binding.tvBackToLogin.setOnClickListener(v -> finish());
    }

    private void sendResetEmail() {
        String email = binding.etEmail.getText() != null ? binding.etEmail.getText().toString().trim() : "";

        if (TextUtils.isEmpty(email)) {
            binding.tilEmail.setError("Vui lòng nhập Email");
            return;
        } else {
            binding.tilEmail.setError(null);
        }

        setLoading(true);

        mAuth.sendPasswordResetEmail(email)
                .addOnCompleteListener(task -> {
                    setLoading(false);
                    if (task.isSuccessful()) {
                        Toast.makeText(ForgotPasswordActivity.this, "Đã gửi email hướng dẫn đặt lại mật khẩu. Vui lòng kiểm tra hộp thư!", Toast.LENGTH_LONG).show();
                        finish();
                    } else {
                        String errMsg = task.getException() != null ? task.getException().getMessage() : "Gửi thất bại";
                        Toast.makeText(ForgotPasswordActivity.this, "Lỗi: " + errMsg, Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void setLoading(boolean isLoading) {
        binding.progressBar.setVisibility(isLoading ? View.VISIBLE : View.GONE);
        binding.btnSendReset.setEnabled(!isLoading);
    }
}
