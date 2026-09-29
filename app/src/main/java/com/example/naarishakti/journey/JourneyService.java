package com.example.naarishakti.journey;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.example.naarishakti.R;
import com.example.naarishakti.VoiceRecognitionService;
import com.example.naarishakti.core.Prefs;
import com.example.naarishakti.core.ProtectionController;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import java.util.ArrayList;
import java.util.List;

/**
 * Foreground location service for journeys.
 * <ul>
 *   <li>Check-in: sends a location to the server every 60 s and double-checks the deadline
 *   (in case an alarm is delayed).</li>
 *   <li>Cab mode: watches progress towards the destination. If the distance grows by more than
 *   500 m over 3 consecutive minutes, or the phone stands still (&lt; 30 m) for over 6 minutes
 *   more than 300 m from the destination, it asks "Is everything OK?" and raises an SOS when
 *   there is no answer within 60 s. Within 150 m of the destination it offers "Arrived safely".</li>
 * </ul>
 * Stops itself when neither a check-in nor a cab trip is active.
 */
public class JourneyService extends Service {

    private static final String TAG = "JourneyService";

    static final String ACTION_SYNC = "com.example.naarishakti.journey.SYNC";
    static final String ACTION_CAB_OK = "com.example.naarishakti.journey.CAB_OK";
    static final String ACTION_CAB_SOS = "com.example.naarishakti.journey.CAB_SOS";
    static final String ACTION_CAB_ARRIVED = "com.example.naarishakti.journey.CAB_ARRIVED";
    static final String ACTION_CAB_END = "com.example.naarishakti.journey.CAB_END";
    static final String ACTION_CHECKIN_EXTEND = "com.example.naarishakti.journey.CHECKIN_EXTEND";

    static final int NOTIF_ONGOING = 7310;
    static final int NOTIF_CAB_PROMPT = 7311;
    static final int NOTIF_CAB_ARRIVED = 7312;

    private static final long CHECKIN_INTERVAL_MS = 60_000L;
    private static final long CAB_INTERVAL_MS = 15_000L;
    private static final long TICK_MS = 20_000L;
    private static final long PROMPT_TIMEOUT_MS = 60_000L;
    private static final long PROMPT_COOLDOWN_MS = 5 * 60_000L;

    // Deviation rules
    private static final float MOVING_AWAY_M = 500f;
    private static final int MOVING_AWAY_MINUTES = 3;
    private static final float STATIONARY_RADIUS_M = 30f;
    private static final long STATIONARY_MS = 6 * 60_000L;
    private static final float STATIONARY_MIN_FROM_DEST_M = 300f;

    private static volatile boolean running;

    private FusedLocationProviderClient fused;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long currentInterval = -1L;
    private long lastCloudUpload;

    // Cab monitoring
    private final List<Long> sampleTimes = new ArrayList<>();
    private final List<Float> sampleDists = new ArrayList<>();
    @Nullable private Location anchor;
    private long anchorTime;
    private long cooldownUntil;
    private boolean arrivalNotified;

    private final LocationCallback callback = new LocationCallback() {
        @Override
        public void onLocationResult(@NonNull LocationResult result) {
            Location l = result.getLastLocation();
            if (l != null) onLocation(l);
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            onTick();
            handler.postDelayed(this, TICK_MS);
        }
    };

    private final Runnable promptTimeout = () -> {
        if (CabTrip.promptAt(this) > 0) {
            clearPrompt();
            Log.w(TAG, "No answer to the cab check: triggering SOS");
            ProtectionController.triggerPanic(this, "cab_deviation");
        }
    };

    // ------------------------------------------------------------------ control

    /**
     * Starts, refreshes or stops the service to match the saved journey state.
     * @param fromForeground true when called from a visible screen. From the background the
     *                       location service can only start with "Allow all the time" location.
     */
    static void sync(Context ctx, boolean fromForeground) {
        Context app = ctx.getApplicationContext();
        boolean needed = CheckIn.isActive(app) || CabTrip.isActive(app);
        Intent i = new Intent(app, JourneyService.class).setAction(ACTION_SYNC);
        if (!needed) {
            if (running) app.stopService(new Intent(app, JourneyService.class));
            return;
        }
        if (!JourneyUtil.hasLocation(app)) return; // alarms still cover the check-in
        try {
            if (running) {
                app.startService(i);
            } else if (fromForeground || JourneyUtil.hasBackgroundLocation(app)) {
                ContextCompat.startForegroundService(app, i);
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to start journey service", e);
        }
    }

    static void send(Context ctx, String action) {
        Context app = ctx.getApplicationContext();
        Intent i = new Intent(app, JourneyService.class).setAction(action);
        try {
            if (running) app.startService(i);
            else ContextCompat.startForegroundService(app, i);
        } catch (Exception e) {
            Log.w(TAG, "Unable to deliver " + action, e);
        }
    }

    static boolean isRunning() {
        return running;
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        JourneyUtil.createChannels(this);
        fused = LocationServices.getFusedLocationProviderClient(this);
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (!enterForeground()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent == null ? null : intent.getAction();
        if (ACTION_CAB_OK.equals(action)) {
            clearPrompt();
            resetMonitoring();
            cooldownUntil = System.currentTimeMillis() + PROMPT_COOLDOWN_MS;
        } else if (ACTION_CAB_SOS.equals(action)) {
            clearPrompt();
            ProtectionController.triggerPanic(this, "cab_deviation");
        } else if (ACTION_CAB_ARRIVED.equals(action)) {
            arrivedSafely(this);
        } else if (ACTION_CAB_END.equals(action)) {
            endTrip(this);
        } else if (ACTION_CHECKIN_EXTEND.equals(action)) {
            CheckIn.extend(this);
        }
        refresh();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        stopUpdates();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private boolean enterForeground() {
        Notification n = buildOngoing();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ONGOING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIF_ONGOING, n);
            }
            return true;
        } catch (Exception e) {
            // Android 14: no location permission, or started from the background without it.
            Log.w(TAG, "startForeground refused", e);
            return false;
        }
    }

    /** Re-reads state; stops when idle, otherwise adjusts the location rate and notification. */
    private void refresh() {
        boolean checkIn = CheckIn.isActive(this);
        boolean cab = CabTrip.isActive(this);
        if (!checkIn && !cab) {
            handler.removeCallbacksAndMessages(null);
            stopUpdates();
            JourneyUtil.cancelNotification(this, NOTIF_CAB_PROMPT);
            JourneyUtil.cancelNotification(this, NOTIF_CAB_ARRIVED);
            stopForegroundCompat();
            stopSelf();
            return;
        }
        if (!cab) {
            resetMonitoring();
            arrivalNotified = false;
        }
        startUpdates(cab ? CAB_INTERVAL_MS : CHECKIN_INTERVAL_MS, cab);
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, TICK_MS);
        JourneyUtil.notifySafely(this, NOTIF_ONGOING, buildOngoing());
    }

    @SuppressWarnings("deprecation")
    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    @SuppressLint("MissingPermission")
    private void startUpdates(long interval, boolean precise) {
        if (interval == currentInterval) return;
        stopUpdates();
        if (!JourneyUtil.hasLocation(this)) return;
        boolean fine = JourneyUtil.granted(this, Manifest.permission.ACCESS_FINE_LOCATION);
        int priority = precise && fine ? Priority.PRIORITY_HIGH_ACCURACY : Priority.PRIORITY_BALANCED_POWER_ACCURACY;
        LocationRequest req = new LocationRequest.Builder(priority, interval)
                .setMinUpdateIntervalMillis(interval / 2)
                .build();
        try {
            fused.requestLocationUpdates(req, callback, Looper.getMainLooper());
            currentInterval = interval;
        } catch (SecurityException e) {
            Log.w(TAG, "Location updates refused", e);
        }
    }

    private void stopUpdates() {
        if (currentInterval < 0) return;
        try {
            fused.removeLocationUpdates(callback);
        } catch (Exception ignored) {
            // Nothing to remove.
        }
        currentInterval = -1L;
    }

    // ------------------------------------------------------------------ location

    private void onLocation(Location l) {
        long now = System.currentTimeMillis();
        if (CheckIn.isActive(this) && now - lastCloudUpload >= CHECKIN_INTERVAL_MS - 5_000L) {
            lastCloudUpload = now;
            CheckIn.uploadLocation(this, l);
        }
        if (CabTrip.isActive(this)) evaluateCab(l, now);
    }

    private void onTick() {
        // Backup for delayed/inexact alarms.
        if (CheckIn.isActive(this) && !CheckIn.isOverdue(this)) {
            long now = System.currentTimeMillis();
            long deadline = CheckIn.deadline(this);
            if (now >= deadline) {
                CheckIn.onDeadline(this);
            } else if (now >= deadline - CheckIn.WARN_BEFORE_MS) {
                CheckIn.onWarning(this);
            }
        }
        if (CheckIn.isActive(this) || CabTrip.isActive(this)) {
            JourneyUtil.notifySafely(this, NOTIF_ONGOING, buildOngoing());
        }
    }

    private void evaluateCab(Location l, long now) {
        double[] dest = CabTrip.dest(this);
        if (dest == null) return; // no destination: nothing to measure progress against
        float[] out = new float[1];
        Location.distanceBetween(l.getLatitude(), l.getLongitude(), dest[0], dest[1], out);
        float dist = out[0];
        boolean near = dist <= CabTrip.ARRIVAL_RADIUS_M;
        boolean wasNear = CabTrip.isNear(this);
        CabTrip.saveProgress(this, dist, near || wasNear);

        if (near) {
            if (!arrivalNotified) {
                arrivalNotified = true;
                postArrival();
                JourneyUtil.notifySafely(this, NOTIF_ONGOING, buildOngoing());
            }
            return;
        }
        if (CabTrip.promptAt(this) > 0 || now < cooldownUntil) return;

        // Rule 1: moving away from the destination for 3 consecutive minutes, > 500 m in total.
        if (sampleTimes.isEmpty() || now - sampleTimes.get(sampleTimes.size() - 1) >= 60_000L) {
            sampleTimes.add(now);
            sampleDists.add(dist);
            while (sampleTimes.size() > MOVING_AWAY_MINUTES + 1) {
                sampleTimes.remove(0);
                sampleDists.remove(0);
            }
        }
        if (sampleDists.size() == MOVING_AWAY_MINUTES + 1) {
            boolean increasing = true;
            for (int i = 1; i < sampleDists.size(); i++) {
                if (sampleDists.get(i) <= sampleDists.get(i - 1)) {
                    increasing = false;
                    break;
                }
            }
            float grown = sampleDists.get(sampleDists.size() - 1) - sampleDists.get(0);
            if (increasing && grown > MOVING_AWAY_M) {
                prompt();
                return;
            }
        }

        // Rule 2: stationary for more than 6 minutes, far from the destination.
        if (anchor == null || l.distanceTo(anchor) > STATIONARY_RADIUS_M) {
            anchor = l;
            anchorTime = now;
        } else if (now - anchorTime > STATIONARY_MS && dist > STATIONARY_MIN_FROM_DEST_M) {
            prompt();
        }
    }

    private void resetMonitoring() {
        sampleTimes.clear();
        sampleDists.clear();
        anchor = null;
        anchorTime = 0L;
    }

    // ------------------------------------------------------------------ cab prompt

    private void prompt() {
        long now = System.currentTimeMillis();
        CabTrip.setPromptAt(this, now);
        resetMonitoring();

        Intent open = new Intent(this, CabModeActivity.class)
                .setAction(CabModeActivity.ACTION_PROMPT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent full = PendingIntent.getActivity(this, 7501, open, JourneyUtil.piFlags());
        Notification n = new NotificationCompat.Builder(this, JourneyUtil.CH_ALERTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(ContextCompat.getColor(this, R.color.ns_rose))
                .setContentTitle(getString(R.string.jr_cab_prompt_title))
                .setContentText(getString(R.string.jr_cab_prompt_text))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(getString(R.string.jr_cab_prompt_text)))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setWhen(now + PROMPT_TIMEOUT_MS)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setContentIntent(full)
                .setFullScreenIntent(full, true)
                .addAction(0, getString(R.string.jr_cab_im_ok), serviceIntent(ACTION_CAB_OK, 7502))
                .addAction(0, getString(R.string.jr_sos), serviceIntent(ACTION_CAB_SOS, 7503))
                .build();
        JourneyUtil.notifySafely(this, NOTIF_CAB_PROMPT, n);
        handler.removeCallbacks(promptTimeout);
        handler.postDelayed(promptTimeout, PROMPT_TIMEOUT_MS);
    }

    private void clearPrompt() {
        handler.removeCallbacks(promptTimeout);
        CabTrip.setPromptAt(this, 0L);
        JourneyUtil.cancelNotification(this, NOTIF_CAB_PROMPT);
    }

    private void postArrival() {
        Notification n = new NotificationCompat.Builder(this, JourneyUtil.CH_ALERTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(ContextCompat.getColor(this, R.color.ns_safe))
                .setContentTitle(getString(R.string.jr_cab_near_title))
                .setContentText(getString(R.string.jr_cab_near_text, CabTrip.destLabel(this)))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(openCabIntent())
                .addAction(0, getString(R.string.jr_cab_arrived), serviceIntent(ACTION_CAB_ARRIVED, 7504))
                .build();
        JourneyUtil.notifySafely(this, NOTIF_CAB_ARRIVED, n);
    }

    // ------------------------------------------------------------------ trip end

    /** SMS the contacts that she arrived, then end the trip. */
    static void arrivedSafely(Context ctx) {
        final Context app = ctx.getApplicationContext();
        if (!CabTrip.isActive(app)) return;
        final String dest = CabTrip.destLabel(app);
        final List<String> numbers = Prefs.getContactNumbers(app);
        JourneyUtil.io(() -> {
            String text = app.getString(R.string.jr_sms_cab_arrived, JourneyUtil.userName(app), dest);
            VoiceRecognitionService.sendSmsToAll(app, numbers, text);
        });
        endTrip(app);
    }

    static void endTrip(Context ctx) {
        Context app = ctx.getApplicationContext();
        CabTrip.clear(app);
        JourneyUtil.cancelNotification(app, NOTIF_CAB_PROMPT);
        JourneyUtil.cancelNotification(app, NOTIF_CAB_ARRIVED);
        sync(app, false);
    }

    // ------------------------------------------------------------------ notification

    private PendingIntent serviceIntent(String action, int req) {
        Intent i = new Intent(this, JourneyService.class).setAction(action);
        return PendingIntent.getService(this, req, i, JourneyUtil.piFlags());
    }

    private PendingIntent openCabIntent() {
        Intent i = new Intent(this, CabModeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 7505, i, JourneyUtil.piFlags());
    }

    private Notification buildOngoing() {
        boolean cab = CabTrip.isActive(this);
        boolean checkIn = CheckIn.isActive(this);
        NotificationCompat.Builder nb = new NotificationCompat.Builder(this, JourneyUtil.CH_ONGOING)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(ContextCompat.getColor(this, R.color.ns_rose))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW);

        if (cab) {
            String plate = CabTrip.plate(this);
            nb.setContentTitle(TextUtils.isEmpty(plate)
                    ? getString(R.string.jr_cab_ongoing_title_noplate)
                    : getString(R.string.jr_cab_ongoing_title, plate));
            float left = CabTrip.distLeft(this);
            String text;
            if (CabTrip.dest(this) == null) {
                text = getString(R.string.jr_cab_ongoing_nodest);
            } else if (left < 0) {
                text = getString(R.string.jr_cab_ongoing_locating);
            } else {
                text = getString(R.string.jr_cab_ongoing_left, JourneyUtil.distance(this, left));
            }
            if (checkIn) text = getString(R.string.jr_joined, text, CheckIn.summary(this));
            nb.setContentText(text)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                    .setContentIntent(openCabIntent());
            if (CabTrip.isNear(this)) {
                nb.addAction(0, getString(R.string.jr_cab_arrived), serviceIntent(ACTION_CAB_ARRIVED, 7506));
            }
            nb.addAction(0, getString(R.string.jr_cab_end), serviceIntent(ACTION_CAB_END, 7507));
            nb.addAction(0, getString(R.string.jr_sos), serviceIntent(ACTION_CAB_SOS, 7508));
        } else if (checkIn) {
            nb.setContentTitle(getString(CheckIn.isOverdue(this)
                            ? R.string.jr_ci_ongoing_title_overdue : R.string.jr_ci_ongoing_title))
                    .setContentText(CheckIn.summary(this))
                    .setWhen(CheckIn.deadline(this))
                    .setShowWhen(true)
                    .setUsesChronometer(!CheckIn.isOverdue(this))
                    .setChronometerCountDown(true)
                    .setContentIntent(CheckIn.openIntent(this, false))
                    .addAction(0, getString(R.string.jr_ci_safe), CheckIn.openIntent(this, true))
                    .addAction(0, getString(R.string.jr_ci_extend), serviceIntent(ACTION_CHECKIN_EXTEND, 7509));
        } else {
            nb.setContentTitle(getString(R.string.jr_journey_title));
        }
        return nb.build();
    }
}
