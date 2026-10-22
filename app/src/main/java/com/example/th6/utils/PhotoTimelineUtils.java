package com.example.th6.utils;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class PhotoTimelineUtils {
    public static final int VIEW_MODE_ALL = 0;
    public static final int VIEW_MODE_DAYS = 1;
    public static final int VIEW_MODE_MONTHS = 2;
    public static final int VIEW_MODE_YEARS = 3;

    private static final SimpleDateFormat dayFormat = new SimpleDateFormat("dd 'tháng' MM, yyyy", Locale.getDefault());
    private static final SimpleDateFormat monthFormat = new SimpleDateFormat("'Tháng' MM, yyyy", Locale.getDefault());
    private static final SimpleDateFormat yearFormat = new SimpleDateFormat("yyyy", Locale.getDefault());

    public static String getDayLabel(long timestamp) {
        return dayFormat.format(new Date(timestamp));
    }

    public static String getMonthLabel(long timestamp) {
        return monthFormat.format(new Date(timestamp));
    }

    public static String getYearLabel(long timestamp) {
        return yearFormat.format(new Date(timestamp));
    }

    public static String getGroupKey(long timestamp, int viewMode) {
        if (timestamp <= 0) return "unknown";
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(timestamp);
        switch (viewMode) {
            case VIEW_MODE_DAYS:
                return calendar.get(Calendar.YEAR) + "-" + calendar.get(Calendar.DAY_OF_YEAR);
            case VIEW_MODE_MONTHS:
                return calendar.get(Calendar.YEAR) + "-" + calendar.get(Calendar.MONTH);
            case VIEW_MODE_YEARS:
                return String.valueOf(calendar.get(Calendar.YEAR));
            default:
                return "";
        }
    }

    public static String getGroupLabel(long timestamp, int viewMode) {
        if (timestamp <= 0) {
            if (viewMode == VIEW_MODE_DAYS) return "Ngày không xác định";
            if (viewMode == VIEW_MODE_MONTHS) return "Tháng không xác định";
            return "Năm không xác định";
        }
        Calendar date = Calendar.getInstance();
        date.setTimeInMillis(timestamp);
        if (viewMode == VIEW_MODE_DAYS) {
            Calendar today = Calendar.getInstance();
            if (isSameDay(date, today)) return "Hôm nay";
            today.add(Calendar.DAY_OF_YEAR, -1);
            if (isSameDay(date, today)) return "Hôm qua";
            return getDayLabel(timestamp);
        }
        if (viewMode == VIEW_MODE_MONTHS) return getMonthLabel(timestamp);
        return getYearLabel(timestamp);
    }

    private static boolean isSameDay(Calendar first, Calendar second) {
        return first.get(Calendar.YEAR) == second.get(Calendar.YEAR)
                && first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR);
    }
}
