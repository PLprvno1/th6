package com.example.th6.services;

import android.content.Context;

import com.example.th6.data.FirestoreRepository;
import com.example.th6.models.LocalMediaPhoto;
import com.example.th6.models.Photo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Builds the image-only backup queue from MediaStore and cloud metadata. */
public class PhotoBackupManager {
    public interface ManagerCallback {
        void onStatsReady(BackupStats stats, List<LocalMediaPhoto> allLocalPhotos);
        void onError(String error);
    }

    public static class BackupStats {
        public final int totalLocalPhotos;
        public final int backedUpCount;
        public final int remainingCount;
        public final List<LocalMediaPhoto> pendingPhotos;

        BackupStats(int total, int backedUp, List<LocalMediaPhoto> pending) {
            totalLocalPhotos = total;
            backedUpCount = backedUp;
            remainingCount = pending.size();
            pendingPhotos = pending;
        }
    }

    public void processBackupState(Context context, String uid, ManagerCallback callback) {
        new DevicePhotoScanner().scanImages(context, new DevicePhotoScanner.ScanCallback() {
            @Override public void onScanComplete(List<LocalMediaPhoto> scanned) {
                List<LocalMediaPhoto> images = new ArrayList<>();
                for (LocalMediaPhoto item : scanned) if (!item.isVideo()) images.add(item);
                new FirestoreRepository().getPhotos(uid, new FirestoreRepository.RepositoryCallback<List<Photo>>() {
                    @Override public void onSuccess(List<Photo> cloudPhotos) {
                        buildStats(context, uid, images, cloudPhotos, callback);
                    }
                    @Override public void onError(String error) { callback.onError(error); }
                });
            }
            @Override public void onScanError(String error) { callback.onError(error); }
        });
    }

    private void buildStats(Context context, String uid, List<LocalMediaPhoto> images,
                            List<Photo> cloudPhotos, ManagerCallback callback) {
        Map<String, Photo> byUri = new HashMap<>();
        Map<Long, Photo> byId = new HashMap<>();
        for (Photo photo : cloudPhotos) {
            if (photo.getLocalMediaUri() != null && !photo.getLocalMediaUri().isEmpty()) byUri.put(photo.getLocalMediaUri(), photo);
            if (photo.getLocalMediaId() > 0) byId.put(photo.getLocalMediaId(), photo);
        }
        List<LocalMediaPhoto> pending = new ArrayList<>();
        int backedUp = 0;
        try (BackupUploadLedger ledger = new BackupUploadLedger(context)) {
            for (LocalMediaPhoto local : images) {
                Photo cloud = byUri.get(local.getContentUri());
                if (cloud == null && local.getMediaId() > 0) cloud = byId.get(local.getMediaId());
                if (cloud != null && (cloud.getLocalDateModified() == 0
                        || cloud.getLocalDateModified() == local.getDateModified())) {
                    local.setBackupStatus(LocalMediaPhoto.STATUS_UPLOADED);
                    local.setCloudPhotoId(cloud.getId());
                    local.setCloudUrl(cloud.getImageUrl());
                    backedUp++;
                    continue;
                }
                BackupUploadLedger.Entry entry = ledger.find(uid, local.getContentUri());
                if (entry != null && entry.modified == local.getDateModified()) {
                    local.setBackupStatus(LocalMediaPhoto.STATUS_METADATA_PENDING);
                    local.setCloudUrl(entry.imageUrl);
                } else {
                    if (entry != null) ledger.remove(uid, local.getContentUri());
                    local.setBackupStatus(LocalMediaPhoto.STATUS_PENDING);
                }
                pending.add(local);
            }
        } catch (RuntimeException e) {
            callback.onError("Không đọc được trạng thái backup cục bộ: " + e.getLocalizedMessage());
            return;
        }
        callback.onStatsReady(new BackupStats(images.size(), backedUp, pending), images);
    }
}
