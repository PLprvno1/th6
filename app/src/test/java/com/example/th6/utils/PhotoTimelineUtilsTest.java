package com.example.th6.utils;

import org.junit.Test;

import java.util.Calendar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class PhotoTimelineUtilsTest {
    private long timestamp(int year, int month, int day) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(year, month - 1, day, 12, 0, 0);
        return calendar.getTimeInMillis();
    }

    @Test
    public void dayGroupsUseCalendarDate() {
        long morning = timestamp(2026, 10, 15);
        long sameDay = timestamp(2026, 10, 15) + 60 * 60 * 1000L;
        long nextDay = timestamp(2026, 10, 16);

        assertEquals(PhotoTimelineUtils.getGroupKey(morning, PhotoTimelineUtils.VIEW_MODE_DAYS),
                PhotoTimelineUtils.getGroupKey(sameDay, PhotoTimelineUtils.VIEW_MODE_DAYS));
        assertNotEquals(PhotoTimelineUtils.getGroupKey(morning, PhotoTimelineUtils.VIEW_MODE_DAYS),
                PhotoTimelineUtils.getGroupKey(nextDay, PhotoTimelineUtils.VIEW_MODE_DAYS));
    }

    @Test
    public void monthGroupsKeepYearInTheKey() {
        long october2025 = timestamp(2025, 10, 1);
        long october2026 = timestamp(2026, 10, 1);

        assertNotEquals(PhotoTimelineUtils.getGroupKey(october2025, PhotoTimelineUtils.VIEW_MODE_MONTHS),
                PhotoTimelineUtils.getGroupKey(october2026, PhotoTimelineUtils.VIEW_MODE_MONTHS));
        assertEquals(PhotoTimelineUtils.getGroupKey(timestamp(2026, 10, 1), PhotoTimelineUtils.VIEW_MODE_MONTHS),
                PhotoTimelineUtils.getGroupKey(timestamp(2026, 10, 31), PhotoTimelineUtils.VIEW_MODE_MONTHS));
    }

    @Test
    public void yearGroupsSeparateYearsAndCombineMonths() {
        assertEquals(PhotoTimelineUtils.getGroupKey(timestamp(2026, 1, 2), PhotoTimelineUtils.VIEW_MODE_YEARS),
                PhotoTimelineUtils.getGroupKey(timestamp(2026, 12, 31), PhotoTimelineUtils.VIEW_MODE_YEARS));
        assertNotEquals(PhotoTimelineUtils.getGroupKey(timestamp(2025, 12, 31), PhotoTimelineUtils.VIEW_MODE_YEARS),
                PhotoTimelineUtils.getGroupKey(timestamp(2026, 1, 1), PhotoTimelineUtils.VIEW_MODE_YEARS));
    }

    @Test
    public void missingTimestampHasStableUnknownGroup() {
        assertEquals("unknown", PhotoTimelineUtils.getGroupKey(0, PhotoTimelineUtils.VIEW_MODE_DAYS));
    }
}
