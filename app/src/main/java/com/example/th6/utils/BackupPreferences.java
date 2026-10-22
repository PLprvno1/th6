package com.example.th6.utils;

import android.content.Context;
import android.content.SharedPreferences;

public class BackupPreferences {

    private static final String PREF_PREFIX = "photovault_backup_prefs_";
    private static final String KEY_BACKUP_ENABLED = "key_backup_enabled";
    private static final String KEY_WIFI_ONLY = "key_wifi_only";
    private static final String KEY_PAUSED = "key_paused";
    private static final String KEY_LAST_SYNC_TIME = "key_last_sync_time";
    private static final String KEY_ONBOARDING_SHOWN = "key_onboarding_shown";

    private final SharedPreferences prefs;

    public BackupPreferences(Context context, String uid) {
        String prefName = PREF_PREFIX + (uid != null ? uid : "guest");
        prefs = context.getApplicationContext().getSharedPreferences(prefName, Context.MODE_PRIVATE);
    }

    public boolean isBackupEnabled() {
        return prefs.getBoolean(KEY_BACKUP_ENABLED, false);
    }

    public void setBackupEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_BACKUP_ENABLED, enabled).apply();
    }

    public boolean isWifiOnly() {
        return prefs.getBoolean(KEY_WIFI_ONLY, true);
    }

    public void setWifiOnly(boolean wifiOnly) {
        prefs.edit().putBoolean(KEY_WIFI_ONLY, wifiOnly).apply();
    }

    public boolean isPaused() {
        return prefs.getBoolean(KEY_PAUSED, false);
    }

    public void setPaused(boolean paused) {
        prefs.edit().putBoolean(KEY_PAUSED, paused).apply();
    }

    public long getLastSyncTime() {
        return prefs.getLong(KEY_LAST_SYNC_TIME, 0);
    }

    public void setLastSyncTime(long timestamp) {
        prefs.edit().putLong(KEY_LAST_SYNC_TIME, timestamp).apply();
    }

    public boolean isOnboardingShown() {
        return prefs.getBoolean(KEY_ONBOARDING_SHOWN, false);
    }

    public void setOnboardingShown(boolean shown) {
        prefs.edit().putBoolean(KEY_ONBOARDING_SHOWN, shown).apply();
    }
}
