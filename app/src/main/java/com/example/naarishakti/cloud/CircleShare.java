package com.example.naarishakti.cloud;

import android.content.Context;
import android.location.Location;
import android.os.BatteryManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.android.gms.location.Priority;
import com.google.gson.JsonObject;

import java.util.concurrent.TimeUnit;

/**
 * Family circle sharing: while she has it switched on, her position (and battery level) is sent
 * to the server for the people she is linked to as guardian or ward. Off by default. Refreshed
 * when the app is opened and every 30 min; switching it off deletes the position on the server.
 */
public final class CircleShare {

    private static final String WORK_NOW = "ns_circle_now";
    private static final String WORK_PERIODIC = "ns_circle_periodic";
    private static final String OUTBOX_KEY = "circle";
    private static final String KEY_SHARING = "circle_sharing";
    private static final String KEY_LAST_OPEN_REFRESH = "circle_last_open_refresh";
    /** Minimum gap between "app opened" refreshes. */
    private static final long APP_OPEN_MIN_GAP_MS = 5 * 60_000L;

    private CircleShare() {}

    public static boolean isSharing(Context ctx) {
        return Cloud.state(ctx).getBoolean(KEY_SHARING, false);
    }

    /** Saves the switch; on → upload now and keep refreshing, off → remove the position. */
    public static void setSharing(Context ctx, boolean on) {
        Context app = ctx.getApplicationContext();
        Cloud.state(app).edit().putBoolean(KEY_SHARING, on).apply();
        if (!Cloud.isActive(app)) return;
        if (on) {
            // A queued "sharing: false" from earlier must not undo this.
            CloudOutbox.removeKey(app, OUTBOX_KEY);
            refreshSoon(app);
            schedulePeriodic(app);
        } else {
            try {
                WorkManager wm = WorkManager.getInstance(app);
                wm.cancelUniqueWork(WORK_PERIODIC);
                wm.cancelUniqueWork(WORK_NOW);
            } catch (Throwable ignored) {
            }
            JsonObject body = new JsonObject();
            body.addProperty("sharing", false);
            CloudOutbox.add(app, "PUT", "/api/v1/circle/me", body, OUTBOX_KEY);
        }
    }

    /** The app came to the foreground: refresh (throttled) and make sure the periodic job exists. */
    static void onAppOpened(Context ctx) {
        if (!Cloud.isActive(ctx) || !isSharing(ctx)) return;
        long now = System.currentTimeMillis();
        long last = Cloud.state(ctx).getLong(KEY_LAST_OPEN_REFRESH, 0L);
        if (now - last < APP_OPEN_MIN_GAP_MS && now >= last) return;
        Cloud.state(ctx).edit().putLong(KEY_LAST_OPEN_REFRESH, now).apply();
        refreshSoon(ctx);
        schedulePeriodic(ctx);
    }

    /** One-off upload through WorkManager (runs when the network is available). */
    public static void refreshSoon(Context ctx) {
        try {
            OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(ShareWorker.class)
                    .setConstraints(networkConstraint())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build();
            WorkManager.getInstance(ctx.getApplicationContext())
                    .enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.REPLACE, req);
        } catch (Throwable t) {
            Log.e(Cloud.TAG, "Circle refresh enqueue failed", t);
        }
    }

    private static void schedulePeriodic(Context ctx) {
        try {
            PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(ShareWorker.class, 30, TimeUnit.MINUTES)
                    .setConstraints(networkConstraint())
                    .setInitialDelay(30, TimeUnit.MINUTES)
                    .build();
            WorkManager.getInstance(ctx.getApplicationContext())
                    .enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req);
        } catch (Throwable t) {
            Log.e(Cloud.TAG, "Circle periodic enqueue failed", t);
        }
    }

    private static Constraints networkConstraint() {
        return new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
    }

    /** Battery percent, or -1 when the phone does not report it. */
    private static int batteryPercent(Context ctx) {
        try {
            BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
            int level = bm == null ? -1 : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            return level >= 0 && level <= 100 ? level : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    public static final class ShareWorker extends Worker {

        public ShareWorker(@NonNull Context context, @NonNull WorkerParameters params) {
            super(context, params);
        }

        @NonNull
        @Override
        public Result doWork() {
            Context app = getApplicationContext();
            if (!Cloud.isActive(app) || !isSharing(app)) return Result.success();
            try {
                // No fix (permission missing, location off, background limits): try again next round.
                Location loc = CloudHelper.currentLocation(app, Priority.PRIORITY_BALANCED_POWER_ACCURACY, 20_000L);
                if (loc == null) return Result.success();
                // She may have switched it off while the fix was being taken.
                if (!isSharing(app)) return Result.success();
                CloudSocial.shareLocation(app, loc, batteryPercent(app));
                // Switched off during the upload: the "sharing: false" may have reached the server
                // first, so send it again rather than leave her position behind.
                if (!isSharing(app)) setSharing(app, false);
                return Result.success();
            } catch (CloudException e) {
                return e.isTransient() && getRunAttemptCount() < 5 ? Result.retry() : Result.success();
            } catch (Throwable t) {
                Log.w(Cloud.TAG, "Circle upload failed", t);
                return Result.success();
            }
        }
    }
}
