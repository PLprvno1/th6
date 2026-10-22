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
import com.example.th6.data.AuthRepository;
import com.example.th6.data.PhotoRepository;
import com.example.th6.data.StorageRepository;
import com.example.th6.databinding.FragmentProfileBinding;
import com.example.th6.models.Album;
import com.example.th6.models.Photo;
import com.example.th6.models.UserProfile;
import io.github.jan.supabase.auth.user.UserInfo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ProfileFragment extends Fragment {

    private FragmentProfileBinding binding;
    private AuthRepository authRepository;
    private PhotoRepository repository;
    private StorageRepository storageRepository;

    private ActivityResultLauncher<String> avatarPickerLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        authRepository = new AuthRepository();
        repository = new PhotoRepository();
        storageRepository = new StorageRepository();

        avatarPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri != null) {
                        uploadAvatarToSupabase(uri);
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
        UserInfo currentUser = authRepository.getCurrentUser();
        if (currentUser == null) {
            if (binding != null) {
                binding.tvProfileStatPhotos.setText("—");
                binding.tvProfileStatVideos.setText("—");
                binding.tvProfileStatAlbums.setText("—");
                binding.tvProfileStatFavorites.setText("—");
            }
            return;
        }

        if (binding != null) {
            binding.tvEmail.setText(currentUser.getEmail());
            binding.tvProfileStatPhotos.setText("—");
            binding.tvProfileStatVideos.setText("—");
            binding.tvProfileStatAlbums.setText("—");
            binding.tvProfileStatFavorites.setText("—");
            String metaName = currentUser.getUserMetadata() != null ? currentUser.getUserMetadata().get("display_name") != null ? currentUser.getUserMetadata().get("display_name").toString() : null : null;
            binding.tvDisplayName.setText(metaName != null ? metaName : "Thành viên");
        }

        // Load profile from Supabase
        repository.getUserProfileAsync(currentUser.getId(), new PhotoRepository.Callback<UserProfile>() {
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
                                .circleCrop()
                                .placeholder(R.drawable.ic_profile)
                                .into(binding.imgAvatar);
                    }
                }
            }

            @Override
            public void onError(String error) {}
        });

        // Load stats
        repository.getPhotosByUserAsync(currentUser.getId(), new PhotoRepository.Callback<List<Photo>>() {
            @Override
            public void onSuccess(List<Photo> photos) {
                if (binding == null || !isAdded()) return;
                int totalPhotos = 0;
                int totalVideos = 0;
                int favCount = 0;
                if (photos != null) {
                    for (Photo p : photos) {
                        if (!p.isTrashed()) {
                            if ("video".equalsIgnoreCase(p.getMediaType())) totalVideos++;
                            else totalPhotos++;
                            if (p.isFavorite()) favCount++;
                        }
                    }
                }
                binding.tvProfileStatPhotos.setText(String.valueOf(totalPhotos));
                binding.tvProfileStatVideos.setText(String.valueOf(totalVideos));
                binding.tvProfileStatFavorites.setText(String.valueOf(favCount));
            }

            @Override
            public void onError(String error) {}
        });

        repository.getAlbumsAsync(currentUser.getId(), new PhotoRepository.Callback<List<Album>>() {
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

    private void uploadAvatarToSupabase(Uri uri) {
        UserInfo user = authRepository.getCurrentUser();
        if (user == null || getContext() == null) return;

        if (binding != null) {
            binding.progressBar.setVisibility(View.VISIBLE);
        }
        Toast.makeText(getContext(), "Đang tải ảnh đại diện lên Supabase Storage...", Toast.LENGTH_SHORT).show();

        String fileName = "avatar_" + user.getId() + "_" + System.currentTimeMillis() + ".jpg";
        storageRepository.uploadImageAsync(requireContext(), uri, fileName, new StorageRepository.UploadCallback() {
            @Override
            public void onSuccess(String publicUrl) {
                Map<String, Object> updates = new HashMap<>();
                updates.put("photo_url", publicUrl);

                repository.updateUserProfileAsync(
                        user.getId(), updates, currentDisplayName(), safeUserEmail(user),
                        new PhotoRepository.Callback<Void>() {
                    @Override
                    public void onSuccess(Void result) {
                        if (binding != null && isAdded() && getContext() != null) {
                            binding.progressBar.setVisibility(View.GONE);
                            Toast.makeText(getContext(), "Cập nhật ảnh đại diện thành công!", Toast.LENGTH_SHORT).show();
                            Glide.with(requireContext())
                                    .load(publicUrl)
                                            .circleCrop()
                                    .placeholder(R.drawable.ic_profile)
                                    .into(binding.imgAvatar);
                        }
                    }

                    @Override
                    public void onError(String error) {
                        if (binding != null && isAdded() && getContext() != null) {
                            binding.progressBar.setVisibility(View.GONE);
                            Toast.makeText(getContext(), "Lỗi cập nhật hồ sơ: " + error, Toast.LENGTH_SHORT).show();
                        }
                    }
                        });
            }

            @Override
            public void onError(String error) {
                if (binding != null && isAdded() && getContext() != null) {
                    binding.progressBar.setVisibility(View.GONE);
                    Toast.makeText(getContext(), "Lỗi upload ảnh đại diện: " + error, Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private void showEditNameDialog() {
        UserInfo user = authRepository.getCurrentUser();
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

    private void updateDisplayName(UserInfo user, String newName) {
        if (binding != null) {
            binding.progressBar.setVisibility(View.VISIBLE);
        }

        Map<String, Object> updates = new HashMap<>();
        updates.put("display_name", newName);

        repository.updateUserProfileAsync(
                user.getId(), updates, newName, safeUserEmail(user),
                new PhotoRepository.Callback<Void>() {
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

    private String currentDisplayName() {
        if (binding == null || binding.tvDisplayName.getText() == null) return "Thành viên";
        String name = binding.tvDisplayName.getText().toString().trim();
        return name.isEmpty() ? "Thành viên" : name;
    }

    private String safeUserEmail(UserInfo user) {
        String email = user.getEmail();
        return email == null ? "" : email;
    }

    private void confirmLogout() {
        if (getContext() == null) return;
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.menu_logout)
                .setMessage(R.string.confirm_logout)
                .setPositiveButton(R.string.btn_ok, (dialog, which) -> {
                    authRepository.signOutAsync(result -> {
                        if (getActivity() == null) return;
                        if (!result.getLocalSessionCleared()) {
                            Toast.makeText(getContext(), "Không thể xóa phiên đăng nhập trên thiết bị. Thử lại.", Toast.LENGTH_LONG).show();
                            return;
                        }
                        if (!result.getServerSignOutConfirmed()) {
                            Toast.makeText(getContext(), "Đã đăng xuất khỏi thiết bị. Máy chủ chưa xác nhận thu hồi phiên.", Toast.LENGTH_LONG).show();
                        }
                        Intent intent = new Intent(getActivity(), LoginActivity.class);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(intent);
                        if (getActivity() != null) {
                            getActivity().finish();
                        }
                    });
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
