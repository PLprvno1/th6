package com.example.th6.services;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;

import com.example.th6.utils.BackupPreferences;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Foreground-only MediaStore watcher. It only debounces and validates changed URIs;
 * actual scanning and uploading remain in PhotoBackupWorker. */
public final class MediaStoreBackupObserver {
    private static final long DEBOUNCE_MS = 1_500L;
    private static final long STABILITY_SAMPLE_MS = 700L;
    private static final long NOT_READY_RETRY_MS = 1_800L;
    private static final int MAX_URIS_PER_BATCH = 48;
    private static final int MAX_READINESS_RETRIES = 2;

    private final Context appContext;
    private final String uid;
    private final ContentResolver resolver;
    private final Set<String> changedUris = new LinkedHashSet<>();
    private HandlerThread handlerThread;
    private volatile Handler handler;
    private ContentObserver observer;
    private boolean collectionChange;
    private int retryCount;
    private boolean registered;

    private final Runnable debounceRunnable = () -> validateAndEnqueue(false);
    private final Runnable retryRunnable = () -> validateAndEnqueue(true);

    public MediaStoreBackupObserver(Context context, String uid) {
        this.appContext = context.getApplicationContext();
        this.uid = uid;
        this.resolver = appContext.getContentResolver();
    }

    public synchronized void register() {
        if (registered || uid == null || uid.isEmpty()) return;
        BackupPreferences preferences = new BackupPreferences(appContext, uid);
        if (!preferences.isBackupEnabled() || preferences.isPaused()) return;

        handlerThread = new HandlerThread("PhotoVaultMediaStoreObserver");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
        observer = new ContentObserver(handler) {
            @Override public void onChange(boolean selfChange, Uri uri) {
                onMediaStoreChanged(uri);
            }
        };
        try {
            for (Uri collection : collections()) resolver.registerContentObserver(collection, true, observer);
            registered = true;
        } catch (RuntimeException e) {
            unregisterInternal();
        }
    }

    public synchronized void unregister() {
        if (!registered && handlerThread == null) return;
        unregisterInternal();
    }

    private void unregisterInternal() {
        if (observer != null) {
            try { resolver.unregisterContentObserver(observer); } catch (RuntimeException ignored) { }
        }
        if (handler != null) {
            handler.removeCallbacks(debounceRunnable);
            handler.removeCallbacks(retryRunnable);
        }
        synchronized (changedUris) {
            changedUris.clear();
            collectionChange = false;
            retryCount = 0;
        }
        observer = null;
        handler = null;
        registered = false;
        if (handlerThread != null) {
            handlerThread.quitSafely();
            handlerThread = null;
        }
    }

    private void onMediaStoreChanged(Uri uri) {
        Handler current = handler;
        if (current == null) return;
        synchronized (changedUris) {
            if (uri == null || !isItemUri(uri)) {
                collectionChange = true;
            } else if (changedUris.size() < MAX_URIS_PER_BATCH) {
                changedUris.add(uri.toString());
            } else {
                // A large/bulk operation is verified by the worker's normal MediaStore scan.
                collectionChange = true;
            }
            retryCount = 0;
        }
        current.removeCallbacks(retryRunnable);
        current.removeCallbacks(debounceRunnable);
        current.postDelayed(debounceRunnable, DEBOUNCE_MS);
    }

    private boolean isItemUri(Uri uri) {
        List<String> path = uri.getPathSegments();
        for (Uri collection : collections()) {
            if (collection.getAuthority() != null && collection.getAuthority().equals(uri.getAuthority())
                    && path.size() > collection.getPathSegments().size()
                    && path.subList(0, collection.getPathSegments().size()).equals(collection.getPathSegments())) {
                return true;
            }
        }
        return false;
    }

    private void validateAndEnqueue(boolean retry) {
        List<String> batch;
        boolean collectionOnly;
        synchronized (changedUris) {
            batch = new ArrayList<>(changedUris);
            collectionOnly = collectionChange;
        }
        // A collection notification has no safe item URI to probe. The existing scanner
        // performs the full query and excludes IS_PENDING rows before they reach the worker.
        boolean ready = collectionOnly || batch.isEmpty();
        if (!ready) {
            List<MediaSnapshot> first = new ArrayList<>();
            for (String itemUri : batch) first.add(readSnapshot(Uri.parse(itemUri)));
            try { Thread.sleep(STABILITY_SAMPLE_MS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            for (int i = 0; i < batch.size(); i++) {
                MediaSnapshot before = first.get(i);
                MediaSnapshot after = readSnapshot(Uri.parse(batch.get(i)));
                if (before != null && after != null && before.pending == 0 && after.pending == 0
                        && before.size > 0 && before.size == after.size
                        && before.dateModified == after.dateModified && before.readable && after.readable) {
                    ready = true;
                    break;
                }
            }
        }

        if (ready) {
            synchronized (changedUris) {
                changedUris.clear();
                collectionChange = false;
                retryCount = 0;
            }
            BackupWorkScheduler.enqueueIfEligible(appContext, uid);
            return;
        }

        synchronized (changedUris) {
            retryCount++;
            if (retryCount > MAX_READINESS_RETRIES) {
                changedUris.clear();
                collectionChange = false;
                retryCount = 0;
                return;
            }
        }
        Handler current = handler;
        if (current != null) current.postDelayed(retryRunnable, NOT_READY_RETRY_MS);
    }

    private MediaSnapshot readSnapshot(Uri uri) {
        String[] projection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? new String[]{MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED,
                    MediaStore.MediaColumns.IS_PENDING}
                : new String[]{MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED};
        try (Cursor cursor = resolver.query(uri, projection, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            int sizeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE);
            int modifiedIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED);
            int pendingIndex = cursor.getColumnIndex(MediaStore.MediaColumns.IS_PENDING);
            long size = sizeIndex >= 0 && !cursor.isNull(sizeIndex) ? cursor.getLong(sizeIndex) : -1L;
            long modified = modifiedIndex >= 0 && !cursor.isNull(modifiedIndex) ? cursor.getLong(modifiedIndex) : -1L;
            int pending = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && pendingIndex >= 0
                    ? cursor.getInt(pendingIndex) : 0;
            if (pending != 0 || size <= 0) return new MediaSnapshot(size, modified, pending, false);
            boolean readable = false;
            try (InputStream input = resolver.openInputStream(uri)) {
                readable = input != null && input.read() >= 0;
            }
            return new MediaSnapshot(size, modified, pending, readable);
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<Uri> collections() {
        List<Uri> result = new ArrayList<>(2);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            result.add(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL));
            result.add(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL));
        } else {
            result.add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            result.add(MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
        }
        return result;
    }

    private static final class MediaSnapshot {
        final long size;
        final long dateModified;
        final int pending;
        final boolean readable;

        MediaSnapshot(long size, long dateModified, int pending, boolean readable) {
            this.size = size;
            this.dateModified = dateModified;
            this.pending = pending;
            this.readable = readable;
        }
    }
}
