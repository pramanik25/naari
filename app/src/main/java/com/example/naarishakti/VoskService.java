package com.example.naarishakti;

import android.Manifest;
import android.app.Notification;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.example.naarishakti.core.Prefs;
import com.example.naarishakti.core.ProtectionController;

import org.json.JSONObject;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.util.Locale;

import Services.BootReceiver;
import Utils.VoskModelLoader;

/**
 * Offline, continuous trigger-phrase listener. Vosk is the only recogniser that touches the mic, so
 * nothing competes for audio. When the phrase is heard it asks the engine to start the SOS flow via
 * {@link ProtectionController#triggerPanic}.
 */
public class VoskService extends Service {

    private static final String TAG = "VoskService";

    /** Broadcast (global or local) sent by the trigger word screen after saving a new phrase. */
    public static final String ACTION_TRIGGER_PHRASE_UPDATED = "com.example.naarishakti.ACTION_TRIGGER_PHRASE_UPDATED";

    private static final String MODEL_ASSET_NAME = "vosk-model-small-en-in-0.4.zip";
    private static final String MODEL_DIR = "vosk_model_main";
    private static final String MODEL_NAME = "vosk-model-small-en-in-0.4";

    private static final int SAMPLE_RATE = 16000;
    private static final int READ_BYTES = 4096; // ~128 ms of 16 kHz mono PCM16
    /** One utterance fires once: matches are ignored for this long after a trigger. */
    private static final long MATCH_COOLDOWN_MS = 10_000;
    private static final long RESTART_DELAY_MS = 5_000;
    private static final int MAX_CONSECUTIVE_FAILURES = 6;

    private volatile String triggerPhrase = Prefs.DEFAULT_TRIGGER_PHRASE;
    private volatile boolean singleWordPhrase = true;
    private volatile boolean running;
    private boolean foreground;
    private volatile long cooldownUntil;
    private volatile boolean resetRecognizer;

    private Thread listenThread;
    private Model model;
    private PowerManager.WakeLock wakeLock;
    private int consecutiveFailures;

    private final BroadcastReceiver settingsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            loadPhrase();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        ProtectionController.reportServiceState(this, false, true);
        foreground = goForeground();
        if (!foreground) {
            stopSelf();
            return;
        }
        LibVosk.setLogLevel(LogLevel.WARNINGS);
        loadPhrase();

        IntentFilter filter = new IntentFilter(Prefs.ACTION_SETTINGS_UPDATED);
        filter.addAction(ACTION_TRIGGER_PHRASE_UPDATED);
        LocalBroadcastManager.getInstance(this).registerReceiver(settingsReceiver, filter);
        // The trigger word screen also sends the phrase update as a normal app broadcast.
        ContextCompat.registerReceiver(this, settingsReceiver,
                new IntentFilter(ACTION_TRIGGER_PHRASE_UPDATED), ContextCompat.RECEIVER_NOT_EXPORTED);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NaariShakti::VoskListener");
            wakeLock.setReferenceCounted(false);
        }
    }

    /** Runs as a microphone foreground service; returns false if Android refuses. */
    private boolean goForeground() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO not granted; voice trigger unavailable");
            return false;
        }
        Notification notification = VoiceRecognitionService.buildProtectionNotification(this);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(VoiceRecognitionService.PROTECTION_NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(VoiceRecognitionService.PROTECTION_NOTIFICATION_ID, notification);
            }
            return true;
        } catch (Exception e) {
            // Background start restrictions (boot, sticky restart, alarms on Android 12+/14).
            Log.e(TAG, "Unable to start microphone foreground service", e);
            if (ProtectionController.isProtectionWanted(this)) {
                BootReceiver.showResumeNotification(this);
            }
            return false;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!foreground) return START_NOT_STICKY;
        if (intent != null && intent.getBooleanExtra(ProtectionController.EXTRA_STOP, false)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent == null && !ProtectionController.isProtectionWanted(this)) {
            // Sticky restart after the user already turned protection off.
            stopSelf();
            return START_NOT_STICKY;
        }
        startListening();
        return START_STICKY;
    }

    private void loadPhrase() {
        String phrase = normalize(Prefs.getTriggerPhrase(this));
        if (TextUtils.isEmpty(phrase)) phrase = Prefs.DEFAULT_TRIGGER_PHRASE;
        triggerPhrase = phrase;
        singleWordPhrase = !phrase.contains(" ");
        Log.d(TAG, "Trigger phrase: \"" + phrase + "\"");
    }

    private synchronized void startListening() {
        if (running) return;
        running = true;
        consecutiveFailures = 0;
        if (wakeLock != null) wakeLock.acquire();
        listenThread = new Thread(this::listenLoop, "ns-vosk");
        listenThread.start();
    }

    private void listenLoop() {
        while (running) {
            boolean ok = listenOnce();
            if (!running) break;
            consecutiveFailures = ok ? 0 : consecutiveFailures + 1;
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                Log.e(TAG, "Voice listener keeps failing; giving up until protection restarts");
                break;
            }
            SystemClock.sleep(RESTART_DELAY_MS * Math.max(1, consecutiveFailures));
        }
        if (running) stopSelf();
    }

    /** One recording session. Returns false if it ended because of an error. */
    private boolean listenOnce() {
        if (model == null && !loadModel()) return false;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO revoked");
            return false;
        }

        Recognizer recognizer = null;
        AudioRecord audio = null;
        try {
            recognizer = new Recognizer(model, SAMPLE_RATE);
            int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            audio = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minBuffer, READ_BYTES * 4));
            if (audio.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialise");
                return false;
            }
            audio.startRecording();
            Log.d(TAG, "Listening for trigger phrase");

            byte[] buffer = new byte[READ_BYTES];
            while (running) {
                int read = audio.read(buffer, 0, buffer.length);
                if (read < 0) {
                    Log.e(TAG, "AudioRecord read error " + read);
                    return false;
                }
                if (read == 0) continue;

                // While an SOS runs (or right after a match) keep draining the mic but don't match.
                if (ProtectionController.isPanicActive() || SystemClock.elapsedRealtime() < cooldownUntil) {
                    resetRecognizer = true;
                    continue;
                }
                if (resetRecognizer) {
                    recognizer.reset();
                    resetRecognizer = false;
                }

                if (recognizer.acceptWaveForm(buffer, read)) {
                    handleText(field(recognizer.getResult(), "text"), recognizer);
                } else if (singleWordPhrase) {
                    handleText(field(recognizer.getPartialResult(), "partial"), recognizer);
                }
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Voice listener error", e);
            return false;
        } finally {
            if (audio != null) {
                try {
                    if (audio.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) audio.stop();
                } catch (IllegalStateException ignored) {
                    // Already stopped.
                }
                audio.release();
            }
            if (recognizer != null) recognizer.close();
        }
    }

    private boolean loadModel() {
        File dir = VoskModelLoader.loadModel(this, MODEL_ASSET_NAME,
                new File(getFilesDir(), MODEL_DIR).getAbsolutePath());
        File modelPath = new File(dir == null ? new File(getFilesDir(), MODEL_DIR) : dir, MODEL_NAME);
        if (dir == null || !new File(modelPath, "am").exists()) {
            Log.e(TAG, "Vosk model not available at " + modelPath);
            return false;
        }
        try {
            model = new Model(modelPath.getAbsolutePath());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error loading Vosk model", e);
            return false;
        }
    }

    private void handleText(String text, Recognizer recognizer) {
        if (TextUtils.isEmpty(text)) return;
        String heard = " " + normalize(text) + " ";
        if (!heard.contains(" " + triggerPhrase + " ")) return;

        Log.i(TAG, "Trigger phrase heard: \"" + text + "\"");
        cooldownUntil = SystemClock.elapsedRealtime() + MATCH_COOLDOWN_MS;
        recognizer.reset();
        ProtectionController.triggerPanic(getApplicationContext(), "voice");
    }

    /** Lower-case, letters/digits only, single spaces: makes matching word-boundary safe. */
    private static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{Nd}]+", " ").trim();
    }

    private static String field(String json, String name) {
        if (TextUtils.isEmpty(json)) return "";
        try {
            return new JSONObject(json).optString(name, "");
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        Thread thread = listenThread;
        if (thread != null) {
            try {
                thread.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        listenThread = null;
        if (model != null && (thread == null || !thread.isAlive())) {
            model.close();
            model = null;
        }
        LocalBroadcastManager.getInstance(this).unregisterReceiver(settingsReceiver);
        try {
            unregisterReceiver(settingsReceiver);
        } catch (IllegalArgumentException ignored) {
            // Not registered (onCreate bailed out early).
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        ProtectionController.reportServiceState(this, false, false);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
