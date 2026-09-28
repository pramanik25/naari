package Location;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.naarishakti.R;
import com.example.naarishakti.core.Prefs;
import com.example.naarishakti.databinding.ActivityGeofenceSettingsBinding;
import com.example.naarishakti.databinding.GeofenceItemCardBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Overlay;
import org.osmdroid.views.overlay.Polygon;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import Home_Activity.FeatureKit;

/**
 * "Safe zones": drop a pin, pick a radius, name the place. Zones are stored in
 * {@link Prefs#GEOFENCES} as a JSON array of {id, latitude, longitude, radius, message} and
 * registered with the system by {@link GeofenceService#sync(Context)}.
 */
public class GeofenceSettingsActivity extends AppCompatActivity {

    private static final String TAG = "GeofenceSettings";
    /** Format written by older versions of this screen (Gson list of GeofenceData). */
    private static final String LEGACY_KEY = "geofence_list_key";
    private static final int MAX_ZONES = 100; // Android's per-app geofence limit
    private static final float DEFAULT_RADIUS = 300f;
    private static final double DEFAULT_LAT = 20.5937; // India
    private static final double DEFAULT_LNG = 78.9629;

    private static final class Zone {
        final String id;
        final double lat;
        final double lng;
        final float radius;
        final String message;

        Zone(String id, double lat, double lng, float radius, String message) {
            this.id = id;
            this.lat = lat;
            this.lng = lng;
            this.radius = radius;
            this.message = message;
        }

        GeoPoint point() {
            return new GeoPoint(lat, lng);
        }
    }

    private ActivityGeofenceSettingsBinding b;
    private final List<Zone> zones = new ArrayList<>();
    private ZoneAdapter adapter;
    private MapEventsOverlay eventsOverlay;
    @Nullable private GeoPoint pin;

    private ActivityResultLauncher<String[]> locationLauncher;
    private ActivityResultLauncher<String> backgroundLauncher;
    private boolean pendingMyLocation;
    private boolean backgroundPrompted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FeatureKit.initOsmdroid(this); // must precede MapView inflation
        b = ActivityGeofenceSettingsBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        locationLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(), this::onLocationResult);
        backgroundLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), this::onBackgroundResult);

        // Map
        FeatureKit.styleMap(b.mapView);
        b.mapView.getController().setZoom(4.5);
        b.mapView.getController().setCenter(new GeoPoint(DEFAULT_LAT, DEFAULT_LNG));
        eventsOverlay = new MapEventsOverlay(new MapEventsReceiver() {
            @Override
            public boolean singleTapConfirmedHelper(GeoPoint p) {
                setPin(p, false);
                return true;
            }

            @Override
            public boolean longPressHelper(GeoPoint p) {
                setPin(p, false);
                return true;
            }
        });

        // List
        adapter = new ZoneAdapter();
        b.zonesList.setLayoutManager(new LinearLayoutManager(this));
        b.zonesList.setAdapter(adapter);

        // Radius
        b.radiusSlider.setLabelFormatter(v -> getString(R.string.ub_zone_radius_value, Math.round(v)));
        b.radiusSlider.addOnChangeListener((slider, value, fromUser) -> {
            b.radiusValue.setText(getString(R.string.ub_zone_radius_value, Math.round(value)));
            if (pin != null) redrawOverlays();
        });
        b.radiusValue.setText(getString(R.string.ub_zone_radius_value, Math.round(b.radiusSlider.getValue())));

        b.backButton.setOnClickListener(v -> finish());
        b.myLocationButton.setOnClickListener(v -> {
            FeatureKit.tick(v);
            useMyLocation();
        });
        b.addZoneButton.setOnClickListener(v -> addZone());
        b.permissionFixButton.setOnClickListener(v -> onFixPermissionTapped());
        b.nameInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                b.nameLayout.setError(null);
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        loadZones();
        renderZones();
        redrawOverlays();

        if (!zones.isEmpty()) {
            Zone first = zones.get(0);
            b.mapView.getController().setZoom(zoomFor(first.radius));
            b.mapView.getController().setCenter(first.point());
        }
        if (!FeatureKit.hasLocationPermission(this)) {
            pendingMyLocation = zones.isEmpty();
            requestLocation();
        } else if (zones.isEmpty()) {
            centerOnMyLocation(false);
        }
        // Background access is requested right after foreground is granted, from the warning card,
        // or from the snackbar shown when a zone is added - never on every visit.
    }

    @Override
    protected void onResume() {
        super.onResume();
        b.mapView.onResume();
        renderPermissionWarning();
    }

    @Override
    protected void onPause() {
        super.onPause();
        b.mapView.onPause();
    }

    @Override
    protected void onDestroy() {
        b.mapView.onDetach();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ permissions

    private void requestLocation() {
        locationLauncher.launch(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION});
    }

    private void onLocationResult(Map<String, Boolean> result) {
        renderPermissionWarning();
        if (!FeatureKit.hasLocationPermission(this)) {
            pendingMyLocation = false;
            Snackbar.make(b.getRoot(), R.string.ub_location_denied, Snackbar.LENGTH_LONG)
                    .setAction(R.string.ub_open_settings, v -> FeatureKit.openAppSettings(this))
                    .show();
            return;
        }
        if (pendingMyLocation) {
            pendingMyLocation = false;
            centerOnMyLocation(false);
        }
        GeofenceService.sync(this); // permissions changed: (re)register what we have
        maybePromptBackground();
    }

    private boolean needsBackground() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !FeatureKit.hasBackgroundLocation(this);
    }

    /** Explains and asks for background location at most once per visit. */
    private void maybePromptBackground() {
        if (backgroundPrompted || !needsBackground()) return;
        backgroundPrompted = true;
        showBackgroundRationale();
    }

    private void showBackgroundRationale() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        boolean settingsPage = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.ub_zone_bg_dialog_title)
                .setMessage(settingsPage ? R.string.ub_zone_bg_dialog_body_settings : R.string.ub_zone_bg_dialog_body)
                .setPositiveButton(R.string.ub_continue, (d, w) -> requestBackground())
                .setNegativeButton(R.string.ub_not_now, null)
                .show();
    }

    private void requestBackground() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        if (!FeatureKit.isGranted(this, Manifest.permission.ACCESS_FINE_LOCATION)) {
            requestLocation();
            return;
        }
        backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
    }

    private void onBackgroundResult(boolean granted) {
        renderPermissionWarning();
        if (granted) {
            GeofenceService.sync(this);
            Snackbar.make(b.getRoot(), R.string.ub_zone_bg_granted, Snackbar.LENGTH_LONG).show();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            // The system won't show the prompt again; the only way is the app's settings page.
            Snackbar.make(b.getRoot(), R.string.ub_zone_bg_denied, Snackbar.LENGTH_LONG)
                    .setAction(R.string.ub_open_settings, v -> FeatureKit.openAppSettings(this))
                    .show();
        }
    }

    private void onFixPermissionTapped() {
        if (!FeatureKit.isGranted(this, Manifest.permission.ACCESS_FINE_LOCATION)) {
            requestLocation();
        } else {
            showBackgroundRationale();
        }
    }

    private void renderPermissionWarning() {
        if (!FeatureKit.isGranted(this, Manifest.permission.ACCESS_FINE_LOCATION)) {
            b.permissionWarning.setVisibility(View.VISIBLE);
            b.permissionWarningTitle.setText(R.string.ub_zone_perm_title);
            b.permissionWarningBody.setText(R.string.ub_zone_perm_body);
            b.permissionFixButton.setText(R.string.ub_allow_location);
        } else if (needsBackground()) {
            b.permissionWarning.setVisibility(View.VISIBLE);
            b.permissionWarningTitle.setText(R.string.ub_zone_bg_title);
            b.permissionWarningBody.setText(R.string.ub_zone_bg_body);
            b.permissionFixButton.setText(R.string.ub_zone_bg_fix);
        } else {
            b.permissionWarning.setVisibility(View.GONE);
        }
    }

    // ------------------------------------------------------------------ map

    private void useMyLocation() {
        if (!FeatureKit.hasLocationPermission(this)) {
            pendingMyLocation = true;
            requestLocation();
            return;
        }
        centerOnMyLocation(true);
    }

    private void centerOnMyLocation(final boolean dropPin) {
        FeatureKit.fetchLocation(this, loc -> {
            if (isFinishing() || isDestroyed()) return;
            if (loc == null) {
                if (dropPin) {
                    Snackbar.make(b.getRoot(), R.string.ub_location_unavailable, Snackbar.LENGTH_LONG).show();
                }
                return;
            }
            GeoPoint p = new GeoPoint(loc.getLatitude(), loc.getLongitude());
            b.mapView.getController().animateTo(p, 16.0, 500L);
            if (dropPin) setPin(p, true);
        });
    }

    private void setPin(GeoPoint p, boolean fromMyLocation) {
        pin = p;
        FeatureKit.tick(b.mapView);
        b.pinTitle.setText(fromMyLocation ? R.string.ub_zone_pin_mine : R.string.ub_zone_pin_placed);
        b.pinCoords.setText(FeatureKit.formatCoords(p.getLatitude(), p.getLongitude()));
        b.pinBadge.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.ns_rose_container)));
        b.pinBadge.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.ns_rose)));
        if (b.mapHint.getVisibility() == View.VISIBLE) {
            b.mapHint.animate().alpha(0f).setDuration(200)
                    .withEndAction(() -> b.mapHint.setVisibility(View.GONE)).start();
        }
        redrawOverlays();
    }

    private void clearPin() {
        pin = null;
        b.pinTitle.setText(R.string.ub_zone_no_pin);
        b.pinCoords.setText(R.string.ub_zone_no_pin_caption);
        b.pinBadge.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.ns_surface_highest)));
        b.pinBadge.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.ns_text_muted)));
        b.mapHint.setAlpha(1f);
        b.mapHint.setVisibility(View.VISIBLE);
        redrawOverlays();
    }

    private void redrawOverlays() {
        List<Overlay> overlays = b.mapView.getOverlays();
        overlays.clear();
        for (Zone z : zones) {
            Polygon c = new Polygon(b.mapView);
            c.setPoints(Polygon.pointsAsCircle(z.point(), z.radius));
            c.setFillColor(0x248B7CFF);
            c.setStrokeColor(0xB38B7CFF);
            c.setStrokeWidth(2f);
            c.setInfoWindow(null);
            overlays.add(c);
        }
        if (pin != null) {
            Polygon preview = new Polygon(b.mapView);
            preview.setPoints(Polygon.pointsAsCircle(pin, b.radiusSlider.getValue()));
            preview.setFillColor(0x33FF4D7E);
            preview.setStrokeColor(0xFFFF4D7E);
            preview.setStrokeWidth(3f);
            preview.setInfoWindow(null);
            overlays.add(preview);

            Marker marker = new Marker(b.mapView);
            marker.setPosition(pin);
            marker.setIcon(ContextCompat.getDrawable(this, R.drawable.ub_map_pin));
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            marker.setInfoWindow(null);
            overlays.add(marker);
        }
        // Last = top-most for touch: taps always place the pin.
        overlays.add(eventsOverlay);
        b.mapView.invalidate();
    }

    private static double zoomFor(float radius) {
        if (radius <= 200) return 16.5;
        if (radius <= 500) return 15.5;
        if (radius <= 1000) return 14.5;
        return 13.5;
    }

    private void focusZone(Zone z) {
        FeatureKit.tick(b.mapView);
        b.scroll.smoothScrollTo(0, Math.max(0, b.mapCard.getTop() - dp(16)));
        b.mapView.getController().animateTo(z.point(), zoomFor(z.radius), 500L);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------ add / delete

    private void addZone() {
        FeatureKit.tick(b.addZoneButton);
        if (pin == null) {
            Snackbar.make(b.getRoot(), R.string.ub_zone_need_pin, Snackbar.LENGTH_SHORT).show();
            b.scroll.smoothScrollTo(0, Math.max(0, b.mapCard.getTop() - dp(16)));
            return;
        }
        CharSequence raw = b.nameInput.getText();
        String name = raw == null ? "" : raw.toString().trim();
        if (name.isEmpty()) {
            b.nameLayout.setError(getString(R.string.ub_zone_need_name));
            b.nameInput.requestFocus();
            return;
        }
        if (zones.size() >= MAX_ZONES) {
            Snackbar.make(b.getRoot(), R.string.ub_zone_limit, Snackbar.LENGTH_LONG).show();
            return;
        }
        Zone z = new Zone(UUID.randomUUID().toString(), pin.getLatitude(), pin.getLongitude(),
                b.radiusSlider.getValue(), name);
        zones.add(z);
        persist();

        b.nameInput.setText(null);
        b.nameInput.clearFocus();
        hideKeyboard();
        clearPin();
        renderZones();
        FeatureKit.thud(b.addZoneButton);

        if (needsBackground()) {
            Snackbar.make(b.getRoot(), getString(R.string.ub_zone_added_needs_bg, name), Snackbar.LENGTH_LONG)
                    .setAction(R.string.ub_zone_bg_fix, v -> showBackgroundRationale())
                    .show();
        } else {
            Snackbar.make(b.getRoot(), getString(R.string.ub_zone_added, name), Snackbar.LENGTH_SHORT).show();
        }
    }

    private void confirmDelete(final int position) {
        if (position < 0 || position >= zones.size()) return;
        final Zone z = zones.get(position);
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.ub_zone_delete_title, z.message))
                .setMessage(R.string.ub_zone_delete_body)
                .setPositiveButton(R.string.ub_delete, (d, w) -> {
                    zones.remove(z);
                    persist();
                    renderZones();
                    redrawOverlays();
                    Snackbar.make(b.getRoot(), getString(R.string.ub_zone_deleted, z.message), Snackbar.LENGTH_LONG)
                            .setAction(R.string.ub_undo, v -> {
                                zones.add(Math.min(position, zones.size()), z);
                                persist();
                                renderZones();
                                redrawOverlays();
                            })
                            .show();
                })
                .setNegativeButton(R.string.ub_cancel, null)
                .show();
    }

    private void renderZones() {
        adapter.notifyDataSetChanged();
        boolean empty = zones.isEmpty();
        b.zonesEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        b.zonesList.setVisibility(empty ? View.GONE : View.VISIBLE);
        b.zoneCount.setText(empty ? "" : getResources().getQuantityString(R.plurals.ub_zone_count, zones.size(), zones.size()));
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(b.getRoot().getWindowToken(), 0);
    }

    // ------------------------------------------------------------------ persistence

    /** Writes zones in the canonical schema and asks GeofenceService to re-register them all. */
    private void persist() {
        JSONArray arr = new JSONArray();
        for (Zone z : zones) {
            try {
                JSONObject o = new JSONObject();
                o.put("id", z.id);
                o.put("latitude", z.lat);
                o.put("longitude", z.lng);
                o.put("radius", (double) z.radius);
                o.put("message", z.message);
                arr.put(o);
            } catch (JSONException e) {
                Log.e(TAG, "Cannot encode zone " + z.id, e);
            }
        }
        Prefs.get(this).edit().putString(Prefs.GEOFENCES, arr.toString()).apply();
        Prefs.notifyChanged(this);
        GeofenceService.sync(this);
    }

    private void loadZones() {
        SharedPreferences p = Prefs.get(this);
        String json = p.getString(Prefs.GEOFENCES, null);
        boolean fromLegacy = false;
        if (TextUtils.isEmpty(json)) {
            String legacy = p.getString(LEGACY_KEY, null);
            if (!TextUtils.isEmpty(legacy)) {
                json = legacy;
                fromLegacy = true;
            }
        }
        zones.clear();
        boolean rewrite = fromLegacy;
        if (!TextUtils.isEmpty(json)) {
            try {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) {
                        rewrite = true;
                        continue;
                    }
                    Zone z = parseZone(o);
                    if (z == null) {
                        rewrite = true;
                        continue;
                    }
                    if (!isCanonical(o)) rewrite = true;
                    zones.add(z);
                }
            } catch (JSONException e) {
                Log.w(TAG, "Unreadable saved geofences; leaving them untouched", e);
                rewrite = false;
            }
        }
        if (rewrite) {
            persist();
            if (fromLegacy) p.edit().remove(LEGACY_KEY).apply();
        }
    }

    private static String str(JSONObject o, String key) {
        if (!o.has(key) || o.isNull(key)) return "";
        return o.optString(key, "").trim();
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) if (!TextUtils.isEmpty(v)) return v;
        return "";
    }

    @Nullable
    private Zone parseZone(JSONObject o) {
        double lat = o.optDouble("latitude", Double.NaN);
        double lng = o.optDouble("longitude", Double.NaN);
        if (Double.isNaN(lat) || Double.isNaN(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180) return null;
        double r = o.optDouble("radius", DEFAULT_RADIUS);
        float radius = (Double.isNaN(r) || r <= 0) ? DEFAULT_RADIUS : (float) r;
        String id = firstNonEmpty(str(o, "id"), str(o, "geofenceId"));
        if (id.isEmpty()) id = UUID.randomUUID().toString();
        String message = firstNonEmpty(str(o, "message"), str(o, "notificationText"), str(o, "name"),
                str(o, "transitionAlert"));
        if (message.isEmpty()) message = getString(R.string.ub_zone_default_name);
        return new Zone(id, lat, lng, radius, message);
    }

    private static boolean isCanonical(JSONObject o) {
        return !str(o, "id").isEmpty() && o.has("latitude") && o.has("longitude") && o.has("radius")
                && !str(o, "message").isEmpty();
    }

    // ------------------------------------------------------------------ adapter

    private final class ZoneAdapter extends RecyclerView.Adapter<ZoneAdapter.Holder> {

        final class Holder extends RecyclerView.ViewHolder {
            final GeofenceItemCardBinding v;

            Holder(GeofenceItemCardBinding v) {
                super(v.getRoot());
                this.v = v;
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(GeofenceItemCardBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull final Holder h, int position) {
            final Zone z = zones.get(position);
            h.v.zoneName.setText(z.message);
            h.v.zoneMeta.setText(getString(R.string.ub_zone_meta, Math.round(z.radius),
                    FeatureKit.formatCoords(z.lat, z.lng)));
            h.v.card.setContentDescription(getString(R.string.ub_zone_item_cd, z.message, Math.round(z.radius)));
            h.v.card.setOnClickListener(v -> focusZone(z));
            h.v.deleteButton.setContentDescription(getString(R.string.ub_zone_delete_cd, z.message));
            h.v.deleteButton.setOnClickListener(v -> confirmDelete(h.getAdapterPosition()));
        }

        @Override
        public int getItemCount() {
            return zones.size();
        }
    }
}
