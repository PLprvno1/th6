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
import androidx.work.WorkInfo;

import com.example.th6.utils.BackupPreferences;
import com.example.th6.data.AuthRepository;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.List;

/** Keeps one persistent, low-frequency scan schedule per signed-in account. */
public final class BackupWorkScheduler {
    private static final long PERIOD_HOURS = 6;
    private static final String PERIODIC_PREFIX = "photovault_backup_periodic_";
    private static final String IMMEDIATE_PREFIX = "photovault_backup_immediate_";
    private static final ExecutorService ENQUEUE_EXECUTOR = Executors.newSingleThreadExecutor();

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

        if (scanNow) enqueueIfEligible(appContext, uid);
    }

    /**
     * Shared account-scoped backup trigger. A queued run already scans the current
     * MediaStore state, so requests coalesce while it is waiting. If a run is active,
     * one follow-up is appended; later requests coalesce behind that queued scan.
     */
    public static boolean enqueueIfEligible(Context context, String uid) {
        if (context == null || uid == null || uid.isEmpty()) return false;
        Context appContext = context.getApplicationContext();
        AuthRepository authRepository = new AuthRepository();
        if (!authRepository.isLoggedIn()) return false;
        String activeUid = authRepository.getCurrentUserUid();
        if (!uid.equals(activeUid)) return false;
        BackupPreferences prefs = new BackupPreferences(appContext, uid);
        if (!prefs.isBackupEnabled() || prefs.isPaused()) return false;

        ENQUEUE_EXECUTOR.execute(() -> enqueueCoalesced(appContext, uid));
        return true;
    }

    private static void enqueueCoalesced(Context context, String uid) {
        AuthRepository authRepository = new AuthRepository();
        if (!authRepository.isLoggedIn() || !uid.equals(authRepository.getCurrentUserUid())) return;
        BackupPreferences prefs = new BackupPreferences(context, uid);
        if (!prefs.isBackupEnabled() || prefs.isPaused()) return;

        WorkManager workManager = WorkManager.getInstance(context);
        String workName = immediateWorkName(uid);
        boolean running = false;
        boolean waiting = false;
        try {
            List<WorkInfo> infos = workManager.getWorkInfosForUniqueWork(workName).get();
            for (WorkInfo info : infos) {
                if (info.getState() == WorkInfo.State.RUNNING) running = true;
                else if (info.getState() == WorkInfo.State.ENQUEUED || info.getState() == WorkInfo.State.BLOCKED) waiting = true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (ExecutionException | RuntimeException e) {
            // If WorkManager state cannot be read, preserve the request with a serialized
            // append rather than dropping a scan that may have arrived during a running job.
            enqueueRequest(workManager, context, uid, prefs, ExistingWorkPolicy.APPEND_OR_REPLACE);
            return;
        }

        if (waiting) return;
        ExistingWorkPolicy policy = running ? ExistingWorkPolicy.APPEND_OR_REPLACE : ExistingWorkPolicy.KEEP;
        enqueueRequest(workManager, context, uid, prefs, policy);
    }

    private static void enqueueRequest(WorkManager workManager, Context context, String uid,
                                      BackupPreferences prefs, ExistingWorkPolicy policy) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(PhotoBackupWorker.class)
                .setInputData(backupInput(uid))
                .setConstraints(createConstraints(prefs.isWifiOnly()))
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build();
        try {
            workManager.enqueueUniqueWork(immediateWorkName(uid), policy, request).getResult().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | RuntimeException ignored) { }
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
