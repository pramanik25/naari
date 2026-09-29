package com.example.naarishakti;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.AssetFileDescriptor;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.location.Location;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.telecom.TelecomManager;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.SmsManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.example.naarishakti.core.Prefs;
import com.example.naarishakti.core.ProtectionController;
import com.example.naarishakti.core.SafetyHooks;
import com.example.naarishakti.escape.FakeCall;
import com.example.naarishakti.security.PinStore;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import SQLite_Database.ProfileDbHelper;
import Services.BootReceiver;

/**
 * The emergency engine. While protection is on it watches the shake and power-button triggers and
 * runs the single SOS flow used by every source (voice, power, shake, SOS button, notification):
 * optional cancel countdown, SMS with location (and tracking link) to all contacts, calls with
 * escalation, live-location follow-ups, siren + strobe + spoken alert, evidence photos and the
 * PanicActivity status screen.
 *
 * Silent SOS (setting or forced) skips everything visible/audible and shows only a low-key
 * "sharing location" notification. The duress PIN turns a running SOS into a silent one while the
 * phone behaves as if it had been stopped.
 */
public class VoiceRecognitionService extends Service {

    private static final String TAG = "SosEngine";

    public static final int PROTECTION_NOTIFICATION_ID = 1001;
    private static final int SOS_NOTIFICATION_ID = 1002;

    public static final String CHANNEL_PROTECTION = "ns_protection";
    public static final String CHANNEL_SOS = "ns_sos";
    public static final String CHANNEL_ALERTS = "ns_alerts";
    public static final String CHANNEL_LIVE = "ns_live";
    public static final String CHANNEL_GEOFENCE = "ns_geofence";

    /** Stop an active SOS after the app PIN was verified (or when no PIN is set). App-internal. */
    public static final String ACTION_STOP_VERIFIED = "com.example.naarishakti.ACTION_STOP_VERIFIED";
    /** The duress PIN was entered: look stopped, continue covertly. App-internal. */
    public static final String ACTION_DURESS = "com.example.naarishakti.ACTION_DURESS";

    private static final String ACTION_STOP_PROTECTION = "com.example.naarishakti.ACTION_STOP_PROTECTION";
    private static final String SOURCE_SOS_BUTTON = "sos_button";
    private static final String SOURCE_DURESS = "duress";

    private static final long LOCATION_FIX_TIMEOUT_MS = 6_000;
    /** Budget for one fresh high-accuracy fix; the periodic updates keep trying after that. */
    private static final long CURRENT_FIX_TIMEOUT_MS = 20_000;
    private static final long LOCATION_SMS_INTERVAL_MS = 2 * 60_000;
    private static final float LOCATION_SMS_DISTANCE_M = 100f;
    private static final long CAPTURE_INTERVAL_MS = 15_000;
    private static final int MAX_PHOTOS = 20;
    private static final long SHAKE_STOP_GRACE_MS = 5_000;
    private static final long INCIDENT_WAKELOCK_MS = 60 * 60_000L;

    private static final long STROBE_TOGGLE_MS = 125; // ~4 flashes per second
    private static final long SPEAK_FIRST_MS = 3_000;
    private static final long SPEAK_INTERVAL_MS = 20_000;
    private static final float SIREN_DUCK_VOLUME = 0.15f;

    private static final long CALL_ANSWERED_MS = 25_000;
    private static final long CALL_NO_OFFHOOK_MS = 20_000;
    private static final long CALL_NEXT_DELAY_MS = 2_000;

    private static final long SMS_RETRY_INTERVAL_MS = 60_000;

    private static final String VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION";
    private static final String EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE";
    private static final String EXTRA_VOLUME_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE";
    private static final String EXTRA_PREV_VOLUME_STREAM_VALUE = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE";
    private static final String UPLOAD_WORK_NAME = "sendUnsentImages";

    private enum State { IDLE, COUNTDOWN, ACTIVE }

    /** Latest panic status for PanicActivity to render when it (re)starts; null when idle. */
    private static volatile Bundle panicStatus;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean foreground;

    // ---- Triggers ----
    private final PowerButtonReceiver powerButtonReceiver = new PowerButtonReceiver();
    private final ShakeDetector shakeDetector = new ShakeDetector();
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private boolean shakeRegistered;

    // ---- Incident state ----
    private State state = State.IDLE;
    private int incident;
    private long stateSince;
    private boolean firedSos;
    private long fireAtElapsed;
    @Nullable private String triggerSource;
    /** Covert SOS for this incident (Silent SOS setting or a forced-silent trigger). */
    private boolean triggerSilent;
    /** Not reported as a running panic to the rest of the UI (forced-silent trigger or duress). */
    private boolean hiddenIncident;
    /** The duress PIN was entered for this incident. */
    private boolean duress;
    @Nullable private SafetyHooks.Incident currentIncident;
    private final Bundle status = new Bundle();
    private PowerManager.WakeLock incidentWakeLock;

    // ---- Location ----
    private FusedLocationProviderClient fusedClient;
    private LocationCallback locationCallback;
    private Location latestLocation;
    private Location lastSentLocation;
    private long lastLocationSmsAt;
    private boolean alertSent;
    private boolean locationFollowUpPending;

    // ---- Siren & volume keys ----
    private MediaPlayer siren;
    private AudioFocusRequest focusRequest;
    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        // Keep the siren going whatever else wants audio focus.
    };
    private final Map<Integer, Integer> savedVolumes = new HashMap<>();
    private boolean volumeReceiverRegistered;
    private int volumeSeqIndex;
    private long lastVolumePressAt;
    private long lastVolumeEventAt;
    private int selfStream = -1;
    private int selfValue = -1;
    private long selfAt;

    // ---- Strobe ----
    @Nullable private CameraManager cameraManager;
    @Nullable private String torchCameraId;
    private boolean strobing;
    private boolean torchOn;
    /** True while EvidenceCapture holds a camera; the strobe leaves the torch alone meanwhile. */
    private volatile boolean evidenceCameraBusy;

    // ---- Spoken alert ----
    @Nullable private TextToSpeech tts;
    private boolean ttsReady;
    private String spokenText;

    // ---- Calls & escalation ----
    private final List<Prefs.Contact> callContacts = new ArrayList<>();
    private int callIndex;
    private boolean escalating;
    private boolean callTo112Planned;
    private boolean called112;
    private boolean sawOffhook;
    private long offhookAt;
    @Nullable private Object callStateWatcher;
    @Nullable private Object serviceStateWatcher;
    private int lastServiceState = -1;

    // ---- Evidence ----
    private EvidenceCapture capture;
    private final AtomicInteger photoCount = new AtomicInteger();

    private final BroadcastReceiver settingsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshShakeSensor();
            updateProtectionNotification();
        }
    };

    private final BroadcastReceiver volumeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (VOLUME_CHANGED_ACTION.equals(intent.getAction())) onVolumeChanged(intent);
        }
    };

    private final Runnable countdownTick = new Runnable() {
        @Override
        public void run() {
            if (state != State.COUNTDOWN) return;
            long remaining = fireAtElapsed - SystemClock.elapsedRealtime();
            if (remaining <= 0) {
                fireSos();
                return;
            }
            status.putInt(PanicActivity.EXTRA_SECONDS_LEFT, (int) Math.ceil(remaining / 1000.0));
            publishStatus();
            vibrate(60);
            handler.postDelayed(this, Math.min(1000, remaining));
        }
    };

    private final Runnable locationFixTimeout = () -> {
        if (state != State.ACTIVE || alertSent) return;
        deliverInitialAlert(latestLocation);
        if (latestLocation == null) {
            status.putInt(PanicActivity.EXTRA_LOCATION, PanicActivity.STEP_FAILED);
            publishStatus();
        }
    };

    private final Runnable locationTicker = new Runnable() {
        @Override
        public void run() {
            if (state != State.ACTIVE) return;
            if (alertSent && latestLocation != null
                    && SystemClock.elapsedRealtime() - lastLocationSmsAt >= LOCATION_SMS_INTERVAL_MS - 5_000) {
                sendLocationUpdate(latestLocation);
            }
            handler.postDelayed(this, LOCATION_SMS_INTERVAL_MS);
        }
    };

    private final Runnable strobeTick = new Runnable() {
        @Override
        public void run() {
            if (!strobing || state != State.ACTIVE) return;
            if (evidenceCameraBusy) {
                torchOn = false; // the camera owns the flash unit right now
            } else {
                setTorch(!torchOn);
            }
            handler.postDelayed(this, STROBE_TOGGLE_MS);
        }
    };

    private final Runnable speakTick = new Runnable() {
        @Override
        public void run() {
            if (!ttsReady || tts == null || state != State.ACTIVE || duress) return;
            Bundle params = new Bundle();
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM);
            tts.speak(spokenText, TextToSpeech.QUEUE_FLUSH, params, "ns_sos_" + SystemClock.elapsedRealtime());
            handler.postDelayed(this, SPEAK_INTERVAL_MS);
        }
    };

    private final Runnable smsRetryTick = new Runnable() {
        @Override
        public void run() {
            if (state != State.ACTIVE) return;
            SmsOutbox.flush(VoiceRecognitionService.this);
            handler.postDelayed(this, SMS_RETRY_INTERVAL_MS);
        }
    };

    private final Runnable callAnsweredCheck = () -> {
        if (escalating && sawOffhook) finishEscalation(PanicActivity.CALL_PHASE_CONNECTED);
    };

    private final Runnable callNoOffhook = () -> {
        if (escalating) dialNext();
    };

    private final Runnable dialNextRunnable = this::dialNext;

    // =====================================================================================
    // Lifecycle
    // =====================================================================================

    @Override
    public void onCreate() {
        super.onCreate();
        ProtectionController.reportServiceState(this, true, true);
        createChannels(this);
        foreground = goForeground();
        if (!foreground) {
            stopSelf();
            return;
        }
        fusedClient = LocationServices.getFusedLocationProviderClient(this);

        ContextCompat.registerReceiver(this, powerButtonReceiver, PowerButtonReceiver.filter(),
                ContextCompat.RECEIVER_NOT_EXPORTED);
        LocalBroadcastManager.getInstance(this)
                .registerReceiver(settingsReceiver, new IntentFilter(Prefs.ACTION_SETTINGS_UPDATED));

        shakeDetector.setOnShakeListener(this::onShake);
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        accelerometer = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        refreshShakeSensor();
        startServiceStateWatch();
        SafetyHooks.protectionStarted(this);
    }

    /** Starts as a foreground service with only the types Android will currently allow. */
    private boolean goForeground() {
        Notification notification = buildProtectionNotification(this);
        try {
            int types = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasLocationPermission(this)) {
                types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (granted(this, Manifest.permission.CAMERA)) types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
                if (granted(this, Manifest.permission.RECORD_AUDIO)) types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && types != 0) {
                startForeground(PROTECTION_NOTIFICATION_ID, notification, types);
            } else {
                startForeground(PROTECTION_NOTIFICATION_ID, notification);
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Unable to start the engine in the foreground", e);
            if (ProtectionController.isProtectionWanted(this)) {
                BootReceiver.showResumeNotification(this);
            }
            return false;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!foreground) return START_NOT_STICKY;
        String action = intent == null ? null : intent.getAction();
        if (ProtectionController.ACTION_TRIGGER_PANIC.equals(action)) {
            onTrigger(intent.getStringExtra(ProtectionController.EXTRA_SOURCE),
                    intent.getBooleanExtra(ProtectionController.EXTRA_SILENT, false));
        } else if (ProtectionController.ACTION_STOP_PANIC.equals(action)) {
            onStopRequest();
        } else if (ACTION_STOP_VERIFIED.equals(action)) {
            stopPanic(true);
        } else if (ACTION_DURESS.equals(action)) {
            onDuress();
        } else if (ACTION_STOP_PROTECTION.equals(action)) {
            // "Turn off" on the protection notification never silently ends a running SOS.
            if (state == State.IDLE) ProtectionController.stop(this);
        } else if (intent == null && state == State.IDLE && !ProtectionController.isProtectionWanted(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (state != State.IDLE) stopPanic(false);
        if (foreground) {
            try {
                unregisterReceiver(powerButtonReceiver);
            } catch (IllegalArgumentException ignored) {
                // Not registered.
            }
            LocalBroadcastManager.getInstance(this).unregisterReceiver(settingsReceiver);
            if (shakeRegistered && sensorManager != null) sensorManager.unregisterListener(shakeDetector);
            shakeRegistered = false;
            stopServiceStateWatch();
            SafetyHooks.protectionStopped(this);
        }
        handler.removeCallbacksAndMessages(null);
        ProtectionController.reportServiceState(this, true, false);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // =====================================================================================
    // Triggers
    // =====================================================================================

    private void refreshShakeSensor() {
        if (sensorManager == null || accelerometer == null) return;
        SharedPreferences prefs = Prefs.get(this);
        boolean needed = prefs.getBoolean(Prefs.SHAKE_ENABLED, true)
                || (state != State.IDLE && !duress && isShakeDeactivation());
        if (needed && !shakeRegistered) {
            shakeRegistered = sensorManager.registerListener(shakeDetector, accelerometer,
                    SensorManager.SENSOR_DELAY_UI);
        } else if (!needed && shakeRegistered) {
            sensorManager.unregisterListener(shakeDetector);
            shakeRegistered = false;
        }
    }

    private void onShake() {
        if (state == State.IDLE) {
            if (Prefs.get(this).getBoolean(Prefs.SHAKE_ENABLED, true)) onTrigger("shake", false);
        } else if (isShakeDeactivation()
                && SystemClock.elapsedRealtime() - stateSince > SHAKE_STOP_GRACE_MS) {
            Log.i(TAG, "Shake deactivation");
            onStopRequest();
        }
    }

    private boolean isShakeDeactivation() {
        return Prefs.DEACTIVATION_SHAKE.equals(
                Prefs.get(this).getString(Prefs.DEACTIVATION_METHOD, Prefs.DEACTIVATION_VOLUME));
    }

    private boolean isVolumeDeactivation() {
        return Prefs.DEACTIVATION_VOLUME.equals(
                Prefs.get(this).getString(Prefs.DEACTIVATION_METHOD, Prefs.DEACTIVATION_VOLUME));
    }

    private void onTrigger(@Nullable String source, boolean forceSilent) {
        Log.i(TAG, "SOS trigger from " + source + " (state " + state + ", silent " + forceSilent + ")");
        if (state != State.IDLE && forceSilent && SOURCE_DURESS.equals(source)) {
            onDuress(); // a duress PIN entered elsewhere while an SOS is already running
            return;
        }
        if (state == State.ACTIVE) return;
        if (state == State.COUNTDOWN) {
            if (SOURCE_SOS_BUTTON.equals(source)) fireSos();
            return;
        }
        triggerSource = source;
        triggerSilent = forceSilent || prefBool(this, Prefs.SILENT_SOS, false);
        hiddenIncident = forceSilent;
        duress = false;
        currentIncident = null;
        incident++;
        firedSos = false;
        alertSent = false;
        locationFollowUpPending = false;
        latestLocation = null;
        lastSentLocation = null;
        photoCount.set(0);
        savedVolumes.clear();
        volumeSeqIndex = 0;

        acquireIncidentWakeLock();
        ProtectionController.reportPanicState(this, !hiddenIncident);
        if (isVolumeDeactivation()) registerVolumeReceiver();

        List<Prefs.Contact> contacts = Prefs.getContacts(this);
        status.clear();
        status.putBoolean(PanicActivity.EXTRA_COVERT, triggerSilent);
        status.putInt(PanicActivity.EXTRA_CONTACT_COUNT, contacts.size());
        if (!contacts.isEmpty()) {
            status.putString(PanicActivity.EXTRA_CALL_NAME, contacts.get(0).displayName());
        }

        long confirmMs = prefNumber(Prefs.get(this), Prefs.CONFIRMATION_TIMEOUT_MS,
                Prefs.DEFAULT_CONFIRMATION_TIMEOUT_MS);
        if (confirmMs > 0 && !SOURCE_SOS_BUTTON.equals(source) && !triggerSilent) {
            startCountdown(confirmMs);
        } else {
            fireSos();
        }
        refreshShakeSensor();
    }

    private void startCountdown(long ms) {
        state = State.COUNTDOWN;
        stateSince = SystemClock.elapsedRealtime();
        fireAtElapsed = stateSince + ms;
        status.putInt(PanicActivity.EXTRA_STATE, PanicActivity.STATE_COUNTDOWN);
        status.putInt(PanicActivity.EXTRA_SECONDS_LEFT, (int) Math.ceil(ms / 1000.0));
        publishStatus();
        showPanicUi();
        vibrate(300);
        handler.postDelayed(countdownTick, Math.min(1000, ms));
    }

    /**
     * Any "stop" that did not come with a verified PIN: notification, volume pattern, shake,
     * other screens. A countdown is simply cancelled; an active SOS asks for the PIN when one is set.
     */
    private void onStopRequest() {
        if (state == State.COUNTDOWN) {
            stopPanic(true);
            return;
        }
        if (state != State.ACTIVE) return;
        if (duress) {
            Log.i(TAG, "Stop ignored: incident continues after duress");
            return;
        }
        if (PinStore.isSet(this)) {
            openPinPad();
            return;
        }
        stopPanic(true);
    }

    private void openPinPad() {
        Intent ui = new Intent(this, PanicActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(PanicActivity.EXTRA_OPEN_PIN, true)
                .putExtra(PanicActivity.EXTRA_OPEN_COVERT, triggerSilent);
        try {
            startActivity(ui);
        } catch (Exception e) {
            Log.w(TAG, "Couldn't open the PIN pad: " + e.getMessage());
        }
    }

    // =====================================================================================
    // SOS
    // =====================================================================================

    private void fireSos() {
        handler.removeCallbacks(countdownTick);
        state = State.ACTIVE;
        firedSos = true;
        stateSince = SystemClock.elapsedRealtime();
        Log.i(TAG, "SOS firing (silent " + triggerSilent + ")");
        currentIncident = SafetyHooks.sosStarted(this, triggerSource, triggerSilent);

        List<Prefs.Contact> contacts = Prefs.getContacts(this);
        boolean video = prefBool(this, Prefs.EVIDENCE_VIDEO, false);
        status.putInt(PanicActivity.EXTRA_STATE, PanicActivity.STATE_ACTIVE);
        status.putBoolean(PanicActivity.EXTRA_COVERT, triggerSilent);
        status.putInt(PanicActivity.EXTRA_CONTACT_COUNT, contacts.size());
        status.putInt(PanicActivity.EXTRA_SMS, contacts.isEmpty() ? PanicActivity.STEP_OFF : PanicActivity.STEP_PENDING);
        status.putInt(PanicActivity.EXTRA_LOCATION, PanicActivity.STEP_PENDING);
        status.putInt(PanicActivity.EXTRA_PHOTOS, 0);
        status.putBoolean(PanicActivity.EXTRA_VIDEO, video);
        publishStatus();
        if (triggerSilent) {
            showCovertNotification();
        } else {
            showPanicUi();
            startSiren();
            startStrobe();
            startSpokenAlert();
        }

        startCalls(contacts);
        startIncidentLocation();
        if (video) {
            // The evidence module records video itself; don't compete for the camera.
            status.putInt(PanicActivity.EXTRA_CAMERA, PanicActivity.STEP_PENDING);
        } else {
            startEvidenceCapture();
        }
        handler.postDelayed(smsRetryTick, SMS_RETRY_INTERVAL_MS);
        publishStatus();
    }

    /**
     * Ends the countdown or the active SOS.
     * @param userInitiated true when the user deliberately stopped it (sends "I'm safe" if it fired,
     *                      unless the duress PIN was used for this incident)
     */
    private void stopPanic(boolean userInitiated) {
        if (state == State.IDLE) return;
        boolean fired = firedSos;
        boolean wasDuress = duress;
        Log.i(TAG, "Stopping SOS (fired=" + fired + ", user=" + userInitiated + ", duress=" + wasDuress + ")");

        incident++; // invalidates late async callbacks from the finished incident
        state = State.IDLE;
        firedSos = false;
        handler.removeCallbacks(countdownTick);
        handler.removeCallbacks(locationFixTimeout);
        handler.removeCallbacks(locationTicker);
        handler.removeCallbacks(smsRetryTick);
        stopIncidentLocation();
        stopSiren();
        stopStrobe();
        stopSpokenAlert();
        stopEscalation();
        if (capture != null) {
            capture.stop();
            capture = null;
        }
        unregisterVolumeReceiver();
        restoreVolumes();
        NotificationManagerCompat.from(this).cancel(SOS_NOTIFICATION_ID);
        if (fired) SafetyHooks.sosStopped(this, userInitiated);

        status.clear();
        status.putInt(PanicActivity.EXTRA_STATE, PanicActivity.STATE_ENDED);
        publishStatus();
        panicStatus = null;

        if (fired && userInitiated && !wasDuress) {
            sendSmsToAll(this, Prefs.getContactNumbers(this), getString(R.string.eng_sms_safe));
            TelegramBot.sendMessageToEmergencyAsync(this, getString(R.string.eng_telegram_safe));
        }
        duress = false;
        hiddenIncident = false;
        triggerSilent = false;
        currentIncident = null;

        if (incidentWakeLock != null && incidentWakeLock.isHeld()) incidentWakeLock.release();
        ProtectionController.reportPanicState(this, false);
        refreshShakeSensor();
        if (!ProtectionController.isProtectionWanted(this)) stopSelf();
    }

    /**
     * Duress PIN: everything visible and audible stops exactly as for a normal stop, but the
     * incident continues as a silent SOS (location, evidence, tracking). Contacts get one extra SMS.
     */
    private void onDuress() {
        if (state == State.IDLE) return;
        if (state == State.COUNTDOWN) {
            triggerSilent = true;
            fireSos();
        }
        if (duress) return;
        Log.i(TAG, "Duress PIN: continuing covertly");
        duress = true;
        triggerSilent = true;
        hiddenIncident = true;

        stopSiren();
        stopStrobe();
        stopSpokenAlert();
        stopEscalation(); // no new calls: a ringing phone would give it away
        unregisterVolumeReceiver();
        restoreVolumes();

        // The SOS screen sees an ordinary "ended"; later updates are covert.
        Bundle ended = new Bundle();
        ended.putInt(PanicActivity.EXTRA_STATE, PanicActivity.STATE_ENDED);
        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(PanicActivity.ACTION_PANIC_UI).putExtras(ended));
        status.putBoolean(PanicActivity.EXTRA_COVERT, true);
        status.putBoolean(PanicActivity.EXTRA_SIREN, false);
        panicStatus = new Bundle(status);

        showCovertNotification();
        ProtectionController.reportPanicState(this, false);
        SafetyHooks.sosDuress(this);
        sendDuressAlert();
        refreshShakeSensor();
    }

    private void sendDuressAlert() {
        String name = profileName();
        if (name == null) name = getString(R.string.en_duress_name_fallback);
        String link = currentIncident != null ? currentIncident.trackingUrl() : null;
        if (link == null && latestLocation != null) link = mapsLink(latestLocation);
        String msg = link != null
                ? getString(R.string.en_sms_duress, name, link)
                : getString(R.string.en_sms_duress_no_link, name);
        sendSmsToAll(this, Prefs.getContactNumbers(this), msg);
        TelegramBot.sendMessageToEmergencyAsync(this, msg);
    }

    @Nullable
    private String profileName() {
        ProfileDbHelper db = new ProfileDbHelper(this);
        try {
            String name = db.getProfileName();
            return TextUtils.isEmpty(name) ? null : name;
        } catch (Exception e) {
            return null;
        } finally {
            db.close();
        }
    }

    // ---- Status -> PanicActivity ----

    private void publishStatus() {
        panicStatus = new Bundle(status);
        Intent update = new Intent(PanicActivity.ACTION_PANIC_UI).putExtras(status);
        LocalBroadcastManager.getInstance(this).sendBroadcast(update);
    }

    /** Snapshot of the current SOS for PanicActivity, or null when no SOS is running. */
    @Nullable
    public static Bundle getPanicStatus() {
        Bundle b = panicStatus;
        return b == null ? null : new Bundle(b);
    }

    private void showPanicUi() {
        boolean countdown = state == State.COUNTDOWN;
        Intent ui = new Intent(this, PanicActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtras(status);
        PendingIntent full = PendingIntent.getActivity(this, 11, ui,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_SOS)
                .setSmallIcon(R.drawable.eng_ic_siren)
                .setColor(ContextCompat.getColor(this, R.color.ns_rose))
                .setContentTitle(getString(countdown ? R.string.eng_sos_countdown_title : R.string.eng_sos_active_title))
                .setContentText(getString(countdown ? R.string.eng_sos_countdown_text : R.string.eng_sos_active_text))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(full)
                .setFullScreenIntent(full, true);

        Intent stop = new Intent(this, VoiceRecognitionService.class).setAction(ProtectionController.ACTION_STOP_PANIC);
        PendingIntent stopPi = PendingIntent.getService(this, 12, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (countdown) {
            b.setUsesChronometer(true)
                    .setChronometerCountDown(true)
                    .setWhen(System.currentTimeMillis() + Math.max(0, fireAtElapsed - SystemClock.elapsedRealtime()))
                    .addAction(R.drawable.eng_ic_close, getString(R.string.eng_action_cancel), stopPi);
        } else {
            if (PinStore.isSet(this)) {
                // With an app PIN, "Stop SOS" opens the PIN pad on the SOS screen.
                Intent pin = new Intent(this, PanicActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(PanicActivity.EXTRA_OPEN_PIN, true);
                PendingIntent pinPi = PendingIntent.getActivity(this, 15, pin,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                b.addAction(R.drawable.eng_ic_close, getString(R.string.eng_action_stop_sos), pinPi);
            } else {
                // Stopping an active SOS from the lock screen requires unlocking first.
                b.addAction(new NotificationCompat.Action.Builder(R.drawable.eng_ic_close,
                        getString(R.string.eng_action_stop_sos), stopPi)
                        .setAuthenticationRequired(true)
                        .build());
            }
            String callName = status.getString(PanicActivity.EXTRA_CALL_NAME);
            List<String> numbers = Prefs.getContactNumbers(this);
            if (!numbers.isEmpty() && granted(this, Manifest.permission.CALL_PHONE)) {
                Intent call = new Intent(Intent.ACTION_CALL, Uri.fromParts("tel", numbers.get(0), null));
                PendingIntent callPi = PendingIntent.getActivity(this, 13, call,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                b.addAction(R.drawable.eng_ic_call, getString(R.string.eng_action_call_contact,
                        callName == null ? numbers.get(0) : callName), callPi);
            }
        }
        notifySafely(this, SOS_NOTIFICATION_ID, b.build());

        // Also try to open the screen directly; allowed when the app is visible or may draw overlays.
        try {
            startActivity(ui);
        } catch (Exception e) {
            Log.w(TAG, "Couldn't open PanicActivity directly: " + e.getMessage());
        }
    }

    /** Low-key ongoing notification for a silent SOS (and after duress): no sound, no full screen. */
    private void showCovertNotification() {
        Intent ui = new Intent(this, PanicActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(PanicActivity.EXTRA_OPEN_COVERT, true);
        PendingIntent pi = PendingIntent.getActivity(this, 14, ui,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new NotificationCompat.Builder(this, CHANNEL_LIVE)
                .setSmallIcon(R.drawable.eng_ic_location)
                .setContentTitle(getString(R.string.en_covert_title))
                .setContentText(getString(R.string.en_covert_text))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(pi)
                .build();
        notifySafely(this, SOS_NOTIFICATION_ID, n);
    }

    // ---- Calls & escalation ----

    private boolean canCall() {
        return getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
                && granted(this, Manifest.permission.CALL_PHONE);
    }

    /**
     * Calls contact 1. With call escalation on (and READ_PHONE_STATE granted), an unanswered call
     * (back to idle within 25 s of going off-hook) moves on to the next contact, then optionally 112.
     * A silent SOS only calls when call escalation is on.
     */
    private void startCalls(List<Prefs.Contact> contacts) {
        stopEscalation();
        callContacts.clear();
        callContacts.addAll(contacts);
        callIndex = 0;
        called112 = false;
        boolean escalation = prefBool(this, Prefs.CALL_ESCALATION, true);
        if (triggerSilent && !escalation) {
            status.putInt(PanicActivity.EXTRA_CALL, PanicActivity.STEP_OFF);
            return;
        }
        boolean watchable = escalation && granted(this, Manifest.permission.READ_PHONE_STATE);
        callTo112Planned = watchable && prefBool(this, Prefs.ESCALATE_TO_112, false);
        if (contacts.isEmpty() && !callTo112Planned) {
            status.putInt(PanicActivity.EXTRA_CALL, PanicActivity.STEP_OFF);
            return;
        }
        if (!canCall()) {
            status.putInt(PanicActivity.EXTRA_CALL, PanicActivity.STEP_FAILED);
            return;
        }
        escalating = watchable && startCallStateWatch();
        if (!escalating) callTo112Planned = false;
        dialCurrent();
    }

    private void dialCurrent() {
        if (state != State.ACTIVE) return;
        String number;
        String name;
        if (callIndex < callContacts.size()) {
            Prefs.Contact c = callContacts.get(callIndex);
            number = Prefs.normalizeNumber(c.number);
            name = c.displayName();
        } else if (escalating && callTo112Planned && !called112) {
            number = "112";
            name = getString(R.string.en_call_112);
            called112 = true;
        } else {
            finishEscalation(PanicActivity.CALL_PHASE_NO_ANSWER);
            return;
        }
        int total = callContacts.size() + (callTo112Planned ? 1 : 0);
        status.putString(PanicActivity.EXTRA_CALL_NAME, name);
        status.putInt(PanicActivity.EXTRA_CALL_INDEX, Math.min(callIndex + 1, total));
        status.putInt(PanicActivity.EXTRA_CALL_TOTAL, total);
        boolean placed = !number.isEmpty() && dial(number);
        status.putInt(PanicActivity.EXTRA_CALL, placed ? PanicActivity.STEP_DONE : PanicActivity.STEP_FAILED);
        if (escalating) {
            status.putInt(PanicActivity.EXTRA_CALL_PHASE, PanicActivity.CALL_PHASE_RINGING);
            sawOffhook = false;
            handler.postDelayed(placed ? callNoOffhook : dialNextRunnable,
                    placed ? CALL_NO_OFFHOOK_MS : CALL_NEXT_DELAY_MS);
        }
        publishStatus();
    }

    private void dialNext() {
        handler.removeCallbacks(callNoOffhook);
        handler.removeCallbacks(callAnsweredCheck);
        handler.removeCallbacks(dialNextRunnable);
        if (!escalating || state != State.ACTIVE) return;
        callIndex++;
        dialCurrent();
    }

    private boolean dial(String number) {
        Uri uri = Uri.fromParts("tel", number, null);
        try {
            // Telecom places the call itself, so it works even when an activity can't be started.
            TelecomManager telecom = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
            if (telecom != null && ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
                    == PackageManager.PERMISSION_GRANTED) {
                telecom.placeCall(uri, new Bundle());
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "TelecomManager.placeCall failed: " + e.getMessage());
        }
        try {
            startActivity(new Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (Exception e) {
            Log.e(TAG, "ACTION_CALL failed: " + e.getMessage());
            return false;
        }
    }

    /** Main thread. OFFHOOK starts the 25 s "answered" clock; IDLE before that means unanswered. */
    private void onCallState(int callState) {
        if (!escalating || state != State.ACTIVE) return;
        if (callState == TelephonyManager.CALL_STATE_OFFHOOK) {
            if (!sawOffhook) {
                sawOffhook = true;
                offhookAt = SystemClock.elapsedRealtime();
                handler.removeCallbacks(callNoOffhook);
                handler.postDelayed(callAnsweredCheck, CALL_ANSWERED_MS);
            }
        } else if (callState == TelephonyManager.CALL_STATE_IDLE && sawOffhook) {
            sawOffhook = false;
            handler.removeCallbacks(callAnsweredCheck);
            if (SystemClock.elapsedRealtime() - offhookAt >= CALL_ANSWERED_MS) {
                finishEscalation(PanicActivity.CALL_PHASE_CONNECTED);
            } else {
                Log.i(TAG, "Call unanswered, escalating");
                handler.postDelayed(dialNextRunnable, CALL_NEXT_DELAY_MS);
            }
        }
    }

    private void finishEscalation(int phase) {
        stopEscalation();
        status.putInt(PanicActivity.EXTRA_CALL_PHASE, phase);
        publishStatus();
    }

    private void stopEscalation() {
        escalating = false;
        sawOffhook = false;
        handler.removeCallbacks(callAnsweredCheck);
        handler.removeCallbacks(callNoOffhook);
        handler.removeCallbacks(dialNextRunnable);
        stopCallStateWatch();
    }

    @SuppressWarnings("deprecation")
    private boolean startCallStateWatch() {
        if (callStateWatcher != null) return true;
        TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        if (tm == null) return false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                CallStateCallback cb = new CallStateCallback();
                tm.registerTelephonyCallback(getMainExecutor(), cb);
                callStateWatcher = cb;
            } else {
                PhoneStateListener l = new PhoneStateListener() {
                    @Override
                    public void onCallStateChanged(int s, String phoneNumber) {
                        onCallState(s);
                    }
                };
                tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE);
                callStateWatcher = l;
            }
            return true;
        } catch (SecurityException e) {
            Log.w(TAG, "Can't watch call state: " + e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private void stopCallStateWatch() {
        Object w = callStateWatcher;
        callStateWatcher = null;
        if (w == null) return;
        TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        if (tm == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && w instanceof TelephonyCallback) {
            tm.unregisterTelephonyCallback((TelephonyCallback) w);
        } else if (w instanceof PhoneStateListener) {
            tm.listen((PhoneStateListener) w, PhoneStateListener.LISTEN_NONE);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    private final class CallStateCallback extends TelephonyCallback implements TelephonyCallback.CallStateListener {
        @Override
        public void onCallStateChanged(int s) {
            onCallState(s);
        }
    }

    // ---- Network service state -> SMS retry ----

    @SuppressWarnings("deprecation")
    private void startServiceStateWatch() {
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) return;
        TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        if (tm == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ServiceStateCallback cb = new ServiceStateCallback();
                tm.registerTelephonyCallback(getMainExecutor(), cb);
                serviceStateWatcher = cb;
            } else {
                PhoneStateListener l = new PhoneStateListener() {
                    @Override
                    public void onServiceStateChanged(ServiceState ss) {
                        onServiceState(ss == null ? -1 : ss.getState());
                    }
                };
                tm.listen(l, PhoneStateListener.LISTEN_SERVICE_STATE);
                serviceStateWatcher = l;
            }
        } catch (Exception e) {
            Log.w(TAG, "Can't watch service state: " + e.getMessage());
        }
    }

    @SuppressWarnings("deprecation")
    private void stopServiceStateWatch() {
        Object w = serviceStateWatcher;
        serviceStateWatcher = null;
        if (w == null) return;
        TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        if (tm == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && w instanceof TelephonyCallback) {
            tm.unregisterTelephonyCallback((TelephonyCallback) w);
        } else if (w instanceof PhoneStateListener) {
            tm.listen((PhoneStateListener) w, PhoneStateListener.LISTEN_NONE);
        }
    }

    private void onServiceState(int s) {
        boolean becameInService = s == ServiceState.STATE_IN_SERVICE && lastServiceState != s;
        lastServiceState = s;
        if (becameInService) SmsOutbox.flush(this);
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    private final class ServiceStateCallback extends TelephonyCallback implements TelephonyCallback.ServiceStateListener {
        @Override
        public void onServiceStateChanged(@NonNull ServiceState ss) {
            onServiceState(ss.getState());
        }
    }

    // ---- Location + SMS ----

    private void startIncidentLocation() {
        if (!hasLocationPermission(this)) {
            deliverInitialAlert(null);
            status.putInt(PanicActivity.EXTRA_LOCATION, PanicActivity.STEP_FAILED);
            publishStatus();
            return;
        }
        final int id = incident;
        handler.postDelayed(locationFixTimeout, LOCATION_FIX_TIMEOUT_MS);
        handler.postDelayed(locationTicker, LOCATION_SMS_INTERVAL_MS);
        try {
            // Last known first (usually instant), then a fresh high-accuracy fix. Without a GPS fix
            // getLastLocation can itself take several seconds, so the fresh request gets its own
            // token and budget, created only when it is issued: Play Services rejects a token that
            // is already cancelled, and that exception used to crash the whole SOS.
            fusedClient.getLastLocation().addOnCompleteListener(last -> {
                if (id != incident) return;
                if (last.isSuccessful() && last.getResult() != null && latestLocation == null) {
                    latestLocation = last.getResult();
                }
                final CancellationTokenSource cts = new CancellationTokenSource();
                handler.postDelayed(cts::cancel, CURRENT_FIX_TIMEOUT_MS);
                try {
                    fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.getToken())
                            .addOnCompleteListener(current -> {
                                if (id != incident || state != State.ACTIVE) return;
                                Location fix = current.isSuccessful() ? current.getResult() : null;
                                if (fix != null) {
                                    onIncidentLocation(fix);
                                } else if (!alertSent) {
                                    handler.removeCallbacks(locationFixTimeout);
                                    locationFixTimeout.run();
                                }
                            });
                } catch (RuntimeException e) {
                    // SecurityException (permission revoked) or any Play Services argument error:
                    // log it and keep the SOS running on the periodic updates below.
                    Log.e(TAG, "Fresh location request failed", e);
                }
            });

            locationCallback = new LocationCallback() {
                @Override
                public void onLocationResult(@NonNull LocationResult result) {
                    Location loc = result.getLastLocation();
                    if (loc != null && state == State.ACTIVE) onIncidentLocation(loc);
                }
            };
            LocationRequest request = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 30_000L)
                    .setMinUpdateIntervalMillis(15_000L)
                    .build();
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper());
        } catch (SecurityException e) {
            Log.e(TAG, "Location permission lost", e);
        }
    }

    private void stopIncidentLocation() {
        if (fusedClient != null && locationCallback != null) {
            fusedClient.removeLocationUpdates(locationCallback);
        }
        locationCallback = null;
    }

    private void onIncidentLocation(Location loc) {
        latestLocation = loc;
        SafetyHooks.sosLocation(this, loc);
        if (status.getInt(PanicActivity.EXTRA_LOCATION, -1) != PanicActivity.STEP_DONE) {
            status.putInt(PanicActivity.EXTRA_LOCATION, PanicActivity.STEP_DONE);
            publishStatus();
        }
        if (!alertSent) {
            handler.removeCallbacks(locationFixTimeout);
            deliverInitialAlert(loc);
            return;
        }
        if (locationFollowUpPending) {
            locationFollowUpPending = false;
            sendLocationSms(withTracking(getString(R.string.eng_sms_location_followup, mapsLink(loc))), loc);
            return;
        }
        if (lastSentLocation == null || loc.distanceTo(lastSentLocation) >= LOCATION_SMS_DISTANCE_M) {
            sendLocationUpdate(loc);
        }
    }

    /** Appends the live-tracking line when a tracking site is configured. */
    private String withTracking(String text) {
        SafetyHooks.Incident inc = currentIncident != null ? currentIncident : SafetyHooks.current();
        String url = inc == null ? null : inc.trackingUrl();
        return url == null ? text : text + "\n" + getString(R.string.en_sms_tracking_line, url);
    }

    /** The one initial SOS SMS of the incident, with a location link when available. */
    private void deliverInitialAlert(@Nullable Location loc) {
        if (alertSent) return;
        alertSent = true;
        List<String> numbers = Prefs.getContactNumbers(this);
        String message = withTracking(Prefs.getEmergencyMessage(this) + "\n" + (loc != null
                ? getString(R.string.eng_sms_location_line, mapsLink(loc))
                : getString(R.string.eng_sms_location_pending)));
        int sent = sendSmsToAll(this, numbers, message);
        if (loc != null) {
            lastSentLocation = loc;
            lastLocationSmsAt = SystemClock.elapsedRealtime();
        } else {
            locationFollowUpPending = hasLocationPermission(this);
        }
        if (numbers.isEmpty()) {
            status.putInt(PanicActivity.EXTRA_SMS, PanicActivity.STEP_OFF);
        } else {
            status.putInt(PanicActivity.EXTRA_SMS, sent > 0 ? PanicActivity.STEP_DONE : PanicActivity.STEP_FAILED);
            status.putInt(PanicActivity.EXTRA_SMS_SENT, sent);
            status.putBoolean(PanicActivity.EXTRA_SMS_WITH_LOCATION, loc != null);
        }
        publishStatus();
        TelegramBot.sendMessageToEmergencyAsync(this, getString(R.string.eng_telegram_sos, message));
    }

    private void sendLocationUpdate(Location loc) {
        String time = DateFormat.getTimeFormat(this).format(new Date());
        sendLocationSms(withTracking(getString(R.string.eng_sms_location_update, time, mapsLink(loc))), loc);
    }

    private void sendLocationSms(String text, Location loc) {
        sendSmsToAll(this, Prefs.getContactNumbers(this), text);
        lastSentLocation = loc;
        lastLocationSmsAt = SystemClock.elapsedRealtime();
    }

    // ---- Siren ----

    private void startSiren() {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        try {
            if (am != null) {
                int max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
                // With the volume-key deactivation, stay one step below max so an UP press is detectable.
                int target = isVolumeDeactivation() && max > 1 ? max - 1 : max;
                saveVolume(am, AudioManager.STREAM_ALARM, am.getStreamVolume(AudioManager.STREAM_ALARM));
                setVolumeSelf(am, AudioManager.STREAM_ALARM, target);
                requestFocus(am);
            }
            MediaPlayer player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            AssetFileDescriptor afd = getResources().openRawResourceFd(R.raw.help);
            try {
                player.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            } finally {
                afd.close();
            }
            player.setLooping(true);
            player.prepare();
            player.start();
            siren = player;
            status.putBoolean(PanicActivity.EXTRA_SIREN, true);
        } catch (Exception e) {
            Log.e(TAG, "Siren failed", e);
            status.putBoolean(PanicActivity.EXTRA_SIREN, false);
        }
    }

    private void stopSiren() {
        if (siren != null) {
            try {
                siren.stop();
            } catch (IllegalStateException ignored) {
                // Not started.
            }
            siren.release();
            siren = null;
        }
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (focusRequest != null) am.abandonAudioFocusRequest(focusRequest);
            focusRequest = null;
        } else {
            am.abandonAudioFocus(focusListener);
        }
    }

    private void duckSiren(boolean duck) {
        if (siren == null) return;
        float v = duck ? SIREN_DUCK_VOLUME : 1f;
        try {
            siren.setVolume(v, v);
        } catch (IllegalStateException ignored) {
            // Released.
        }
    }

    @SuppressWarnings("deprecation")
    private void requestFocus(AudioManager am) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setOnAudioFocusChangeListener(focusListener)
                    .build();
            am.requestAudioFocus(focusRequest);
        } else {
            am.requestAudioFocus(focusListener, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN);
        }
    }

    // ---- Strobe ----

    private void startStrobe() {
        if (!prefBool(this, Prefs.STROBE_ENABLED, true)) return;
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        torchCameraId = findTorchCamera();
        if (torchCameraId == null) return;
        strobing = true;
        torchOn = false;
        status.putBoolean(PanicActivity.EXTRA_STROBE, true);
        handler.post(strobeTick);
    }

    private void stopStrobe() {
        boolean was = strobing || torchOn;
        strobing = false;
        handler.removeCallbacks(strobeTick);
        if (was) setTorch(false);
        torchOn = false;
    }

    private void setTorch(boolean on) {
        if (cameraManager == null || torchCameraId == null) return;
        try {
            cameraManager.setTorchMode(torchCameraId, on);
            torchOn = on;
        } catch (CameraAccessException | IllegalArgumentException | SecurityException e) {
            torchOn = false; // camera busy (evidence/video) or flash unavailable
        }
    }

    @Nullable
    private String findTorchCamera() {
        if (cameraManager == null) return null;
        try {
            String fallback = null;
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
                Boolean flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (flash == null || !flash) continue;
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return id;
                if (fallback == null) fallback = id;
            }
            return fallback;
        } catch (CameraAccessException | RuntimeException e) {
            Log.w(TAG, "No torch: " + e.getMessage());
            return null;
        }
    }

    // ---- Spoken alert ----

    private void startSpokenAlert() {
        if (!prefBool(this, Prefs.SPEAK_ALERT, false)) return;
        final int id = incident;
        try {
            tts = new TextToSpeech(getApplicationContext(), code -> handler.post(() -> onTtsReady(id, code)));
        } catch (Exception e) {
            Log.w(TAG, "TextToSpeech unavailable: " + e.getMessage());
            tts = null;
        }
    }

    private void onTtsReady(int id, int code) {
        TextToSpeech t = tts;
        if (t == null || id != incident || state != State.ACTIVE || duress) return;
        if (code != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TextToSpeech init failed: " + code);
            return;
        }
        // Speak in the app's language when the engine supports it, otherwise in English.
        Locale appLocale = getResources().getConfiguration().getLocales().get(0);
        if (appLocale != null && t.isLanguageAvailable(appLocale) >= TextToSpeech.LANG_AVAILABLE) {
            t.setLanguage(appLocale);
            spokenText = getString(R.string.en_spoken_alert);
        } else {
            t.setLanguage(Locale.ENGLISH);
            spokenText = englishString(R.string.en_spoken_alert);
        }
        t.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build());
        t.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                handler.post(() -> duckSiren(true));
            }

            @Override
            public void onDone(String utteranceId) {
                handler.post(() -> duckSiren(false));
            }

            @Override
            @SuppressWarnings("deprecation")
            public void onError(String utteranceId) {
                handler.post(() -> duckSiren(false));
            }
        });
        ttsReady = true;
        status.putBoolean(PanicActivity.EXTRA_SPEAK, true);
        publishStatus();
        handler.postDelayed(speakTick, SPEAK_FIRST_MS);
    }

    private String englishString(int resId) {
        Configuration c = new Configuration(getResources().getConfiguration());
        c.setLocale(Locale.ENGLISH);
        return createConfigurationContext(c).getString(resId);
    }

    private void stopSpokenAlert() {
        handler.removeCallbacks(speakTick);
        ttsReady = false;
        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (Exception ignored) {
                // Engine already gone.
            }
            tts = null;
        }
        duckSiren(false);
    }

    // ---- Volume-key deactivation: UP, DOWN, UP, DOWN ----

    private void registerVolumeReceiver() {
        if (volumeReceiverRegistered) return;
        ContextCompat.registerReceiver(this, volumeReceiver, new IntentFilter(VOLUME_CHANGED_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED);
        volumeReceiverRegistered = true;
    }

    private void unregisterVolumeReceiver() {
        if (!volumeReceiverRegistered) return;
        try {
            unregisterReceiver(volumeReceiver);
        } catch (IllegalArgumentException ignored) {
            // Already gone.
        }
        volumeReceiverRegistered = false;
    }

    private void onVolumeChanged(Intent intent) {
        int stream = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1);
        int value = intent.getIntExtra(EXTRA_VOLUME_STREAM_VALUE, -1);
        int prev = intent.getIntExtra(EXTRA_PREV_VOLUME_STREAM_VALUE, -1);
        if (stream < 0 || value < 0 || prev < 0 || value == prev) return;

        long now = SystemClock.elapsedRealtime();
        if (stream == selfStream && value == selfValue && now - selfAt < 1_000) {
            selfStream = -1; // our own re-anchoring, not a key press
            return;
        }
        // Aliased streams (ring/notification/system) report the same key press several times.
        if (now - lastVolumeEventAt < 250) return;
        lastVolumeEventAt = now;

        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am != null) saveVolume(am, stream, prev);
        onVolumePress(value > prev, now);
        if (am != null && state != State.IDLE) reanchor(am, stream, value);
    }

    private void onVolumePress(boolean up, long now) {
        long timeout = prefNumber(Prefs.get(this), Prefs.INPUT_TIMEOUT_MS, Prefs.DEFAULT_INPUT_TIMEOUT_MS);
        if (volumeSeqIndex > 0 && now - lastVolumePressAt > timeout) volumeSeqIndex = 0;
        lastVolumePressAt = now;
        boolean expectedUp = volumeSeqIndex % 2 == 0;
        if (up == expectedUp) {
            volumeSeqIndex++;
        } else {
            volumeSeqIndex = up ? 1 : 0;
        }
        if (volumeSeqIndex >= 4) {
            volumeSeqIndex = 0;
            Log.i(TAG, "Volume pattern deactivation");
            onStopRequest();
        }
    }

    /** Keeps the adjusted stream away from its limits so the next press in either direction registers. */
    private void reanchor(AudioManager am, int stream, int value) {
        int max = am.getStreamMaxVolume(stream);
        int min = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? am.getStreamMinVolume(stream) : 0;
        if (max - min < 2) return;
        if (stream == AudioManager.STREAM_ALARM && siren != null) {
            if (value != max - 1) setVolumeSelf(am, stream, max - 1); // keep the siren loud
        } else if (value >= max) {
            setVolumeSelf(am, stream, max - 1);
        } else if (value <= min) {
            setVolumeSelf(am, stream, min + 1);
        }
    }

    private void saveVolume(AudioManager am, int stream, int value) {
        if (!savedVolumes.containsKey(stream)) savedVolumes.put(stream, value);
    }

    private void setVolumeSelf(AudioManager am, int stream, int value) {
        try {
            selfStream = stream;
            selfValue = value;
            selfAt = SystemClock.elapsedRealtime();
            am.setStreamVolume(stream, value, 0);
        } catch (Exception e) {
            // Do Not Disturb can forbid changing some streams.
            Log.w(TAG, "setStreamVolume(" + stream + ") failed: " + e.getMessage());
        }
    }

    private void restoreVolumes() {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            for (Map.Entry<Integer, Integer> e : savedVolumes.entrySet()) {
                try {
                    am.setStreamVolume(e.getKey(), e.getValue(), 0);
                } catch (Exception ex) {
                    Log.w(TAG, "Couldn't restore volume: " + ex.getMessage());
                }
            }
        }
        savedVolumes.clear();
    }

    // ---- Evidence photos ----

    private void startEvidenceCapture() {
        boolean hasCamera = getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
        if (!hasCamera || !granted(this, Manifest.permission.CAMERA)) {
            status.putInt(PanicActivity.EXTRA_CAMERA, PanicActivity.STEP_FAILED);
            return;
        }
        status.putInt(PanicActivity.EXTRA_CAMERA, PanicActivity.STEP_PENDING);
        capture = new EvidenceCapture(incident);
        capture.start();
    }

    /** Called on the camera thread with a JPEG; saves it and queues it for Telegram/email. */
    private void saveEvidence(int incidentId, byte[] jpeg, boolean front) {
        try {
            File base = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            File dir = new File(base != null ? base : getFilesDir(), "Emergency");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                Log.e(TAG, "Can't create " + dir);
                return;
            }
            long now = System.currentTimeMillis();
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date(now));
            File file = new File(dir, (front ? "FRONT_" : "BACK_") + stamp + ".jpg");
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(jpeg);
            }
            ProfileDbHelper db = new ProfileDbHelper(this);
            try {
                db.insertEmergencyImage(thumbnail(jpeg), front ? "front" : "back", now, file.getAbsolutePath());
            } finally {
                db.close();
            }
            final int count = photoCount.incrementAndGet();
            scheduleImageUpload(this);
            final File saved = file;
            handler.post(() -> {
                if (incidentId != incident) return;
                SafetyHooks.sosPhoto(this, saved, front);
                status.putInt(PanicActivity.EXTRA_CAMERA, PanicActivity.STEP_DONE);
                status.putInt(PanicActivity.EXTRA_PHOTOS, count);
                publishStatus();
            });
        } catch (Exception e) {
            Log.e(TAG, "Saving evidence failed", e);
        }
    }

    /** Small JPEG preview for the database (full image stays on disk). */
    @Nullable
    private static byte[] thumbnail(byte[] jpeg) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 720) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opts);
        if (bmp == null) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 75, out);
        bmp.recycle();
        return out.toByteArray();
    }

    /**
     * Captures front then back camera, one at a time (open, capture, release, next), every
     * {@link #CAPTURE_INTERVAL_MS} until the SOS ends or {@link #MAX_PHOTOS} are taken.
     * Uses the legacy Camera API with an offscreen SurfaceTexture on its own thread. While a camera
     * is open the strobe leaves the torch alone ({@link #evidenceCameraBusy}).
     */
    @SuppressWarnings("deprecation")
    private final class EvidenceCapture implements Runnable {
        private final int incidentId;
        private final ArrayDeque<Integer> queue = new ArrayDeque<>();
        private HandlerThread thread;
        private Handler camHandler;
        private Camera camera;
        private SurfaceTexture texture;
        private volatile boolean active;

        private final Runnable shotTimeout = () -> {
            Log.w(TAG, "Camera capture timed out");
            releaseCamera();
            captureNext();
        };

        EvidenceCapture(int incidentId) {
            this.incidentId = incidentId;
        }

        void start() {
            thread = new HandlerThread("ns-evidence");
            thread.start();
            camHandler = new Handler(thread.getLooper());
            active = true;
            camHandler.post(this);
        }

        void stop() {
            active = false;
            camHandler.removeCallbacksAndMessages(null);
            camHandler.post(() -> {
                releaseCamera();
                thread.quitSafely();
            });
        }

        /** One capture cycle. */
        @Override
        public void run() {
            if (!active || photoCount.get() >= MAX_PHOTOS) return;
            queue.clear();
            int front = findCamera(Camera.CameraInfo.CAMERA_FACING_FRONT);
            int back = findCamera(Camera.CameraInfo.CAMERA_FACING_BACK);
            if (front >= 0) queue.add(front);
            if (back >= 0) queue.add(back);
            captureNext();
        }

        private void captureNext() {
            if (!active) return;
            Integer id = queue.poll();
            if (id == null || photoCount.get() >= MAX_PHOTOS) {
                if (photoCount.get() < MAX_PHOTOS) camHandler.postDelayed(this, CAPTURE_INTERVAL_MS);
                return;
            }
            try {
                Camera.CameraInfo info = new Camera.CameraInfo();
                Camera.getCameraInfo(id, info);
                final boolean front = info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT;
                evidenceCameraBusy = true;
                camera = Camera.open(id);
                try {
                    camera.enableShutterSound(false); // matters for a silent SOS
                } catch (Exception ignored) {
                    // Some regions force the shutter sound.
                }
                Camera.Parameters params = camera.getParameters();
                Camera.Size size = pickPictureSize(params.getSupportedPictureSizes());
                if (size != null) params.setPictureSize(size.width, size.height);
                params.setJpegQuality(85);
                params.setRotation(info.orientation);
                List<String> focus = params.getSupportedFocusModes();
                if (focus != null && focus.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                    params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                }
                camera.setParameters(params);
                texture = new SurfaceTexture(10);
                camera.setPreviewTexture(texture);
                camera.startPreview();
                // Give auto-exposure a moment before capturing.
                camHandler.postDelayed(() -> shoot(front), 700);
            } catch (Exception e) {
                Log.e(TAG, "Camera " + id + " unavailable: " + e.getMessage());
                releaseCamera();
                captureNext();
            }
        }

        private void shoot(final boolean front) {
            if (!active || camera == null) {
                releaseCamera();
                return;
            }
            camHandler.postDelayed(shotTimeout, 5_000);
            try {
                camera.takePicture(null, null, (data, cam) -> {
                    camHandler.removeCallbacks(shotTimeout);
                    releaseCamera();
                    if (data != null && active) saveEvidence(incidentId, data, front);
                    captureNext();
                });
            } catch (Exception e) {
                Log.e(TAG, "takePicture failed: " + e.getMessage());
                camHandler.removeCallbacks(shotTimeout);
                releaseCamera();
                captureNext();
            }
        }

        private void releaseCamera() {
            if (camera != null) {
                try {
                    camera.stopPreview();
                } catch (Exception ignored) {
                    // Preview may not have started.
                }
                camera.release();
                camera = null;
            }
            if (texture != null) {
                texture.release();
                texture = null;
            }
            evidenceCameraBusy = false;
        }

        private int findCamera(int facing) {
            try {
                Camera.CameraInfo info = new Camera.CameraInfo();
                for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
                    Camera.getCameraInfo(i, info);
                    if (info.facing == facing) return i;
                }
            } catch (Exception e) {
                Log.e(TAG, "Camera lookup failed: " + e.getMessage());
            }
            return -1;
        }

        /** Closest size to ~2 MP: plenty for evidence, small enough to upload on a weak network. */
        @Nullable
        private Camera.Size pickPictureSize(@Nullable List<Camera.Size> sizes) {
            if (sizes == null || sizes.isEmpty()) return null;
            int target = 1920 * 1080;
            Camera.Size best = sizes.get(0);
            for (Camera.Size s : sizes) {
                if (Math.abs(s.width * s.height - target) < Math.abs(best.width * best.height - target)) best = s;
            }
            return best;
        }
    }

    // =====================================================================================
    // Misc helpers
    // =====================================================================================

    private void acquireIncidentWakeLock() {
        if (incidentWakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            incidentWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NaariShakti::Sos");
            incidentWakeLock.setReferenceCounted(false);
        }
        incidentWakeLock.acquire(INCIDENT_WAKELOCK_MS);
    }

    @SuppressWarnings("deprecation")
    private void vibrate(long ms) {
        if (triggerSilent) return;
        Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            vibrator.vibrate(ms);
        }
    }

    private void updateProtectionNotification() {
        notifySafely(this, PROTECTION_NOTIFICATION_ID, buildProtectionNotification(this));
    }

    // =====================================================================================
    // Static helpers shared with the other engine components
    // =====================================================================================

    /** The persistent notification shared by this service and VoskService (same id = one notification). */
    public static Notification buildProtectionNotification(Context ctx) {
        createChannels(ctx);
        int presses = (int) prefNumber(Prefs.get(ctx), Prefs.REQUIRED_PRESSES, Prefs.DEFAULT_REQUIRED_PRESSES);
        String text = granted(ctx, Manifest.permission.RECORD_AUDIO)
                ? ctx.getString(R.string.eng_protect_text, Prefs.getTriggerPhrase(ctx), presses)
                : ctx.getString(R.string.eng_protect_text_no_voice, presses);

        PendingIntent open = PendingIntent.getActivity(ctx, 0,
                new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent sos = new Intent(ctx, VoiceRecognitionService.class)
                .setAction(ProtectionController.ACTION_TRIGGER_PANIC)
                .putExtra(ProtectionController.EXTRA_SOURCE, "notification");
        Intent off = new Intent(ctx, VoiceRecognitionService.class).setAction(ACTION_STOP_PROTECTION);

        return new NotificationCompat.Builder(ctx, CHANNEL_PROTECTION)
                .setSmallIcon(R.drawable.eng_ic_shield)
                .setColor(ContextCompat.getColor(ctx, R.color.ns_rose))
                .setContentTitle(ctx.getString(R.string.eng_protect_title))
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(open)
                .addAction(R.drawable.eng_ic_siren, ctx.getString(R.string.eng_action_sos_now), servicePending(ctx, 1, sos))
                .addAction(R.drawable.eng_ic_call, ctx.getString(R.string.en_action_fake_call, 10),
                        FakeCall.scheduleIntent(ctx, 10))
                .addAction(R.drawable.eng_ic_close, ctx.getString(R.string.eng_action_turn_off), servicePending(ctx, 2, off))
                .build();
    }

    private static PendingIntent servicePending(Context ctx, int requestCode, Intent intent) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return PendingIntent.getForegroundService(ctx, requestCode, intent, flags);
        }
        return PendingIntent.getService(ctx, requestCode, intent, flags);
    }

    /** Creates every notification channel the app uses (idempotent). */
    public static void createChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        List<NotificationChannel> channels = new ArrayList<>();

        NotificationChannel protection = new NotificationChannel(CHANNEL_PROTECTION,
                ctx.getString(R.string.eng_channel_protection), NotificationManager.IMPORTANCE_LOW);
        protection.setDescription(ctx.getString(R.string.eng_channel_protection_desc));
        protection.setShowBadge(false);
        channels.add(protection);

        NotificationChannel sos = new NotificationChannel(CHANNEL_SOS,
                ctx.getString(R.string.eng_channel_sos), NotificationManager.IMPORTANCE_HIGH);
        sos.setDescription(ctx.getString(R.string.eng_channel_sos_desc));
        sos.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        sos.setSound(null, null); // the siren provides the sound
        sos.enableVibration(true);
        channels.add(sos);

        NotificationChannel alerts = new NotificationChannel(CHANNEL_ALERTS,
                ctx.getString(R.string.eng_channel_alerts), NotificationManager.IMPORTANCE_HIGH);
        alerts.setDescription(ctx.getString(R.string.eng_channel_alerts_desc));
        channels.add(alerts);

        NotificationChannel live = new NotificationChannel(CHANNEL_LIVE,
                ctx.getString(R.string.eng_channel_live), NotificationManager.IMPORTANCE_LOW);
        live.setDescription(ctx.getString(R.string.eng_channel_live_desc));
        channels.add(live);

        NotificationChannel geofence = new NotificationChannel(CHANNEL_GEOFENCE,
                ctx.getString(R.string.eng_channel_geofence), NotificationManager.IMPORTANCE_DEFAULT);
        geofence.setDescription(ctx.getString(R.string.eng_channel_geofence_desc));
        channels.add(geofence);

        nm.createNotificationChannels(channels);
    }

    /** Posts a notification if the app may (POST_NOTIFICATIONS on Android 13+). */
    public static void notifySafely(Context ctx, int id, Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && !granted(ctx, Manifest.permission.POST_NOTIFICATIONS)) {
            Log.w(TAG, "Notification permission not granted; notification " + id + " not shown");
            return;
        }
        try {
            NotificationManagerCompat.from(ctx).notify(id, notification);
        } catch (SecurityException e) {
            Log.w(TAG, "notify failed: " + e.getMessage());
        }
    }

    /**
     * Sends {@code text} to every number, split into parts when it exceeds one SMS. Each message
     * is tracked with sent-result PendingIntents; failures go to a persisted retry queue
     * ({@link SmsOutbox}) that is retried when the network comes back and every minute during an SOS.
     * @return how many numbers the message was handed to the radio for.
     */
    public static int sendSmsToAll(Context ctx, List<String> numbers, String text) {
        if (numbers.isEmpty()) return 0;
        if (!granted(ctx, Manifest.permission.SEND_SMS)) {
            Log.e(TAG, "SEND_SMS not granted");
            return 0;
        }
        if (!ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            Log.e(TAG, "No telephony on this device");
            return 0;
        }
        SmsManager sms = smsManager(ctx);
        int ok = 0;
        for (String number : numbers) {
            if (TextUtils.isEmpty(number)) continue;
            if (SmsOutbox.dispatch(ctx, sms, SmsOutbox.Entry.create(number, text))) ok++;
        }
        return ok;
    }

    @SuppressWarnings("deprecation")
    private static SmsManager smsManager(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SmsManager manager = ctx.getSystemService(SmsManager.class);
            if (manager != null) return manager;
        }
        return SmsManager.getDefault();
    }

    public static String mapsLink(Location loc) {
        return String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f",
                loc.getLatitude(), loc.getLongitude());
    }

    public static boolean hasLocationPermission(Context ctx) {
        return granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
                || granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION);
    }

    static boolean granted(Context ctx, String permission) {
        return ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED;
    }

    /** Reads a boolean pref, tolerating values stored as strings. */
    static boolean prefBool(Context ctx, String key, boolean def) {
        SharedPreferences prefs = Prefs.get(ctx);
        try {
            return prefs.getBoolean(key, def);
        } catch (ClassCastException e) {
            try {
                String s = prefs.getString(key, null);
                return s == null ? def : Boolean.parseBoolean(s.trim());
            } catch (ClassCastException e2) {
                return def;
            }
        }
    }

    /** Reads a numeric pref whether it was stored as long, int or string. */
    public static long prefNumber(SharedPreferences prefs, String key, long def) {
        try {
            return prefs.getLong(key, def);
        } catch (ClassCastException notLong) {
            try {
                return prefs.getInt(key, (int) def);
            } catch (ClassCastException notInt) {
                try {
                    return Long.parseLong(prefs.getString(key, String.valueOf(def)).trim());
                } catch (Exception e) {
                    return def;
                }
            }
        }
    }

    public static void scheduleImageUpload(Context ctx) {
        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(SendUnsentImagesWorker.class)
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build();
        WorkManager.getInstance(ctx.getApplicationContext())
                .enqueueUniqueWork(UPLOAD_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, work);
    }

    // =====================================================================================
    // SMS delivery tracking + persisted retry queue
    // =====================================================================================

    /**
     * Every SMS is sent with one "sent" PendingIntent per part. When all parts reported back, a
     * failed message is written to {@code files/sms_outbox.json} and retried later (network back
     * in service, every 60 s during an SOS) up to {@link #MAX_ATTEMPTS} times, for at most an hour.
     */
    static final class SmsOutbox {
        static final String ACTION_SENT = "com.example.naarishakti.ACTION_SMS_SENT";
        private static final String EXTRA_ID = "ns_sms_id";
        private static final String FILE = "sms_outbox.json";
        private static final int MAX_ATTEMPTS = 10;
        private static final long MAX_AGE_MS = 60 * 60_000L;

        private static final Object LOCK = new Object();
        private static final AtomicInteger REQUEST_CODES =
                new AtomicInteger(20_000 + (int) (SystemClock.uptimeMillis() % 100_000));
        private static final Map<String, Flight> IN_FLIGHT = new HashMap<>();
        private static volatile boolean receiverRegistered;

        private SmsOutbox() {}

        static final class Entry {
            final String id;
            final String number;
            final String text;
            final long created;
            int attempts;

            Entry(String id, String number, String text, long created, int attempts) {
                this.id = id;
                this.number = number;
                this.text = text;
                this.created = created;
                this.attempts = attempts;
            }

            static Entry create(String number, String text) {
                return new Entry(UUID.randomUUID().toString(), number, text, System.currentTimeMillis(), 0);
            }
        }

        private static final class Flight {
            final Entry entry;
            final int parts;
            int results;
            boolean failed;

            Flight(Entry entry, int parts) {
                this.entry = entry;
                this.parts = parts;
            }
        }

        private static final BroadcastReceiver RESULT_RECEIVER = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String id = intent.getStringExtra(EXTRA_ID);
                if (id == null) return;
                int code = getResultCode();
                Entry done;
                boolean failed;
                synchronized (LOCK) {
                    Flight f = IN_FLIGHT.get(id);
                    if (f == null) return;
                    f.results++;
                    if (code != Activity.RESULT_OK) f.failed = true;
                    if (f.results < f.parts) return;
                    IN_FLIGHT.remove(id);
                    done = f.entry;
                    failed = f.failed;
                }
                if (failed) {
                    Log.w(TAG, "SMS to " + done.number + " failed (result " + code + "), queued for retry");
                    onFailed(context, done);
                } else {
                    onDelivered(context, done);
                }
            }
        };

        private static void ensureReceiver(Context app) {
            if (receiverRegistered) return;
            synchronized (LOCK) {
                if (receiverRegistered) return;
                ContextCompat.registerReceiver(app, RESULT_RECEIVER, new IntentFilter(ACTION_SENT),
                        ContextCompat.RECEIVER_NOT_EXPORTED);
                receiverRegistered = true;
            }
        }

        /** Hands one message to the radio. @return false if the radio refused it (it is queued). */
        static boolean dispatch(Context ctx, SmsManager sms, Entry e) {
            Context app = ctx.getApplicationContext();
            try {
                ensureReceiver(app);
                ArrayList<String> parts = sms.divideMessage(e.text);
                if (parts == null || parts.isEmpty()) {
                    parts = new ArrayList<>();
                    parts.add(e.text);
                }
                ArrayList<PendingIntent> sent = new ArrayList<>();
                for (int i = 0; i < parts.size(); i++) sent.add(sentIntent(app, e.id));
                synchronized (LOCK) {
                    IN_FLIGHT.put(e.id, new Flight(e, parts.size()));
                }
                if (parts.size() > 1) {
                    sms.sendMultipartTextMessage(e.number, null, parts, sent, null);
                } else {
                    sms.sendTextMessage(e.number, null, parts.get(0), sent.get(0), null);
                }
                return true;
            } catch (Exception ex) {
                Log.e(TAG, "SMS to " + e.number + " failed: " + ex.getMessage());
                synchronized (LOCK) {
                    IN_FLIGHT.remove(e.id);
                }
                onFailed(app, e);
                return false;
            }
        }

        private static PendingIntent sentIntent(Context app, String id) {
            Intent i = new Intent(ACTION_SENT).setPackage(app.getPackageName()).putExtra(EXTRA_ID, id);
            int flags = PendingIntent.FLAG_ONE_SHOT;
            // Mutable so the telephony stack can fill in the error details.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
            return PendingIntent.getBroadcast(app, REQUEST_CODES.incrementAndGet(), i, flags);
        }

        /** Retries every queued message that is not currently in flight. */
        static void flush(Context ctx) {
            Context app = ctx.getApplicationContext();
            if (!granted(app, Manifest.permission.SEND_SMS)
                    || !app.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
                return;
            }
            List<Entry> due = new ArrayList<>();
            synchronized (LOCK) {
                List<Entry> queue = load(app);
                if (queue.isEmpty()) return;
                long now = System.currentTimeMillis();
                boolean pruned = false;
                Iterator<Entry> it = queue.iterator();
                while (it.hasNext()) {
                    Entry e = it.next();
                    if (e.attempts >= MAX_ATTEMPTS || now - e.created > MAX_AGE_MS) {
                        it.remove();
                        pruned = true;
                    } else if (!IN_FLIGHT.containsKey(e.id)) {
                        due.add(e);
                    }
                }
                if (pruned) save(app, queue);
            }
            if (due.isEmpty()) return;
            Log.i(TAG, "Retrying " + due.size() + " SMS");
            SmsManager sms = smsManager(app);
            for (Entry e : due) dispatch(app, sms, e);
        }

        private static void onFailed(Context ctx, Entry e) {
            e.attempts++;
            synchronized (LOCK) {
                List<Entry> queue = load(ctx);
                removeId(queue, e.id);
                if (e.attempts < MAX_ATTEMPTS && System.currentTimeMillis() - e.created <= MAX_AGE_MS) {
                    queue.add(e);
                } else {
                    Log.w(TAG, "Giving up on SMS to " + e.number + " after " + e.attempts + " attempts");
                }
                save(ctx, queue);
            }
        }

        private static void onDelivered(Context ctx, Entry e) {
            synchronized (LOCK) {
                List<Entry> queue = load(ctx);
                if (removeId(queue, e.id)) save(ctx, queue);
            }
        }

        private static boolean removeId(List<Entry> queue, String id) {
            boolean removed = false;
            Iterator<Entry> it = queue.iterator();
            while (it.hasNext()) {
                if (it.next().id.equals(id)) {
                    it.remove();
                    removed = true;
                }
            }
            return removed;
        }

        private static List<Entry> load(Context ctx) {
            List<Entry> out = new ArrayList<>();
            File f = new File(ctx.getFilesDir(), FILE);
            if (!f.isFile()) return out;
            try (FileInputStream in = new FileInputStream(f)) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                int n;
                while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
                JSONArray arr = new JSONArray(buf.toString("UTF-8"));
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    out.add(new Entry(o.getString("id"), o.getString("number"), o.getString("text"),
                            o.getLong("created"), o.optInt("attempts", 0)));
                }
            } catch (Exception e) {
                Log.w(TAG, "SMS outbox unreadable, starting fresh: " + e.getMessage());
            }
            return out;
        }

        private static void save(Context ctx, List<Entry> queue) {
            File f = new File(ctx.getFilesDir(), FILE);
            if (queue.isEmpty()) {
                if (f.exists() && !f.delete()) Log.w(TAG, "Couldn't clear SMS outbox");
                return;
            }
            try {
                JSONArray arr = new JSONArray();
                for (Entry e : queue) {
                    arr.put(new JSONObject()
                            .put("id", e.id)
                            .put("number", e.number)
                            .put("text", e.text)
                            .put("created", e.created)
                            .put("attempts", e.attempts));
                }
                File tmp = new File(ctx.getFilesDir(), FILE + ".tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(arr.toString().getBytes("UTF-8"));
                }
                if (!tmp.renameTo(f)) {
                    try (FileOutputStream out = new FileOutputStream(f)) {
                        out.write(arr.toString().getBytes("UTF-8"));
                    }
                }
            } catch (JSONException | java.io.IOException e) {
                Log.e(TAG, "Couldn't persist SMS outbox", e);
            }
        }
    }

    /** Sends unsent evidence photos to Telegram and email; retries while a configured channel fails. */
    public static class SendUnsentImagesWorker extends Worker {

        private static final int MAX_ATTEMPTS = 5;

        public SendUnsentImagesWorker(@NonNull Context context, @NonNull WorkerParameters params) {
            super(context, params);
        }

        @NonNull
        @Override
        public Result doWork() {
            Context ctx = getApplicationContext();
            ProfileDbHelper db = new ProfileDbHelper(ctx);
            EmailSender email = new EmailSender(ctx);
            boolean telegram = TelegramBot.isConfigured() && !Prefs.getTelegramEmergencyChatIds(ctx).isEmpty();
            boolean mail = email.isConfigured();
            if (!telegram && !mail) {
                Log.w(TAG, "No Telegram emergency contact or email configured; photos stay on the device");
                return Result.success();
            }

            boolean anyFailed = false;
            for (ImageData image : db.getUnsentImages()) {
                File file = new File(image.imagePath == null ? "" : image.imagePath);
                if (!file.exists()) {
                    db.markImageAsFailed(image.id);
                    continue;
                }
                String camera = ctx.getString("front".equals(image.imageType)
                        ? R.string.eng_camera_front : R.string.eng_camera_back);
                String when = DateFormat.getTimeFormat(ctx).format(new Date(image.timestamp));
                String body = ctx.getString(R.string.eng_email_body, camera, when);

                boolean delivered = false;
                if (telegram && TelegramBot.sendPhotoToEmergency(ctx, file, body) > 0) delivered = true;
                if (mail && email.send(ctx.getString(R.string.eng_email_subject, camera), body, file)) delivered = true;

                if (delivered) {
                    db.markImageAsSent(image.id);
                } else {
                    anyFailed = true;
                }
            }
            db.close();
            if (!anyFailed) return Result.success();
            return getRunAttemptCount() + 1 < MAX_ATTEMPTS ? Result.retry() : Result.failure();
        }
    }

    /** Row of the emergency_images table (used by ProfileDbHelper.getUnsentImages). */
    public static class ImageData {
        public long id;
        public String imagePath;
        public String imageType;
        public boolean sent;
        public long timestamp;

        public ImageData(long id, String imagePath, String imageType, long timestamp, boolean sent) {
            this.id = id;
            this.imagePath = imagePath;
            this.imageType = imageType;
            this.timestamp = timestamp;
            this.sent = sent;
        }
    }
}
