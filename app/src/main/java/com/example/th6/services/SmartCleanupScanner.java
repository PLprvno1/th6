package com.example.th6.services;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.MediaStore;
import android.database.Cursor;
import android.util.Log;

import com.example.th6.data.PhotoRepository;
import com.example.th6.models.LocalMediaPhoto;
import com.example.th6.models.Photo;
import com.example.th6.models.SmartCleanupMedia;
import com.example.th6.utils.PhotoVaultTrashCache;
import com.example.th6.utils.SmartCleanupImageAnalyzer;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One-shot, cancellable local/cloud scan. Heavy I/O and image work run off the main thread. */
public final class SmartCleanupScanner {
    private static final String TAG = "SmartCleanupScanner";
    private static final int MAX_REMOTE_BYTES = 50 * 1024 * 1024;
    private static final long MAX_REMOTE_SCAN_BYTES = 200L * 1024L * 1024L;
    private static final int MAX_PAIR_COMPARISONS = 25000;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "smart-cleanup-scan"); thread.setDaemon(true); return thread;
    });
    private static String latestUid;
    private static Result latestResult;

    public interface Listener {
        void onProgress(String stage, int processed, int total);
        void onComplete(Result result);
        void onCancelled();
    }

    public static final class Group {
        public final boolean exact;
        public final boolean similar;
        public final List<SmartCleanupMedia> media;
        Group(boolean exact, boolean similar, List<SmartCleanupMedia> media) { this.exact = exact; this.similar = similar; this.media = media; }
    }

    public static final class Result {
        public final List<Group> duplicates;
        public final List<Group> similar;
        public final List<SmartCleanupMedia> largeVideos;
        public final List<SmartCleanupMedia> unknownSizeVideos;
        public final List<SmartCleanupMedia> allMedia;
        public final long analyzedBytes;
        public final boolean localScanned;
        public final boolean cloudScanned;
        public final boolean partialComparison;
        public final String warning;
        public final long completedAt;
        Result(List<Group> duplicates, List<Group> similar, List<SmartCleanupMedia> videos,
               List<SmartCleanupMedia> unknownVideos,
               List<SmartCleanupMedia> all, long bytes, boolean local, boolean cloud,
               boolean partial, String warning) {
            this.duplicates = duplicates; this.similar = similar; this.largeVideos = videos;
            this.unknownSizeVideos = unknownVideos;
            this.allMedia = all; this.analyzedBytes = bytes; this.localScanned = local;
            this.cloudScanned = cloud; this.partialComparison = partial; this.warning = warning;
            this.completedAt = System.currentTimeMillis();
        }
    }

    private static final class GroupAnalysis {
        final List<Group> groups; final boolean truncated;
        GroupAnalysis(List<Group> groups, boolean truncated) { this.groups = groups; this.truncated = truncated; }
    }

    public static final class Session {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        public void cancel() { cancelled.set(true); }
    }

    private static final class Features {
        long phash;
        byte[] gray;
        String digest;
        boolean digestVerified;
    }

    private SmartCleanupScanner() { }

    public static Session scan(Context context, String uid, Listener listener) {
        Session session = new Session();
        synchronized (SmartCleanupScanner.class) { latestUid = uid; latestResult = null; }
        Context app = context.getApplicationContext();
        new DevicePhotoScanner().scanPhotos(app, new DevicePhotoScanner.ScanCallback() {
            @Override public void onScanComplete(List<LocalMediaPhoto> locals) {
                if (session.cancelled.get()) { listener.onCancelled(); return; }
                new PhotoRepository().getPhotosByUserPagedAsync(uid, new PhotoRepository.Callback<List<Photo>>() {
                    @Override public void onSuccess(List<Photo> cloud) {
                        EXECUTOR.execute(() -> analyze(app, uid, locals, true, cloud, true, "", session, listener));
                    }
                    @Override public void onError(String error) {
                        EXECUTOR.execute(() -> analyze(app, uid, locals, true, Collections.emptyList(), false,
                                "Không quét được cloud: " + error, session, listener));
                    }
                });
            }
            @Override public void onScanError(String error) {
                if (session.cancelled.get()) listener.onCancelled();
                else new PhotoRepository().getPhotosByUserPagedAsync(uid, new PhotoRepository.Callback<List<Photo>>() {
                    @Override public void onSuccess(List<Photo> cloud) {
                        EXECUTOR.execute(() -> analyze(app, uid, Collections.emptyList(), false, cloud, true,
                                "Không quét được MediaStore: " + error, session, listener));
                    }
                    @Override public void onError(String cloudError) {
                        EXECUTOR.execute(() -> analyze(app, uid, Collections.emptyList(), false, Collections.emptyList(), false,
                                "Không quét được MediaStore: " + error + "; cloud: " + cloudError, session, listener));
                    }
                });
            }
        });
        return session;
    }

    public static synchronized Result getLatest(String uid) {
        return uid != null && uid.equals(latestUid) ? latestResult : null;
    }

    public static synchronized void clearLatestForUid(String uid) {
        if (uid != null && uid.equals(latestUid)) { latestUid = null; latestResult = null; }
    }

    private static void analyze(Context context, String uid, List<LocalMediaPhoto> locals, boolean localScanned, List<Photo> cloud,
                                boolean cloudScanned, String initialWarning, Session session, Listener listener) {
        cleanupAbandonedDownloads(context);
        List<SmartCleanupMedia> all = new ArrayList<>();
        Map<LocalMediaPhoto, Features> localFeatures = new LinkedHashMap<>();
        Set<String> localTrash = PhotoVaultTrashCache.getLocalMediaIdentityKeys(context);
        List<Photo> validCloud = new ArrayList<>();
        List<Photo> trashedCloud = new ArrayList<>();
        if (cloudScanned) for (Photo photo : cloud) if (photo != null && uid.equals(photo.getOwnerUid())) {
            if (photo.isTrashed()) trashedCloud.add(photo); else validCloud.add(photo);
        }
        List<LocalMediaPhoto> validLocal = new ArrayList<>();
        for (LocalMediaPhoto local : locals) if (local != null && !PhotoVaultTrashCache.isLocalMediaTrashed(localTrash, local)
                && !matchesCloudTrash(local, trashedCloud)) validLocal.add(local);

        int total = validLocal.size() + validCloud.size();
        long bytes = 0;
        boolean partialComparison = false;
        int unknownVideoSizes = 0;
        StringBuilder warning = new StringBuilder(initialWarning);
        try {
            checkCancelled(session);
            int processed = 0;
            for (LocalMediaPhoto local : validLocal) {
                checkCancelled(session);
                Features features = new Features();
                if (!local.isVideo()) {
                    try {
                        features.digest = sha256(context, Uri.parse(local.getContentUri()), session);
                        features.digestVerified = features.digest != null;
                        Bitmap bitmap = decodeLocal(context, Uri.parse(local.getContentUri()));
                        if (bitmap != null) {
                            features.gray = SmartCleanupImageAnalyzer.graySignature(bitmap);
                            features.phash = SmartCleanupImageAnalyzer.perceptualHash(features.gray);
                            bitmap.recycle();
                        } else partialComparison = true;
                    } catch (CancelledException cancelled) { throw cancelled; }
                    catch (Exception inaccessible) { partialComparison = true; }
                }
                localFeatures.put(local, features);
                if (local.getSizeBytes() > 0) bytes += local.getSizeBytes();
                checkCancelled(session);
                processed++;
                listener.onProgress("Đang phân tích media trên thiết bị", processed, total);
            }

            Map<String, Photo> cloudByUri = new HashMap<>();
            Map<Long, Photo> cloudByLocalId = new HashMap<>();
            Map<String, Photo> cloudByHash = new HashMap<>();
            for (Photo photo : validCloud) {
                if (photo.getLocalMediaUri() != null && !photo.getLocalMediaUri().isEmpty()) cloudByUri.putIfAbsent(photo.getLocalMediaUri(), photo);
                if (photo.getLocalMediaId() > 0 && photo.getLocalDateModified() > 0) cloudByLocalId.putIfAbsent(photo.getLocalMediaId(), photo);
                if (photo.getContentHash() != null && !photo.getContentHash().isEmpty()) cloudByHash.putIfAbsent(photo.getContentHash().toLowerCase(Locale.US), photo);
            }
            Set<String> usedCloud = new HashSet<>();
            Map<LocalMediaPhoto, Photo> linked = new HashMap<>();
            for (LocalMediaPhoto local : validLocal) {
                checkCancelled(session);
                Photo match = cloudByUri.get(local.getContentUri());
                if (match == null && local.getDateModified() > 0) {
                    Photo candidate = cloudByLocalId.get(local.getMediaId());
                    if (candidate != null && candidate.getLocalDateModified() == local.getDateModified()) match = candidate;
                }
                Features f = localFeatures.get(local);
                if (match == null && f != null && f.digest != null) match = cloudByHash.get(f.digest.toLowerCase(Locale.US));
                if (match != null && usedCloud.add(match.getId())) linked.put(local, match);
            }

            Map<SmartCleanupMedia, Features> mediaFeatures = new LinkedHashMap<>();
            for (LocalMediaPhoto local : validLocal) {
                Photo paired = linked.get(local);
                if (paired != null) usedCloud.add(paired.getId());
                Features feature = localFeatures.get(local);
                SmartCleanupMedia media = new SmartCleanupMedia("local:" + local.getContentUri(), local, paired,
                        local.isVideo(), local.getSizeBytes() > 0 ? local.getSizeBytes() : -1,
                        feature == null ? null : feature.digest, feature != null && feature.digestVerified,
                        feature == null || feature.gray == null ? null : feature.phash);
                all.add(media);
                if (!local.isVideo() && feature != null && feature.gray != null) mediaFeatures.put(media, feature);
            }

            processed = validLocal.size();
            long downloadedRemoteBytes = 0L;
            for (Photo photo : validCloud) {
                checkCancelled(session);
                if (usedCloud.contains(photo.getId())) { processed++; continue; }
                boolean video = "video".equalsIgnoreCase(photo.getMediaType());
                long remoteSize = -1;
                Features feature = new Features();
                if (!video && photo.getContentHash() != null && !photo.getContentHash().isEmpty()) {
                    feature.digest = photo.getContentHash().toLowerCase(Locale.US);
                    feature.digestVerified = true; // Existing cloud content_hash is the repository's integrity metadata.
                }
                if (!video && photo.getImageUrl() != null && photo.getImageUrl().startsWith("http")) {
                    long remaining = MAX_REMOTE_SCAN_BYTES - downloadedRemoteBytes;
                    File temp = remaining > 0 ? downloadToTemp(context, photo.getImageUrl(), Math.min(MAX_REMOTE_BYTES, remaining), session) : null;
                    if (temp != null) {
                        try {
                            remoteSize = temp.length();
                            downloadedRemoteBytes += remoteSize;
                            String downloadedDigest = sha256File(temp, session);
                            if (feature.digest != null && !feature.digest.equalsIgnoreCase(downloadedDigest)) {
                                partialComparison = true; // stale cloud content_hash; use bytes actually fetched
                                feature.digestVerified = false;
                            }
                            feature.digest = downloadedDigest;
                            feature.digestVerified = true;
                            Bitmap bitmap = decodeFile(temp);
                            if (bitmap != null) {
                                feature.gray = SmartCleanupImageAnalyzer.graySignature(bitmap);
                                feature.phash = SmartCleanupImageAnalyzer.perceptualHash(feature.gray);
                                bitmap.recycle();
                            } else partialComparison = true;
                        } finally { //noinspection ResultOfMethodCallIgnored
                            temp.delete();
                        }
                    } else partialComparison = true;
                } else if (!video) partialComparison = true;
                if (video) {
                    remoteSize = remoteLength(photo.getImageUrl(), session);
                    if (remoteSize <= 0) unknownVideoSizes++;
                }
                SmartCleanupMedia media = new SmartCleanupMedia("cloud:" + photo.getId(), null, photo, video,
                        remoteSize > 0 ? remoteSize : -1, feature.digest, feature.digestVerified,
                        feature.gray == null ? null : feature.phash);
                all.add(media);
                if (feature.gray != null) mediaFeatures.put(media, feature);
                if (remoteSize > 0) bytes += remoteSize;
                checkCancelled(session);
                processed++;
                listener.onProgress(video ? "Đang đọc metadata video cloud" : "Đang phân tích media cloud", processed, total);
            }

            List<Group> duplicates = exactGroups(all);
            GroupAnalysis groupAnalysis = perceptualGroups(mediaFeatures, session);
            List<Group> similar = groupAnalysis.groups;
            partialComparison |= groupAnalysis.truncated;
            List<SmartCleanupMedia> videos = new ArrayList<>(), unknownVideos = new ArrayList<>();
            for (SmartCleanupMedia item : all) if (item.video) {
                if (item.sizeBytes > 0) videos.add(item); else unknownVideos.add(item);
            }
            videos.sort((a, b) -> Long.compare(b.sizeBytes, a.sizeBytes));
            if (videos.size() > 20) videos = new ArrayList<>(videos.subList(0, 20));
            if (partialComparison && warning.length() == 0) warning.append("Một phần nội dung không thể so sánh hoặc phạm vi so sánh đã được giới hạn để giữ hiệu năng.");
            if (unknownVideoSizes > 0) warning.append(warning.length() == 0 ? "" : " ").append(unknownVideoSizes).append(" video cloud không có metadata dung lượng và đã bị loại khỏi xếp hạng.");
            checkCancelled(session);
            Result result = new Result(duplicates, similar, videos, unknownVideos, all, bytes, localScanned,
                    cloudScanned, partialComparison, warning.toString());
            synchronized (SmartCleanupScanner.class) {
                if (!session.cancelled.get() && uid.equals(latestUid)) latestResult = result;
            }
            listener.onComplete(result);
        } catch (CancelledException ignored) {
            listener.onCancelled();
        } catch (Exception e) {
            Log.e(TAG, "Smart Cleanup scan failed", e);
            Result result = new Result(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList(), 0, localScanned, cloudScanned, true,
                    "Quét chưa hoàn tất: " + (e.getLocalizedMessage() == null ? "lỗi đọc media" : e.getLocalizedMessage()));
            synchronized (SmartCleanupScanner.class) { if (!session.cancelled.get() && uid.equals(latestUid)) latestResult = result; }
            listener.onComplete(result);
        }
    }

    private static boolean matchesCloudTrash(LocalMediaPhoto local, List<Photo> trashedCloud) {
        for (Photo photo : trashedCloud) {
            if (photo.getLocalMediaUri() != null && !photo.getLocalMediaUri().isEmpty()
                    && photo.getLocalMediaUri().equals(local.getContentUri())) return true;
            if (photo.getLocalMediaId() > 0 && photo.getLocalMediaId() == local.getMediaId()
                    && photo.getLocalDateModified() > 0 && photo.getLocalDateModified() == local.getDateModified()) return true;
        }
        return false;
    }

    private static List<Group> exactGroups(List<SmartCleanupMedia> media) {
        Map<String, List<SmartCleanupMedia>> groups = new LinkedHashMap<>();
        for (SmartCleanupMedia item : media) if (item.digestVerified && item.contentHash != null && !item.contentHash.isEmpty())
            groups.computeIfAbsent(item.contentHash, ignored -> new ArrayList<>()).add(item);
        List<Group> result = new ArrayList<>();
        for (List<SmartCleanupMedia> group : groups.values()) if (group.size() > 1) result.add(new Group(true, false, group));
        return result;
    }

    private static GroupAnalysis perceptualGroups(Map<SmartCleanupMedia, Features> features, Session session) {
        List<SmartCleanupMedia> items = new ArrayList<>(features.keySet());
        // LSH: equal 8-bit bands produce candidates. This deliberately favors precision and bounded work.
        Map<Integer, List<Integer>> buckets = new HashMap<>();
        Set<Long> candidatePairs = new HashSet<>();
        boolean partial = false;
        for (int i = 0; i < items.size(); i++) {
            long hash = features.get(items.get(i)).phash;
            for (int band = 0; band < 8; band++) {
                int key = (band << 8) | (int) ((hash >>> (band * 8)) & 0xff);
                List<Integer> previous = buckets.computeIfAbsent(key, ignored -> new ArrayList<>());
                for (int j : previous) {
                    long pair = (((long) j) << 32) | (i & 0xffffffffL);
                    if (candidatePairs.size() >= MAX_PAIR_COMPARISONS) { partial = true; break; }
                    candidatePairs.add(pair);
                }
                previous.add(i);
            }
            checkCancelled(session);
        }
        List<List<SmartCleanupMedia>> duplicateGroups = new ArrayList<>();
        List<List<SmartCleanupMedia>> similarGroups = new ArrayList<>();
        for (long pair : candidatePairs) {
            int left = (int) (pair >>> 32), right = (int) pair;
            SmartCleanupMedia a = items.get(left), b = items.get(right);
            if (a.contentHash != null && a.contentHash.equalsIgnoreCase(b.contentHash)) continue;
            Features fa = features.get(a), fb = features.get(b);
            int distance = SmartCleanupImageAnalyzer.hammingDistance(fa.phash, fb.phash);
            if (distance > SmartCleanupImageAnalyzer.SIMILAR_MAX_DISTANCE) continue;
            double ssim = SmartCleanupImageAnalyzer.luminanceSsim(fa.gray, fb.gray);
            boolean duplicate = SmartCleanupImageAnalyzer.isDuplicate(fa.phash, fb.phash, ssim);
            boolean similar = SmartCleanupImageAnalyzer.isSimilar(fa.phash, fb.phash, ssim);
            if (!duplicate && !similar) continue;
            List<List<SmartCleanupMedia>> target = duplicate ? duplicateGroups : similarGroups;
            List<SmartCleanupMedia> groupA = null, groupB = null;
            for (List<SmartCleanupMedia> group : target) {
                if (group.contains(a)) groupA = group;
                if (group.contains(b)) groupB = group;
            }
            if (groupA != null && groupB != null && groupA != groupB) continue;
            List<SmartCleanupMedia> chosen = groupA != null ? groupA : groupB;
            if (chosen != null) {
                boolean fits = true;
                for (SmartCleanupMedia member : chosen) {
                    if (member.equals(a) || member.equals(b)) continue;
                    Features fm = features.get(member);
                    double s = SmartCleanupImageAnalyzer.luminanceSsim(fa.gray, fm.gray);
                    if (duplicate ? !SmartCleanupImageAnalyzer.isDuplicate(fa.phash, fm.phash, s)
                            : !SmartCleanupImageAnalyzer.isSimilar(fa.phash, fm.phash, s)) { fits = false; break; }
                }
                if (!fits) continue;
            } else { chosen = new ArrayList<>(); target.add(chosen); }
            if (!chosen.contains(a)) chosen.add(a);
            if (!chosen.contains(b)) chosen.add(b);
            checkCancelled(session);
        }
        List<Group> out = new ArrayList<>();
        for (List<SmartCleanupMedia> group : duplicateGroups) if (group.size() > 1) out.add(new Group(false, false, group));
        for (List<SmartCleanupMedia> group : similarGroups) if (group.size() > 1) out.add(new Group(false, true, group));
        return new GroupAnalysis(out, partial);
    }

    private static String sha256(Context context, Uri uri, Session session) throws Exception {
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) return null;
            return digest(input, session);
        }
    }
    private static String sha256File(File file, Session session) throws Exception {
        try (InputStream input = new FileInputStream(file)) { return digest(input, session); }
    }
    private static String digest(InputStream input, Session session) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[32 * 1024]; int read;
        while ((read = input.read(buffer)) != -1) { checkCancelled(session); md.update(buffer, 0, read); }
        StringBuilder result = new StringBuilder(64);
        for (byte b : md.digest()) result.append(String.format(Locale.US, "%02x", b & 255));
        return result.toString();
    }
    private static Bitmap decodeLocal(Context context, Uri uri) {
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) return null;
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(input, null, bounds);
            int sample = sample(bounds.outWidth, bounds.outHeight);
            try (InputStream second = context.getContentResolver().openInputStream(uri)) {
                if (second == null) return null;
                BitmapFactory.Options opts = new BitmapFactory.Options(); opts.inSampleSize = sample; opts.inPreferredConfig = Bitmap.Config.RGB_565;
                return BitmapFactory.decodeStream(second, null, opts);
            }
        } catch (Exception e) { return null; }
    }
    private static Bitmap decodeFile(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        BitmapFactory.Options opts = new BitmapFactory.Options(); opts.inSampleSize = sample(bounds.outWidth, bounds.outHeight); opts.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
    }
    private static int sample(int width, int height) {
        int sample = 1; while (width / sample > 1024 || height / sample > 1024) sample *= 2; return sample;
    }
    private static File downloadToTemp(Context context, String url, long allowedBytes, Session session) {
        HttpURLConnection connection = null; File file = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(6000); connection.setReadTimeout(10000); connection.setInstanceFollowRedirects(true);
            int status = connection.getResponseCode(); if (status < 200 || status >= 300) return null;
            int length = connection.getContentLength(); if (length > allowedBytes) return null;
            file = File.createTempFile("pv-cleanup-", ".bin", context.getCacheDir());
            try (InputStream in = new BufferedInputStream(connection.getInputStream()); FileOutputStream out = new FileOutputStream(file)) {
                byte[] buffer = new byte[32 * 1024]; int count; long total = 0;
                while ((count = in.read(buffer)) != -1) {
                    checkCancelled(session); total += count; if (total > allowedBytes) throw new IllegalStateException("Cloud media exceeds analysis limit");
                    out.write(buffer, 0, count);
                }
            }
            return file;
        } catch (CancelledException cancelled) { if (file != null) file.delete(); throw cancelled; }
        catch (Exception e) { if (file != null) file.delete(); return null; }
        finally { if (connection != null) connection.disconnect(); }
    }
    private static long remoteLength(String url, Session session) {
        if (url == null || !url.startsWith("http")) return -1;
        HttpURLConnection connection = null;
        try {
            checkCancelled(session); connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("HEAD"); connection.setConnectTimeout(4000); connection.setReadTimeout(4000);
            connection.getResponseCode(); return connection.getContentLengthLong();
        } catch (CancelledException cancelled) { throw cancelled; }
        catch (Exception ignored) { return -1; }
        finally { if (connection != null) connection.disconnect(); }
    }
    private static void cleanupAbandonedDownloads(Context context) {
        File cache = context.getCacheDir();
        File[] old = cache == null ? null : cache.listFiles((dir, name) -> name.startsWith("pv-cleanup-") && name.endsWith(".bin"));
        if (old != null) for (File file : old) { //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }
    private static void checkCancelled(Session session) { if (session.cancelled.get() || Thread.currentThread().isInterrupted()) throw new CancelledException(); }
    private static final class CancelledException extends RuntimeException { }
}
