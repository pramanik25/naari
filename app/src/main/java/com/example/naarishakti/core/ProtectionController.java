package com.example.naarishakti.core;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.example.naarishakti.VoiceRecognitionService;
import com.example.naarishakti.VoskService;

/**
 * The one entry point screens use to control protection. Services report their own running
 * state back through {@link #reportServiceState} so the UI never has to poll ActivityManager.
 */
public final class ProtectionController {

    private static final String TAG = "ProtectionController";

    /** Local broadcast fired whenever protection or panic state changes. */
    public static final String ACTION_STATE_CHANGED = "com.example.naarishakti.ACTION_SERVICE_STATE_CHANGED";

    /** Sent to VoiceRecognitionService to fire the full SOS flow immediately. */
    public static final String ACTION_TRIGGER_PANIC = "com.example.naarishakti.ACTION_TRIGGER_PANIC";
    /** Sent to VoiceRecognitionService to end an active SOS (siren, overlay, capture). */
    public static final String ACTION_STOP_PANIC = "com.example.naarishakti.ACTION_STOP_PANIC";
    /** Optional String extra on ACTION_TRIGGER_PANIC describing the source ("sos_button", "power", "shake", "voice"). */
    public static final String EXTRA_SOURCE = "source";

    /** Extras understood by VoskService.onStartCommand. */
    public static final String EXTRA_START = "start_service";
    public static final String EXTRA_STOP = "stop_service";

    private static volatile boolean engineRunning;
    private static volatile boolean listenerRunning;
    private static volatile boolean panicActive;

    private ProtectionController() {}

    /** Start both protection services and remember that the user wants protection on. */
    public static void start(Context context) {
        Context app = context.getApplicationContext();
        Prefs.get(app).edit().putBoolean(Prefs.PROTECTION_ENABLED, true).apply();
        try {
            ContextCompat.startForegroundService(app, new Intent(app, VoiceRecognitionService.class));
            Intent vosk = new Intent(app, VoskService.class).putExtra(EXTRA_START, true);
            ContextCompat.startForegroundService(app, vosk);
        } catch (Exception e) {
            // Background-start restrictions (Android 12+) or missing FGS permissions.
            Log.e(TAG, "Unable to start protection services", e);
        }
    }

    /** Stop both protection services and remember that the user turned protection off. */
    public static void stop(Context context) {
        Context app = context.getApplicationContext();
        Prefs.get(app).edit().putBoolean(Prefs.PROTECTION_ENABLED, false).apply();
        app.stopService(new Intent(app, VoskService.class));
        app.stopService(new Intent(app, VoiceRecognitionService.class));
    }

    /** Fire the SOS flow now (SMS + location, call, siren, overlay, evidence capture). */
    public static void triggerPanic(Context context, String source) {
        Context app = context.getApplicationContext();
        Intent i = new Intent(app, VoiceRecognitionService.class)
                .setAction(ACTION_TRIGGER_PANIC)
                .putExtra(EXTRA_SOURCE, source);
        try {
            ContextCompat.startForegroundService(app, i);
        } catch (Exception e) {
            // Android 12+ may refuse a foreground-service start from the background. When the engine
            // is already running as a foreground service a plain start is allowed and delivers it.
            Log.w(TAG, "startForegroundService refused, retrying with startService", e);
            try {
                app.startService(i);
            } catch (Exception e2) {
                Log.e(TAG, "Unable to trigger panic", e2);
            }
        }
    }

    /** End an active SOS. */
    public static void stopPanic(Context context) {
        Context app = context.getApplicationContext();
        Intent i = new Intent(app, VoiceRecognitionService.class).setAction(ACTION_STOP_PANIC);
        try {
            app.startService(i);
        } catch (Exception e) {
            Log.e(TAG, "Unable to stop panic", e);
        }
    }

    public static boolean isProtectionActive() {
        return engineRunning || listenerRunning;
    }

    public static boolean isListening() {
        return listenerRunning;
    }

    public static boolean isPanicActive() {
        return panicActive;
    }

    public static boolean isProtectionWanted(Context context) {
        return Prefs.get(context).getBoolean(Prefs.PROTECTION_ENABLED, false);
    }

    /** Called by VoiceRecognitionService (engine=true) and VoskService (engine=false) in onCreate/onDestroy. */
    public static void reportServiceState(Context context, boolean engine, boolean running) {
        if (engine) engineRunning = running; else listenerRunning = running;
        broadcast(context);
    }

    /** Called by VoiceRecognitionService when an SOS starts or ends. */
    public static void reportPanicState(Context context, boolean active) {
        panicActive = active;
        broadcast(context);
    }

    private static void broadcast(Context context) {
        LocalBroadcastManager.getInstance(context.getApplicationContext())
                .sendBroadcast(new Intent(ACTION_STATE_CHANGED));
    }
}
