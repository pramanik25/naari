package com.example.naarishakti.together;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;

import com.example.naarishakti.R;
import com.example.naarishakti.cloud.CloudSocial;
import com.example.naarishakti.databinding.TgActivitySafetyMapBinding;
import com.example.naarishakti.databinding.TgItemReportBinding;
import com.example.naarishakti.databinding.TgSheetReportBinding;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import org.osmdroid.api.IGeoPoint;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.overlay.Marker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import Home_Activity.FeatureKit;

/**
 * Community safety map: places other users marked as poorly lit, isolated, unsafe (or safe), and
 * a way to add one for the spot under the pin. Reports are anonymous; a wrong one can be reported
 * and disappears once a few people did.
 */
public class SafetyMapActivity extends AppCompatActivity {

    private static final String TAG = "SafetyMapActivity";
    private static final int RADIUS_M = 3000;
    private static final int MAX_LIST = 20;

    /** One call to the API; lets the callers below share the thread hop and error handling. */
    private interface Call {
        void run() throws Exception;
    }

    private TgActivitySafetyMapBinding b;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Marker> markers = new ArrayList<>();
    private ActivityResultLauncher<String[]> permLauncher;
    @Nullable private Marker meMarker;
    @Nullable private GeoPoint here;
    /** Bumped for every load so a slow older answer cannot overwrite a newer one. */
    private int loadSeq;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FeatureKit.initOsmdroid(this);
        b = TgActivitySafetyMapBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        permLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(), this::onPermissions);

        FeatureKit.styleMap(b.mapView);
        b.mapView.getController().setZoom(5.0);
        b.mapView.getController().setCenter(new GeoPoint(Tg.DEFAULT_LAT, Tg.DEFAULT_LNG));

        b.backButton.setOnClickListener(v -> finish());
        b.searchHereButton.setOnClickListener(v -> load());
        b.recenterButton.setOnClickListener(v -> {
            if (here != null) b.mapView.getController().animateTo(here, 15.0, 400L);
            else locate();
        });
        b.reportButton.setOnClickListener(v -> showReportSheet());

        boolean cloud = CloudSocial.isActive(this);
        Tg.show(b.cloudOff, !cloud);
        Tg.show(b.content, cloud);
        if (cloud) locate();
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
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
        b.mapView.onDetach();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ location

    private void locate() {
        if (!FeatureKit.hasLocationPermission(this)) {
            permLauncher.launch(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION});
            return;
        }
        Tg.show(b.progress, true);
        FeatureKit.fetchLocation(this, loc -> {
            if (isFinishing() || isDestroyed()) return;
            Tg.show(b.progress, false);
            if (loc == null) {
                Snackbar.make(b.getRoot(), R.string.tg_sm_no_location, Snackbar.LENGTH_LONG).show();
                return;
            }
            here = new GeoPoint(loc.getLatitude(), loc.getLongitude());
            if (meMarker == null) {
                meMarker = new Marker(b.mapView);
                meMarker.setIcon(ContextCompat.getDrawable(this, R.drawable.ub_map_dot));
                meMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
                meMarker.setInfoWindow(null);
                meMarker.setOnMarkerClickListener((m, map) -> true);
                b.mapView.getOverlays().add(meMarker);
            }
            meMarker.setPosition(here);
            b.mapView.getController().setZoom(15.0);
            b.mapView.getController().setCenter(here);
            load();
        });
    }

    private void onPermissions(Map<String, Boolean> result) {
        if (FeatureKit.hasLocationPermission(this)) {
            locate();
        } else {
            // The map still works without it: she can pan to a place and search there.
            Snackbar.make(b.getRoot(), R.string.tg_sm_pan_hint, Snackbar.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ data

    /** Loads the reports around the middle of the map. */
    private void load() {
        final IGeoPoint center = b.mapView.getMapCenter();
        final double lat = center.getLatitude();
        final double lng = center.getLongitude();
        final int seq = ++loadSeq;
        Tg.show(b.progress, true);
        io.execute(() -> {
            List<CloudSocial.PlaceReport> reports = null;
            Throwable error = null;
            try {
                reports = CloudSocial.placeReports(this, lat, lng, RADIUS_M);
            } catch (Throwable t) {
                Log.w(TAG, "Loading reports failed", t);
                error = t;
            }
            final List<CloudSocial.PlaceReport> result = reports;
            final Throwable failure = error;
            main.post(() -> {
                if (isFinishing() || isDestroyed() || seq != loadSeq) return;
                Tg.show(b.progress, false);
                if (result == null) {
                    Snackbar.make(b.getRoot(), Tg.errorText(failure), Snackbar.LENGTH_LONG).show();
                } else {
                    render(result);
                }
            });
        });
    }

    private void render(List<CloudSocial.PlaceReport> reports) {
        for (Marker m : markers) b.mapView.getOverlays().remove(m);
        markers.clear();
        b.reportList.removeAllViews();
        Tg.show(b.emptyText, reports.isEmpty());

        LayoutInflater inflater = LayoutInflater.from(this);
        int count = 0;
        for (final CloudSocial.PlaceReport r : reports) {
            final GeoPoint point = new GeoPoint(r.lat, r.lng);
            Marker m = new Marker(b.mapView);
            m.setPosition(point);
            m.setIcon(Tg.dot(this, Tg.categoryColor(r.category), 18));
            m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
            m.setInfoWindow(null);
            m.setOnMarkerClickListener((marker, map) -> {
                showReport(r);
                return true;
            });
            markers.add(m);
            b.mapView.getOverlays().add(m);

            if (count++ >= MAX_LIST) continue;
            TgItemReportBinding row = TgItemReportBinding.inflate(inflater, b.reportList, false);
            ViewCompat.setBackgroundTintList(row.dot,
                    ColorStateList.valueOf(ContextCompat.getColor(this, Tg.categoryColor(r.category))));
            row.category.setText(Tg.categoryLabel(r.category));
            row.note.setText(r.note);
            Tg.show(row.note, !TextUtils.isEmpty(r.note));
            row.when.setText(Tg.ago(r.createdAt));
            row.getRoot().setOnClickListener(v -> {
                b.mapView.getController().animateTo(point, 17.0, 400L);
                showReport(r);
            });
            b.reportList.addView(row.getRoot());
        }
        b.mapView.invalidate();
    }

    private void showReport(final CloudSocial.PlaceReport r) {
        String message = TextUtils.isEmpty(r.note)
                ? getString(R.string.tg_sm_detail, Tg.ago(r.createdAt))
                : r.note + "\n\n" + getString(R.string.tg_sm_detail, Tg.ago(r.createdAt));
        MaterialAlertDialogBuilder dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(Tg.categoryLabel(r.category))
                .setMessage(message)
                .setPositiveButton(R.string.tg_close, null);
        if (r.mine) {
            dialog.setNeutralButton(R.string.tg_delete, (d, w) ->
                    act(() -> CloudSocial.deletePlaceReport(this, r.id), R.string.tg_sm_deleted));
        } else {
            dialog.setNeutralButton(R.string.tg_sm_flag, (d, w) ->
                    act(() -> CloudSocial.flagPlaceReport(this, r.id), R.string.tg_flagged));
        }
        dialog.show();
    }

    /** Runs one API call, says how it went and reloads the map. */
    private void act(final Call call, final int doneText) {
        io.execute(() -> {
            Throwable error = null;
            try {
                call.run();
            } catch (Throwable t) {
                Log.w(TAG, "Safety map action failed", t);
                error = t;
            }
            final Throwable failure = error;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                Snackbar.make(b.getRoot(), failure == null ? doneText : Tg.errorText(failure),
                        Snackbar.LENGTH_LONG).show();
                if (failure == null) load();
            });
        });
    }

    // ------------------------------------------------------------------ new report

    private void showReportSheet() {
        if (b.mapView.getZoomLevelDouble() < 14.0) {
            // From far out the pin covers a whole neighbourhood.
            Snackbar.make(b.getRoot(), R.string.tg_sm_zoom_in, Snackbar.LENGTH_LONG).show();
            return;
        }
        final IGeoPoint center = b.mapView.getMapCenter();
        final double lat = center.getLatitude();
        final double lng = center.getLongitude();
        final BottomSheetDialog sheet = new BottomSheetDialog(this);
        final TgSheetReportBinding s = TgSheetReportBinding.inflate(LayoutInflater.from(this));
        sheet.setContentView(s.getRoot());

        final List<Chip> chips = new ArrayList<>();
        for (String category : Tg.CATEGORIES) {
            Chip chip = (Chip) getLayoutInflater().inflate(R.layout.dl_item_chip, s.categoryChips, false);
            chip.setId(View.generateViewId());
            chip.setText(Tg.categoryLabel(category));
            s.categoryChips.addView(chip);
            chips.add(chip);
        }
        s.submitButton.setOnClickListener(v -> {
            String category = null;
            for (int i = 0; i < chips.size(); i++) if (chips.get(i).isChecked()) category = Tg.CATEGORIES[i];
            if (category == null) {
                Snackbar.make(s.getRoot(), R.string.tg_sm_pick_category, Snackbar.LENGTH_SHORT).show();
                return;
            }
            final String chosen = category;
            final String note = s.noteInput.getText() == null ? "" : s.noteInput.getText().toString().trim();
            sheet.dismiss();
            act(() -> CloudSocial.addPlaceReport(this, chosen, lat, lng, note), R.string.tg_sm_added);
        });
        sheet.setOnShowListener(d -> {
            View container = sheet.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (container != null) {
                ViewCompat.setBackgroundTintList(container,
                        ColorStateList.valueOf(ContextCompat.getColor(this, R.color.ns_surface_high)));
            }
        });
        sheet.show();
    }
}
