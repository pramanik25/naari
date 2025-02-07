package com.example.naarishakti;

import android.Manifest;
import android.app.AlarmManager;
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
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import Utils.VoskModelLoader;

public class VoskService extends Service implements RecognitionListener {

    private static final String TAG = "CombinedVoiceService";
    private final AtomicBoolean isVoskProcessing = new AtomicBoolean(false);

    private static final String MODEL_ASSET_NAME = "vosk-model-small-en-in-0.4.zip";
    private static final String MODEL_DESTINATION_FOLDER = "vosk_model_main";
    private static final String CHANNEL_ID = "VoskServiceChannel";
    private static final int NOTIFICATION_ID = 1;
    private String triggerWord = "help";
    private String triggerAudioPath;

    private static final int SAMPLE_RATE = 16000;
    private Model voskModel;
    private volatile Recognizer voskRecognizer;
    private volatile AudioRecord audioRecord;
    private volatile boolean isVoskListening = false;
    private final Object audioRecordLock = new Object();
    private SettingsUpdateReceiver settingsUpdateReceiver;
    private TriggerPhraseUpdateReceiver triggerPhraseUpdateReceiver;
    private TriggerAudioUpdateReceiver triggerAudioUpdateReceiver;
    private TimeSettingsUpdateReceiver timeSettingsUpdateReceiver;

    private SpeechRecognizer speechRecognizer;
    private Intent speechRecognizerIntent;
    private volatile boolean isBuiltInListening = false;
    private final AtomicBoolean isEmergencyActive = new AtomicBoolean(false);
    private volatile boolean isServiceActive = false; // Flag to track if service was explicitly started
    private boolean isFirstRun = true; // Flag to track if it's the first time the service is started

    private static VoskService instance;
    private ProcessAudioTask currentAudioTask;

    // Time window settings
    private int startTimeHour = 0;
    private int startTimeMinute = 0;
    private int endTimeHour = 23;
    private int endTimeMinute = 59;

    // Alarm manager
    private AlarmManager alarmManager;
    private PendingIntent alarmIntent;

    // Binder given to clients
    private final IBinder binder = new LocalBinder();

    /**
     * Class used for the client binding. Because we know this service always
     * runs in the same process as its clients, we don't need to deal with IPC.
     */
    public class LocalBinder extends Binder {
        VoskService getService() {
            // Return this instance of VoskService so clients can call public methods
            return VoskService.this;
        }
    }

    private PowerManager.WakeLock wakeLock;
    private static final String RESTART_SERVICE_ACTION = "com.example.naarishakti.RESTART_SERVICE";
    private RestartBroadcastReceiver restartBroadcastReceiver;
    private ProcessDeathReceiver processDeathReceiver;
    private boolean isCrashDetected = false;
    private int crashCount = 0;
    private static final int MAX_CRASH_COUNT = 3;
    private static final long CRASH_BACKOFF_DELAY = 60000; // 1 minute


    public static VoskService getInstance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");

        LibVosk.setLogLevel(LogLevel.INFO);
        instance = this;

        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NaariShakti::VoskWakeLock");
        wakeLock.acquire();

        // Register receiver for restarting the service
        restartBroadcastReceiver = new RestartBroadcastReceiver();
        registerReceiver(restartBroadcastReceiver, new IntentFilter(RESTART_SERVICE_ACTION));

        // Load Keyword model using VoskModelLoader
        File modelDir = new File(getCacheDir(), MODEL_DESTINATION_FOLDER);
        String modelPath = new File(modelDir, "vosk-model-small-en-in-0.4").getAbsolutePath();
        File extractedModelDir = VoskModelLoader.loadModel(this, MODEL_ASSET_NAME, modelDir.getAbsolutePath());

        if (extractedModelDir != null && new File(modelPath, "am").exists()) {
            try {
                voskModel = new Model(modelPath);
                Log.d(TAG, "Vosk model loaded successfully from: " + modelPath);
            } catch (IOException e) {
                Log.e(TAG, "Error loading Vosk model: " + e.getMessage());
                stopSelf();
                return;
            }
        } else {
            Log.e(TAG, "Failed to load Vosk model or model files not found in expected location.");
            stopSelf();
            return;
        }

        initializeBuiltInSpeechRecognizer();
        loadSettings();
        registerReceivers();

        if (triggerAudioPath != null && !triggerAudioPath.isEmpty()) {
            startBackgroundAudioProcessing(triggerAudioPath);
        }

        // Initialize AlarmManager
        alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        Intent alarmIntentService = new Intent(this, VoskService.class);
        alarmIntent = PendingIntent.getService(this, 0, alarmIntentService, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        scheduleAlarm();

        // Create notification channel for foreground service
        createNotificationChannel();

        // Start foreground service
        startForeground(NOTIFICATION_ID, buildNotification("Safety Service is running"));
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Vosk Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);
        }
    }

    private void initializeService() {
        loadSettings(); // Load settings including the trigger word
        initializeBuiltInSpeechRecognizer(); // Initialize recognition service
        if (triggerWord == null || triggerWord.trim().isEmpty()) {
            Log.e(TAG, "Trigger word is not initialized.");
        }
        Log.d(TAG, "Service initialized.");
    }


    private Notification buildNotification(String message) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this,
                0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Naari Shakti")
                .setContentText(message)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .build();
    }

    private void registerReceivers() {
        settingsUpdateReceiver = new SettingsUpdateReceiver();
        IntentFilter settingsFilter = new IntentFilter("com.example.naarishakti.ACTION_SETTINGS_UPDATED");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(settingsUpdateReceiver, settingsFilter, RECEIVER_EXPORTED);
        } else {
            registerReceiver(settingsUpdateReceiver, settingsFilter);
        }

        triggerPhraseUpdateReceiver = new TriggerPhraseUpdateReceiver();
        IntentFilter triggerPhraseFilter = new IntentFilter("com.example.naarishakti.ACTION_TRIGGER_PHRASE_UPDATED");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(triggerPhraseUpdateReceiver, triggerPhraseFilter, RECEIVER_EXPORTED);
        } else {
            registerReceiver(triggerPhraseUpdateReceiver, triggerPhraseFilter);
        }

        triggerAudioUpdateReceiver = new TriggerAudioUpdateReceiver();
        IntentFilter triggerAudioFilter = new IntentFilter("com.example.naarishakti.ACTION_TRIGGER_AUDIO_UPDATED");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(triggerAudioUpdateReceiver, triggerAudioFilter, RECEIVER_EXPORTED);
        } else {
            registerReceiver(triggerAudioUpdateReceiver, triggerAudioFilter);
        }

        timeSettingsUpdateReceiver = new TimeSettingsUpdateReceiver();
        IntentFilter timeSettingsFilter = new IntentFilter("com.example.naarishakti.ACTION_TIME_SETTINGS_UPDATED");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(timeSettingsUpdateReceiver, timeSettingsFilter, RECEIVER_EXPORTED);
        } else {
            registerReceiver(timeSettingsUpdateReceiver, timeSettingsFilter);
        }

        // Register receiver for process death
        processDeathReceiver = new ProcessDeathReceiver();
        IntentFilter processDeathFilter = new IntentFilter(Intent.ACTION_MY_PACKAGE_REPLACED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(processDeathReceiver, processDeathFilter, RECEIVER_EXPORTED);
        } else {
            registerReceiver(processDeathReceiver, processDeathFilter);
        }
    }

    private void unregisterReceivers() {
        if (settingsUpdateReceiver != null) {
            try {
                unregisterReceiver(settingsUpdateReceiver);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Error unregistering settings receiver: " + e.getMessage());
            }
            settingsUpdateReceiver = null;
        }
        if (triggerPhraseUpdateReceiver != null) {
            try {
                unregisterReceiver(triggerPhraseUpdateReceiver);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Error unregistering trigger phrase receiver: " + e.getMessage());
            }
            triggerPhraseUpdateReceiver = null;
        }
        if (triggerAudioUpdateReceiver != null) {
            try {
                unregisterReceiver(triggerAudioUpdateReceiver);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Error unregistering trigger audio receiver: " + e.getMessage());
            }
            triggerAudioUpdateReceiver = null;
        }
        if (timeSettingsUpdateReceiver != null) {
            try {
                unregisterReceiver(timeSettingsUpdateReceiver);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Error unregistering time settings receiver: " + e.getMessage());
            }
            timeSettingsUpdateReceiver = null;
        }
        if (processDeathReceiver != null) {
            try {
                unregisterReceiver(processDeathReceiver);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Error unregistering process death receiver: " + e.getMessage());
            }
            processDeathReceiver = null;
        }
        if (restartBroadcastReceiver != null) {
            try {
                unregisterReceiver(restartBroadcastReceiver);
                Log.d(TAG, "RestartBroadcastReceiver unregistered.");
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Error unregistering restart receiver: " + e.getMessage());
            }
            restartBroadcastReceiver = null;
        }
    }


    private void startBackgroundAudioProcessing(String audioPath) {
        if (currentAudioTask != null && currentAudioTask.getStatus() == AsyncTask.Status.RUNNING) {
            currentAudioTask.cancel(true);
        }
        currentAudioTask = new ProcessAudioTask(this);
        currentAudioTask.execute(audioPath);
    }

    public void loadSettings() {
        SharedPreferences sharedPreferences = getSharedPreferences("AppPrefs", MODE_PRIVATE);
        if (sharedPreferences != null) {
            triggerWord = sharedPreferences.getString("trigger_phrase_text", "help");
            triggerAudioPath = sharedPreferences.getString("last_trigger_audio_path", "");

            startTimeHour = sharedPreferences.getInt("start_time_hour", 0);
            startTimeMinute = sharedPreferences.getInt("start_time_minute", 0);
            endTimeHour = sharedPreferences.getInt("end_time_hour", 23);
            endTimeMinute = sharedPreferences.getInt("end_time_minute", 59);
            Log.d(TAG, "Loaded trigger word: " + triggerWord);
            Log.d(TAG, "Loaded trigger audio path: " + triggerAudioPath);
            Log.d(TAG, "Loaded start time: " + startTimeHour + ":" + startTimeMinute);
            Log.d(TAG, "Loaded end time: " + endTimeHour + ":" + endTimeMinute);
        } else {
            Log.e(TAG, "SharedPreferences is null, cannot load settings.");
        }
    }

    private void initializeBuiltInSpeechRecognizer() {
        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            if (speechRecognizer == null) {
                Log.e(TAG, "Failed to create SpeechRecognizer. Ensure the service is available on this device.");
                return;
            }
            speechRecognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
            speechRecognizer.setRecognitionListener(this);
        }
    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if (intent.getBooleanExtra("start_service", false)) {
                isServiceActive = true;
                initializeService();
                startRecognition();
            } else if (intent.getBooleanExtra("stop_service", false)) {
                Log.d(TAG, "Stop alarm triggered. Stopping service.");
                stopVoskRecognition();
                stopBuiltInRecognition();
                stopSelf();
            }
        }
        return START_REDELIVER_INTENT;
    }


    private boolean isWithinActiveTime() {
        Calendar now = Calendar.getInstance();
        int currentHour = now.get(Calendar.HOUR_OF_DAY);
        int currentMinute = now.get(Calendar.MINUTE);

        int startTimeInMinutes = startTimeHour * 60 + startTimeMinute;
        int endTimeInMinutes = endTimeHour * 60 + endTimeMinute;
        int currentTimeInMinutes = currentHour * 60 + currentMinute;

        if (startTimeInMinutes <= endTimeInMinutes) {
            return currentTimeInMinutes >= startTimeInMinutes && currentTimeInMinutes <= endTimeInMinutes;
        } else { // Handle cases where the time window crosses midnight
            return currentTimeInMinutes >= startTimeInMinutes || currentTimeInMinutes <= endTimeInMinutes;
        }
    }

    private void startRecognition() {
        if (!isEmergencyActive.get() && !isCrashDetected) {
            startVoskLiveRecognition();
            startBuiltInRecognition();
        } else {
            Log.d(TAG, "Recognition not started, emergency is active or crash detected.");
        }
    }

    private void startVoskLiveRecognition() {
        if (voskModel == null) {
            Log.e(TAG, "Vosk model not initialized");
            return;
        }

        if (isVoskListening) {
            Log.d(TAG, "Vosk is already listening");
            return;
        }

        try {
            voskRecognizer = new Recognizer(voskModel, SAMPLE_RATE);
        } catch (IOException e) {
            Log.e(TAG, "Error creating Vosk recognizer: " + e.getMessage());
            stopSelf();
            return;
        }

        final int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Record audio permission not granted for Vosk");
            stopSelf();
            return;
        }

        synchronized (audioRecordLock) {
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufferSize);

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed for Vosk");
                return;
            }
            try {
                audioRecord.startRecording();
                isVoskListening = true;
            } catch (IllegalStateException e) {
                Log.e(TAG, "Error starting AudioRecord: " + e.getMessage());
                return;
            }
        }

        Log.d(TAG, "Vosk is listening live...");
        isVoskProcessing.set(true); // Set the flag to true before starting the thread
        new Thread(() -> {
            try {
                byte[] buffer = new byte[bufferSize];
                while (isVoskProcessing.get() && !isEmergencyActive.get() && !isCrashDetected) {
                    int bytesRead;
                    synchronized (audioRecordLock) {
                        if (audioRecord != null && audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                            bytesRead = audioRecord.read(buffer, 0, buffer.length);
                        } else {
                            break; // Exit loop if audioRecord is not recording
                        }
                    }

                    if (bytesRead > 0) {
                        if (voskRecognizer != null) {
                            try {
                                if (voskRecognizer.acceptWaveForm(buffer, bytesRead)) {
                                    String result = voskRecognizer.getResult();
                                    Log.d(TAG, "Vosk Live Result: " + result);
                                    processResult(result);
                                } else {
                                    String partialResult = voskRecognizer.getPartialResult();
                                    Log.d(TAG, "Vosk Live Partial Result: " + partialResult);
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "Error during Vosk processing: " + e.getMessage());
                                handleVoskCrash();
                                break;
                            }
                        } else {
                            Log.e(TAG, "Recognizer is null, cannot get result or partial result.");
                        }
                    }
                }
            } finally {
                stopVoskRecognitionInternal();
            }
        }).start();
        Log.d(TAG, "startVoskLiveRecognition: Vosk live recognition started");
    }


    private void startBuiltInRecognition() {
        if (speechRecognizer == null) {
            Log.e(TAG, "SpeechRecognizer is null. Cannot start built-in recognition.");
            return;
        }

        if (isBuiltInListening) {
            Log.d(TAG, "Built-in recognizer is already listening.");
            return;
        }

        if (isEmergencyActive.get() || isCrashDetected) {
            Log.d(TAG, "Emergency is active or crash detected. Cannot start built-in recognition.");
            return;
        }

        isBuiltInListening = true;
        try {
            speechRecognizer.startListening(speechRecognizerIntent);
            Log.d(TAG, "Built-in recognizer is listening...");
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception while starting built-in recognition: ", e);
            // Consider informing the user about the missing permission.
            isBuiltInListening = false; // Reset the listening flag
        } catch (Exception e) {
            Log.e(TAG, "Error starting built-in recognition: ", e);
            // Handle other potential errors.
            isBuiltInListening = false; // Reset the listening flag
        }
    }

    private Handler mainThreadHandler = new Handler(Looper.getMainLooper());

    public void stopBuiltInRecognition() {
        mainThreadHandler.post(() -> {
            if (speechRecognizer == null) {
                Log.e(TAG, "SpeechRecognizer is null. Cannot stop built-in recognition.");
                return;
            }

            if (!isBuiltInListening) {
                Log.d(TAG, "Built-in recognizer is not listening.");
                return;
            }

            try {
                speechRecognizer.stopListening();
                Log.d(TAG, "Built-in recognizer stopped.");
            } catch (IllegalStateException e) {
                Log.e(TAG, "Error stopping built-in recognition: " + e.getMessage());
                // Handle the error, if necessary.
            } finally {
                isBuiltInListening = false;
            }
        });
    }




    private synchronized void processResult(String result) {
        if (result == null || result.trim().isEmpty()) {
            Log.w(TAG, "Received empty result, ignoring.");
            return;
        }

        Log.d(TAG, "Processing result: " + result);

        if (triggerWord == null || triggerWord.trim().isEmpty()) {
            Log.e(TAG, "Trigger word is not initialized.");
            return;
        }

        if (result.trim().equalsIgnoreCase(triggerWord.trim())) {
            Log.i(TAG, "Trigger phrase detected: " + result);
            if (!isEmergencyActive.get()) {
                isEmergencyActive.set(true);
                Log.d(TAG, "Emergency state activated. Initiating emergency task.");
                triggerEmergencyAction();
            } else {
                Log.d(TAG, "Emergency task already active, ignoring repeated trigger.");
            }
        } else {
            Log.d(TAG, "Result does not match the trigger phrase.");
        }
    }



    private void triggerEmergencyAction() {
        Log.i(TAG, "Triggering Emergency Action!");
        VoiceRecognitionService service = VoiceRecognitionService.getInstance();
        if (service != null) {
            service.sendEmergencyMessage(() -> {
                // Callback when emergency action is completed
                isEmergencyActive.set(false);
                Log.i(TAG, "Emergency action completed, restarting recognition.");
                startRecognition();
            });
        } else {
            Log.e(TAG, "VoiceRecognitionService instance is null, cannot trigger emergency.");
            // Use a Handler to post the Toast on the main thread
            new Handler(Looper.getMainLooper()).post(() -> {
                Toast.makeText(this, "Error: Emergency service not available.", Toast.LENGTH_SHORT).show();
            });
            isEmergencyActive.set(false); // Reset flag in case of error
            startRecognition(); // Attempt to restart recognition
        }
    }
    public void cancelAudioProcessing() {
        Log.d(TAG, "Cancellation of audio processing requested.");
        if (currentAudioTask != null && currentAudioTask.getStatus() == AsyncTask.Status.RUNNING) {
            currentAudioTask.cancel(true);
        }
    }

    public void stopVoskRecognition() {
        Log.d(TAG, "Stopping Vosk recognition");

        if (!isVoskListening) {
            Log.d(TAG, "Vosk recognizer is not listening.");
            return;
        }
        isVoskListening = false;
        stopVoskRecognitionInternal(); // Use internal to ensure clean stop
        Log.d(TAG, "Vosk recognition stopped.");
    }

    // In VoskService.java


    private synchronized void stopVoskRecognitionInternal() {
        Log.d(TAG, "Stopping Vosk recognition internal");

        isVoskProcessing.set(false); // Signal the audio processing loop to stop

        Recognizer tempRecognizer = voskRecognizer;
        if (tempRecognizer != null) {
            try {
                tempRecognizer.close();
                Log.d(TAG, "Vosk recognizer closed.");
            } catch (Exception e) {
                Log.e(TAG, "Error closing Vosk recognizer: " + e.getMessage());
            } finally {
                voskRecognizer = null;
            }
        } else {
            Log.d(TAG, "Vosk recognizer is already null.");
        }

        synchronized (audioRecordLock) {
            AudioRecord tempAudioRecord = audioRecord;
            if (tempAudioRecord != null) {
                try {
                    if (tempAudioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        tempAudioRecord.stop();
                        Log.d(TAG, "AudioRecord stopped.");
                    }
                } catch (IllegalStateException e) {
                    Log.e(TAG, "Error stopping AudioRecord: " + e.getMessage());
                } finally {
                    try {
                        tempAudioRecord.release();
                        Log.d(TAG, "AudioRecord released.");
                    } catch (Exception e) {
                        Log.e(TAG, "Error releasing AudioRecord: " + e.getMessage());
                    } finally {
                        audioRecord = null;
                    }
                }
            } else {
                Log.d(TAG, "AudioRecord is already null.");
            }
        }

        Log.d(TAG, "Vosk recognition internal stopped.");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "Service is being destroyed. Cleaning up resources.");

        try {
            stopVoskRecognition();
            stopBuiltInRecognition();

            if (speechRecognizer != null) {
                speechRecognizer.destroy();
                Log.d(TAG, "SpeechRecognizer destroyed.");
                speechRecognizer = null;
            }

            if (alarmManager != null && alarmIntent != null) {
                alarmManager.cancel(alarmIntent);
                Log.d(TAG, "Alarm canceled.");
            }

            unregisterReceivers();
            cancelAudioProcessing();

        } catch (Exception e) {
            Log.e(TAG, "Error during cleanup: " + e.getMessage(), e);
        }

        isServiceActive = false;
        Log.i(TAG, "Service cleanup completed.");
        sendServiceStateBroadcast();
    }

    private void scheduleAlarm() {
        if (alarmManager == null || alarmIntent == null) {
            Log.e(TAG, "AlarmManager or PendingIntent is null, cannot schedule alarm.");
            return;
        }

        long interval = AlarmManager.INTERVAL_FIFTEEN_MINUTES; // Check every 15 minutes
        long triggerTime = SystemClock.elapsedRealtime() + interval;

        alarmManager.setRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerTime, interval, alarmIntent);
        Log.d(TAG, "Alarm scheduled to check service status every 15 minutes.");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    // Built-in RecognitionListener methods
    @Override
    public void onReadyForSpeech(Bundle bundle) {
        Log.d(TAG, "Built-in: Ready for speech");
        isBuiltInListening = true;
    }

    @Override
    public void onBeginningOfSpeech() {
        Log.d(TAG, "Built-in: Beginning of speech");
    }

    @Override
    public void onRmsChanged(float v) {
    }

    @Override
    public void onBufferReceived(byte[] bytes) {    }

    @Override
    public void onEndOfSpeech() {
        Log.d(TAG, "Built-in: End of speech");
        // After built-in speech ends, restart listening if trigger wasn't detected and no emergency active
        if (!isVoskListening && !isEmergencyActive.get() && !isCrashDetected) {
            startBuiltInRecognition();
        }
    }


    @Override
    public void onError(int error) {
        Log.e(TAG, "Built-in: Error " + error);
        isBuiltInListening = false;
        if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            if (!isEmergencyActive.get() && !isCrashDetected) {
                startBuiltInRecognition();
            }
        } else if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            Log.w(TAG, "Insufficient permissions for speech recognition.");
            // Optionally, you can request the permission here or inform the user.
        } else if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
            Log.w(TAG, "Speech recognizer is busy.");
            if (!isEmergencyActive.get() && !isCrashDetected) {
                startBuiltInRecognition();
            }
        }
    }

    @Override
    public void onResults(Bundle bundle) {
        ArrayList<String> data = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (data != null && !data.isEmpty()) {
            String spokenText = data.get(0);
            Log.d(TAG, "Built-in: Recognized text " + spokenText);
            processResult(spokenText);
        }
    }

    @Override
    public void onPartialResults(Bundle bundle) {
        // Partial results can be logged if required.
         Log.d(TAG, "Built-in: Partial Result " + bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION));
    }

    @Override
    public void onEvent(int i, Bundle bundle) {
    }

    private class SettingsUpdateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.example.naarishakti.ACTION_SETTINGS_UPDATED".equals(intent.getAction())) {
                Log.d(TAG, "Received settings update broadcast");
                loadSettings();
            }
        }
    }

    private class TriggerPhraseUpdateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.example.naarishakti.ACTION_TRIGGER_PHRASE_UPDATED".equals(intent.getAction())) {
                Log.d(TAG, "Received trigger phrase update broadcast");
                loadSettings(); // Reload settings to get the updated trigger
            }
        }
    }

    private class TriggerAudioUpdateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.example.naarishakti.ACTION_TRIGGER_AUDIO_UPDATED".equals(intent.getAction())) {
                Log.d(TAG, "Received trigger audio update broadcast");
                loadSettings(); // Reload settings to get the updated audio path
                if (triggerAudioPath != null && !triggerAudioPath.isEmpty()) {
                    startBackgroundAudioProcessing(triggerAudioPath);
                } else {
                    Log.w(TAG, "Trigger audio path is empty, skipping background processing.");
                    cancelAudioProcessing(); // Cancel any ongoing processing if the path is empty
                }
            }
        }
    }

    private class TimeSettingsUpdateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.example.naarishakti.ACTION_TIME_SETTINGS_UPDATED".equals(intent.getAction())) {
                Log.d(TAG, "Received time settings update broadcast");
                loadSettings(); // Reload settings to get the updated time settings
                if (isWithinActiveTime() && isServiceActive) {
                    startRecognition();
                } else if (!isWithinActiveTime() && isServiceActive) {
                    stopSelf();
                }
            }
        }
    }

    private class ProcessAudioTask extends AsyncTask<String, Void, Boolean> {

        private final Context context;

        public ProcessAudioTask(Context context) {
            this.context = context.getApplicationContext(); // Use application context to avoid leaks
        }

        @Override
        protected void onPreExecute() {
            // Removed the toast from here
            Log.d(TAG, "Processing trigger audio...");
        }

        @Override
        protected Boolean doInBackground(String... audioPaths) {
            String audioPath = audioPaths[0];
            Log.d(TAG, "Background processing of trigger audio: " + audioPath);
            if (voskModel == null) {
                Log.e(TAG, "Vosk model not initialized");
                return false;
            }
            Recognizer offlineRecognizer = null;
            FileInputStream fis = null;
            try {
                fis = new FileInputStream(audioPath);
                byte[] header = new byte[44];
                int headerBytesRead = fis.read(header);
                if (headerBytesRead != header.length) {
                    Log.e(TAG, "Could not read full WAV header.");
                    return false;
                }
                byte[] buffer = new byte[4096];
                int bytesRead;
                offlineRecognizer = new Recognizer(voskModel, SAMPLE_RATE);

                while ((bytesRead = fis.read(buffer)) != -1 && !isCancelled() && !isEmergencyActive.get() && !isCrashDetected) {
                    if (offlineRecognizer.acceptWaveForm(buffer, bytesRead)) {
                        String result = offlineRecognizer.getResult();
                        Log.d(TAG, "Vosk (Offline) Full Result: " + result);
                        processResult(result);
                        return true;
                    } else {
                        String partialResult = offlineRecognizer.getPartialResult();
                        Log.d(TAG, "Vosk (Offline) Partial Result: " + partialResult);
                    }
                }
                if (!isCancelled() && !isEmergencyActive.get() && !isCrashDetected) {
                    String finalResult = offlineRecognizer.getFinalResult();
                    Log.d(TAG, "Vosk (Offline) Final Result: " + finalResult);
                    processResult(finalResult);
                }

                return true;
            } catch (FileNotFoundException e) {
                Log.e(TAG, "Error processing trigger audio file: File not found", e);
                return false;
            } catch (IOException e) {
                Log.e(TAG, "Error processing trigger audio file: Read error", e);
                return false;
            } catch (Exception e) {
                Log.e(TAG, "Error during offline Vosk processing", e);
                return false;
            } finally {
                if (fis != null) {
                    try {
                        fis.close();
                    } catch (IOException e) {
                        Log.e(TAG, "Error closing FileInputStream: " + e.getMessage());
                    }
                }
                if (offlineRecognizer != null) {
                    try {
                        offlineRecognizer.close();
                        Log.d(TAG, "Offline Vosk recognizer closed");
                    } catch (Exception e) {
                        Log.e(TAG, "Error closing offline Vosk recognizer: " + e.getMessage());
                    }
                }
            }
        }

        @Override
        protected void onPostExecute(Boolean success) {
            if (success) {
                Log.d(TAG, "Trigger audio processed.");
            } else {
                Log.e(TAG, "Failed to process trigger audio.");
            }
            Log.d(TAG, "Background processing of trigger audio finished.");
        }

        @Override
        protected void onCancelled() {
            Log.d(TAG, "Background processing of trigger audio cancelled.");
        }
    }

// BroadcastReceiver to handle process death
private  class ProcessDeathReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            Log.d(TAG, "Process death detected, restarting service.");
            // Start the service again only if it was explicitly started
            if (isServiceActive) {
                Intent serviceIntent = new Intent(context, VoskService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            } else {
                Log.d(TAG, "Service was not explicitly started, not restarting after process death.");
            }
        }
    }
}


// Broadcast receiver for restarting the service
public class RestartBroadcastReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Objects.equals(intent.getAction(), RESTART_SERVICE_ACTION)) {
            Log.i(TAG, "Received restart service broadcast. Restarting...");
            Intent restartServiceIntent = new Intent(context, VoskService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(restartServiceIntent);
            } else {
                context.startService(restartServiceIntent);
            }
        }
    }
}

    private void handleVoskCrash() {
        Log.e(TAG, "Vosk recognizer crashed.");
        isCrashDetected = true;
        crashCount++;
        stopVoskRecognition();
        stopBuiltInRecognition();
        if (crashCount >= MAX_CRASH_COUNT) {
            Log.e(TAG, "Max crash count reached. Stopping service.");
            stopSelf();
        } else {
            Log.w(TAG, "Restarting service after crash in " + CRASH_BACKOFF_DELAY / 1000 + " seconds.");
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                isCrashDetected = false;
                startRecognition();
            }, CRASH_BACKOFF_DELAY);
        }
    }

    private void sendServiceStateBroadcast() {
        Intent intent = new Intent("com.example.naarishakti.ACTION_SERVICE_STATE_CHANGED");
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }
}