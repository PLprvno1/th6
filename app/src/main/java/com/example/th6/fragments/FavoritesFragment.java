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
import com.example.th6.data.AuthRepository;
import com.example.th6.data.PhotoRepository;
import com.example.th6.databinding.FragmentFavoritesBinding;
import com.example.th6.models.LocalMediaPhoto;
import com.example.th6.models.Photo;
import com.example.th6.services.DevicePhotoScanner;
import com.example.th6.utils.MediaTimelineUtils;
import com.example.th6.utils.PhotoTimelineUtils;
import com.example.th6.utils.TimelineSortMenu;
import com.example.th6.utils.LocalVideoFavorites;
import io.github.jan.supabase.auth.user.UserInfo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FavoritesFragment extends Fragment implements PhotoAdapter.OnPhotoActionListener {
    private FragmentFavoritesBinding binding;
    private PhotoAdapter photoAdapter;
    private PhotoRepository repository;
    private AuthRepository authRepository;
    private List<Photo> favoritePhotos = new ArrayList<>();
    private Map<String, LocalMediaPhoto> localByUri = new LinkedHashMap<>();
    private List<Photo> pendingCloudPhotos;
    private Map<String, LocalMediaPhoto> pendingLocalByUri;
    private boolean cloudReady, localReady;
    private int loadGeneration;
    private int currentViewMode = PhotoTimelineUtils.VIEW_MODE_ALL;
    private int currentMediaFilter = MediaTimelineUtils.FILTER_ALL;
    private int currentSortMode = R.id.sort_date_desc;

    @Override public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        authRepository = new AuthRepository();
        repository = new PhotoRepository();
        currentSortMode = requireContext().getSharedPreferences("photovault_library", android.content.Context.MODE_PRIVATE)
                .getInt("sort_mode", R.id.sort_date_desc);
        currentViewMode = requireContext().getSharedPreferences("photovault_library", android.content.Context.MODE_PRIVATE)
                .getInt("view_mode", PhotoTimelineUtils.VIEW_MODE_ALL);
        currentMediaFilter = requireContext().getSharedPreferences("photovault_library", android.content.Context.MODE_PRIVATE)
                .getInt("media_filter", MediaTimelineUtils.FILTER_ALL);
    }

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentFavoritesBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        photoAdapter = new PhotoAdapter(requireContext(), this);
        GridLayoutManager favoritesGrid = new GridLayoutManager(requireContext(), 2);
        favoritesGrid.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int position) { return photoAdapter.getItemViewType(position) == 1 ? 2 : 1; }
        });
        binding.rvFavorites.setLayoutManager(favoritesGrid);
        binding.rvFavorites.setAdapter(photoAdapter);
        binding.swipeRefresh.setOnRefreshListener(this::loadFavorites);
        setCheckedChip(currentViewMode,currentMediaFilter);
        binding.chipFavoriteAllTime.setOnClickListener(v -> selectTimeline(PhotoTimelineUtils.VIEW_MODE_ALL,currentMediaFilter));
        binding.chipFavoriteDays.setOnClickListener(v -> selectTimeline(PhotoTimelineUtils.VIEW_MODE_DAYS,currentMediaFilter));
        binding.chipFavoriteMonths.setOnClickListener(v -> selectTimeline(PhotoTimelineUtils.VIEW_MODE_MONTHS,currentMediaFilter));
        binding.chipFavoriteYears.setOnClickListener(v -> selectTimeline(PhotoTimelineUtils.VIEW_MODE_YEARS,currentMediaFilter));
        binding.chipFavoriteMediaAll.setOnClickListener(v -> selectTimeline(currentViewMode,MediaTimelineUtils.FILTER_ALL));
        binding.chipFavoriteImages.setOnClickListener(v -> selectTimeline(currentViewMode,MediaTimelineUtils.FILTER_IMAGES));
        binding.chipFavoriteVideos.setOnClickListener(v -> selectTimeline(currentViewMode,MediaTimelineUtils.FILTER_VIDEOS));
        binding.btnFavoriteSort.setOnClickListener(v -> TimelineSortMenu.show(requireContext(), v, currentSortMode, sort -> {
            currentSortMode=sort;
            requireContext().getSharedPreferences("photovault_library", android.content.Context.MODE_PRIVATE)
                    .edit().putInt("sort_mode", sort).apply();
            applyTimeline();
        }));
    }

    private void loadFavorites() {
        UserInfo user = authRepository.getCurrentUser();
        if (user == null || binding == null) return;
        final String uid = user.getId();
        final int generation = ++loadGeneration;
        cloudReady = false; localReady = false; pendingCloudPhotos = null; pendingLocalByUri = null;
        binding.progressBar.setVisibility(View.VISIBLE);
        repository.getPhotosByUserAsync(uid, new PhotoRepository.Callback<List<Photo>>() {
            @Override public void onSuccess(List<Photo> photos) {
                if (!isCurrentLoad(generation, uid)) return;
                LinkedHashMap<String, Photo> unique = new LinkedHashMap<>();
                if (photos != null) for (Photo photo : photos) {
                    if (photo == null || !photo.isFavorite() || photo.isTrashed()) continue;
                    String uri = photo.getLocalMediaUri();
                    String key = uri == null || uri.isEmpty() ? "cloud:" + photo.getId() : "local:" + uri;
                    unique.putIfAbsent(key, photo);
                }
                pendingCloudPhotos = new ArrayList<>(unique.values());
                cloudReady = true;
                renderWhenReady(generation, uid);
            }
            @Override public void onError(String error) {
                if (!isCurrentLoad(generation, uid)) return;
                pendingCloudPhotos = new ArrayList<>();
                cloudReady = true;
                renderWhenReady(generation, uid);
                Toast.makeText(requireContext(), "KhÃ´ng táº£i Ä‘Æ°á»£c má»¥c yÃªu thÃ­ch: " + error, Toast.LENGTH_LONG).show();
            }
        });
        new DevicePhotoScanner().scanPhotos(requireContext(), new DevicePhotoScanner.ScanCallback() {
            @Override public void onScanComplete(List<LocalMediaPhoto> photos) {
                if (!isCurrentLoad(generation, uid)) return;
                Map<String, LocalMediaPhoto> byUri = new LinkedHashMap<>();
                if (photos != null) for (LocalMediaPhoto local : photos) if (local.getContentUri() != null) byUri.put(local.getContentUri(), local);
                pendingLocalByUri = byUri;
                localReady = true; renderWhenReady(generation, uid);
            }
            @Override public void onScanError(String error) {
                if (!isCurrentLoad(generation, uid)) return;
                pendingLocalByUri = new LinkedHashMap<>(); localReady = true; renderWhenReady(generation, uid);
            }
        });
    }

    private boolean isCurrentLoad(int generation, String uid) {
        UserInfo active = authRepository.getCurrentUser();
        return binding != null && isAdded() && generation == loadGeneration && active != null && uid.equals(active.getId());
    }

    private void renderWhenReady(int generation, String uid) {
        if (!cloudReady || !localReady || !isCurrentLoad(generation, uid)) return;
        favoritePhotos = pendingCloudPhotos == null ? new ArrayList<>() : pendingCloudPhotos;
        localByUri = pendingLocalByUri == null ? new LinkedHashMap<>() : pendingLocalByUri;
        for (LocalMediaPhoto local : localByUri.values()) {
            if (!local.isVideo() || local.getContentUri() == null
                    || !LocalVideoFavorites.contains(requireContext(), uid, local.getContentUri())) continue;
            Photo represented = favoritePhotos.stream().filter(p -> local.getContentUri().equals(p.getLocalMediaUri())).findFirst().orElse(null);
            if (represented != null) {
                represented.setFavorite(true);
                continue;
            }
            Photo localFavorite = new Photo();
            localFavorite.setId("local:" + local.getMediaId());
            localFavorite.setOwnerUid(uid);
            localFavorite.setName(local.getDisplayName());
            localFavorite.setMediaType("video");
            localFavorite.setImageUrl(local.getContentUri());
            localFavorite.setCreatedAt(local.getTimelineTimestamp());
            localFavorite.setLocalMediaId(local.getMediaId());
            localFavorite.setLocalMediaUri(local.getContentUri());
            localFavorite.setLocalDateModified(local.getDateModified());
            localFavorite.setFavorite(true);
            favoritePhotos.add(localFavorite);
        }
        binding.progressBar.setVisibility(View.GONE); binding.swipeRefresh.setRefreshing(false);
        applyTimeline();
    }

    private long timestamp(Photo photo) {
        LocalMediaPhoto local = photo.getLocalMediaUri() == null ? null : localByUri.get(photo.getLocalMediaUri());
        long localTimestamp=local==null?0:local.getTimelineTimestamp();
        return localTimestamp<=0?photo.getCreatedAt():localTimestamp;
    }

    private void applyTimeline() {
        if (binding == null || photoAdapter == null) return;
        List<Photo> visible = MediaTimelineUtils.filterAndSort(favoritePhotos, currentMediaFilter, currentViewMode,
                currentSortMode, this::timestamp, Photo::getName, p -> "video".equalsIgnoreCase(p.getMediaType()));
        photoAdapter.setTimelinePhotos(visible, currentViewMode, this::timestamp);
        boolean noResults = visible.isEmpty();
        binding.layoutEmpty.setVisibility(noResults ? View.VISIBLE : View.GONE);
        binding.rvFavorites.setVisibility(noResults ? View.GONE : View.VISIBLE);
        binding.tvFavoriteEmptyMessage.setText(favoritePhotos.isEmpty() ? R.string.empty_favorites : R.string.empty_media_filter);
    }

    private void selectTimeline(int viewMode,int mediaFilter) {
        currentViewMode=viewMode; currentMediaFilter=mediaFilter;
        requireContext().getSharedPreferences("photovault_library",android.content.Context.MODE_PRIVATE).edit()
                .putInt("view_mode",viewMode).putInt("media_filter",mediaFilter).apply();
        setCheckedChip(viewMode,mediaFilter); applyTimeline();
    }

    private void setCheckedChip(int viewMode,int mediaFilter) {
        if(binding==null)return;
        if(viewMode==PhotoTimelineUtils.VIEW_MODE_DAYS)binding.chipFavoriteDays.setChecked(true);
        else if(viewMode==PhotoTimelineUtils.VIEW_MODE_MONTHS)binding.chipFavoriteMonths.setChecked(true);
        else if(viewMode==PhotoTimelineUtils.VIEW_MODE_YEARS)binding.chipFavoriteYears.setChecked(true);
        else binding.chipFavoriteAllTime.setChecked(true);
        if(mediaFilter==MediaTimelineUtils.FILTER_IMAGES)binding.chipFavoriteImages.setChecked(true);
        else if(mediaFilter==MediaTimelineUtils.FILTER_VIDEOS)binding.chipFavoriteVideos.setChecked(true);
        else binding.chipFavoriteMediaAll.setChecked(true);
    }

    @Override public void onPhotoClick(Photo photo) {
        List<Photo> visibleFavorites = photoAdapter == null ? favoritePhotos : photoAdapter.getPhotos();
        startActivity(PhotoDetailActivity.createIntent(requireContext(), photo, visibleFavorites));
    }

    @Override public void onPhotoLongClick(Photo photo, View view) {
        PopupMenu popupMenu = new PopupMenu(requireContext(), view);
        popupMenu.getMenuInflater().inflate(R.menu.context_menu_photo, popupMenu.getMenu());
        MenuItem favItem = popupMenu.getMenu().findItem(R.id.ctx_toggle_favorite);
        if (favItem != null) favItem.setTitle(photo.isFavorite() ? R.string.ctx_remove_favorite : R.string.ctx_add_favorite);
        popupMenu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.ctx_view_detail) { onPhotoClick(photo); return true; }
            if (item.getItemId() == R.id.ctx_toggle_favorite) { onFavoriteToggle(photo); return true; }
            if (item.getItemId() == R.id.ctx_share) { sharePhoto(photo); return true; }
            if (item.getItemId() == R.id.ctx_delete) { confirmDeletePhoto(photo); return true; }
            return false;
        });
        popupMenu.show();
    }

    @Override public void onFavoriteToggle(Photo photo) {
        UserInfo user = authRepository.getCurrentUser();
        if (user == null || getContext() == null) return;
        boolean newFavorite = !photo.isFavorite();
        if (photo.getId() != null && photo.getId().startsWith("local:")) {
            LocalVideoFavorites.set(requireContext(), user.getId(), photo.getLocalMediaUri(), newFavorite);
            loadFavorites();
            return;
        }
        repository.toggleFavoriteAsync(user.getId(), photo.getId(), newFavorite, new PhotoRepository.Callback<Void>() {
            @Override public void onSuccess(Void result) {
                if (binding == null || !isAdded()) return;
                LocalVideoFavorites.set(requireContext(), user.getId(), photo.getLocalMediaUri(), false);
                Toast.makeText(requireContext(), newFavorite ? "ÄÃ£ thÃªm vÃ o yÃªu thÃ­ch" : "ÄÃ£ bá» khá»i yÃªu thÃ­ch", Toast.LENGTH_SHORT).show();
                loadFavorites();
            }
            @Override public void onError(String error) { if (binding != null && isAdded()) Toast.makeText(requireContext(), "Lá»—i: " + error, Toast.LENGTH_SHORT).show(); }
        });
    }

    private void sharePhoto(Photo photo) {
        Intent shareIntent = new Intent(Intent.ACTION_SEND); shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, photo.getName());
        shareIntent.putExtra(Intent.EXTRA_TEXT, "Xem áº£nh \"" + photo.getName() + "\" trÃªn PhotoVault: " + photo.getImageUrl());
        startActivity(Intent.createChooser(shareIntent, "Chia sáº» áº£nh qua"));
    }

    private void confirmDeletePhoto(Photo photo) {
        if (getContext() == null) return;
        new AlertDialog.Builder(requireContext()).setTitle("XÃ¡c nháº­n xÃ³a").setMessage(R.string.confirm_delete_photo)
                .setPositiveButton("XÃ³a", (dialog, which) -> {
                    UserInfo user = authRepository.getCurrentUser(); if (user == null) return;
                    repository.deletePhotoAsync(user.getId(), photo.getId(), new PhotoRepository.Callback<Void>() {
                        @Override public void onSuccess(Void result) { if (binding != null && isAdded()) { Toast.makeText(requireContext(), "ÄÃ£ xÃ³a áº£nh khá»i thÆ° viá»‡n", Toast.LENGTH_SHORT).show(); loadFavorites(); } }
                        @Override public void onError(String error) { if (binding != null && isAdded()) Toast.makeText(requireContext(), "Lá»—i xÃ³a áº£nh: " + error, Toast.LENGTH_SHORT).show(); }
                    });
                }).setNegativeButton("Há»§y", null).show();
    }

    @Override public void onResume() {
        super.onResume();
        if(binding!=null){
            android.content.SharedPreferences prefs=requireContext().getSharedPreferences("photovault_library",android.content.Context.MODE_PRIVATE);
            currentSortMode=prefs.getInt("sort_mode",R.id.sort_date_desc);
            currentViewMode=prefs.getInt("view_mode",PhotoTimelineUtils.VIEW_MODE_ALL);
            currentMediaFilter=prefs.getInt("media_filter",MediaTimelineUtils.FILTER_ALL);
            setCheckedChip(currentViewMode,currentMediaFilter); applyTimeline(); loadFavorites();
        }
    }
    @Override public void onDestroyView() { loadGeneration++; super.onDestroyView(); binding = null; }
}
