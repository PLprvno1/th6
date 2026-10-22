package com.example.th6.utils;

import com.example.th6.R;
import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.assertEquals;

public class MediaTimelineUtilsTest {
    private static final class Item {
        final String name; final long time; final boolean video;
        Item(String name,long time,boolean video){this.name=name;this.time=time;this.video=video;}
    }

    private final List<Item> source=Arrays.asList(
            new Item("z-video",timestamp(2026,1,1),true),
            new Item("b-photo",timestamp(2025,2,1),false),
            new Item("a-photo",timestamp(2025,1,1),false));

    private List<Item> query(int filter,int mode,int sort) {
        return MediaTimelineUtils.filterAndSort(source,filter,mode,sort,item->item.time,item->item.name,item->item.video);
    }

    @Test public void mediaTypeFilterKeepsOnlyRequestedType() {
        assertEquals(Arrays.asList("b-photo","a-photo"),names(query(MediaTimelineUtils.FILTER_IMAGES,PhotoTimelineUtils.VIEW_MODE_ALL,R.id.sort_date_desc)));
        assertEquals(Arrays.asList("z-video"),names(query(MediaTimelineUtils.FILTER_VIDEOS,PhotoTimelineUtils.VIEW_MODE_ALL,R.id.sort_date_desc)));
        assertEquals(3,query(MediaTimelineUtils.FILTER_ALL,PhotoTimelineUtils.VIEW_MODE_ALL,R.id.sort_date_desc).size());
    }

    @Test public void dateAndNameSortAreAppliedAfterFiltering() {
        assertEquals(Arrays.asList("a-photo","b-photo","z-video"),names(query(MediaTimelineUtils.FILTER_ALL,PhotoTimelineUtils.VIEW_MODE_ALL,R.id.sort_date_asc)));
        assertEquals(Arrays.asList("a-photo","b-photo"),names(query(MediaTimelineUtils.FILTER_IMAGES,PhotoTimelineUtils.VIEW_MODE_ALL,R.id.sort_name_asc)));
        assertEquals(Arrays.asList("b-photo","a-photo"),names(query(MediaTimelineUtils.FILTER_IMAGES,PhotoTimelineUtils.VIEW_MODE_ALL,R.id.sort_name_desc)));
    }

    @Test public void groupedNameSortKeepsTimeGroupsNewestFirst() {
        List<Item> sorted=query(MediaTimelineUtils.FILTER_ALL,PhotoTimelineUtils.VIEW_MODE_YEARS,R.id.sort_name_asc);
        assertEquals("z-video",sorted.get(0).name);
        assertEquals("a-photo",sorted.get(1).name);
        assertEquals("b-photo",sorted.get(2).name);
    }

    private List<String> names(List<Item> items) {
        java.util.ArrayList<String> names=new java.util.ArrayList<>();
        for(Item item:items) names.add(item.name);
        return names;
    }

    private static long timestamp(int year,int month,int day) {
        java.util.Calendar calendar=java.util.Calendar.getInstance();
        calendar.clear(); calendar.set(year,month-1,day,12,0,0);
        return calendar.getTimeInMillis();
    }
}
