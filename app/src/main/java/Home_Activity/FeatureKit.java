package Home_Activity;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.telephony.SmsManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.example.naarishakti.R;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;

import org.osmdroid.config.Configuration;
import org.osmdroid.config.IConfigurationProvider;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.views.CustomZoomButtonsController;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.TilesOverlay;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Small shared toolkit for the feature screens (call police, live location, safe zones, contacts):
 * location lookup, SMS / share helpers, dialing, haptics and the dark osmdroid map styling.
 */
public final class FeatureKit {

    private static final String TAG = "FeatureKit";

    private FeatureKit() {}

    // ------------------------------------------------------------------ permissions

    public static boolean isGranted(Context ctx, String permission) {
        return ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean hasLocationPermission(Context ctx) {
        return isGranted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
                || isGranted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION);
    }

    /** True when background location is granted, or not needed (API < 29). */
    public static boolean hasBackgroundLocation(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return hasLocationPermission(ctx);
        return isGranted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION);
    }

    public static void openAppSettings(Context ctx) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", ctx.getPackageName(), null));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "No app settings screen", e);
        }
    }

    // ------------------------------------------------------------------ location

    public interface LocationResult {
        void onLocation(@Nullable Location location);
    }

    /** One-shot current location (falls back to last known). Result arrives on the main thread. */
    @SuppressLint("MissingPermission")
    public static void fetchLocation(@NonNull Context ctx, @NonNull final LocationResult cb) {
        if (!hasLocationPermission(ctx)) {
            cb.onLocation(null);
            return;
        }
        final FusedLocationProviderClient client = LocationServices.getFusedLocationProviderClient(ctx);
        int priority = isGranted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
                ? Priority.PRIORITY_HIGH_ACCURACY
                : Priority.PRIORITY_BALANCED_POWER_ACCURACY;
        try {
            client.getCurrentLocation(priority, new CancellationTokenSource().getToken())
                    .addOnSuccessListener(loc -> {
                        if (loc != null) cb.onLocation(loc);
                        else lastLocation(client, cb);
                    })
                    .addOnFailureListener(e -> lastLocation(client, cb));
        } catch (SecurityException e) {
            cb.onLocation(null);
        }
    }

    @SuppressLint("MissingPermission")
    private static void lastLocation(FusedLocationProviderClient client, final LocationResult cb) {
        try {
            client.getLastLocation()
                    .addOnSuccessListener(cb::onLocation)
                    .addOnFailureListener(e -> cb.onLocation(null));
        } catch (SecurityException e) {
            cb.onLocation(null);
        }
    }

    public static String mapsLink(double lat, double lng) {
        return String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", lat, lng);
    }

    public static String formatCoords(double lat, double lng) {
        return String.format(Locale.US, "%.5f, %.5f", lat, lng);
    }

    // ------------------------------------------------------------------ SMS / share / dial

    @SuppressWarnings("deprecation")
    private static SmsManager smsManager(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SmsManager m = ctx.getSystemService(SmsManager.class);
            if (m != null) return m;
        }
        return SmsManager.getDefault();
    }

    /** Sends {@code text} to every number; returns how many sends were handed to the radio. */
    public static int sendSms(Context ctx, List<String> numbers, String text) {
        if (!isGranted(ctx, Manifest.permission.SEND_SMS)) return 0;
        int sent = 0;
        try {
            SmsManager sms = smsManager(ctx);
            ArrayList<String> parts = sms.divideMessage(text);
            for (String n : numbers) {
                if (TextUtils.isEmpty(n)) continue;
                try {
                    if (parts.size() > 1) sms.sendMultipartTextMessage(n, null, parts, null, null);
                    else sms.sendTextMessage(n, null, text, null, null);
                    sent++;
                } catch (Exception e) {
                    Log.e(TAG, "SMS to " + n + " failed", e);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "SMS unavailable", e);
        }
        return sent;
    }

    public static void shareText(Activity activity, String text) {
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, activity.getString(R.string.ub_share_subject))
                .putExtra(Intent.EXTRA_TEXT, text);
        try {
            activity.startActivity(Intent.createChooser(send, activity.getString(R.string.ub_share_chooser)));
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "No share target", e);
        }
    }

    /** Opens the dialer pre-filled (never places the call). */
    public static boolean dial(Context ctx, String number) {
        return startSafely(ctx, new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + number)));
    }

    public static boolean openUrl(Context ctx, String url) {
        return startSafely(ctx, new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }

    public static boolean startSafely(Context ctx, Intent intent) {
        try {
            ctx.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "Cannot start " + intent, e);
            return false;
        }
    }

    // ------------------------------------------------------------------ feel

    public static void tick(View v) {
        if (v != null) v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    public static void thud(View v) {
        if (v != null) v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
    }

    /** "Aarti Sharma" -> "AS", "+9198..." -> "#". */
    public static String initials(String name) {
        if (name == null) return "#";
        String t = name.trim();
        if (t.isEmpty() || !Character.isLetter(t.charAt(0))) return "#";
        String[] parts = t.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (!p.isEmpty() && Character.isLetter(p.charAt(0))) sb.append(Character.toUpperCase(p.charAt(0)));
            if (sb.length() == 2) break;
        }
        return sb.length() == 0 ? "#" : sb.toString();
    }

    // ------------------------------------------------------------------ maps

    /** Must run before inflating a layout that contains a MapView. */
    public static void initOsmdroid(Context ctx) {
        Context app = ctx.getApplicationContext();
        IConfigurationProvider cfg = Configuration.getInstance();
        cfg.load(app, app.getSharedPreferences("osmdroid", Context.MODE_PRIVATE));
        cfg.setUserAgentValue(app.getPackageName());
        File base = new File(app.getCacheDir(), "osmdroid");
        cfg.setOsmdroidBasePath(base);
        cfg.setOsmdroidTileCache(new File(base, "tiles"));
    }

    /** MAPNIK tiles, multitouch, no zoom buttons, "midnight" tiles in dark theme, plays nice in scroll views. */
    @SuppressLint("ClickableViewAccessibility")
    public static void styleMap(MapView map) {
        Context ctx = map.getContext();
        map.setTileSource(TileSourceFactory.MAPNIK);
        map.setMultiTouchControls(true);
        map.setTilesScaledToDpi(true);
        map.setMinZoomLevel(4.0);
        map.setMaxZoomLevel(19.0);
        map.getZoomController().setVisibility(CustomZoomButtonsController.Visibility.NEVER);
        TilesOverlay tiles = map.getOverlayManager().getTilesOverlay();
        tiles.setLoadingBackgroundColor(ContextCompat.getColor(ctx, R.color.ns_surface));
        tiles.setLoadingLineColor(ContextCompat.getColor(ctx, R.color.ns_surface_high));
        // Dim the tiles only in the dark theme; the light theme uses the regular map colours.
        int uiMode = ctx.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        tiles.setColorFilter(uiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
                ? darkTileFilter() : null);
        // Let the map pan inside a NestedScrollView instead of scrolling the page.
        map.setOnTouchListener((v, e) -> {
            ViewParent parent = v.getParent();
            if (parent != null) {
                int action = e.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    parent.requestDisallowInterceptTouchEvent(true);
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    parent.requestDisallowInterceptTouchEvent(false);
                }
            }
            return false;
        });
    }

    /** Invert + 180° hue rotation keeps hues (water stays blue) but makes light tiles dark, then mutes. */
    public static ColorMatrixColorFilter darkTileFilter() {
        ColorMatrix m = new ColorMatrix(new float[]{
                -1, 0, 0, 0, 255,
                0, -1, 0, 0, 255,
                0, 0, -1, 0, 255,
                0, 0, 0, 1, 0});
        ColorMatrix hue180 = new ColorMatrix(new float[]{
                -0.574f, 1.430f, 0.144f, 0, 0,
                0.426f, 0.430f, 0.144f, 0, 0,
                0.426f, 1.430f, -0.856f, 0, 0,
                0, 0, 0, 1, 0});
        ColorMatrix sat = new ColorMatrix();
        sat.setSaturation(0.6f);
        ColorMatrix dim = new ColorMatrix();
        dim.setScale(0.86f, 0.86f, 0.98f, 1f);
        m.postConcat(hue180);
        m.postConcat(sat);
        m.postConcat(dim);
        return new ColorMatrixColorFilter(m);
    }
}
