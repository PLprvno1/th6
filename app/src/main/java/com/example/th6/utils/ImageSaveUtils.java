package com.example.th6.utils;

import android.app.RecoverableSecurityException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** MediaStore save helpers. Image data is fully encoded before a destination is changed. */
public final class ImageSaveUtils {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ImageSaveUtils() { }

    public interface SaveCallback {
        void onSuccess(Uri savedUri, String filePath);
        void onError(String error);
        default void onPermissionRequired(android.content.IntentSender sender) {
            onError("Android yêu cầu xác nhận quyền sửa ảnh.");
        }
    }

    public static void saveBitmapAsCopy(Context context, Bitmap bitmap, SaveCallback callback) {
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            File encoded = null;
            Uri inserted = null;
            boolean complete = false;
            try {
                encoded = encode(app, bitmap, Bitmap.CompressFormat.JPEG, 95);
                String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
                String filename = "PhotoVault_" + stamp + "_" + UUID.randomUUID().toString().substring(0, 4) + ".jpg";
                ContentResolver resolver = app.getContentResolver();
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PhotoVault");
                    values.put(MediaStore.Images.Media.IS_PENDING, 1);
                }
                inserted = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (inserted == null) throw new IllegalStateException("Không thể tạo mục ảnh trong MediaStore.");
                try (InputStream in = new FileInputStream(encoded);
                     OutputStream out = resolver.openOutputStream(inserted, "w")) {
                    if (out == null) throw new IllegalStateException("Không thể mở file đích để ghi.");
                    copy(in, out);
                    out.flush();
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues publish = new ContentValues();
                    publish.put(MediaStore.Images.Media.IS_PENDING, 0);
                    int rows = resolver.update(inserted, publish, null, null);
                    if (rows <= 0) throw new IllegalStateException("Không thể hoàn tất công bố ảnh trong thư viện.");
                }
                complete = true;
                Uri saved = inserted;
                post(() -> callback.onSuccess(saved, filename));
            } catch (Exception error) {
                if (inserted != null && !complete) {
                    try { app.getContentResolver().delete(inserted, null, null); } catch (Exception ignored) { }
                }
                post(() -> callback.onError(message(error)));
            } finally {
                deleteQuietly(encoded);
            }
        });
    }

    public static void overwriteOriginalBitmap(Context context, Bitmap bitmap, Uri originalUri, SaveCallback callback) {
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            File encoded = null;
            File backup = null;
            boolean destinationTouched = false;
            try {
                ContentResolver resolver = app.getContentResolver();
                Bitmap.CompressFormat format = formatForMime(resolver.getType(originalUri));
                if (format == null) throw new IllegalArgumentException("Định dạng ảnh gốc không được hỗ trợ. Hãy lưu bản sao.");
                encoded = encode(app, bitmap, format, 95);

                // Keep the original bytes available for rollback if the provider fails mid-write.
                backup = File.createTempFile("photovault_original_", ".bak", app.getCacheDir());
                try (InputStream in = resolver.openInputStream(originalUri);
                     OutputStream out = new FileOutputStream(backup)) {
                    if (in == null) throw new IllegalStateException("Không thể đọc ảnh gốc để tạo bản dự phòng.");
                    copy(in, out);
                    out.flush();
                }

                try (InputStream in = new FileInputStream(encoded);
                     OutputStream out = resolver.openOutputStream(originalUri, "rwt")) {
                    if (out == null) throw new IllegalStateException("Không thể mở ảnh gốc để ghi.");
                    destinationTouched = true;
                    copy(in, out);
                    out.flush();
                }
                Uri saved = originalUri;
                post(() -> callback.onSuccess(saved, "Original Updated"));
            } catch (Exception error) {
                if (!destinationTouched && isRecoverableWriteError(error)) {
                    android.content.IntentSender sender = recoverableSender(error);
                    if (sender != null) {
                        post(() -> callback.onPermissionRequired(sender));
                        return;
                    }
                }
                String rollbackError = null;
                if (destinationTouched && backup != null && backup.exists()) {
                    try (InputStream in = new FileInputStream(backup);
                         OutputStream out = app.getContentResolver().openOutputStream(originalUri, "rwt")) {
                        if (out == null) throw new IllegalStateException("Không thể khôi phục ảnh gốc.");
                        copy(in, out);
                        out.flush();
                    } catch (Exception restoreFailure) {
                        rollbackError = " Không thể xác nhận khôi phục ảnh gốc: " + message(restoreFailure);
                    }
                }
                String suffix = rollbackError == null ? " Ảnh gốc được giữ nguyên." : rollbackError;
                post(() -> callback.onError(message(error) + suffix));
            } finally {
                deleteQuietly(encoded);
                deleteQuietly(backup);
            }
        });
    }

    private static File encode(Context context, Bitmap bitmap, Bitmap.CompressFormat format, int quality) throws Exception {
        File file = File.createTempFile("photovault_edit_", ".tmp", context.getCacheDir());
        boolean success = false;
        try (OutputStream out = new FileOutputStream(file)) {
            success = bitmap.compress(format, quality, out);
            out.flush();
        } finally {
            if (!success) deleteQuietly(file);
        }
        if (!success || file.length() == 0) throw new IllegalStateException("Không thể mã hóa ảnh đã chỉnh sửa.");
        return file;
    }

    private static Bitmap.CompressFormat formatForMime(String mime) {
        if ("image/png".equalsIgnoreCase(mime)) return Bitmap.CompressFormat.PNG;
        if ("image/webp".equalsIgnoreCase(mime)) return Bitmap.CompressFormat.WEBP;
        if ("image/jpeg".equalsIgnoreCase(mime) || "image/jpg".equalsIgnoreCase(mime)) return Bitmap.CompressFormat.JPEG;
        return null;
    }

    private static boolean isRecoverableWriteError(Exception error) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && error instanceof RecoverableSecurityException;
    }

    private static android.content.IntentSender recoverableSender(Exception error) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !(error instanceof RecoverableSecurityException)) return null;
        return ((RecoverableSecurityException) error).getUserAction().getActionIntent().getIntentSender();
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
    }

    private static String message(Exception error) {
        String text = error.getLocalizedMessage();
        return text == null || text.trim().isEmpty() ? error.getClass().getSimpleName() : text;
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private static void post(Runnable action) { MAIN.post(action); }
}
