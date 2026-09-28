package Location;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.example.naarishakti.core.Prefs;
import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofencingClient;
import com.google.android.gms.location.GeofencingRequest;
import com.google.android.gms.location.LocationServices;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Registers the saved places ({@link Prefs#GEOFENCES}) with Play Services geofencing. Geofences
 * persist inside Play Services, so nothing needs to keep running: call {@link #sync} after the list
 * changes and after boot. The class is still a Service only so older callers that start it keep
 * working; starting it simply runs {@link #sync} and stops.
 */
public class GeofenceService extends Service {

    private static final String TAG = "GeofenceService";
    private static final int MAX_GEOFENCES = 100; // Play Services limit per app

    /** One saved place parsed from {@link Prefs#GEOFENCES}. */
    static final class Place {
        final String id;
        final double latitude;
        final double longitude;
        final float radius;
        final String message;

        Place(String id, double latitude, double longitude, float radius, String message) {
            this.id = id;
            this.latitude = latitude;
            this.longitude = longitude;
            this.radius = radius;
            this.message = message;
        }
    }

    /** Removes every registered geofence and re-registers all places saved in prefs. */
    @SuppressLint("MissingPermission") // checked in hasPermissions()
    public static void sync(Context ctx) {
        final Context app = ctx.getApplicationContext();
        if (!hasPermissions(app)) {
            Log.w(TAG, "Geofences not synced: fine/background location permission missing");
            return;
        }
        final GeofencingClient client = LocationServices.getGeofencingClient(app);
        final PendingIntent pi = pendingIntent(app);
        final List<Place> places = loadPlaces(app);

        client.removeGeofences(pi).addOnCompleteListener(removed -> {
            if (places.isEmpty()) {
                Log.d(TAG, "No saved places; all geofences removed");
                return;
            }
            GeofencingRequest.Builder request = new GeofencingRequest.Builder()
                    .setInitialTrigger(0); // only alert on real transitions, not on registration
            for (Place p : places) {
                request.addGeofence(new Geofence.Builder()
                        .setRequestId(p.id)
                        .setCircularRegion(p.latitude, p.longitude, p.radius)
                        .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER | Geofence.GEOFENCE_TRANSITION_EXIT)
                        .setExpirationDuration(Geofence.NEVER_EXPIRE)
                        .build());
            }
            try {
                client.addGeofences(request.build(), pi)
                        .addOnSuccessListener(v -> Log.d(TAG, "Registered " + places.size() + " geofences"))
                        .addOnFailureListener(e -> Log.e(TAG, "addGeofences failed: " + e.getMessage()));
            } catch (SecurityException e) {
                Log.e(TAG, "Location permission revoked during sync", e);
            }
        });
    }

    static boolean hasPermissions(Context ctx) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static PendingIntent pendingIntent(Context app) {
        Intent intent = new Intent(app, GeofenceBroadcastReceiver.class);
        // Play Services fills in the transition details, so the PendingIntent must be mutable.
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
        return PendingIntent.getBroadcast(app, 0, intent, flags);
    }

    /** Parses {@link Prefs#GEOFENCES}; tolerates the older field names (geofenceId/transitionAlert). */
    static List<Place> loadPlaces(Context ctx) {
        List<Place> out = new ArrayList<>();
        String json = Prefs.get(ctx).getString(Prefs.GEOFENCES, "");
        if (TextUtils.isEmpty(json)) return out;
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length() && out.size() < MAX_GEOFENCES; i++) {
                JSONObject o = array.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("id", o.optString("geofenceId", ""));
                double lat = o.optDouble("latitude", Double.NaN);
                double lng = o.optDouble("longitude", Double.NaN);
                float radius = (float) o.optDouble("radius", 100);
                String message = o.optString("message", o.optString("transitionAlert", ""));
                if (TextUtils.isEmpty(id) || Double.isNaN(lat) || Double.isNaN(lng) || radius <= 0) continue;
                out.add(new Place(id, lat, lng, radius, message));
            }
        } catch (Exception e) {
            Log.e(TAG, "Bad geofence JSON", e);
        }
        return out;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        sync(this);
        stopSelf(startId);
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
