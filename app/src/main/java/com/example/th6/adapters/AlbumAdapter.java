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
import com.example.th6.models.Album;

import java.util.ArrayList;
import java.util.List;

public class AlbumAdapter extends RecyclerView.Adapter<AlbumAdapter.AlbumViewHolder> {

    private final Context context;
    private List<Album> albums;
    private OnAlbumActionListener listener;

    public interface OnAlbumActionListener {
        void onAlbumClick(Album album);
        void onAlbumLongClick(Album album, View view);
    }

    public AlbumAdapter(Context context, OnAlbumActionListener listener) {
        this.context = context;
        this.albums = new ArrayList<>();
        this.listener = listener;
    }

    public void setAlbums(List<Album> albums) {
        this.albums = albums != null ? albums : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public AlbumViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_album, parent, false);
        return new AlbumViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull AlbumViewHolder holder, int position) {
        Album album = albums.get(position);
        holder.tvAlbumName.setText(album.getName());
        holder.tvAlbumCount.setText(context.getResources().getQuantityString(R.plurals.album_photo_count,
                album.getPhotoCount(), album.getPhotoCount()));

        String cover = album.getDisplayCoverUri() != null && !album.getDisplayCoverUri().isEmpty()
                ? album.getDisplayCoverUri() : album.getCoverImageUrl();
        if (cover != null && !cover.isEmpty()) {
            if (album.getDisplayCoverVideo()) {
                Glide.with(context).asBitmap().load(cover)
                        .apply(RequestOptions.frameOf(1_000_000))
                        .placeholder(R.drawable.ic_photo_placeholder).error(R.drawable.ic_photo_placeholder)
                        .centerCrop().into(holder.imgAlbumCover);
            } else Glide.with(context).load(cover)
                    .placeholder(R.drawable.ic_photo_placeholder).error(R.drawable.ic_photo_placeholder)
                    .centerCrop().into(holder.imgAlbumCover);
        } else {
            Glide.with(context).clear(holder.imgAlbumCover);
            holder.imgAlbumCover.setImageResource(R.drawable.ic_photo_placeholder);
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onAlbumClick(album);
        });

        holder.itemView.setOnLongClickListener(v -> {
            if (listener != null) listener.onAlbumLongClick(album, v);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return albums.size();
    }

    public static class AlbumViewHolder extends RecyclerView.ViewHolder {
        ImageView imgAlbumCover;
        TextView tvAlbumName;
        TextView tvAlbumCount;

        public AlbumViewHolder(@NonNull View itemView) {
            super(itemView);
            imgAlbumCover = itemView.findViewById(R.id.imgAlbumCover);
            tvAlbumName = itemView.findViewById(R.id.tvAlbumName);
            tvAlbumCount = itemView.findViewById(R.id.tvAlbumCount);
        }
    }
}
