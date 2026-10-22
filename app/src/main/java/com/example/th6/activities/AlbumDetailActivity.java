package com.example.th6.activities;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.recyclerview.widget.GridLayoutManager;

import com.example.th6.R;
import com.example.th6.adapters.PhotoAdapter;
import com.example.th6.data.FirestoreRepository;
import com.example.th6.databinding.ActivityAlbumDetailBinding;
import com.example.th6.models.Album;
import com.example.th6.models.Photo;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.util.ArrayList;
import java.util.List;

public class AlbumDetailActivity extends AppCompatActivity implements PhotoAdapter.OnPhotoActionListener {

    private ActivityAlbumDetailBinding binding;
    private Album album;
    private PhotoAdapter photoAdapter;
    private FirestoreRepository repository;
    private FirebaseAuth mAuth;
    private List<Photo> albumPhotos = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAlbumDetailBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        mAuth = FirebaseAuth.getInstance();
        repository = new FirestoreRepository();

        if (getIntent().hasExtra("album")) {
            album = (Album) getIntent().getSerializableExtra("album");
        }

        if (album == null) {
            Toast.makeText(this, "Không tìm thấy thông tin album", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        binding.tvAlbumTitle.setText(album.getName());

        setupRecyclerView();
        setupListeners();
        loadAlbumPhotos();
    }

    private void setupRecyclerView() {
        photoAdapter = new PhotoAdapter(this, this);
        binding.rvAlbumPhotos.setLayoutManager(new GridLayoutManager(this, 2));
        binding.rvAlbumPhotos.setAdapter(photoAdapter);
    }

    private void setupListeners() {
        binding.btnBack.setOnClickListener(v -> finish());
        binding.swipeRefresh.setOnRefreshListener(this::loadAlbumPhotos);
        binding.btnEditAlbum.setOnClickListener(v -> showEditAlbumNameDialog());
        binding.btnDeleteAlbum.setOnClickListener(v -> confirmDeleteAlbum());
    }

    private void loadAlbumPhotos() {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) return;

        binding.progressBar.setVisibility(View.VISIBLE);
        repository.getPhotos(user.getUid(), new FirestoreRepository.RepositoryCallback<List<Photo>>() {
            @Override
            public void onSuccess(List<Photo> photos) {
                binding.progressBar.setVisibility(View.GONE);
                binding.swipeRefresh.setRefreshing(false);

                albumPhotos.clear();
                if (photos != null) {
                    for (Photo p : photos) {
                        if (!p.isTrashed() && p.getAlbumIds() != null && p.getAlbumIds().contains(album.getId())) {
                            albumPhotos.add(p);
                        }
                    }
                }

                photoAdapter.setPhotos(albumPhotos);
                updateEmptyState();
            }

            @Override
            public void onError(String error) {
                binding.progressBar.setVisibility(View.GONE);
                binding.swipeRefresh.setRefreshing(false);
                Toast.makeText(AlbumDetailActivity.this, "Lỗi tải ảnh trong album: " + error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void updateEmptyState() {
        if (albumPhotos.isEmpty()) {
            binding.layoutEmpty.setVisibility(View.VISIBLE);
            binding.rvAlbumPhotos.setVisibility(View.GONE);
        } else {
            binding.layoutEmpty.setVisibility(View.GONE);
            binding.rvAlbumPhotos.setVisibility(View.VISIBLE);
        }
    }

    private void showEditAlbumNameDialog() {
        EditText input = new EditText(this);
        input.setText(album.getName());
        input.setPadding(32, 32, 32, 32);

        new AlertDialog.Builder(this)
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
                                    album.setName(newName);
                                    binding.tvAlbumTitle.setText(newName);
                                    Toast.makeText(AlbumDetailActivity.this, "Đổi tên album thành công!", Toast.LENGTH_SHORT).show();
                                }

                                @Override
                                public void onError(String error) {
                                    Toast.makeText(AlbumDetailActivity.this, "Lỗi: " + error, Toast.LENGTH_SHORT).show();
                                }
                            });
                        }
                    }
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    private void confirmDeleteAlbum() {
        new AlertDialog.Builder(this)
                .setTitle("Xóa Album")
                .setMessage(R.string.confirm_delete_album)
                .setPositiveButton("Xóa", (dialog, which) -> {
                    FirebaseUser user = mAuth.getCurrentUser();
                    if (user != null) {
                        repository.deleteAlbum(user.getUid(), album.getId(), new FirestoreRepository.RepositoryCallback<Void>() {
                            @Override
                            public void onSuccess(Void result) {
                                Toast.makeText(AlbumDetailActivity.this, "Đã xóa album", Toast.LENGTH_SHORT).show();
                                finish();
                            }

                            @Override
                            public void onError(String error) {
                                Toast.makeText(AlbumDetailActivity.this, "Lỗi xóa album: " + error, Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                })
                .setNegativeButton("Hủy", null)
                .show();
    }

    @Override
    public void onPhotoClick(Photo photo) {
        Intent intent = PhotoDetailActivity.createIntent(this, photo, albumPhotos);
        startActivity(intent);
    }

    @Override
    public void onPhotoLongClick(Photo photo, View view) {
        PopupMenu popupMenu = new PopupMenu(this, view);
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
            } else if (itemId == R.id.ctx_delete) {
                // Remove photo from this album
                removePhotoFromAlbum(photo);
                return true;
            }
            return false;
        });

        popupMenu.show();
    }

    private void removePhotoFromAlbum(Photo photo) {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) return;

        photo.getAlbumIds().remove(album.getId());
        repository.updatePhotoAlbums(user.getUid(), photo.getId(), photo.getAlbumIds(), new FirestoreRepository.RepositoryCallback<Void>() {
            @Override
            public void onSuccess(Void result) {
                Toast.makeText(AlbumDetailActivity.this, "Đã xóa ảnh khỏi album", Toast.LENGTH_SHORT).show();
                loadAlbumPhotos();
            }

            @Override
            public void onError(String error) {
                Toast.makeText(AlbumDetailActivity.this, "Lỗi: " + error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onFavoriteToggle(Photo photo) {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) return;

        boolean newFav = !photo.isFavorite();
        repository.toggleFavorite(user.getUid(), photo.getId(), newFav, new FirestoreRepository.RepositoryCallback<Void>() {
            @Override
            public void onSuccess(Void result) {
                Toast.makeText(AlbumDetailActivity.this, newFav ? "Đã thêm vào yêu thích" : "Đã bỏ khỏi yêu thích", Toast.LENGTH_SHORT).show();
                loadAlbumPhotos();
            }

            @Override
            public void onError(String error) {
                Toast.makeText(AlbumDetailActivity.this, "Lỗi: " + error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadAlbumPhotos();
    }
}
