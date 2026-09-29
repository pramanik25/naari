package com.example.naarishakti.mesh;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Location;
import android.net.Uri;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.example.naarishakti.R;
import com.example.naarishakti.VoiceRecognitionService;
import com.example.naarishakti.core.Prefs;
import com.example.naarishakti.core.SafetyHooks;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Strategy;

import java.text.NumberFormat;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

import SQLite_Database.ProfileDbHelper;

/**
 * Offline mesh alert. While protection runs (and the owner enabled it) this phone discovers
 * nearby SOS phones; during its own SOS it advertises one. See {@link Mesh} for the format.
 * All callbacks arrive on the main thread.
 */
public final class MeshModule implements SafetyHooks.Module {

    private static final String TAG = "MeshModule";
    private static final long DEDUPE_MS = 10 * 60_000L;
    private static final long MAX_ALERT_AGE_MIN = 60;
    private static final float READVERTISE_DISTANCE_M = 50f;
    private static final int NOTIFICATION_BASE = 4200;

    @Nullable private Context app;
    @Nullable private ConnectionsClient client;
    private boolean protectionOn;
    private boolean discovering;
    private boolean advertising;
    @Nullable private Location advertisedAt;
    @Nullable private String firstName;
    private final Map<String, Long> seen = new HashMap<>();

    private final BroadcastReceiver settingsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!protectionOn) return;
            if (allowed(context)) {
                startDiscovery(context);
            } else {
                stopDiscovery();
                stopAdvertising();
            }
        }
    };

    // ---- SafetyHooks ----

    @Override
    public void onProtectionStarted(@NonNull Context ctx) {
        app = ctx.getApplicationContext();
        protectionOn = true;
        LocalBroadcastManager.getInstance(app)
                .registerReceiver(settingsReceiver, new IntentFilter(Prefs.ACTION_SETTINGS_UPDATED));
        if (allowed(app)) startDiscovery(app);
    }

    @Override
    public void onProtectionStopped(@NonNull Context ctx) {
        protectionOn = false;
        try {
            LocalBroadcastManager.getInstance(ctx.getApplicationContext()).unregisterReceiver(settingsReceiver);
        } catch (Exception ignored) {
            // Not registered.
        }
        stopDiscovery();
        stopAdvertising();
    }

    @Override
    public void onSosStarted(@NonNull Context ctx, @NonNull final SafetyHooks.Incident incident) {
        app = ctx.getApplicationContext();
        if (!allowed(app)) return;
        firstName = null;
        Location loc = incident.lastLocation;
        if (loc != null) {
            advertise(app, loc);
            return;
        }
        try {
            LocationServices.getFusedLocationProviderClient(app).getLastLocation()
                    .addOnSuccessListener(last -> {
                        if (last != null && SafetyHooks.current() == incident && !advertising) {
                            advertise(app, last);
                        }
                    });
        } catch (SecurityException e) {
            Log.w(TAG, "No location for the mesh advertisement yet");
        }
    }

    @Override
    public void onSosLocation(@NonNull Context ctx, @NonNull SafetyHooks.Incident incident, @NonNull Location location) {
        if (!allowed(ctx)) return;
        if (!advertising || advertisedAt == null || location.distanceTo(advertisedAt) > READVERTISE_DISTANCE_M) {
            advertise(ctx.getApplicationContext(), location);
        }
    }

    @Override
    public void onSosStopped(@NonNull Context ctx, @NonNull SafetyHooks.Incident incident, boolean userInitiated) {
        stopAdvertising();
    }

    // ---- Advertising (the phone in trouble) ----

    private void advertise(Context ctx, Location loc) {
        ConnectionsClient c = client(ctx);
        if (advertising) {
            c.stopAdvertising();
            advertising = false;
        }
        String name = Mesh.encode(firstName(ctx), loc.getLatitude(), loc.getLongitude(),
                System.currentTimeMillis() / 60_000L);
        AdvertisingOptions options = new AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build();
        advertising = true;
        advertisedAt = loc;
        try {
            c.startAdvertising(name, Mesh.SERVICE_ID, rejectAll, options)
                    .addOnFailureListener(e -> {
                        Log.w(TAG, "Advertising failed: " + e.getMessage());
                        advertising = false;
                        advertisedAt = null;
                    });
        } catch (Exception e) {
            Log.w(TAG, "Advertising unavailable: " + e.getMessage());
            advertising = false;
            advertisedAt = null;
        }
    }

    private void stopAdvertising() {
        if (client != null && advertising) client.stopAdvertising();
        advertising = false;
        advertisedAt = null;
    }

    /** Only the endpoint name matters; never accept a connection. */
    private final ConnectionLifecycleCallback rejectAll = new ConnectionLifecycleCallback() {
        @Override
        public void onConnectionInitiated(@NonNull String endpointId, @NonNull ConnectionInfo info) {
            if (client != null) client.rejectConnection(endpointId);
        }

        @Override
        public void onConnectionResult(@NonNull String endpointId, @NonNull ConnectionResolution result) {
            // Never connected.
        }

        @Override
        public void onDisconnected(@NonNull String endpointId) {
            // Never connected.
        }
    };

    // ---- Discovery (phones nearby) ----

    private void startDiscovery(Context ctx) {
        if (discovering) return;
        DiscoveryOptions options = new DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build();
        discovering = true;
        try {
            client(ctx).startDiscovery(Mesh.SERVICE_ID, discovery, options)
                    .addOnFailureListener(e -> {
                        Log.w(TAG, "Discovery failed: " + e.getMessage());
                        discovering = false;
                    });
        } catch (Exception e) {
            Log.w(TAG, "Discovery unavailable: " + e.getMessage());
            discovering = false;
        }
    }

    private void stopDiscovery() {
        if (client != null && discovering) client.stopDiscovery();
        discovering = false;
    }

    private final EndpointDiscoveryCallback discovery = new EndpointDiscoveryCallback() {
        @Override
        public void onEndpointFound(@NonNull String endpointId, @NonNull DiscoveredEndpointInfo info) {
            final Mesh.Alert alert = Mesh.decode(info.getEndpointName());
            final Context ctx = app;
            if (alert == null || ctx == null || !Mesh.SERVICE_ID.equals(info.getServiceId())) return;
            long ageMin = System.currentTimeMillis() / 60_000L - alert.unixMinutes;
            if (ageMin > MAX_ALERT_AGE_MIN) return;
            if (!firstSighting(endpointId)) return;
            try {
                LocationServices.getFusedLocationProviderClient(ctx).getLastLocation()
                        .addOnCompleteListener(t -> notifyAlert(ctx, alert,
                                t.isSuccessful() ? t.getResult() : null));
            } catch (SecurityException e) {
                notifyAlert(ctx, alert, null);
            }
        }

        @Override
        public void onEndpointLost(@NonNull String endpointId) {
            // Keep the notification: the person may simply have moved out of radio range.
        }
    };

    private boolean firstSighting(String endpointId) {
        long now = SystemClock.elapsedRealtime();
        Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue() > DEDUPE_MS) it.remove();
        }
        if (seen.containsKey(endpointId)) return false;
        seen.put(endpointId, now);
        return true;
    }

    private void notifyAlert(Context ctx, Mesh.Alert alert, @Nullable Location me) {
        VoiceRecognitionService.createChannels(ctx);
        String who = TextUtils.isEmpty(alert.firstName) ? ctx.getString(R.string.en_mesh_someone) : alert.firstName;

        String title;
        if (me != null) {
            float[] d = new float[1];
            Location.distanceBetween(me.getLatitude(), me.getLongitude(), alert.lat, alert.lng, d);
            title = ctx.getString(R.string.en_mesh_title, formatDistance(ctx, d[0]));
        } else {
            title = ctx.getString(R.string.en_mesh_title_unknown);
        }
        long ageMin = Math.max(0, System.currentTimeMillis() / 60_000L - alert.unixMinutes);
        String when = ageMin < 1 ? ctx.getString(R.string.en_mesh_just_now)
                : ctx.getResources().getQuantityString(R.plurals.en_mesh_minutes_ago, (int) ageMin, (int) ageMin);
        String text = ctx.getString(R.string.en_mesh_text, who, when);

        int id = NOTIFICATION_BASE + (who.hashCode() & 0x3ff);
        Uri maps = Uri.parse(String.format(Locale.US,
                "https://www.google.com/maps/search/?api=1&query=%.6f,%.6f", alert.lat, alert.lng));
        PendingIntent open = PendingIntent.getActivity(ctx, id,
                new Intent(Intent.ACTION_VIEW, maps).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent call = PendingIntent.getActivity(ctx, id + 1,
                new Intent(Intent.ACTION_DIAL, Uri.parse("tel:112")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, VoiceRecognitionService.CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.eng_ic_warning)
                .setColor(ContextCompat.getColor(ctx, R.color.ns_rose))
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setContentIntent(open)
                .addAction(R.drawable.eng_ic_location, ctx.getString(R.string.en_mesh_open_map), open)
                .addAction(R.drawable.eng_ic_call, ctx.getString(R.string.en_mesh_call_112), call);
        VoiceRecognitionService.notifySafely(ctx, id, b.build());
    }

    private static String formatDistance(Context ctx, float meters) {
        if (meters < 1000f) {
            int rounded = Math.max(50, Math.round(meters / 50f) * 50);
            return ctx.getString(R.string.en_mesh_distance_m, rounded);
        }
        NumberFormat nf = NumberFormat.getNumberInstance();
        nf.setMaximumFractionDigits(1);
        return ctx.getString(R.string.en_mesh_distance_km, nf.format(meters / 1000f));
    }

    // ---- Helpers ----

    private static boolean allowed(Context ctx) {
        return Mesh.isEnabled(ctx) && Mesh.hasPermissions(ctx);
    }

    private ConnectionsClient client(Context ctx) {
        if (client == null) client = Nearby.getConnectionsClient(ctx.getApplicationContext());
        return client;
    }

    @Nullable
    private String firstName(Context ctx) {
        if (firstName != null) return firstName;
        ProfileDbHelper db = new ProfileDbHelper(ctx);
        try {
            String full = db.getProfileName();
            firstName = full == null ? "" : full.trim().split("\\s+")[0];
        } catch (Exception e) {
            firstName = "";
        } finally {
            db.close();
        }
        return firstName;
    }
}
