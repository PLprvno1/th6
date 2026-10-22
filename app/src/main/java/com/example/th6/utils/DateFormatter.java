package com.example.th6.utils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class DateFormatter {
    private static final SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault());

    public static String formatTimestamp(long timestamp) {
        if (timestamp <= 0) return "";
        return sdf.format(new Date(timestamp));
    }
}
