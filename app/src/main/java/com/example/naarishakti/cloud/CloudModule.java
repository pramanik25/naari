package com.example.naarishakti.cloud;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Location;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.example.naarishakti.core.Prefs;
import com.example.naarishakti.core.SafetyHooks;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Mirrors incidents to the Naari Shakti API server. Every write goes through the persisted,
 * ordered {@link CloudOutbox}, so an SOS fired offline reaches the server as soon as the network
 * is back. Locations are batched (flushed every 10 s, the first one immediately because it
 * triggers the nearby-helper fan-out); battery is reported every 5 min during an SOS.
 *
 * <p>All callbacks arrive on the main thread; state below is main-thread only.
 */
public final class CloudModule implements SafetyHooks.Module {

    private static final long LOCATION_FLUSH_MS = 10_000L;
    private static final long BATTERY_EVERY_MS = 5 * 60_000L;
    private static final int MAX_POINTS_PER_CALL = 100;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<JsonObject> points = new ArrayList<>();
    private Context app;
    private String incidentId;
    private boolean firstPointSent;

    private final Runnable flushTask = new Runnable() {
        @Override
        public void run() {
            flushLocations();
            if (incidentId != null) main.postDelayed(this, LOCATION_FLUSH_MS);
        }
    };

    private final Runnable batteryTask = new Runnable() {
        @Override
        public void run() {
            if (incidentId == null || app == null) return;
            int battery = batteryPercent(app);
            if (battery >= 0) {
                JsonObject body = new JsonObject();
                body.addProperty("battery", battery);
                CloudOutbox.add(app, "POST", incidentPath(incidentId) + "/battery", body,
                        "incident:" + incidentId + ":battery");
            }
            main.postDelayed(this, BATTERY_EVERY_MS);
        }
    };

    public CloudModule() {}

    // ------------------------------------------------------------------ protection

    @Override
    public void onProtectionStarted(@NonNull Context ctx) {
        if (!Cloud.isActive(ctx)) return;
        CloudAlerts.setEngineRunning(ctx, true);
        CloudHelper.onProtectionStarted(ctx);
    }

    @Override
    public void onProtectionStopped(@NonNull Context ctx) {
        if (!Cloud.isAvailable()) return;
        CloudAlerts.setEngineRunning(ctx, false);
        CloudHelper.onProtectionStopped(ctx);
    }

    // ------------------------------------------------------------------ SOS

    @Override
    public void onSosStarted(@NonNull Context ctx, @NonNull SafetyHooks.Incident incident) {
        if (!Cloud.isActive(ctx)) return;
        app = ctx.getApplicationContext();
        stopTimers();
        points.clear();
        incidentId = incident.id;
        firstPointSent = false;

        JsonObject body = new JsonObject();
        body.addProperty("token", incident.token);
        body.addProperty("source", incident.source);
        body.addProperty("silent", incident.silent);
        body.addProperty("startedAt", incident.startedAt);
        int contacts = 0;
        try {
            contacts = Prefs.getContacts(app).size();
        } catch (Throwable t) {
            Log.w(Cloud.TAG, "Contacts unreadable", t);
        }
        body.addProperty("contactsCount", contacts);
        try {
            String message = Prefs.getEmergencyMessage(app);
            if (message != null && !message.isEmpty()) {
                body.addProperty("message", message.length() > 500 ? message.substring(0, 500) : message);
            }
        } catch (Throwable t) {
            Log.w(Cloud.TAG, "Emergency message unreadable", t);
        }
        int battery = batteryPercent(app);
        if (battery >= 0) body.addProperty("battery", battery);
        // v1.1: false = never alert nearby helpers for this incident (guardians still get it).
        body.addProperty("broadcast", Prefs.get(app).getBoolean(Prefs.NEARBY_BROADCAST, true));
        CloudOutbox.add(app, "PUT", incidentPath(incident.id), body, null);

        // A location that arrived before this callback (engine ordering) is still worth sending.
        Location last = incident.lastLocation;
        if (last != null) addPoint(last);

        main.postDelayed(flushTask, LOCATION_FLUSH_MS);
        main.postDelayed(batteryTask, BATTERY_EVERY_MS);
    }

    @Override
    public void onSosLocation(@NonNull Context ctx, @NonNull SafetyHooks.Incident incident,
                              @NonNull Location location) {
        if (incidentId == null || !incidentId.equals(incident.id) || !Cloud.isActive(ctx)) return;
        addPoint(location);
    }

    @Override
    public void onSosDuress(@NonNull Context ctx, @NonNull SafetyHooks.Incident incident) {
        if (!Cloud.isActive(ctx)) return;
        Context a = ctx.getApplicationContext();
        if (incident.id.equals(incidentId)) flushLocations();
        CloudOutbox.add(a, "POST", incidentPath(incident.id) + "/duress", null, null);
    }

    @Override
    public void onSosStopped(@NonNull Context ctx, @NonNull SafetyHooks.Incident incident,
                             boolean userInitiated) {
        if (!Cloud.isActive(ctx)) {
            stopTimers();
            incidentId = null;
            return;
        }
        Context a = ctx.getApplicationContext();
        if (incident.id.equals(incidentId)) flushLocations();
        stopTimers();
        incidentId = null;
        points.clear();

        JsonObject body = new JsonObject();
        body.addProperty("userInitiated", userInitiated);
        CloudOutbox.add(a, "POST", incidentPath(incident.id) + "/end", body, null);
        // Evidence captured in the last seconds of the SOS.
        CloudUploads.enqueue(a);
    }

    // ------------------------------------------------------------------ internals

    private void addPoint(Location location) {
        JsonObject p = new JsonObject();
        p.addProperty("lat", location.getLatitude());
        p.addProperty("lng", location.getLongitude());
        if (location.hasAccuracy()) p.addProperty("accuracy", location.getAccuracy());
        p.addProperty("at", location.getTime() > 0 ? location.getTime() : System.currentTimeMillis());
        points.add(p);
        if (!firstPointSent || points.size() >= MAX_POINTS_PER_CALL) flushLocations();
    }

    private void flushLocations() {
        if (app == null || incidentId == null || points.isEmpty()) return;
        firstPointSent = true;
        JsonArray batch = new JsonArray();
        for (int i = 0; i < points.size(); i++) {
            batch.add(points.get(i));
            if (batch.size() == MAX_POINTS_PER_CALL) {
                sendBatch(batch);
                batch = new JsonArray();
            }
        }
        if (batch.size() > 0) sendBatch(batch);
        points.clear();
    }

    private void sendBatch(JsonArray batch) {
        JsonObject body = new JsonObject();
        body.add("points", batch);
        CloudOutbox.add(app, "POST", incidentPath(incidentId) + "/locations", body, null);
    }

    private void stopTimers() {
        main.removeCallbacks(flushTask);
        main.removeCallbacks(batteryTask);
    }

    static String incidentPath(String id) {
        return "/api/v1/incidents/" + Uri.encode(id);
    }

    /** Battery level 0-100, or -1 when unknown. */
    static int batteryPercent(Context ctx) {
        try {
            BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) {
                int v = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                if (v > 0 && v <= 100) return v;
            }
            Intent i = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i != null) {
                int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                if (level >= 0 && scale > 0) return Math.round(level * 100f / scale);
            }
        } catch (Throwable t) {
            Log.w(Cloud.TAG, "Battery level unavailable", t);
        }
        return -1;
    }
}
