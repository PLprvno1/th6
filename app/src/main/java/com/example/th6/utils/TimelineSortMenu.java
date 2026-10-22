package com.example.th6.utils;

import android.content.Context;
import android.view.Menu;
import android.view.View;

import androidx.appcompat.widget.PopupMenu;

import com.example.th6.R;

import java.util.function.IntConsumer;

public final class TimelineSortMenu {
    private TimelineSortMenu() { }

    public static void show(Context context, View anchor, int selectedId, IntConsumer onSelected) {
        PopupMenu popup = new PopupMenu(context, anchor);
        Menu menu = popup.getMenu();
        menu.add(Menu.NONE, R.id.sort_date_desc, 0, R.string.sort_date_desc);
        menu.add(Menu.NONE, R.id.sort_date_asc, 1, R.string.sort_date_asc);
        menu.add(Menu.NONE, R.id.sort_name_asc, 2, R.string.sort_name_asc);
        menu.add(Menu.NONE, R.id.sort_name_desc, 3, R.string.sort_name_desc);
        for (int i = 0; i < menu.size(); i++) {
            menu.getItem(i).setCheckable(true).setChecked(menu.getItem(i).getItemId() == selectedId);
        }
        popup.setOnMenuItemClickListener(item -> { onSelected.accept(item.getItemId()); return true; });
        popup.show();
    }
}
