package com.example.th6.activities;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import androidx.appcompat.app.AppCompatActivity;

import com.example.th6.MainActivity;
import com.example.th6.R;
import com.example.th6.data.AuthRepository;
import com.example.th6.utils.PreferencesManager;
import com.example.th6.utils.VideoSaveUtils;

public class SplashActivity extends AppCompatActivity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable sessionCheck;
    private boolean navigated;
    private String interruptedVideoRecoveryError;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        PreferencesManager prefs = new PreferencesManager(this);
        PreferencesManager.applyTheme(prefs.isDarkMode());

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        AuthRepository authRepository = new AuthRepository();
        sessionCheck = () -> {
            if (isFinishing() || isDestroyed() || navigated) return;
            authRepository.checkIsLoggedInAsync(new AuthRepository.SessionCallback() {
                @Override
                public void onResult(boolean isLoggedIn, String userId, String errorMessage) {
                    if (isFinishing() || isDestroyed() || navigated) return;
                    Intent intent;
                    if (isLoggedIn) {
                        intent = new Intent(SplashActivity.this, MainActivity.class);
                    } else {
                        intent = new Intent(SplashActivity.this, LoginActivity.class);
                    }
                    if (!TextUtils.isEmpty(errorMessage)) {
                        intent.putExtra("auth_session_error", errorMessage);
                    }
                    navigated = true;
                    startActivity(intent);
                    if (interruptedVideoRecoveryError != null) {
                        android.widget.Toast.makeText(SplashActivity.this, interruptedVideoRecoveryError,
                                android.widget.Toast.LENGTH_LONG).show();
                    }
                    finish();
                }
            });
        };
        new Thread(() -> {
            interruptedVideoRecoveryError = VideoSaveUtils.recoverInterruptedOverwrite(getApplicationContext());
            handler.postDelayed(sessionCheck, 1200);
        }, "video-overwrite-recovery").start();
    }

    @Override
    protected void onDestroy() {
        if (sessionCheck != null) handler.removeCallbacks(sessionCheck);
        super.onDestroy();
    }
}
