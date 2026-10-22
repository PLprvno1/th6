package com.example.th6.models;

import java.io.Serializable;

/** A stable logical media reference used by one Smart Cleanup scan result. */
public final class SmartCleanupMedia implements Serializable {
    public final String key;
    public final LocalMediaPhoto local;
    public final Photo cloud;
    public final boolean video;
    public final long sizeBytes;
    public final String contentHash;
    public final boolean digestVerified;
    public final Long perceptualHash;
    public boolean selected;

    public SmartCleanupMedia(String key, LocalMediaPhoto local, Photo cloud, boolean video,
                             long sizeBytes, String contentHash, boolean digestVerified, Long perceptualHash) {
        this.key = key;
        this.local = local;
        this.cloud = cloud;
        this.video = video;
        this.sizeBytes = sizeBytes;
        this.contentHash = contentHash;
        this.digestVerified = digestVerified;
        this.perceptualHash = perceptualHash;
    }

    public String getName() {
        if (local != null && local.getDisplayName() != null && !local.getDisplayName().isEmpty()) return local.getDisplayName();
        if (cloud != null && cloud.getName() != null && !cloud.getName().isEmpty()) return cloud.getName();
        return video ? "Video" : "Ảnh";
    }

    public String getPreviewSource() {
        if (local != null && local.getContentUri() != null && !local.getContentUri().isEmpty()) return local.getContentUri();
        return cloud == null ? null : cloud.getImageUrl();
    }

    public String getSourceLabel() {
        if (local != null && cloud != null) return "Thiết bị + cloud";
        return local != null ? "Thiết bị" : "Cloud";
    }
}
