package com.example.th6.adapters;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.example.th6.R;
import com.example.th6.models.AlbumMediaEntry;
import com.example.th6.utils.PhotoTimelineUtils;
import java.util.ArrayList;
import java.util.List;

public final class AlbumMediaAdapter extends RecyclerView.Adapter<AlbumMediaAdapter.Holder> {
    public interface Listener { void onClick(AlbumMediaEntry entry); void onLongClick(AlbumMediaEntry entry, View anchor); }
    private final Context context; private final Listener listener; private final List<AlbumMediaEntry> items = new ArrayList<>();
    private boolean selectionMode;
    private boolean showAlreadyStatus;
    private final List<Row> rows = new ArrayList<>();

    public AlbumMediaAdapter(Context context, Listener listener) { this.context=context; this.listener=listener; }
    public void setItems(List<AlbumMediaEntry> entries) { setTimelineItems(entries, PhotoTimelineUtils.VIEW_MODE_ALL); }
    public void setTimelineItems(List<AlbumMediaEntry> entries, int viewMode) {
        items.clear(); rows.clear();
        if(entries!=null) items.addAll(entries);
        String previousKey = null;
        for (AlbumMediaEntry entry : items) {
            if (viewMode != PhotoTimelineUtils.VIEW_MODE_ALL) {
                String key = PhotoTimelineUtils.getGroupKey(entry.getTimestamp(), viewMode);
                if (!key.equals(previousKey)) { rows.add(Row.header(PhotoTimelineUtils.getGroupLabel(entry.getTimestamp(), viewMode))); previousKey = key; }
            }
            rows.add(Row.media(entry));
        }
        notifyDataSetChanged();
    }
    public List<AlbumMediaEntry> getItems() { return items; }
    public void notifyEntryChanged(AlbumMediaEntry entry) {
        int index = items.indexOf(entry);
        if (index < 0) return;
        for (int i = 0, mediaIndex = 0; i < rows.size(); i++) {
            if (rows.get(i).entry != null) {
                if (mediaIndex++ == index) { notifyItemChanged(i); return; }
            }
        }
    }
    public void setSelectionMode(boolean value) { selectionMode=value; notifyDataSetChanged(); }
    public void setShowAlreadyStatus(boolean value) { showAlreadyStatus=value; notifyDataSetChanged(); }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent,int type) {
        if (type == 1) {
            TextView header = new TextView(context);
            header.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            header.setPadding(dp(12), dp(14), dp(12), dp(8)); header.setTextSize(18); header.setTypeface(null, android.graphics.Typeface.BOLD);
            android.util.TypedValue color = new android.util.TypedValue();
            if (context.getTheme().resolveAttribute(com.google.android.material.R.attr.colorOnBackground, color, true)) header.setTextColor(color.data);
            return new Holder(header);
        }
        return new Holder(LayoutInflater.from(context).inflate(R.layout.item_album_media,parent,false));
    }

    @Override public void onBindViewHolder(@NonNull Holder h,int position) {
        Row row = rows.get(position);
        if (row.entry == null) { h.header.setText(row.header); return; }
        AlbumMediaEntry e=row.entry;
        h.name.setText(e.getName());
        boolean alreadyInAlbum = showAlreadyStatus && e.isAlreadyInAlbum();
        boolean checked = e.isSelected() || alreadyInAlbum;
        h.check.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        h.check.setImageResource(checked ? R.drawable.ic_album_media_checked : R.drawable.ic_album_media_unchecked);
        h.check.setContentDescription(context.getString(checked ? R.string.album_media_selected : R.string.album_media_not_selected));
        h.check.setAlpha(alreadyInAlbum ? .72f : 1f);
        h.already.setVisibility(alreadyInAlbum ? View.VISIBLE : View.GONE);
        h.play.setVisibility(e.isVideo() ? View.VISIBLE : View.GONE);
        Object source=e.getSource();
        if(e.isVideo()) Glide.with(context).asBitmap().load(source).apply(RequestOptions.frameOf(1_000_000)).placeholder(R.drawable.ic_photo_placeholder).error(R.drawable.ic_photo_placeholder).centerCrop().into(h.image);
        else Glide.with(context).load(source).placeholder(R.drawable.ic_photo_placeholder).error(R.drawable.ic_photo_placeholder).centerCrop().into(h.image);
        h.itemView.setOnClickListener(v -> { if(listener!=null) listener.onClick(e); });
        h.itemView.setOnLongClickListener(v -> { if(listener!=null) listener.onLongClick(e,v); return true; });
    }

    @Override public int getItemViewType(int position) { return rows.get(position).entry == null ? 1 : 0; }
    @Override public int getItemCount(){return rows.size();}
    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private static final class Row {
        final AlbumMediaEntry entry; final String header;
        private Row(AlbumMediaEntry entry,String header){this.entry=entry;this.header=header;}
        static Row media(AlbumMediaEntry entry){return new Row(entry,null);}
        static Row header(String label){return new Row(null,label);}
    }
    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView image,play,check; final TextView name,already;
        final TextView header;
        Holder(View v){super(v); header=v instanceof TextView?(TextView)v:null; image=v.findViewById(R.id.imgAlbumMedia); play=v.findViewById(R.id.imgAlbumMediaPlay); name=v.findViewById(R.id.tvAlbumMediaName); check=v.findViewById(R.id.tvAlbumMediaCheck); already=v.findViewById(R.id.tvAlbumMediaExisting);}
    }
}
