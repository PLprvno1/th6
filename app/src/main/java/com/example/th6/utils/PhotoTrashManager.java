package com.example.th6.utils;

import android.content.Context;

import com.example.th6.data.PhotoRepository;
import com.example.th6.models.Photo;

/** Shared soft-delete entry point for cloud-backed and device-only library items. */
public final class PhotoTrashManager {
    public interface Callback {
        void onSuccess(Photo photo);
        void onError(String error);
    }

    private PhotoTrashManager() { }

    public static void moveToTrash(Context context, String uid, Photo photo, Callback callback) {
        if (photo == null) {
            callback.onError("Không tìm thấy mục cần chuyển vào thùng rác.");
            return;
        }
        if ("local".equals(photo.getOwnerUid())) {
            PhotoVaultTrashCache.addLocal(context.getApplicationContext(), photo);
            callback.onSuccess(photo);
            return;
        }
        if (uid == null || uid.isBlank() || !uid.equals(photo.getOwnerUid()) || photo.getId() == null || photo.getId().isBlank()) {
            callback.onError("Không xác minh được tài khoản sở hữu mục cloud.");
            return;
        }
        new PhotoRepository().moveToTrashAsync(uid, photo.getId(), true, new PhotoRepository.Callback<Void>() {
            @Override public void onSuccess(Void result) {
                PhotoVaultTrashCache.add(context.getApplicationContext(), uid, photo);
                callback.onSuccess(photo);
            }
            @Override public void onError(String error) { callback.onError(error); }
        });
    }
}
