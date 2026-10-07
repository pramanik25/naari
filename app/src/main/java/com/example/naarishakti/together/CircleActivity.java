package com.example.naarishakti.together;

import android.content.Intent;
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
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;

import com.example.naarishakti.MainActivity;
import com.example.naarishakti.R;
import com.example.naarishakti.cloud.CircleShare;
import com.example.naarishakti.cloud.CloudHelper;
import com.example.naarishakti.cloud.CloudSettingsActivity;
import com.example.naarishakti.cloud.CloudSocial;
import com.example.naarishakti.databinding.TgActivityCircleBinding;
import com.example.naarishakti.databinding.TgItemMemberBinding;
import com.google.android.material.snackbar.Snackbar;

import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.overlay.Marker;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import Home_Activity.FeatureKit;

/**
 * Family circle: the people she is linked to as guardian or ward, on a map and in a list, with
 * when each was last seen and their battery level. Everyone decides for herself whether to share:
 * the switch at the top is off until she turns it on.
 */
public class CircleActivity extends AppCompatActivity {

    private static final String TAG = "CircleActivity";
    private static final long REFRESH_MS = 30_000L;

    private TgActivityCircleBinding b;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Marker> markers = new ArrayList<>();
    private ActivityResultLauncher<String[]> permLauncher;
    private boolean suppress;
    /** The map is framed around everyone only once, so a refresh never fights her panning. */
    private boolean framed;

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            load();
            main.postDelayed(this, REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FeatureKit.initOsmdroid(this);
        b = TgActivityCircleBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        permLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(), this::onPermissions);

        FeatureKit.styleMap(b.mapView);
        b.mapView.getController().setZoom(5.0);
        b.mapView.getController().setCenter(new GeoPoint(Tg.DEFAULT_LAT, Tg.DEFAULT_LNG));

        b.backButton.setOnClickListener(v -> finish());
        b.linkButton.setOnClickListener(v -> Tg.open(this, new Intent(this, CloudSettingsActivity.class)));
        b.shareRow.setOnClickListener(v -> b.shareSwitch.toggle());
        b.shareSwitch.setOnCheckedChangeListener((btn, checked) -> {
            if (suppress) return;
            if (checked && !FeatureKit.hasLocationPermission(this)) {
                setSwitch(false);
                permLauncher.launch(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION});
                return;
            }
            CircleShare.setSharing(this, checked);
            renderShare();
        });

        boolean cloud = CloudSocial.isActive(this);
        Tg.show(b.cloudOff, !cloud);
        Tg.show(b.content, cloud);
        renderShare();
    }

    @Override
    protected void onResume() {
        super.onResume();
        b.mapView.onResume();
        if (CloudSocial.isActive(this)) {
            main.removeCallbacks(refresher);
            main.post(refresher);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        b.mapView.onPause();
        main.removeCallbacks(refresher);
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
        b.mapView.onDetach();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ sharing

    private void onPermissions(Map<String, Boolean> result) {
        if (FeatureKit.hasLocationPermission(this)) {
            setSwitch(true);
            CircleShare.setSharing(this, true);
            renderShare();
        } else {
            Snackbar.make(b.getRoot(), R.string.tg_ci_need_location, Snackbar.LENGTH_LONG)
                    .setAction(R.string.tg_open_settings, v -> FeatureKit.openAppSettings(this))
                    .show();
        }
    }

    private void setSwitch(boolean checked) {
        suppress = true;
        b.shareSwitch.setChecked(checked);
        suppress = false;
    }

    private void renderShare() {
        boolean on = CircleShare.isSharing(this);
        setSwitch(on);
        @StringRes int status = !on ? R.string.tg_ci_share_off
                : FeatureKit.hasBackgroundLocation(this) ? R.string.tg_ci_share_on
                : R.string.tg_ci_share_on_foreground;
        b.shareStatus.setText(status);
    }

    // ------------------------------------------------------------------ data

    private void load() {
        io.execute(() -> {
            CloudSocial.Circle circle = null;
            Throwable error = null;
            int[] stats = null;
            try {
                circle = CloudSocial.circle(this);
            } catch (Throwable t) {
                Log.w(TAG, "Circle load failed", t);
                error = t;
            }
            if (CloudHelper.isOptedIn(this)) {
                try {
                    stats = CloudSocial.helperStats(this);
                } catch (Throwable t) {
                    Log.w(TAG, "Helper stats failed", t);
                }
            }
            final CloudSocial.Circle result = circle;
            final Throwable failure = error;
            final int[] helper = stats;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                Tg.show(b.progress, false);
                if (result != null) {
                    render(result);
                } else if (b.members.getChildCount() == 0) {
                    // Only interrupt her when there is nothing on screen yet.
                    Snackbar.make(b.getRoot(), Tg.errorText(failure), Snackbar.LENGTH_LONG).show();
                }
                renderHelper(helper);
            });
        });
    }

    private void renderHelper(@Nullable int[] stats) {
        boolean show = stats != null && stats[0] > 0;
        Tg.show(b.helperCard, show);
        if (show) {
            b.helperText.setText(getResources().getQuantityString(R.plurals.tg_ci_helper_stats,
                    stats[0], stats[1], stats[0]));
        }
    }

    private void render(CloudSocial.Circle circle) {
        for (Marker m : markers) b.mapView.getOverlays().remove(m);
        markers.clear();
        b.members.removeAllViews();
        Tg.show(b.emptyState, circle.members.isEmpty());
        Tg.show(b.mapCard, !circle.members.isEmpty());

        List<GeoPoint> points = new ArrayList<>();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (final CloudSocial.Member member : circle.members) {
            final String name = TextUtils.isEmpty(member.name) ? getString(R.string.tg_ci_unnamed) : member.name;
            final GeoPoint point = member.hasLocation ? new GeoPoint(member.lat, member.lng) : null;
            if (point != null) {
                points.add(point);
                Marker m = new Marker(b.mapView);
                m.setPosition(point);
                m.setIcon(Tg.dot(this, R.color.ns_rose, 20));
                m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
                m.setInfoWindow(null);
                m.setOnMarkerClickListener((marker, map) -> {
                    Snackbar.make(b.getRoot(), getString(R.string.tg_ci_marker, name, Tg.ago(member.updatedAt)),
                            Snackbar.LENGTH_LONG).show();
                    return true;
                });
                markers.add(m);
                b.mapView.getOverlays().add(m);
            }

            TgItemMemberBinding row = TgItemMemberBinding.inflate(inflater, b.members, false);
            MainActivity.tintBadge(row.icon, point != null ? R.color.ns_rose : R.color.ns_text_faint,
                    point != null ? R.color.ns_rose_container : R.color.ns_surface_highest);
            row.name.setText(name);
            row.relation.setText("guardian".equals(member.relation) ? R.string.tg_ci_rel_guardian
                    : "ward".equals(member.relation) ? R.string.tg_ci_rel_ward : R.string.tg_ci_rel_both);
            if (point == null) {
                row.status.setText(R.string.tg_ci_not_sharing);
            } else if (member.battery >= 0) {
                row.status.setText(getString(R.string.tg_ci_seen_battery, Tg.ago(member.updatedAt), member.battery));
            } else {
                row.status.setText(getString(R.string.tg_ci_seen, Tg.ago(member.updatedAt)));
            }
            Tg.show(row.navigateButton, point != null);
            if (point != null) {
                row.getRoot().setOnClickListener(v -> b.mapView.getController().animateTo(point, 16.0, 400L));
                row.navigateButton.setOnClickListener(v -> Tg.open(this, new Intent(Intent.ACTION_VIEW,
                        Uri.parse(String.format(Locale.US, "geo:%.6f,%.6f?q=%.6f,%.6f(%s)",
                                member.lat, member.lng, member.lat, member.lng, Uri.encode(name))))));
            }
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) row.getRoot().getLayoutParams();
            if (b.members.getChildCount() > 0) lp.topMargin = Tg.dp(this, 12);
            b.members.addView(row.getRoot(), lp);
        }

        if (!framed && !points.isEmpty()) {
            framed = true;
            if (points.size() == 1) {
                b.mapView.getController().setZoom(15.0);
                b.mapView.getController().setCenter(points.get(0));
            } else {
                final BoundingBox box = BoundingBox.fromGeoPointsSafe(points);
                // zoomToBoundingBox needs the map's size, which exists only after layout.
                b.mapView.post(() -> b.mapView.zoomToBoundingBox(box, false, Tg.dp(this, 48)));
            }
        }
        b.mapView.invalidate();
    }
}
