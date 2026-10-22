package com.example.th6.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.PopupMenu;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;

import com.example.th6.R;
import com.example.th6.activities.PhotoDetailActivity;
import com.example.th6.adapters.PhotoAdapter;
import com.example.th6.data.FirestoreRepository;
import com.example.th6.databinding.FragmentTrashBinding;
import com.example.th6.models.Photo;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.util.ArrayList;
import java.util.List;

public class TrashFragment extends Fragment implements PhotoAdapter.OnPhotoActionListener {

    private FragmentTrashBinding binding;
    private PhotoAdapter photoAdapter;
    private FirestoreRepository repository;
    private FirebaseAuth mAuth;
    private List<Photo> trashedPhotos = new ArrayList<>();

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mAuth = FirebaseAuth.getInstance();
        repository = new FirestoreRepository();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentTrashBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        setupRecyclerView();
        setupListeners();
        loadTrashPhotos();
    }

    private void setupRecyclerView() {
        photoAdapter = new PhotoAdapter(requireContext(), this);
        binding.rvTrashPhotos.setLayoutManager(new GridLayoutManager(requireContext(), 2));
        binding.rvTrashPhotos.setAdapter(photoAdapter);
    }

    private void setupListeners() {
        binding.swipeRefresh.setOnRefreshListener(this::loadTrashPhotos);
    }

    private void loadTrashPhotos() {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null || binding == null) return;

        binding.progressBar.setVisibility(View.VISIBLE);
        repository.getPhotos(user.getUid(), new FirestoreRepository.RepositoryCallback<List<Photo>>() {
            @Override
            public void onSuccess(List<Photo> photos) {
                if (binding == null || !isAdded()) return;
                binding.progressBar.setVisibility(View.GONE);
                binding.swipeRefresh.setRefreshing(false);

                trashedPhotos.clear();
                if (photos != null) {
                    for (Photo p : photos) {
                        if (p.isTrashed()) {
                            trashedPhotos.add(p);
                        }
                    }
                }

                photoAdapter.setPhotos(trashedPhotos);
                updateEmptyState();
            }

            @Override
            public void onError(String error) {
                if (binding == null || !isAdded()) return;
                binding.progressBar.setVisibility(View.GONE);
                binding.swipeRefresh.setRefreshing(false);
                if (getContext() != null) {
                    Toast.makeText(getContext(), "Lỗi tải thùng rác: " + error, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void updateEmptyState() {
        if (binding == null) return;
        if (trashedPhotos.isEmpty()) {
            binding.layoutEmpty.setVisibility(View.VISIBLE);
            binding.rvTrashPhotos.setVisibility(View.GONE);
        } else {
            binding.layoutEmpty.setVisibility(View.GONE);
            binding.rvTrashPhotos.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onPhotoClick(Photo photo) {
        Intent intent = PhotoDetailActivity.createIntent(requireContext(), photo, trashedPhotos);
        startActivity(intent);
    }

    @Override
    public void onPhotoLongClick(Photo photo, View view) {
        PopupMenu popupMenu = new PopupMenu(requireContext(), view);
        popupMenu.getMenu().add(0, 1, 0, R.string.ctx_restore);
        popupMenu.getMenu().add(0, 2, 1, R.string.ctx_delete_perm);

        popupMenu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                restorePhoto(photo);
                return true;
            } else if (item.getItemId() == 2) {
                confirmPermanentDelete(photo);
                return true;
            }
            return false;
        });

        popupMenu.show();
    }

    @Override
    public void onFavoriteToggle(Photo photo) {
        // Disabled in trash
    }

    private void restorePhoto(Photo photo) {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null || getContext() == null) return;

        repository.moveToTrash(user.getUid(), photo.getId(), false, new FirestoreRepository.RepositoryCallback<Void>() {
            @Override
            public void onSuccess(Void result) {
                if (binding == null || !isAdded()) return;
                if (getContext() != null) {
                    Toast.makeText(getContext(), "Đã khôi phục ảnh về thư viện!", Toast.LENGTH_SHORT).show();
                }
                loadTrashPhotos();
            }

            @Override
            public void onError(String error) {
                if (binding == null || !isAdded()) return;
                if (getContext() != null) {
                    Toast.makeText(getContext(), "Lỗi: " + error, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void confirmPermanentDelete(Photo photo) {
        if (getContext() == null) return;
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.ctx_delete_perm)
                .setMessage(R.string.confirm_delete_perm)
                .setPositiveButton("Xóa vĩnh viễn", (dialog, which) -> {
                    FirebaseUser user = mAuth.getCurrentUser();
                    if (user != null) {
                        repository.deletePhoto(user.getUid(), photo.getId(), new FirestoreRepository.RepositoryCallback<Void>() {
                            @Override
                            public void onSuccess(Void result) {
                                if (binding == null || !isAdded()) return;
                                if (getContext() != null) {
                                    Toast.makeText(getContext(), "Đã xóa metadata khỏi PhotoVault. Tệp ImgBB vẫn được giữ.", Toast.LENGTH_LONG).show();
                                }
                                loadTrashPhotos();
                            }

                            @Override
                            public void onError(String error) {
                                if (binding == null || !isAdded()) return;
                                if (getContext() != null) {
                                    Toast.makeText(getContext(), "Lỗi xóa vĩnh viễn: " + error, Toast.LENGTH_SHORT).show();
                                }
                            }
                        });
                    }
                })
                .setNegativeButton("Hủy", null)
                .show();
    }

    @Override
    public void onResume() {
        super.onResume();
        loadTrashPhotos();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
