package com.example.th6.activities;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.th6.MainActivity;
import com.example.th6.data.FirestoreRepository;
import com.example.th6.databinding.ActivityRegisterBinding;
import com.example.th6.models.UserProfile;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.UserProfileChangeRequest;

public class RegisterActivity extends AppCompatActivity {

    private ActivityRegisterBinding binding;
    private FirebaseAuth mAuth;
    private FirestoreRepository repository;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityRegisterBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        mAuth = FirebaseAuth.getInstance();
        repository = new FirestoreRepository();

        binding.btnRegister.setOnClickListener(v -> performRegister());

        binding.tvGoToLogin.setOnClickListener(v -> finish());
    }

    private void performRegister() {
        String fullName = binding.etFullName.getText() != null ? binding.etFullName.getText().toString().trim() : "";
        String email = binding.etEmail.getText() != null ? binding.etEmail.getText().toString().trim() : "";
        String password = binding.etPassword.getText() != null ? binding.etPassword.getText().toString().trim() : "";
        String confirmPassword = binding.etConfirmPassword.getText() != null ? binding.etConfirmPassword.getText().toString().trim() : "";

        if (TextUtils.isEmpty(fullName)) {
            binding.tilFullName.setError("Vui lòng nhập họ tên");
            return;
        } else {
            binding.tilFullName.setError(null);
        }

        if (TextUtils.isEmpty(email)) {
            binding.tilEmail.setError("Vui lòng nhập email");
            return;
        } else {
            binding.tilEmail.setError(null);
        }

        if (TextUtils.isEmpty(password) || password.length() < 6) {
            binding.tilPassword.setError("Mật khẩu phải từ 6 ký tự trở lên");
            return;
        } else {
            binding.tilPassword.setError(null);
        }

        if (!password.equals(confirmPassword)) {
            binding.tilConfirmPassword.setError("Mật khẩu xác nhận không khớp");
            return;
        } else {
            binding.tilConfirmPassword.setError(null);
        }

        setLoading(true);

        mAuth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener(this, task -> {
                    if (task.isSuccessful() && mAuth.getCurrentUser() != null) {
                        FirebaseUser user = mAuth.getCurrentUser();

                        // Update displayName in Firebase Auth
                        UserProfileChangeRequest profileUpdates = new UserProfileChangeRequest.Builder()
                                .setDisplayName(fullName)
                                .build();
                        user.updateProfile(profileUpdates);

                        // Create UserProfile document in Firestore
                        UserProfile userProfile = new UserProfile(
                                user.getUid(),
                                fullName,
                                email,
                                "",
                                System.currentTimeMillis()
                        );

                        repository.createUserProfile(userProfile, new FirestoreRepository.RepositoryCallback<Void>() {
                            @Override
                            public void onSuccess(Void result) {
                                setLoading(false);
                                Toast.makeText(RegisterActivity.this, "Đăng ký tài khoản thành công!", Toast.LENGTH_SHORT).show();
                                Intent intent = new Intent(RegisterActivity.this, MainActivity.class);
                                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                startActivity(intent);
                                finish();
                            }

                            @Override
                            public void onError(String error) {
                                setLoading(false);
                                // Account created, but firestore failed -> navigate anyway
                                Toast.makeText(RegisterActivity.this, "Tài khoản đã tạo nhưng chưa lưu thông tin chi tiết: " + error, Toast.LENGTH_LONG).show();
                                Intent intent = new Intent(RegisterActivity.this, MainActivity.class);
                                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                startActivity(intent);
                                finish();
                            }
                        });

                    } else {
                        setLoading(false);
                        String errMsg = task.getException() != null ? task.getException().getMessage() : "Đăng ký thất bại";
                        Toast.makeText(RegisterActivity.this, "Lỗi đăng ký: " + errMsg, Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void setLoading(boolean isLoading) {
        binding.progressBar.setVisibility(isLoading ? View.VISIBLE : View.GONE);
        binding.btnRegister.setEnabled(!isLoading);
    }
}
