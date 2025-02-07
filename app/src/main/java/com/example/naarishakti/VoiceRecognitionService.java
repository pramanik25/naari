package com.example.naarishakti;

import android.Manifest;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.Camera;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.telephony.PhoneStateListener;
import android.telephony.SmsManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.View;
import android.widget.Toast;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import android.graphics.SurfaceTexture;


import SQLite_Database.ProfileDbHelper;

public class VoiceRecognitionService extends Service {

    private static final String TAG = "EmergencyService";
    private static final String CHANNEL_ID = "EmergencyServiceChannel";
    public static final String ACTION_TRIGGER_PANIC = "com.example.naarishakti.ACTION_TRIGGER_PANIC";
    private String emergencyMessage;
    private List<String> emergencyContacts = new ArrayList<>();
    private TelephonyManager telephonyManager;
    private CustomPhoneStateListener phoneStateListener;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private static final int LOCATION_UPDATE_INTERVAL = 30 * 1000;
    private LocationRequest locationRequest;
    private static VoiceRecognitionService instance;
    private OnEmergencyActionCompleteListener currentListener;
    private final Handler mainThreadHandler = new Handler(Looper.getMainLooper());
    private boolean isCallActive = false;
    private boolean isEmergencyActive = false;
    private ShakeDetector shakeDetector;
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private boolean isShakeDetectionEnabled = true;
    private MediaPlayer mediaPlayer;

    private static final String PREFS_NAME = "PanicButtonPrefs";
    private static final String VOLUME_BUTTON_PRESS_COUNT = "volumeButtonPressCount";
    private static final long VOLUME_BUTTON_TIMEOUT = 3000; // 3 seconds timeout
    private long lastVolumeButtonPress = 0;
    private int volumeButtonPressCount = 0;
    private boolean isPanicModeActive = false;
    private WindowManager windowManager;
    private View overlayView;

    private static final int VOLUME_BUTTON_DEACTIVATION_COUNT = 2;
    private static final long VOLUME_BUTTON_PRESS_INTERVAL = 500; // 500ms between presses
    private long lastVolumeUpPress = 0;
    private long lastVolumeDownPress = 0;
    private boolean isVolumeUpPressed = false;
    private boolean isVolumeDownPressed = false;
    private PowerManager.WakeLock wakeLock;
    private static final String TELEGRAM_NUMBER_KEY = "telegram_number";
    private static final String EMAIL_ADDRESS_KEY = "email_address";

    private Camera frontCamera;
    private Camera backCamera;
    private boolean isFrontPhotoTaken = false;
    private boolean isBackPhotoTaken = false;

    private boolean isContinuousCapturing = false;
    private Handler cameraHandler = new Handler(Looper.getMainLooper());
    private static final int CAPTURE_INTERVAL = 10000; // 10 seconds between captures
    private String telegramNumber;
    private String emailAddress;
    @Nullable private KeyguardManager.KeyguardLock keyguardLock = null;

    private final BroadcastReceiver volumeButtonReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals("android.media.VOLUME_CHANGED_ACTION")) {
                AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                int prevVolume = intent.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1);
                int currentVolume = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1);

                if (prevVolume != currentVolume) {
                    boolean isVolumeUp = currentVolume > prevVolume;
                    handleVolumeButtonPress(isVolumeUp);
                }
            }
        }
    };

    private ProfileDbHelper dbHelper;
    private WorkManager workManager;

    public static VoiceRecognitionService getInstance() {
        return instance;
    }

    // Modified triggerEmergency to directly call triggerPanicButtonAction
    public static void triggerEmergency(Context context) {
        Intent serviceIntent = new Intent(context, VoiceRecognitionService.class);
        serviceIntent.setAction(ACTION_TRIGGER_PANIC); // Set action to trigger panic
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service Created");
        loadSettings();
        instance = this;
        setupCallListener();
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        createLocationRequest();
        dbHelper = new ProfileDbHelper(this);
        workManager = WorkManager.getInstance(this);

        initializeVolumeButtonListener();
        keyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);

        createNotificationChannel();
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent =
                PendingIntent.getActivity(this, 0, notificationIntent,
                        PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Emergency Service")
                .setContentText("Running...")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .build();

        startForeground(1, notification);

        IntentFilter filter = new IntentFilter("android.media.VOLUME_CHANGED_ACTION");
        registerReceiver(volumeButtonReceiver, filter);

        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);


        // Initialize ShakeDetector
        shakeDetector = new ShakeDetector();
        shakeDetector.setOnShakeListener(new ShakeDetector.OnShakeListener() {
            @Override
            public void onShake(int shakeCount) {

            }

            @Override
            public void onShake() {
                if (isShakeDetectionEnabled) {
                    triggerPanicButtonAction(); // Trigger SOS on shake as well if enabled
                }
            }

        });

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (accelerometer != null) {
                startShakeDetection();
            } else {
                Log.e(TAG, "Accelerometer sensor not available.");
            }
        } else {
            Log.e(TAG, "SensorManager is null.");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_TRIGGER_PANIC.equals(intent.getAction())) {
            triggerPanicButtonAction(); // Directly call panic action
        }
        return START_STICKY;
    }

    // Method to encapsulate all panic actions
    private void triggerPanicButtonAction() {
        if (isEmergencyActive) {
            Log.d(TAG, "Emergency action already in progress, ignoring panic button press.");
            return;
        }
        isEmergencyActive = true;
        Log.d(TAG, "Panic button action initiated.");

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Location permissions not granted.");
            sendSmsAndProcessMedia(null);
            return;
        }

        fusedLocationClient.getLastLocation()
                .addOnSuccessListener(this::sendSmsAndProcessMedia)
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to get last known location: " + e.getMessage());
                    sendSmsAndProcessMedia(null);
                });

        playEmergencyAudio();
        showSecureOverlay(); // Freeze screen

        // Disable power button and volume change - not reliably possible, informing user in documentation is better approach.
        // disablePowerButton();
        // disableVolumeChange();
    }


    private void disableVolumeChange() {
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0);
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
        }
    }
    private void disablePowerButton() {
        // Note: It is generally not possible to completely disable the power button or prevent turning off the phone due to security and privacy concerns.
        // Instead, focus on making the SOS feature reliable when triggered.
    }

    private void sendSmsAndProcessMedia(Location location) {
        String message = emergencyMessage;
        if (location != null) {
            String locationData = "http://maps.google.com/maps?q=" + location.getLatitude() + "," + location.getLongitude();
            message += "\nLast Known Location: " + locationData;
        } else {
            message += "\nLast Known Location not available at this moment.";
        }

        for (String number : emergencyContacts) {
            String trimmedNumber = number.trim();
            if (!trimmedNumber.isEmpty()) {
                sendSms(trimmedNumber, message);
            } else {
                Log.w(TAG, "Skipping empty contact number for SMS.");
            }
        }
        startContinuousCapture();
    }

    private void startContinuousCapture() {
        if (isContinuousCapturing) return;

        isContinuousCapturing = true;

        // Get Telegram number and email from SharedPreferences
        SharedPreferences sharedPreferences = getSharedPreferences("AppPrefs", MODE_PRIVATE);
        telegramNumber = sharedPreferences.getString(TELEGRAM_NUMBER_KEY, "");
        emailAddress = sharedPreferences.getString(EMAIL_ADDRESS_KEY, "");

        cameraHandler.post(new Runnable() {
            @Override
            public void run() {
                if (isContinuousCapturing && mediaPlayer != null && mediaPlayer.isPlaying()) { // Capture only when panic sound is playing
                    captureAndSendImages();
                    cameraHandler.postDelayed(this, CAPTURE_INTERVAL);
                } else if (!isContinuousCapturing) {
                    // Stop capturing if continuous capture is disabled
                } else if (mediaPlayer == null || !mediaPlayer.isPlaying()) {
                    stopContinuousCapture(); // Stop capture if audio playback stops.
                }
            }
        });
    }

    private void captureAndSendImages() {
        acquireWakeLock();
        takeFrontPhotoForTelegramAndEmail();
        takeBackPhotoForTelegramAndEmail();
    }

    private void prepareCameraForCapture(final Camera camera, final boolean isFront) {
        try {
            Camera.Parameters parameters = camera.getParameters();

            // Set optimal picture size
            Camera.Size pictureSize = getOptimalPictureSize(parameters.getSupportedPictureSizes());
            parameters.setPictureSize(pictureSize.width, pictureSize.height);

            // Enable auto focus if available
            List<String> supportedFocusModes = parameters.getSupportedFocusModes();
            if (supportedFocusModes != null && supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            }

            // Set scene mode to night if available for better low-light performance
            List<String> supportedSceneModes = parameters.getSupportedSceneModes();
            if (supportedSceneModes != null && supportedSceneModes.contains(Camera.Parameters.SCENE_MODE_NIGHT)) {
                parameters.setSceneMode(Camera.Parameters.SCENE_MODE_NIGHT);
            }

            // Set flash mode to auto if available
            List<String> supportedFlashModes = parameters.getSupportedFlashModes();
            if (supportedFlashModes != null && supportedFlashModes.contains(Camera.Parameters.FLASH_MODE_AUTO)) {
                parameters.setFlashMode(Camera.Parameters.FLASH_MODE_AUTO);
            }

            camera.setParameters(parameters);

            // Set display orientation
            camera.setDisplayOrientation(90);

            // Create a dummy surface texture for preview
            SurfaceTexture dummySurfaceTexture = new SurfaceTexture(0);
            camera.setPreviewTexture(dummySurfaceTexture);

            camera.startPreview();

            // Auto focus if available
            if (supportedFocusModes != null && supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                camera.autoFocus((success, camera1) -> {
                    // Take picture after autofocus completes
                    takePictureWithRetry(camera1, isFront, 3);
                });
            } else {
                // Take picture immediately if auto focus not available
                takePictureWithRetry(camera, isFront, 3);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error preparing camera: " + e.getMessage());
            releaseCamera(camera);
            if (isFront) {
                isFrontPhotoTaken = true;
                takeBackPhotoForTelegramAndEmail();
            } else {
                isBackPhotoTaken = true;
                releaseWakeLock();
            }
        }
    }

    private void takePictureWithRetry(final Camera camera, final boolean isFront, final int retriesLeft) {
        try {
            camera.takePicture(null, null, (data, camera1) -> {
                saveAndShareImage(data, isFront);
                saveImageToDatabase(data, isFront);
                releaseCamera(camera1);

                if (isFront) {
                    isFrontPhotoTaken = true;
                    takeBackPhotoForTelegramAndEmail();
                } else {
                    isBackPhotoTaken = true;
                    releaseWakeLock();
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error taking picture: " + e.getMessage());
            if (retriesLeft > 0) {
                // Retry after a short delay
                new Handler(Looper.getMainLooper()).postDelayed(() ->
                        takePictureWithRetry(camera, isFront, retriesLeft - 1), 500);
            } else {
                releaseCamera(camera);
                if (isFront) {
                    isFrontPhotoTaken = true;
                    takeBackPhotoForTelegramAndEmail();
                } else {
                    isBackPhotoTaken = true;
                    releaseWakeLock();
                }
            }
        }
    }
    private void stopContinuousCapture() {
        isContinuousCapturing = false;
        cameraHandler.removeCallbacksAndMessages(null);
    }

    private Camera.Size getOptimalPictureSize(List<Camera.Size> sizes) {
        if (sizes == null || sizes.isEmpty()) {
            return null;
        }

        Camera.Size optimalSize = sizes.get(0);
        int targetArea = 1920 * 1080; // Target 2MP images

        for (Camera.Size size : sizes) {
            int area = size.width * size.height;
            if (Math.abs(area - targetArea) < Math.abs(optimalSize.width * optimalSize.height - targetArea)) {
                optimalSize = size;
            }
        }

        return optimalSize;
    }

    private void releaseCamera(Camera camera) {
        if (camera != null) {
            try {
                camera.stopPreview();
                camera.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing camera: " + e.getMessage());
            }
        }
    }

    private void takeFrontPhotoForTelegramAndEmail() {
        int cameraId = findFrontFacingCamera();
        if (cameraId == -1) {
            Log.e(TAG, "Front camera not found");
            takeBackPhotoForTelegramAndEmail();
            return;
        }

        try {
            Camera camera = Camera.open(cameraId);
            prepareCameraForCapture(camera, true);  // Using the prepare method

            camera.setPreviewTexture(new SurfaceTexture(0));
            camera.startPreview();

            camera.takePicture(null, null, (data, camera1) -> {
                saveAndShareImage(data, true);
                camera1.release();
            });
        } catch (Exception e) {
            Log.e(TAG, "Error taking front photo: " + e.getMessage());
        }
    }

    private int findFrontFacingCamera() {
        int cameraCount = 0;
        int result = -1;

        // Get number of cameras available
        try {
            cameraCount = Camera.getNumberOfCameras();
        } catch (Exception e) {
            Log.e(TAG, "Error getting camera count: " + e.getMessage());
            return -1;
        }

        // Find the front facing camera
        for (int camIdx = 0; camIdx < cameraCount; camIdx++) {
            Camera.CameraInfo info = new Camera.CameraInfo();
            try {
                Camera.getCameraInfo(camIdx, info);
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                    result = camIdx;
                    break;
                }
            } catch (Exception e) {
                Log.e(TAG, "Error checking camera " + camIdx + ": " + e.getMessage());
            }
        }

        return result;
    }


    private int findBackFacingCamera() {
        int cameraCount = 0;
        int result = -1;

        // Get number of cameras available
        try {
            cameraCount = Camera.getNumberOfCameras();
        } catch (Exception e) {
            Log.e(TAG, "Error getting camera count: " + e.getMessage());
            return -1;
        }

        // Find the back facing camera
        for (int camIdx = 0; camIdx < cameraCount; camIdx++) {
            Camera.CameraInfo info = new Camera.CameraInfo();
            try {
                Camera.getCameraInfo(camIdx, info);
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                    result = camIdx;
                    break;
                }
            } catch (Exception e) {
                Log.e(TAG, "Error checking camera " + camIdx + ": " + e.getMessage());
            }
        }

        return result;
    }


    private void takeBackPhotoForTelegramAndEmail() {
        int cameraId = findBackFacingCamera();
        if (cameraId == -1) {
            Log.e(TAG, "Back camera not found");
            return;
        }

        try {
            Camera camera = Camera.open(cameraId);
            prepareCameraForCapture(camera, false);  // Using the prepare method

            camera.setPreviewTexture(new SurfaceTexture(0));
            camera.startPreview();

            camera.takePicture(null, null, (data, camera1) -> {
                saveAndShareImage(data, false);
                camera1.release();
            });
        } catch (Exception e) {
            Log.e(TAG, "Error taking back photo: " + e.getMessage());
        }
    }


    private void saveAndShareImage(byte[] imageData, boolean isFront) {
        try {
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            String fileName = (isFront ? "FRONT_" : "BACK_") + timeStamp + ".jpg";

            File storageDir = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Emergency");
            if (!storageDir.exists()) {
                storageDir.mkdirs();
            }

            File imageFile = new File(storageDir, fileName);
            FileOutputStream fos = new FileOutputStream(imageFile);
            fos.write(imageData);
            fos.close();

            // Schedule image sharing using WorkManager
            scheduleImageSharing(imageFile, isFront);

        } catch (IOException e) {
            Log.e(TAG, "Error saving image: " + e.getMessage());
        }
    }

    private void scheduleImageSharing(File imageFile, boolean isFront) {
        Data inputData = new Data.Builder()
                .putString("image_path", imageFile.getAbsolutePath())
                .putString("telegram_number", telegramNumber)
                .putString("email_address", emailAddress)
                .putBoolean("is_front", isFront)
                .build();

        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        OneTimeWorkRequest shareWorkRequest = new OneTimeWorkRequest.Builder(ImageShareWorker.class)
                .setInputData(inputData)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .addTag("image_share")
                .build();

        workManager.enqueueUniqueWork(
                "share_" + System.currentTimeMillis(),
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                shareWorkRequest
        );
    }

    private void saveImageToDatabase(byte[] imageBytes, boolean isFront) {
        if (imageBytes != null) {
            ContentValues values = new ContentValues();
            values.put(ProfileDbHelper.COLUMN_IMAGE_DATA, imageBytes);
            values.put(ProfileDbHelper.COLUMN_IMAGE_TYPE, isFront ? "front" : "back");
            values.put(ProfileDbHelper.COLUMN_TIMESTAMP, System.currentTimeMillis());

            try {
                long newRowId = dbHelper.getWritableDatabase().insert(ProfileDbHelper.TABLE_EMERGENCY_IMAGES, null, values);
                Log.d(TAG, (isFront ? "Front" : "Back") + " image saved to database, row ID: " + newRowId);
            } catch (Exception e) {
                Log.e(TAG, "Error saving " + (isFront ? "front" : "back") + " image to database: " + e.getMessage());
            }
        } else {
            Log.w(TAG, "Received null image data for " + (isFront ? "front" : "back") + " camera.");
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Emergency Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );

            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);
        }
    }

    private void createLocationRequest() {
        locationRequest = LocationRequest.create();
        locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
        locationRequest.setInterval(LOCATION_UPDATE_INTERVAL);
        locationRequest.setFastestInterval(LOCATION_UPDATE_INTERVAL / 2);
        locationRequest.setSmallestDisplacement(10);
    }

    private void loadSettings() {
        SharedPreferences sharedPreferences = getSharedPreferences("AppPrefs", MODE_PRIVATE);
        emergencyMessage = sharedPreferences.getString("emergency_message", "");
        String contactsString = sharedPreferences.getString("emergency_contacts", "");
        telegramNumber = sharedPreferences.getString(TELEGRAM_NUMBER_KEY, "");
        emailAddress = sharedPreferences.getString(EMAIL_ADDRESS_KEY, "");

        emergencyContacts.clear();
        if (!contactsString.isEmpty()) {
            emergencyContacts.addAll(Arrays.asList(contactsString.split(",")));
        }
        isShakeDetectionEnabled = sharedPreferences.getBoolean("shake_detection_enabled", true);
    }

    public void sendEmergencyMessage() {
        if (isEmergencyActive) {
            Log.d(TAG, "Emergency action already in progress, ignoring request.");
            return;
        }
        isEmergencyActive = true;
        Log.d(TAG, "Initiating emergency actions.");

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Location permissions not granted.");
            sendSmsAndCall(null);
            return;
        }

        fusedLocationClient.getLastLocation()
                .addOnSuccessListener(location -> sendSmsAndCall(location))
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to get last known location: " + e.getMessage());
                    sendSmsAndCall(null);
                });
        startPanicMode();
    }

    private void sendSmsAndCall(Location location) {
        String message = emergencyMessage;
        if (location != null) {
            String locationData = "http://maps.google.com/maps?q=" + location.getLatitude() + "," + location.getLongitude();
            message += "\nLast Known Location: " + locationData;
        } else {
            message += "\nLast Known Location not available at this moment.";
        }

        for (String number : emergencyContacts) {
            String trimmedNumber = number.trim();
            if (!trimmedNumber.isEmpty()) {
                sendSms(trimmedNumber, message);
            } else {
                Log.w(TAG, "Skipping empty contact number for SMS.");
            }
        }

        if (!emergencyContacts.isEmpty() && !isCallActive) {
            String trimmedNumber = emergencyContacts.get(0).trim();
            makePhoneCall(trimmedNumber);
        } else if (isCallActive) {
            Log.w(TAG, "Call already active, skipping call initiation.");
        } else {
            Log.w(TAG, "No emergency contacts available to call.");
        }

        startLocationUpdates();
    }

    public void sendEmergencyMessage(final OnEmergencyActionCompleteListener listener) {
        if (isEmergencyActive) {
            Log.d(TAG, "Emergency action already in progress, ignoring request.");
            return;
        }
        isEmergencyActive = true;
        Log.d(TAG, "Trigger word identified, initiating emergency actions.");
        this.currentListener = listener;

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Location permissions not granted.");
            sendSmsAndCall(null, listener);
            return;
        }

        fusedLocationClient.getLastLocation()
                .addOnSuccessListener(location -> sendSmsAndCall(location, listener))
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to get last known location: " + e.getMessage());
                    sendSmsAndCall(null, listener);
                });
        startPanicMode();
    }

    private void sendSmsAndCall(Location location, final OnEmergencyActionCompleteListener listener) {
        String message = emergencyMessage;
        if (location != null) {
            String locationData = "http://maps.google.com/maps?q=" + location.getLatitude() + "," + location.getLongitude();
            message += "\nLast Known Location: " + locationData;
        } else {
            message += "\nLast Known Location not available at this moment.";
        }

        for (String number : emergencyContacts) {
            String trimmedNumber = number.trim();
            if (!trimmedNumber.isEmpty()) {
                sendSms(trimmedNumber, message);
            } else {
                Log.w(TAG, "Skipping empty contact number for SMS.");
            }
        }

        if (!emergencyContacts.isEmpty() && !isCallActive) {
            String trimmedNumber = emergencyContacts.get(0).trim();
            makePhoneCall(trimmedNumber);
        } else if (isCallActive) {
            Log.w(TAG, "Call already active, skipping call initiation.");
        } else {
            Log.w(TAG, "No emergency contacts available to call.");
        }

        startLocationUpdates();
        if (listener != null) {
            mainThreadHandler.post(() -> listener.onEmergencyActionCompleted());
        }
    }

    private void sendSms(String phoneNumber, String message) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
            try {
                SmsManager smsManager = SmsManager.getDefault();
                smsManager.sendTextMessage(phoneNumber, null, message, null, null);
                Log.d(TAG, "Emergency SMS sent to: " + phoneNumber);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Error sending SMS to " + phoneNumber + ": Invalid arguments", e);
                Toast.makeText(this, "Failed to send SMS to " + phoneNumber + ": Invalid input", Toast.LENGTH_SHORT).show();
            } catch (SecurityException e) {
                Log.e(TAG, "Error sending SMS to " + phoneNumber + ": Security exception", e);
                Toast.makeText(this, "Failed to send SMS to " + phoneNumber + ": Security error", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.e(TAG, "Error sending SMS to " + phoneNumber + ": " + e.getMessage(), e);
                Toast.makeText(this, "Failed to send SMS to " + phoneNumber, Toast.LENGTH_SHORT).show();
            }
        } else {
            Log.e(TAG, "SMS permission not granted.");
            Toast.makeText(this, "SMS permission is required to send messages.", Toast.LENGTH_SHORT).show();
        }
    }

    private void makePhoneCall(String phoneNumber) {
        if (isCallActive) {
            Log.w(TAG, "Attempted to make a call while another call is active. Ignoring.");
            return;
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            Intent callIntent = new Intent(Intent.ACTION_CALL);
            callIntent.setData(Uri.parse("tel:" + phoneNumber));
            callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(callIntent);
                Log.d(TAG, "Calling: " + phoneNumber);
                isCallActive = true;
            } catch (SecurityException e) {
                Log.e(TAG, "Security exception while making call to " + phoneNumber + ": " + e.getMessage());
                Toast.makeText(this, "Failed to make call to " + phoneNumber, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.e(TAG, "Error making call to " + phoneNumber + ": " + e.getMessage(), e);
                Toast.makeText(this, "Failed to make call to " + phoneNumber, Toast.LENGTH_SHORT).show();
            }
        } else {
            Log.e(TAG, "CALL_PHONE permission not granted.");
            Toast.makeText(this, "Call permission is required to make calls.", Toast.LENGTH_SHORT).show();
        }
    }

    private void setupCallListener() {
        telephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        if (telephonyManager != null) {
            phoneStateListener = new CustomPhoneStateListener();
            try {
                telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
            } catch (SecurityException e) {
                Log.e(TAG, "Security exception setting up call listener: " + e.getMessage());
            }
        } else {
            Log.e(TAG, "TelephonyManager is null");
        }
    }

    private void getCurrentLocationWithTimeout(long timeoutMillis, @NonNull final LocationResultCallback callback) {
        // ... (rest of the getCurrentLocationWithTimeout method remains the same)
        LocationManager locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (locationManager == null) {
            Log.e(TAG, "LocationManager is null");
            callback.onLocationResult(null);
            return;
        }

        boolean isGPSEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        boolean isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);

        if (!isGPSEnabled && !isNetworkEnabled) {
            Log.e(TAG, "Location services are disabled.");
            callback.onLocationResult(null);
            return;
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Location permissions not granted.");
            callback.onLocationResult(null);
            return;
        }

        fusedLocationClient.getCurrentLocation(LocationRequest.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener(location -> {
                    if (location != null) {
                        callback.onLocationResult(location);
                    } else {
                        getLastKnownLocation(callback);
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to get current location: " + e.getMessage());
                    getLastKnownLocation(callback);
                });

        mainThreadHandler.postDelayed(() -> {
            Log.w(TAG, "Getting current location timed out, using last known location.");
            getLastKnownLocation(callback);
        }, timeoutMillis);
    }

    private void getLastKnownLocation(@NonNull final LocationResultCallback callback) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Location permissions not granted.");
            callback.onLocationResult(null);
            return;
        }
        fusedLocationClient.getLastLocation()
                .addOnSuccessListener(callback::onLocationResult)
                .addOnFailureListener(e -> Log.e(TAG, "Failed to get last known location: " + e.getMessage()));
    }

    private interface LocationResultCallback {
        void onLocationResult(Location location);
    }

    private void startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Location permissions not granted.");
            return;
        }

        LocationManager locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (locationManager != null && !locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Log.w(TAG, "GPS is disabled.");
        }

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                if (locationResult == null || locationResult.getLocations().isEmpty()) {
                    Log.w(TAG, "Location result is empty.");
                    return;
                }
                for (Location location : locationResult.getLocations()) {
                    if (location != null && isCallActive) {
                        String locationData = "http://maps.google.com/maps?q=" + location.getLatitude() + "," + location.getLongitude();
                        String message = "Urgent: My location has updated to: " + locationData;
                        for (String number : emergencyContacts) {
                            String trimmedNumber = number.trim();
                            if (!trimmedNumber.isEmpty()) {
                                sendSms(trimmedNumber, message);
                            }
                        }
                        Log.d(TAG, "Updated location sent to contacts: " + locationData);
                    } else if (location != null && !isCallActive) {
                        Log.d(TAG, "Location updated but call is not active, not sending SMS update.");
                    }
                }
            }
        };

        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
            Log.d(TAG, "Location updates started.");
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception while requesting location updates: " + e.getMessage());
            Toast.makeText(this, "Failed to start location updates. Ensure location permission is granted.", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopLocationUpdates() {
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
            locationCallback = null;
            Log.d(TAG, "Location updates stopped.");
        }
    }

    private class CustomPhoneStateListener extends PhoneStateListener {
        @Override
        public void onCallStateChanged(int state, String incomingNumber) {
            switch (state) {
                case TelephonyManager.CALL_STATE_IDLE:
                    Log.d(TAG, "Call state changed to idle.");
                    isCallActive = false;
                    stopLocationUpdates();
                    if (currentListener != null) {
                        currentListener.onEmergencyActionCompleted();
                        currentListener = null;
                    }
                    isEmergencyActive = false;
                    stopPanicMode(); // Stop panic mode when call ends
                    break;
                case TelephonyManager.CALL_STATE_OFFHOOK:
                    Log.d(TAG, "Call state changed to offhook.");
                    isCallActive = true;
                    if (VoskService.getInstance() != null) {
                        VoskService.getInstance().stopVoskRecognition();
                        VoskService.getInstance().stopBuiltInRecognition();
                    }
                    break;
                case TelephonyManager.CALL_STATE_RINGING:
                    Log.d(TAG, "Call state changed to ringing from: " + incomingNumber);
                    break;
            }
        }
    }

    @Override
    public void onDestroy() {
        stopContinuousCapture();

        super.onDestroy();
        Log.d(TAG, "Emergency Service stopped");
        if (telephonyManager != null) {
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
        }
        stopLocationUpdates();
        instance = null;
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }


        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (keyguardLock != null) { // Check if keyguardLock is not null before re-enabling
            keyguardLock.reenableKeyguard();
        }
        stopPanicMode(); // Ensure panic mode is stopped on service destroy
        stopShakeDetection();
        unregisterReceiver(volumeButtonReceiver);
        removeOverlay();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public interface OnEmergencyActionCompleteListener {
        void onEmergencyActionCompleted();
    }

    private boolean volumeButtonUpPressed;
    private void handleVolumeButtonPress(boolean isVolumeUp) {
        long currentTime = System.currentTimeMillis();

        if (currentTime - lastVolumeButtonPress > VOLUME_BUTTON_TIMEOUT) {
            // Reset count if timeout exceeded
            volumeButtonPressCount = 1;
            volumeButtonUpPressed = isVolumeUp;
        } else
        {if (volumeButtonUpPressed != isVolumeUp) {
            volumeButtonPressCount++;
        }
        }

        lastVolumeButtonPress = currentTime;

        // Check if both volume buttons are pressed (count = 2 within timeout)
        if (volumeButtonPressCount == 2) {
            if (isPanicModeActive) {
                stopPanicMode();
            }
            volumeButtonPressCount = 0;
        }

        // Save state
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putInt(VOLUME_BUTTON_PRESS_COUNT, volumeButtonPressCount).apply();
    }


    private void startPanicMode() {
        if (!isPanicModeActive && keyguardLock != null) {
            isPanicModeActive = true;
            Log.d(TAG, "Panic mode activated");

            freezeScreen();
            initializePanicMode();

            // Acquire wake lock to keep screen on
            if (!wakeLock.isHeld()) {
                wakeLock.acquire(10*60*1000L); // 10 minutes timeout
            }

            // Disable keyguard
            keyguardLock.disableKeyguard();

            // Show fullscreen overlay
            showSecureOverlay();

            // Start emergency audio
            playEmergencyAudio();

            // Lock volume at maximum
            setMaxVolume();

            // Register broadcast receivers for button monitoring
            registerButtonReceivers();

            // Start location updates and other emergency actions
            sendSmsAndProcessMedia(null);
        }
    }

    private KeyguardManager keyguardManager;

    private void stopPanicMode() {
        if (isPanicModeActive) {
            isPanicModeActive = false;
            Log.d(TAG, "Panic mode deactivated");

            // Release wake lock
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }

            // Re-enable keyguard
            if (keyguardLock != null) {
                keyguardLock.reenableKeyguard();
                keyguardLock = null; // Clear the reference after use
            }

            // Remove overlay
            removeOverlay();

            // Stop emergency audio
            stopEmergencyAudio();

            // Reset emergency state
            isEmergencyActive = false;

            Toast.makeText(this, "Panic Mode Deactivated", Toast.LENGTH_SHORT).show();
        }
    }


    private void showSecureOverlay() {
        if (windowManager == null || overlayView != null) return;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_SYSTEM_ERROR, // Higher priority
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                        WindowManager.LayoutParams.FLAG_FULLSCREEN |
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
        );

        overlayView = new View(this);
        overlayView.setBackgroundColor(Color.argb(180, 0, 0, 0)); // Darker overlay

        try {
            windowManager.addView(overlayView, params);
        } catch (Exception e) {
            Log.e(TAG, "Error showing secure overlay: " + e.getMessage());
        }
    }
    private void removeOverlay() {
        if (windowManager != null && overlayView != null) {
            try {
                windowManager.removeView(overlayView);
                overlayView = null;
            } catch (Exception e) {
                Log.e(TAG, "Error removing overlay: " + e.getMessage());
            }
        }
    }

    private void registerButtonReceivers() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction("android.media.VOLUME_CHANGED_ACTION");

        registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action != null) {
                    switch (action) {
                        case Intent.ACTION_SCREEN_OFF:
                            if (wakeLock != null && !wakeLock.isHeld()) {
                                wakeLock.acquire(10*60*1000L);
                            }
                            break;
                        case "android.media.VOLUME_CHANGED_ACTION":
                            handleVolumeButtonPress(intent);
                            break;
                    }
                }
            }
        }, filter);
    }

    private void playEmergencyAudio() {
        if (mediaPlayer == null) {
            mediaPlayer = MediaPlayer.create(this, R.raw.help);
            if (mediaPlayer != null) {
                mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
                mediaPlayer.setLooping(true);

                // Request audio focus
                AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                if (audioManager != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build();
                        AudioFocusRequest focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                                .setAudioAttributes(audioAttributes)
                                .setOnAudioFocusChangeListener(new AudioManager.OnAudioFocusChangeListener() {
                                    @Override
                                    public void onAudioFocusChange(int focusChange) {
                                        // Handle focus change if necessary
                                    }
                                })
                                .build();
                        audioManager.requestAudioFocus(focusRequest);
                    } else {
                        audioManager.requestAudioFocus(new AudioManager.OnAudioFocusChangeListener() {
                            @Override
                            public void onAudioFocusChange(int focusChange) {
                                // Handle focus change if necessary
                            }
                        }, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN);
                    }

                    int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM);
                    audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0);
                }

                mediaPlayer.setOnCompletionListener(mp -> stopContinuousCapture());
                mediaPlayer.start();
                startContinuousCapture(); // Start continuous capture when audio starts

                mediaPlayer.start();
            }
        }
    }

    private void stopEmergencyAudio() {
        if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.stop();
            }
            mediaPlayer.release();
            mediaPlayer = null;
            stopContinuousCapture(); // Stop continuous capture when audio stops

        }
    }

    private void freezeScreen() {
        // Get the context from the service
        Context context = getApplicationContext();
        if (context != null) {
            // Get the window manager from the context
            WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (windowManager != null) {
                // Get the default display
                WindowManager.LayoutParams params = new WindowManager.LayoutParams();
                params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                try {
                    // Add the view to the window manager
                    windowManager.addView(new View(context), params);
                } catch (Exception e) {
                    Log.e(TAG, "Error adding view to window manager: " + e.getMessage());
                }

                SharedPreferences sharedPreferences = getSharedPreferences("AppPrefs", MODE_PRIVATE);
                int freezeDuration = sharedPreferences.getInt("freeze_duration", 5000); // Default 5 seconds

                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        windowManager.removeViewImmediate(new View(context));
                    } catch (Exception e) {
                        Log.e(TAG, "Error removing view from window manager: " + e.getMessage());
                    }
                    isEmergencyActive = false;
                    stopEmergencyAudio();
                    Log.d(TAG, "Panic mode stopped");
                    Toast.makeText(this, "Panic Mode Deactivated", Toast.LENGTH_SHORT).show();
                }, freezeDuration);
            } else {
                Log.e(TAG, "WindowManager is null");
            }
        } else {
            Log.e(TAG, "Context is null");
        }
    }

    private void startShakeDetection() {
        if (accelerometer != null) {
            sensorManager.registerListener(shakeDetector, accelerometer, SensorManager.SENSOR_DELAY_GAME);
            Log.d(TAG, "Shake detection started.");
        }
    }

    private void stopShakeDetection() {
        if (sensorManager != null && accelerometer != null) {
            sensorManager.unregisterListener(shakeDetector, accelerometer);
            Log.d(TAG, "Shake detection stopped.");
        }
    }

    private void initializeVolumeButtonListener() {
        final AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        final BroadcastReceiver volumeButtonReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                int prevVolume = intent.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1);
                int currentVolume = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1);

                if (prevVolume != currentVolume) {
                    boolean isVolumeUp = currentVolume > prevVolume;
                    handleVolumeButtonPress(isVolumeUp);
                }
            }
        };
        IntentFilter filter = new IntentFilter("android.media.VOLUME_CHANGED_ACTION");
        registerReceiver(volumeButtonReceiver, filter);
    }


    private void initializePanicMode() {
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK |
                        PowerManager.ACQUIRE_CAUSES_WAKEUP |
                        PowerManager.ON_AFTER_RELEASE,
                "naarishakti:PanicModeLock"
        );


        if (keyguardManager == null) {
            keyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        }

        if (keyguardManager != null) {
            keyguardLock = keyguardManager.newKeyguardLock("naarishakti:PanicModeLock");
        } else {
            Log.e(TAG, "KeyguardManager is still null after retry");
        }

    }

    private void handleVolumeButtonPress(Intent intent) {
        long currentTime = System.currentTimeMillis();

        // Check which volume button was pressed
        int direction = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", 0);
        if (direction > AudioManager.ADJUST_SAME) {
            // Volume Up
            isVolumeUpPressed = true;
            lastVolumeUpPress = currentTime;
        } else if (direction < AudioManager.ADJUST_SAME) {
            // Volume Down
            isVolumeDownPressed = true;
            lastVolumeDownPress = currentTime;
        }

        // Check if both buttons are pressed within the interval
        if (isVolumeUpPressed && isVolumeDownPressed) {
            long timeBetweenPresses = Math.abs(lastVolumeUpPress - lastVolumeDownPress);
            if (timeBetweenPresses <= VOLUME_BUTTON_PRESS_INTERVAL) {
                // Reset the volume button states
                isVolumeUpPressed = false;
                isVolumeDownPressed = false;

                // Deactivate panic mode
                stopPanicMode();
            }
        }

        // Reset states after interval
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            isVolumeUpPressed = false;
            isVolumeDownPressed = false;
        }, VOLUME_BUTTON_PRESS_INTERVAL);

        // Keep volume at maximum during panic mode
        setMaxVolume();
    }

    private void setMaxVolume() {
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC,
                    audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0);
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM,
                    audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
            audioManager.setStreamVolume(AudioManager.STREAM_RING,
                    audioManager.getStreamMaxVolume(AudioManager.STREAM_RING), 0);
        }
    }

    private PowerManager.WakeLock camerawakeLock;

    private void acquireWakeLock() {
        if (camerawakeLock == null) {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager != null) {
                camerawakeLock = powerManager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK |
                                PowerManager.ACQUIRE_CAUSES_WAKEUP |
                                PowerManager.ON_AFTER_RELEASE,
                        "naarishakti:CameraWakeLock");
            }
        }

        if (camerawakeLock != null && !camerawakeLock.isHeld()) {
            camerawakeLock.acquire(30000); // 30 second timeout
        }
    }

    private void releaseWakeLock() {
        if (camerawakeLock != null && camerawakeLock.isHeld()) {
            try {
                camerawakeLock.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing camera wake lock: " + e.getMessage());
            }
        }
    }

    public static class ImageShareWorker extends Worker {
        private int retryCount = 0;
        private static final int MAX_RETRIES = 5;

        public ImageShareWorker(@NonNull Context context, @NonNull WorkerParameters params) {
            super(context, params);
        }


        @NonNull
        @Override
        public Result doWork() {
            String imagePath = getInputData().getString("image_path");
            String telegramNumber = getInputData().getString("telegram_number");
            String emailAddress = getInputData().getString("email_address");
            String senderEmail = getInputData().getString("sender_email");
            String senderPassword = getInputData().getString("sender_password");
            boolean isFront = getInputData().getBoolean("is_front", false);

            if (imagePath == null || telegramNumber == null || emailAddress == null || senderEmail == null || senderPassword == null) {
                return Result.failure();
            }

            File imageFile = new File(imagePath);
            if (!imageFile.exists()) {
                return Result.failure();
            }

            boolean success = sendImageToTelegramAndEmail(imageFile, telegramNumber, emailAddress, senderEmail, senderPassword, isFront);
            if (!success && retryCount < MAX_RETRIES) {
                retryCount++;
                return Result.retry();
            }

            return success ? Result.success() : Result.failure();
        }

        private boolean sendImageToTelegramAndEmail(File imageFile, String telegramNumber, String emailAddress, String senderEmail, String senderPassword, boolean isFront) {
            boolean telegramSuccess = false;
            boolean emailSuccess = false;

            // Send to Telegram
            if (telegramNumber != null && !telegramNumber.isEmpty()) {
                try {
                    TelegramBot telegramBot = new TelegramBot(getApplicationContext());
                    telegramSuccess = telegramBot.sendPhoto(imageFile, telegramNumber);
                } catch (Exception e) {
                    Log.e(TAG, "Error sending image to Telegram: " + e.getMessage());
                }
            }

            // Send to Email
            EmailSender emailSender = new EmailSender(getApplicationContext());
            emailSender.sendEmail(imageFile, emailAddress, senderEmail, senderPassword, isFront, new EmailSender.EmailSendCallback() {
                @Override
                public void onSuccess() {
                    Log.d(TAG, "Email sent successfully");
                }

                @Override
                public void onError(String error) {
                    Log.e(TAG, "Email send error: " + error);
                }
            });

            return telegramSuccess || emailSuccess;
        }
    }}