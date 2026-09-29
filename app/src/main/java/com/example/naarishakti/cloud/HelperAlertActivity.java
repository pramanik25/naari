package com.example.naarishakti.cloud;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.app.KeyguardManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.example.naarishakti.R;
import com.google.android.gms.location.Priority;
import com.google.android.material.button.MaterialButton;
import com.google.gson.JsonObject;

import org.osmdroid.config.Configuration;
import org.osmdroid.config.IConfigurationProvider;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.CustomZoomButtonsController;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.TilesOverlay;

import java.io.File;
import java.util.Locale;

/**
 * Full-screen "someone nearby needs help" alert for opted-in helpers (server type
 * {@code helper_alert}). Shows over the lock screen; "I'm going" tells the server
 * ({@code POST /alerts/{id}/respond}) and opens live tracking plus turn-by-turn navigation.
 */
public class HelperAlertActivity extends AppCompatActivity {

    static final String EXTRA_INCIDENT_ID = "incidentId";
    static final String EXTRA_LAT = "lat";
    static final String EXTRA_LNG = "lng";
    static final String EXTRA_DISTANCE_M = "distanceM";
    static final String EXTRA_TRACK_URL = "trackUrl";
    static final String EXTRA_NOTIFICATION_ID = "notificationId";
    static final String EXTRA_RADIUS_KM = "radiusKm";
    static final String EXTRA_EVIDENCE_COUNT = "evidenceCount";
    static final String EXTRA_PHOTO_URL = "photoUrl";

    /** Photos are decoded to about this many pixels on the long side. */
    private static final int PHOTO_PX = 1080;

    /** Incident whose alert is on screen right now (null when none). */
    @Nullable private static volatile String showingIncidentId;

    /** True while the alert screen for {@code incidentId} is visible. */
    static boolean isShowing(@Nullable String incidentId) {
        String s = showingIncidentId;
        return s != null && s.equals(incidentId);
    }

    private String incidentId;
    private double lat = Double.NaN;
    private double lng = Double.NaN;
    private String trackUrl;
    private int notificationId;

    private MapView map;
    private TextView distanceText;
    private TextView statusText;
    private TextView titleText;
    private MaterialButton goingButton;
    private ObjectAnimator ring1Anim;
    private ObjectAnimator ring2Anim;
    private boolean sending;

    // v1.1 evidence
    private View photoCard;
    private ImageView photoView;
    private TextView evidenceCountText;
    private TextView radiusText;
    private MaterialButton viewLiveButton;
    @Nullable private String photoUrl;
    private int photoGeneration;

    private final BroadcastReceiver evidenceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String id = intent.getStringExtra(CloudNotifier.EXTRA_INCIDENT_ID);
            if (id == null || !id.equals(incidentId)) return;
            String url = intent.getStringExtra(CloudNotifier.EXTRA_PHOTO_URL);
            String track = intent.getStringExtra(CloudNotifier.EXTRA_TRACK_URL);
            int count = intent.getIntExtra(CloudNotifier.EXTRA_EVIDENCE_COUNT, -1);
            if (!TextUtils.isEmpty(track)) trackUrl = track;
            if (count >= 0) renderEvidenceCount(count);
            renderViewLive();
            if (!TextUtils.isEmpty(url)) loadPhoto(url);
        }
    };

    private final BroadcastReceiver endedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String id = intent.getStringExtra(CloudNotifier.EXTRA_INCIDENT_ID);
            if (id != null && id.equals(incidentId)) showEnded();
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        // Emergency screens are always dark, whatever theme the user picked.
        getDelegate().setLocalNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES);
        super.onCreate(savedInstanceState);
        showOverLockScreen();
        initOsmdroid();
        setContentView(R.layout.cl_activity_helper_alert);

        map = findViewById(R.id.clHelperMap);
        distanceText = findViewById(R.id.clHelperDistance);
        statusText = findViewById(R.id.clHelperStatus);
        titleText = findViewById(R.id.clHelperTitle);
        goingButton = findViewById(R.id.clHelperGoing);
        MaterialButton callButton = findViewById(R.id.clHelperCall);
        MaterialButton dismissButton = findViewById(R.id.clHelperDismiss);
        photoCard = findViewById(R.id.nbHelperPhotoCard);
        photoView = findViewById(R.id.nbHelperPhoto);
        evidenceCountText = findViewById(R.id.nbHelperEvidenceCount);
        radiusText = findViewById(R.id.nbHelperRadius);
        viewLiveButton = findViewById(R.id.nbHelperViewLive);
        viewLiveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                silenceAlert();
                openTrackingOnly();
            }
        });

        goingButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onGoing();
            }
        });
        callButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                silenceAlert();
                try {
                    startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:112")));
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(HelperAlertActivity.this, R.string.cl_no_app, Toast.LENGTH_SHORT).show();
                }
            }
        });
        dismissButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                silenceAlert();
                finish();
            }
        });

        styleMap();
        startPulse();
        bind(getIntent());
        LocalBroadcastManager.getInstance(this)
                .registerReceiver(endedReceiver, new IntentFilter(CloudNotifier.ACTION_ALERT_ENDED));
        LocalBroadcastManager.getInstance(this)
                .registerReceiver(evidenceReceiver, new IntentFilter(CloudNotifier.ACTION_EVIDENCE_UPDATE));
    }

    @Override
    protected void onStart() {
        super.onStart();
        showingIncidentId = incidentId;
    }

    @Override
    protected void onStop() {
        if (showingIncidentId != null && showingIncidentId.equals(incidentId)) showingIncidentId = null;
        super.onStop();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        bind(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (map != null) map.onResume();
    }

    @Override
    protected void onPause() {
        if (map != null) map.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(endedReceiver);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(evidenceReceiver);
        if (ring1Anim != null) ring1Anim.cancel();
        if (ring2Anim != null) ring2Anim.cancel();
        if (map != null) map.onDetach();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ setup

    private void showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void initOsmdroid() {
        try {
            Context app = getApplicationContext();
            IConfigurationProvider cfg = Configuration.getInstance();
            cfg.load(app, app.getSharedPreferences("osmdroid", Context.MODE_PRIVATE));
            cfg.setUserAgentValue(app.getPackageName());
            File base = new File(app.getCacheDir(), "osmdroid");
            cfg.setOsmdroidBasePath(base);
            cfg.setOsmdroidTileCache(new File(base, "tiles"));
        } catch (Throwable ignored) {
            // The map is decorative; the alert works without it.
        }
    }

    private void styleMap() {
        map.setTileSource(TileSourceFactory.MAPNIK);
        map.setMultiTouchControls(true);
        map.setTilesScaledToDpi(true);
        map.getZoomController().setVisibility(CustomZoomButtonsController.Visibility.NEVER);
        TilesOverlay tiles = map.getOverlayManager().getTilesOverlay();
        tiles.setLoadingBackgroundColor(ContextCompat.getColor(this, R.color.ns_surface));
        tiles.setLoadingLineColor(ContextCompat.getColor(this, R.color.ns_surface_high));
        if (isNightMode()) tiles.setColorFilter(TilesOverlay.INVERT_COLORS);
        map.getController().setZoom(15.0);
    }

    private boolean isNightMode() {
        int mode = getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        // The app is dark-first: treat "undefined" as dark.
        return mode != android.content.res.Configuration.UI_MODE_NIGHT_NO;
    }

    private void startPulse() {
        ring1Anim = pulse(findViewById(R.id.clHelperRing1), 0);
        ring2Anim = pulse(findViewById(R.id.clHelperRing2), 900);
    }

    private ObjectAnimator pulse(View ring, long delay) {
        ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(ring,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 0.7f, 1.25f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.7f, 1.25f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 0.9f, 0f));
        a.setDuration(1800);
        a.setStartDelay(delay);
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.setInterpolator(new AccelerateDecelerateInterpolator());
        a.start();
        return a;
    }

    private void bind(Intent intent) {
        incidentId = intent.getStringExtra(EXTRA_INCIDENT_ID);
        lat = intent.getDoubleExtra(EXTRA_LAT, Double.NaN);
        lng = intent.getDoubleExtra(EXTRA_LNG, Double.NaN);
        trackUrl = intent.getStringExtra(EXTRA_TRACK_URL);
        notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0);
        double distance = intent.getDoubleExtra(EXTRA_DISTANCE_M, -1);

        if (TextUtils.isEmpty(incidentId)) {
            finish();
            return;
        }
        titleText.setText(R.string.cl_helper_title);
        goingButton.setEnabled(true);
        statusText.setVisibility(View.GONE);
        distanceText.setText(distance >= 0
                ? getString(R.string.cl_distance_away, CloudNotifier.formatDistance(this, distance))
                : getString(R.string.cl_helper_distance_unknown));

        map.getOverlays().clear();
        if (hasPoint()) {
            GeoPoint p = new GeoPoint(lat, lng);
            Marker m = new Marker(map);
            m.setPosition(p);
            m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
            m.setIcon(ContextCompat.getDrawable(this, R.drawable.cl_map_pin));
            m.setTitle(getString(R.string.cl_helper_her_location));
            m.setInfoWindow(null);
            map.getOverlays().add(m);
            map.getController().setCenter(p);
            map.setVisibility(View.VISIBLE);
        } else {
            findViewById(R.id.clHelperMapCard).setVisibility(View.GONE);
        }
        map.invalidate();

        // v1.1: evidence and search radius.
        showingIncidentId = incidentId;
        renderEvidenceCount(intent.getIntExtra(EXTRA_EVIDENCE_COUNT, 0));
        int radius = intent.getIntExtra(EXTRA_RADIUS_KM, 0);
        if (radius > 0) {
            radiusText.setText(getString(R.string.nb_radius_km, radius));
            radiusText.setVisibility(View.VISIBLE);
        } else {
            radiusText.setVisibility(View.GONE);
        }
        renderViewLive();
        photoUrl = null;
        photoView.setImageDrawable(null);
        photoCard.setVisibility(View.GONE);
        loadPhoto(intent.getStringExtra(EXTRA_PHOTO_URL));
    }

    // ------------------------------------------------------------------ evidence

    private void renderEvidenceCount(int count) {
        if (count > 0) {
            evidenceCountText.setText(getResources().getQuantityString(R.plurals.nb_evidence_count, count, count));
            evidenceCountText.setVisibility(View.VISIBLE);
        } else {
            evidenceCountText.setVisibility(View.GONE);
        }
    }

    private void renderViewLive() {
        viewLiveButton.setVisibility(TextUtils.isEmpty(trackUrl) ? View.GONE : View.VISIBLE);
    }

    /** Downloads (downsampled) and shows the photo; a failed download keeps the previous one. */
    private void loadPhoto(@Nullable final String url) {
        if (TextUtils.isEmpty(url) || url.equals(photoUrl)) return;
        final int gen = ++photoGeneration;
        final Context app = getApplicationContext();
        Cloud.io().execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap bmp = CloudImages.load(app, url, PHOTO_PX);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != photoGeneration || isFinishing() || isDestroyed() || bmp == null) return;
                        photoUrl = url;
                        photoView.setImageBitmap(bmp);
                        photoCard.setVisibility(View.VISIBLE);
                    }
                });
            }
        });
    }

    /** "View live location &amp; evidence": the tracking page only, the alert stays open. */
    private void openTrackingOnly() {
        final String track = trackUrl;
        if (TextUtils.isEmpty(track)) return;
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && km != null && km.isKeyguardLocked()) {
            km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                @Override
                public void onDismissSucceeded() {
                    openUrl(track);
                }

                @Override
                public void onDismissError() {
                    openUrl(track);
                }
            });
        } else {
            openUrl(track);
        }
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.cl_no_app, Toast.LENGTH_SHORT).show();
        }
    }

    private boolean hasPoint() {
        return !Double.isNaN(lat) && !Double.isNaN(lng) && (lat != 0d || lng != 0d);
    }

    // ------------------------------------------------------------------ actions

    private void onGoing() {
        if (sending) return;
        sending = true;
        silenceAlert();
        goingButton.setEnabled(false);
        showStatus(getString(R.string.cl_helper_sending));
        final Context app = getApplicationContext();
        final String id = incidentId;
        Cloud.io().execute(new Runnable() {
            @Override
            public void run() {
                String track = trackUrl;
                int message;
                Location me = CloudHelper.currentLocation(app, Priority.PRIORITY_HIGH_ACCURACY, 10_000L);
                if (me == null) {
                    message = R.string.cl_helper_no_location;
                } else {
                    try {
                        JsonObject body = new JsonObject();
                        body.addProperty("lat", me.getLatitude());
                        body.addProperty("lng", me.getLongitude());
                        JsonObject res = ApiClient.get(app).call("POST",
                                "/api/v1/alerts/" + Uri.encode(id) + "/respond", body);
                        String t = Json.str(res, "trackUrl");
                        if (t != null) track = t;
                        message = R.string.cl_helper_sent;
                    } catch (CloudException e) {
                        message = R.string.cl_helper_send_failed;
                    }
                }
                final String finalTrack = track;
                final int finalMessage = message;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        sending = false;
                        if (isFinishing() || isDestroyed()) return;
                        Toast.makeText(HelperAlertActivity.this, finalMessage, Toast.LENGTH_LONG).show();
                        launchAfterUnlock(finalTrack);
                    }
                });
            }
        });
    }

    /** Opens tracking and navigation, asking the keyguard to step aside first. */
    private void launchAfterUnlock(final String track) {
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && km != null && km.isKeyguardLocked()) {
            km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                @Override
                public void onDismissSucceeded() {
                    openTrackingAndNavigation(track);
                }

                @Override
                public void onDismissCancelled() {
                    goingButton.setEnabled(true);
                }

                @Override
                public void onDismissError() {
                    openTrackingAndNavigation(track);
                }
            });
        } else {
            openTrackingAndNavigation(track);
        }
    }

    private void openTrackingAndNavigation(@Nullable String track) {
        if (!TextUtils.isEmpty(track)) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(track)));
            } catch (ActivityNotFoundException ignored) {
            }
        }
        if (hasPoint()) {
            String ll = String.format(Locale.US, "%.6f,%.6f", lat, lng);
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + ll)));
            } catch (ActivityNotFoundException e) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:" + ll + "?q=" + ll)));
                } catch (ActivityNotFoundException e2) {
                    Toast.makeText(this, R.string.cl_no_app, Toast.LENGTH_SHORT).show();
                }
            }
        }
        finish();
    }

    private void showEnded() {
        silenceAlert();
        titleText.setText(R.string.cl_helper_ended_title);
        showStatus(getString(R.string.cl_helper_ended));
        goingButton.setEnabled(false);
        if (ring1Anim != null) ring1Anim.cancel();
        if (ring2Anim != null) ring2Anim.cancel();
    }

    private void showStatus(String text) {
        statusText.setText(text);
        statusText.setVisibility(View.VISIBLE);
    }

    /** Stop the insistent alarm sound of the notification. */
    private void silenceAlert() {
        if (notificationId != 0) CloudNotifier.cancel(this, notificationId);
    }
}
