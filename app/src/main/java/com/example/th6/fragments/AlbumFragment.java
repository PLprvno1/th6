package com.example.th6.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.PopupMenu;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;

import com.example.th6.R;
import com.example.th6.activities.AlbumDetailActivity;
import com.example.th6.adapters.AlbumAdapter;
import com.example.th6.data.FirestoreRepository;
import com.example.th6.databinding.FragmentAlbumBinding;
import com.example.th6.models.Album;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class AlbumFragment extends Fragment implements AlbumAdapter.OnAlbumActionListener {

    private FragmentAlbumBinding binding;
    private AlbumAdapter albumAdapter;
    private FirestoreRepository repository;
    private FirebaseAuth mAuth;
    private List<Album> albumList = new ArrayList<>();

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mAuth = FirebaseAuth.getInstance();
        repository = new FirestoreRepository();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentAlbumBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        setupRecyclerView();
        setupListeners();
        loadAlbums();
    }

    private void setupRecyclerView() {
        albumAdapter = new AlbumAdapter(requireContext(), this);
        binding.rvAlbums.setLayoutManager(new GridLayoutManager(requireContext(), 2));
        binding.rvAlbums.setAdapter(albumAdapter);
    }

    private void setupListeners() {
        binding.swipeRefresh.setOnRefreshListener(this::loadAlbums);
        binding.btnCreateAlbum.setOnClickListener(v -> showCreateAlbumDialog());
    }

    private void loadAlbums() {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null || binding == null) return;
        String requestedUid = user.getUid();

        binding.progressBar.setVisibility(View.VISIBLE);
        repository.getAlbums(requestedUid, new FirestoreRepository.RepositoryCallback<List<Album>>() {
            @Override
            public void onSuccess(List<Album> albums) {
                FirebaseUser activeUser = mAuth.getCurrentUser();
                if (binding == null || !isAdded() || activeUser == null || !requestedUid.equals(activeUser.getUid())) return;
                binding.progressBar.setVisibility(View.GONE);
                binding.swipeRefresh.setRefreshing(false);
                albumList = albums != null ? albums : new ArrayList<>();
                albumAdapter.setAlbums(albumList);
                updateEmptyState();
                repository.getPhotos(requestedUid, new FirestoreRepository.RepositoryCallback<List<com.example.th6.models.Photo>>() {
                    @Override public void onSuccess(List<com.example.th6.models.Photo> photos) {
                        FirebaseUser current = mAuth.getCurrentUser();
                        if (binding == null || !isAdded() || current == null || !requestedUid.equals(current.getUid())) return;
                        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
                        java.util.Map<String, String> covers = new java.util.HashMap<>();
                        if (photos != null) for (com.example.th6.models.Photo photo : photos) {
                            if (photo.isTrashed() || photo.getAlbumIds() == null) continue;
                            for (String albumId : photo.getAlbumIds()) {
                                counts.put(albumId, counts.getOrDefault(albumId, 0) + 1);
                                if (!covers.containsKey(albumId) && photo.getImageUrl() != null) covers.put(albumId, photo.getImageUrl());
                            }
                        }
                        for (Album album : albumList) {
                            album.setPhotoCount(counts.getOrDefault(album.getId(), 0));
                            String cover = covers.get(album.getId());
                            if (cover != null && (album.getCoverImageUrl() == null || album.getCoverImageUrl().isEmpty())) album.setCoverImageUrl(cover);
                        }
                        albumAdapter.setAlbums(albumList);
                    }
                    @Override public void onError(String error) { /* Album contents remain available in detail view. */ }
                });
            }

            @Override
            public void onError(String error) {
                if (binding == null || !isAdded()) return;
                binding.progressBar.setVisibility(View.GONE);
                binding.swipeRefresh.setRefreshing(false);
                if (getContext() != null) {
                    Toast.makeText(getContext(), "Lỗi tải danh sách album: " + error, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void showCreateAlbumDialog() {
        if (getContext() == null) return;
        EditText input = new EditText(requireContext());
        input.setHint(R.string.album_name_hint);
        input.setPadding(32, 32, 32, 32);

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.create_album)
                .setView(input)
                .setPositiveButton(R.string.btn_create, (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (!TextUtils.isEmpty(name)) {
                        createAlbum(name);
                    } else if (getContext() != null) {
                        Toast.makeText(getContext(), "Vui lòng nhập tên album", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    private void createAlbum(String name) {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null || binding == null) return;

        String albumId = UUID.randomUUID().toString();
        Album album = new Album(albumId, user.getUid(), name, System.currentTimeMillis());

        binding.progressBar.setVisibility(View.VISIBLE);
        repository.createAlbum(album, new FirestoreRepository.RepositoryCallback<Void>() {
            @Override
            public void onSuccess(Void result) {
                if (binding == null || !isAdded()) return;
                binding.progressBar.setVisibility(View.GONE);
                if (getContext() != null) {
                    Toast.makeText(getContext(), "Tạo album thành công!", Toast.LENGTH_SHORT).show();
                }
                loadAlbums();
            }

            @Override
            public void onError(String error) {
                if (binding == null || !isAdded()) return;
                binding.progressBar.setVisibility(View.GONE);
                if (getContext() != null) {
                    Toast.makeText(getContext(), "Lỗi tạo album: " + error, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void updateEmptyState() {
        if (binding == null) return;
        if (albumList.isEmpty()) {
            binding.layoutEmpty.setVisibility(View.VISIBLE);
            binding.rvAlbums.setVisibility(View.GONE);
        } else {
            binding.layoutEmpty.setVisibility(View.GONE);
            binding.rvAlbums.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onAlbumClick(Album album) {
        Intent intent = new Intent(requireContext(), AlbumDetailActivity.class);
        intent.putExtra("album", album);
        startActivity(intent);
    }

    @Override
    public void onAlbumLongClick(Album album, View view) {
        PopupMenu popupMenu = new PopupMenu(requireContext(), view);
        popupMenu.getMenu().add(0, 1, 0, R.string.edit_name);
        popupMenu.getMenu().add(0, 2, 1, R.string.btn_delete);

        popupMenu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                showEditAlbumNameDialog(album);
                return true;
            } else if (item.getItemId() == 2) {
                confirmDeleteAlbum(album);
                return true;
            }
            return false;
        });

        popupMenu.show();
    }

    private void showEditAlbumNameDialog(Album album) {
        if (getContext() == null) return;
        EditText input = new EditText(requireContext());
        input.setText(album.getName());
        input.setPadding(32, 32, 32, 32);

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.edit_name)
                .setView(input)
                .setPositiveButton(R.string.btn_save, (dialog, which) -> {
                    String newName = input.getText().toString().trim();
                    if (!TextUtils.isEmpty(newName)) {
                        FirebaseUser user = mAuth.getCurrentUser();
                        if (user != null) {
                            repository.updateAlbumName(user.getUid(), album.getId(), newName, new FirestoreRepository.RepositoryCallback<Void>() {
                                @Override
                                public void onSuccess(Void result) {
                                    if (binding == null || !isAdded()) return;
                                    if (getContext() != null) {
                                        Toast.makeText(getContext(), "Cập nhật tên album thành công!", Toast.LENGTH_SHORT).show();
                                    }
                                    loadAlbums();
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
                    }
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    private void confirmDeleteAlbum(Album album) {
        if (getContext() == null) return;
        new AlertDialog.Builder(requireContext())
                .setTitle("Xóa Album")
                .setMessage(R.string.confirm_delete_album)
                .setPositiveButton("Xóa", (dialog, which) -> {
                    FirebaseUser user = mAuth.getCurrentUser();
                    if (user != null) {
                        repository.deleteAlbum(user.getUid(), album.getId(), new FirestoreRepository.RepositoryCallback<Void>() {
                            @Override
                            public void onSuccess(Void result) {
                                if (binding == null || !isAdded()) return;
                                if (getContext() != null) {
                                    Toast.makeText(getContext(), "Đã xóa album", Toast.LENGTH_SHORT).show();
                                }
                                loadAlbums();
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
                })
                .setNegativeButton("Hủy", null)
                .show();
    }

    @Override
    public void onResume() {
        super.onResume();
        loadAlbums();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
