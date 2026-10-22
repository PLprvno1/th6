package com.example.th6.adapters;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
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
import com.example.th6.models.LocalMediaPhoto;
import com.example.th6.utils.PhotoTimelineUtils;
import com.example.th6.utils.VideoDurationFormatter;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

public class LocalPhotoAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int TYPE_HEADER = 0;
    private static final int TYPE_PHOTO = 1;

    private final Context context;
    private final OnLocalPhotoClickListener listener;
    private final List<Row> rows = new ArrayList<>();
    private final Set<String> selectedKeys = new HashSet<>();

    public interface OnLocalPhotoClickListener {
        void onLocalPhotoClick(LocalMediaPhoto photo, int position);
        void onLocalPhotoLongClick(LocalMediaPhoto photo, View view);
    }

    private static class Row {
        final String header;
        final LocalMediaPhoto photo;
        final int photoPosition;

        private Row(String header, LocalMediaPhoto photo, int photoPosition) {
            this.header = header;
            this.photo = photo;
            this.photoPosition = photoPosition;
        }

        static Row header(String label) { return new Row(label, null, -1); }
        static Row photo(LocalMediaPhoto photo, int position) { return new Row(null, photo, position); }
    }

    public LocalPhotoAdapter(Context context, OnLocalPhotoClickListener listener) {
        this.context = context;
        this.listener = listener;
    }

    public void setPhotoList(List<LocalMediaPhoto> photoList, int viewMode) {
        rows.clear();
        if (photoList != null) {
            String previousKey = null;
            for (int i = 0; i < photoList.size(); i++) {
                LocalMediaPhoto photo = photoList.get(i);
                if (viewMode != PhotoTimelineUtils.VIEW_MODE_ALL) {
                    String key = PhotoTimelineUtils.getGroupKey(photo.getTimelineTimestamp(), viewMode);
                    if (!key.equals(previousKey)) {
                        rows.add(Row.header(PhotoTimelineUtils.getGroupLabel(photo.getTimelineTimestamp(), viewMode)));
                        previousKey = key;
                    }
                }
                rows.add(Row.photo(photo, i));
            }
        }
        notifyDataSetChanged();
    }

    public void setSelectedKeys(Set<String> keys) {
        selectedKeys.clear();
        if (keys != null) selectedKeys.addAll(keys);
        notifyDataSetChanged();
    }

    public static String keyFor(LocalMediaPhoto photo) {
        if (photo == null) return "";
        if (photo.getCloudPhoto() != null && (photo.getContentUri() == null || photo.getContentUri().isEmpty())) {
            return "cloud:" + photo.getCloudPhoto().getId();
        }
        return "local:" + photo.getMediaId() + ":" + String.valueOf(photo.getContentUri());
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position).photo == null ? TYPE_HEADER : TYPE_PHOTO;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_HEADER) {
            TextView header = new TextView(context);
            header.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setPadding(dp(12), dp(14), dp(12), dp(8));
            header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            header.setTypeface(null, android.graphics.Typeface.BOLD);
            TypedValue color = new TypedValue();
            if (context.getTheme().resolveAttribute(com.google.android.material.R.attr.colorOnBackground, color, true)) {
                header.setTextColor(color.data);
            }
            return new HeaderViewHolder(header);
        }
        View view = LayoutInflater.from(context).inflate(R.layout.item_photo, parent, false);
        return new PhotoViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Row row = rows.get(position);
        if (holder instanceof HeaderViewHolder) {
            ((HeaderViewHolder) holder).textView.setText(row.header);
            return;
        }
        PhotoViewHolder photoHolder = (PhotoViewHolder) holder;
        LocalMediaPhoto photo = row.photo;
        if (photoHolder.tvPhotoName != null) photoHolder.tvPhotoName.setText(photo.getDisplayName());
        boolean selected = selectedKeys.contains(keyFor(photo));
        if (photoHolder.tvSelectionCheck != null) photoHolder.tvSelectionCheck.setVisibility(selected ? View.VISIBLE : View.GONE);
        if (photoHolder.cardPhoto != null) {
            photoHolder.cardPhoto.setStrokeWidth(selected ? dp(2) : 0);
            photoHolder.cardPhoto.setStrokeColor(androidx.core.content.ContextCompat.getColor(context,
                    selected ? com.example.th6.R.color.rosewood_primary : android.R.color.transparent));
        }
        if (photoHolder.btnFavorite != null) photoHolder.btnFavorite.setVisibility(View.GONE);
        if (photoHolder.imgPlayIcon != null) photoHolder.imgPlayIcon.setVisibility(photo.isVideo() ? View.VISIBLE : View.GONE);
        if (photoHolder.tvDuration != null) {
            photoHolder.tvDuration.setVisibility(photo.isVideo() ? View.VISIBLE : View.GONE);
            if (photo.isVideo()) photoHolder.tvDuration.setText(VideoDurationFormatter.formatDuration(photo.getDurationMs()));
        }

        if (photo.isVideo()) {
            Glide.with(context).asBitmap().load(photo.getContentUri())
                    .apply(RequestOptions.frameOf(1_000_000))
                    .placeholder(R.drawable.ic_photo_placeholder)
                    .error(R.drawable.ic_photo_placeholder)
                    .centerCrop().into(photoHolder.imgPhoto);
        } else {
            Object imageSource = photo.getContentUri() != null && !photo.getContentUri().isEmpty()
                    ? photo.getContentUri() : photo.getCloudUrl();
            Glide.with(context).load(imageSource)
                    .placeholder(R.drawable.ic_photo_placeholder)
                    .error(R.drawable.ic_photo_placeholder)
                    .centerCrop().into(photoHolder.imgPhoto);
        }

        photoHolder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onLocalPhotoClick(photo, row.photoPosition);
        });
        photoHolder.itemView.setOnLongClickListener(v -> {
            if (listener != null) listener.onLocalPhotoLongClick(photo, v);
            return true;
        });
    }

    @Override
    public int getItemCount() { return rows.size(); }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static class HeaderViewHolder extends RecyclerView.ViewHolder {
        final TextView textView;
        HeaderViewHolder(TextView view) { super(view); textView = view; }
    }

    private static class PhotoViewHolder extends RecyclerView.ViewHolder {
        final ImageView imgPhoto;
        final TextView tvPhotoName;
        final View btnFavorite;
        final ImageView imgPlayIcon;
        final TextView tvDuration;
        final TextView tvSelectionCheck;
        final com.google.android.material.card.MaterialCardView cardPhoto;

        PhotoViewHolder(@NonNull View itemView) {
            super(itemView);
            imgPhoto = itemView.findViewById(R.id.imgPhoto);
            tvPhotoName = itemView.findViewById(R.id.tvPhotoName);
            btnFavorite = itemView.findViewById(R.id.btnFavorite);
            imgPlayIcon = itemView.findViewById(R.id.imgPlayIcon);
            tvDuration = itemView.findViewById(R.id.tvDuration);
            tvSelectionCheck = itemView.findViewById(R.id.tvSelectionCheck);
            cardPhoto = itemView.findViewById(R.id.cardPhoto);
        }
    }
}
