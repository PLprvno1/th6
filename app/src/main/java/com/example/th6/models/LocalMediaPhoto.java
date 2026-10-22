package com.example.th6.models;

import java.io.Serializable;

public class LocalMediaPhoto implements Serializable {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_UPLOADING = "UPLOADING";
    public static final String STATUS_UPLOADED = "UPLOADED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_METADATA_PENDING = "METADATA_PENDING";

    private long mediaId;
    private String contentUri;
    private String displayName;
    private String mimeType;
    private long sizeBytes;
    private int width;
    private int height;
    private long dateModified;
    private boolean isVideo;
    private long durationMs;
    private String backupStatus;
    private String cloudPhotoId;
    private String cloudUrl;
    private Photo cloudPhoto;

    public LocalMediaPhoto() {
        this.backupStatus = STATUS_PENDING;
        this.isVideo = false;
        this.durationMs = 0;
    }

    public LocalMediaPhoto(long mediaId, String contentUri, String displayName, String mimeType, long sizeBytes, int width, int height, long dateModified) {
        this.mediaId = mediaId;
        this.contentUri = contentUri;
        this.displayName = displayName;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.width = width;
        this.height = height;
        this.dateModified = dateModified;
        this.isVideo = false;
        this.durationMs = 0;
        this.backupStatus = STATUS_PENDING;
    }

    public long getMediaId() { return mediaId; }
    public void setMediaId(long mediaId) { this.mediaId = mediaId; }

    public String getContentUri() { return contentUri; }
    public void setContentUri(String contentUri) { this.contentUri = contentUri; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }

    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }

    public int getWidth() { return width; }
    public void setWidth(int width) { this.width = width; }

    public int getHeight() { return height; }
    public void setHeight(int height) { this.height = height; }

    public long getDateModified() { return dateModified; }
    public void setDateModified(long dateModified) { this.dateModified = dateModified; }

    public boolean isVideo() { return isVideo; }
    public void setVideo(boolean video) { isVideo = video; }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

    public String getBackupStatus() { return backupStatus; }
    public void setBackupStatus(String backupStatus) { this.backupStatus = backupStatus; }

    public String getCloudPhotoId() { return cloudPhotoId; }
    public void setCloudPhotoId(String cloudPhotoId) { this.cloudPhotoId = cloudPhotoId; }

    public String getCloudUrl() { return cloudUrl; }
    public void setCloudUrl(String cloudUrl) { this.cloudUrl = cloudUrl; }

    public Photo getCloudPhoto() { return cloudPhoto; }
    public void setCloudPhoto(Photo cloudPhoto) { this.cloudPhoto = cloudPhoto; }
}
