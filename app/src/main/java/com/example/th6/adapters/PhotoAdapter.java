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

import com.bumptech.glide.Glide;
import com.example.th6.R;
import com.example.th6.models.Photo;

import java.util.ArrayList;
import java.util.List;

public class PhotoAdapter extends RecyclerView.Adapter<PhotoAdapter.PhotoViewHolder> {

    private final Context context;
    private List<Photo> photos;
    private OnPhotoActionListener listener;

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
        notifyDataSetChanged();
    }

    public List<Photo> getPhotos() {
        return photos;
    }

    @NonNull
    @Override
    public PhotoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_photo, parent, false);
        return new PhotoViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PhotoViewHolder holder, int position) {
        Photo photo = photos.get(position);
        holder.tvPhotoName.setText(photo.getName());

        Glide.with(context)
                .load(photo.getImageUrl())
                .placeholder(R.drawable.ic_photo_placeholder)
                .error(R.drawable.ic_photo_placeholder)
                .centerCrop()
                .into(holder.imgPhoto);

        if (photo.isFavorite()) {
            holder.btnFavorite.setImageResource(R.drawable.ic_favorite);
            holder.btnFavorite.setColorFilter(ContextCompat.getColor(context, R.color.favorite_red));
        } else {
            holder.btnFavorite.setImageResource(R.drawable.ic_favorite_border);
            holder.btnFavorite.setColorFilter(ContextCompat.getColor(context, android.R.color.white));
        }

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

    @Override
    public int getItemCount() {
        return photos.size();
    }

    public static class PhotoViewHolder extends RecyclerView.ViewHolder {
        ImageView imgPhoto;
        TextView tvPhotoName;
        ImageButton btnFavorite;

        public PhotoViewHolder(@NonNull View itemView) {
            super(itemView);
            imgPhoto = itemView.findViewById(R.id.imgPhoto);
            tvPhotoName = itemView.findViewById(R.id.tvPhotoName);
            btnFavorite = itemView.findViewById(R.id.btnFavorite);
        }
    }
}
