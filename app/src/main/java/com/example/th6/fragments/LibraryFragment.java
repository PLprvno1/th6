package com.example.th6.fragments;

import android.Manifest;
import android.content.Intent;
import android.content.ClipData;
import android.content.Context;
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
import com.example.th6.activities.SmartCleanupActivity;
import com.example.th6.activities.LoginActivity;
import com.example.th6.activities.PhotoDetailActivity;
import com.example.th6.activities.VideoPlayerActivity;
import com.example.th6.activities.SearchActivity;
import com.example.th6.activities.SlideshowActivity;
import com.example.th6.adapters.LocalPhotoAdapter;
import com.example.th6.data.AuthRepository;
import com.example.th6.data.PhotoRepository;
import com.example.th6.data.StorageRepository;
import com.example.th6.databinding.FragmentLibraryBinding;
import com.example.th6.models.LocalMediaPhoto;
import com.example.th6.models.Photo;
import com.example.th6.services.DevicePhotoScanner;
import com.example.th6.services.BackupUploadLedger;
import com.example.th6.services.BackupWorkScheduler;
import com.example.th6.utils.BackupPreferences;
import com.example.th6.utils.PhotoTimelineUtils;
import com.example.th6.utils.MediaTimelineUtils;
import com.example.th6.utils.PhotoVaultTrashCache;
import com.example.th6.utils.PhotoTrashManager;
import io.github.jan.supabase.auth.user.UserInfo;
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

public class LibraryFragment extends Fragment implements LocalPhotoAdapter.OnLocalPhotoClickListener {

    private FragmentLibraryBinding binding;
    private LocalPhotoAdapter localPhotoAdapter;
    private DevicePhotoScanner photoScanner;
    private PhotoRepository repository;
    private StorageRepository storageRepository;
    private AuthRepository authRepository;

    private List<LocalMediaPhoto> allDevicePhotos = new ArrayList<>();
    private List<LocalMediaPhoto> localDevicePhotos = new ArrayList<>();
    private final Set<String> selectedPhotoKeys = new LinkedHashSet<>();
    private boolean selectionMode;
    private boolean cloudLoadFailed;
    private String displayedCloudUid;
    private final ExecutorService cloudMergeExecutor = Executors.newSingleThreadExecutor();
    private int currentViewMode = PhotoTimelineUtils.VIEW_MODE_ALL;
    private int currentSortMode = R.id.sort_date_desc;
    private int currentMediaFilter = MediaTimelineUtils.FILTER_ALL;
    private boolean scanInProgress;
    private boolean manualUploadInProgress;
    private boolean trashOperationRunning;
    private WorkInfo immediateBackupWork;
    private WorkInfo periodicBackupWork;

    private ActivityResultLauncher<String[]> requestPermissionLauncher;
    private ActivityResultLauncher<String> photoPickerLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        currentSortMode = requireContext().getSharedPreferences("photovault_library", Context.MODE_PRIVATE)
                .getInt("sort_mode", R.id.sort_date_desc);
        currentViewMode = requireContext().getSharedPreferences("photovault_library", Context.MODE_PRIVATE)
                .getInt("view_mode", PhotoTimelineUtils.VIEW_MODE_ALL);
        currentMediaFilter = requireContext().getSharedPreferences("photovault_library", Context.MODE_PRIVATE)
                .getInt("media_filter", MediaTimelineUtils.FILTER_ALL);

        authRepository = new AuthRepository();
        repository = new PhotoRepository();
        storageRepository = new StorageRepository();
        photoScanner = new DevicePhotoScanner();
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
                        UserInfo backupUser = authRepository.getCurrentUser();
                        if (backupUser != null && getContext() != null
                                && new BackupPreferences(requireContext(), backupUser.getId()).isBackupEnabled()) {
                            BackupWorkScheduler.reconcile(requireContext(), backupUser.getId(), true);
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
                        uploadPhotoToSupabaseStorage(uri);
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
        setCheckedTimelineChips();
        observeBackupWork();
        checkPermissionAndScan();
        checkBackupOnboarding();
    }

    private void observeBackupWork() {
        UserInfo user = authRepository.getCurrentUser();
        if (user == null) {
            renderBackupStatus();
            return;
        }
        WorkManager workManager = WorkManager.getInstance(requireContext().getApplicationContext());
        String uid = user.getId();
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
            boolean videoUpload = "video".equals(running.getProgress().getString("current_type"));
            binding.tvBackupStatus.setText(videoUpload ? "Đang sao lưu video" : "Đang sao lưu media");
            if (total > 0) {
                binding.tvBackupProgress.setVisibility(View.VISIBLE);
                binding.tvBackupProgress.setText("Đã xử lý " + Math.min(completed, total) + "/" + total + " media");
            } else {
                binding.tvBackupProgress.setVisibility(View.GONE);
            }
            return;
        }
        binding.layoutBackupStatus.setVisibility(View.GONE);
        binding.progressBackupStatus.setVisibility(View.GONE);
        maybeReportPartialCompletion(immediateBackupWork, false);
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
        UserInfo user = authRepository.getCurrentUser();
        if (user == null) return;
        android.content.SharedPreferences noticePrefs = requireContext().getSharedPreferences(
                "backup_status_notices", android.content.Context.MODE_PRIVATE);
        String noticeKey = "last_partial_" + user.getId();
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
            selectTimeline(PhotoTimelineUtils.VIEW_MODE_ALL,currentMediaFilter);
        });

        binding.chipDays.setOnClickListener(v -> {
            selectTimeline(PhotoTimelineUtils.VIEW_MODE_DAYS,currentMediaFilter);
        });

        binding.chipMonths.setOnClickListener(v -> {
            selectTimeline(PhotoTimelineUtils.VIEW_MODE_MONTHS,currentMediaFilter);
        });

        binding.chipYears.setOnClickListener(v -> {
            selectTimeline(PhotoTimelineUtils.VIEW_MODE_YEARS,currentMediaFilter);
        });

        binding.chipMediaAll.setOnClickListener(v -> selectTimeline(currentViewMode,MediaTimelineUtils.FILTER_ALL));
        binding.chipMediaImages.setOnClickListener(v -> selectTimeline(currentViewMode,MediaTimelineUtils.FILTER_IMAGES));
        binding.chipMediaVideos.setOnClickListener(v -> selectTimeline(currentViewMode,MediaTimelineUtils.FILTER_VIDEOS));

        binding.btnSelectionCancel.setOnClickListener(v -> clearSelection());
        binding.btnSelectionShare.setOnClickListener(v -> shareSelectedPhotos());
        binding.btnSelectionAlbum.setOnClickListener(v -> addSelectedPhotosToAlbum());
        binding.btnSelectionFavorite.setOnClickListener(v -> toggleSelectedFavorites());
        binding.btnSelectionDelete.setOnClickListener(v -> confirmMoveSelectedToTrash());
    }

    private void selectTimeline(int viewMode,int mediaFilter) {
        currentViewMode=viewMode; currentMediaFilter=mediaFilter;
        requireContext().getSharedPreferences("photovault_library",Context.MODE_PRIVATE).edit()
                .putInt("view_mode",viewMode).putInt("media_filter",mediaFilter).apply();
        setCheckedTimelineChips(); applyViewModeAndSort(); updateEmptyState();
    }

    private void setCheckedTimelineChips() {
        if(binding==null)return;
        if(currentViewMode==PhotoTimelineUtils.VIEW_MODE_DAYS)binding.chipDays.setChecked(true);
        else if(currentViewMode==PhotoTimelineUtils.VIEW_MODE_MONTHS)binding.chipMonths.setChecked(true);
        else if(currentViewMode==PhotoTimelineUtils.VIEW_MODE_YEARS)binding.chipYears.setChecked(true);
        else binding.chipAll.setChecked(true);
        if(currentMediaFilter==MediaTimelineUtils.FILTER_IMAGES)binding.chipMediaImages.setChecked(true);
        else if(currentMediaFilter==MediaTimelineUtils.FILTER_VIDEOS)binding.chipMediaVideos.setChecked(true);
        else binding.chipMediaAll.setChecked(true);
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
                UserInfo user = authRepository.getCurrentUser();
                allDevicePhotos = user == null
                        ? filterLocalsInTrash(requireContext(), localDevicePhotos, knownTrash(null))
                        : filterLocalsInTrash(requireContext(), localDevicePhotos, knownTrash(user.getId()));
                applyViewModeAndSort();
                updateEmptyState();
                loadCloudPhotosForCurrentUser();
                if (user != null) {
                    // Enqueue only after a successful device scan. WorkManager runs the
                    // existing worker off the UI thread and rechecks the backup queue.
                    BackupWorkScheduler.enqueueIfEligible(requireContext(), user.getId());
                }
            }

            @Override
            public void onScanError(String error) {
                scanInProgress = false;
                if (binding == null || !isAdded()) return;
                binding.progressBar.setVisibility(View.GONE);
                binding.swipeRefresh.setRefreshing(false);
                // A failed scan cannot validate the previous MediaStore snapshot. Drop those
                // local rows so removed files are not presented as if they still existed;
                // the independent cloud query below can still contribute verified cloud rows.
                localDevicePhotos = new ArrayList<>();
                UserInfo user = authRepository.getCurrentUser();
                allDevicePhotos = filterLocalsInTrash(requireContext(), localDevicePhotos,
                        knownTrash(user == null ? null : user.getId()));
                applyViewModeAndSort();
                updateEmptyState();
                loadCloudPhotosForCurrentUser();
                Toast.makeText(getContext(), error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void uploadPhotoToSupabaseStorage(Uri imageUri) {
        if (manualUploadInProgress || binding == null || !isAdded()) return;
        UserInfo currentUser = authRepository.isLoggedIn() ? authRepository.getCurrentUser() : null;
        if (currentUser == null) {
            Toast.makeText(getContext(), "Hãy đăng nhập để tải ảnh lên cloud.", Toast.LENGTH_LONG).show();
            return;
        }
        manualUploadInProgress = true;
        binding.progressBar.setVisibility(View.VISIBLE);
        Context appContext = requireContext().getApplicationContext();
        String mime = appContext.getContentResolver().getType(imageUri);
        if (mime != null && !mime.startsWith("image/")) {
            manualUploadInProgress = false;
            binding.progressBar.setVisibility(View.GONE);
            Toast.makeText(getContext(), "Chỉ có thể tải ảnh lên từ thao tác này.", Toast.LENGTH_LONG).show();
            return;
        }
        if (mime == null) mime = "image/jpeg";
        String extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
        if (extension == null || extension.isEmpty()) extension = "img";
        String identity = currentUser.getId() + "|" + imageUri;
        String stableId = UUID.nameUUIDFromBytes(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        String objectPath = currentUser.getId() + "/photos/manual_" + stableId + "." + extension;
        storageRepository.uploadMediaAsync(appContext, imageUri, objectPath, mime, new StorageRepository.UploadCallback() {
            @Override public void onSuccess(String publicUrl) {
                try (BackupUploadLedger ledger = new BackupUploadLedger(appContext)) {
                    ledger.recordUpload(currentUser.getId(), imageUri.toString(), 0L, publicUrl);
                } catch (RuntimeException ignored) { }
                UserInfo activeUser = authRepository.isLoggedIn() ? authRepository.getCurrentUser() : null;
                if (activeUser == null || !currentUser.getId().equals(activeUser.getId())) {
                    manualUploadInProgress = false;
                    if (binding != null) binding.progressBar.setVisibility(View.GONE);
                    return;
                }
                Photo photo = new Photo();
                photo.setId("manual_" + stableId);
                photo.setOwnerUid(currentUser.getId());
                photo.setName("Photo_" + System.currentTimeMillis());
                photo.setImageUrl(publicUrl);
                photo.setDisplayUrl(publicUrl);
                photo.setCreatedAt(System.currentTimeMillis());
                photo.setMediaType("image");
                photo.setLocalMediaUri(imageUri.toString());
                photo.setStorageProvider("supabase");
                photo.setStoragePath(objectPath);
                repository.savePhotoAsync(photo, new PhotoRepository.Callback<Void>() {
                    @Override public void onSuccess(Void result) {
                        try (BackupUploadLedger ledger = new BackupUploadLedger(appContext)) {
                            ledger.remove(currentUser.getId(), imageUri.toString());
                        } catch (RuntimeException ignored) { }
                        manualUploadInProgress = false;
                        if (binding == null || !isAdded()) return;
                        binding.progressBar.setVisibility(View.GONE);
                        Toast.makeText(getContext(), "Đã tải ảnh lên cloud.", Toast.LENGTH_SHORT).show();
                        scanDeviceGallery();
                    }
                    @Override public void onError(String error) {
                        manualUploadInProgress = false;
                        if (binding == null || !isAdded()) return;
                        binding.progressBar.setVisibility(View.GONE);
                        Toast.makeText(getContext(), "Ảnh đã tải lên Storage nhưng chưa lưu metadata. Hãy chọn lại đúng ảnh này để thử đồng bộ lại.", Toast.LENGTH_LONG).show();
                    }
                });
            }
            @Override public void onError(String error) {
                manualUploadInProgress = false;
                if (binding == null || !isAdded()) return;
                binding.progressBar.setVisibility(View.GONE);
                Toast.makeText(getContext(), error, Toast.LENGTH_LONG).show();
            }
        });
    }
    private void showSortPopupMenu(View anchorView) {
        PopupMenu popupMenu = new PopupMenu(requireContext(), anchorView);
        popupMenu.getMenuInflater().inflate(R.menu.options_menu_library, popupMenu.getMenu());

        popupMenu.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.sort_date_desc || itemId == R.id.sort_date_asc
                    || itemId == R.id.sort_name_asc || itemId == R.id.sort_name_desc) {
                currentSortMode = itemId;
                requireContext().getSharedPreferences("photovault_library", Context.MODE_PRIVATE)
                        .edit().putInt("sort_mode", currentSortMode).apply();
                applyViewModeAndSort();
                return true;
            } else if (itemId == R.id.action_upload_photo) {
                if (!manualUploadInProgress) photoPickerLauncher.launch("image/*");
                return true;
            } else if (itemId == R.id.action_slideshow) {
                startSlideshow();
                return true;
            } else if (itemId == R.id.action_backup) {
                startActivity(new Intent(requireContext(), BackupCenterActivity.class));
                return true;
            } else if (itemId == R.id.action_smart_cleanup) {
                startActivity(new Intent(requireContext(), SmartCleanupActivity.class));
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
                    getActivity().finish();
                });
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
            Photo p = new Photo();
            p.setId(String.valueOf(local.getMediaId()));
            p.setOwnerUid("local");
            p.setName(local.getDisplayName());
            p.setImageUrl(local.getContentUri());
            p.setDisplayUrl(local.getContentUri());
            p.setCreatedAt(local.getTimelineTimestamp());
            slideshowList.add(p);
        }

        Intent intent = new Intent(requireContext(), SlideshowActivity.class);
        intent.putExtra("photos", (Serializable) slideshowList);
        startActivity(intent);
    }

    private void applyViewModeAndSort() {
        if (allDevicePhotos == null) allDevicePhotos = new ArrayList<>();
        List<LocalMediaPhoto> displayPhotos = MediaTimelineUtils.filterAndSort(allDevicePhotos,
                currentMediaFilter, currentViewMode, currentSortMode,
                LocalMediaPhoto::getTimelineTimestamp, LocalMediaPhoto::getDisplayName, LocalMediaPhoto::isVideo);
        localPhotoAdapter.setPhotoList(displayPhotos, currentViewMode);
        Set<String> validKeys = new LinkedHashSet<>();
        for (LocalMediaPhoto photo : allDevicePhotos) validKeys.add(LocalPhotoAdapter.keyFor(photo));
        selectedPhotoKeys.retainAll(validKeys);
        localPhotoAdapter.setSelectedKeys(selectedPhotoKeys);
        renderSelectionMode();
    }

    private void loadCloudPhotosForCurrentUser() {
        UserInfo user = authRepository.getCurrentUser();
        if (user == null || getContext() == null) {
            displayedCloudUid = null;
            cloudLoadFailed = false;
            if (selectionMode) clearSelection();
            allDevicePhotos = filterLocalsInTrash(requireContext(), localDevicePhotos, knownTrash(null));
            applyViewModeAndSort();
            updateEmptyState();
            return;
        }
        final String requestedUid = user.getId();
        if (!requestedUid.equals(displayedCloudUid)) {
            if (selectionMode) clearSelection();
            allDevicePhotos = filterLocalsInTrash(requireContext(), localDevicePhotos, knownTrash(requestedUid));
            applyViewModeAndSort();
            updateEmptyState();
        }
        repository.getPhotosByUserAsync(requestedUid, new PhotoRepository.Callback<List<Photo>>() {
            @Override public void onSuccess(List<Photo> cloudPhotos) {
                androidx.fragment.app.FragmentActivity activity = getActivity();
                if (activity == null) return;
                cloudLoadFailed = false;
                List<LocalMediaPhoto> localSnapshot = new ArrayList<>(localDevicePhotos);
                List<Photo> cloudSnapshot = cloudPhotos == null ? new ArrayList<>() : new ArrayList<>(cloudPhotos);
                android.content.Context appContext = requireContext().getApplicationContext();
                PhotoVaultTrashCache.replace(appContext, requestedUid, cloudSnapshot);
                cloudMergeExecutor.execute(() -> {
                    List<LocalMediaPhoto> merged = mergeLocalAndCloud(appContext, localSnapshot, cloudSnapshot);
                    activity.runOnUiThread(() -> {
                        UserInfo active = authRepository.getCurrentUser();
                        if (binding == null || !isAdded() || active == null || !requestedUid.equals(active.getId())) return;
                        allDevicePhotos = merged;
                        displayedCloudUid = requestedUid;
                        applyViewModeAndSort();
                        updateEmptyState();
                    });
                });
            }
            @Override public void onError(String error) {
                UserInfo active = authRepository.getCurrentUser();
                if (active == null || !requestedUid.equals(active.getId())) return;
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
        Map<String, LocalMediaPhoto> byMediaId = new HashMap<>();
        List<LocalMediaPhoto> merged = new ArrayList<>();
        for (LocalMediaPhoto source : localItems) {
            LocalMediaPhoto local = copyLocalPhoto(source);
            merged.add(local);
            if (local.getContentUri() != null) byUri.put(local.getContentUri(), local);
            if (local.getMediaId() > 0) byMediaId.put(localTypeKey(local) + ":" + local.getMediaId(), local);
        }
        Map<String, LocalMediaPhoto> byHash = new HashMap<>();
        Map<String, String> hashByUri = new HashMap<>();
        boolean cloudHasHashes = false;
        for (Photo cloud : cloudItems) if (cloud.getContentHash() != null && !cloud.getContentHash().isEmpty()) cloudHasHashes = true;
        if (cloudHasHashes) {
            for (LocalMediaPhoto local : merged) {
                String hash = hashUri(context, local.getContentUri());
                if (hash != null) {
                    byHash.put(hash, local);
                    hashByUri.put(local.getContentUri(), hash);
                }
            }
        }
        for (Photo cloud : cloudItems) {
            if (cloud.isTrashed() || cloud.getImageUrl() == null || cloud.getImageUrl().isEmpty()) continue;
            LocalMediaPhoto match = cloud.getLocalMediaUri() == null ? null : byUri.get(cloud.getLocalMediaUri());
            String cloudType = cloudType(cloud);
            if (match != null && !cloudType.equals(localTypeKey(match))) match = null;
            if (match == null && cloud.getLocalMediaId() > 0) {
                match = byMediaId.get(cloudType + ":" + cloud.getLocalMediaId());
            }
            if (match == null && cloud.getContentHash() != null) {
                LocalMediaPhoto hashMatch = byHash.get(cloud.getContentHash());
                if (hashMatch != null && cloudType.equals(localTypeKey(hashMatch))) match = hashMatch;
            }
            if (match != null) {
                match.setCloudPhoto(cloud);
                match.setCloudPhotoId(cloud.getId());
                match.setCloudUrl(cloud.getImageUrl());
                match.setBackupStatus(LocalMediaPhoto.STATUS_UPLOADED);
            } else {
                LocalMediaPhoto remote = new LocalMediaPhoto();
                remote.setMediaId(-Math.abs((long) cloud.getId().hashCode()));
                remote.setDisplayName(cloud.getName() == null || cloud.getName().isEmpty() ? "Ảnh cloud" : cloud.getName());
                boolean cloudVideo = "video".equalsIgnoreCase(cloud.getMediaType());
                remote.setVideo(cloudVideo);
                remote.setMimeType(cloudVideo ? "video/*" : "image/*");
                remote.setDateModified(cloud.getCreatedAt());
                remote.setDateTaken(cloud.getCreatedAt());
                remote.setCloudPhoto(cloud);
                remote.setCloudPhotoId(cloud.getId());
                remote.setCloudUrl(cloud.getImageUrl());
                remote.setBackupStatus(LocalMediaPhoto.STATUS_UPLOADED);
                merged.add(remote);
            }
        }
        List<Photo> trashedCloud = new ArrayList<>();
        for (Photo cloud : cloudItems) if (cloud != null && cloud.isTrashed()) trashedCloud.add(cloud);
        trashedCloud.addAll(PhotoVaultTrashCache.getLocal(context));
        removeLocalsMatchedByTrash(merged, trashedCloud, hashByUri);
        return merged;
    }

    private List<Photo> knownTrash(@Nullable String uid) {
        List<Photo> result = PhotoVaultTrashCache.getLocal(requireContext());
        if (uid != null) result.addAll(PhotoVaultTrashCache.get(requireContext(), uid));
        return result;
    }

    private List<LocalMediaPhoto> filterLocalsInTrash(android.content.Context context,
                                                       List<LocalMediaPhoto> locals,
                                                       List<Photo> trashed) {
        List<LocalMediaPhoto> filtered = new ArrayList<>();
        if (locals == null || locals.isEmpty() || trashed == null || trashed.isEmpty()) {
            if (locals != null) filtered.addAll(locals);
            return filtered;
        }
        Map<String, String> hashes = new HashMap<>();
        boolean needsHash = false;
        for (Photo photo : trashed) {
            if (photo.getContentHash() != null && !photo.getContentHash().isEmpty()) needsHash = true;
        }
        if (needsHash) for (LocalMediaPhoto local : locals) {
            String hash = hashUri(context, local.getContentUri());
            if (hash != null) hashes.put(local.getContentUri(), hash);
        }
        Set<String> hiddenUris = new LinkedHashSet<>();
        Set<String> hiddenIds = new java.util.HashSet<>();
        Set<String> hiddenHashes = new java.util.HashSet<>();
        for (Photo photo : trashed) {
            if (photo.getLocalMediaUri() != null && !photo.getLocalMediaUri().isEmpty()) hiddenUris.add(photo.getLocalMediaUri());
            if (photo.getLocalMediaId() > 0) hiddenIds.add(cloudType(photo) + ":" + photo.getLocalMediaId());
            if (photo.getContentHash() != null && !photo.getContentHash().isEmpty()) {
                hiddenHashes.add(cloudType(photo) + ":" + photo.getContentHash());
            }
        }
        for (LocalMediaPhoto local : locals) {
            boolean hidden = (local.getContentUri() != null && hiddenUris.contains(local.getContentUri()))
                    || (local.getMediaId() > 0 && hiddenIds.contains(localTypeKey(local) + ":" + local.getMediaId()))
                    || (local.getContentUri() != null && hiddenHashes.contains(
                    localTypeKey(local) + ":" + hashes.get(local.getContentUri())));
            if (!hidden) filtered.add(local);
        }
        return filtered;
    }

    private void removeLocalsMatchedByTrash(List<LocalMediaPhoto> merged, List<Photo> trashed,
                                             Map<String, String> hashByUri) {
        if (trashed.isEmpty()) return;
        Set<String> hiddenUris = new LinkedHashSet<>();
        Set<String> hiddenIds = new java.util.HashSet<>();
        Set<String> hiddenHashes = new java.util.HashSet<>();
        for (Photo photo : trashed) {
            if (photo.getLocalMediaUri() != null && !photo.getLocalMediaUri().isEmpty()) hiddenUris.add(photo.getLocalMediaUri());
            if (photo.getLocalMediaId() > 0) hiddenIds.add(cloudType(photo) + ":" + photo.getLocalMediaId());
            if (photo.getContentHash() != null && !photo.getContentHash().isEmpty()) {
                hiddenHashes.add(cloudType(photo) + ":" + photo.getContentHash());
            }
        }
        java.util.Iterator<LocalMediaPhoto> iterator = merged.iterator();
        while (iterator.hasNext()) {
            LocalMediaPhoto item = iterator.next();
            if (item.getCloudPhoto() != null) continue;
            String uri = item.getContentUri();
            if ((uri != null && hiddenUris.contains(uri))
                    || (item.getMediaId() > 0 && hiddenIds.contains(localTypeKey(item) + ":" + item.getMediaId()))
                    || (uri != null && hiddenHashes.contains(localTypeKey(item) + ":" + hashByUri.get(uri)))) iterator.remove();
        }
    }

    private String localTypeKey(LocalMediaPhoto item) {
        return item != null && item.isVideo() ? "video" : "image";
    }

    private String cloudType(Photo item) {
        if (item == null) return "image";
        String type = item.getMediaType();
        if (type != null && type.toLowerCase(java.util.Locale.ROOT).startsWith("video")) return "video";
        String url = item.getImageUrl();
        if (url != null) {
            String path = Uri.parse(url).getPath();
            String extension = path == null ? "" : path.toLowerCase(java.util.Locale.ROOT);
            if (extension.endsWith(".mp4") || extension.endsWith(".mov")
                    || extension.endsWith(".m4v") || extension.endsWith(".webm")
                    || extension.endsWith(".3gp") || extension.endsWith(".mkv")) return "video";
        }
        return "image";
    }

    private LocalMediaPhoto copyLocalPhoto(LocalMediaPhoto source) {
        LocalMediaPhoto copy = new LocalMediaPhoto(source.getMediaId(), source.getContentUri(), source.getDisplayName(),
                source.getMimeType(), source.getSizeBytes(), source.getWidth(), source.getHeight(), source.getDateModified());
        copy.setDateTaken(source.getDateTaken());
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
        boolean noMediaMatches = MediaTimelineUtils.filterAndSort(allDevicePhotos, currentMediaFilter,
                currentViewMode, currentSortMode, LocalMediaPhoto::getTimelineTimestamp,
                LocalMediaPhoto::getDisplayName, LocalMediaPhoto::isVideo).isEmpty();
        if (allDevicePhotos.isEmpty() || noMediaMatches) {
            binding.layoutEmpty.setVisibility(View.VISIBLE);
            binding.rvPhotos.setVisibility(View.GONE);
            binding.tvEmptyMessage.setText(cloudLoadFailed
                    ? "Không tải được ảnh cloud. Kiểm tra kết nối rồi thử làm mới."
                    : allDevicePhotos.isEmpty() ? getString(R.string.empty_photos) : getString(R.string.empty_media_filter));
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
        Photo current = PhotoDetailActivity.photoForLocalMedia(photo);
        List<LocalMediaPhoto> visibleMedia = MediaTimelineUtils.filterAndSort(allDevicePhotos,
                currentMediaFilter, currentViewMode, currentSortMode,
                LocalMediaPhoto::getTimelineTimestamp, LocalMediaPhoto::getDisplayName, LocalMediaPhoto::isVideo);
        List<Photo> collection = new ArrayList<>(visibleMedia.size());
        for (LocalMediaPhoto item : visibleMedia) collection.add(PhotoDetailActivity.photoForLocalMedia(item));
        startActivity(PhotoDetailActivity.createIntent(requireContext(), current, collection));
    }

    private Photo copyCloudPhoto(Photo source) {
        Photo copy = new Photo();
        copy.setId(source.getId());
        copy.setOwnerUid(source.getOwnerUid());
        copy.setName(source.getName());
        copy.setImageUrl(source.getImageUrl());
        copy.setDisplayUrl(source.getDisplayUrl());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setMediaType(source.getMediaType());
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
        UserInfo user = authRepository.getCurrentUser();
        if (user == null) return;
        repository.getAlbumsAsync(user.getId(), new PhotoRepository.Callback<List<com.example.th6.models.Album>>() {
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
                        .setItems(names, (dialog, which) -> applySelectedAlbum(user.getId(), albums.get(which).getId()))
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
            repository.updatePhotoAlbumsAsync(uid, photo.getId(), ids, new PhotoRepository.Callback<Void>() {
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
        UserInfo user = authRepository.getCurrentUser();
        if (user == null) return;
        List<Photo> cloudPhotos = new ArrayList<>();
        for (LocalMediaPhoto item : selectedPhotos()) if (item.getCloudPhoto() != null) cloudPhotos.add(item.getCloudPhoto());
        if (cloudPhotos.isEmpty()) return;
        AtomicInteger remaining = new AtomicInteger(cloudPhotos.size());
        AtomicInteger successes = new AtomicInteger();
        boolean favoriteValue = cloudPhotos.stream().allMatch(Photo::isFavorite) ? false : true;
        for (Photo photo : cloudPhotos) {
            boolean newFavorite = favoriteValue;
            repository.toggleFavoriteAsync(user.getId(), photo.getId(), newFavorite, new PhotoRepository.Callback<Void>() {
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

    private void confirmMoveSelectedToTrash() {
        List<LocalMediaPhoto> selected = new ArrayList<>(selectedPhotos());
        if (selected.isEmpty() || trashOperationRunning) return;
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Chuyển vào thùng rác?")
                .setMessage("Chuyển " + selected.size() + " ảnh/video vào thùng rác PhotoVault. File gốc trên thiết bị sẽ được giữ nguyên.")
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Chuyển vào thùng rác", (dialog, which) -> moveSelectedToTrash(selected))
                .show();
    }

    private void moveSelectedToTrash(List<LocalMediaPhoto> selected) {
        if (trashOperationRunning || selected.isEmpty()) return;
        trashOperationRunning = true;
        binding.btnSelectionDelete.setEnabled(false);
        UserInfo user = authRepository.getCurrentUser();
        moveSelectedToTrashAt(selected, 0, user == null ? null : user.getId(), 0, new ArrayList<>());
    }

    private void moveSelectedToTrashAt(List<LocalMediaPhoto> selected, int index, String uid,
                                       int successes, List<String> failures) {
        if (index >= selected.size()) {
            trashOperationRunning = false;
            if (binding == null || !isAdded()) return;
            binding.btnSelectionDelete.setEnabled(true);
            if (selectedPhotoKeys.isEmpty()) clearSelection(); else renderSelectionMode();
            String summary = "Đã chuyển " + successes + "/" + selected.size() + " mục vào thùng rác.";
            if (failures.isEmpty()) Toast.makeText(requireContext(), summary, Toast.LENGTH_LONG).show();
            else new androidx.appcompat.app.AlertDialog.Builder(requireContext()).setTitle(summary)
                    .setMessage(android.text.TextUtils.join("\n\n", failures))
                    .setPositiveButton("Đóng", null).show();
            scanDeviceGallery();
            return;
        }
        LocalMediaPhoto item = selected.get(index);
        Photo target = photoForTrash(item);
        PhotoTrashManager.moveToTrash(requireContext(), uid, target, new PhotoTrashManager.Callback() {
            @Override public void onSuccess(Photo moved) {
                selectedPhotoKeys.remove(LocalPhotoAdapter.keyFor(item));
                if (binding != null && isAdded()) binding.getRoot().post(() -> moveSelectedToTrashAt(selected, index + 1, uid, successes + 1, failures));
            }
            @Override public void onError(String error) {
                failures.add((item.getDisplayName() == null ? "Mục" : item.getDisplayName()) + ": " + error);
                if (binding != null) binding.getRoot().post(() -> moveSelectedToTrashAt(selected, index + 1, uid, successes, failures));
            }
        });
    }

    private Photo photoForTrash(LocalMediaPhoto item) {
        Photo photo = item.getCloudPhoto() == null ? new Photo() : copyCloudPhoto(item.getCloudPhoto());
        if (item.getCloudPhoto() == null) {
            photo.setId("local:" + item.getMediaId());
            photo.setOwnerUid("local");
            photo.setImageUrl(item.getContentUri() == null ? "" : item.getContentUri());
            photo.setMediaType(item.isVideo() ? "video" : "image");
            photo.setCreatedAt(item.getTimelineTimestamp());
        }
        photo.setName(item.getDisplayName());
        if (item.getContentUri() != null) photo.setLocalMediaUri(item.getContentUri());
        if (item.getMediaId() > 0) photo.setLocalMediaId(item.getMediaId());
        photo.setLocalDateModified(item.getDateModified());
        return photo;
    }

    private void checkBackupOnboarding() {
        UserInfo user = authRepository.getCurrentUser();
        if (user != null && getContext() != null) {
            BackupPreferences prefs = new BackupPreferences(requireContext(), user.getId());
            if (!prefs.isOnboardingShown()) {
                BackupOnboardingDialog dialog = BackupOnboardingDialog.newInstance(new BackupOnboardingDialog.OnBackupDecisionListener() {
                    @Override
                    public void onEnableBackup(boolean isWifiOnly) {
                        Toast.makeText(getContext(), "Đã bật tự động sao lưu", Toast.LENGTH_SHORT).show();
                        UserInfo enabledUser = authRepository.getCurrentUser();
                        if (enabledUser != null && getContext() != null) {
                            BackupWorkScheduler.reconcile(requireContext(), enabledUser.getId(), true);
                        }
                    }

                    @Override
                    public void onSkipBackup() {
                        Toast.makeText(getContext(), "Đã bỏ qua sao lưu", Toast.LENGTH_SHORT).show();
                        UserInfo skippedUser = authRepository.getCurrentUser();
                        if (skippedUser != null && getContext() != null) {
                            BackupWorkScheduler.disable(requireContext(), skippedUser.getId());
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
        if(binding!=null){
            android.content.SharedPreferences prefs=requireContext().getSharedPreferences("photovault_library",Context.MODE_PRIVATE);
            currentSortMode=prefs.getInt("sort_mode",R.id.sort_date_desc);
            currentViewMode=prefs.getInt("view_mode",PhotoTimelineUtils.VIEW_MODE_ALL);
            currentMediaFilter=prefs.getInt("media_filter",MediaTimelineUtils.FILTER_ALL);
            setCheckedTimelineChips(); applyViewModeAndSort(); updateEmptyState();
        }
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


