package com.example.naarishakti.journey;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;

import com.example.naarishakti.MainActivity;
import com.example.naarishakti.R;
import com.example.naarishakti.databinding.JrActivitySafePlacesBinding;
import com.example.naarishakti.databinding.JrItemPlaceBinding;
import com.google.android.material.snackbar.Snackbar;

import org.json.JSONArray;
import org.json.JSONObject;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.overlay.Marker;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import Home_Activity.FeatureKit;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Police stations, hospitals, clinics, pharmacies and fuel stations within 3 km, from
 * OpenStreetMap (Overpass API). The last result is cached for offline use.
 */
public class SafePlacesActivity extends AppCompatActivity {

    private static final String TAG = "SafePlaces";
    private static final String OVERPASS_URL = "https://overpass-api.de/api/interpreter";
    private static final String CACHE_FILE = "jr_safe_places.json";
    private static final int RADIUS_M = 3000;
    private static final int MAX_LIST = 80;
    private static final double DEFAULT_LAT = 20.5937;
    private static final double DEFAULT_LNG = 78.9629;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    static final String POLICE = "police";
    static final String HOSPITAL = "hospital";
    static final String CLINIC = "clinic";
    static final String PHARMACY = "pharmacy";
    static final String FUEL = "fuel";

    private static final class Place {
        String name;
        String type;
        String phone;
        double lat;
        double lng;
        boolean open24;
        float dist;
    }

    private JrActivitySafePlacesBinding b;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build();

    private ActivityResultLauncher<String[]> permLauncher;
    private final List<Place> places = new ArrayList<>();
    private final List<Marker> markers = new ArrayList<>();
    @Nullable private Marker meMarker;
    @Nullable private String filter;
    @Nullable private Location here;
    private boolean loading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FeatureKit.initOsmdroid(this);
        b = JrActivitySafePlacesBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        permLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(), this::onPermissions);

        int screenH = getResources().getDisplayMetrics().heightPixels;
        ViewGroup.LayoutParams lp = b.mapCard.getLayoutParams();
        lp.height = Math.max(JourneyUtil.dp(this, 240), (int) (screenH * 0.38f));
        b.mapCard.setLayoutParams(lp);

        FeatureKit.styleMap(b.mapView);
        b.mapView.getController().setZoom(5.0);
        b.mapView.getController().setCenter(new GeoPoint(DEFAULT_LAT, DEFAULT_LNG));

        b.backButton.setOnClickListener(v -> finish());
        b.refreshButton.setOnClickListener(v -> start());
        b.recenterButton.setOnClickListener(v -> {
            if (here != null) {
                b.mapView.getController().animateTo(new GeoPoint(here.getLatitude(), here.getLongitude()), 15.0, 400L);
            } else {
                start();
            }
        });
        b.filterChips.setOnCheckedStateChangeListener((group, ids) -> {
            int id = ids.isEmpty() ? R.id.chipAll : ids.get(0);
            if (id == R.id.chipPolice) filter = POLICE;
            else if (id == R.id.chipHospital) filter = HOSPITAL;
            else if (id == R.id.chipClinic) filter = CLINIC;
            else if (id == R.id.chipPharmacy) filter = PHARMACY;
            else if (id == R.id.chipFuel) filter = FUEL;
            else filter = null;
            renderPlaces();
        });

        start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        b.mapView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        b.mapView.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        io.shutdownNow();
        b.mapView.onDetach();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ loading

    private void start() {
        if (loading) return;
        if (!JourneyUtil.hasLocation(this)) {
            permLauncher.launch(JourneyUtil.locationPerms());
            return;
        }
        locate();
    }

    private void onPermissions(Map<String, Boolean> result) {
        if (JourneyUtil.hasLocation(this)) {
            locate();
        } else {
            Snackbar.make(b.getRoot(), R.string.jr_sp_no_location, Snackbar.LENGTH_LONG)
                    .setAction(R.string.jr_open_settings, v -> JourneyUtil.openAppSettings(this))
                    .show();
            load(null); // cached places, if any
        }
    }

    private void locate() {
        setLoading(true);
        b.summaryText.setText(R.string.jr_sp_locating);
        FeatureKit.fetchLocation(this, loc -> {
            if (isFinishing() || isDestroyed()) return;
            here = loc;
            if (loc != null) showMe(loc);
            load(loc);
        });
    }

    private void load(@Nullable final Location loc) {
        setLoading(true);
        b.summaryText.setText(R.string.jr_sp_loading);
        final File cache = new File(getCacheDir(), CACHE_FILE);
        try {
            io.execute(() -> {
                String raw = null;
                boolean offline = false;
                long savedAt = 0L;
                double originLat = loc != null ? loc.getLatitude() : DEFAULT_LAT;
                double originLng = loc != null ? loc.getLongitude() : DEFAULT_LNG;
                if (loc != null) {
                    try {
                        raw = query(loc.getLatitude(), loc.getLongitude());
                        writeCache(cache, loc.getLatitude(), loc.getLongitude(), raw);
                    } catch (Exception e) {
                        Log.w(TAG, "Overpass query failed", e);
                        raw = null;
                    }
                }
                if (raw == null) {
                    JSONObject cached = readCache(cache);
                    if (cached != null) {
                        raw = cached.optString("data", null);
                        savedAt = cached.optLong("time", 0L);
                        offline = true;
                        if (loc == null) {
                            originLat = cached.optDouble("lat", DEFAULT_LAT);
                            originLng = cached.optDouble("lng", DEFAULT_LNG);
                        }
                    }
                }
                final List<Place> parsed = parse(raw, originLat, originLng);
                final boolean fromCache = offline;
                final long cacheTime = savedAt;
                final boolean nothing = raw == null;
                final double cLat = originLat;
                final double cLng = originLng;
                handler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    setLoading(false);
                    places.clear();
                    places.addAll(parsed);
                    renderBanner(fromCache, cacheTime, nothing);
                    if (here == null && !parsed.isEmpty()) {
                        b.mapView.getController().setZoom(14.0);
                        b.mapView.getController().setCenter(new GeoPoint(cLat, cLng));
                    }
                    renderPlaces();
                });
            });
        } catch (Exception e) {
            // Executor shut down while leaving.
        }
    }

    private void setLoading(boolean on) {
        loading = on;
        JourneyUtil.show(b.progress, on);
        b.refreshButton.setEnabled(!on);
    }

    private String query(double lat, double lng) throws IOException {
        String around = String.format(Locale.US, "(around:%d,%.6f,%.6f)", RADIUS_M, lat, lng);
        String filterExpr = "[\"amenity\"~\"^(police|hospital|clinic|pharmacy|fuel)$\"]";
        String q = "[out:json][timeout:25];("
                + "node" + filterExpr + around + ";"
                + "way" + filterExpr + around + ";"
                + ");out center tags 300;";
        Request req = new Request.Builder()
                .url(OVERPASS_URL)
                .header("User-Agent", getPackageName())
                .post(new FormBody.Builder().add("data", q).build())
                .build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) throw new IOException("HTTP " + resp.code());
            ResponseBody body = resp.body();
            if (body == null) throw new IOException("Empty body");
            String s = body.string();
            new JSONObject(s); // validate before caching
            return s;
        } catch (org.json.JSONException e) {
            throw new IOException("Bad JSON", e);
        }
    }

    private static void writeCache(File file, double lat, double lng, String raw) {
        try {
            JSONObject o = new JSONObject();
            o.put("lat", lat);
            o.put("lng", lng);
            o.put("time", System.currentTimeMillis());
            o.put("data", raw);
            try (OutputStream os = new FileOutputStream(file)) {
                os.write(o.toString().getBytes(UTF8));
            }
        } catch (Exception e) {
            Log.w(TAG, "Cache write failed", e);
        }
    }

    @Nullable
    private static JSONObject readCache(File file) {
        if (!file.exists()) return null;
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[(int) file.length()];
            int off = 0;
            while (off < buf.length) {
                int r = in.read(buf, off, buf.length - off);
                if (r < 0) break;
                off += r;
            }
            return new JSONObject(new String(buf, 0, off, UTF8));
        } catch (Exception e) {
            Log.w(TAG, "Cache read failed", e);
            return null;
        }
    }

    private List<Place> parse(@Nullable String raw, double lat, double lng) {
        List<Place> out = new ArrayList<>();
        if (raw == null) return out;
        try {
            JSONArray els = new JSONObject(raw).optJSONArray("elements");
            if (els == null) return out;
            for (int i = 0; i < els.length(); i++) {
                JSONObject e = els.optJSONObject(i);
                if (e == null) continue;
                JSONObject tags = e.optJSONObject("tags");
                if (tags == null) continue;
                String type = tags.optString("amenity", "");
                if (!isKnown(type)) continue;
                double pLat;
                double pLng;
                if (e.has("lat")) {
                    pLat = e.optDouble("lat");
                    pLng = e.optDouble("lon");
                } else {
                    JSONObject c = e.optJSONObject("center");
                    if (c == null) continue;
                    pLat = c.optDouble("lat");
                    pLng = c.optDouble("lon");
                }
                if (Double.isNaN(pLat) || Double.isNaN(pLng)) continue;
                Place p = new Place();
                p.type = type;
                p.lat = pLat;
                p.lng = pLng;
                String name = tags.optString("name", "");
                if (TextUtils.isEmpty(name)) name = tags.optString("name:en", "");
                p.name = TextUtils.isEmpty(name) ? getString(labelRes(type)) : name;
                String phone = tags.optString("phone", "");
                if (TextUtils.isEmpty(phone)) phone = tags.optString("contact:phone", "");
                if (phone.contains(";")) phone = phone.substring(0, phone.indexOf(';'));
                p.phone = phone.trim();
                p.open24 = "24/7".equals(tags.optString("opening_hours", "").trim());
                float[] d = new float[1];
                Location.distanceBetween(lat, lng, pLat, pLng, d);
                p.dist = d[0];
                out.add(p);
            }
        } catch (Exception e) {
            Log.w(TAG, "Parse failed", e);
        }
        Collections.sort(out, (x, y) -> Float.compare(x.dist, y.dist));
        return out;
    }

    // ------------------------------------------------------------------ render

    private void renderBanner(boolean offline, long savedAt, boolean nothing) {
        if (offline) {
            b.offlineText.setText(getString(R.string.jr_sp_offline, JourneyUtil.clock(this, savedAt)));
            JourneyUtil.show(b.offlineBanner, true);
        } else if (nothing) {
            b.offlineText.setText(R.string.jr_sp_offline_nocache);
            JourneyUtil.show(b.offlineBanner, true);
        } else {
            JourneyUtil.show(b.offlineBanner, false);
        }
    }

    private void showMe(Location loc) {
        GeoPoint p = new GeoPoint(loc.getLatitude(), loc.getLongitude());
        if (meMarker == null) {
            meMarker = new Marker(b.mapView);
            meMarker.setIcon(ContextCompat.getDrawable(this, R.drawable.ub_map_dot));
            meMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
            meMarker.setInfoWindow(null);
            meMarker.setOnMarkerClickListener((m, map) -> true);
            b.mapView.getOverlays().add(meMarker);
        }
        meMarker.setPosition(p);
        b.mapView.getController().setZoom(14.5);
        b.mapView.getController().setCenter(p);
        b.mapView.invalidate();
    }

    private void renderPlaces() {
        for (Marker m : markers) b.mapView.getOverlays().remove(m);
        markers.clear();
        b.placeList.removeAllViews();

        List<Place> shown = new ArrayList<>();
        for (Place p : places) if (filter == null || filter.equals(p.type)) shown.add(p);

        LayoutInflater inflater = LayoutInflater.from(this);
        int count = 0;
        for (final Place p : shown) {
            Marker m = new Marker(b.mapView);
            m.setPosition(new GeoPoint(p.lat, p.lng));
            m.setIcon(ContextCompat.getDrawable(this, pinRes(p.type)));
            m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            m.setInfoWindow(null);
            m.setOnMarkerClickListener((marker, map) -> {
                Snackbar.make(b.getRoot(), getString(R.string.jr_sp_marker, p.name,
                                JourneyUtil.distance(this, p.dist)), Snackbar.LENGTH_LONG)
                        .setAction(R.string.jr_sp_navigate, v -> navigate(p))
                        .show();
                return true;
            });
            markers.add(m);
            b.mapView.getOverlays().add(m);

            if (count++ >= MAX_LIST) continue;
            JrItemPlaceBinding row = JrItemPlaceBinding.inflate(inflater, b.placeList, false);
            row.icon.setImageResource(iconRes(p.type));
            MainActivity.tintBadge(row.icon, colorRes(p.type), containerRes(p.type));
            row.name.setText(p.name);
            row.typeChip.setText(labelRes(p.type));
            row.typeChip.setTextColor(ContextCompat.getColor(this, colorRes(p.type)));
            ViewCompat.setBackgroundTintList(row.typeChip,
                    ColorStateList.valueOf(ContextCompat.getColor(this, containerRes(p.type))));
            row.distance.setText(JourneyUtil.distance(this, p.dist));
            JourneyUtil.show(row.open24, p.open24);
            JourneyUtil.show(row.callButton, !TextUtils.isEmpty(p.phone));
            row.callButton.setOnClickListener(v ->
                    JourneyUtil.startSafely(this, new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + p.phone))));
            row.navigateButton.setOnClickListener(v -> navigate(p));
            row.getRoot().setOnClickListener(v -> {
                b.mapView.getController().animateTo(new GeoPoint(p.lat, p.lng), 17.0, 400L);
                b.scroll.smoothScrollTo(0, 0);
            });
            b.placeList.addView(row.getRoot());
        }
        b.mapView.invalidate();

        if (loading) return;
        b.summaryText.setText(shown.isEmpty() ? getString(R.string.jr_sp_summary_none)
                : getString(R.string.jr_sp_summary, shown.size()));
        JourneyUtil.show(b.emptyText, shown.isEmpty());
        b.emptyText.setText(places.isEmpty() ? R.string.jr_sp_empty : R.string.jr_sp_empty_filter);
    }

    private void navigate(Place p) {
        Uri nav = Uri.parse(String.format(Locale.US, "google.navigation:q=%.6f,%.6f&mode=w", p.lat, p.lng));
        Intent i = new Intent(Intent.ACTION_VIEW, nav).setPackage("com.google.android.apps.maps");
        if (!JourneyUtil.startSafely(this, i)) {
            Uri web = Uri.parse(String.format(Locale.US,
                    "https://www.google.com/maps/dir/?api=1&destination=%.6f,%.6f", p.lat, p.lng));
            JourneyUtil.startSafely(this, new Intent(Intent.ACTION_VIEW, web));
        }
    }

    // ------------------------------------------------------------------ categories

    private static boolean isKnown(String type) {
        return POLICE.equals(type) || HOSPITAL.equals(type) || CLINIC.equals(type)
                || PHARMACY.equals(type) || FUEL.equals(type);
    }

    @StringRes
    private static int labelRes(String type) {
        if (POLICE.equals(type)) return R.string.jr_sp_type_police;
        if (HOSPITAL.equals(type)) return R.string.jr_sp_type_hospital;
        if (CLINIC.equals(type)) return R.string.jr_sp_type_clinic;
        if (PHARMACY.equals(type)) return R.string.jr_sp_type_pharmacy;
        return R.string.jr_sp_type_fuel;
    }

    @DrawableRes
    private static int iconRes(String type) {
        if (POLICE.equals(type)) return R.drawable.ua_ic_police;
        if (HOSPITAL.equals(type)) return R.drawable.jr_ic_hospital;
        if (CLINIC.equals(type)) return R.drawable.jr_ic_clinic;
        if (PHARMACY.equals(type)) return R.drawable.jr_ic_pharmacy;
        return R.drawable.jr_ic_fuel;
    }

    @DrawableRes
    private static int pinRes(String type) {
        if (POLICE.equals(type)) return R.drawable.jr_pin_police;
        if (HOSPITAL.equals(type)) return R.drawable.jr_pin_hospital;
        if (CLINIC.equals(type)) return R.drawable.jr_pin_clinic;
        if (PHARMACY.equals(type)) return R.drawable.jr_pin_pharmacy;
        return R.drawable.jr_pin_fuel;
    }

    @ColorRes
    private static int colorRes(String type) {
        if (POLICE.equals(type)) return R.color.ns_info;
        if (HOSPITAL.equals(type)) return R.color.ns_danger;
        if (CLINIC.equals(type)) return R.color.ns_rose;
        if (PHARMACY.equals(type)) return R.color.ns_safe;
        return R.color.ns_warn;
    }

    @ColorRes
    private static int containerRes(String type) {
        if (POLICE.equals(type)) return R.color.ns_info_container;
        if (HOSPITAL.equals(type)) return R.color.ns_danger_container;
        if (CLINIC.equals(type)) return R.color.ns_rose_container;
        if (PHARMACY.equals(type)) return R.color.ns_safe_container;
        return R.color.ns_warn_container;
    }
}
