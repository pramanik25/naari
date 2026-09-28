package Services;

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
import android.os.SystemClock;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.example.naarishakti.MainActivity;
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
import com.google.android.gms.tasks.CancellationTokenSource;

import java.util.Date;
import java.util.List;

import SQLite_Database.ProfileDbHelper;

/**
 * Shares the user's live location with all emergency contacts by SMS for a chosen number of
 * minutes: a start message, updates every 2 minutes or 100 m, and a final "ended" message.
 */
public class LiveShareService extends Service {

    private static final String TAG = "LiveShareService";
    private static final int NOTIFICATION_ID = 1004;
    private static final String EXTRA_MINUTES = "minutes";
    private static final String ACTION_STOP = "com.example.naarishakti.ACTION_STOP_LIVE_SHARE";

    private static final long FIRST_FIX_TIMEOUT_MS = 8_000;
    private static final long UPDATE_INTERVAL_MS = 2 * 60_000;
    private static final float UPDATE_DISTANCE_M = 100f;

    private static volatile boolean running;
    private static volatile long endTimeMillis;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private FusedLocationProviderClient fusedClient;
    private LocationCallback callback;
    private Location latest;
    private Location lastSent;
    private long lastSentAt;
    private boolean startSent;
    private boolean firstFixPending;
    private int minutes;
    private int session;

    // ---- Public API ----

    public static void start(Context ctx, int minutes) {
        Context app = ctx.getApplicationContext();
        Intent i = new Intent(app, LiveShareService.class).putExtra(EXTRA_MINUTES, Math.max(1, minutes));
        try {
            ContextCompat.startForegroundService(app, i);
        } catch (Exception e) {
            Log.e(TAG, "Unable to start live sharing", e);
        }
    }

    public static void stop(Context ctx) {
        if (!running) return;
        Context app = ctx.getApplicationContext();
        try {
            app.startService(new Intent(app, LiveShareService.class).setAction(ACTION_STOP));
        } catch (Exception e) {
            // Background start refused: stop without the final message.
            app.stopService(new Intent(app, LiveShareService.class));
        }
    }

    public static boolean isRunning() {
        return running;
    }

    /** Wall-clock end of the current session, or 0 when not sharing. */
    public static long getEndTimeMillis() {
        return running ? endTimeMillis : 0;
    }

    // ---- Service ----

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            if (System.currentTimeMillis() >= endTimeMillis) {
                finish(true);
                return;
            }
            if (startSent && latest != null
                    && SystemClock.elapsedRealtime() - lastSentAt >= UPDATE_INTERVAL_MS - 5_000) {
                sendUpdate(latest);
            }
            handler.postDelayed(this, 30_000);
        }
    };

    private final Runnable firstFixTimeout = () -> {
        if (running && !startSent) sendStart(latest);
    };

    @Override
    public void onCreate() {
        super.onCreate();
        fusedClient = LocationServices.getFusedLocationProviderClient(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            if (running) {
                finish(true);
            } else {
                stopSelf();
            }
            return START_NOT_STICKY;
        }
        if (intent == null) {
            // Not restarted after a process kill: the time window and SMS state are gone.
            stopSelf();
            return START_NOT_STICKY;
        }
        minutes = intent.getIntExtra(EXTRA_MINUTES, 15);
        endTimeMillis = System.currentTimeMillis() + minutes * 60_000L;

        if (!goForeground()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        beginSession();
        return START_NOT_STICKY;
    }

    private boolean goForeground() {
        try {
            Notification n = buildNotification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Unable to start location foreground service", e);
            return false;
        }
    }

    private void beginSession() {
        session++;
        running = true;
        startSent = false;
        firstFixPending = false;
        lastSent = null;
        stopUpdates();
        handler.removeCallbacksAndMessages(null);
        broadcastState();

        if (!VoiceRecognitionService.hasLocationPermission(this)) {
            sendStart(null);
        } else {
            requestLocation();
            handler.postDelayed(firstFixTimeout, FIRST_FIX_TIMEOUT_MS);
        }
        handler.postDelayed(ticker, 30_000);
    }

    @SuppressWarnings("MissingPermission") // checked in beginSession()
    private void requestLocation() {
        final int id = session;
        try {
            CancellationTokenSource cts = new CancellationTokenSource();
            handler.postDelayed(cts::cancel, FIRST_FIX_TIMEOUT_MS);
            fusedClient.getLastLocation().addOnSuccessListener(loc -> {
                if (id == session && loc != null && latest == null) latest = loc;
            });
            fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.getToken())
                    .addOnSuccessListener(loc -> {
                        if (id == session && loc != null) onLocation(loc);
                    });
            callback = new LocationCallback() {
                @Override
                public void onLocationResult(@NonNull LocationResult result) {
                    Location loc = result.getLastLocation();
                    if (loc != null && running) onLocation(loc);
                }
            };
            LocationRequest request = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 30_000L)
                    .setMinUpdateIntervalMillis(15_000L)
                    .build();
            fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper());
        } catch (SecurityException e) {
            Log.e(TAG, "Location permission lost", e);
        }
    }

    private void onLocation(Location loc) {
        latest = loc;
        if (!startSent) {
            handler.removeCallbacks(firstFixTimeout);
            sendStart(loc);
        } else if (firstFixPending) {
            firstFixPending = false;
            sendUpdate(loc);
        } else if (lastSent == null || loc.distanceTo(lastSent) >= UPDATE_DISTANCE_M) {
            sendUpdate(loc);
        }
    }

    private void sendStart(@Nullable Location loc) {
        startSent = true;
        String name = profileName();
        String text;
        if (loc != null) {
            String link = VoiceRecognitionService.mapsLink(loc);
            text = name != null
                    ? getString(R.string.eng_sms_live_start_named, name, minutes, link)
                    : getString(R.string.eng_sms_live_start, minutes, link);
            lastSent = loc;
            lastSentAt = SystemClock.elapsedRealtime();
        } else {
            text = name != null
                    ? getString(R.string.eng_sms_live_start_named_no_fix, name, minutes)
                    : getString(R.string.eng_sms_live_start_no_fix, minutes);
            firstFixPending = true;
        }
        VoiceRecognitionService.sendSmsToAll(this, Prefs.getContactNumbers(this), text);
    }

    private void sendUpdate(Location loc) {
        String time = DateFormat.getTimeFormat(this).format(new Date());
        VoiceRecognitionService.sendSmsToAll(this, Prefs.getContactNumbers(this),
                getString(R.string.eng_sms_live_update, time, VoiceRecognitionService.mapsLink(loc)));
        lastSent = loc;
        lastSentAt = SystemClock.elapsedRealtime();
    }

    @Nullable
    private String profileName() {
        ProfileDbHelper db = new ProfileDbHelper(this);
        try {
            String name = db.getProfileName();
            return TextUtils.isEmpty(name) ? null : name;
        } finally {
            db.close();
        }
    }

    private void finish(boolean sendEnded) {
        if (running && sendEnded) {
            VoiceRecognitionService.sendSmsToAll(this, Prefs.getContactNumbers(this),
                    getString(R.string.eng_sms_live_ended));
        }
        running = false;
        stopSelf();
    }

    private void stopUpdates() {
        if (callback != null) fusedClient.removeLocationUpdates(callback);
        callback = null;
    }

    private Notification buildNotification() {
        VoiceRecognitionService.createChannels(this);
        List<String> numbers = Prefs.getContactNumbers(this);
        String ends = DateFormat.getTimeFormat(this).format(new Date(endTimeMillis));
        PendingIntent open = PendingIntent.getActivity(this, 5,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 6,
                new Intent(this, LiveShareService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, VoiceRecognitionService.CHANNEL_LIVE)
                .setSmallIcon(R.drawable.eng_ic_location)
                .setColor(ContextCompat.getColor(this, R.color.ns_violet))
                .setContentTitle(getString(R.string.eng_live_title))
                .setContentText(getString(R.string.eng_live_text, numbers.size(), ends))
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setWhen(endTimeMillis)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(open)
                .addAction(R.drawable.eng_ic_close, getString(R.string.eng_action_stop), stop)
                .build();
    }

    private void broadcastState() {
        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(ProtectionController.ACTION_STATE_CHANGED));
    }

    @Override
    public void onDestroy() {
        running = false;
        endTimeMillis = 0;
        handler.removeCallbacksAndMessages(null);
        stopUpdates();
        broadcastState();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
