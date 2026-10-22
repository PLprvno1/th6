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
    public interface Callback { void onLoaded(Bitmap bitmap); }

    private static final int FRAME_WIDTH = 512;
    private static final int FRAME_HEIGHT = 288;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(
            Math.max(1024, (int) (Runtime.getRuntime().maxMemory() / 16L / 1024L))) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return Math.max(1, value.getByteCount() / 1024);
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
        Bitmap cached;
        synchronized (CACHE) { cached = CACHE.get(url); }
        if (cached != null && !cached.isRecycled()) {
            Bitmap result = cached;
            MAIN.post(() -> callback.onLoaded(result));
            return;
        }
        EXECUTOR.execute(() -> {
            Bitmap frame = extract(url);
            if (frame != null) synchronized (CACHE) { CACHE.put(url, frame); }
            Bitmap result = frame;
            MAIN.post(() -> callback.onLoaded(result));
        });
    }

    private static Bitmap extract(String url) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            Map<String, String> headers = Collections.emptyMap();
            retriever.setDataSource(url, headers);
            Bitmap frame;
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
            return frame;
        } catch (Exception | OutOfMemoryError ignored) {
            return null;
        } finally {
            try { retriever.release(); } catch (Exception ignored) { }
        }
    }
}
