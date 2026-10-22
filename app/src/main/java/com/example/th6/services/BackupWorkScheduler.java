package com.example.th6.services;

import android.content.Context;

import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.example.th6.utils.BackupPreferences;

import java.util.concurrent.TimeUnit;

/** Keeps one persistent, low-frequency scan schedule per signed-in account. */
public final class BackupWorkScheduler {
    private static final long PERIOD_HOURS = 6;
    private static final String PERIODIC_PREFIX = "photovault_backup_periodic_";
    private static final String IMMEDIATE_PREFIX = "photovault_backup_immediate_";

    private BackupWorkScheduler() { }

    public static String immediateWorkName(String uid) { return IMMEDIATE_PREFIX + uid; }
    public static String periodicWorkName(String uid) { return PERIODIC_PREFIX + uid; }

    public static void reconcile(Context context, String uid, boolean scanNow) {
        if (context == null || uid == null || uid.isEmpty()) return;
        Context appContext = context.getApplicationContext();
        BackupPreferences prefs = new BackupPreferences(appContext, uid);
        WorkManager workManager = WorkManager.getInstance(appContext);
        String periodicName = periodicWorkName(uid);

        if (!prefs.isBackupEnabled() || prefs.isPaused()) {
            disable(appContext, uid);
            return;
        }

        Constraints constraints = createConstraints(prefs.isWifiOnly());
        PeriodicWorkRequest periodic = new PeriodicWorkRequest.Builder(
                PhotoBackupWorker.class, PERIOD_HOURS, TimeUnit.HOURS)
                .setInputData(backupInput(uid))
                .setConstraints(constraints)
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build();
        workManager.enqueueUniquePeriodicWork(periodicName, ExistingPeriodicWorkPolicy.UPDATE, periodic);

        if (scanNow) {
            OneTimeWorkRequest immediate = new OneTimeWorkRequest.Builder(PhotoBackupWorker.class)
                    .setInputData(backupInput(uid))
                    .setConstraints(constraints)
                    .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build();
            workManager.enqueueUniqueWork(immediateWorkName(uid), ExistingWorkPolicy.KEEP, immediate);
        }
    }

    public static void enqueueManual(Context context, String uid, boolean wifiOnly) {
        if (context == null || uid == null || uid.isEmpty()) return;
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(PhotoBackupWorker.class)
                .setInputData(backupInput(uid))
                .setConstraints(createConstraints(wifiOnly))
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build();
        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniqueWork(immediateWorkName(uid), ExistingWorkPolicy.KEEP, request);
    }

    public static void disable(Context context, String uid) {
        if (context == null || uid == null || uid.isEmpty()) return;
        // Cancel queued periodic work only when no worker owns the upload lock. A running
        // worker cancels its own periodic schedule after the current image is committed.
        if (PhotoBackupWorker.acquireIdleRunLock()) {
            try {
                WorkManager.getInstance(context.getApplicationContext())
                        .cancelUniqueWork(PERIODIC_PREFIX + uid);
            } finally {
                PhotoBackupWorker.releaseIdleRunLock();
            }
        }
    }

    public static void cancelPeriodic(Context context, String uid) {
        if (context != null && uid != null && !uid.isEmpty()) {
            WorkManager.getInstance(context.getApplicationContext())
                    .cancelUniqueWork(PERIODIC_PREFIX + uid);
        }
    }

    private static Constraints createConstraints(boolean wifiOnly) {
        NetworkType networkType = wifiOnly ? NetworkType.UNMETERED : NetworkType.CONNECTED;
        return new Constraints.Builder().setRequiredNetworkType(networkType).build();
    }

    private static Data backupInput(String uid) {
        return new Data.Builder().putString("backup_uid", uid).build();
    }
}
