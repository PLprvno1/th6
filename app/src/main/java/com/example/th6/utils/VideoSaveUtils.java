package com.example.th6.utils;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.Executors;

public class VideoSaveUtils {

    public interface SaveVideoCallback {
        void onSuccess(Uri savedUri, String name);
        void onError(String error);
    }

    public static void trimAndExportVideo(Context context, Uri sourceUri, long startMs, long endMs, boolean muteAudio, SaveVideoCallback callback) {
        Executors.newSingleThreadExecutor().execute(() -> {
            MediaExtractor extractor = null;
            MediaMuxer muxer = null;
            File tempOutFile = null;

            try {
                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
                String filename = "PhotoVault_Video_" + timestamp + ".mp4";

                tempOutFile = new File(context.getCacheDir(), filename);

                extractor = new MediaExtractor();
                extractor.setDataSource(context, sourceUri, null);

                int trackCount = extractor.getTrackCount();
                muxer = new MediaMuxer(tempOutFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

                HashMap<Integer, Integer> indexMap = new HashMap<>();
                int bufferSize = -1;

                for (int i = 0; i < trackCount; i++) {
                    MediaFormat format = extractor.getTrackFormat(i);
                    String mime = format.getString(MediaFormat.KEY_MIME);

                    boolean isVideo = mime != null && mime.startsWith("video/");
                    boolean isAudio = mime != null && mime.startsWith("audio/");

                    if (isVideo || (isAudio && !muteAudio)) {
                        extractor.selectTrack(i);
                        int dstIndex = muxer.addTrack(format);
                        indexMap.put(i, dstIndex);

                        if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                            int newSize = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
                            bufferSize = Math.max(newSize, bufferSize);
                        }
                    }
                }

                if (bufferSize <= 0) {
                    bufferSize = 1024 * 1024; // 1MB buffer default
                }

                muxer.start();

                long startUs = startMs * 1000L;
                long endUs = endMs * 1000L;

                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

                ByteBuffer buffer = ByteBuffer.allocateDirect(bufferSize);
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

                while (true) {
                    int sampleTrackIndex = extractor.getSampleTrackIndex();
                    if (sampleTrackIndex < 0) break;

                    long sampleTime = extractor.getSampleTime();
                    if (endUs > 0 && sampleTime > endUs) break;

                    if (indexMap.containsKey(sampleTrackIndex)) {
                        info.offset = 0;
                        info.size = extractor.readSampleData(buffer, 0);
                        if (info.size < 0) break;

                        info.presentationTimeUs = Math.max(0, sampleTime - startUs);
                        int sampleFlags = extractor.getSampleFlags();
                        int flags = 0;
                        if ((sampleFlags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                            flags |= MediaCodec.BUFFER_FLAG_KEY_FRAME;
                        }
                        info.flags = flags;

                        Integer dstTrack = indexMap.get(sampleTrackIndex);
                        if (dstTrack != null) {
                            muxer.writeSampleData(dstTrack, buffer, info);
                        }
                    }

                    extractor.advance();
                }

                muxer.stop();
                muxer.release();
                muxer = null;

                extractor.release();
                extractor = null;

                // Save temp file into MediaStore
                ContentResolver resolver = context.getContentResolver();
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.DISPLAY_NAME, filename);
                values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/PhotoVault");
                    values.put(MediaStore.Video.Media.IS_PENDING, 1);
                }

                Uri resultUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                if (resultUri == null) {
                    notifyError(callback, "Không thể tạo file trong MediaStore");
                    return;
                }

                try (InputStream in = new FileInputStream(tempOutFile);
                     OutputStream out = resolver.openOutputStream(resultUri)) {
                    if (out != null) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = in.read(buf)) > 0) {
                            out.write(buf, 0, len);
                        }
                        out.flush();
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear();
                    values.put(MediaStore.Video.Media.IS_PENDING, 0);
                    resolver.update(resultUri, values, null, null);
                }

                if (tempOutFile.exists()) tempOutFile.delete();

                notifySuccess(callback, resultUri, filename);

            } catch (Exception e) {
                if (muxer != null) {
                    try { muxer.release(); } catch (Exception ignored) {}
                }
                if (extractor != null) {
                    try { extractor.release(); } catch (Exception ignored) {}
                }
                if (tempOutFile != null && tempOutFile.exists()) tempOutFile.delete();
                notifyError(callback, "Lỗi xuất video: " + e.getLocalizedMessage());
            }
        });
    }

    private static void notifySuccess(SaveVideoCallback callback, Uri uri, String name) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (callback != null) callback.onSuccess(uri, name);
        });
    }

    private static void notifyError(SaveVideoCallback callback, String error) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (callback != null) callback.onError(error);
        });
    }
}
