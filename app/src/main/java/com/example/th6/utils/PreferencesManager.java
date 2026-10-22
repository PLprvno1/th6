package com.example.th6.utils;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

public class PreferencesManager {
    private static final String PREF_NAME = "photovault_prefs";
    private static final String KEY_DARK_MODE = "key_dark_mode";
    private static final String KEY_SORT_ORDER = "key_sort_order";

    public static final String SORT_DATE_DESC = "date_desc";
    public static final String SORT_DATE_ASC = "date_asc";
    public static final String SORT_NAME_ASC = "name_asc";
    public static final String SORT_NAME_DESC = "name_desc";

    private final SharedPreferences prefs;

    public PreferencesManager(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public boolean isDarkMode() {
        return prefs.getBoolean(KEY_DARK_MODE, false);
    }

    public void setDarkMode(boolean enabled) {
        prefs.edit().putBoolean(KEY_DARK_MODE, enabled).apply();
        applyTheme(enabled);
    }

    public static void applyTheme(boolean isDarkMode) {
        if (isDarkMode) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        }
    }

    public String getSortOrder() {
        return prefs.getString(KEY_SORT_ORDER, SORT_DATE_DESC);
    }

    public void setSortOrder(String sortOrder) {
        prefs.edit().putString(KEY_SORT_ORDER, sortOrder).apply();
    }
}
