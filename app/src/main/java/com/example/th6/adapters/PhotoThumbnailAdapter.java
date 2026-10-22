package com.example.th6.adapters;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.th6.R;
import com.example.th6.models.Photo;

import java.util.ArrayList;
import java.util.List;

public class PhotoThumbnailAdapter extends RecyclerView.Adapter<PhotoThumbnailAdapter.Holder> {
    public interface Listener { void onPhotoSelected(int position); }

    private final Context context;
    private final Listener listener;
    private final List<Photo> photos = new ArrayList<>();
    private int selectedPosition = RecyclerView.NO_POSITION;
    private final int accent;

    public PhotoThumbnailAdapter(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        this.accent = context.getColor(R.color.editor_accent);
    }

    public void setPhotos(List<Photo> values, int selected) {
        photos.clear();
        if (values != null) photos.addAll(values);
        selectedPosition = photos.isEmpty() ? RecyclerView.NO_POSITION : Math.max(0, Math.min(selected, photos.size() - 1));
        notifyDataSetChanged();
    }

    public void setSelectedPosition(int position) {
        if (position < 0 || position >= photos.size() || selectedPosition == position) return;
        int old = selectedPosition;
        selectedPosition = position;
        if (old != RecyclerView.NO_POSITION) notifyItemChanged(old);
        notifyItemChanged(position);
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_photo_thumbnail, parent, false);
        return new Holder(view);
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        Photo photo = photos.get(position);
        boolean video = "video".equalsIgnoreCase(photo.getMediaType());
        holder.playBadge.setVisibility(video ? View.VISIBLE : View.GONE);
        String localUri = photo.getLocalMediaUri();
        String url = localUri != null && !localUri.isEmpty() ? localUri
                : photo.getDisplayUrl() != null && !photo.getDisplayUrl().isEmpty()
                ? photo.getDisplayUrl() : photo.getImageUrl();
        if (video && url != null && !url.isEmpty()
                && ("content".equalsIgnoreCase(Uri.parse(url).getScheme())
                || "file".equalsIgnoreCase(Uri.parse(url).getScheme()))) {
            Glide.with(holder.image).asBitmap().load(url).apply(com.bumptech.glide.request.RequestOptions
                            .frameOf(1_000_000).override(120, 120))
                    .centerCrop().placeholder(R.drawable.ic_photo_placeholder)
                    .error(R.drawable.ic_photo_placeholder).into(holder.image);
        } else if (video) {
            // Do not fetch an entire remote video just to produce a small thumbnail.
            Glide.with(holder.image).clear(holder.image);
            holder.image.setImageResource(R.drawable.ic_photo_placeholder);
        } else {
            Glide.with(holder.image).load(url).override(120, 120).centerCrop()
                    .placeholder(R.drawable.ic_photo_placeholder).error(R.drawable.ic_photo_placeholder)
                    .into(holder.image);
        }
        GradientDrawable border = new GradientDrawable();
        border.setColor(Color.TRANSPARENT);
        border.setCornerRadius(dp(3));
        border.setStroke(dp(position == selectedPosition ? 3 : 0), accent);
        holder.image.setBackground(border);
        holder.image.setPadding(dp(position == selectedPosition ? 2 : 0), dp(position == selectedPosition ? 2 : 0),
                dp(position == selectedPosition ? 2 : 0), dp(position == selectedPosition ? 2 : 0));
        holder.itemView.setContentDescription((video ? "Video: " : "Ảnh: ") + (photo.getName() == null ? "Media" : photo.getName()));
        holder.itemView.setOnClickListener(v -> {
            int currentPosition = holder.getBindingAdapterPosition();
            if (currentPosition != RecyclerView.NO_POSITION) listener.onPhotoSelected(currentPosition);
        });
    }

    private int dp(float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    @Override public int getItemCount() { return photos.size(); }

    static class Holder extends RecyclerView.ViewHolder {
        final ImageView image;
        final ImageView playBadge;
        Holder(@NonNull View itemView) {
            super(itemView);
            image = itemView.findViewById(R.id.imgThumbnail);
            playBadge = itemView.findViewById(R.id.imgVideoPlayBadge);
        }
    }
}
