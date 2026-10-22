package com.example.th6;

import android.os.Bundle;
import android.content.Intent;
import android.text.TextUtils;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.example.th6.data.AuthRepository;
import com.example.th6.databinding.ActivityMainBinding;
import com.example.th6.fragments.AlbumFragment;
import com.example.th6.fragments.FavoritesFragment;
import com.example.th6.fragments.LibraryFragment;
import com.example.th6.fragments.ProfileFragment;
import com.example.th6.fragments.TrashFragment;
import com.example.th6.activities.LoginActivity;
import com.example.th6.services.BackupWorkScheduler;
import com.example.th6.services.MediaStoreBackupObserver;
import com.example.th6.utils.BackupPreferences;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private boolean sessionVerified;
    private boolean activityStarted;
    private String verifiedUserId;
    private MediaStoreBackupObserver mediaStoreBackupObserver;

    @Override
    protected void onStart() {
        super.onStart();
        activityStarted = true;
        startMediaStoreObserverIfEligible();
    }

    @Override
    protected void onStop() {
        activityStarted = false;
        stopMediaStoreObserver();
        super.onStop();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        AuthRepository authRepository = new AuthRepository();
        binding.bottomNav.setEnabled(false);

        binding.bottomNav.setOnItemSelectedListener(item -> {
            if (!sessionVerified) return false;
            int itemId = item.getItemId();
            if (itemId == R.id.nav_library) {
                loadFragment(new LibraryFragment());
                return true;
            } else if (itemId == R.id.nav_albums) {
                loadFragment(new AlbumFragment());
                return true;
            } else if (itemId == R.id.nav_trash) {
                loadFragment(new TrashFragment());
                return true;
            } else if (itemId == R.id.nav_favorites) {
                loadFragment(new FavoritesFragment());
                return true;
            } else if (itemId == R.id.nav_profile) {
                loadFragment(new ProfileFragment());
                return true;
            }
            return false;
        });

        authRepository.checkIsLoggedInAsync(new AuthRepository.SessionCallback() {
            @Override
            public void onResult(boolean isLoggedIn, String userId, String errorMessage) {
                if (isFinishing() || isDestroyed()) return;
                if (!isLoggedIn || TextUtils.isEmpty(userId)) {
                    Intent intent = new Intent(MainActivity.this, LoginActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    if (!TextUtils.isEmpty(errorMessage)) intent.putExtra("auth_session_error", errorMessage);
                    startActivity(intent);
                    finish();
                    return;
                }

                sessionVerified = true;
                verifiedUserId = userId;
                binding.bottomNav.setEnabled(true);
                BackupWorkScheduler.reconcile(MainActivity.this, userId, true);
                startMediaStoreObserverIfEligible();
                if (getSupportFragmentManager().findFragmentById(R.id.fragmentContainer) == null) {
                    loadFragment(new LibraryFragment());
                }
            }
        });
    }

    public void selectTab(int navItemId) {
        if (binding != null && binding.bottomNav != null) {
            binding.bottomNav.setSelectedItemId(navItemId);
        }
    }

    private void loadFragment(Fragment fragment) {
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragmentContainer, fragment)
                .commit();
    }

    private void startMediaStoreObserverIfEligible() {
        if (!activityStarted || !sessionVerified || verifiedUserId == null || verifiedUserId.isEmpty()) return;
        BackupPreferences preferences = new BackupPreferences(this, verifiedUserId);
        if (!preferences.isBackupEnabled() || preferences.isPaused()) return;
        if (mediaStoreBackupObserver != null) return;
        mediaStoreBackupObserver = new MediaStoreBackupObserver(getApplicationContext(), verifiedUserId);
        mediaStoreBackupObserver.register();
    }

    private void stopMediaStoreObserver() {
        if (mediaStoreBackupObserver != null) {
            mediaStoreBackupObserver.unregister();
            mediaStoreBackupObserver = null;
        }
    }

    @Override
    protected void onDestroy() {
        stopMediaStoreObserver();
        super.onDestroy();
    }
}
