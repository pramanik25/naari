package com.example.naarishakti.triggers;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;

import com.example.naarishakti.VoskService;
import com.example.naarishakti.core.ProtectionController;
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifier;
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifierResult;
import com.google.mediapipe.tasks.audio.core.RunningMode;
import com.google.mediapipe.tasks.components.containers.AudioData;
import com.google.mediapipe.tasks.components.containers.Category;
import com.google.mediapipe.tasks.components.containers.ClassificationResult;
import com.google.mediapipe.tasks.components.containers.Classifications;
import com.google.mediapipe.tasks.core.BaseOptions;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * On-device scream / distress-sound detection with Google's YAMNet (MediaPipe AudioClassifier).
 *
 * It never opens the microphone: it listens through {@link VoskService#setAudioTap}, so it only
 * works while the voice listener is running. Audio is converted and classified on this class's
 * own single thread so the Vosk loop is never slowed. Nothing is recorded or stored.
 *
 * Fires {@code triggerPanic(ctx, "scream")} when a distress category scores >= 0.6 in 2 of the
 * last 3 windows (0.975 s windows, 50% overlap), then stays quiet for 60 s.
 */
final class ScreamDetector implements VoskService.AudioTap {

    private static final String TAG = "ScreamDetector";

    static final String MODEL_ASSET = "yamnet.tflite";

    private static final int SAMPLE_RATE = VoskService.TAP_SAMPLE_RATE;
    /** YAMNet's native input: 15600 samples = 0.975 s at 16 kHz. */
    private static final int WINDOW = 15600;
    /** 50% overlap: a new decision about every 0.49 s. */
    private static final int HOP = WINDOW / 2;

    private static final float SCORE_THRESHOLD = 0.6f;
    private static final int HISTORY = 3;
    private static final int HITS_REQUIRED = 2;
    private static final long COOLDOWN_MS = 60_000L;
    /** Windows quieter than this RMS (about -40 dBFS) are not classified: a scream is loud. Saves battery. */
    private static final float MIN_RMS = 0.01f;
    /** A gap longer than this in the audio (mic paused) starts a fresh window. */
    private static final long GAP_RESET_MS = 1_000L;
    /** Drop audio instead of queueing without bound if classification ever falls behind. */
    private static final int MAX_PENDING_CHUNKS = 24;

    private static final List<String> DISTRESS = Arrays.asList(
            "Screaming", "Shout", "Yell", "Children shouting", "Crying, sobbing", "Wail, moan");

    private final Context app;
    private final ExecutorService executor;
    private final AtomicInteger pending = new AtomicInteger();

    // ---- Executor-thread state only ----
    @Nullable private AudioClassifier classifier;
    private boolean classifierFailed;
    private final float[] window = new float[WINDOW];
    private int filled;
    private long lastChunkAt;
    private final boolean[] history = new boolean[HISTORY];
    private int historyPos;
    private long cooldownUntil;

    private volatile boolean running;

    ScreamDetector(Context ctx) {
        this.app = ctx.getApplicationContext();
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ns-scream");
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
    }

    /** True when the YAMNet model ships with the app. */
    static boolean isModelAvailable(Context ctx) {
        InputStream in = null;
        try {
            in = ctx.getAssets().open(MODEL_ASSET);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            if (in != null) {
                try { in.close(); } catch (IOException ignored) { }
            }
        }
    }

    /** @return false if the detector cannot run (model missing). */
    boolean start() {
        if (running) return true;
        if (!isModelAvailable(app)) {
            Log.w(TAG, "YAMNet model missing from assets; scream detection disabled");
            return false;
        }
        running = true;
        submit(this::ensureClassifier);
        VoskService.setAudioTap(this);
        Log.i(TAG, "Scream detection on");
        return true;
    }

    void stop() {
        if (!running) return;
        running = false;
        if (VoskService.getAudioTap() == this) VoskService.setAudioTap(null);
        submit(() -> {
            if (classifier != null) {
                try { classifier.close(); } catch (Exception ignored) { }
                classifier = null;
            }
        });
        executor.shutdown();
        Log.i(TAG, "Scream detection off");
    }

    boolean isRunning() {
        return running;
    }

    // ------------------------------------------------------------------ audio tap (Vosk thread)

    @Override
    public void onAudio(final short[] buffer, final int length) {
        if (!running) return;
        if (pending.get() >= MAX_PENDING_CHUNKS) return; // falling behind: drop, never block Vosk
        pending.incrementAndGet();
        boolean queued = submit(() -> {
            pending.decrementAndGet();
            consume(buffer, length);
        });
        if (!queued) pending.decrementAndGet();
    }

    private boolean submit(Runnable r) {
        try {
            executor.execute(r);
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ executor thread

    private void consume(short[] buffer, int length) {
        if (!running) return;
        long now = SystemClock.elapsedRealtime();
        if (lastChunkAt != 0 && now - lastChunkAt > GAP_RESET_MS) {
            filled = 0; // the mic was paused (SOS, restart); don't glue unrelated audio together
        }
        lastChunkAt = now;

        for (int i = 0; i < length; i++) {
            window[filled++] = buffer[i] / 32768f;
            if (filled == WINDOW) {
                evaluateWindow();
                System.arraycopy(window, HOP, window, 0, WINDOW - HOP);
                filled = WINDOW - HOP;
            }
        }
    }

    private void evaluateWindow() {
        boolean hit = false;
        if (rms(window) >= MIN_RMS) {
            float score = distressScore();
            hit = score >= SCORE_THRESHOLD;
            if (score >= 0.3f) Log.d(TAG, "Distress score " + score);
        }
        history[historyPos] = hit;
        historyPos = (historyPos + 1) % HISTORY;

        int hits = 0;
        for (boolean h : history) if (h) hits++;
        long now = SystemClock.elapsedRealtime();
        if (hits >= HITS_REQUIRED && now >= cooldownUntil && !ProtectionController.isPanicActive()) {
            cooldownUntil = now + COOLDOWN_MS;
            Arrays.fill(history, false);
            Log.i(TAG, "Distress sound detected; triggering SOS");
            ProtectionController.triggerPanic(app, "scream");
        }
    }

    private float distressScore() {
        AudioClassifier c = ensureClassifier();
        if (c == null) return 0f;
        try {
            AudioData data = AudioData.create(
                    AudioData.AudioDataFormat.builder()
                            .setNumOfChannels(1)
                            .setSampleRate(SAMPLE_RATE)
                            .build(),
                    WINDOW);
            data.load(window);
            AudioClassifierResult result = c.classify(data);
            float best = 0f;
            for (ClassificationResult cr : result.classificationResults()) {
                for (Classifications cls : cr.classifications()) {
                    for (Category cat : cls.categories()) {
                        if (DISTRESS.contains(cat.categoryName()) && cat.score() > best) {
                            best = cat.score();
                        }
                    }
                }
            }
            return best;
        } catch (Exception e) {
            Log.e(TAG, "Classification failed", e);
            return 0f;
        }
    }

    @Nullable
    private AudioClassifier ensureClassifier() {
        if (classifier != null || classifierFailed || !running) return classifier;
        try {
            AudioClassifier.AudioClassifierOptions options = AudioClassifier.AudioClassifierOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build())
                    .setRunningMode(RunningMode.AUDIO_CLIPS)
                    .setCategoryAllowlist(DISTRESS)
                    .build();
            classifier = AudioClassifier.createFromOptions(app, options);
        } catch (Throwable t) {
            // Missing/corrupt model or native library problem: disable quietly, never crash protection.
            classifierFailed = true;
            Log.e(TAG, "Unable to load YAMNet; scream detection disabled", t);
        }
        return classifier;
    }

    private static float rms(float[] samples) {
        double sum = 0;
        for (float s : samples) sum += s * s;
        return (float) Math.sqrt(sum / samples.length);
    }
}
