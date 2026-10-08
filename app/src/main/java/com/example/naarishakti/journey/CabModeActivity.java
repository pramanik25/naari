package com.example.naarishakti.journey;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.media.Image;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.example.naarishakti.R;
import com.example.naarishakti.VoiceRecognitionService;
import com.example.naarishakti.core.Prefs;
import com.example.naarishakti.core.ProtectionController;
import com.example.naarishakti.databinding.JrActivityCabModeBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Marker;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import Home_Activity.FeatureKit;

/**
 * Cab mode: scan the number plate (CameraX + on-device ML Kit OCR), add the driver, the cab app
 * and a destination, then SMS the contacts and let {@link JourneyService} watch the route.
 */
public class CabModeActivity extends AppCompatActivity {

    private static final String TAG = "CabModeActivity";

    /** Launched by the full-screen "Is everything OK?" notification. */
    static final String ACTION_PROMPT = "com.example.naarishakti.journey.CAB_PROMPT";

    private static final double DEFAULT_LAT = 20.5937;
    private static final double DEFAULT_LNG = 78.9629;
    private static final int PENDING_NONE = 0;
    private static final int PENDING_START = 1;

    private JrActivityCabModeBinding b;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private ActivityResultLauncher<String[]> permLauncher;
    private int pending = PENDING_NONE;

    @Nullable private ProcessCameraProvider cameraProvider;
    @Nullable private TextRecognizer recognizer;
    private volatile boolean analysing;
    private volatile boolean cameraOn;
    private boolean cameraStarting;
    @Nullable private String lastSeen;
    private boolean userEditedPlate;
    private boolean settingPlate;

    @Nullable private GeoPoint destination;
    @Nullable private Marker destMarker;
    @Nullable private Marker meMarker;
    private boolean destNameTyped;
    private boolean settingDest;

    @Nullable private AlertDialog promptDialog;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            renderActive();
            handler.postDelayed(this, 1000L);
        }
    };

    private final BroadcastReceiver changeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            render();
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FeatureKit.initOsmdroid(this); // must precede MapView inflation
        b = JrActivityCabModeBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        permLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(), this::onPermissions);

        b.backButton.setOnClickListener(v -> finish());
        b.grantCameraButton.setOnClickListener(v -> {
            if (!JourneyUtil.granted(this, Manifest.permission.CAMERA)
                    && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
                    && askedCamera) {
                JourneyUtil.openAppSettings(this);
            } else {
                requestSetupPermissions();
            }
        });
        b.plateLayout.setEndIconOnClickListener(v -> {
            userEditedPlate = false;
            lastSeen = null;
            setPlate("");
            b.scanStatus.setText(R.string.jr_cab_scanning);
        });
        b.plateInput.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                if (!settingPlate) userEditedPlate = s.length() > 0;
                b.plateLayout.setError(null);
            }
        });
        b.destInput.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                if (!settingDest) destNameTyped = s.length() > 0;
            }
        });
        b.startButton.setOnClickListener(v -> onStartTrip());
        b.endButton.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.jr_cab_end_title)
                .setMessage(R.string.jr_cab_end_body)
                .setPositiveButton(R.string.jr_cab_end, (d, w) -> {
                    JourneyService.endTrip(this);
                    render();
                })
                .setNegativeButton(R.string.jr_cancel, null)
                .show());
        b.arrivedButton.setOnClickListener(v -> {
            JourneyService.arrivedSafely(this);
            Snackbar.make(b.getRoot(), R.string.jr_cab_arrived_sent, Snackbar.LENGTH_LONG).show();
            render();
        });
        b.sosButton.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            ProtectionController.triggerPanic(this, "cab_sos");
        });

        setupMap();

        if (!CabTrip.isActive(this)) requestSetupPermissions();
        handlePrompt(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handlePrompt(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        LocalBroadcastManager.getInstance(this)
                .registerReceiver(changeReceiver, new IntentFilter(JourneyUtil.ACTION_CHANGED));
    }

    @Override
    protected void onResume() {
        super.onResume();
        b.mapView.onResume();
        if (CabTrip.isActive(this)) JourneyService.sync(this, true);
        render();
    }

    @Override
    protected void onPause() {
        super.onPause();
        b.mapView.onPause();
        handler.removeCallbacks(ticker);
    }

    @Override
    protected void onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(changeReceiver);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        stopCamera();
        if (recognizer != null) recognizer.close();
        analysisExecutor.shutdown();
        io.shutdownNow();
        b.mapView.onDetach();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ permissions

    private boolean askedCamera;

    private void requestSetupPermissions() {
        String[] missing = JourneyUtil.missing(this,
                JourneyUtil.concat(new String[]{Manifest.permission.CAMERA}, JourneyUtil.locationPerms()));
        if (missing.length == 0) {
            startCamera();
            centerOnMe();
        } else {
            pending = PENDING_NONE;
            askedCamera = true;
            permLauncher.launch(missing);
        }
    }

    private void onPermissions(Map<String, Boolean> result) {
        int action = pending;
        pending = PENDING_NONE;
        if (action == PENDING_START) {
            startTripNow();
            return;
        }
        if (JourneyUtil.granted(this, Manifest.permission.CAMERA)) startCamera();
        render();
        if (JourneyUtil.hasLocation(this)) centerOnMe();
    }

    // ------------------------------------------------------------------ camera + OCR

    private void startCamera() {
        if (cameraOn || cameraStarting || CabTrip.isActive(this)) return;
        if (!JourneyUtil.granted(this, Manifest.permission.CAMERA)) return;
        cameraStarting = true;
        final ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            cameraStarting = false;
            try {
                ProcessCameraProvider provider = future.get();
                if (isFinishing() || isDestroyed() || CabTrip.isActive(this)) return;
                cameraProvider = provider;
                if (recognizer == null) {
                    recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
                }
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(b.previewView.getSurfaceProvider());
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(analysisExecutor, this::analyse);
                provider.unbindAll();
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
                cameraOn = true;
                render();
            } catch (Exception e) {
                Log.w(TAG, "Camera unavailable", e);
                b.scanStatus.setText(R.string.jr_cab_camera_error);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void stopCamera() {
        if (cameraProvider != null) {
            try {
                cameraProvider.unbindAll();
            } catch (Exception ignored) {
                // Already released.
            }
        }
        cameraOn = false;
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyse(@NonNull ImageProxy proxy) {
        Image media = proxy.getImage();
        TextRecognizer r = recognizer;
        if (media == null || r == null || analysing) {
            proxy.close();
            return;
        }
        analysing = true;
        InputImage input = InputImage.fromMediaImage(media, proxy.getImageInfo().getRotationDegrees());
        r.process(input)
                .addOnSuccessListener(text -> onText(PlateParser.find(text.getText())))
                .addOnCompleteListener(t -> {
                    analysing = false;
                    proxy.close();
                });
    }

    /** Runs on the main thread (ML Kit's default listener executor). */
    private void onText(@Nullable String plate) {
        if (plate == null || b == null || CabTrip.isActive(this)) return;
        // Require the same reading twice in a row before trusting it.
        if (!plate.equals(lastSeen)) {
            lastSeen = plate;
            return;
        }
        b.scanStatus.setText(getString(R.string.jr_cab_detected, plate));
        ViewCompat.setBackgroundTintList(b.scanDot, ColorStateList.valueOf(color(R.color.ns_safe)));
        if (!userEditedPlate) {
            String current = b.plateInput.getText() == null ? "" : b.plateInput.getText().toString();
            if (!plate.equals(current)) {
                setPlate(plate);
                b.plateInput.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
            }
        }
    }

    private void setPlate(String plate) {
        settingPlate = true;
        b.plateInput.setText(plate);
        b.plateInput.setSelection(plate.length());
        settingPlate = false;
    }

    // ------------------------------------------------------------------ map

    private void setupMap() {
        FeatureKit.styleMap(b.mapView);
        b.mapView.getController().setZoom(5.0);
        b.mapView.getController().setCenter(new GeoPoint(DEFAULT_LAT, DEFAULT_LNG));
        MapEventsOverlay events = new MapEventsOverlay(new MapEventsReceiver() {
            @Override
            public boolean singleTapConfirmedHelper(GeoPoint p) {
                setDestination(p);
                return true;
            }

            @Override
            public boolean longPressHelper(GeoPoint p) {
                setDestination(p);
                return true;
            }
        });
        b.mapView.getOverlays().add(0, events);
    }

    private void centerOnMe() {
        FeatureKit.fetchLocation(this, loc -> {
            if (loc == null || isFinishing() || isDestroyed()) return;
            GeoPoint p = new GeoPoint(loc.getLatitude(), loc.getLongitude());
            if (meMarker == null) {
                meMarker = new Marker(b.mapView);
                meMarker.setIcon(ContextCompat.getDrawable(this, R.drawable.ub_map_dot));
                meMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
                meMarker.setInfoWindow(null);
                b.mapView.getOverlays().add(meMarker);
            }
            meMarker.setPosition(p);
            if (destination == null) {
                b.mapView.getController().setZoom(14.0);
                b.mapView.getController().setCenter(p);
            }
            b.mapView.invalidate();
        });
    }

    private void setDestination(GeoPoint p) {
        destination = p;
        if (destMarker == null) {
            destMarker = new Marker(b.mapView);
            destMarker.setIcon(ContextCompat.getDrawable(this, R.drawable.jr_pin_dest));
            destMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            destMarker.setInfoWindow(null);
            b.mapView.getOverlays().add(destMarker);
        }
        destMarker.setPosition(p);
        b.mapView.invalidate();
        b.mapView.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
        if (!destNameTyped) {
            setDestText(FeatureKit.formatCoords(p.getLatitude(), p.getLongitude()));
            reverseGeocode(p);
        }
    }

    private void setDestText(String text) {
        settingDest = true;
        b.destInput.setText(text);
        settingDest = false;
    }

    private void reverseGeocode(final GeoPoint p) {
        if (!Geocoder.isPresent() || io.isShutdown()) return;
        final Context app = getApplicationContext();
        io.execute(() -> {
            final String line = geocodeLine(app, p.getLatitude(), p.getLongitude());
            handler.post(() -> {
                if (line == null || isFinishing() || destNameTyped || destination != p) return;
                setDestText(line);
            });
        });
    }

    @Nullable
    @SuppressWarnings("deprecation")
    static String geocodeLine(Context ctx, double lat, double lng) {
        try {
            List<Address> list = new Geocoder(ctx, Locale.getDefault()).getFromLocation(lat, lng, 1);
            if (list == null || list.isEmpty()) return null;
            Address a = list.get(0);
            List<String> parts = new ArrayList<>();
            if (!TextUtils.isEmpty(a.getFeatureName()) && !a.getFeatureName().matches("[0-9\\-]+")) {
                parts.add(a.getFeatureName());
            }
            if (!TextUtils.isEmpty(a.getSubLocality())) parts.add(a.getSubLocality());
            if (!TextUtils.isEmpty(a.getLocality())) parts.add(a.getLocality());
            if (parts.isEmpty() && a.getMaxAddressLineIndex() >= 0) return a.getAddressLine(0);
            return parts.isEmpty() ? null : TextUtils.join(", ", parts);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ start trip

    private void onStartTrip() {
        String typed = b.plateInput.getText() == null ? "" : b.plateInput.getText().toString();
        if (typed.trim().length() < 4) {
            b.plateLayout.setError(getString(R.string.jr_cab_plate_needed));
            b.plateInput.requestFocus();
            return;
        }
        if (Prefs.getContacts(this).isEmpty()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.jr_no_contacts_title)
                    .setMessage(R.string.jr_no_contacts_body)
                    .setPositiveButton(R.string.jr_add_contacts, (d, w) ->
                            startActivity(new Intent(this, Home_Activity.SettingsEmergencyActivity.class)))
                    .setNegativeButton(R.string.jr_cancel, null)
                    .show();
            return;
        }
        String[] missing = JourneyUtil.missing(this, JourneyUtil.concat(
                JourneyUtil.concat(new String[]{Manifest.permission.SEND_SMS}, JourneyUtil.locationPerms()),
                JourneyUtil.notificationPerms()));
        if (missing.length == 0) {
            startTripNow();
        } else {
            pending = PENDING_START;
            permLauncher.launch(missing);
        }
    }

    private void startTripNow() {
        final String plate = PlateParser.normalise(
                b.plateInput.getText() == null ? "" : b.plateInput.getText().toString());
        final String driver = b.driverInput.getText() == null ? "" : b.driverInput.getText().toString().trim();
        final String appName = selectedApp();
        final String destName = b.destInput.getText() == null ? "" : b.destInput.getText().toString().trim();
        final double[] dest = destination == null ? null
                : new double[]{destination.getLatitude(), destination.getLongitude()};

        CabTrip.start(this, plate, appName, driver, dest, destName);
        stopCamera();
        JourneyService.sync(this, true);
        b.startButton.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        render();

        if (!JourneyUtil.granted(this, Manifest.permission.SEND_SMS)) {
            Snackbar.make(b.getRoot(), R.string.jr_sms_denied, Snackbar.LENGTH_LONG).show();
        }
        if (!JourneyUtil.hasLocation(this)) {
            Snackbar.make(b.getRoot(), R.string.jr_cab_no_location, Snackbar.LENGTH_LONG)
                    .setAction(R.string.jr_open_settings, v -> JourneyUtil.openAppSettings(this))
                    .show();
        }

        final Context app = getApplicationContext();
        final List<String> numbers = Prefs.getContactNumbers(app);
        FeatureKit.fetchLocation(this, loc -> JourneyUtil.io(() -> {
            String text = buildStartSms(app, JourneyUtil.userName(app), plate, appName, driver,
                    destName, loc);
            int sent = VoiceRecognitionService.sendSmsToAll(app, numbers, text);
            handler.post(() -> {
                if (!isFinishing() && sent > 0) {
                    Snackbar.make(b.getRoot(), getString(R.string.jr_cab_sms_sent, sent),
                            Snackbar.LENGTH_LONG).show();
                }
            });
        }));
    }

    /** "Naari Kavach: <name> is in cab <PLATE> (<app>, driver <x>). Going to <dest>. Live: <link>" */
    static String buildStartSms(Context ctx, String name, String plate, String app, String driver,
                                String dest, @Nullable Location loc) {
        String details;
        if (!TextUtils.isEmpty(app) && !TextUtils.isEmpty(driver)) {
            details = ctx.getString(R.string.jr_sms_cab_details_app_driver, app, driver);
        } else if (!TextUtils.isEmpty(app)) {
            details = ctx.getString(R.string.jr_sms_cab_details_app, app);
        } else if (!TextUtils.isEmpty(driver)) {
            details = ctx.getString(R.string.jr_sms_cab_details_driver, driver);
        } else {
            details = "";
        }
        StringBuilder sb = new StringBuilder(ctx.getString(R.string.jr_sms_cab_start, name, plate, details));
        if (!TextUtils.isEmpty(dest)) sb.append(' ').append(ctx.getString(R.string.jr_sms_cab_going, dest));
        if (loc != null) {
            sb.append(' ').append(ctx.getString(R.string.jr_sms_live, VoiceRecognitionService.mapsLink(loc)));
        }
        return sb.toString();
    }

    private String selectedApp() {
        int id = b.appChips.getCheckedChipId();
        if (id == R.id.chipUber) return getString(R.string.jr_cab_app_uber);
        if (id == R.id.chipOla) return getString(R.string.jr_cab_app_ola);
        if (id == R.id.chipRapido) return getString(R.string.jr_cab_app_rapido);
        if (id == R.id.chipAuto) return getString(R.string.jr_cab_app_auto);
        if (id == R.id.chipOther) return getString(R.string.jr_cab_app_other);
        return "";
    }

    // ------------------------------------------------------------------ "Is everything OK?"

    private void handlePrompt(@Nullable Intent intent) {
        if (intent == null || !ACTION_PROMPT.equals(intent.getAction())) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        showPromptIfPending();
    }

    private void showPromptIfPending() {
        long at = CabTrip.promptAt(this);
        if (at <= 0 || !CabTrip.isActive(this)) {
            if (promptDialog != null) promptDialog.dismiss();
            return;
        }
        if (promptDialog != null && promptDialog.isShowing()) return;
        promptDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.jr_cab_prompt_title)
                .setMessage(getString(R.string.jr_cab_prompt_dialog, secondsLeft(at)))
                .setCancelable(false)
                .setPositiveButton(R.string.jr_cab_im_ok, (d, w) -> JourneyService.send(this, JourneyService.ACTION_CAB_OK))
                .setNegativeButton(R.string.jr_sos, (d, w) -> JourneyService.send(this, JourneyService.ACTION_CAB_SOS))
                .show();
    }

    private static long secondsLeft(long promptAt) {
        return Math.max(0L, (promptAt + 60_000L - System.currentTimeMillis()) / 1000L);
    }

    // ------------------------------------------------------------------ render

    private void render() {
        if (b == null) return;
        boolean active = CabTrip.isActive(this);
        JourneyUtil.show(b.setupGroup, !active);
        JourneyUtil.show(b.activeGroup, active);
        handler.removeCallbacks(ticker);
        if (active) {
            stopCamera();
            handler.post(ticker);
        } else {
            boolean camera = JourneyUtil.granted(this, Manifest.permission.CAMERA);
            JourneyUtil.show(b.cameraPlaceholder, !camera);
            if (camera && !cameraOn) startCamera();
            int n = Prefs.getContactNumbers(this).size();
            b.contactsNote.setText(n == 0 ? getString(R.string.jr_contacts_none_sub)
                    : getString(R.string.jr_cab_contacts_note, n));
        }
        if (promptDialog != null && promptDialog.isShowing() && CabTrip.promptAt(this) <= 0) {
            promptDialog.dismiss();
        }
    }

    private void renderActive() {
        if (!CabTrip.isActive(this)) {
            render();
            return;
        }
        b.activePlate.setText(CabTrip.plate(this));
        List<String> meta = new ArrayList<>();
        if (!TextUtils.isEmpty(CabTrip.app(this))) meta.add(CabTrip.app(this));
        if (!TextUtils.isEmpty(CabTrip.driver(this))) {
            meta.add(getString(R.string.jr_cab_driver_label, CabTrip.driver(this)));
        }
        if (!TextUtils.isEmpty(CabTrip.destName(this))) {
            meta.add(getString(R.string.jr_cab_to_label, CabTrip.destName(this)));
        }
        b.activeMeta.setText(TextUtils.join(getString(R.string.jr_dot_separator), meta));
        JourneyUtil.show(b.activeMeta, !meta.isEmpty());

        b.elapsedText.setText(JourneyUtil.countdown(System.currentTimeMillis() - CabTrip.startedAt(this)));
        boolean hasDest = CabTrip.dest(this) != null;
        float left = CabTrip.distLeft(this);
        b.distanceText.setText(!hasDest ? getString(R.string.jr_dash)
                : left < 0 ? getString(R.string.jr_cab_locating) : JourneyUtil.distance(this, left));

        boolean near = CabTrip.isNear(this);
        boolean prompt = CabTrip.promptAt(this) > 0;
        int tone;
        String text;
        if (prompt) {
            tone = R.color.ns_danger;
            text = getString(R.string.jr_cab_monitor_prompt, secondsLeft(CabTrip.promptAt(this)));
        } else if (near) {
            tone = R.color.ns_safe;
            text = getString(R.string.jr_cab_monitor_near);
        } else if (!hasDest) {
            tone = R.color.ns_warn;
            text = getString(R.string.jr_cab_monitor_nodest);
        } else if (!JourneyUtil.hasLocation(this)) {
            tone = R.color.ns_warn;
            text = getString(R.string.jr_cab_no_location);
        } else {
            tone = R.color.ns_safe;
            text = getString(R.string.jr_cab_monitor_on);
        }
        b.monitorText.setText(text);
        ViewCompat.setBackgroundTintList(b.monitorDot, ColorStateList.valueOf(color(tone)));
        ViewCompat.setBackgroundTintList(b.monitorRow, ColorStateList.valueOf(color(
                tone == R.color.ns_danger ? R.color.ns_danger_container
                        : tone == R.color.ns_warn ? R.color.ns_warn_container : R.color.ns_safe_container)));
        JourneyUtil.show(b.arrivedButton, near || !hasDest);

        if (prompt) {
            if (promptDialog != null && promptDialog.isShowing()) {
                promptDialog.setMessage(getString(R.string.jr_cab_prompt_dialog, secondsLeft(CabTrip.promptAt(this))));
            } else {
                showPromptIfPending();
            }
        }
    }

    private int color(int res) {
        return ContextCompat.getColor(this, res);
    }

    private abstract static class SimpleWatcher implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {}
    }
}
