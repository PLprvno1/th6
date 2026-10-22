package com.example.th6.services;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class BackupUploadLedger extends SQLiteOpenHelper {
    private static final String DB_NAME = "photovault_backup_uploads.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE = "pending_metadata";
    private static final String COL_UID = "uid";
    private static final String COL_URI = "media_uri";
    private static final String COL_MODIFIED = "media_modified";
    private static final String COL_URL = "image_url";

    public static class Entry {
        public final long modified;
        public final String imageUrl;
        Entry(long modified, String imageUrl) {
            this.modified = modified;
            this.imageUrl = imageUrl;
        }
    }

    public BackupUploadLedger(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " (" + COL_UID + " TEXT NOT NULL, "
                + COL_URI + " TEXT NOT NULL, " + COL_MODIFIED + " INTEGER NOT NULL, "
                + COL_URL + " TEXT NOT NULL, PRIMARY KEY (" + COL_UID + ", " + COL_URI + "))");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // No schema migrations are needed for version 1.
    }

    public synchronized void recordUpload(String uid, String mediaUri, long modified, String imageUrl) {
        ContentValues values = new ContentValues();
        values.put(COL_UID, uid);
        values.put(COL_URI, mediaUri);
        values.put(COL_MODIFIED, modified);
        values.put(COL_URL, imageUrl);
        getWritableDatabase().insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized Entry find(String uid, String mediaUri) {
        try (Cursor cursor = getReadableDatabase().query(TABLE,
                new String[]{COL_MODIFIED, COL_URL}, COL_UID + "=? AND " + COL_URI + "=?",
                new String[]{uid, mediaUri}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return new Entry(cursor.getLong(0), cursor.getString(1));
            return null;
        }
    }

    public synchronized void remove(String uid, String mediaUri) {
        getWritableDatabase().delete(TABLE, COL_UID + "=? AND " + COL_URI + "=?",
                new String[]{uid, mediaUri});
    }
}
