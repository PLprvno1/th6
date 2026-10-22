package com.example.th6.utils;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/** Account-scoped favorites for videos that have no cloud photo row yet. */
public final class LocalVideoFavorites {
    private static final String PREFS = "local_video_favorites";
    private LocalVideoFavorites() {}

    private static String key(String uid) { return "uid:" + uid; }

    public static boolean contains(Context context, String uid, String uri) {
        return uri != null && preferences(context).getStringSet(key(uid), new HashSet<>()).contains(uri);
    }

    public static void set(Context context, String uid, String uri, boolean favorite) {
        if (uid == null || uid.isEmpty() || uri == null || uri.isEmpty()) return;
        SharedPreferences prefs = preferences(context);
        Set<String> values = new HashSet<>(prefs.getStringSet(key(uid), new HashSet<>()));
        if (favorite) values.add(uri); else values.remove(uri);
        prefs.edit().putStringSet(key(uid), values).apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
