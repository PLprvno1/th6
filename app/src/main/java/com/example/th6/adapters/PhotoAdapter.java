package com.example.th6.adapters;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.card.MaterialCardView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.example.th6.R;
import com.example.th6.models.Photo;
import com.example.th6.utils.PhotoTimelineUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.ToLongFunction;

public class PhotoAdapter extends RecyclerView.Adapter<PhotoAdapter.PhotoViewHolder> {

    private final Context context;
    private List<Photo> photos;
    private final List<Row> rows = new ArrayList<>();
    private OnPhotoActionListener listener;
    private final Set<String> selectedIds = new HashSet<>();
    private boolean selectionMode;
    private boolean actionsHidden;

    public interface OnPhotoActionListener {
        void onPhotoClick(Photo photo);
        void onPhotoLongClick(Photo photo, View view);
        void onFavoriteToggle(Photo photo);
    }

    public PhotoAdapter(Context context, OnPhotoActionListener listener) {
        this.context = context;
        this.photos = new ArrayList<>();
        this.listener = listener;
    }

    public void setPhotos(List<Photo> photos) {
        this.photos = photos != null ? photos : new ArrayList<>();
        rebuildRows(PhotoTimelineUtils.VIEW_MODE_ALL);
    }

    public void setTimelinePhotos(List<Photo> photos, int viewMode) {
        setTimelinePhotos(photos,viewMode,Photo::getCreatedAt);
    }

    public void setTimelinePhotos(List<Photo> photos, int viewMode, ToLongFunction<Photo> timestamp) {
        this.photos = photos != null ? photos : new ArrayList<>();
        rebuildRows(viewMode,timestamp);
    }

    private void rebuildRows(int viewMode) {
        rebuildRows(viewMode,Photo::getCreatedAt);
    }

    private void rebuildRows(int viewMode,ToLongFunction<Photo> timestamp) {
        rows.clear();
        String previousKey = null;
        for (Photo photo : this.photos) {
            if (viewMode != PhotoTimelineUtils.VIEW_MODE_ALL) {
                long time=timestamp.applyAsLong(photo);
                String key = PhotoTimelineUtils.getGroupKey(time, viewMode);
                if (!key.equals(previousKey)) { rows.add(Row.header(PhotoTimelineUtils.getGroupLabel(time, viewMode))); previousKey = key; }
            }
            rows.add(Row.photo(photo));
        }
        notifyDataSetChanged();
    }

    public List<Photo> getPhotos() {
        return photos;
    }

    public void setSelectionState(boolean active, Set<String> selected) {
        selectionMode = active;
        selectedIds.clear();
        if (selected != null) selectedIds.addAll(selected);
        notifyDataSetChanged();
    }

    public void setActionsHidden(boolean hidden) {
        actionsHidden = hidden;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public PhotoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == 1) {
            TextView header = new TextView(context);
            header.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            header.setGravity(android.view.Gravity.CENTER_VERTICAL);
            header.setPadding(dp(12), dp(14), dp(12), dp(8));
            header.setTextSize(18); header.setTypeface(null, android.graphics.Typeface.BOLD);
            android.util.TypedValue color = new android.util.TypedValue();
            if (context.getTheme().resolveAttribute(com.google.android.material.R.attr.colorOnBackground, color, true)) header.setTextColor(color.data);
            return new PhotoViewHolder(header);
        }
        View view = LayoutInflater.from(context).inflate(R.layout.item_photo, parent, false);
        return new PhotoViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PhotoViewHolder holder, int position) {
        Row row = rows.get(position);
        if (row.photo == null) { holder.header.setText(row.header); return; }
        Photo photo = row.photo;
        holder.tvPhotoName.setText(photo.getName());
        String key = photoKey(photo);
        boolean selected = selectedIds.contains(key);
        holder.tvSelectionCheck.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        holder.tvSelectionCheck.setText(selected ? "✓" : "");
        if (holder.itemView instanceof MaterialCardView) {
            MaterialCardView card = (MaterialCardView) holder.itemView;
            card.setStrokeWidth(selectionMode && selected ? Math.round(2 * context.getResources().getDisplayMetrics().density) : 0);
            if (selectionMode && selected) card.setStrokeColor(ContextCompat.getColor(context, R.color.rosewood_primary));
        }

        boolean video = "video".equalsIgnoreCase(photo.getMediaType());
        holder.imgPlayIcon.setVisibility(video ? View.VISIBLE : View.GONE);
        if (video) Glide.with(context).asBitmap().load(photo.getImageUrl())
                .apply(RequestOptions.frameOf(1_000_000)).placeholder(R.drawable.ic_photo_placeholder)
                .error(R.drawable.ic_photo_placeholder).centerCrop().into(holder.imgPhoto);
        else Glide.with(context).load(photo.getImageUrl()).placeholder(R.drawable.ic_photo_placeholder)
                .error(R.drawable.ic_photo_placeholder).centerCrop().into(holder.imgPhoto);

        if (photo.isFavorite()) {
            holder.btnFavorite.setImageResource(R.drawable.ic_favorite);
            holder.btnFavorite.setColorFilter(ContextCompat.getColor(context, R.color.favorite_red));
        } else {
            holder.btnFavorite.setImageResource(R.drawable.ic_favorite_border);
            holder.btnFavorite.setColorFilter(ContextCompat.getColor(context, android.R.color.white));
        }
        holder.btnFavorite.setVisibility(actionsHidden || selectionMode ? View.GONE : View.VISIBLE);

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onPhotoClick(photo);
        });

        holder.itemView.setOnLongClickListener(v -> {
            if (listener != null) listener.onPhotoLongClick(photo, v);
            return true;
        });

        holder.btnFavorite.setOnClickListener(v -> {
            if (listener != null) listener.onFavoriteToggle(photo);
        });
    }

    public static String photoKey(Photo photo) {
        if (photo == null) return "";
        if (photo.getId() != null && !photo.getId().isEmpty()) return photo.getId();
        return photo.getLocalMediaUri() == null ? photo.getImageUrl() : photo.getLocalMediaUri();
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    @Override public int getItemViewType(int position) { return rows.get(position).photo == null ? 1 : 0; }
    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private static final class Row {
        final Photo photo; final String header;
        private Row(Photo photo,String header){this.photo=photo;this.header=header;}
        static Row photo(Photo photo){return new Row(photo,null);}
        static Row header(String header){return new Row(null,header);}
    }

    public static class PhotoViewHolder extends RecyclerView.ViewHolder {
        ImageView imgPhoto;
        TextView tvPhotoName;
        ImageButton btnFavorite;
        TextView tvSelectionCheck;
        ImageView imgPlayIcon;
        TextView header;

        public PhotoViewHolder(@NonNull View itemView) {
            super(itemView);
            header = itemView instanceof TextView ? (TextView)itemView : null;
            imgPhoto = itemView.findViewById(R.id.imgPhoto);
            tvPhotoName = itemView.findViewById(R.id.tvPhotoName);
            btnFavorite = itemView.findViewById(R.id.btnFavorite);
            tvSelectionCheck = itemView.findViewById(R.id.tvSelectionCheck);
            imgPlayIcon = itemView.findViewById(R.id.imgPlayIcon);
        }
    }
}
