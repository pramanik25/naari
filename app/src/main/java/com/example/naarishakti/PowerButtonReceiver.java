package com.example.naarishakti;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

public class PowerButtonReceiver extends BroadcastReceiver {

    private static final String TAG = "PowerButtonReceiver";
    private static final int PRESS_COUNT = 3;
    private static final int TIME_WINDOW = 1500; // Time window of 1.5 seconds
    private int pressCounter = 0;
    private long lastPressTime = 0;

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "PowerButtonReceiver: onReceive called!");
        if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction()) || Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
            long currentTime = SystemClock.elapsedRealtime();
            Log.d(TAG, "Power button action detected at time: " + currentTime);

            if (currentTime - lastPressTime > TIME_WINDOW) {
                Log.d(TAG, "Press timeout, resetting counter");
                pressCounter = 0;
            }

            pressCounter++;
            lastPressTime = currentTime;
            Log.d(TAG, "Power button press detected. Press count: " + pressCounter + " Time since last press: " + (currentTime - lastPressTime));

            if (pressCounter >= PRESS_COUNT) {
                Log.d(TAG, "Triple Power button press detected, triggering panic action.");

                // Start the VoiceRecognitionService if it's not already running
                Intent serviceIntent = new Intent(context, VoiceRecognitionService.class);
                serviceIntent.setAction(VoiceRecognitionService.ACTION_TRIGGER_PANIC); // Add an action to specify panic trigger
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }

                pressCounter = 0;
            }
        }
    }
}