package com.example.naarishakti.triggers;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;

import com.example.naarishakti.core.ProtectionController;

/**
 * Hard-fall and struggle detection from the accelerometer (SENSOR_DELAY_GAME, ~50 Hz), processed on
 * its own thread. Either pattern fires {@code triggerPanic(ctx, "fall")}; the engine's cancellable
 * countdown doubles as the "Are you OK?" prompt. 60 s cooldown after firing.
 *
 * <p><b>Fall</b>: free fall (|a| &lt; 3 m/s² for &ge; 80 ms), an impact (&gt; 25 m/s²) within 1 s,
 * then ~10 s of near-stillness during which the phone's orientation differs from before the fall
 * (a person who fell is lying down; a runner who stopped is not).
 *
 * <p><b>Struggle</b>: &ge; 6 peaks &gt; 22 m/s² within 3 s whose directions are scattered (a
 * struggle or violent shaking). Running also produces strong peaks, but they all point the same
 * way, so they are rejected by the direction check.
 */
final class FallDetector implements SensorEventListener {

    private static final String TAG = "FallDetector";

    private static final float G = SensorManager.GRAVITY_EARTH;

    // ---- Fall ----
    private static final float FREE_FALL_MAX = 3f;
    private static final long FREE_FALL_MIN_MS = 80;
    private static final long IMPACT_WINDOW_MS = 1_000;
    private static final float IMPACT_MIN = 25f;
    /** Let the phone bounce / settle before judging stillness. */
    private static final long SETTLE_MS = 1_500;
    private static final long STILL_WINDOW_MS = 10_000;
    /** A sample is "still" when |a| is within this of 1 g. */
    private static final float STILL_TOLERANCE = 1.5f;
    /** Fraction of the stillness window that must be still. */
    private static final float STILL_FRACTION = 0.9f;
    /** Posture must have changed by at least this many degrees (standing -> lying is ~90). */
    private static final double MIN_POSTURE_CHANGE_DEG = 35;

    // ---- Struggle ----
    private static final float PEAK_MIN = 22f;
    /** Hysteresis: the signal must drop below this before the next peak counts. */
    private static final float PEAK_RESET = 16f;
    private static final int PEAKS_REQUIRED = 6;
    private static final long PEAK_WINDOW_MS = 3_000;
    /** Mean resultant length of peak directions; 1 = all identical (running), ~0 = random. */
    private static final float MAX_DIRECTION_CONSISTENCY = 0.6f;

    private static final long COOLDOWN_MS = 60_000;

    private enum State { IDLE, FREE_FALL, AWAIT_IMPACT, AWAIT_STILL }

    private final Context app;
    @Nullable private SensorManager sensors;
    @Nullable private HandlerThread thread;
    private volatile boolean running;

    // ---- Sensor-thread state ----
    private State state = State.IDLE;
    private long freeFallStart;
    private long freeFallEnd;
    private long impactAt;
    private int stillSamples;
    private int totalSamples;
    private final float[] postureBefore = new float[3];
    private final float[] postureAfterSum = new float[3];

    /** Slow low-pass gravity estimate, only updated while the phone is not being thrown about. */
    private final float[] gravity = new float[]{0f, 0f, G};
    private boolean gravityInit;

    private final long[] peakTimes = new long[16];
    private final float[][] peakDirs = new float[16][3];
    private int peakCount;
    private boolean aboveThreshold;

    private long cooldownUntil;

    FallDetector(Context ctx) {
        this.app = ctx.getApplicationContext();
    }

    /** @return false when the device has no accelerometer. */
    boolean start() {
        if (running) return true;
        sensors = (SensorManager) app.getSystemService(Context.SENSOR_SERVICE);
        Sensor accel = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accel == null) {
            Log.w(TAG, "No accelerometer; fall detection unavailable");
            return false;
        }
        thread = new HandlerThread("ns-fall");
        thread.start();
        reset();
        running = sensors.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME,
                new Handler(thread.getLooper()));
        if (!running) {
            thread.quitSafely();
            thread = null;
        }
        Log.i(TAG, "Fall detection " + (running ? "on" : "failed to start"));
        return running;
    }

    void stop() {
        if (!running) return;
        running = false;
        if (sensors != null) sensors.unregisterListener(this);
        if (thread != null) thread.quitSafely();
        thread = null;
        Log.i(TAG, "Fall detection off");
    }

    boolean isRunning() {
        return running;
    }

    private void reset() {
        state = State.IDLE;
        peakCount = 0;
        aboveThreshold = false;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!running) return;
        long now = event.timestamp / 1_000_000L; // elapsedRealtime base, ms
        float x = event.values[0], y = event.values[1], z = event.values[2];
        float mag = (float) Math.sqrt(x * x + y * y + z * z);

        updateGravity(x, y, z, mag);
        detectStruggle(now, x, y, z, mag);
        detectFall(now, x, y, z, mag);
    }

    private void updateGravity(float x, float y, float z, float mag) {
        if (Math.abs(mag - G) > 2f) return; // only learn posture while roughly at rest
        if (!gravityInit) {
            gravity[0] = x; gravity[1] = y; gravity[2] = z;
            gravityInit = true;
            return;
        }
        final float alpha = 0.97f; // ~0.6 s time constant at 50 Hz
        gravity[0] = alpha * gravity[0] + (1 - alpha) * x;
        gravity[1] = alpha * gravity[1] + (1 - alpha) * y;
        gravity[2] = alpha * gravity[2] + (1 - alpha) * z;
    }

    // ------------------------------------------------------------------ fall

    private void detectFall(long now, float x, float y, float z, float mag) {
        switch (state) {
            case IDLE:
                if (mag < FREE_FALL_MAX) {
                    state = State.FREE_FALL;
                    freeFallStart = now;
                    System.arraycopy(gravity, 0, postureBefore, 0, 3);
                }
                break;

            case FREE_FALL:
                if (mag < FREE_FALL_MAX) break;
                if (now - freeFallStart >= FREE_FALL_MIN_MS) {
                    state = State.AWAIT_IMPACT;
                    freeFallEnd = now;
                    // The sample that ended free fall may itself be the impact.
                    checkImpact(now, mag);
                } else {
                    state = State.IDLE; // too short: a jolt, a step, not a fall
                }
                break;

            case AWAIT_IMPACT:
                if (now - freeFallEnd > IMPACT_WINDOW_MS) {
                    state = State.IDLE; // soft landing (bed, sofa, caught): ignore
                } else {
                    checkImpact(now, mag);
                }
                break;

            case AWAIT_STILL:
                long since = now - impactAt;
                if (since < SETTLE_MS) break;
                if (since <= SETTLE_MS + STILL_WINDOW_MS) {
                    totalSamples++;
                    if (Math.abs(mag - G) <= STILL_TOLERANCE) stillSamples++;
                    postureAfterSum[0] += x; postureAfterSum[1] += y; postureAfterSum[2] += z;
                    // Early exit once too much movement makes success impossible (she picked it up).
                    int expected = (int) (STILL_WINDOW_MS / 20); // ~50 Hz
                    int moving = totalSamples - stillSamples;
                    if (moving > expected * (1f - STILL_FRACTION) + 5) state = State.IDLE;
                    break;
                }
                evaluateStillness(now);
                break;
        }
    }

    private void checkImpact(long now, float mag) {
        if (mag > IMPACT_MIN) {
            state = State.AWAIT_STILL;
            impactAt = now;
            stillSamples = 0;
            totalSamples = 0;
            postureAfterSum[0] = postureAfterSum[1] = postureAfterSum[2] = 0f;
        }
    }

    private void evaluateStillness(long now) {
        state = State.IDLE;
        if (totalSamples < 50) return; // sensor stalled; can't judge
        float stillRatio = stillSamples / (float) totalSamples;
        double angle = angleDeg(postureBefore, postureAfterSum);
        Log.d(TAG, "Post-impact stillness " + stillRatio + ", posture change " + angle + " deg");
        if (stillRatio >= STILL_FRACTION && angle >= MIN_POSTURE_CHANGE_DEG) {
            fire(now, "hard fall");
        }
    }

    // ------------------------------------------------------------------ struggle

    private void detectStruggle(long now, float x, float y, float z, float mag) {
        if (mag < PEAK_RESET) {
            aboveThreshold = false;
            return;
        }
        if (mag < PEAK_MIN || aboveThreshold) return;
        aboveThreshold = true;

        // Direction of the peak with gravity removed.
        float lx = x - gravity[0], ly = y - gravity[1], lz = z - gravity[2];
        float len = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
        if (len < 1e-3f) return;

        // Drop peaks older than the window.
        int keep = 0;
        for (int i = 0; i < peakCount; i++) {
            if (now - peakTimes[i] <= PEAK_WINDOW_MS) {
                peakTimes[keep] = peakTimes[i];
                System.arraycopy(peakDirs[i], 0, peakDirs[keep], 0, 3);
                keep++;
            }
        }
        peakCount = keep;
        if (peakCount == peakTimes.length) return;
        peakTimes[peakCount] = now;
        peakDirs[peakCount][0] = lx / len;
        peakDirs[peakCount][1] = ly / len;
        peakDirs[peakCount][2] = lz / len;
        peakCount++;

        if (peakCount >= PEAKS_REQUIRED) {
            float sx = 0, sy = 0, sz = 0;
            for (int i = 0; i < peakCount; i++) {
                sx += peakDirs[i][0]; sy += peakDirs[i][1]; sz += peakDirs[i][2];
            }
            float consistency = (float) Math.sqrt(sx * sx + sy * sy + sz * sz) / peakCount;
            if (consistency <= MAX_DIRECTION_CONSISTENCY) {
                peakCount = 0;
                fire(now, "violent motion");
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private void fire(long now, String why) {
        long wall = SystemClock.elapsedRealtime();
        if (wall < cooldownUntil || ProtectionController.isPanicActive()) return;
        cooldownUntil = wall + COOLDOWN_MS;
        state = State.IDLE;
        peakCount = 0;
        Log.i(TAG, "Detected " + why + "; triggering SOS");
        ProtectionController.triggerPanic(app, "fall");
    }

    private static double angleDeg(float[] a, float[] b) {
        double na = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
        double nb = Math.sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2]);
        if (na < 1e-3 || nb < 1e-3) return 0;
        double cos = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) / (na * nb);
        cos = Math.max(-1, Math.min(1, cos));
        return Math.toDegrees(Math.acos(cos));
    }
}
