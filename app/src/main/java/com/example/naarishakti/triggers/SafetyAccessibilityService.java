package com.example.naarishakti.triggers;

import android.accessibilityservice.AccessibilityService;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import com.example.naarishakti.core.Prefs;
import com.example.naarishakti.core.ProtectionController;

/**
 * Volume-key SOS that also works with the screen off or locked: pressing volume-down 5 times
 * within 3 s fires {@code triggerPanic(ctx, "volume_keys")}.
 *
 * Key events are only observed, never consumed ({@link #onKeyEvent} always returns false), so the
 * volume keeps working normally. No window content is read.
 */
public class SafetyAccessibilityService extends AccessibilityService {

    private static final String TAG = "SafetyA11yService";

    private static final int PRESSES_REQUIRED = 5;
    private static final long WINDOW_MS = 3_000L;
    private static final long COOLDOWN_MS = 10_000L;

    private final long[] presses = new long[PRESSES_REQUIRED];
    private int pressCount;
    private long cooldownUntil;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "Accessibility service connected");
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        try {
            if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN
                    && event.getAction() == KeyEvent.ACTION_DOWN
                    && event.getRepeatCount() == 0) {
                onVolumeDown();
            }
        } catch (Exception e) {
            Log.e(TAG, "Key handling failed", e);
        }
        return false; // never consume: normal volume behaviour stays untouched
    }

    private void onVolumeDown() {
        if (!Prefs.get(this).getBoolean(Prefs.VOLUME_KEY_TRIGGER, true)) return;
        if (!ProtectionController.isProtectionWanted(this)) return;
        // During an SOS the volume keys belong to the deactivation pattern.
        if (ProtectionController.isPanicActive()) {
            pressCount = 0;
            return;
        }
        long now = SystemClock.elapsedRealtime();
        int keep = 0;
        for (int i = 0; i < pressCount; i++) {
            if (now - presses[i] <= WINDOW_MS) presses[keep++] = presses[i];
        }
        pressCount = keep;
        if (pressCount == presses.length) {
            System.arraycopy(presses, 1, presses, 0, presses.length - 1);
            pressCount--;
        }
        presses[pressCount++] = now;

        if (pressCount >= PRESSES_REQUIRED) {
            pressCount = 0;
            if (now < cooldownUntil) return;
            cooldownUntil = now + COOLDOWN_MS;
            Log.i(TAG, "Volume-key pattern; triggering SOS");
            ProtectionController.triggerPanic(getApplicationContext(), "volume_keys");
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Intentionally empty: this service only watches hardware keys.
    }

    @Override
    public void onInterrupt() {
        // Nothing to interrupt.
    }
}
