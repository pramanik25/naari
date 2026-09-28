package com.example.naarishakti;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import com.example.naarishakti.core.Prefs;
import com.example.naarishakti.core.ProtectionController;

import java.util.ArrayDeque;

/**
 * Counts power-button presses (each press toggles the screen, so SCREEN_ON/OFF are counted) and
 * triggers an SOS after {@link Prefs#REQUIRED_PRESSES} presses inside {@link Prefs#PRESS_WINDOW_MS}.
 *
 * SCREEN_ON/OFF are only delivered to receivers registered at runtime, so VoiceRecognitionService
 * registers this for as long as protection is on (see {@link #filter()}).
 */
public class PowerButtonReceiver extends BroadcastReceiver {

    private static final String TAG = "PowerButtonReceiver";

    private final ArrayDeque<Long> presses = new ArrayDeque<>();

    public static IntentFilter filter() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        return filter;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_SCREEN_OFF.equals(action) && !Intent.ACTION_SCREEN_ON.equals(action)) return;

        SharedPreferences prefs = Prefs.get(context);
        if (!prefs.getBoolean(Prefs.POWER_BUTTON_ENABLED, true)) {
            presses.clear();
            return;
        }
        int required = Math.max(2, (int) VoiceRecognitionService.prefNumber(prefs,
                Prefs.REQUIRED_PRESSES, Prefs.DEFAULT_REQUIRED_PRESSES));
        long window = Math.max(500L, VoiceRecognitionService.prefNumber(prefs,
                Prefs.PRESS_WINDOW_MS, Prefs.DEFAULT_PRESS_WINDOW_MS));

        long now = SystemClock.elapsedRealtime();
        presses.addLast(now);
        while (!presses.isEmpty() && now - presses.peekFirst() > window) presses.pollFirst();
        Log.d(TAG, "Power press " + presses.size() + "/" + required);

        if (presses.size() >= required) {
            presses.clear();
            ProtectionController.triggerPanic(context, "power");
        }
    }
}
