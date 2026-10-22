package com.example.th6.activities;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Patterns;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.example.th6.MainActivity;
import com.example.th6.databinding.ActivityLoginBinding;
import com.example.th6.viewmodels.LoginViewModel;

public class LoginActivity extends AppCompatActivity {

    private ActivityLoginBinding binding;
    private LoginViewModel viewModel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityLoginBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        viewModel = new ViewModelProvider(this).get(LoginViewModel.class);
        viewModel.getState().observe(this, state -> {
            if (state instanceof LoginViewModel.State.Loading) {
                setLoading(true);
            } else {
                setLoading(false);
            }

            if (state instanceof LoginViewModel.State.Success) {
                viewModel.consumeResult();
                Toast.makeText(this, "Đăng nhập thành công!", Toast.LENGTH_SHORT).show();
                Intent intent = new Intent(this, MainActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finish();
            } else if (state instanceof LoginViewModel.State.Error) {
                String message = ((LoginViewModel.State.Error) state).getMessage();
                viewModel.consumeResult();
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            }
        });

        String sessionError = getIntent().getStringExtra("auth_session_error");
        if (!TextUtils.isEmpty(sessionError)) {
            Toast.makeText(this, sessionError, Toast.LENGTH_LONG).show();
            getIntent().removeExtra("auth_session_error");
        }

        binding.btnLogin.setOnClickListener(v -> performLogin());

        binding.tvForgotPassword.setOnClickListener(v -> {
            startActivity(new Intent(LoginActivity.this, ForgotPasswordActivity.class));
        });

        binding.tvGoToRegister.setOnClickListener(v -> {
            startActivity(new Intent(LoginActivity.this, RegisterActivity.class));
        });
    }

    private void performLogin() {
        String email = binding.etEmail.getText() != null ? binding.etEmail.getText().toString().trim() : "";
        String password = binding.etPassword.getText() != null ? binding.etPassword.getText().toString() : "";

        if (TextUtils.isEmpty(email)) {
            binding.tilEmail.setError("Vui lòng nhập Email");
            return;
        } else {
            binding.tilEmail.setError(null);
        }

        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.tilEmail.setError("Nhập địa chỉ email hợp lệ");
            return;
        }

        if (TextUtils.isEmpty(password)) {
            binding.tilPassword.setError("Vui lòng nhập mật khẩu");
            return;
        } else {
            binding.tilPassword.setError(null);
        }

        viewModel.login(email, password);
    }

    private void setLoading(boolean isLoading) {
        binding.progressBar.setVisibility(isLoading ? View.VISIBLE : View.GONE);
        binding.btnLogin.setEnabled(!isLoading);
    }
}
