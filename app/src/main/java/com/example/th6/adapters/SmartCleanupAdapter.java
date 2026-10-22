package com.example.th6.adapters;

import android.content.Context;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.example.th6.R;
import com.example.th6.models.SmartCleanupMedia;
import com.example.th6.services.SmartCleanupScanner;

import java.util.ArrayList;
import java.util.List;

public final class SmartCleanupAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    public interface Listener { void onToggle(SmartCleanupMedia item); void onOpen(SmartCleanupMedia item); }
    private static final int TYPE_HEADER = 0, TYPE_MEDIA = 1;
    private final Context context;
    private final Listener listener;
    private final List<Row> rows = new ArrayList<>();
    private boolean interactionEnabled = true;

    private static final class Row {
        final String header; final SmartCleanupMedia item;
        Row(String header, SmartCleanupMedia item) { this.header = header; this.item = item; }
    }
    public SmartCleanupAdapter(Context context, Listener listener) { this.context = context; this.listener = listener; }

    public void setResults(SmartCleanupScanner.Result result) {
        rows.clear();
        if (result != null) {
            java.util.List<SmartCleanupScanner.Group> closeDuplicates = new ArrayList<>();
            java.util.List<SmartCleanupScanner.Group> similarGroups = new ArrayList<>();
            for (SmartCleanupScanner.Group group : result.duplicates) (group.similar ? similarGroups : closeDuplicates).add(group);
            for (SmartCleanupScanner.Group group : result.similar) (group.similar ? similarGroups : closeDuplicates).add(group);
            if (!closeDuplicates.isEmpty()) {
                rows.add(new Row("Ảnh trùng lặp • " + count(closeDuplicates) + " media", null));
                for (int i = 0; i < closeDuplicates.size(); i++) {
                    SmartCleanupScanner.Group group = closeDuplicates.get(i);
                    String heading = (group.exact ? "Trùng hoàn toàn" : "Nội dung gần như giống nhau") + " • Nhóm " + (i + 1);
                    long knownTotal = 0, smallest = Long.MAX_VALUE; boolean allSizesKnown = true;
                    for (SmartCleanupMedia item : group.media) {
                        if (item.sizeBytes <= 0) { allSizesKnown = false; break; }
                        knownTotal += item.sizeBytes; smallest = Math.min(smallest, item.sizeBytes);
                    }
                    if (allSizesKnown && group.exact) heading += " • dư thừa ước tính " + Formatter.formatFileSize(context, knownTotal - smallest) + " nếu giữ lại một bản";
                    else if (allSizesKnown) heading += " • tổng dung lượng nhóm " + Formatter.formatFileSize(context, knownTotal);
                    rows.add(new Row(heading, null));
                    for (SmartCleanupMedia item : group.media) rows.add(new Row(null, item));
                }
            }
            if (!similarGroups.isEmpty()) {
                rows.add(new Row("Ảnh tương tự • " + count(similarGroups) + " media", null));
                for (int i = 0; i < similarGroups.size(); i++) {
                    rows.add(new Row("Nhóm tương tự " + (i + 1), null));
                    for (SmartCleanupMedia item : similarGroups.get(i).media) rows.add(new Row(null, item));
                }
            }
            if (!result.largeVideos.isEmpty()) {
                rows.add(new Row("20 video lớn nhất • " + result.largeVideos.size() + " video có dung lượng xác định", null));
                for (SmartCleanupMedia item : result.largeVideos) rows.add(new Row(null, item));
            }
            if (!result.unknownSizeVideos.isEmpty()) {
                rows.add(new Row("Video chưa xác định dung lượng • không xếp hạng", null));
                for (SmartCleanupMedia item : result.unknownSizeVideos) rows.add(new Row(null, item));
            }
        }
        notifyDataSetChanged();
    }

    public void setInteractionEnabled(boolean enabled) {
        if (interactionEnabled == enabled) return;
        interactionEnabled = enabled;
        notifyDataSetChanged();
    }

    private int count(List<SmartCleanupScanner.Group> groups) { int count = 0; for (SmartCleanupScanner.Group group : groups) count += group.media.size(); return count; }

    @Override public int getItemViewType(int position) { return rows.get(position).item == null ? TYPE_HEADER : TYPE_MEDIA; }
    @Override public int getItemCount() { return rows.size(); }
    @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        if (type == TYPE_HEADER) return new HeaderHolder(LayoutInflater.from(context).inflate(R.layout.item_smart_cleanup_header, parent, false));
        return new MediaHolder(LayoutInflater.from(context).inflate(R.layout.item_smart_cleanup_media, parent, false));
    }
    @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Row row = rows.get(position);
        if (holder instanceof HeaderHolder) ((HeaderHolder) holder).title.setText(row.header);
        else ((MediaHolder) holder).bind(row.item);
    }

    private final class HeaderHolder extends RecyclerView.ViewHolder {
        final TextView title; HeaderHolder(View v) { super(v); title = v.findViewById(R.id.tvCleanupGroupHeader); }
    }
    private final class MediaHolder extends RecyclerView.ViewHolder {
        final ImageView thumbnail; final TextView name, info, source; final CheckBox selected;
        MediaHolder(View v) {
            super(v); thumbnail = v.findViewById(R.id.ivCleanupMedia); name = v.findViewById(R.id.tvCleanupMediaName);
            info = v.findViewById(R.id.tvCleanupMediaInfo); source = v.findViewById(R.id.tvCleanupMediaSource);
            selected = v.findViewById(R.id.cbCleanupMedia);
        }
        void bind(SmartCleanupMedia item) {
            name.setText(item.getName());
            String detail = item.sizeBytes > 0 ? Formatter.formatFileSize(context, item.sizeBytes) : "Chưa xác định dung lượng";
            if (item.video && item.local != null && item.local.getDurationMs() > 0)
                detail += " • " + com.example.th6.utils.VideoDurationFormatter.formatDuration(item.local.getDurationMs());
            info.setText(detail);
            source.setText(item.getSourceLabel() + (item.video ? " • Video" : " • Ảnh"));
            selected.setOnCheckedChangeListener(null);
            selected.setEnabled(interactionEnabled);
            selected.setChecked(item.selected);
            selected.setOnCheckedChangeListener((button, checked) -> { if (item.selected != checked) listener.onToggle(item); });
            selected.setContentDescription(item.selected ? "Đã chọn " + item.getName() : "Chọn " + item.getName());
            String preview = item.getPreviewSource();
            if (item.video && item.local != null && preview != null && !preview.isEmpty()) {
                Glide.with(thumbnail).asBitmap().load(preview).apply(RequestOptions.frameOf(1_000_000L))
                        .centerCrop().placeholder(R.drawable.ic_photo_placeholder).error(R.drawable.ic_photo_placeholder).into(thumbnail);
            } else if (item.video || preview == null || preview.isEmpty()) {
                Glide.with(thumbnail).clear(thumbnail); thumbnail.setImageResource(R.drawable.ic_photo_placeholder);
            } else {
                Glide.with(thumbnail).load(preview).centerCrop().placeholder(R.drawable.ic_photo_placeholder).error(R.drawable.ic_photo_placeholder).into(thumbnail);
            }
            itemView.setOnClickListener(v -> listener.onOpen(item));
            itemView.setEnabled(interactionEnabled);
        }
    }
}
