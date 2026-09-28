package com.example.naarishakti;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.SystemClock;
import android.util.Log;

/**
 * Fires {@link OnShakeListener#onShake()} once when the phone is shaken hard {@link #REQUIRED_SHAKES}
 * times within {@link #WINDOW_MS}, then ignores further shakes for {@link #COOLDOWN_MS}.
 */
public class ShakeDetector implements SensorEventListener {

    private static final String TAG = "ShakeDetector";

    private static final float SHAKE_THRESHOLD_GRAVITY = 2.7f;
    /** Minimum gap between two counted shakes, so one jerk is not counted several times. */
    private static final long SHAKE_SLOP_MS = 250;
    private static final long WINDOW_MS = 1_500;
    private static final int REQUIRED_SHAKES = 3;
    private static final long COOLDOWN_MS = 5_000;

    public interface OnShakeListener {
        void onShake();
    }

    private OnShakeListener listener;
    private long firstShakeAt;
    private long lastShakeAt;
    private int shakeCount;
    private long cooldownUntil;

    public void setOnShakeListener(OnShakeListener listener) {
        this.listener = listener;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event == null || event.values == null || event.values.length < 3 || listener == null) {
            return;
        }
        float gX = event.values[0] / SensorManager.GRAVITY_EARTH;
        float gY = event.values[1] / SensorManager.GRAVITY_EARTH;
        float gZ = event.values[2] / SensorManager.GRAVITY_EARTH;
        double gForce = Math.sqrt(gX * gX + gY * gY + gZ * gZ);
        if (gForce <= SHAKE_THRESHOLD_GRAVITY) return;

        long now = SystemClock.elapsedRealtime();
        if (now < cooldownUntil || now - lastShakeAt < SHAKE_SLOP_MS) return;

        if (shakeCount == 0 || now - firstShakeAt > WINDOW_MS) {
            shakeCount = 0;
            firstShakeAt = now;
        }
        lastShakeAt = now;
        shakeCount++;
        Log.d(TAG, "Shake " + shakeCount + "/" + REQUIRED_SHAKES);

        if (shakeCount >= REQUIRED_SHAKES) {
            shakeCount = 0;
            cooldownUntil = now + COOLDOWN_MS;
            listener.onShake();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Not needed.
    }
}
