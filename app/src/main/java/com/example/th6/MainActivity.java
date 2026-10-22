package com.example.th6;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.example.th6.databinding.ActivityMainBinding;
import com.example.th6.fragments.AlbumFragment;
import com.example.th6.fragments.FavoritesFragment;
import com.example.th6.fragments.LibraryFragment;
import com.example.th6.fragments.ProfileFragment;
import com.example.th6.fragments.TrashFragment;
import com.example.th6.services.BackupWorkScheduler;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user != null) BackupWorkScheduler.reconcile(this, user.getUid(), true);

        if (savedInstanceState == null) {
            loadFragment(new LibraryFragment());
        }

        binding.bottomNav.setOnItemSelectedListener(item -> {
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
}
