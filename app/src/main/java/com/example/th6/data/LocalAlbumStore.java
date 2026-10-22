package com.example.th6.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/** Device-local memberships for media that has not been backed up yet. */
public final class LocalAlbumStore extends SQLiteOpenHelper {
    private static final String DB_NAME = "photovault_local_albums.db";
    private static final int DB_VERSION = 1;
    private static final String MEMBERS = "local_album_members";
    private static final String COVERS = "local_album_covers";

    public LocalAlbumStore(Context context) { super(context.getApplicationContext(), DB_NAME, null, DB_VERSION); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + MEMBERS + " (uid TEXT NOT NULL, album_id TEXT NOT NULL, media_key TEXT NOT NULL, added_at INTEGER NOT NULL, PRIMARY KEY(uid,album_id,media_key))");
        db.execSQL("CREATE TABLE " + COVERS + " (uid TEXT NOT NULL, album_id TEXT NOT NULL, media_key TEXT NOT NULL, PRIMARY KEY(uid,album_id))");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }

    public synchronized boolean add(String uid, String albumId, String mediaKey) {
        ContentValues v = new ContentValues(); v.put("uid", uid); v.put("album_id", albumId); v.put("media_key", mediaKey); v.put("added_at", System.currentTimeMillis());
        return getWritableDatabase().insertWithOnConflict(MEMBERS, null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }
    public synchronized boolean remove(String uid, String albumId, String mediaKey) {
        return getWritableDatabase().delete(MEMBERS, "uid=? AND album_id=? AND media_key=?", new String[]{uid, albumId, mediaKey}) > 0;
    }
    public synchronized List<String> getMembers(String uid, String albumId) {
        List<String> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(MEMBERS, new String[]{"media_key"}, "uid=? AND album_id=?", new String[]{uid, albumId}, null, null, "added_at ASC, media_key ASC")) {
            while (c.moveToNext()) result.add(c.getString(0));
        }
        return result;
    }
    public synchronized boolean hasMember(String uid, String albumId, String mediaKey) {
        try (Cursor c = getReadableDatabase().query(MEMBERS, new String[]{"media_key"}, "uid=? AND album_id=? AND media_key=?", new String[]{uid, albumId, mediaKey}, null, null, null, "1")) { return c.moveToFirst(); }
    }
    public synchronized String getCover(String uid, String albumId) {
        try (Cursor c = getReadableDatabase().query(COVERS, new String[]{"media_key"}, "uid=? AND album_id=?", new String[]{uid, albumId}, null, null, null, "1")) { return c.moveToFirst() ? c.getString(0) : null; }
    }
    public synchronized void setCover(String uid, String albumId, String mediaKey) {
        ContentValues v = new ContentValues(); v.put("uid", uid); v.put("album_id", albumId); v.put("media_key", mediaKey);
        getWritableDatabase().insertWithOnConflict(COVERS, null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }
    public synchronized void clearCover(String uid, String albumId) {
        getWritableDatabase().delete(COVERS, "uid=? AND album_id=?", new String[]{uid, albumId});
    }
    public synchronized void deleteAlbum(String uid, String albumId) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete(MEMBERS, "uid=? AND album_id=?", new String[]{uid, albumId});
            db.delete(COVERS, "uid=? AND album_id=?", new String[]{uid, albumId});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
}
