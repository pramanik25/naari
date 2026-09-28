package Home_Activity;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.example.naarishakti.R;
import com.example.naarishakti.core.Prefs;
import com.example.naarishakti.core.ProtectionController;
import com.example.naarishakti.databinding.ActivityLiveLocationBinding;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polygon;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import Services.LiveShareService;

/**
 * Shows the user's position on a dark map with a readable address, lets her share it once, and
 * starts/stops timed live sharing (handled by {@link LiveShareService}).
 */
public class LiveLocationActivity extends AppCompatActivity {

    private static final int PENDING_NONE = 0;
    private static final int PENDING_TRACK = 1;
    private static final int PENDING_SMS = 2;
    private static final int PENDING_LIVE = 3;

    private static final double DEFAULT_LAT = 20.5937; // India
    private static final double DEFAULT_LNG = 78.9629;
    private static final float REGEOCODE_METERS = 60f;

    private ActivityLiveLocationBinding b;
    private FusedLocationProviderClient fused;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService geocodeExecutor = Executors.newSingleThreadExecutor();

    private ActivityResultLauncher<String[]> permissionLauncher;
    private int pendingAction = PENDING_NONE;

    @Nullable private Marker meMarker;
    @Nullable private Polygon accuracyCircle;
    @Nullable private Location lastLocation;
    @Nullable private Location lastGeocoded;
    @Nullable private String lastAddress;
    private boolean firstFix = true;
    private boolean updatesActive;
    private int selectedMinutes = 30;
    @Nullable private ObjectAnimator pulse;

    private final LocationCallback locationCallback = new LocationCallback() {
        @Override
        public void onLocationResult(@NonNull LocationResult result) {
            Location l = result.getLastLocation();
            if (l != null) onNewLocation(l);
        }
    };

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            renderLiveState();
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            renderLiveState();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FeatureKit.initOsmdroid(this); // must precede MapView inflation
        b = ActivityLiveLocationBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        fused = LocationServices.getFusedLocationProviderClient(this);
        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(), this::onPermissionResult);

        // Map card takes ~45% of the screen height.
        int screenH = getResources().getDisplayMetrics().heightPixels;
        ViewGroup.LayoutParams lp = b.mapCard.getLayoutParams();
        lp.height = Math.max(dp(260), (int) (screenH * 0.45f));
        b.mapCard.setLayoutParams(lp);

        FeatureKit.styleMap(b.mapView);
        b.mapView.getController().setZoom(4.5);
        b.mapView.getController().setCenter(new GeoPoint(DEFAULT_LAT, DEFAULT_LNG));

        b.backButton.setOnClickListener(v -> finish());
        b.recenterButton.setOnClickListener(v -> {
            FeatureKit.tick(v);
            if (lastLocation != null) {
                b.mapView.getController().animateTo(toGeo(lastLocation), 17.0, 400L);
            } else {
                ensureLocation(PENDING_TRACK);
            }
        });
        b.grantLocationButton.setOnClickListener(v -> onGrantLocationTapped());
        b.copyButton.setOnClickListener(v -> copyLocation());
        b.openMapsButton.setOnClickListener(v -> openInMaps());
        b.shareSheetButton.setOnClickListener(v -> {
            FeatureKit.tick(v);
            shareSheet();
        });
        b.textContactsButton.setOnClickListener(v -> {
            FeatureKit.tick(v);
            textContacts();
        });
        b.addContactsButton.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsEmergencyActivity.class)));
        b.durationChips.setOnCheckedStateChangeListener((group, ids) -> {
            int id = ids.isEmpty() ? R.id.chip30 : ids.get(0);
            if (id == R.id.chip15) selectedMinutes = 15;
            else if (id == R.id.chip60) selectedMinutes = 60;
            else if (id == R.id.chip120) selectedMinutes = 120;
            else selectedMinutes = 30;
        });
        b.liveButton.setOnClickListener(v -> {
            FeatureKit.tick(v);
            onLiveButton();
        });

        if (!FeatureKit.hasLocationPermission(this)) {
            b.mapPlaceholder.setVisibility(View.VISIBLE);
            ensureLocation(PENDING_TRACK);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        LocalBroadcastManager.getInstance(this)
                .registerReceiver(stateReceiver, new IntentFilter(ProtectionController.ACTION_STATE_CHANGED));
        renderLiveState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        b.mapView.onResume();
        boolean granted = FeatureKit.hasLocationPermission(this);
        b.mapPlaceholder.setVisibility(granted ? View.GONE : View.VISIBLE);
        if (granted) startUpdates();
        renderLiveState();
    }

    @Override
    protected void onPause() {
        super.onPause();
        b.mapView.onPause();
        stopUpdates();
    }

    @Override
    protected void onStop() {
        super.onStop();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(stateReceiver);
        handler.removeCallbacks(ticker);
        stopPulse();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        geocodeExecutor.shutdownNow();
        b.mapView.onDetach();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ permissions

    private void ensureLocation(int action) {
        if (FeatureKit.hasLocationPermission(this)) {
            runPending(action);
            return;
        }
        pendingAction = action;
        permissionLauncher.launch(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION});
    }

    private void onGrantLocationTapped() {
        boolean canAsk = shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
                || shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION);
        // After a "don't ask again" the system dialog no longer appears; send the user to settings.
        if (!canAsk && pendingAction == PENDING_NONE && askedOnce) {
            FeatureKit.openAppSettings(this);
        } else {
            ensureLocation(PENDING_TRACK);
        }
    }

    private boolean askedOnce;

    private void onPermissionResult(Map<String, Boolean> result) {
        askedOnce = true;
        int action = pendingAction;
        pendingAction = PENDING_NONE;
        boolean location = FeatureKit.hasLocationPermission(this);
        b.mapPlaceholder.setVisibility(location ? View.GONE : View.VISIBLE);
        if (!location) {
            Snackbar.make(b.getRoot(), R.string.ub_location_denied, Snackbar.LENGTH_LONG)
                    .setAction(R.string.ub_open_settings, v -> FeatureKit.openAppSettings(this))
                    .show();
            return;
        }
        startUpdates();
        if (action == PENDING_SMS || action == PENDING_LIVE) {
            if (!FeatureKit.isGranted(this, Manifest.permission.SEND_SMS)) {
                Snackbar.make(b.getRoot(), action == PENDING_LIVE ? R.string.ub_live_sms_needed : R.string.ub_sms_denied_fallback,
                        Snackbar.LENGTH_LONG).show();
                if (action == PENDING_SMS) shareSheet();
                return;
            }
        }
        runPending(action);
    }

    private void runPending(int action) {
        switch (action) {
            case PENDING_TRACK:
                startUpdates();
                break;
            case PENDING_SMS:
                sendLocationSms();
                break;
            case PENDING_LIVE:
                startLiveSharing();
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------------ location updates

    @SuppressLint("MissingPermission")
    private void startUpdates() {
        if (updatesActive || !FeatureKit.hasLocationPermission(this)) return;
        boolean fine = FeatureKit.isGranted(this, Manifest.permission.ACCESS_FINE_LOCATION);
        LocationRequest request = new LocationRequest.Builder(
                fine ? Priority.PRIORITY_HIGH_ACCURACY : Priority.PRIORITY_BALANCED_POWER_ACCURACY, 5000L)
                .setMinUpdateIntervalMillis(2000L)
                .build();
        try {
            fused.requestLocationUpdates(request, locationCallback, Looper.getMainLooper());
            updatesActive = true;
            if (lastLocation == null) {
                fused.getLastLocation().addOnSuccessListener(l -> {
                    if (l != null && lastLocation == null && !isDestroyed()) onNewLocation(l);
                });
            }
        } catch (SecurityException e) {
            updatesActive = false;
        }
    }

    private void stopUpdates() {
        if (!updatesActive) return;
        fused.removeLocationUpdates(locationCallback);
        updatesActive = false;
    }

    private static GeoPoint toGeo(Location l) {
        return new GeoPoint(l.getLatitude(), l.getLongitude());
    }

    private void onNewLocation(Location l) {
        lastLocation = l;
        GeoPoint p = toGeo(l);

        if (l.hasAccuracy() && l.getAccuracy() > 0) {
            if (accuracyCircle == null) {
                accuracyCircle = new Polygon(b.mapView);
                accuracyCircle.setFillColor(0x26FF4D7E);
                accuracyCircle.setStrokeColor(0x66FF4D7E);
                accuracyCircle.setStrokeWidth(2f);
                accuracyCircle.setInfoWindow(null);
                b.mapView.getOverlays().add(0, accuracyCircle);
            }
            accuracyCircle.setPoints(Polygon.pointsAsCircle(p, Math.min(l.getAccuracy(), 2000f)));
        }
        if (meMarker == null) {
            meMarker = new Marker(b.mapView);
            meMarker.setIcon(ContextCompat.getDrawable(this, R.drawable.ub_map_dot));
            meMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
            meMarker.setInfoWindow(null);
            meMarker.setOnMarkerClickListener((m, map) -> true);
            b.mapView.getOverlays().add(meMarker);
        }
        meMarker.setPosition(p);

        if (firstFix) {
            firstFix = false;
            b.mapView.getController().setZoom(17.0);
            b.mapView.getController().setCenter(p);
        }
        b.mapView.invalidate();

        String coords = FeatureKit.formatCoords(l.getLatitude(), l.getLongitude());
        b.coordsText.setText(l.hasAccuracy()
                ? getString(R.string.ub_live_coords_accuracy, coords, Math.round(l.getAccuracy()))
                : coords);
        if (lastAddress == null) b.addressText.setText(coords);
        b.copyButton.setEnabled(true);
        b.openMapsButton.setEnabled(true);

        if (lastGeocoded == null || lastGeocoded.distanceTo(l) > REGEOCODE_METERS) geocode(l);
    }

    private void geocode(final Location l) {
        lastGeocoded = l;
        if (!Geocoder.isPresent() || geocodeExecutor.isShutdown()) return;
        final double lat = l.getLatitude();
        final double lng = l.getLongitude();
        final Context app = getApplicationContext();
        try {
            geocodeExecutor.execute(() -> {
                final String line = reverseGeocode(app, lat, lng);
                handler.post(() -> {
                    if (isFinishing() || isDestroyed() || line == null) return;
                    lastAddress = line;
                    b.addressText.setText(line);
                });
            });
        } catch (Exception ignored) {
            // Executor shut down while leaving the screen.
        }
    }

    @Nullable
    @SuppressWarnings("deprecation")
    private static String reverseGeocode(Context ctx, double lat, double lng) {
        try {
            List<Address> list = new Geocoder(ctx, Locale.getDefault()).getFromLocation(lat, lng, 1);
            if (list == null || list.isEmpty()) return null;
            Address a = list.get(0);
            if (a.getMaxAddressLineIndex() >= 0 && !TextUtils.isEmpty(a.getAddressLine(0))) {
                return a.getAddressLine(0);
            }
            List<String> parts = new ArrayList<>();
            if (!TextUtils.isEmpty(a.getFeatureName())) parts.add(a.getFeatureName());
            if (!TextUtils.isEmpty(a.getSubLocality())) parts.add(a.getSubLocality());
            if (!TextUtils.isEmpty(a.getLocality())) parts.add(a.getLocality());
            if (!TextUtils.isEmpty(a.getAdminArea())) parts.add(a.getAdminArea());
            return parts.isEmpty() ? null : TextUtils.join(", ", parts);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ share once

    @Nullable
    private String currentLink() {
        return lastLocation == null ? null
                : FeatureKit.mapsLink(lastLocation.getLatitude(), lastLocation.getLongitude());
    }

    private boolean requireFix() {
        if (lastLocation != null) return true;
        Snackbar.make(b.getRoot(), R.string.ub_live_still_locating, Snackbar.LENGTH_SHORT).show();
        ensureLocation(PENDING_TRACK);
        return false;
    }

    private String shareMessage() {
        String link = currentLink();
        return lastAddress != null
                ? getString(R.string.ub_location_message_address, lastAddress, link)
                : getString(R.string.ub_location_message_share, link);
    }

    private void copyLocation() {
        if (!requireFix()) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.ub_share_subject), shareMessage()));
        FeatureKit.tick(b.copyButton);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Snackbar.make(b.getRoot(), R.string.ub_copied, Snackbar.LENGTH_SHORT).show();
        }
    }

    private void openInMaps() {
        if (!requireFix()) return;
        double lat = lastLocation.getLatitude();
        double lng = lastLocation.getLongitude();
        Uri geo = Uri.parse(String.format(Locale.US, "geo:%.6f,%.6f?q=%.6f,%.6f", lat, lng, lat, lng));
        if (!FeatureKit.startSafely(this, new Intent(Intent.ACTION_VIEW, geo))) {
            FeatureKit.openUrl(this, FeatureKit.mapsLink(lat, lng));
        }
    }

    private void shareSheet() {
        if (!requireFix()) return;
        FeatureKit.shareText(this, shareMessage());
    }

    private void textContacts() {
        final int n = Prefs.getContactNumbers(this).size();
        if (n == 0) {
            showNoContactsSnackbar();
            return;
        }
        if (!requireFix()) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle(getResources().getQuantityString(R.plurals.ub_share_confirm_title, n, n))
                .setMessage(R.string.ub_share_confirm_body)
                .setPositiveButton(R.string.ub_send, (d, w) -> {
                    if (FeatureKit.isGranted(this, Manifest.permission.SEND_SMS)) {
                        sendLocationSms();
                    } else {
                        pendingAction = PENDING_SMS;
                        permissionLauncher.launch(new String[]{Manifest.permission.SEND_SMS});
                    }
                })
                .setNegativeButton(R.string.ub_cancel, null)
                .show();
    }

    private void sendLocationSms() {
        if (lastLocation == null) {
            requireFix();
            return;
        }
        int sent = FeatureKit.sendSms(this, Prefs.getContactNumbers(this), shareMessage());
        FeatureKit.thud(b.textContactsButton);
        if (sent > 0) {
            Snackbar.make(b.getRoot(), getResources().getQuantityString(R.plurals.ub_sms_sent, sent, sent),
                    Snackbar.LENGTH_LONG).show();
        } else {
            Snackbar.make(b.getRoot(), R.string.ub_sms_failed, Snackbar.LENGTH_LONG)
                    .setAction(R.string.ub_share, v -> shareSheet())
                    .show();
        }
    }

    private void showNoContactsSnackbar() {
        Snackbar.make(b.getRoot(), R.string.ub_no_contacts, Snackbar.LENGTH_LONG)
                .setAction(R.string.ub_add_contacts, v ->
                        startActivity(new Intent(this, SettingsEmergencyActivity.class)))
                .show();
    }

    // ------------------------------------------------------------------ live sharing

    private void onLiveButton() {
        if (LiveShareService.isRunning()) {
            LiveShareService.stop(this);
            Snackbar.make(b.getRoot(), R.string.ub_live_stopped, Snackbar.LENGTH_SHORT).show();
            handler.postDelayed(ticker, 300);
            return;
        }
        if (Prefs.getContactNumbers(this).isEmpty()) {
            showNoContactsSnackbar();
            return;
        }
        List<String> needed = new ArrayList<>();
        if (!FeatureKit.hasLocationPermission(this)) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (!FeatureKit.isGranted(this, Manifest.permission.SEND_SMS)) {
            needed.add(Manifest.permission.SEND_SMS);
        }
        if (Build.VERSION.SDK_INT >= 33
                && !FeatureKit.isGranted(this, Manifest.permission.POST_NOTIFICATIONS)) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS); // for the "sharing" notification; optional
        }
        if (needed.isEmpty()) {
            startLiveSharing();
        } else {
            pendingAction = PENDING_LIVE;
            permissionLauncher.launch(needed.toArray(new String[0]));
        }
    }

    private void startLiveSharing() {
        if (!FeatureKit.hasLocationPermission(this) || !FeatureKit.isGranted(this, Manifest.permission.SEND_SMS)) {
            Snackbar.make(b.getRoot(), R.string.ub_live_sms_needed, Snackbar.LENGTH_LONG).show();
            return;
        }
        LiveShareService.start(this, selectedMinutes);
        FeatureKit.thud(b.liveButton);
        Snackbar.make(b.getRoot(), getString(R.string.ub_live_started, durationLabel(selectedMinutes)),
                Snackbar.LENGTH_LONG).show();
        // The service reports back via ACTION_STATE_CHANGED; refresh shortly in case it is already up.
        handler.postDelayed(ticker, 400);
    }

    private String durationLabel(int minutes) {
        if (minutes == 15) return getString(R.string.ub_live_15);
        if (minutes == 60) return getString(R.string.ub_live_60);
        if (minutes == 120) return getString(R.string.ub_live_120);
        return getString(R.string.ub_live_30);
    }

    private void renderLiveState() {
        handler.removeCallbacks(ticker);
        boolean running = LiveShareService.isRunning();
        int contacts = Prefs.getContactNumbers(this).size();

        if (running) {
            long remaining = Math.max(0L, LiveShareService.getEndTimeMillis() - System.currentTimeMillis());
            b.liveStatusRow.setVisibility(View.VISIBLE);
            b.liveStatusText.setText(getResources().getQuantityString(
                    R.plurals.ub_live_sharing_status, contacts, contacts, formatRemaining(remaining)));
            b.noContactsRow.setVisibility(View.GONE);
            b.durationLabel.setVisibility(View.GONE);
            b.durationChips.setVisibility(View.GONE);
            b.liveButton.setText(R.string.ub_live_stop);
            b.liveButton.setIconResource(R.drawable.ub_ic_stop);
            b.liveButton.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.ns_danger)));
            b.liveButton.setEnabled(true);
            b.mapStatusText.setText(R.string.ub_live_status_live);
            b.mapStatusDot.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.ns_rose)));
            startPulse();
            handler.postDelayed(ticker, 1000);
        } else {
            b.liveStatusRow.setVisibility(View.GONE);
            b.noContactsRow.setVisibility(contacts == 0 ? View.VISIBLE : View.GONE);
            b.durationLabel.setVisibility(View.VISIBLE);
            b.durationChips.setVisibility(View.VISIBLE);
            b.liveButton.setText(R.string.ub_live_start);
            b.liveButton.setIconResource(R.drawable.ub_ic_location);
            b.liveButton.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.ns_button_primary_bg));
            b.liveButton.setEnabled(contacts > 0);
            b.mapStatusText.setText(R.string.ub_live_status_idle);
            b.mapStatusDot.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this,
                    lastLocation != null ? R.color.ns_safe : R.color.ns_text_faint)));
            stopPulse();
        }
    }

    private static String formatRemaining(long ms) {
        long total = ms / 1000L;
        long h = total / 3600L;
        long m = (total % 3600L) / 60L;
        long s = total % 60L;
        return h > 0
                ? String.format(Locale.US, "%d:%02d:%02d", h, m, s)
                : String.format(Locale.US, "%02d:%02d", m, s);
    }

    private void startPulse() {
        if (pulse != null) return;
        ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(b.livePulse,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 0.4f, 1f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.4f, 1f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 0.9f, 0f));
        a.setDuration(1400);
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.start();
        pulse = a;
    }

    private void stopPulse() {
        if (pulse != null) {
            pulse.cancel();
            pulse = null;
        }
        b.livePulse.setAlpha(0f);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
