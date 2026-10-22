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
import com.example.th6.data.AuthRepository;
import com.example.th6.data.PhotoRepository;
import com.example.th6.databinding.FragmentAlbumBinding;
import com.example.th6.models.Album;
import com.example.th6.models.LocalMediaPhoto;
import com.example.th6.models.Photo;
import com.example.th6.data.LocalAlbumStore;
import com.example.th6.services.DevicePhotoScanner;
import com.example.th6.utils.PhotoVaultTrashCache;
import io.github.jan.supabase.auth.user.UserInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class AlbumFragment extends Fragment implements AlbumAdapter.OnAlbumActionListener {

    private FragmentAlbumBinding binding;
    private AlbumAdapter albumAdapter;
    private PhotoRepository repository;
    private AuthRepository authRepository;
    private List<Album> albumList = new ArrayList<>();

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        authRepository = new AuthRepository();
        repository = new PhotoRepository();
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
        UserInfo user = authRepository.getCurrentUser();
        if (user == null || binding == null) return;
        String requestedUid = user.getId();

        binding.progressBar.setVisibility(View.VISIBLE);
        repository.getAlbumsAsync(requestedUid, new PhotoRepository.Callback<List<Album>>() {
            @Override
            public void onSuccess(List<Album> albums) {
                UserInfo activeUser = authRepository.getCurrentUser();
                if (binding == null || !isAdded() || activeUser == null || !requestedUid.equals(activeUser.getId())) return;
                binding.progressBar.setVisibility(View.GONE);
                binding.swipeRefresh.setRefreshing(false);
                albumList = albums != null ? albums : new ArrayList<>();
                albumAdapter.setAlbums(albumList);
                updateEmptyState();
                repository.getPhotosByUserAsync(requestedUid, new PhotoRepository.Callback<List<Photo>>() {
                    @Override public void onSuccess(List<Photo> photos) {
                        new DevicePhotoScanner().scanPhotos(requireContext(), new DevicePhotoScanner.ScanCallback() {
                            @Override public void onScanComplete(List<LocalMediaPhoto> localItems) {
                                UserInfo current = authRepository.getCurrentUser();
                                if (binding == null || !isAdded() || current == null || !requestedUid.equals(current.getId())) return;
                                java.util.Map<String, LocalMediaPhoto> byUri = new java.util.HashMap<>();
                                for (LocalMediaPhoto local : localItems) byUri.put(local.getContentUri(), local);
                                java.util.Map<String, java.util.Set<String>> members = new java.util.HashMap<>();
                                java.util.Map<String, List<Photo>> cloudMembers = new java.util.HashMap<>();
                                java.util.Set<String> trashedLocalKeys = new java.util.HashSet<>();
                                java.util.Set<String> localTrashIdentities = PhotoVaultTrashCache.getLocalMediaIdentityKeys(requireContext());
                                for (LocalMediaPhoto local : localItems) if (PhotoVaultTrashCache.isLocalMediaTrashed(localTrashIdentities, local)) trashedLocalKeys.add(AlbumMediaPickerActivityKey.forLocal(local));
                                if (photos != null) for (Photo photo : photos) {
                                    LocalMediaPhoto local = byUri.get(photo.getLocalMediaUri());
                                    if (photo.isTrashed()) { if (local != null) trashedLocalKeys.add(AlbumMediaPickerActivityKey.forLocal(local)); continue; }
                                    if (photo.getAlbumIds() == null) continue;
                                    String mediaKey = local == null ? "cloud:" + photo.getId() : AlbumMediaPickerActivityKey.forLocal(local);
                                    for (String albumId : photo.getAlbumIds()) {
                                        members.computeIfAbsent(albumId, ignored -> new java.util.HashSet<>()).add(mediaKey);
                                        cloudMembers.computeIfAbsent(albumId, ignored -> new ArrayList<>()).add(photo);
                                    }
                                }
                                try (LocalAlbumStore store = new LocalAlbumStore(requireContext())) {
                                    for (Album album : albumList) {
                                        java.util.Set<String> all = members.computeIfAbsent(album.getId(), ignored -> new java.util.HashSet<>());
                                        java.util.Set<String> availableLocalKeys = new java.util.HashSet<>();
                                        for (LocalMediaPhoto local : localItems) availableLocalKeys.add(AlbumMediaPickerActivityKey.forLocal(local));
                                        java.util.Set<String> localMemberships = new java.util.HashSet<>(store.getMembers(requestedUid, album.getId()));
                                        for (String key : localMemberships) if (availableLocalKeys.contains(key) && !trashedLocalKeys.contains(key)) all.add(key);
                                        album.setPhotoCount(all.size());
                                        List<Photo> cloud = cloudMembers.getOrDefault(album.getId(), new ArrayList<>());
                                        cloud.sort(java.util.Comparator.comparingLong(Photo::getCreatedAt).thenComparing(Photo::getId));
                                        boolean cloudCoverValid = false;
                                        if (album.getCoverImageUrl() != null) for (Photo p : cloud) if (album.getCoverImageUrl().equals(p.getImageUrl())) cloudCoverValid = true;
                                        String localCover = store.getCover(requestedUid, album.getId());
                                        LocalMediaPhoto localCoverItem = null;
                                        if (localCover != null && localMemberships.contains(localCover)) for (LocalMediaPhoto local : localItems) if (localCover.equals(AlbumMediaPickerActivityKey.forLocal(local)) && !trashedLocalKeys.contains(localCover)) { localCoverItem = local; break; }
                                        album.setDisplayCoverUri(localCoverItem == null ? "" : localCoverItem.getContentUri());
                                        Photo selectedCloudCover = null;
                                        if (cloudCoverValid) for (Photo p : cloud) if (album.getCoverImageUrl().equals(p.getImageUrl())) { selectedCloudCover = p; break; }
                                        album.setDisplayCoverVideo(localCoverItem != null ? localCoverItem.isVideo() : selectedCloudCover != null && "video".equalsIgnoreCase(selectedCloudCover.getMediaType()));
                                        if (!cloudCoverValid && localCoverItem == null && !cloud.isEmpty()) {
                                            Photo first = cloud.get(0);
                                            album.setCoverImageUrl(first.getImageUrl());
                                            album.setDisplayCoverVideo("video".equalsIgnoreCase(first.getMediaType()));
                                            repository.updateAlbumCoverUrlAsync(requestedUid, album.getId(), first.getImageUrl(), new PhotoRepository.Callback<Void>() {
                                                @Override public void onSuccess(Void result) { }
                                                @Override public void onError(String error) { }
                                            });
                                        } else if (!cloudCoverValid && localCoverItem == null && all.isEmpty()) {
                                            album.setCoverImageUrl(""); store.clearCover(requestedUid, album.getId());
                                        }
                                    }
                                }
                                albumAdapter.setAlbums(albumList);
                            }
                            @Override public void onScanError(String error) {
                                if (binding == null || !isAdded()) return;
                                albumAdapter.setAlbums(albumList);
                            }
                        });
                    }
                    @Override public void onError(String error) {
                        if (binding == null || !isAdded() || getContext() == null) return;
                        Toast.makeText(requireContext(), "Không tải được nội dung album; đang giữ danh sách album.", Toast.LENGTH_LONG).show();
                    }
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
        UserInfo user = authRepository.getCurrentUser();
        if (user == null || binding == null) return;

        String albumId = UUID.randomUUID().toString();
        Album album = new Album(albumId, user.getId(), name, System.currentTimeMillis());

        binding.progressBar.setVisibility(View.VISIBLE);
        repository.createAlbumAsync(album, new PhotoRepository.Callback<Void>() {
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
        popupMenu.getMenu().add(0, 1, 0, R.string.rename_album);
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
                .setTitle(R.string.rename_album)
                .setView(input)
                .setPositiveButton(R.string.btn_save, (dialog, which) -> {
                    String newName = input.getText().toString().trim();
                    if (!TextUtils.isEmpty(newName)) {
                        UserInfo user = authRepository.getCurrentUser();
                        if (user != null) {
                            repository.updateAlbumNameAsync(user.getId(), album.getId(), newName, new PhotoRepository.Callback<Void>() {
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
                    UserInfo user = authRepository.getCurrentUser();
                    if (user != null) {
                        repository.deleteAlbumAsync(user.getId(), album.getId(), new PhotoRepository.Callback<Void>() {
                            @Override
                            public void onSuccess(Void result) {
                                if (binding == null || !isAdded()) return;
                                try (LocalAlbumStore store = new LocalAlbumStore(requireContext())) { store.deleteAlbum(user.getId(), album.getId()); }
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

    private static final class AlbumMediaPickerActivityKey {
        static String forLocal(LocalMediaPhoto local) {
            return "media:" + (local.isVideo() ? "video" : "image") + ":" + local.getMediaId() + ":" + local.getDateModified() + ":" + local.getSizeBytes();
        }
    }
}
