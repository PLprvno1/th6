package com.example.th6.fragments;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.bumptech.glide.Glide;
import com.example.th6.R;
import com.example.th6.activities.LoginActivity;
import com.example.th6.activities.SettingsActivity;
import com.example.th6.data.FirestoreRepository;
import com.example.th6.databinding.FragmentProfileBinding;
import com.example.th6.models.Album;
import com.example.th6.models.Photo;
import com.example.th6.models.UserProfile;
import com.example.th6.services.ImgBBUploadService;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.UserProfileChangeRequest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ProfileFragment extends Fragment {

    private FragmentProfileBinding binding;
    private FirebaseAuth mAuth;
    private FirestoreRepository repository;
    private ImgBBUploadService uploadService;

    private ActivityResultLauncher<String> avatarPickerLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mAuth = FirebaseAuth.getInstance();
        repository = new FirestoreRepository();
        uploadService = new ImgBBUploadService();

        avatarPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri != null) {
                        uploadAvatarToImgBB(uri);
                    }
                }
        );
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentProfileBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        setupListeners();
        loadProfileData();
    }

    private void setupListeners() {
        binding.btnChangeAvatar.setOnClickListener(v -> avatarPickerLauncher.launch("image/*"));
        binding.btnEditName.setOnClickListener(v -> showEditNameDialog());

        binding.btnSettings.setOnClickListener(v -> {
            startActivity(new Intent(requireContext(), SettingsActivity.class));
        });

        binding.btnLogout.setOnClickListener(v -> confirmLogout());
    }

    private void loadProfileData() {
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null) return;

        if (binding != null) {
            binding.tvEmail.setText(currentUser.getEmail());
            binding.tvDisplayName.setText(currentUser.getDisplayName() != null ? currentUser.getDisplayName() : "Chưa đặt tên");
        }

        // Load profile from Firestore
        repository.getUserProfile(currentUser.getUid(), new FirestoreRepository.RepositoryCallback<UserProfile>() {
            @Override
            public void onSuccess(UserProfile profile) {
                if (binding == null || !isAdded()) return;
                if (profile != null) {
                    if (profile.getDisplayName() != null && !profile.getDisplayName().isEmpty()) {
                        binding.tvDisplayName.setText(profile.getDisplayName());
                    }
                    if (profile.getPhotoUrl() != null && !profile.getPhotoUrl().isEmpty() && getContext() != null) {
                        Glide.with(requireContext())
                                .load(profile.getPhotoUrl())
                                .placeholder(R.drawable.ic_profile)
                                .into(binding.imgAvatar);
                    }
                }
            }

            @Override
            public void onError(String error) {}
        });

        // Load stats
        repository.getPhotos(currentUser.getUid(), new FirestoreRepository.RepositoryCallback<List<Photo>>() {
            @Override
            public void onSuccess(List<Photo> photos) {
                if (binding == null || !isAdded()) return;
                int totalPhotos = 0;
                int favCount = 0;
                if (photos != null) {
                    for (Photo p : photos) {
                        if (!p.isTrashed()) {
                            totalPhotos++;
                            if (p.isFavorite()) favCount++;
                        }
                    }
                }
                binding.tvProfileStatPhotos.setText(String.valueOf(totalPhotos));
                binding.tvProfileStatFavorites.setText(String.valueOf(favCount));
            }

            @Override
            public void onError(String error) {}
        });

        repository.getAlbums(currentUser.getUid(), new FirestoreRepository.RepositoryCallback<List<Album>>() {
            @Override
            public void onSuccess(List<Album> albums) {
                if (binding == null || !isAdded()) return;
                int totalAlbums = albums != null ? albums.size() : 0;
                binding.tvProfileStatAlbums.setText(String.valueOf(totalAlbums));
            }

            @Override
            public void onError(String error) {}
        });
    }

    private void uploadAvatarToImgBB(Uri uri) {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null || getContext() == null) return;

        if (binding != null) {
            binding.progressBar.setVisibility(View.VISIBLE);
        }
        Toast.makeText(getContext(), "Đang tải ảnh đại diện lên ImgBB...", Toast.LENGTH_SHORT).show();

        uploadService.uploadImage(requireContext(), uri, new ImgBBUploadService.UploadCallback() {
            @Override
            public void onSuccess(String imageUrl) {
                // Update Firebase Auth profile
                UserProfileChangeRequest profileUpdates = new UserProfileChangeRequest.Builder()
                        .setPhotoUri(Uri.parse(imageUrl))
                        .build();
                user.updateProfile(profileUpdates);

                // Update Firestore profile
                Map<String, Object> updates = new HashMap<>();
                updates.put("photoUrl", imageUrl);

                repository.updateUserProfile(user.getUid(), updates, new FirestoreRepository.RepositoryCallback<Void>() {
                    @Override
                    public void onSuccess(Void result) {
                        if (binding != null && isAdded() && getContext() != null) {
                            binding.progressBar.setVisibility(View.GONE);
                            Toast.makeText(getContext(), "Cập nhật ảnh đại diện thành công!", Toast.LENGTH_SHORT).show();
                            Glide.with(requireContext())
                                    .load(imageUrl)
                                    .placeholder(R.drawable.ic_profile)
                                    .into(binding.imgAvatar);
                        }
                    }

                    @Override
                    public void onError(String error) {
                        if (binding != null && isAdded() && getContext() != null) {
                            binding.progressBar.setVisibility(View.GONE);
                            Toast.makeText(getContext(), "Lỗi cập nhật Firestore: " + error, Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }

            @Override
            public void onError(String error) {
                if (binding != null && isAdded() && getContext() != null) {
                    binding.progressBar.setVisibility(View.GONE);
                    Toast.makeText(getContext(), "Lỗi upload ImgBB: " + error, Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private void showEditNameDialog() {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null || getContext() == null) return;

        EditText input = new EditText(requireContext());
        if (binding != null) {
            input.setText(binding.tvDisplayName.getText().toString());
        }
        input.setPadding(32, 32, 32, 32);

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.edit_name)
                .setView(input)
                .setPositiveButton(R.string.btn_save, (dialog, which) -> {
                    String newName = input.getText().toString().trim();
                    if (!TextUtils.isEmpty(newName)) {
                        updateDisplayName(user, newName);
                    }
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    private void updateDisplayName(FirebaseUser user, String newName) {
        if (binding != null) {
            binding.progressBar.setVisibility(View.VISIBLE);
        }

        UserProfileChangeRequest profileUpdates = new UserProfileChangeRequest.Builder()
                .setDisplayName(newName)
                .build();
        user.updateProfile(profileUpdates);

        Map<String, Object> updates = new HashMap<>();
        updates.put("displayName", newName);

        repository.updateUserProfile(user.getUid(), updates, new FirestoreRepository.RepositoryCallback<Void>() {
            @Override
            public void onSuccess(Void result) {
                if (binding != null && isAdded()) {
                    binding.progressBar.setVisibility(View.GONE);
                    binding.tvDisplayName.setText(newName);
                    Toast.makeText(getContext(), "Cập nhật họ tên thành công!", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(String error) {
                if (binding != null && isAdded()) {
                    binding.progressBar.setVisibility(View.GONE);
                    Toast.makeText(getContext(), "Lỗi: " + error, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void confirmLogout() {
        if (getContext() == null) return;
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.menu_logout)
                .setMessage(R.string.confirm_logout)
                .setPositiveButton(R.string.btn_ok, (dialog, which) -> {
                    mAuth.signOut();
                    Intent intent = new Intent(requireContext(), LoginActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    if (getActivity() != null) {
                        getActivity().finish();
                    }
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    @Override
    public void onResume() {
        super.onResume();
        loadProfileData();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
