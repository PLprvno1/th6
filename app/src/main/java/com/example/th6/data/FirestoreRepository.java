package com.example.th6.data;

import com.example.th6.models.Album;
import com.example.th6.models.Photo;
import com.example.th6.models.UserProfile;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class FirestoreRepository {

    private final FirebaseFirestore db;

    public interface RepositoryCallback<T> {
        void onSuccess(T result);
        void onError(String error);
    }

    public FirestoreRepository() {
        this.db = FirebaseFirestore.getInstance();
    }

    // --- USER PROFILE ---
    public void createUserProfile(UserProfile profile, RepositoryCallback<Void> callback) {
        db.collection("users").document(profile.getUid())
                .set(profile)
                .addOnSuccessListener(aVoid -> callback.onSuccess(null))
                .addOnFailureListener(e -> callback.onError("Lỗi lưu hồ sơ người dùng: " + e.getLocalizedMessage()));
    }

    public void getUserProfile(String uid, RepositoryCallback<UserProfile> callback) {
        db.collection("users").document(uid)
                .get()
                .addOnSuccessListener(documentSnapshot -> {
                    if (documentSnapshot.exists()) {
                        UserProfile profile = documentSnapshot.toObject(UserProfile.class);
                        callback.onSuccess(profile);
                    } else {
                        callback.onError("Không tìm thấy thông tin hồ sơ.");
                    }
                })
                .addOnFailureListener(e -> callback.onError("Lỗi tải hồ sơ: " + e.getLocalizedMessage()));
    }

    public void updateUserProfile(String uid, Map<String, Object> updates, RepositoryCallback<Void> callback) {
        db.collection("users").document(uid)
                .update(updates)
                .addOnSuccessListener(aVoid -> callback.onSuccess(null))
                .addOnFailureListener(e -> callback.onError("Lỗi cập nhật hồ sơ: " + e.getLocalizedMessage()));
    }

    // --- PHOTOS ---
    private CollectionReference getPhotosRef(String uid) {
        return db.collection("users").document(uid).collection("photos");
    }

    public void savePhoto(Photo photo, RepositoryCallback<Void> callback) {
        getPhotosRef(photo.getOwnerUid()).document(photo.getId())
                .set(photo)
                .addOnSuccessListener(aVoid -> callback.onSuccess(null))
                .addOnFailureListener(e -> callback.onError("Lỗi lưu thông tin ảnh vào Firestore: " + e.getLocalizedMessage()));
    }

    /** Saves device-backup metadata under a stable document ID, making retries idempotent. */
    public void saveBackupPhoto(Photo photo, RepositoryCallback<Void> callback) {
        String uri = photo.getLocalMediaUri();
        if (uri == null || uri.isEmpty()) {
            callback.onError("Thiếu URI media để lưu metadata backup.");
            return;
        }
        String documentId = "backup_" + sha256(uri);
        photo.setId(documentId);
        getPhotosRef(photo.getOwnerUid()).document(documentId)
                .set(photo)
                .addOnSuccessListener(aVoid -> callback.onSuccess(null))
                .addOnFailureListener(e -> callback.onError("Lỗi lưu metadata backup: " + e.getLocalizedMessage()));
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte b : digest) result.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public void getPhotos(String uid, RepositoryCallback<List<Photo>> callback) {
        getPhotosRef(uid)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .get()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    List<Photo> photoList = new ArrayList<>();
                    for (DocumentSnapshot doc : queryDocumentSnapshots) {
                        Photo p = doc.toObject(Photo.class);
                        if (p != null) {
                            p.setId(doc.getId());
                            photoList.add(p);
                        }
                    }
                    callback.onSuccess(photoList);
                })
                .addOnFailureListener(e -> callback.onError("Lỗi tải danh sách ảnh: " + e.getLocalizedMessage()));
    }

    public void toggleFavorite(String uid, String photoId, boolean isFavorite, RepositoryCallback<Void> callback) {
        getPhotosRef(uid).document(photoId)
                .update("favorite", isFavorite)
                .addOnSuccessListener(aVoid -> callback.onSuccess(null))
                .addOnFailureListener(e -> callback.onError("Lỗi cập nhật yêu thích: " + e.getLocalizedMessage()));
    }

    public void moveToTrash(String uid, String photoId, boolean isTrashed, RepositoryCallback<Void> callback) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("trashed", isTrashed);
        updates.put("trashedAt", isTrashed ? System.currentTimeMillis() : 0);

        getPhotosRef(uid).document(photoId)
                .update(updates)
                .addOnSuccessListener(aVoid -> callback.onSuccess(null))
                .addOnFailureListener(e -> callback.onError("Lỗi cập nhật trạng thái thùng rác: " + e.getLocalizedMessage()));
    }

    public void deletePhoto(String uid, String photoId, RepositoryCallback<Void> callback) {
        getPhotosRef(uid).document(photoId)
                .delete()
                .addOnSuccessListener(aVoid -> callback.onSuccess(null))
                .addOnFailureListener(e -> callback.onError("Lỗi xóa vĩnh viễn ảnh: " + e.getLocalizedMessage()));
    }

    public void updatePhotoAlbums(String uid, String photoId, List<String> albumIds, RepositoryCallback<Void> callback) {
        getPhotosRef(uid).document(photoId)
                .update("albumIds", albumIds)
                .addOnSuccessListener(aVoid -> callback.onSuccess(null))
                .addOnFailureListener(e -> callback.onError("Lỗi cập nhật album cho ảnh: " + e.getLocalizedMessage()));
    }

    // --- ALBUMS ---
    private CollectionReference getAlbumsRef(String uid) {
        return db.collection("users").document(uid).collection("albums");
    }

    public void createAlbum(Album album, RepositoryCallback<Void> callback) {
        getAlbumsRef(album.getOwnerUid()).document(album.getId())
                .set(album)
                .addOnSuccessListener(aVoid -> callback.onSuccess(null))
                .addOnFailureListener(e -> callback.onError("Lỗi tạo album: " + e.getLocalizedMessage()));
    }

    public void getAlbums(String uid, RepositoryCallback<List<Album>> callback) {
        getAlbumsRef(uid)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .get()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    List<Album> albumList = new ArrayList<>();
                    for (DocumentSnapshot doc : queryDocumentSnapshots) {
                        Album a = doc.toObject(Album.class);
                        if (a != null) {
                            a.setId(doc.getId());
                            albumList.add(a);
                        }
                    }
                    callback.onSuccess(albumList);
                })
                .addOnFailureListener(e -> callback.onError("Lỗi tải danh sách album: " + e.getLocalizedMessage()));
    }

    public void deleteAlbum(String uid, String albumId, RepositoryCallback<Void> callback) {
        getAlbumsRef(uid).document(albumId)
                .delete()
                .addOnSuccessListener(aVoid -> callback.onSuccess(null))
                .addOnFailureListener(e -> callback.onError("Lỗi xóa album: " + e.getLocalizedMessage()));
    }

    public void updateAlbumName(String uid, String albumId, String newName, RepositoryCallback<Void> callback) {
        getAlbumsRef(uid).document(albumId)
                .update("name", newName)
                .addOnSuccessListener(aVoid -> callback.onSuccess(null))
                .addOnFailureListener(e -> callback.onError("Lỗi đổi tên album: " + e.getLocalizedMessage()));
    }
}
