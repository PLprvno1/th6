package com.example.th6.activities;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AppCompatActivity;

import com.example.th6.MainActivity;
import com.example.th6.R;
import com.example.th6.utils.PreferencesManager;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

public class SplashActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Apply theme preference before layout creation
        PreferencesManager prefs = new PreferencesManager(this);
        PreferencesManager.applyTheme(prefs.isDarkMode());

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
            if (currentUser != null) {
                Intent intent = new Intent(SplashActivity.this, MainActivity.class);
                startActivity(intent);
            } else {
                Intent intent = new Intent(SplashActivity.this, LoginActivity.class);
                startActivity(intent);
            }
            finish();
        }, 1200);
    }
}
