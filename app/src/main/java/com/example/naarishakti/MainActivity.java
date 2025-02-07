package com.example.naarishakti;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.ActivityManager;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.*;
import android.util.Log;
import android.Manifest;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import com.example.naarishakti.databinding.ActivityMainBinding;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.navigation.NavigationBarView;

import java.util.ArrayList;
import java.util.List;

import Home_Activity.DatabaseViewActivity;
import Home_Activity.ProfileActivity;
import Home_Activity.TimeSettingsActivity;
import SQLite_Database.ProfileDbHelper;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int PERMISSIONS_REQUEST_CODE = 100;

    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CAMERA,
            Manifest.permission.POST_NOTIFICATIONS
    };


    private ActivityMainBinding binding;
    private PowerButtonReceiver powerButtonReceiver;
    private ServiceStateReceiver serviceStateReceiver;
    private boolean isServiceRunning = false;
    private boolean isInitializationInProgress = false;
    private boolean isDestructionInProgress = false;

    private ProfileDbHelper dbHelper;
    private SQLiteDatabase db;
    private Vibrator vibrator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        initializeComponents();
        updateServiceStatus();
        setupClickListeners();
        checkFirstRun();
        initializePowerButtonReceiver();

        if (getIntent().getBooleanExtra("loadHomeFragment", false)) {
            handleNavigation(R.id.menu_home);
        }
    }

    private void initializeComponents() {
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        dbHelper = new ProfileDbHelper(this);
        db = dbHelper.getReadableDatabase();
    }

    private void setupClickListeners() {
        binding.profileIcon.setOnClickListener(v -> navigateToProfile());
        binding.alarmBtn.setOnClickListener(v -> startActivity(new Intent(this, TimeSettingsActivity.class)));
        binding.viewDatabaseButton.setOnClickListener(v -> startActivity(new Intent(this, DatabaseViewActivity.class)));

        binding.bottomNavigation.setOnItemSelectedListener(item -> {
            handleNavigation(item.getItemId());
            return true;
        });

        binding.startServiceButton.setOnClickListener(v -> toggleServices()); // Add the service button click listener
    }

    private void navigateToProfile() {
        triggerHapticFeedback();
        startActivity(new Intent(this, ProfileActivity.class));
    }

    private boolean handleNavigation(int itemId) {
        FragmentManager fm = getSupportFragmentManager();
        FragmentTransaction ft = fm.beginTransaction()
                .setCustomAnimations(R.anim.slide_in, R.anim.slide_out); // Keep animations

        Fragment homeFragment = fm.findFragmentByTag("HomeFragment");
        Fragment settingsFragment = fm.findFragmentByTag("SettingsFragment");
        Fragment fragmentToShow = null; // Track which fragment we want to show

        if (itemId == R.id.menu_home) {
            if (homeFragment == null) {
                homeFragment = new HomeFragment();
                ft.add(R.id.fragment_container, homeFragment, "HomeFragment"); // Add, not replace, the first time
            }
            fragmentToShow = homeFragment;
        } else if (itemId == R.id.menu_settings) {
            if (settingsFragment == null) {
                settingsFragment = new SettingsFragment();
                ft.add(R.id.fragment_container, settingsFragment, "SettingsFragment");
            }
            fragmentToShow = settingsFragment;
        } else {
            return false; // Indicate that the item was not handled
        }

        // Hide all fragments first
        for (Fragment fragment : fm.getFragments()) {
            if (fragment != null && fragment.isVisible()) { // Check if visible to avoid unnecessary calls
                ft.hide(fragment);
            }
        }

        // Show the fragment we want to display
        if (fragmentToShow != null) {
            ft.show(fragmentToShow);
        }

        ft.commit();
        return true;
    }

    private void checkFirstRun() {
        SharedPreferences prefs = getSharedPreferences("com.example.naarishakti", MODE_PRIVATE);
        if (prefs.getBoolean("first_run", true)) {
            showWelcomeAnimation();
            prefs.edit().putBoolean("first_run", false).apply();
        }
    }

    private void showWelcomeAnimation() {
        binding.statusCard.animate()
                .scaleY(1.2f)
                .scaleX(1.2f)
                .setDuration(500)
                .withEndAction(() -> binding.statusCard.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(300))
                .start();
    }



    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                toggleServices();
            } else {
                showPermissionWarning();
            }
        }
    }

    private void showPermissionWarning() {
        binding.statusTextView.setText("Permissions Required!");
        binding.statusCard.setCardBackgroundColor(ContextCompat.getColor(this, R.color.error_color));
        binding.statusCard.animate()
                .scaleY(1.1f)
                .scaleX(1.1f)
                .setDuration(300)
                .withEndAction(() -> binding.statusCard.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(200));
    }

    public void toggleServices() {
        if (isServiceRunning) {
            stopServices();
        } else {
            startServices();
        }
        animateFAB();
        updateServiceStatus();
    }

    private void animateFAB() {
        binding.startServiceButton.animate()
                .scaleX(0.8f)
                .scaleY(0.8f)
                .setDuration(150)
                .withEndAction(() -> binding.startServiceButton.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(150))
                .start();
    }

    private void startServices() {
        if (isInitializationInProgress) return;
        isInitializationInProgress = true;

        // Start Voice Recognition Service
        Intent voiceService = new Intent(this, VoiceRecognitionService.class);
        ContextCompat.startForegroundService(this, voiceService);

        // Start Vosk Service
        Intent voskService = new Intent(this, VoskService.class);
        voskService.putExtra("start_service", true);
        ContextCompat.startForegroundService(this, voskService);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            isServiceRunning = true;
            isInitializationInProgress = false;
            updateServiceStatus();
            sendServiceStateBroadcast();
        }, 2000);
    }

    private void stopServices() {
        if (isDestructionInProgress) return;
        isDestructionInProgress = true;

        stopService(new Intent(this, VoskService.class));
        stopService(new Intent(this, VoiceRecognitionService.class));

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            isServiceRunning = false;
            isDestructionInProgress = false;
            updateServiceStatus();
            sendServiceStateBroadcast();
        }, 2000);
    }

    private void updateServiceStatus() {
        runOnUiThread(() -> {
            if (isServiceRunning(VoskService.class)) {
                binding.statusTextView.setText("Protection Active");
                binding.statusCard.setCardBackgroundColor(ContextCompat.getColor(this, R.color.success_green));
            } else {
                binding.statusTextView.setText("Service Stopped");
                binding.statusCard.setCardBackgroundColor(ContextCompat.getColor(this, R.color.error_color));
            }

            // Update FAB state
            binding.startServiceButton.setEnabled(!isInitializationInProgress && !isDestructionInProgress);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadProfileImage();
        registerReceivers();

        if (getIntent().getBooleanExtra("loadHomeFragment", false)) {
            handleNavigation(R.id.menu_home);
        }
        CheckPermission();
    }

    private void CheckPermission(){
        List<String> permissionsToRequest = new ArrayList<>();
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission);
            }
        }

        if (!permissionsToRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toArray(new String[0]), PERMISSIONS_REQUEST_CODE);
        }
    }

    private void registerReceivers() {
        serviceStateReceiver = new ServiceStateReceiver();
        LocalBroadcastManager.getInstance(this)
                .registerReceiver(serviceStateReceiver,
                        new IntentFilter("com.example.naarishakti.ACTION_SERVICE_STATE_CHANGED"));
    }

    private void loadProfileImage() {
        try (Cursor cursor = db.query(ProfileDbHelper.TABLE_NAME,
                new String[]{ProfileDbHelper.COLUMN_PROFILE_IMAGE},
                null, null, null, null, null)) {

            if (cursor != null && cursor.moveToFirst()) {
                byte[] imageBytes = cursor.getBlob(cursor.getColumnIndexOrThrow(ProfileDbHelper.COLUMN_PROFILE_IMAGE));
                if (imageBytes != null) {
                    Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
                    binding.profileIcon.setImageBitmap(bitmap);
                    return;
                }
            }
            binding.profileIcon.setImageResource(R.drawable.ic_profile);
        } catch (Exception e) {
            Log.e(TAG, "Profile image load error: ", e);
            binding.profileIcon.setImageResource(R.drawable.ic_profile);
        }
    }

    private void triggerHapticFeedback() {
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK));
            } else {
                vibrator.vibrate(15);
            }
        }
    }

    private class ServiceStateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateServiceStatus();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Cleanup resources
        if (db != null) db.close();
        if (dbHelper != null) dbHelper.close();
        if (serviceStateReceiver != null) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(serviceStateReceiver);
        }
        if (powerButtonReceiver != null) {
            unregisterReceiver(powerButtonReceiver);
        }
    }

    // Existing utility methods
    public boolean isServiceRunning(Class<?> serviceClass) {
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    private void sendServiceStateBroadcast() {
        LocalBroadcastManager.getInstance(this)
                .sendBroadcast(new Intent("com.example.naarishakti.ACTION_SERVICE_STATE_CHANGED"));
    }

    private void initializePowerButtonReceiver() {
        powerButtonReceiver = new PowerButtonReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(powerButtonReceiver, filter);
    }
}