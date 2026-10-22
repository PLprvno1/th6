package com.example.th6.models;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class Photo implements Serializable {
    private String id;
    private String ownerUid;
    private String name;
    private String imageUrl;
    private String displayUrl;
    private long createdAt;
    private boolean isFavorite;
    private boolean isTrashed;
    private long trashedAt;
    private long localMediaId;
    private String localMediaUri;
    private long localDateModified;
    private List<String> albumIds;
    private String storageProvider;
    private String contentHash;

    public Photo() {
        this.albumIds = new ArrayList<>();
        this.storageProvider = "imgbb";
        this.isTrashed = false;
        this.isFavorite = false;
    }

    public Photo(String id, String ownerUid, String name, String imageUrl, long createdAt) {
        this.id = id;
        this.ownerUid = ownerUid;
        this.name = name;
        this.imageUrl = imageUrl;
        this.displayUrl = imageUrl;
        this.createdAt = createdAt;
        this.isFavorite = false;
        this.isTrashed = false;
        this.trashedAt = 0;
        this.albumIds = new ArrayList<>();
        this.storageProvider = "imgbb";
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getOwnerUid() { return ownerUid; }
    public void setOwnerUid(String ownerUid) { this.ownerUid = ownerUid; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }

    public String getDisplayUrl() { return displayUrl; }
    public void setDisplayUrl(String displayUrl) { this.displayUrl = displayUrl; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public boolean isFavorite() { return isFavorite; }
    public void setFavorite(boolean favorite) { isFavorite = favorite; }

    public boolean isTrashed() { return isTrashed; }
    public void setTrashed(boolean trashed) { isTrashed = trashed; }

    public long getTrashedAt() { return trashedAt; }
    public void setTrashedAt(long trashedAt) { this.trashedAt = trashedAt; }

    public long getLocalMediaId() { return localMediaId; }
    public void setLocalMediaId(long localMediaId) { this.localMediaId = localMediaId; }

    public String getLocalMediaUri() { return localMediaUri; }
    public void setLocalMediaUri(String localMediaUri) { this.localMediaUri = localMediaUri; }

    public long getLocalDateModified() { return localDateModified; }
    public void setLocalDateModified(long localDateModified) { this.localDateModified = localDateModified; }

    public List<String> getAlbumIds() {
        if (albumIds == null) albumIds = new ArrayList<>();
        return albumIds;
    }
    public void setAlbumIds(List<String> albumIds) { this.albumIds = albumIds; }

    public String getStorageProvider() { return storageProvider; }
    public void setStorageProvider(String storageProvider) { this.storageProvider = storageProvider; }

    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
}
