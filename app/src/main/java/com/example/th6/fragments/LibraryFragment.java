package com.example.th6.fragments;

import android.Manifest;
import android.content.Intent;
import android.content.ClipData;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.bumptech.glide.Glide;
import com.example.th6.MainActivity;
import com.example.th6.R;
import com.example.th6.activities.BackupCenterActivity;
import com.example.th6.activities.PhotoDetailActivity;
import com.example.th6.activities.VideoPlayerActivity;
import com.example.th6.activities.SearchActivity;
import com.example.th6.activities.SlideshowActivity;
import com.example.th6.adapters.LocalPhotoAdapter;
import com.example.th6.data.FirestoreRepository;
import com.example.th6.databinding.FragmentLibraryBinding;
import com.example.th6.models.LocalMediaPhoto;
import com.example.th6.models.Photo;
import com.example.th6.models.UserProfile;
import com.example.th6.services.DevicePhotoScanner;
import com.example.th6.services.BackupWorkScheduler;
import com.example.th6.services.ImgBBUploadService;
import com.example.th6.utils.BackupPreferences;
import com.example.th6.utils.PhotoTimelineUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.android.material.snackbar.Snackbar;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class LibraryFragment extends Fragment implements LocalPhotoAdapter.OnLocalPhotoClickListener {

    private FragmentLibraryBinding binding;
    private LocalPhotoAdapter localPhotoAdapter;
    private DevicePhotoScanner photoScanner;
    private FirestoreRepository repository;
    private ImgBBUploadService uploadService;
    private FirebaseAuth mAuth;

    private List<LocalMediaPhoto> allDevicePhotos = new ArrayList<>();
    private List<LocalMediaPhoto> localDevicePhotos = new ArrayList<>();
    private final Set<String> selectedPhotoKeys = new LinkedHashSet<>();
    private boolean selectionMode;
    private boolean cloudLoadFailed;
    private ActivityResultLauncher<IntentSenderRequest> deleteMediaLauncher;
    private ActivityResultLauncher<String> writeMediaPermissionLauncher;
    private List<Uri> pendingDeleteUris = new ArrayList<>();
    private int pendingDeleteSucceeded;
    private int pendingDeleteCount;
    private String displayedCloudUid;
    private final ExecutorService cloudMergeExecutor = Executors.newSingleThreadExecutor();
    private int currentViewMode = PhotoTimelineUtils.VIEW_MODE_ALL;
    private boolean scanInProgress;
    private WorkInfo immediateBackupWork;
    private WorkInfo periodicBackupWork;

    private ActivityResultLauncher<String[]> requestPermissionLauncher;
    private ActivityResultLauncher<String> photoPickerLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mAuth = FirebaseAuth.getInstance();
        repository = new FirestoreRepository();
        uploadService = new ImgBBUploadService();
        photoScanner = new DevicePhotoScanner();
        deleteMediaLauncher = registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(), result -> {
            if (!isAdded() || getContext() == null) {
                pendingDeleteUris.clear();
                pendingDeleteSucceeded = 0;
                pendingDeleteCount = 0;
                return;
            }
            if (result.getResultCode() == android.app.Activity.RESULT_OK) {
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && !pendingDeleteUris.isEmpty()) {
                    deleteUrisOnAndroidQ(new ArrayList<>(pendingDeleteUris), true);
                } else {
                    Toast.makeText(getContext(), "Đã gửi yêu cầu xóa cho " + pendingDeleteUris.size() + " ảnh local", Toast.LENGTH_SHORT).show();
                    pendingDeleteUris.clear();
                    pendingDeleteCount = 0;
                    scanDeviceGallery();
                }
            } else {
                pendingDeleteUris.clear();
                pendingDeleteSucceeded = 0;
                pendingDeleteCount = 0;
                Toast.makeText(getContext(), "Đã hủy xóa ảnh", Toast.LENGTH_SHORT).show();
            }
        });
        writeMediaPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (!isAdded() || getContext() == null) {
                pendingDeleteUris.clear();
                pendingDeleteCount = 0;
                return;
            }
            if (granted && !pendingDeleteUris.isEmpty()) {
                int deleted = deleteUrisDirectly(new ArrayList<>(pendingDeleteUris));
                int total = pendingDeleteCount;
                pendingDeleteUris.clear();
                pendingDeleteCount = 0;
                Toast.makeText(getContext(), "Đã xóa " + deleted + "/" + total + " ảnh local", Toast.LENGTH_LONG).show();
                clearSelection();
                scanDeviceGallery();
            } else {
                pendingDeleteUris.clear();
                pendingDeleteCount = 0;
                Toast.makeText(getContext(), "Không được cấp quyền xóa media", Toast.LENGTH_LONG).show();
            }
        });

        requestPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                results -> {
                    if (hasAnyMediaReadPermission()) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                                && !hasPermission(Manifest.permission.READ_MEDIA_VIDEO)
                                && (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                                || !hasPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))) {
                            Toast.makeText(getContext(), "Video permission was not granted; videos may be unavailable", Toast.LENGTH_LONG).show();
                        }
                        scanDeviceGallery();
                        FirebaseUser backupUser = mAuth.getCurrentUser();
                        if (backupUser != null && getContext() != null
                                && new BackupPreferences(requireContext(), backupUser.getUid()).isBackupEnabled()) {
                            BackupWorkScheduler.reconcile(requireContext(), backupUser.getUid(), true);
                        }
                    }
                    else {
                        Toast.makeText(getContext(), "Media permission is needed to show device media", Toast.LENGTH_LONG).show();
                        loadCloudPhotosForCurrentUser();
                        updateEmptyState();
                    }
                }
        );

        photoPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri != null) {
                        uploadPhotoToImgBB(uri);
                    }
                }
        );
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentLibraryBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        setupRecyclerView();
        setupListeners();
        observeBackupWork();
        checkPermissionAndScan();
        checkBackupOnboarding();
    }

    private void observeBackupWork() {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) {
            renderBackupStatus();
            return;
        }
        WorkManager workManager = WorkManager.getInstance(requireContext().getApplicationContext());
        String uid = user.getUid();
        workManager.getWorkInfosForUniqueWorkLiveData(BackupWorkScheduler.immediateWorkName(uid))
                .observe(getViewLifecycleOwner(), infos -> {
                    immediateBackupWork = latestWorkInfo(infos);
                    renderBackupStatus();
                });
        workManager.getWorkInfosForUniqueWorkLiveData(BackupWorkScheduler.periodicWorkName(uid))
                .observe(getViewLifecycleOwner(), infos -> {
                    periodicBackupWork = latestWorkInfo(infos);
                    renderBackupStatus();
                });
    }

    @Nullable
    private WorkInfo latestWorkInfo(@Nullable List<WorkInfo> infos) {
        if (infos == null || infos.isEmpty()) return null;
        for (int i = infos.size() - 1; i >= 0; i--) {
            if (infos.get(i).getState() == WorkInfo.State.RUNNING) return infos.get(i);
        }
        // WorkManager returns the unique-work chain in enqueue order; the last item is newest.
        return infos.get(infos.size() - 1);
    }

    private void renderBackupStatus() {
        if (binding == null) return;
        WorkInfo running = isRunning(immediateBackupWork) ? immediateBackupWork
                : isRunning(periodicBackupWork) ? periodicBackupWork : null;
        if (running != null) {
            binding.layoutBackupStatus.setVisibility(View.VISIBLE);
            binding.progressBackupStatus.setVisibility(View.VISIBLE);
            binding.tvBackupStatus.setText("Đang sao lưu ảnh");
            int total = running.getProgress().getInt("total", 0);
            int completed = running.getProgress().getInt("completed", 0);
            if (total > 0) {
                binding.tvBackupProgress.setVisibility(View.VISIBLE);
                binding.tvBackupProgress.setText("Đã xử lý " + Math.min(completed, total) + "/" + total + " ảnh");
            } else {
                binding.tvBackupProgress.setVisibility(View.GONE);
            }
            return;
        }
        binding.layoutBackupStatus.setVisibility(View.GONE);
        binding.progressBackupStatus.setVisibility(View.GONE);
        maybeReportPartialCompletion(immediateBackupWork, false);
        // A periodic WorkRequest returns to ENQUEUED after a successful cycle.
        maybeReportPartialCompletion(periodicBackupWork, true);
    }

    private boolean isRunning(@Nullable WorkInfo info) {
        return info != null && info.getState() == WorkInfo.State.RUNNING;
    }

    private void maybeReportPartialCompletion(@Nullable WorkInfo info, boolean periodic) {
        if (info == null || binding == null) return;
        boolean completed = info.getState() == WorkInfo.State.SUCCEEDED
                || (periodic && info.getState() == WorkInfo.State.ENQUEUED);
        if (!completed) return;
        String id = info.getId().toString();
        int failures = info.getOutputData().getInt("failures", 0);
        if (failures <= 0) return;
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) return;
        android.content.SharedPreferences noticePrefs = requireContext().getSharedPreferences(
                "backup_status_notices", android.content.Context.MODE_PRIVATE);
        String noticeKey = "last_partial_" + user.getUid();
        if (id.equals(noticePrefs.getString(noticeKey, null))) return;
        noticePrefs.edit().putString(noticeKey, id).apply();
        Snackbar.make(binding.getRoot(), "Sao lưu hoàn tất một phần · " + failures + " ảnh cần xử lý", Snackbar.LENGTH_LONG)
                .setAction("Chi tiết", v -> {
                    if (isAdded()) startActivity(new Intent(requireContext(), BackupCenterActivity.class));
                }).show();
    }

    private void setupRecyclerView() {
        localPhotoAdapter = new LocalPhotoAdapter(requireContext(), this);
        GridLayoutManager layoutManager = new GridLayoutManager(requireContext(), 4);
        layoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return localPhotoAdapter.getItemViewType(position) == 0 ? 4 : 1;
            }
        });
        binding.rvPhotos.setLayoutManager(layoutManager);
        binding.rvPhotos.setAdapter(localPhotoAdapter);
    }

    private void setupListeners() {
        binding.swipeRefresh.setOnRefreshListener(this::scanDeviceGallery);

        binding.btnSearchHeader.setOnClickListener(v -> {
            startActivity(new Intent(requireContext(), SearchActivity.class));
        });

        binding.btnSort.setOnClickListener(this::showSortPopupMenu);

        binding.chipAll.setOnClickListener(v -> {
            currentViewMode = PhotoTimelineUtils.VIEW_MODE_ALL;
            applyViewModeAndSort();
        });

        binding.chipDays.setOnClickListener(v -> {
            currentViewMode = PhotoTimelineUtils.VIEW_MODE_DAYS;
            applyViewModeAndSort();
        });

        binding.chipMonths.setOnClickListener(v -> {
            currentViewMode = PhotoTimelineUtils.VIEW_MODE_MONTHS;
            applyViewModeAndSort();
        });

        binding.chipYears.setOnClickListener(v -> {
            currentViewMode = PhotoTimelineUtils.VIEW_MODE_YEARS;
            applyViewModeAndSort();
        });

        binding.btnSelectionCancel.setOnClickListener(v -> clearSelection());
        binding.btnSelectionShare.setOnClickListener(v -> shareSelectedPhotos());
        binding.btnSelectionAlbum.setOnClickListener(v -> addSelectedPhotosToAlbum());
        binding.btnSelectionFavorite.setOnClickListener(v -> toggleSelectedFavorites());
        binding.btnSelectionDelete.setOnClickListener(v -> confirmDeleteSelectedLocal());
    }

    private void checkPermissionAndScan() {
        if (!shouldRequestMediaPermissions()) {
            scanDeviceGallery();
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                requestPermissionLauncher.launch(new String[]{Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED});
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissionLauncher.launch(new String[]{Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VIDEO});
            } else {
                requestPermissionLauncher.launch(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE});
            }
        }
    }

    private boolean shouldRequestMediaPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            boolean fullImageAccess = hasPermission(Manifest.permission.READ_MEDIA_IMAGES);
            boolean fullVideoAccess = hasPermission(Manifest.permission.READ_MEDIA_VIDEO);
            boolean selectedAccess = hasPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
            return !selectedAccess && (!fullImageAccess || !fullVideoAccess);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return !hasPermission(Manifest.permission.READ_MEDIA_IMAGES)
                    || !hasPermission(Manifest.permission.READ_MEDIA_VIDEO);
        }
        return !hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE);
    }

    private boolean hasAnyMediaReadPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return hasPermission(Manifest.permission.READ_MEDIA_IMAGES)
                    || hasPermission(Manifest.permission.READ_MEDIA_VIDEO)
                    || hasPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return hasPermission(Manifest.permission.READ_MEDIA_IMAGES)
                    || hasPermission(Manifest.permission.READ_MEDIA_VIDEO);
        }
        return hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE);
    }

    private boolean hasPermission(String permission) {
        return ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void scanDeviceGallery() {
        if (binding == null || scanInProgress) return;
        scanInProgress = true;
        binding.progressBar.setVisibility(View.VISIBLE);
        photoScanner.scanPhotos(requireContext(), new DevicePhotoScanner.ScanCallback() {
            @Override
            public void onScanComplete(List<LocalMediaPhoto> localPhotos) {
                scanInProgress = false;
                if (binding == null || !isAdded()) return;
                binding.progressBar.setVisibility(View.GONE);
                binding.swipeRefresh.setRefreshing(false);

                localDevicePhotos = localPhotos != null ? new ArrayList<>(localPhotos) : new ArrayList<>();
                allDevicePhotos = new ArrayList<>(localDevicePhotos);
                applyViewModeAndSort();
                updateEmptyState();
                loadCloudPhotosForCurrentUser();
            }

            @Override
            public void onScanError(String error) {
                scanInProgress = false;
                if (binding == null || !isAdded()) return;
                binding.progressBar.setVisibility(View.GONE);
                binding.swipeRefresh.setRefreshing(false);
                Toast.makeText(getContext(), error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void uploadPhotoToImgBB(Uri imageUri) {
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null) return;

        binding.progressBar.setVisibility(View.VISIBLE);
        Toast.makeText(getContext(), "Đang tải ảnh lên ImgBB...", Toast.LENGTH_SHORT).show();

        uploadService.uploadImage(requireContext(), imageUri, new ImgBBUploadService.UploadCallback() {
            @Override
            public void onSuccess(String imageUrl) {
                String photoId = UUID.randomUUID().toString();
                String photoName = "Photo_" + System.currentTimeMillis();

                Photo photo = new Photo(photoId, currentUser.getUid(), photoName, imageUrl, System.currentTimeMillis());
                // Keep a device-local link when the picker returned a MediaStore URI; the library
                // can then merge this cloud record with its local tile without matching by name.
                photo.setLocalMediaUri(imageUri.toString());

                repository.savePhoto(photo, new FirestoreRepository.RepositoryCallback<Void>() {
                    @Override
                    public void onSuccess(Void result) {
                        if (binding == null || !isAdded()) return;
                        binding.progressBar.setVisibility(View.GONE);
                        Toast.makeText(getContext(), "Thêm ảnh vào cloud thành công!", Toast.LENGTH_SHORT).show();
                        scanDeviceGallery();
                    }

                    @Override
                    public void onError(String error) {
                        if (binding == null || !isAdded()) return;
                        binding.progressBar.setVisibility(View.GONE);
                        Toast.makeText(getContext(), "Lỗi lưu cloud metadata: " + error, Toast.LENGTH_LONG).show();
                    }
                });
            }

            @Override
            public void onError(String error) {
                if (binding == null || !isAdded()) return;
                binding.progressBar.setVisibility(View.GONE);
                Toast.makeText(getContext(), "Lỗi upload ImgBB: " + error, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showSortPopupMenu(View anchorView) {
        PopupMenu popupMenu = new PopupMenu(requireContext(), anchorView);
        popupMenu.getMenuInflater().inflate(R.menu.options_menu_library, popupMenu.getMenu());

        popupMenu.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.action_slideshow) {
                startSlideshow();
                return true;
            } else if (itemId == R.id.action_backup) {
                startActivity(new Intent(requireContext(), BackupCenterActivity.class));
                return true;
            } else if (itemId == R.id.action_search) {
                startActivity(new Intent(requireContext(), SearchActivity.class));
                return true;
            } else if (itemId == R.id.action_favorites) {
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).selectTab(R.id.nav_favorites);
                }
                return true;
            } else if (itemId == R.id.action_profile) {
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).selectTab(R.id.nav_profile);
                }
                return true;
            } else if (itemId == R.id.action_logout) {
                mAuth.signOut();
                if (getActivity() != null) getActivity().finish();
                return true;
            }
            return false;
        });

        popupMenu.show();
    }

    private void startSlideshow() {
        if (allDevicePhotos.isEmpty()) {
            Toast.makeText(getContext(), "Không có ảnh nào để trình chiếu", Toast.LENGTH_SHORT).show();
            return;
        }

        List<Photo> slideshowList = new ArrayList<>();
        for (LocalMediaPhoto local : allDevicePhotos) {
            slideshowList.add(new Photo(
                    String.valueOf(local.getMediaId()),
                    "local",
                    local.getDisplayName(),
                    local.getContentUri(),
                    local.getDateModified()
            ));
        }

        Intent intent = new Intent(requireContext(), SlideshowActivity.class);
        intent.putExtra("photos", (Serializable) slideshowList);
        startActivity(intent);
    }

    private void applyViewModeAndSort() {
        if (allDevicePhotos == null) allDevicePhotos = new ArrayList<>();
        Collections.sort(allDevicePhotos, (p1, p2) -> Long.compare(p2.getDateModified(), p1.getDateModified()));
        localPhotoAdapter.setPhotoList(allDevicePhotos, currentViewMode);
        Set<String> validKeys = new LinkedHashSet<>();
        for (LocalMediaPhoto photo : allDevicePhotos) validKeys.add(LocalPhotoAdapter.keyFor(photo));
        selectedPhotoKeys.retainAll(validKeys);
        localPhotoAdapter.setSelectedKeys(selectedPhotoKeys);
        renderSelectionMode();
    }

    private void loadCloudPhotosForCurrentUser() {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null || getContext() == null) {
            // Do not retain a previous account's cloud items after logout/account switch.
            displayedCloudUid = null;
            cloudLoadFailed = false;
            if (selectionMode) clearSelection();
            allDevicePhotos = new ArrayList<>(localDevicePhotos);
            applyViewModeAndSort();
            updateEmptyState();
            return;
        }
        final String requestedUid = user.getUid();
        if (!requestedUid.equals(displayedCloudUid)) {
            if (selectionMode) clearSelection();
            allDevicePhotos = new ArrayList<>(localDevicePhotos);
            applyViewModeAndSort();
            updateEmptyState();
        }
        repository.getPhotos(requestedUid, new FirestoreRepository.RepositoryCallback<List<Photo>>() {
            @Override public void onSuccess(List<Photo> cloudPhotos) {
                androidx.fragment.app.FragmentActivity activity = getActivity();
                if (activity == null) return;
                cloudLoadFailed = false;
                List<LocalMediaPhoto> localSnapshot = new ArrayList<>(localDevicePhotos);
                List<Photo> cloudSnapshot = cloudPhotos == null ? new ArrayList<>() : new ArrayList<>(cloudPhotos);
                android.content.Context appContext = requireContext().getApplicationContext();
                cloudMergeExecutor.execute(() -> {
                    List<LocalMediaPhoto> merged = mergeLocalAndCloud(appContext, localSnapshot, cloudSnapshot);
                    activity.runOnUiThread(() -> {
                        FirebaseUser active = mAuth.getCurrentUser();
                        if (binding == null || !isAdded() || active == null || !requestedUid.equals(active.getUid())) return;
                        allDevicePhotos = merged;
                        displayedCloudUid = requestedUid;
                        applyViewModeAndSort();
                        updateEmptyState();
                    });
                });
            }
            @Override public void onError(String error) {
                // Cloud failure must never discard the local MediaStore library.
                FirebaseUser active = mAuth.getCurrentUser();
                if (active == null || !requestedUid.equals(active.getUid())) return;
                cloudLoadFailed = true;
                if (binding != null && isAdded()) {
                    updateEmptyState();
                    if (!allDevicePhotos.isEmpty()) {
                        Snackbar.make(binding.getRoot(), "Ảnh trên thiết bị vẫn hiển thị; chưa tải được thư viện cloud.", Snackbar.LENGTH_LONG)
                                .setAction("Thử lại", v -> loadCloudPhotosForCurrentUser()).show();
                    }
                }
            }
        });
    }

    private List<LocalMediaPhoto> mergeLocalAndCloud(android.content.Context context,
                                                       List<LocalMediaPhoto> localItems,
                                                       List<Photo> cloudItems) {
        Map<String, LocalMediaPhoto> byUri = new HashMap<>();
        Map<Long, LocalMediaPhoto> byMediaId = new HashMap<>();
        List<LocalMediaPhoto> merged = new ArrayList<>();
        for (LocalMediaPhoto source : localItems) {
            LocalMediaPhoto local = copyLocalPhoto(source);
            merged.add(local);
            if (local.getContentUri() != null) byUri.put(local.getContentUri(), local);
            if (local.getMediaId() > 0) byMediaId.put(local.getMediaId(), local);
        }
        Map<String, LocalMediaPhoto> byHash = new HashMap<>();
        boolean cloudHasHashes = false;
        for (Photo cloud : cloudItems) if (cloud.getContentHash() != null && !cloud.getContentHash().isEmpty()) cloudHasHashes = true;
        if (cloudHasHashes) {
            for (LocalMediaPhoto local : merged) {
                String hash = hashUri(context, local.getContentUri());
                if (hash != null) byHash.put(hash, local);
            }
        }
        for (Photo cloud : cloudItems) {
            if (cloud.isTrashed() || cloud.getImageUrl() == null || cloud.getImageUrl().isEmpty()) continue;
            LocalMediaPhoto match = cloud.getLocalMediaUri() == null ? null : byUri.get(cloud.getLocalMediaUri());
            if (match == null && cloud.getLocalMediaId() > 0) match = byMediaId.get(cloud.getLocalMediaId());
            if (match == null && cloud.getContentHash() != null) match = byHash.get(cloud.getContentHash());
            if (match != null) {
                match.setCloudPhoto(cloud);
                match.setCloudPhotoId(cloud.getId());
                match.setCloudUrl(cloud.getImageUrl());
                match.setBackupStatus(LocalMediaPhoto.STATUS_UPLOADED);
            } else {
                LocalMediaPhoto remote = new LocalMediaPhoto();
                remote.setMediaId(-Math.abs((long) cloud.getId().hashCode()));
                remote.setDisplayName(cloud.getName() == null || cloud.getName().isEmpty() ? "Ảnh cloud" : cloud.getName());
                remote.setMimeType("image/*");
                remote.setDateModified(cloud.getCreatedAt());
                remote.setCloudPhoto(cloud);
                remote.setCloudPhotoId(cloud.getId());
                remote.setCloudUrl(cloud.getImageUrl());
                remote.setBackupStatus(LocalMediaPhoto.STATUS_UPLOADED);
                merged.add(remote);
            }
        }
        return merged;
    }

    private LocalMediaPhoto copyLocalPhoto(LocalMediaPhoto source) {
        LocalMediaPhoto copy = new LocalMediaPhoto(source.getMediaId(), source.getContentUri(), source.getDisplayName(),
                source.getMimeType(), source.getSizeBytes(), source.getWidth(), source.getHeight(), source.getDateModified());
        copy.setVideo(source.isVideo());
        copy.setDurationMs(source.getDurationMs());
        copy.setBackupStatus(source.getBackupStatus());
        return copy;
    }

    private String hashUri(android.content.Context context, String uriText) {
        if (uriText == null || uriText.isEmpty()) return null;
        try (InputStream input = context.getContentResolver().openInputStream(Uri.parse(uriText))) {
            if (input == null) return null;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            StringBuilder hash = new StringBuilder(64);
            for (byte value : digest.digest()) hash.append(String.format(java.util.Locale.US, "%02x", value & 0xff));
            return hash.toString();
        } catch (Exception ignored) { return null; }
    }

    private void updateEmptyState() {
        if (allDevicePhotos.isEmpty()) {
            binding.layoutEmpty.setVisibility(View.VISIBLE);
            binding.rvPhotos.setVisibility(View.GONE);
            binding.tvEmptyMessage.setText(cloudLoadFailed
                    ? "Không tải được ảnh cloud. Kiểm tra kết nối rồi thử làm mới."
                    : getString(R.string.empty_photos));
        } else {
            binding.layoutEmpty.setVisibility(View.GONE);
            binding.rvPhotos.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onLocalPhotoClick(LocalMediaPhoto photo, int position) {
        if (selectionMode) {
            togglePhotoSelection(photo);
            return;
        }
        if (photo.isVideo()) {
            Intent intent = new Intent(requireContext(), VideoPlayerActivity.class);
            intent.putExtra("video", photo);
            startActivity(intent);
        } else {
            Photo cloudPhoto = photo.getCloudPhoto() != null ? copyCloudPhoto(photo.getCloudPhoto()) : new Photo(
                    String.valueOf(photo.getMediaId()),
                    "local",
                    photo.getDisplayName(),
                    photo.getContentUri(),
                    photo.getDateModified()
            );
            if (photo.getContentUri() != null && !photo.getContentUri().isEmpty()) cloudPhoto.setImageUrl(photo.getContentUri());
            List<Photo> galleryPhotos = new ArrayList<>();
            for (LocalMediaPhoto item : allDevicePhotos) {
                if (item.isVideo()) continue;
                Photo galleryItem;
                if (item.getCloudPhoto() != null) {
                    galleryItem = copyCloudPhoto(item.getCloudPhoto());
                    if (item.getContentUri() != null && !item.getContentUri().isEmpty()) galleryItem.setImageUrl(item.getContentUri());
                } else {
                    galleryItem = new Photo(String.valueOf(item.getMediaId()), "local", item.getDisplayName(),
                            item.getContentUri(), item.getDateModified());
                    galleryItem.setLocalMediaId(item.getMediaId());
                    galleryItem.setLocalMediaUri(item.getContentUri());
                }
                galleryPhotos.add(galleryItem);
            }
            Intent intent = PhotoDetailActivity.createIntent(requireContext(), cloudPhoto, galleryPhotos);
            startActivity(intent);
        }
    }

    private Photo copyCloudPhoto(Photo source) {
        Photo copy = new Photo(source.getId(), source.getOwnerUid(), source.getName(), source.getImageUrl(), source.getCreatedAt());
        copy.setDisplayUrl(source.getDisplayUrl());
        copy.setFavorite(source.isFavorite());
        copy.setTrashed(source.isTrashed());
        copy.setTrashedAt(source.getTrashedAt());
        copy.setLocalMediaId(source.getLocalMediaId());
        copy.setLocalMediaUri(source.getLocalMediaUri());
        copy.setLocalDateModified(source.getLocalDateModified());
        copy.setAlbumIds(new ArrayList<>(source.getAlbumIds()));
        copy.setStorageProvider(source.getStorageProvider());
        copy.setContentHash(source.getContentHash());
        return copy;
    }

    @Override
    public void onLocalPhotoLongClick(LocalMediaPhoto photo, View view) {
        if (!selectionMode) selectionMode = true;
        if (!selectedPhotoKeys.contains(LocalPhotoAdapter.keyFor(photo))) togglePhotoSelection(photo);
        else renderSelectionMode();
    }

    private void togglePhotoSelection(LocalMediaPhoto photo) {
        String key = LocalPhotoAdapter.keyFor(photo);
        if (!selectedPhotoKeys.add(key)) selectedPhotoKeys.remove(key);
        if (selectedPhotoKeys.isEmpty()) selectionMode = false;
        localPhotoAdapter.setSelectedKeys(selectedPhotoKeys);
        renderSelectionMode();
    }

    private void clearSelection() {
        selectionMode = false;
        selectedPhotoKeys.clear();
        if (localPhotoAdapter != null) localPhotoAdapter.setSelectedKeys(selectedPhotoKeys);
        renderSelectionMode();
    }

    private void renderSelectionMode() {
        if (binding == null) return;
        binding.layoutMultiSelect.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        binding.layoutHeaderActions.setVisibility(selectionMode ? View.GONE : View.VISIBLE);
        binding.tvHeaderTitle.setText(selectionMode ? "Đã chọn " + selectedPhotoKeys.size() : getString(R.string.tab_library));
        boolean anyCloud = false;
        for (LocalMediaPhoto photo : selectedPhotos()) if (photo.getCloudPhoto() != null) anyCloud = true;
        binding.btnSelectionAlbum.setEnabled(anyCloud);
        binding.btnSelectionFavorite.setEnabled(anyCloud);
        binding.btnSelectionAlbum.setAlpha(anyCloud ? 1f : 0.45f);
        binding.btnSelectionFavorite.setAlpha(anyCloud ? 1f : 0.45f);
        boolean allFavorites = anyCloud;
        for (LocalMediaPhoto photo : selectedPhotos()) {
            if (photo.getCloudPhoto() != null && !photo.getCloudPhoto().isFavorite()) allFavorites = false;
        }
        binding.btnSelectionFavorite.setText(allFavorites ? "Bỏ thích" : "Yêu thích");
    }

    private List<LocalMediaPhoto> selectedPhotos() {
        List<LocalMediaPhoto> result = new ArrayList<>();
        for (LocalMediaPhoto photo : allDevicePhotos) {
            if (selectedPhotoKeys.contains(LocalPhotoAdapter.keyFor(photo))) result.add(photo);
        }
        return result;
    }

    private void shareSelectedPhotos() {
        List<Uri> localUris = new ArrayList<>();
        List<String> cloudLinks = new ArrayList<>();
        for (LocalMediaPhoto photo : selectedPhotos()) {
            String local = photo.getContentUri();
            if (local != null && "content".equalsIgnoreCase(Uri.parse(local).getScheme())) localUris.add(Uri.parse(local));
            else if (photo.getCloudUrl() != null && !photo.getCloudUrl().isEmpty()) cloudLinks.add(photo.getCloudUrl());
        }
        if (localUris.isEmpty() && cloudLinks.isEmpty()) {
            Toast.makeText(requireContext(), "Không có ảnh hợp lệ để chia sẻ", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent share;
        if (localUris.isEmpty()) {
            share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_TEXT, android.text.TextUtils.join("\n", cloudLinks));
        } else {
            share = new Intent(Intent.ACTION_SEND_MULTIPLE);
            share.setType("image/*");
            share.putParcelableArrayListExtra(Intent.EXTRA_STREAM, new ArrayList<>(localUris));
            ClipData clip = ClipData.newUri(requireContext().getContentResolver(), "PhotoVault images", localUris.get(0));
            for (int i = 1; i < localUris.size(); i++) clip.addItem(new ClipData.Item(localUris.get(i)));
            share.setClipData(clip);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (!cloudLinks.isEmpty()) share.putExtra(Intent.EXTRA_TEXT, android.text.TextUtils.join("\n", cloudLinks));
        }
        startActivity(Intent.createChooser(share, "Chia sẻ ảnh"));
    }

    private void addSelectedPhotosToAlbum() {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) return;
        repository.getAlbums(user.getUid(), new FirestoreRepository.RepositoryCallback<List<com.example.th6.models.Album>>() {
            @Override public void onSuccess(List<com.example.th6.models.Album> albums) {
                if (binding == null || !isAdded()) return;
                if (albums == null || albums.isEmpty()) {
                    Toast.makeText(requireContext(), "Tạo album trước khi thêm ảnh cloud", Toast.LENGTH_SHORT).show();
                    return;
                }
                String[] names = new String[albums.size()];
                for (int i = 0; i < albums.size(); i++) names[i] = albums.get(i).getName();
                new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                        .setTitle("Thêm ảnh đã sao lưu vào album")
                        .setItems(names, (dialog, which) -> applySelectedAlbum(user.getUid(), albums.get(which).getId()))
                        .setNegativeButton("Hủy", null).show();
            }
            @Override public void onError(String error) {
                if (binding != null && isAdded()) Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void applySelectedAlbum(String uid, String albumId) {
        List<Photo> cloudPhotos = new ArrayList<>();
        for (LocalMediaPhoto item : selectedPhotos()) if (item.getCloudPhoto() != null) cloudPhotos.add(item.getCloudPhoto());
        if (cloudPhotos.isEmpty()) return;
        AtomicInteger remaining = new AtomicInteger(cloudPhotos.size());
        AtomicInteger successes = new AtomicInteger();
        for (Photo photo : cloudPhotos) {
            List<String> ids = new ArrayList<>(photo.getAlbumIds());
            if (!ids.contains(albumId)) ids.add(albumId);
            else { if (remaining.decrementAndGet() == 0) finishAlbumSelection(successes.get(), cloudPhotos.size()); continue; }
            repository.updatePhotoAlbums(uid, photo.getId(), ids, new FirestoreRepository.RepositoryCallback<Void>() {
                @Override public void onSuccess(Void result) { photo.setAlbumIds(ids); successes.incrementAndGet(); finishOneAlbumUpdate(remaining, successes, cloudPhotos.size()); }
                @Override public void onError(String error) { finishOneAlbumUpdate(remaining, successes, cloudPhotos.size()); }
            });
        }
    }

    private void finishOneAlbumUpdate(AtomicInteger remaining, AtomicInteger successes, int total) {
        if (remaining.decrementAndGet() == 0) finishAlbumSelection(successes.get(), total);
    }

    private void finishAlbumSelection(int successes, int total) {
        if (binding == null || !isAdded()) return;
        Toast.makeText(requireContext(), "Đã thêm " + successes + "/" + total + " ảnh cloud vào album", Toast.LENGTH_LONG).show();
        clearSelection();
    }

    private void toggleSelectedFavorites() {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) return;
        List<Photo> cloudPhotos = new ArrayList<>();
        for (LocalMediaPhoto item : selectedPhotos()) if (item.getCloudPhoto() != null) cloudPhotos.add(item.getCloudPhoto());
        if (cloudPhotos.isEmpty()) return;
        AtomicInteger remaining = new AtomicInteger(cloudPhotos.size());
        AtomicInteger successes = new AtomicInteger();
        boolean favoriteValue = cloudPhotos.stream().allMatch(Photo::isFavorite) ? false : true;
        for (Photo photo : cloudPhotos) {
            boolean newFavorite = favoriteValue;
            repository.toggleFavorite(user.getUid(), photo.getId(), newFavorite, new FirestoreRepository.RepositoryCallback<Void>() {
                @Override public void onSuccess(Void result) { photo.setFavorite(newFavorite); successes.incrementAndGet(); finishFavoriteUpdate(remaining, successes, cloudPhotos.size()); }
                @Override public void onError(String error) { finishFavoriteUpdate(remaining, successes, cloudPhotos.size()); }
            });
        }
    }

    private void finishFavoriteUpdate(AtomicInteger remaining, AtomicInteger successes, int total) {
        if (remaining.decrementAndGet() != 0 || binding == null || !isAdded()) return;
        Toast.makeText(requireContext(), "Đã cập nhật yêu thích cho " + successes.get() + "/" + total + " ảnh cloud", Toast.LENGTH_LONG).show();
        clearSelection();
    }

    private void confirmDeleteSelectedLocal() {
        List<Uri> uris = new ArrayList<>();
        int cloudOnly = 0;
        for (LocalMediaPhoto photo : selectedPhotos()) {
            if (photo.getContentUri() != null && "content".equalsIgnoreCase(Uri.parse(photo.getContentUri()).getScheme())) {
                uris.add(Uri.parse(photo.getContentUri()));
            } else cloudOnly++;
        }
        if (uris.isEmpty()) {
            Toast.makeText(requireContext(), "Không có bản local để xóa. Ảnh cloud được giữ nguyên.", Toast.LENGTH_LONG).show();
            return;
        }
        int skippedCloud = cloudOnly;
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Xóa bản local?")
                .setMessage("Xóa " + uris.size() + " ảnh khỏi thiết bị. Ảnh cloud sẽ không bị xóa."
                        + (skippedCloud > 0 ? " " + skippedCloud + " ảnh chỉ có trên cloud sẽ được giữ nguyên." : ""))
                .setPositiveButton("Tiếp tục", (dialog, which) -> requestDeleteLocalUris(uris))
                .setNegativeButton("Hủy", null).show();
    }

    private void requestDeleteLocalUris(List<Uri> uris) {
        pendingDeleteUris = new ArrayList<>(uris);
        pendingDeleteCount = uris.size();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                android.app.PendingIntent request = android.provider.MediaStore.createDeleteRequest(
                        requireContext().getContentResolver(), uris);
                deleteMediaLauncher.launch(new IntentSenderRequest.Builder(request.getIntentSender()).build());
            } catch (Exception error) {
                Toast.makeText(requireContext(), "Không thể mở xác nhận xóa của Android.", Toast.LENGTH_LONG).show();
                pendingDeleteUris.clear();
                pendingDeleteCount = 0;
            }
        } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            deleteUrisOnAndroidQ(uris, true);
        } else {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                writeMediaPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
                return;
            }
            int deleted = deleteUrisDirectly(uris);
            Toast.makeText(requireContext(), "Đã xóa " + deleted + "/" + uris.size() + " ảnh local", Toast.LENGTH_LONG).show();
            clearSelection();
            scanDeviceGallery();
        }
    }

    private void deleteUrisOnAndroidQ(List<Uri> uris, boolean allowPermissionRequest) {
        int deleted = 0;
        for (Uri uri : uris) {
            try {
                if (requireContext().getContentResolver().delete(uri, null, null) > 0) deleted++;
            } catch (android.app.RecoverableSecurityException recoverable) {
                if (allowPermissionRequest) {
                    pendingDeleteUris = new ArrayList<>(uris);
                    pendingDeleteSucceeded += deleted;
                    deleteMediaLauncher.launch(new IntentSenderRequest.Builder(
                            recoverable.getUserAction().getActionIntent().getIntentSender()).build());
                    return;
                }
            } catch (SecurityException ignored) { }
        }
        deleted += pendingDeleteSucceeded;
        pendingDeleteSucceeded = 0;
        pendingDeleteUris.clear();
        pendingDeleteCount = 0;
        Toast.makeText(requireContext(), "Đã xóa " + deleted + "/" + uris.size() + " ảnh local", Toast.LENGTH_LONG).show();
        clearSelection();
        scanDeviceGallery();
    }

    private int deleteUrisDirectly(List<Uri> uris) {
        int deleted = 0;
        for (Uri uri : uris) {
            try { if (requireContext().getContentResolver().delete(uri, null, null) > 0) deleted++; }
            catch (SecurityException ignored) { }
        }
        return deleted;
    }

    private void checkBackupOnboarding() {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user != null && getContext() != null) {
            BackupPreferences prefs = new BackupPreferences(requireContext(), user.getUid());
            if (!prefs.isOnboardingShown()) {
                BackupOnboardingDialog dialog = BackupOnboardingDialog.newInstance(new BackupOnboardingDialog.OnBackupDecisionListener() {
                    @Override
                    public void onEnableBackup(boolean isWifiOnly) {
                        Toast.makeText(getContext(), "Đã bật tự động sao lưu", Toast.LENGTH_SHORT).show();
                        FirebaseUser enabledUser = mAuth.getCurrentUser();
                        if (enabledUser != null && getContext() != null) {
                            BackupWorkScheduler.reconcile(requireContext(), enabledUser.getUid(), true);
                        }
                    }

                    @Override
                    public void onSkipBackup() {
                        Toast.makeText(getContext(), "Đã bỏ qua sao lưu", Toast.LENGTH_SHORT).show();
                        FirebaseUser skippedUser = mAuth.getCurrentUser();
                        if (skippedUser != null && getContext() != null) {
                            BackupWorkScheduler.disable(requireContext(), skippedUser.getUid());
                        }
                    }
                });
                dialog.show(getParentFragmentManager(), "backup_onboarding");
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (hasAnyMediaReadPermission()) scanDeviceGallery();
        else loadCloudPhotosForCurrentUser();
    }

    @Override
    public void onDestroyView() {
        immediateBackupWork = null;
        periodicBackupWork = null;
        super.onDestroyView();
        binding = null;
    }

    @Override public void onDestroy() {
        cloudMergeExecutor.shutdownNow();
        super.onDestroy();
    }
}
