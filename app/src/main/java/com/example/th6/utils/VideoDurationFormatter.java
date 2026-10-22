package com.example.th6.utils;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class VideoDurationFormatter {
    public static String formatDuration(long durationMs) {
        if (durationMs <= 0) return "00:00";
        long hours = TimeUnit.MILLISECONDS.toHours(durationMs);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(durationMs) % 60;
        long seconds = TimeUnit.MILLISECONDS.toSeconds(durationMs) % 60;

        if (hours > 0) {
            return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds);
        } else {
            return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
        }
    }
}
