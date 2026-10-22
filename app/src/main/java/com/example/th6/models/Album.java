package com.example.th6.models;

import java.io.Serializable;

public class Album implements Serializable {
    private String id;
    private String ownerUid;
    private String name;
    private long createdAt;
    private String coverImageUrl;
    private int photoCount;

    public Album() {}

    public Album(String id, String ownerUid, String name, long createdAt) {
        this.id = id;
        this.ownerUid = ownerUid;
        this.name = name;
        this.createdAt = createdAt;
        this.coverImageUrl = "";
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getOwnerUid() { return ownerUid; }
    public void setOwnerUid(String ownerUid) { this.ownerUid = ownerUid; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public String getCoverImageUrl() { return coverImageUrl; }
    public void setCoverImageUrl(String coverImageUrl) { this.coverImageUrl = coverImageUrl; }

    public int getPhotoCount() { return photoCount; }
    public void setPhotoCount(int photoCount) { this.photoCount = photoCount; }
}
