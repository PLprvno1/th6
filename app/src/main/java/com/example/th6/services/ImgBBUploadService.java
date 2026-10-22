package com.example.th6.services;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;

import org.json.JSONObject;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;

public class ImgBBUploadService {
    // This key was already embedded in the app source. It must be considered extractable from any APK.
    private static final String API_KEY = "57396217bacb8f9d07321ce100a5df6e";
    private static final String UPLOAD_URL = "https://api.imgbb.com/1/upload";
    private static final long MAX_UPLOAD_BYTES = 32L * 1024L * 1024L;
    private static final int MAX_ATTEMPTS = 3;

    private static final ExecutorService UPLOAD_EXECUTOR = Executors.newFixedThreadPool(2);
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(3, TimeUnit.MINUTES)
            .build();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface UploadCallback {
        void onSuccess(String imageUrl);
        void onError(String error);
    }

    public interface DetailedUploadCallback {
        void onSuccess(String imageUrl);
        void onError(String error, boolean retryable);
    }

    public void uploadImage(Context context, Uri imageUri, UploadCallback callback) {
        uploadImageWithRetry(context, imageUri, new DetailedUploadCallback() {
            @Override public void onSuccess(String imageUrl) {
                if (callback != null) callback.onSuccess(imageUrl);
            }

            @Override public void onError(String error, boolean retryable) {
                if (callback != null) callback.onError(error);
            }
        });
    }

    public void uploadImageWithRetry(Context context, Uri imageUri, DetailedUploadCallback callback) {
        Context appContext = context.getApplicationContext();
        UPLOAD_EXECUTOR.execute(() -> {
            if (imageUri == null || imageUri.getScheme() == null
                    || !("content".equalsIgnoreCase(imageUri.getScheme()) || "file".equalsIgnoreCase(imageUri.getScheme()))) {
                postError(callback, "URI ảnh không hợp lệ.", false);
                return;
            }

            ImageDetails details;
            try {
                details = readImageDetails(appContext.getContentResolver(), imageUri);
                if (details.sizeBytes > MAX_UPLOAD_BYTES) {
                    postError(callback, "Ảnh vượt quá giới hạn 32 MB của ImgBB.", false);
                    return;
                }
                try (InputStream probe = appContext.getContentResolver().openInputStream(imageUri)) {
                    if (probe == null) throw new FileNotFoundException("Content URI không còn truy cập được.");
                }
            } catch (SecurityException | FileNotFoundException e) {
                postError(callback, "Không thể đọc ảnh từ URI: " + safeMessage(e), false);
                return;
            } catch (Exception e) {
                postError(callback, "Không thể mở ảnh: " + safeMessage(e), false);
                return;
            }

            UploadResult lastResult = null;
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                lastResult = uploadOnce(appContext.getContentResolver(), imageUri, details);
                if (lastResult.imageUrl != null) {
                    postSuccess(callback, lastResult.imageUrl);
                    return;
                }
                if (!lastResult.retryable || attempt == MAX_ATTEMPTS) break;
                try {
                    Thread.sleep(500L * (1L << (attempt - 1)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    postError(callback, "Upload bị gián đoạn.", true);
                    return;
                }
            }
            postError(callback, lastResult != null ? lastResult.error : "ImgBB upload thất bại.",
                    lastResult != null && lastResult.retryable);
        });
    }

    private UploadResult uploadOnce(ContentResolver resolver, Uri uri, ImageDetails details) {
        HttpUrl baseUrl = HttpUrl.parse(UPLOAD_URL);
        if (baseUrl == null) return UploadResult.failure("URL ImgBB không hợp lệ.", false);
        HttpUrl url = baseUrl.newBuilder().addQueryParameter("key", API_KEY).build();

        RequestBody imageBody = new UriRequestBody(resolver, uri, details.mimeType, details.sizeBytes);
        RequestBody form = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("image", details.fileName, imageBody)
                .build();
        Request request = new Request.Builder().url(url).post(form).build();

        try (Response response = CLIENT.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            JSONObject json = parseJson(body);
            if (!response.isSuccessful()) {
                return UploadResult.failure(readApiError(json, response.code()), isRetryableStatus(response.code()));
            }

            boolean success = json != null && json.optBoolean("success", false);
            JSONObject data = json != null ? json.optJSONObject("data") : null;
            String imageUrl = data != null ? data.optString("url", "") : "";
            if (imageUrl.isEmpty() && data != null) imageUrl = data.optString("display_url", "");
            if (success && isHttpImageUrl(imageUrl)) return UploadResult.success(imageUrl);

            int apiStatus = json != null ? json.optInt("status", response.code()) : response.code();
            return UploadResult.failure(readApiError(json, apiStatus), isRetryableStatus(apiStatus));
        } catch (IOException e) {
            return UploadResult.failure("Lỗi kết nối ImgBB: " + safeMessage(e), !hasPermanentCause(e));
        }
    }

    private ImageDetails readImageDetails(ContentResolver resolver, Uri uri) {
        String fileName = "image.jpg";
        long size = -1;
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameIndex >= 0 && cursor.getString(nameIndex) != null) fileName = cursor.getString(nameIndex);
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex);
            }
        } catch (RuntimeException ignored) {
            // Some MediaStore providers do not expose OpenableColumns; the upload stream remains authoritative.
        }
        String mimeType = resolver.getType(uri);
        if (mimeType == null || !mimeType.startsWith("image/")) mimeType = "application/octet-stream";
        return new ImageDetails(fileName, size, MediaType.parse(mimeType));
    }

    private JSONObject parseJson(String body) {
        try { return new JSONObject(body); } catch (Exception ignored) { return null; }
    }

    private String readApiError(JSONObject json, int status) {
        if (json != null) {
            JSONObject error = json.optJSONObject("error");
            if (error != null) {
                String message = error.optString("message", "");
                if (!message.isEmpty()) return "ImgBB: " + message;
            }
        }
        return "ImgBB trả về lỗi HTTP/API " + status + ".";
    }

    private boolean isHttpImageUrl(String value) {
        Uri uri = Uri.parse(value == null ? "" : value);
        String scheme = uri.getScheme();
        return uri.getHost() != null && ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme));
    }

    private boolean isRetryableStatus(int status) {
        return status == 408 || status == 429 || status >= 500;
    }

    private boolean hasPermanentCause(IOException error) {
        String message = error.getMessage();
        if (message != null && message.contains("giới hạn 32 MB")) return true;
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof SecurityException || cause instanceof FileNotFoundException) return true;
            cause = cause.getCause();
        }
        return false;
    }

    private String safeMessage(Exception error) {
        String message = error.getLocalizedMessage();
        return message != null ? message : error.getClass().getSimpleName();
    }

    private void postSuccess(DetailedUploadCallback callback, String url) {
        mainHandler.post(() -> { if (callback != null) callback.onSuccess(url); });
    }

    private void postError(DetailedUploadCallback callback, String error, boolean retryable) {
        mainHandler.post(() -> { if (callback != null) callback.onError(error, retryable); });
    }

    private static class ImageDetails {
        final String fileName;
        final long sizeBytes;
        final MediaType mimeType;
        ImageDetails(String fileName, long sizeBytes, MediaType mimeType) {
            this.fileName = fileName;
            this.sizeBytes = sizeBytes;
            this.mimeType = mimeType;
        }
    }

    private static class UploadResult {
        final String imageUrl;
        final String error;
        final boolean retryable;
        private UploadResult(String imageUrl, String error, boolean retryable) {
            this.imageUrl = imageUrl;
            this.error = error;
            this.retryable = retryable;
        }
        static UploadResult success(String url) { return new UploadResult(url, null, false); }
        static UploadResult failure(String error, boolean retryable) { return new UploadResult(null, error, retryable); }
    }

    private static class UriRequestBody extends RequestBody {
        private final ContentResolver resolver;
        private final Uri uri;
        private final MediaType mimeType;
        private final long sizeBytes;

        UriRequestBody(ContentResolver resolver, Uri uri, MediaType mimeType, long sizeBytes) {
            this.resolver = resolver;
            this.uri = uri;
            this.mimeType = mimeType;
            this.sizeBytes = sizeBytes;
        }

        @Override public MediaType contentType() { return mimeType; }
        @Override public long contentLength() { return sizeBytes; }

        @Override
        public void writeTo(BufferedSink sink) throws IOException {
            try (InputStream input = resolver.openInputStream(uri)) {
                if (input == null) throw new FileNotFoundException("Content URI không còn truy cập được.");
                byte[] buffer = new byte[16 * 1024];
                long written = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    written += read;
                    if (written > MAX_UPLOAD_BYTES) throw new IOException("Ảnh vượt quá giới hạn 32 MB của ImgBB.");
                    sink.write(buffer, 0, read);
                }
            }
        }
    }
}
