package com.example.th6.services;

import android.content.Context;

import com.example.th6.data.PhotoRepository;
import com.example.th6.models.LocalMediaPhoto;
import com.example.th6.models.Photo;
import com.example.th6.utils.PhotoVaultTrashCache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Builds the image/video backup queue from MediaStore and cloud metadata. */
public class PhotoBackupManager {
    public interface ManagerCallback {
        void onStatsReady(BackupStats stats, List<LocalMediaPhoto> allLocalPhotos);
        void onError(String error);
    }

    public static class BackupStats {
        public final int totalLocalPhotos;
        public final int backedUpCount;
        public final int remainingCount;
        public final int totalImages;
        public final int backedUpImages;
        public final int pendingImages;
        public final int totalVideos;
        public final int backedUpVideos;
        public final int pendingVideos;
        public final List<LocalMediaPhoto> pendingPhotos;

        BackupStats(int total, int backedUp, List<LocalMediaPhoto> pending,
                    int totalImages, int backedUpImages, int pendingImages,
                    int totalVideos, int backedUpVideos, int pendingVideos) {
            totalLocalPhotos = total;
            backedUpCount = backedUp;
            remainingCount = pending.size();
            this.totalImages = totalImages;
            this.backedUpImages = backedUpImages;
            this.pendingImages = pendingImages;
            this.totalVideos = totalVideos;
            this.backedUpVideos = backedUpVideos;
            this.pendingVideos = pendingVideos;
            pendingPhotos = pending;
        }
    }

    public void processBackupState(Context context, String uid, ManagerCallback callback) {
        new DevicePhotoScanner().scanPhotos(context, new DevicePhotoScanner.ScanCallback() {
            @Override public void onScanComplete(List<LocalMediaPhoto> scanned) {
                new PhotoRepository().getPhotosByUserAsync(uid, new PhotoRepository.Callback<List<Photo>>() {
                    @Override public void onSuccess(List<Photo> cloudPhotos) {
                        buildStats(context, uid, scanned, cloudPhotos, callback);
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
        Map<String, Photo> byHash = new HashMap<>();
        java.util.Set<String> trashedUris = new java.util.HashSet<>();
        java.util.Set<Long> trashedIds = new java.util.HashSet<>();
        java.util.Set<String> trashedHashes = new java.util.HashSet<>();
        boolean cloudHasHashes = false;
        for (Photo photo : cloudPhotos) {
            if (photo.getLocalMediaUri() != null && !photo.getLocalMediaUri().isEmpty()) byUri.put(photo.getLocalMediaUri(), photo);
            if (photo.isTrashed()) {
                if (photo.getLocalMediaUri() != null && !photo.getLocalMediaUri().isEmpty()) trashedUris.add(photo.getLocalMediaUri());
                if (photo.getLocalMediaId() > 0) trashedIds.add(photo.getLocalMediaId());
                if (photo.getContentHash() != null && !photo.getContentHash().isEmpty()) trashedHashes.add(photo.getContentHash());
            }
            if (photo.getContentHash() != null && !photo.getContentHash().isEmpty()) {
                cloudHasHashes = true;
                byHash.put(photo.getContentHash(), photo);
            }
        }
        List<LocalMediaPhoto> pending = new ArrayList<>();
        java.util.Set<String> locallyTrashedUris = new java.util.HashSet<>();
        java.util.Set<Long> locallyTrashedIds = new java.util.HashSet<>();
        for (Photo trashed : PhotoVaultTrashCache.getLocal(context)) {
            if (trashed.getLocalMediaUri() != null && !trashed.getLocalMediaUri().isEmpty()) {
                locallyTrashedUris.add(trashed.getLocalMediaUri());
            }
            if (trashed.getLocalMediaId() > 0) locallyTrashedIds.add(trashed.getLocalMediaId());
        }
        for (Photo trashed : PhotoVaultTrashCache.get(context, uid)) {
            if (trashed.getLocalMediaUri() != null && !trashed.getLocalMediaUri().isEmpty()) trashedUris.add(trashed.getLocalMediaUri());
            if (trashed.getLocalMediaId() > 0) trashedIds.add(trashed.getLocalMediaId());
            if (trashed.getContentHash() != null && !trashed.getContentHash().isEmpty()) trashedHashes.add(trashed.getContentHash());
        }
        int backedUp = 0;
        int totalImages = 0, backedImages = 0, pendingImages = 0;
        int totalVideos = 0, backedVideos = 0, pendingVideos = 0;
        try (BackupUploadLedger ledger = new BackupUploadLedger(context)) {
            for (LocalMediaPhoto local : images) {
                if ((local.getContentUri() != null && locallyTrashedUris.contains(local.getContentUri()))
                        || (local.getMediaId() > 0 && locallyTrashedIds.contains(local.getMediaId()))) continue;
                Photo cloud = byUri.get(local.getContentUri());
                if ((local.getContentUri() != null && trashedUris.contains(local.getContentUri()))
                        || (local.getMediaId() > 0 && trashedIds.contains(local.getMediaId()))) continue;
                String localHash = null;
                if (cloud == null && (cloudHasHashes || !trashedHashes.isEmpty())) {
                    localHash = hashUri(context, local.getContentUri());
                    if (localHash != null && trashedHashes.contains(localHash)) continue;
                }
                if (local.isVideo()) totalVideos++; else totalImages++;
                if (cloud == null && cloudHasHashes) {
                    if (localHash == null) localHash = hashUri(context, local.getContentUri());
                    if (localHash != null) cloud = byHash.get(localHash);
                }
                if (cloud != null) {
                    local.setCloudPhotoId(cloud.getId());
                    local.setCloudPhoto(cloud);
                }
                if (cloud != null && cloud.isTrashed()) {
                    // A trashed cloud row is a known backup target, not a new upload candidate.
                    local.setBackupStatus(LocalMediaPhoto.STATUS_UPLOADED);
                    backedUp++;
                    if (local.isVideo()) backedVideos++; else backedImages++;
                    continue;
                }
                boolean verifiedCloudCopy = false;
                if (cloud != null && cloud.getLocalDateModified() > 0) {
                    verifiedCloudCopy = cloud.getLocalDateModified() == local.getDateModified();
                } else if (cloud != null && cloud.getContentHash() != null
                        && !cloud.getContentHash().isEmpty()) {
                    // A URI match alone is not enough when the cloud row lacks a revision.
                    // Compare its stored digest so edits to a local file are not mistaken
                    // for an already completed backup.
                    if (localHash == null) localHash = hashUri(context, local.getContentUri());
                    verifiedCloudCopy = localHash != null && localHash.equals(cloud.getContentHash());
                }
                if (cloud != null && verifiedCloudCopy) {
                    local.setBackupStatus(LocalMediaPhoto.STATUS_UPLOADED);
                    local.setCloudPhotoId(cloud.getId());
                    local.setCloudUrl(cloud.getImageUrl());
                    backedUp++;
                    if (local.isVideo()) backedVideos++; else backedImages++;
                    continue;
                }
                BackupUploadLedger.Entry entry = ledger.find(uid, local.getContentUri());
                if (entry != null && (entry.modified == 0 || entry.modified == local.getDateModified())) {
                    local.setBackupStatus(LocalMediaPhoto.STATUS_METADATA_PENDING);
                    local.setCloudUrl(entry.imageUrl);
                } else {
                    if (entry != null) ledger.remove(uid, local.getContentUri());
                    local.setBackupStatus(LocalMediaPhoto.STATUS_PENDING);
                }
                pending.add(local);
                if (local.isVideo()) pendingVideos++; else pendingImages++;
            }
        } catch (RuntimeException e) {
            callback.onError("Không đọc được trạng thái backup cục bộ: " + e.getLocalizedMessage());
            return;
        }
        callback.onStatsReady(new BackupStats(totalImages + totalVideos, backedUp, pending,
                totalImages, backedImages, pendingImages, totalVideos, backedVideos, pendingVideos), images);
    }

    private String hashUri(Context context, String uriText) {
        if (uriText == null || uriText.isEmpty()) return null;
        try (java.io.InputStream input = context.getContentResolver().openInputStream(android.net.Uri.parse(uriText))) {
            if (input == null) return null;
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) result.append(String.format(java.util.Locale.US, "%02x", value & 0xff));
            return result.toString();
        } catch (Exception ignored) { return null; }
    }
}
