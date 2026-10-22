package com.example.th6.models;

import java.io.Serializable;

public class UserProfile implements Serializable {
    private String uid;
    private String displayName;
    private String email;
    private String photoUrl;
    private long createdAt;

    public UserProfile() {}

    public UserProfile(String uid, String displayName, String email, String photoUrl, long createdAt) {
        this.uid = uid;
        this.displayName = displayName;
        this.email = email;
        this.photoUrl = photoUrl;
        this.createdAt = createdAt;
    }

    public String getUid() { return uid; }
    public void setUid(String uid) { this.uid = uid; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPhotoUrl() { return photoUrl; }
    public void setPhotoUrl(String photoUrl) { this.photoUrl = photoUrl; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}
