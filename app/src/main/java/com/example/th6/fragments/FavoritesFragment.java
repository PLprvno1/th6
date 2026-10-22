package com.example.th6.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
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
import com.example.th6.databinding.FragmentFavoritesBinding;
import com.example.th6.models.Photo;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.util.ArrayList;
import java.util.List;

public class FavoritesFragment extends Fragment implements PhotoAdapter.OnPhotoActionListener {

    private FragmentFavoritesBinding binding;
    private PhotoAdapter photoAdapter;
    private FirestoreRepository repository;
    private FirebaseAuth mAuth;
    private List<Photo> favoritePhotos = new ArrayList<>();

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mAuth = FirebaseAuth.getInstance();
        repository = new FirestoreRepository();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentFavoritesBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        setupRecyclerView();
        setupListeners();
        loadFavorites();
    }

    private void setupRecyclerView() {
        photoAdapter = new PhotoAdapter(requireContext(), this);
        binding.rvFavorites.setLayoutManager(new GridLayoutManager(requireContext(), 2));
        binding.rvFavorites.setAdapter(photoAdapter);
    }

    private void setupListeners() {
        binding.swipeRefresh.setOnRefreshListener(this::loadFavorites);
    }

    private void loadFavorites() {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null || binding == null) return;

        binding.progressBar.setVisibility(View.VISIBLE);
        repository.getPhotos(user.getUid(), new FirestoreRepository.RepositoryCallback<List<Photo>>() {
            @Override
            public void onSuccess(List<Photo> photos) {
                if (binding == null || !isAdded()) return;
                binding.progressBar.setVisibility(View.GONE);
                binding.swipeRefresh.setRefreshing(false);

                favoritePhotos.clear();
                if (photos != null) {
                    for (Photo p : photos) {
                        if (p.isFavorite() && !p.isTrashed()) {
                            favoritePhotos.add(p);
                        }
                    }
                }

                photoAdapter.setPhotos(favoritePhotos);
                updateEmptyState();
            }

            @Override
            public void onError(String error) {
                if (binding == null || !isAdded()) return;
                binding.progressBar.setVisibility(View.GONE);
                binding.swipeRefresh.setRefreshing(false);
                if (getContext() != null) {
                    Toast.makeText(getContext(), "Lỗi tải ảnh yêu thích: " + error, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void updateEmptyState() {
        if (binding == null) return;
        if (favoritePhotos.isEmpty()) {
            binding.layoutEmpty.setVisibility(View.VISIBLE);
            binding.rvFavorites.setVisibility(View.GONE);
        } else {
            binding.layoutEmpty.setVisibility(View.GONE);
            binding.rvFavorites.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onPhotoClick(Photo photo) {
        Intent intent = PhotoDetailActivity.createIntent(requireContext(), photo, favoritePhotos);
        startActivity(intent);
    }

    @Override
    public void onPhotoLongClick(Photo photo, View view) {
        PopupMenu popupMenu = new PopupMenu(requireContext(), view);
        popupMenu.getMenuInflater().inflate(R.menu.context_menu_photo, popupMenu.getMenu());

        MenuItem favItem = popupMenu.getMenu().findItem(R.id.ctx_toggle_favorite);
        if (favItem != null) {
            favItem.setTitle(photo.isFavorite() ? R.string.ctx_remove_favorite : R.string.ctx_add_favorite);
        }

        popupMenu.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.ctx_view_detail) {
                onPhotoClick(photo);
                return true;
            } else if (itemId == R.id.ctx_toggle_favorite) {
                onFavoriteToggle(photo);
                return true;
            } else if (itemId == R.id.ctx_share) {
                sharePhoto(photo);
                return true;
            } else if (itemId == R.id.ctx_delete) {
                confirmDeletePhoto(photo);
                return true;
            }
            return false;
        });

        popupMenu.show();
    }

    @Override
    public void onFavoriteToggle(Photo photo) {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null || getContext() == null) return;

        boolean newFav = !photo.isFavorite();
        repository.toggleFavorite(user.getUid(), photo.getId(), newFav, new FirestoreRepository.RepositoryCallback<Void>() {
            @Override
            public void onSuccess(Void result) {
                if (binding == null || !isAdded()) return;
                if (getContext() != null) {
                    Toast.makeText(getContext(), newFav ? "Đã thêm vào yêu thích" : "Đã bỏ khỏi yêu thích", Toast.LENGTH_SHORT).show();
                }
                loadFavorites();
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

    private void sharePhoto(Photo photo) {
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, photo.getName());
        shareIntent.putExtra(Intent.EXTRA_TEXT, "Xem ảnh \"" + photo.getName() + "\" trên PhotoVault: " + photo.getImageUrl());
        startActivity(Intent.createChooser(shareIntent, "Chia sẻ ảnh qua"));
    }

    private void confirmDeletePhoto(Photo photo) {
        if (getContext() == null) return;
        new AlertDialog.Builder(requireContext())
                .setTitle("Xác nhận xóa")
                .setMessage(R.string.confirm_delete_photo)
                .setPositiveButton("Xóa", (dialog, which) -> {
                    FirebaseUser user = mAuth.getCurrentUser();
                    if (user != null) {
                        repository.deletePhoto(user.getUid(), photo.getId(), new FirestoreRepository.RepositoryCallback<Void>() {
                            @Override
                            public void onSuccess(Void result) {
                                if (binding == null || !isAdded()) return;
                                if (getContext() != null) {
                                    Toast.makeText(getContext(), "Đã xóa ảnh khỏi thư viện", Toast.LENGTH_SHORT).show();
                                }
                                loadFavorites();
                            }

                            @Override
                            public void onError(String error) {
                                if (binding == null || !isAdded()) return;
                                if (getContext() != null) {
                                    Toast.makeText(getContext(), "Lỗi xóa ảnh: " + error, Toast.LENGTH_SHORT).show();
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
        loadFavorites();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
