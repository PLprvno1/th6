package com.example.th6.activities;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;

import com.example.th6.adapters.PhotoAdapter;
import com.example.th6.data.AuthRepository;
import com.example.th6.data.PhotoRepository;
import com.example.th6.databinding.ActivitySearchBinding;
import com.example.th6.models.Photo;
import io.github.jan.supabase.auth.user.UserInfo;

import java.util.ArrayList;
import java.util.List;

public class SearchActivity extends AppCompatActivity implements PhotoAdapter.OnPhotoActionListener {

    private ActivitySearchBinding binding;
    private PhotoAdapter photoAdapter;
    private PhotoRepository repository;
    private AuthRepository authRepository;

    private List<Photo> allPhotos = new ArrayList<>();
    private List<Photo> searchResults = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySearchBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        authRepository = new AuthRepository();
        repository = new PhotoRepository();

        setupRecyclerView();
        setupListeners();
        loadPhotos();
    }

    private void setupRecyclerView() {
        photoAdapter = new PhotoAdapter(this, this);
        binding.rvSearchResults.setLayoutManager(new GridLayoutManager(this, 2));
        binding.rvSearchResults.setAdapter(photoAdapter);
    }

    private void setupListeners() {
        binding.btnBack.setOnClickListener(v -> finish());

        binding.etSearchQuery.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterPhotos(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
    }

    private void loadPhotos() {
        UserInfo user = authRepository.getCurrentUser();
        if (user == null) return;

        repository.getPhotosByUserAsync(user.getId(), new PhotoRepository.Callback<List<Photo>>() {
            @Override
            public void onSuccess(List<Photo> photos) {
                allPhotos = photos != null ? photos : new ArrayList<>();
                filterPhotos(binding.etSearchQuery.getText().toString());
            }

            @Override
            public void onError(String error) {
                Toast.makeText(SearchActivity.this, "Lỗi tải thư viện: " + error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void filterPhotos(String query) {
        searchResults.clear();
        String q = query != null ? query.trim().toLowerCase() : "";

        if (q.isEmpty()) {
            searchResults.addAll(allPhotos);
        } else {
            for (Photo p : allPhotos) {
                if (!p.isTrashed() && p.getName() != null && p.getName().toLowerCase().contains(q)) {
                    searchResults.add(p);
                }
            }
        }

        photoAdapter.setPhotos(searchResults);

        if (searchResults.isEmpty()) {
            binding.layoutEmpty.setVisibility(View.VISIBLE);
            binding.rvSearchResults.setVisibility(View.GONE);
        } else {
            binding.layoutEmpty.setVisibility(View.GONE);
            binding.rvSearchResults.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onPhotoClick(Photo photo) {
        Intent intent = PhotoDetailActivity.createIntent(this, photo, searchResults);
        startActivity(intent);
    }

    @Override
    public void onPhotoLongClick(Photo photo, View view) {
        onPhotoClick(photo);
    }

    @Override
    public void onFavoriteToggle(Photo photo) {
        UserInfo user = authRepository.getCurrentUser();
        if (user == null) return;

        boolean newFav = !photo.isFavorite();
        repository.toggleFavoriteAsync(user.getId(), photo.getId(), newFav, new PhotoRepository.Callback<Void>() {
            @Override
            public void onSuccess(Void result) {
                photo.setFavorite(newFav);
                photoAdapter.notifyDataSetChanged();
            }

            @Override
            public void onError(String error) {
                Toast.makeText(SearchActivity.this, "Lỗi: " + error, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
