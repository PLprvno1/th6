package com.example.th6.services;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;

import com.example.th6.models.LocalMediaPhoto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DevicePhotoScanner {
    private static final String TAG = "DevicePhotoScanner";
    private static final ExecutorService SCAN_EXECUTOR = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface ScanCallback {
        void onScanComplete(List<LocalMediaPhoto> localPhotos);
        void onScanError(String error);
    }

    public void scanPhotos(Context context, ScanCallback callback) {
        scan(context, callback, false);
    }

    /** Scans only images and reports revoked image permission instead of returning an empty queue. */
    public void scanImages(Context context, ScanCallback callback) {
        scan(context, callback, true);
    }

    private void scan(Context context, ScanCallback callback, boolean imagesOnly) {
        SCAN_EXECUTOR.execute(() -> {
            try {
                ContentResolver resolver = context.getContentResolver();
                List<LocalMediaPhoto> media = new ArrayList<>();
                scanCollection(resolver, getExternalCollectionUri(false), false, media, imagesOnly);
                if (!imagesOnly) scanCollection(resolver, getExternalCollectionUri(true), true, media, false);
                Collections.sort(media, (a, b) -> Long.compare(b.getDateModified(), a.getDateModified()));
                mainHandler.post(() -> {
                    if (callback != null) callback.onScanComplete(media);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (callback != null) callback.onScanError("Media scan failed: " + e.getLocalizedMessage());
                });
            }
        });
    }

    private void scanCollection(ContentResolver resolver, Uri collection, boolean video,
                                List<LocalMediaPhoto> result, boolean failOnSecurityError) {
        String[] projection = video
                ? new String[]{MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.MIME_TYPE, MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT,
                    MediaStore.Video.Media.DATE_TAKEN, MediaStore.Video.Media.DATE_ADDED,
                    MediaStore.Video.Media.DATE_MODIFIED, MediaStore.Video.Media.DURATION}
                : new String[]{MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT,
                    MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.DATE_MODIFIED};
        int itemCount = 0;
        try (Cursor cursor = resolver.query(collection, projection, null, null, null)) {
            if (cursor == null) {
                Log.i(TAG, (video ? "Video" : "Image") + " query returned a null cursor");
                if (failOnSecurityError) throw new IllegalStateException("MediaStore did not return an image cursor.");
                return;
            }
            int idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
            int nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
            int mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE);
            int sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE);
            int widthCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH);
            int heightCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT);
            int takenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN);
            int addedCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED);
            int modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED);
            int durationCol = video ? cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION) : -1;

            while (cursor.moveToNext()) {
                long id = cursor.getLong(idCol);
                long timestamp = getTimestamp(cursor, takenCol, addedCol, modifiedCol);
                Uri contentUri = ContentUris.withAppendedId(collection, id);
                LocalMediaPhoto item = new LocalMediaPhoto(id, contentUri.toString(),
                        cursor.getString(nameCol), cursor.getString(mimeCol), cursor.getLong(sizeCol),
                        cursor.getInt(widthCol), cursor.getInt(heightCol), timestamp);
                item.setVideo(video);
                if (video && durationCol >= 0) item.setDurationMs(Math.max(0, cursor.getLong(durationCol)));
                result.add(item);
                itemCount++;
            }
            Log.i(TAG, (video ? "Video" : "Image") + " query returned " + itemCount + " items");
        } catch (SecurityException e) {
            Log.w(TAG, (video ? "Video" : "Image") + " query denied by media permissions", e);
            if (failOnSecurityError) throw e;
        } catch (RuntimeException e) {
            Log.e(TAG, (video ? "Video" : "Image") + " MediaStore query failed", e);
            if (failOnSecurityError) throw e;
        }
    }

    private Uri getExternalCollectionUri(boolean video) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return video
                    ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                    : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
        }
        return video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
    }

    private long getTimestamp(Cursor cursor, int takenCol, int addedCol, int modifiedCol) {
        long taken = cursor.getLong(takenCol);
        if (taken > 0) return taken;
        long added = cursor.getLong(addedCol);
        if (added > 0) return added * 1000L;
        long modified = cursor.getLong(modifiedCol);
        return modified > 0 ? modified * 1000L : 0L;
    }
}
