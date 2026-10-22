package com.example.th6.fragments;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.activity.OnBackPressedCallback;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;

import com.example.th6.activities.TrashDetailActivity;
import com.example.th6.adapters.PhotoAdapter;
import com.example.th6.data.AuthRepository;
import com.example.th6.data.PhotoRepository;
import com.example.th6.data.StorageRepository;
import com.example.th6.databinding.FragmentTrashBinding;
import com.example.th6.models.Photo;
import com.example.th6.utils.PhotoVaultTrashCache;
import io.github.jan.supabase.auth.user.UserInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class TrashFragment extends Fragment implements PhotoAdapter.OnPhotoActionListener {
    private enum LocalState { EXISTS, MISSING, UNKNOWN }

    private FragmentTrashBinding binding;
    private PhotoAdapter photoAdapter;
    private PhotoRepository repository;
    private StorageRepository storageRepository;
    private AuthRepository authRepository;
    private final List<Photo> trashedPhotos = new ArrayList<>();
    private final Set<String> selectedIds = new LinkedHashSet<>();
    private final Set<String> cloudDeletedIds = new LinkedHashSet<>();
    private final List<Photo> pendingAndroidDelete = new ArrayList<>();
    private final List<Photo> pendingQDelete = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();
    private final List<String> localNotRestored = new ArrayList<>();
    private ActivityResultLauncher<IntentSenderRequest> deleteMediaLauncher;
    private ActivityResultLauncher<String> writePermissionLauncher;
    private OnBackPressedCallback selectionBackCallback;
    private boolean selectionMode;
    private boolean bulkRunning;
    private boolean waitingForQConsent;
    private int operationSuccesses;
    private int qDeleteIndex;
    private String operationUid;
    private List<Photo> operationItems = new ArrayList<>();

    @Override public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        authRepository = new AuthRepository();
        repository = new PhotoRepository();
        storageRepository = new StorageRepository();
        deleteMediaLauncher = registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(), result -> {
            if (!bulkRunning) return;
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && waitingForQConsent) {
                waitingForQConsent = false;
                if (result.getResultCode() == Activity.RESULT_OK) deleteCurrentQItem(false);
                else {
                    Photo item = pendingQDelete.get(qDeleteIndex);
                    fail(item, "Đã hủy xác nhận MediaStore; local và cloud của mục này vẫn được giữ.");
                    qDeleteIndex++;
                    continueQDeletes();
                }
                return;
            }
            if (result.getResultCode() == Activity.RESULT_OK) {
                List<Photo> confirmed = new ArrayList<>();
                for (Photo item : pendingAndroidDelete) {
                    LocalState state = localState(item);
                    if (state == LocalState.MISSING) confirmed.add(item);
                    else fail(item, state == LocalState.EXISTS
                            ? "MediaStore vẫn báo file local còn tồn tại sau xác nhận."
                            : "Không thể xác minh kết quả xóa local sau xác nhận.");
                }
                deleteCloudQueue(confirmed, 0, this::finishPermanentDelete);
            } else {
                for (Photo item : pendingAndroidDelete) fail(item, "Đã hủy xác nhận MediaStore; local và cloud vẫn được giữ.");
                finishPermanentDelete();
            }
            pendingAndroidDelete.clear();
        });
        writePermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (!bulkRunning) return;
            if (granted) deleteLegacyQueue(0);
            else {
                for (Photo item : pendingQDelete) fail(item, "Chưa cấp quyền xóa local; local và cloud vẫn được giữ.");
                finishPermanentDelete();
            }
        });
    }

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentTrashBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        photoAdapter = new PhotoAdapter(requireContext(), this);
        photoAdapter.setActionsHidden(true);
        binding.rvTrashPhotos.setLayoutManager(new GridLayoutManager(requireContext(), 2));
        binding.rvTrashPhotos.setAdapter(photoAdapter);
        binding.swipeRefresh.setOnRefreshListener(this::loadTrashPhotos);
        binding.btnTrashSelectAll.setOnClickListener(v -> selectAll());
        binding.btnTrashClearSelection.setOnClickListener(v -> clearSelectedItems());
        binding.btnTrashExitSelection.setOnClickListener(v -> exitSelectionMode());
        binding.btnTrashBulkRestore.setOnClickListener(v -> startBulkRestore());
        binding.btnTrashBulkDelete.setOnClickListener(v -> confirmBulkDelete());
        selectionBackCallback = new OnBackPressedCallback(false) {
            @Override public void handleOnBackPressed() {
                if (bulkRunning) showMessage("Đang xử lý các mục đã chọn. Vui lòng đợi hoàn tất.");
                else exitSelectionMode();
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), selectionBackCallback);
        loadTrashPhotos();
    }

    private void loadTrashPhotos() {
        UserInfo user = authRepository.getCurrentUser();
        if (binding == null || bulkRunning) return;
        if (user == null) { setLoadedTrash(null); return; }
        binding.progressBar.setVisibility(View.VISIBLE);
        repository.getPhotosByUserAsync(user.getId(), new PhotoRepository.Callback<List<Photo>>() {
            @Override public void onSuccess(List<Photo> photos) {
                if (binding == null || !isAdded()) return;
                binding.progressBar.setVisibility(View.GONE);
                binding.swipeRefresh.setRefreshing(false);
                UserInfo active = authRepository.getCurrentUser();
                if (active == null || !user.getId().equals(active.getId())) return;
                setLoadedTrash(photos);
            }
            @Override public void onError(String error) {
                if (binding == null || !isAdded()) return;
                binding.progressBar.setVisibility(View.GONE);
                binding.swipeRefresh.setRefreshing(false);
                appendLocalTrash();
                Toast.makeText(getContext(), "Không tải được cloud Trash: " + error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setLoadedTrash(@Nullable List<Photo> cloudPhotos) {
        trashedPhotos.clear();
        UserInfo user = authRepository.getCurrentUser();
        if (cloudPhotos != null && user != null) for (Photo item : cloudPhotos) {
            if (item.isTrashed() && user.getId().equals(item.getOwnerUid())) trashedPhotos.add(item);
        }
        for (Photo local : PhotoVaultTrashCache.getLocal(requireContext())) {
            if (local.isTrashed()) trashedPhotos.add(local);
        }
        Set<String> valid = new LinkedHashSet<>();
        for (Photo item : trashedPhotos) valid.add(PhotoAdapter.photoKey(item));
        selectedIds.retainAll(valid);
        if (selectionMode && selectedIds.isEmpty()) selectionMode = false;
        updateSelectionUi();
        syncAdapter();
        updateEmptyState();
    }

    private void appendLocalTrash() {
        Set<String> keys = new LinkedHashSet<>();
        for (Photo photo : trashedPhotos) keys.add(PhotoAdapter.photoKey(photo));
        for (Photo local : PhotoVaultTrashCache.getLocal(requireContext())) {
            String key = PhotoAdapter.photoKey(local);
            if (local.isTrashed() && keys.add(key)) trashedPhotos.add(local);
        }
        syncAdapter();
        updateEmptyState();
    }
    private void updateEmptyState() {
        if (binding == null) return;
        boolean empty = trashedPhotos.isEmpty();
        binding.layoutEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.rvTrashPhotos.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void syncAdapter() {
        if (photoAdapter == null) return;
        photoAdapter.setPhotos(new ArrayList<>(trashedPhotos));
        photoAdapter.setSelectionState(selectionMode, selectedIds);
    }

    @Override public void onPhotoClick(Photo photo) {
        if (bulkRunning) return;
        if (selectionMode) toggleSelection(photo);
        else startActivity(TrashDetailActivity.createIntent(requireContext(), photo));
    }

    @Override public void onPhotoLongClick(Photo photo, View view) {
        if (bulkRunning) return;
        if (!selectionMode) selectionMode = true;
        toggleSelection(photo);
    }

    @Override public void onFavoriteToggle(Photo photo) { }

    private void toggleSelection(Photo photo) {
        String key = PhotoAdapter.photoKey(photo);
        if (!selectedIds.add(key)) selectedIds.remove(key);
        if (selectedIds.isEmpty()) selectionMode = false;
        updateSelectionUi();
        photoAdapter.setSelectionState(selectionMode, selectedIds);
    }

    private void selectAll() {
        if (bulkRunning) return;
        selectionMode = true;
        selectedIds.clear();
        for (Photo item : trashedPhotos) selectedIds.add(PhotoAdapter.photoKey(item));
        updateSelectionUi();
        photoAdapter.setSelectionState(true, selectedIds);
    }

    private void clearSelectedItems() {
        if (bulkRunning) return;
        selectedIds.clear();
        updateSelectionUi();
        photoAdapter.setSelectionState(selectionMode, selectedIds);
    }

    private void exitSelectionMode() {
        if (bulkRunning) return;
        selectionMode = false;
        selectedIds.clear();
        updateSelectionUi();
        photoAdapter.setSelectionState(false, selectedIds);
    }

    private void updateSelectionUi() {
        if (binding == null) return;
        binding.layoutTrashSelection.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        binding.layoutTrashBulkActions.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        if (selectionBackCallback != null) selectionBackCallback.setEnabled(selectionMode);
        binding.tvTrashSelectedCount.setText("Đã chọn " + selectedIds.size());
        binding.swipeRefresh.setEnabled(!selectionMode && !bulkRunning);
        binding.btnTrashSelectAll.setEnabled(!bulkRunning && selectedIds.size() != trashedPhotos.size());
        binding.btnTrashClearSelection.setEnabled(!bulkRunning && !selectedIds.isEmpty());
        binding.btnTrashExitSelection.setEnabled(!bulkRunning);
        binding.btnTrashBulkRestore.setEnabled(!bulkRunning && !selectedIds.isEmpty());
        binding.btnTrashBulkDelete.setEnabled(!bulkRunning && !selectedIds.isEmpty());
    }

    private List<Photo> selectedPhotos() {
        List<Photo> result = new ArrayList<>();
        for (Photo item : trashedPhotos) if (selectedIds.contains(PhotoAdapter.photoKey(item))) result.add(item);
        return result;
    }

    private void startBulkRestore() {
        if (bulkRunning || selectedIds.isEmpty()) return;
        UserInfo user = authRepository.getCurrentUser();
        operationUid = user == null ? null : user.getId();
        operationItems = selectedPhotos();
        failures.clear();
        localNotRestored.clear();
        operationSuccesses = 0;
        bulkRunning = true;
        updateSelectionUi();
        restoreNext(0);
    }

    private void restoreNext(int index) {
        if (index >= operationItems.size()) { finishRestore(); return; }
        Photo item = operationItems.get(index);
        if ("local".equals(item.getOwnerUid())) {
            PhotoVaultTrashCache.remove(requireContext().getApplicationContext(), "local", item.getId());
            operationSuccesses++;
            trashedPhotos.remove(item);
            selectedIds.remove(PhotoAdapter.photoKey(item));
            syncAdapter();
            restoreNext(index + 1);
            return;
        }
        if (operationUid == null || !operationUid.equals(item.getOwnerUid())) {
            fail(item, "Mục cloud không thuộc tài khoản hiện tại; không thay đổi dữ liệu.");
            restoreNext(index + 1);
            return;
        }
        LocalState local = localState(item);
        if (local != LocalState.EXISTS && item.getLocalMediaUri() != null && !item.getLocalMediaUri().isBlank()) {
            localNotRestored.add(displayName(item) + (local == LocalState.MISSING
                    ? ": file local không còn trong MediaStore; chỉ khôi phục metadata cloud."
                    : ": không xác minh được file local; chỉ khôi phục metadata cloud."));
        }
        repository.moveToTrashAsync(operationUid, item.getId(), false, new PhotoRepository.Callback<Void>() {
            @Override public void onSuccess(Void result) {
                PhotoVaultTrashCache.remove(requireContext().getApplicationContext(), operationUid, item.getId());
                operationSuccesses++;
                trashedPhotos.remove(item);
                selectedIds.remove(PhotoAdapter.photoKey(item));
                syncAdapter();
                restoreNext(index + 1);
            }
            @Override public void onError(String error) {
                fail(item, "Khôi phục cloud thất bại: " + error);
                restoreNext(index + 1);
            }
        });
    }
    private void finishRestore() {
        bulkRunning = false;
        if (selectedIds.isEmpty()) selectionMode = false;
        updateSelectionUi();
        syncAdapter();
        updateEmptyState();
        showBatchSummary("Khôi phục", localNotRestored);
    }

    private void confirmBulkDelete() {
        if (bulkRunning || selectedIds.isEmpty()) return;
        List<Photo> chosen = selectedPhotos();
        int local = 0, cloud = 0, both = 0, unknown = 0, unsupportedCloud = 0;
        for (Photo item : chosen) {
            LocalState state = localState(item);
            boolean hasCloud = hasSupportedCloudSource(item);
            if (state == LocalState.EXISTS) local++;
            if (hasCloud) cloud++;
            if (state == LocalState.EXISTS && hasCloud) both++;
            if (state == LocalState.UNKNOWN) unknown++;
            if (!"local".equals(item.getOwnerUid()) && !hasCloud) unsupportedCloud++;
        }
        String message = "Đã chọn " + chosen.size() + " mục: " + local + " có bản local, " + cloud + " có bản cloud, " + both + " có cả hai."
                + (unknown > 0 ? " Không xác minh được nguồn local của " + unknown + " mục; các mục đó sẽ được giữ lại nếu không thể kiểm tra an toàn." : "")
                + (unsupportedCloud > 0 ? " Không xác định được đường dẫn cloud có thể xóa cho " + unsupportedCloud + " mục; các mục đó sẽ được giữ nguyên." : "")
                + "\nXóa vĩnh viễn không thể hoàn tác.";
        new AlertDialog.Builder(requireContext()).setTitle("Xóa vĩnh viễn các mục đã chọn?")
                .setMessage(message).setNegativeButton("Hủy", null)
                .setPositiveButton("Xóa vĩnh viễn", (dialog, which) -> startBulkDelete(chosen)).show();
    }

    private void startBulkDelete(List<Photo> chosen) {
        if (bulkRunning) return;
        UserInfo user = authRepository.getCurrentUser();
        operationUid = user == null ? null : user.getId();
        operationItems = chosen;
        pendingAndroidDelete.clear();
        pendingQDelete.clear();
        failures.clear();
        operationSuccesses = 0;
        bulkRunning = true;
        updateSelectionUi();

        List<Photo> cloudOnlyOrLocalMissing = new ArrayList<>();
        List<Photo> validLocal = new ArrayList<>();
        for (Photo item : chosen) {
            boolean localOnly = "local".equals(item.getOwnerUid());
            if (localOnly) {
                LocalState state = localState(item);
                if (state == LocalState.EXISTS) validLocal.add(item);
                else if (state == LocalState.MISSING) cloudOnlyOrLocalMissing.add(item);
                else fail(item, "Không thể xác minh URI local; mục được giữ trong thùng rác.");
                continue;
            }
            if (operationUid == null || !operationUid.equals(item.getOwnerUid())) {
                fail(item, "Mục không thuộc tài khoản hiện tại; không thay đổi dữ liệu.");
                continue;
            }
            if (!hasSupportedCloudSource(item)) {
                fail(item, "Không có đường dẫn Supabase Storage có thể xóa an toàn; metadata được giữ.");
                continue;
            }
            LocalState state = localState(item);
            if (state == LocalState.EXISTS) validLocal.add(item);
            else if (state == LocalState.MISSING) cloudOnlyOrLocalMissing.add(item);
            else fail(item, "Không thể xác minh URI local; mục được giữ để tránh xóa một phần ngoài ý muốn.");
        }
        deleteCloudQueue(cloudOnlyOrLocalMissing, 0, () -> processLocalDeleteQueue(validLocal));
    }
    private void processLocalDeleteQueue(List<Photo> localItems) {
        if (localItems.isEmpty()) { finishPermanentDelete(); return; }
        pendingAndroidDelete.clear();
        pendingAndroidDelete.addAll(localItems);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            List<Uri> uris = new ArrayList<>();
            for (Photo item : localItems) uris.add(Uri.parse(item.getLocalMediaUri()));
            try {
                android.app.PendingIntent request = MediaStore.createDeleteRequest(requireContext().getContentResolver(), uris);
                deleteMediaLauncher.launch(new IntentSenderRequest.Builder(request.getIntentSender()).build());
            } catch (Exception e) {
                for (Photo item : localItems) fail(item, "Không mở được xác nhận xóa MediaStore: " + e.getMessage());
                pendingAndroidDelete.clear();
                finishPermanentDelete();
            }
        } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            pendingQDelete.clear();
            pendingQDelete.addAll(localItems);
            qDeleteIndex = 0;
            continueQDeletes();
        } else if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingQDelete.clear();
            pendingQDelete.addAll(localItems);
            writePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        } else {
            pendingQDelete.clear();
            pendingQDelete.addAll(localItems);
            deleteLegacyQueue(0);
        }
    }

    private void continueQDeletes() {
        if (qDeleteIndex >= pendingQDelete.size()) { finishPermanentDelete(); return; }
        deleteCurrentQItem(true);
    }

    private void deleteCurrentQItem(boolean allowConsent) {
        Photo item = pendingQDelete.get(qDeleteIndex);
        Uri uri = Uri.parse(item.getLocalMediaUri());
        try {
            int rows = requireContext().getContentResolver().delete(uri, null, null);
            if (rows > 0 || localState(item) == LocalState.MISSING) {
                deleteCloudQueue(Collections.singletonList(item), 0, () -> { qDeleteIndex++; continueQDeletes(); });
            } else {
                fail(item, "MediaStore không xác nhận đã xóa local.");
                qDeleteIndex++;
                continueQDeletes();
            }
        } catch (android.app.RecoverableSecurityException recoverable) {
            if (!allowConsent) {
                fail(item, "Android chưa cấp quyền xóa local sau xác nhận.");
                qDeleteIndex++;
                continueQDeletes();
                return;
            }
            try {
                waitingForQConsent = true;
                deleteMediaLauncher.launch(new IntentSenderRequest.Builder(recoverable.getUserAction().getActionIntent().getIntentSender()).build());
            } catch (Exception e) {
                fail(item, "Không mở được xác nhận xóa local: " + e.getMessage());
                qDeleteIndex++;
                continueQDeletes();
            }
        } catch (Exception e) {
            fail(item, "Lỗi MediaStore: " + e.getMessage());
            qDeleteIndex++;
            continueQDeletes();
        }
    }

    private void deleteLegacyQueue(int index) {
        if (index >= pendingQDelete.size()) { finishPermanentDelete(); return; }
        Photo item = pendingQDelete.get(index);
        try {
            int rows = requireContext().getContentResolver().delete(Uri.parse(item.getLocalMediaUri()), null, null);
            if (rows > 0 || localState(item) == LocalState.MISSING) {
                deleteCloudQueue(Collections.singletonList(item), 0, () -> deleteLegacyQueue(index + 1));
            } else {
                fail(item, "MediaStore không xác nhận đã xóa local.");
                deleteLegacyQueue(index + 1);
            }
        } catch (Exception e) {
            fail(item, "Lỗi MediaStore: " + e.getMessage());
            deleteLegacyQueue(index + 1);
        }
    }

    private void deleteCloudQueue(List<Photo> items, int index, Runnable done) {
        if (index >= items.size()) { done.run(); return; }
        Photo item = items.get(index);
        if ("local".equals(item.getOwnerUid())) {
            PhotoVaultTrashCache.remove(requireContext().getApplicationContext(), "local", item.getId());
            operationSuccesses++;
            trashedPhotos.remove(item);
            selectedIds.remove(PhotoAdapter.photoKey(item));
            syncAdapter();
            deleteCloudQueue(items, index + 1, done);
            return;
        }
        if (operationUid == null || !operationUid.equals(item.getOwnerUid())) {
            fail(item, "Mục không thuộc tài khoản hiện tại; không thay đổi dữ liệu.");
            deleteCloudQueue(items, index + 1, done);
            return;
        }
        if (!hasSupportedCloudSource(item)) {
            fail(item, "Không có đường dẫn Supabase Storage có thể xóa an toàn; metadata được giữ.");
            deleteCloudQueue(items, index + 1, done);
            return;
        }
        if (cloudDeletedIds.contains(item.getId())) {
            deleteCloudMetadata(item, () -> deleteCloudQueue(items, index + 1, done));
            return;
        }
        String path = cloudPath(item);
        storageRepository.deleteImageAsync(path, new StorageRepository.DeleteCallback() {
            @Override public void onSuccess() {
                cloudDeletedIds.add(item.getId());
                deleteCloudMetadata(item, () -> deleteCloudQueue(items, index + 1, done));
            }
            @Override public void onError(String error) {
                fail(item, "Xóa cloud Storage thất bại; metadata được giữ: " + error);
                deleteCloudQueue(items, index + 1, done);
            }
        });
    }

    private void deleteCloudMetadata(Photo item, Runnable done) {
        repository.deletePhotoAsync(operationUid, item.getId(), new PhotoRepository.Callback<Void>() {
            @Override public void onSuccess(Void result) {
                PhotoVaultTrashCache.remove(requireContext().getApplicationContext(), operationUid, item.getId());
                operationSuccesses++;
                trashedPhotos.remove(item);
                selectedIds.remove(PhotoAdapter.photoKey(item));
                syncAdapter();
                done.run();
            }
            @Override public void onError(String error) {
                fail(item, "Cloud Storage đã xóa nhưng metadata còn lại; có thể thử đồng bộ lại: " + error);
                done.run();
            }
        });
    }

    private void finishPermanentDelete() {
        bulkRunning = false;
        if (selectedIds.isEmpty()) selectionMode = false;
        updateSelectionUi();
        syncAdapter();
        updateEmptyState();
        showBatchSummary("Xóa vĩnh viễn", null);
    }

    private boolean hasSupportedCloudSource(Photo item) {
        return "supabase".equalsIgnoreCase(item.getStorageProvider()) && !cloudPath(item).isEmpty();
    }

    private String cloudPath(Photo item) {
        String path = item.getStoragePath();
        if (path == null || path.isBlank()) path = storageRepository.objectPathFromPublicUrl(item.getImageUrl());
        return path == null ? "" : path;
    }

    private LocalState localState(Photo item) {
        String text = item.getLocalMediaUri();
        if (text == null || text.isBlank()) return LocalState.MISSING;
        Uri uri;
        try { uri = Uri.parse(text); }
        catch (Exception e) { return LocalState.UNKNOWN; }
        if (!"content".equalsIgnoreCase(uri.getScheme()) || uri.getAuthority() == null || !uri.getAuthority().contains("media")) return LocalState.UNKNOWN;
        try (android.database.Cursor cursor = requireContext().getContentResolver().query(uri, new String[]{MediaStore.MediaColumns._ID}, null, null, null)) {
            if (cursor == null) return LocalState.UNKNOWN;
            return cursor.moveToFirst() ? LocalState.EXISTS : LocalState.MISSING;
        } catch (Exception e) { return LocalState.UNKNOWN; }
    }

    private void fail(Photo item, String reason) { failures.add(displayName(item) + ": " + reason); }
    private String displayName(Photo item) { return item.getName() == null || item.getName().isBlank() ? item.getId() : item.getName(); }

    private void showBatchSummary(String action, List<String> extra) {
        List<String> details = new ArrayList<>();
        if (extra != null) details.addAll(extra);
        details.addAll(failures);
        int total = operationItems.size();
        int failed = Math.max(0, total - operationSuccesses);
        String summary = action + " hoàn tất: " + operationSuccesses + "/" + total + " mục thành công, " + failed + " mục cần xử lý lại.";
        if (details.isEmpty()) Toast.makeText(getContext(), summary, Toast.LENGTH_LONG).show();
        else new AlertDialog.Builder(requireContext()).setTitle(summary)
                .setMessage(android.text.TextUtils.join("\n\n", details))
                .setPositiveButton("Đóng", null).show();
    }

    private void showMessage(String message) {
        if (getContext() != null) Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();
    }

    @Override public void onResume() { super.onResume(); loadTrashPhotos(); }

    @Override public void onDestroyView() { super.onDestroyView(); binding = null; }
}
