package com.example.naarishakti.cloud;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.example.naarishakti.core.Prefs;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;
import com.google.android.gms.tasks.Tasks;
import com.google.gson.JsonObject;

import java.util.concurrent.TimeUnit;

/**
 * Nearby-helper volunteering ({@link Prefs#HELPER_OPT_IN}): keeps a coarse location on the server
 * so the server can match her to SOS alerts within 2 km. Refreshed when protection starts and then
 * every 30 min while protection runs.
 */
public final class CloudHelper {

    static final String WORK_NOW = "ns_helper_now";
    static final String WORK_PERIODIC = "ns_helper_periodic";

    private CloudHelper() {}

    public static boolean isOptedIn(Context ctx) {
        return Prefs.get(ctx).getBoolean(Prefs.HELPER_OPT_IN, false);
    }

    /** Minimum gap between "app opened" refreshes. */
    private static final long APP_OPEN_MIN_GAP_MS = 10 * 60_000L;
    private static final String KEY_LAST_OPEN_REFRESH = "helper_last_open_refresh";

    /** Protection started: refresh now and keep the 30-min refresh scheduled (only when opted in). */
    static void onProtectionStarted(Context ctx) {
        if (!Cloud.isActive(ctx) || !isOptedIn(ctx)) return;
        refreshSoon(ctx);
        schedulePeriodic(ctx);
    }

    /**
     * v1.1: helpers are everyone who agreed to "Help women near you", so the 30-min refresh runs
     * whenever she is opted in, not only while protection runs. Nothing to stop here.
     */
    static void onProtectionStopped(Context ctx) {
    }

    /** The app came to the foreground: refresh (throttled) and make sure the periodic job exists. */
    static void onAppOpened(Context ctx) {
        if (!Cloud.isActive(ctx) || !isOptedIn(ctx)) return;
        long now = System.currentTimeMillis();
        long last = Cloud.state(ctx).getLong(KEY_LAST_OPEN_REFRESH, 0L);
        if (now - last < APP_OPEN_MIN_GAP_MS && now >= last) return;
        Cloud.state(ctx).edit().putLong(KEY_LAST_OPEN_REFRESH, now).apply();
        refreshSoon(ctx);
        schedulePeriodic(ctx);
    }

    /**
     * Saves the opt-in; on → schedule the 30-min refresh, off → remove the location from the server.
     * {@code protectionRunning} is kept for callers; since v1.1 the refresh runs either way.
     */
    static void setOptIn(Context ctx, boolean on, boolean protectionRunning) {
        Context app = ctx.getApplicationContext();
        Prefs.get(app).edit().putBoolean(Prefs.HELPER_OPT_IN, on).apply();
        Prefs.notifyChanged(app);
        if (!Cloud.isActive(app)) return;
        if (on) {
            // A queued "enabled: false" from earlier must not undo this opt-in.
            CloudOutbox.removeKey(app, "helper");
            schedulePeriodic(app);
        } else {
            cancelPeriodic(app);
            try {
                WorkManager.getInstance(app).cancelUniqueWork(WORK_NOW);
            } catch (Throwable ignored) {
            }
            JsonObject body = new JsonObject();
            body.addProperty("enabled", false);
            CloudOutbox.add(app, "PUT", "/api/v1/helper", body, "helper");
        }
    }

    /** One-off refresh through WorkManager (runs when the network is available). */
    static void refreshSoon(Context ctx) {
        try {
            OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(HelperWorker.class)
                    .setConstraints(networkConstraint())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build();
            WorkManager.getInstance(ctx.getApplicationContext())
                    .enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.REPLACE, req);
        } catch (Throwable t) {
            Log.e(Cloud.TAG, "Helper refresh enqueue failed", t);
        }
    }

    private static void schedulePeriodic(Context ctx) {
        try {
            PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(HelperWorker.class, 30, TimeUnit.MINUTES)
                    .setConstraints(networkConstraint())
                    .setInitialDelay(30, TimeUnit.MINUTES)
                    .build();
            WorkManager.getInstance(ctx.getApplicationContext())
                    .enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req);
        } catch (Throwable t) {
            Log.e(Cloud.TAG, "Helper periodic enqueue failed", t);
        }
    }

    static void cancelPeriodic(Context ctx) {
        try {
            WorkManager.getInstance(ctx.getApplicationContext()).cancelUniqueWork(WORK_PERIODIC);
        } catch (Throwable ignored) {
        }
    }

    private static Constraints networkConstraint() {
        return new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
    }

    /**
     * PUT /helper with a coarse, balanced-power fix (rounded to ~100 m). Blocking.
     *
     * @return false when no location is available (permission missing or no fix).
     */
    static boolean pushLocation(Context ctx) throws CloudException {
        Location loc = currentLocation(ctx, Priority.PRIORITY_BALANCED_POWER_ACCURACY, 20_000L);
        if (loc == null) return false;
        JsonObject body = new JsonObject();
        body.addProperty("enabled", true);
        body.addProperty("lat", Math.round(loc.getLatitude() * 1000d) / 1000d);
        body.addProperty("lng", Math.round(loc.getLongitude() * 1000d) / 1000d);
        ApiClient.get(ctx).call("PUT", "/api/v1/helper", body);
        return true;
    }

    static boolean hasLocationPermission(Context ctx) {
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Fresh fix with the given {@link Priority}, falling back to the last known location.
     * Blocking (must not run on the main thread); null when unavailable.
     */
    @Nullable
    @SuppressLint("MissingPermission")
    static Location currentLocation(Context ctx, int priority, long timeoutMs) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("currentLocation blocks; call it off the main thread");
        }
        if (!hasLocationPermission(ctx)) return null;
        FusedLocationProviderClient client = LocationServices.getFusedLocationProviderClient(ctx.getApplicationContext());
        CancellationTokenSource cts = new CancellationTokenSource();
        try {
            Location l = Tasks.await(client.getCurrentLocation(priority, cts.getToken()), timeoutMs, TimeUnit.MILLISECONDS);
            if (l != null) return l;
        } catch (Exception e) {
            cts.cancel();
            Log.i(Cloud.TAG, "No fresh location: " + e.getMessage());
        } catch (Throwable t) {
            cts.cancel();
            Log.w(Cloud.TAG, "Location lookup failed", t);
        }
        try {
            return Tasks.await(client.getLastLocation(), 5, TimeUnit.SECONDS);
        } catch (Throwable t) {
            return null;
        }
    }
}
