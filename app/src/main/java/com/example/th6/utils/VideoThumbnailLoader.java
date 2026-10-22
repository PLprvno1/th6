package com.example.th6.utils;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Loads a small preview frame from a remote video URL without blocking a RecyclerView bind. */
public final class VideoThumbnailLoader {
    public interface Callback { void onLoaded(Bitmap bitmap, long durationMs); }

    private static final class ThumbnailResult {
        final Bitmap bitmap;
        final long durationMs;
        ThumbnailResult(Bitmap bitmap, long durationMs) { this.bitmap = bitmap; this.durationMs = durationMs; }
    }

    private static final int FRAME_WIDTH = 512;
    private static final int FRAME_HEIGHT = 288;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, ThumbnailResult> CACHE = new LruCache<String, ThumbnailResult>(
            Math.max(1024, (int) (Runtime.getRuntime().maxMemory() / 16L / 1024L))) {
        @Override protected int sizeOf(String key, ThumbnailResult value) {
            return Math.max(1, value.bitmap == null ? 1 : value.bitmap.getByteCount() / 1024);
        }
    };
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
            2, 2, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(48), runnable -> {
                Thread thread = new Thread(runnable, "CloudVideoThumbnail");
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            }, new ThreadPoolExecutor.DiscardOldestPolicy());

    private VideoThumbnailLoader() { }

    public static void load(String url, Callback callback) {
        if (url == null || url.isEmpty() || callback == null) return;
        ThumbnailResult cached;
        synchronized (CACHE) { cached = CACHE.get(url); }
        if (cached != null && (cached.bitmap == null || !cached.bitmap.isRecycled())) {
            ThumbnailResult result = cached;
            MAIN.post(() -> callback.onLoaded(result.bitmap, result.durationMs));
            return;
        }
        EXECUTOR.execute(() -> {
            ThumbnailResult result = extract(url);
            if (result != null) synchronized (CACHE) { CACHE.put(url, result); }
            MAIN.post(() -> callback.onLoaded(result == null ? null : result.bitmap,
                    result == null ? 0L : result.durationMs));
        });
    }

    private static ThumbnailResult extract(String url) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            Map<String, String> headers = Collections.emptyMap();
            retriever.setDataSource(url, headers);
            long durationMs = 0L;
            String rawDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (rawDuration != null) {
                try { durationMs = Math.max(0L, Long.parseLong(rawDuration)); }
                catch (NumberFormatException ignored) { }
            }
            Bitmap frame = null;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    frame = retriever.getScaledFrameAtTime(1_000_000L,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC, FRAME_WIDTH, FRAME_HEIGHT);
                } else {
                    frame = retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    if (frame != null && (frame.getWidth() > FRAME_WIDTH || frame.getHeight() > FRAME_HEIGHT)) {
                        float scale = Math.min(FRAME_WIDTH / (float) frame.getWidth(),
                                FRAME_HEIGHT / (float) frame.getHeight());
                        Bitmap scaled = Bitmap.createScaledBitmap(frame,
                                Math.max(1, Math.round(frame.getWidth() * scale)),
                                Math.max(1, Math.round(frame.getHeight() * scale)), true);
                        if (scaled != frame) frame.recycle();
                        frame = scaled;
                    }
                }
            } catch (Exception | OutOfMemoryError ignored) {
                // Keep the duration even when a codec cannot provide a preview frame.
            }
            return new ThumbnailResult(frame, durationMs);
        } catch (Exception | OutOfMemoryError ignored) {
            return null;
        } finally {
            try { retriever.release(); } catch (Exception ignored) { }
        }
    }
}
