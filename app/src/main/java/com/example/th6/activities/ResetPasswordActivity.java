package com.example.th6.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.example.th6.MainActivity;
import com.example.th6.databinding.ActivityResetPasswordBinding;
import com.example.th6.viewmodels.PasswordRecoveryViewModel;

public class ResetPasswordActivity extends AppCompatActivity {
    private ActivityResetPasswordBinding binding;
    private PasswordRecoveryViewModel viewModel;
    private boolean recoveryReady = false;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityResetPasswordBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        viewModel = new ViewModelProvider(this).get(PasswordRecoveryViewModel.class);
        binding.btnUpdatePassword.setEnabled(false);
        binding.btnUpdatePassword.setOnClickListener(v -> updatePassword());
        handleRecoveryLink(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleRecoveryLink(intent);
    }

    private void handleRecoveryLink(Intent intent) {
        Uri uri = intent == null ? null : intent.getData();
        if (uri == null || !"com.example.th6".equals(uri.getScheme()) || !"auth-callback".equals(uri.getHost())) {
            showLinkError();
            return;
        }
        String fragment = uri.getFragment();
        if (fragment == null || fragment.isEmpty()) {
            showLinkError();
            return;
        }
        setLoading(true);
        viewModel.accept(fragment, (success, message) -> {
            setLoading(false);
            if (!success) {
                showLinkError();
                return;
            }
            recoveryReady = true;
            binding.btnUpdatePassword.setEnabled(true);
            binding.tvRecoveryStatus.setText("Liên kết hợp lệ. Nhập mật khẩu mới.");
        });
    }

    private void updatePassword() {
        if (!recoveryReady || viewModel.getRecoveryInFlight()) return;
        String password = binding.etNewPassword.getText() == null ? "" : binding.etNewPassword.getText().toString();
        String confirm = binding.etConfirmPassword.getText() == null ? "" : binding.etConfirmPassword.getText().toString();
        if (password.isEmpty() || confirm.isEmpty()) {
            binding.tilConfirmPassword.setError("Vui lòng nhập cả hai trường mật khẩu");
            return;
        }
        if (!password.equals(confirm)) {
            binding.tilConfirmPassword.setError("Hai mật khẩu không trùng nhau");
            return;
        }
        binding.tilConfirmPassword.setError(null);
        setLoading(true);
        viewModel.update(password, (success, message) -> {
            setLoading(false);
            if (success) {
                recoveryReady = false;
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                Intent login = new Intent(this, LoginActivity.class);
                login.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(login);
                finish();
            } else {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showLinkError() {
        recoveryReady = false;
        binding.btnUpdatePassword.setEnabled(false);
        binding.tvRecoveryStatus.setText("Liên kết khôi phục không hợp lệ hoặc đã hết hạn. Hãy yêu cầu liên kết mới.");
    }

    private void setLoading(boolean loading) {
        binding.progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        binding.btnUpdatePassword.setEnabled(!loading && recoveryReady);
    }
}
