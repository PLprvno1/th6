package com.example.th6.models;

import java.io.Serializable;

/** One deduplicated local/cloud media item shown in album flows. */
public class AlbumMediaEntry implements Serializable {
    private final String key;
    private final LocalMediaPhoto local;
    private final Photo cloud;
    private boolean selected;
    private final boolean alreadyInAlbum;

    public AlbumMediaEntry(String key, LocalMediaPhoto local, Photo cloud, boolean alreadyInAlbum) {
        this.key = key; this.local = local; this.cloud = cloud; this.alreadyInAlbum = alreadyInAlbum;
    }
    public String getKey() { return key; }
    public LocalMediaPhoto getLocal() { return local; }
    public Photo getCloud() { return cloud; }
    public boolean isAlreadyInAlbum() { return alreadyInAlbum; }
    public boolean isSelected() { return selected; }
    public void setSelected(boolean value) { selected = value; }
    public boolean isVideo() { return local != null ? local.isVideo() : cloud != null && "video".equalsIgnoreCase(cloud.getMediaType()); }
    public String getName() { return local != null && local.getDisplayName() != null ? local.getDisplayName() : cloud != null ? cloud.getName() : "Media"; }
    public String getSource() { return local != null && local.getContentUri() != null && !local.getContentUri().isEmpty() ? local.getContentUri() : cloud != null ? cloud.getImageUrl() : null; }
    public long getDurationMs() { return local == null ? 0 : local.getDurationMs(); }
    public long getTimestamp() {
        long localTimestamp = local == null ? 0 : local.getTimelineTimestamp();
        return localTimestamp > 0 ? localTimestamp : cloud != null ? cloud.getCreatedAt() : 0;
    }
    public String getLocalKey() {
        if (local == null) return null;
        return "media:" + (local.isVideo() ? "video" : "image") + ":" + local.getMediaId() + ":" + local.getDateModified() + ":" + local.getSizeBytes();
    }
}
