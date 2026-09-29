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
 *
 * Other on-device detectors (scream detection) read the same audio through {@link #setAudioTap}
 * instead of opening a second microphone. While an SOS is active the microphone is released so
 * the evidence recorder can use it; listening resumes when the SOS ends.
 */
public class VoskService extends Service {

    private static final String TAG = "VoskService";

    /**
     * Receives every block of 16 kHz mono PCM16 audio the listener reads. Called on the listener
     * thread: implementations must return immediately (hand the data to their own thread).
     */
    public interface AudioTap {
        /** @param buffer freshly allocated for this call, so the tap may keep it; {@code length} samples are valid. */
        void onAudio(short[] buffer, int length);
    }

    /** Sample rate of the audio delivered to {@link AudioTap}. */
    public static final int TAP_SAMPLE_RATE = 16000;

    @Nullable private static volatile AudioTap audioTap;

    /** Installs (or with null, removes) the single audio tap. Thread-safe. */
    public static void setAudioTap(@Nullable AudioTap tap) {
        audioTap = tap;
    }

    @Nullable
    public static AudioTap getAudioTap() {
        return audioTap;
    }

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
    /** Set when a recording session ended because an SOS started (not an error). */
    private volatile boolean pausedForPanic;
    private final Object pauseLock = new Object();

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

    /** Wakes the listener thread when an SOS starts or ends. */
    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (pauseLock) {
                pauseLock.notifyAll();
            }
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
        LocalBroadcastManager.getInstance(this).registerReceiver(stateReceiver,
                new IntentFilter(ProtectionController.ACTION_STATE_CHANGED));
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
            if (ProtectionController.isPanicActive()) {
                waitWhilePanic();
                continue;
            }
            pausedForPanic = false;
            boolean ok = listenOnce();
            if (!running) break;
            if (pausedForPanic) {
                // Released the mic for the SOS; not a failure, no back-off.
                continue;
            }
            consecutiveFailures = ok ? 0 : consecutiveFailures + 1;
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                Log.e(TAG, "Voice listener keeps failing; giving up until protection restarts");
                break;
            }
            SystemClock.sleep(RESTART_DELAY_MS * Math.max(1, consecutiveFailures));
        }
        if (running) stopSelf();
    }

    /** Blocks the listener thread (mic released) until the SOS ends or the service stops. */
    private void waitWhilePanic() {
        Log.d(TAG, "SOS active: microphone released");
        synchronized (pauseLock) {
            while (running && ProtectionController.isPanicActive()) {
                try {
                    // Timed wait as a safety net in case a state broadcast is missed.
                    pauseLock.wait(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        if (running) Log.d(TAG, "SOS ended: resuming listening");
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

                // An SOS started: release the microphone so evidence recording can use it.
                if (ProtectionController.isPanicActive()) {
                    pausedForPanic = true;
                    return true;
                }

                AudioTap tap = audioTap;
                if (tap != null) deliverToTap(tap, buffer, read);

                // Right after a match keep draining the mic but don't match.
                if (SystemClock.elapsedRealtime() < cooldownUntil) {
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

    /** Converts little-endian PCM16 bytes to samples and hands them to the tap; never throws. */
    private static void deliverToTap(AudioTap tap, byte[] bytes, int byteCount) {
        int samples = byteCount / 2;
        if (samples <= 0) return;
        short[] out = new short[samples];
        for (int i = 0, j = 0; i < samples; i++, j += 2) {
            out[i] = (short) ((bytes[j] & 0xFF) | (bytes[j + 1] << 8));
        }
        try {
            tap.onAudio(out, samples);
        } catch (Throwable t) {
            Log.e(TAG, "Audio tap failed", t);
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
        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }
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
        LocalBroadcastManager.getInstance(this).unregisterReceiver(stateReceiver);
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
