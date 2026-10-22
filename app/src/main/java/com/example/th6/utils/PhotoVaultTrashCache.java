package com.example.th6.utils;

import android.content.Context;
import android.content.SharedPreferences;

import com.example.th6.models.Photo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import com.example.th6.models.LocalMediaPhoto;

/** Local, account-scoped cache of server-confirmed trash identities for offline library scans. */
public final class PhotoVaultTrashCache {
    private static final String PREFS = "photovault_trash_cache";

    private PhotoVaultTrashCache() { }

    public static synchronized List<Photo> get(Context context, String uid) {
        List<Photo> result = new ArrayList<>();
        if (context == null || uid == null || uid.isEmpty()) return result;
        String encoded = prefs(context).getString(uid, "[]");
        try {
            JSONArray entries = new JSONArray(encoded);
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.optJSONObject(i);
                if (entry == null) continue;
                Photo photo = new Photo();
                photo.setId(entry.optString("id", ""));
                photo.setOwnerUid(uid);
                photo.setTrashed(true);
                photo.setLocalMediaUri(entry.optString("uri", ""));
                photo.setLocalMediaId(entry.optLong("mediaId", 0L));
                photo.setLocalDateModified(entry.optLong("modified", 0L));
                photo.setContentHash(entry.isNull("hash") ? null : entry.optString("hash", null));
                photo.setName(entry.optString("name", ""));
                photo.setImageUrl(entry.optString("imageUrl", ""));
                photo.setMediaType(entry.optString("mediaType", "image"));
                photo.setCreatedAt(entry.optLong("createdAt", 0L));
                result.add(photo);
            }
        } catch (Exception ignored) { }
        return result;
    }

    /** Replace only after a successful, complete cloud query for this user. */
    public static synchronized void replace(Context context, String uid, List<Photo> trashedPhotos) {
        if (context == null || uid == null || uid.isEmpty()) return;
        JSONArray entries = new JSONArray();
        if (trashedPhotos != null) for (Photo photo : trashedPhotos) {
            if (photo == null || !photo.isTrashed() || !uid.equals(photo.getOwnerUid())) continue;
            entries.put(encode(photo));
        }
        prefs(context).edit().putString(uid, entries.toString()).commit();
    }

    public static synchronized void add(Context context, String uid, Photo photo) {
        if (context == null || uid == null || uid.isEmpty() || photo == null || !uid.equals(photo.getOwnerUid())) return;
        List<Photo> entries = get(context, uid);
        entries.removeIf(existing -> existing.getId().equals(photo.getId()));
        photo.setTrashed(true);
        entries.add(photo);
        write(context, uid, entries);
    }

    /** Store a device-only soft-delete record; the MediaStore file remains untouched. */
    public static synchronized void addLocal(Context context, Photo photo) {
        if (photo == null || !"local".equals(photo.getOwnerUid())) return;
        List<Photo> entries = get(context, "local");
        entries.removeIf(existing -> existing.getId().equals(photo.getId()));
        photo.setTrashed(true);
        entries.add(photo);
        write(context, "local", entries);
    }

    public static synchronized List<Photo> getLocal(Context context) {
        return get(context, "local");
    }

    /** Stable identities used by album/library scans to hide device-only trashed rows. */
    public static synchronized Set<String> getLocalMediaIdentityKeys(Context context) {
        Set<String> keys = new LinkedHashSet<>();
        for (Photo photo : getLocal(context)) {
            if (!photo.isTrashed()) continue;
            if (photo.getLocalMediaUri() != null && !photo.getLocalMediaUri().isEmpty()) keys.add("uri:" + photo.getLocalMediaUri());
            else if (photo.getLocalMediaId() > 0) keys.add("id:" + photo.getLocalMediaId());
        }
        return keys;
    }

    public static boolean isLocalMediaTrashed(Set<String> identities, LocalMediaPhoto media) {
        if (identities == null || media == null) return false;
        String uri = media.getContentUri();
        return (uri != null && identities.contains("uri:" + uri))
                || (media.getMediaId() > 0 && identities.contains("id:" + media.getMediaId()));
    }

    public static synchronized void remove(Context context, String uid, String photoId) {
        if (context == null || uid == null || uid.isEmpty() || photoId == null || photoId.isEmpty()) return;
        List<Photo> entries = get(context, uid);
        entries.removeIf(existing -> photoId.equals(existing.getId()));
        write(context, uid, entries);
    }

    private static JSONObject encode(Photo photo) {
        JSONObject entry = new JSONObject();
        try {
            entry.put("id", photo.getId());
            entry.put("uri", photo.getLocalMediaUri());
            entry.put("mediaId", photo.getLocalMediaId());
            entry.put("modified", photo.getLocalDateModified());
            entry.put("hash", photo.getContentHash());
            entry.put("name", photo.getName());
            entry.put("imageUrl", photo.getImageUrl());
            entry.put("mediaType", photo.getMediaType());
            entry.put("createdAt", photo.getCreatedAt());
        } catch (Exception ignored) { }
        return entry;
    }

    private static void write(Context context, String uid, List<Photo> photos) {
        JSONArray entries = new JSONArray();
        for (Photo photo : photos) entries.put(encode(photo));
        prefs(context).edit().putString(uid, entries.toString()).commit();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
