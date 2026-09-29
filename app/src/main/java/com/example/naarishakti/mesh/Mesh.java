package com.example.naarishakti.mesh;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.example.naarishakti.core.Prefs;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Offline SOS over Google Nearby Connections. Nothing is connected or transferred: an SOS phone
 * advertises an endpoint whose <i>name</i> carries the alert, and protected phones nearby
 * discover it. Wire format (ASCII separators, at most {@link #MAX_NAME_BYTES} bytes):
 * {@code SOS|<first name>|<lat>,<lng>|<unix minutes>}.
 */
public final class Mesh {

    public static final String SERVICE_ID = "com.example.naarishakti.sos";
    static final int MAX_NAME_BYTES = 120;
    private static final String PREFIX = "SOS";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private Mesh() {}

    /** Runtime permissions Nearby Connections needs on this API level (request all before enabling). */
    public static String[] requiredPermissions() {
        List<String> p = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            p.add(Manifest.permission.BLUETOOTH_SCAN);
            p.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            p.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            p.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        } else {
            p.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        return p.toArray(new String[0]);
    }

    public static boolean hasPermissions(Context ctx) {
        for (String perm : requiredPermissions()) {
            if (ContextCompat.checkSelfPermission(ctx, perm) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    /** The owner turned the offline mesh on (default off). */
    public static boolean isEnabled(Context ctx) {
        SharedPreferences prefs = Prefs.get(ctx);
        try {
            return prefs.getBoolean(Prefs.MESH_ENABLED, false);
        } catch (ClassCastException e) {
            return false;
        }
    }

    // ---- Wire format ----

    static String encode(@Nullable String firstName, double lat, double lng, long unixMinutes) {
        String coords = String.format(Locale.US, "%.5f,%.5f", lat, lng);
        String tail = "|" + coords + "|" + unixMinutes;
        String name = sanitize(firstName);
        int budget = MAX_NAME_BYTES - (PREFIX.length() + 1) - tail.getBytes(UTF8).length;
        while (name.getBytes(UTF8).length > budget && name.length() > 0) {
            int cut = name.offsetByCodePoints(name.length(), -1);
            name = name.substring(0, cut);
        }
        return PREFIX + "|" + name + tail;
    }

    @Nullable
    static Alert decode(@Nullable String endpointName) {
        if (endpointName == null) return null;
        String[] parts = endpointName.split("\\|", -1);
        if (parts.length != 4 || !PREFIX.equals(parts[0])) return null;
        String[] ll = parts[2].split(",");
        if (ll.length != 2) return null;
        try {
            double lat = Double.parseDouble(ll[0]);
            double lng = Double.parseDouble(ll[1]);
            long minutes = Long.parseLong(parts[3]);
            if (Math.abs(lat) > 90 || Math.abs(lng) > 180) return null;
            return new Alert(parts[1], lat, lng, minutes);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String sanitize(@Nullable String s) {
        if (TextUtils.isEmpty(s)) return "";
        String first = s.trim().split("\\s+")[0];
        return first.replace("|", "").replace(",", "");
    }

    static final class Alert {
        final String firstName;
        final double lat;
        final double lng;
        final long unixMinutes;

        Alert(String firstName, double lat, double lng, long unixMinutes) {
            this.firstName = firstName;
            this.lat = lat;
            this.lng = lng;
            this.unixMinutes = unixMinutes;
        }
    }
}
