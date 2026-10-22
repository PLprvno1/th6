package com.example.th6.utils;

import androidx.annotation.Nullable;

import com.example.th6.R;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/** Shared filtering and ordering rules for Library, Album and Favorites timelines. */
public final class MediaTimelineUtils {
    public static final int FILTER_ALL = 0;
    public static final int FILTER_IMAGES = 1;
    public static final int FILTER_VIDEOS = 2;

    private MediaTimelineUtils() { }

    public static <T> List<T> filterAndSort(@Nullable List<T> source, int mediaFilter,
                                             int viewMode, int sortMode,
                                             ToLongFunction<T> timestamp,
                                             Function<T, String> name,
                                             Predicate<T> isVideo) {
        List<T> result = new ArrayList<>();
        if (source != null) {
            for (T item : source) {
                boolean video = isVideo.test(item);
                if (mediaFilter == FILTER_ALL
                        || (mediaFilter == FILTER_VIDEOS && video)
                        || (mediaFilter == FILTER_IMAGES && !video)) result.add(item);
            }
        }
        Collator collator = Collator.getInstance(new Locale("vi", "VN"));
        collator.setStrength(Collator.SECONDARY);
        collator.setDecomposition(Collator.CANONICAL_DECOMPOSITION);
        Comparator<T> comparator;
        if (sortMode == R.id.sort_date_asc || sortMode == R.id.sort_date_desc) {
            comparator = Comparator.comparingLong(timestamp);
            if (sortMode == R.id.sort_date_desc) comparator = comparator.reversed();
        } else {
            comparator = (left, right) -> {
                if (viewMode != PhotoTimelineUtils.VIEW_MODE_ALL) {
                    int groupOrder = Long.compare(groupSortTimestamp(timestamp.applyAsLong(right), viewMode),
                            groupSortTimestamp(timestamp.applyAsLong(left), viewMode));
                    if (groupOrder != 0) return groupOrder;
                }
                int order = collator.compare(safeName(name.apply(left)), safeName(name.apply(right)));
                return sortMode == R.id.sort_name_desc ? -order : order;
            };
        }
        result.sort(comparator);
        return result;
    }

    public static long groupSortTimestamp(long timestamp, int viewMode) {
        if (timestamp <= 0) return Long.MIN_VALUE;
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(timestamp);
        if (viewMode == PhotoTimelineUtils.VIEW_MODE_YEARS) {
            calendar.set(Calendar.DAY_OF_YEAR, 1);
            calendar.set(Calendar.MONTH, Calendar.JANUARY);
        }
        if (viewMode == PhotoTimelineUtils.VIEW_MODE_MONTHS || viewMode == PhotoTimelineUtils.VIEW_MODE_YEARS) {
            calendar.set(Calendar.DAY_OF_MONTH, 1);
        }
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private static String safeName(@Nullable String name) { return name == null ? "" : name; }
}
