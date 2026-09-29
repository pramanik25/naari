package com.example.naarishakti.evidence;

import android.content.Context;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.File;

/**
 * Records the microphone in 10-second AAC segments ({@code audio_<n>.m4a}) into one folder and
 * hands every finished segment to a listener. Rotation is stop/start on a timer, with
 * setMaxDuration as a safety net. All MediaRecorder work runs on a private HandlerThread.
 *
 * Never throws: a missing permission, a busy microphone or a full disk are logged and the
 * recorder retries (start) or gives up quietly.
 */
final class AudioChunkRecorder {

    interface Listener {
        /** A finished, non-empty segment on disk. Called on the recorder thread. */
        void onSegment(File file, long capturedAt);
    }

    private static final String TAG = "AudioChunkRecorder";
    static final long SEGMENT_MS = 10_000L;
    /** The voice-trigger listener may hold the mic for a moment after the SOS fires. */
    private static final long START_RETRY_WINDOW_MS = 5_000L;
    private static final long RETRY_DELAY_MS = 400L;
    /** Stop recording when less than this is free on the evidence volume. */
    private static final long MIN_FREE_BYTES = 8L * 1024 * 1024;
    /** Consecutive failures after rotation before giving up. */
    private static final int MAX_ROTATION_FAILURES = 5;

    private final Context ctx;
    private final File dir;
    private final Listener listener;
    private final HandlerThread thread;
    private final Handler handler;

    // Recorder-thread state
    @Nullable private MediaRecorder recorder;
    @Nullable private File currentFile;
    private long currentStartedAt;
    private long currentStartedElapsed;
    private int nextIndex = 1;
    private long startDeadline;
    private int rotationFailures;
    private boolean stopped;

    AudioChunkRecorder(Context ctx, File dir, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.dir = dir;
        this.listener = listener;
        this.thread = new HandlerThread("ev-audio");
        this.thread.start();
        this.handler = new Handler(thread.getLooper());
    }

    void start() {
        handler.post(() -> {
            startDeadline = SystemClock.elapsedRealtime() + START_RETRY_WINDOW_MS;
            attemptStart();
        });
    }

    /** Stops recording, flushes the current segment to the listener, then ends the thread. */
    void stop() {
        handler.post(() -> {
            stopped = true;
            handler.removeCallbacksAndMessages(null);
            finishSegment();
            thread.quitSafely();
        });
    }

    // ---- recorder thread ----

    private final Runnable rotate = this::rotate;
    private final Runnable retryStart = this::attemptStart;

    private void attemptStart() {
        if (stopped) return;
        if (!hasSpace()) {
            Log.w(TAG, "Storage almost full, audio evidence stopped");
            stopped = true;
            thread.quitSafely();
            return;
        }
        if (beginSegment()) {
            rotationFailures = 0;
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now < startDeadline) {
            handler.postDelayed(retryStart, RETRY_DELAY_MS);
        } else if (++rotationFailures < MAX_ROTATION_FAILURES) {
            // Mic still unavailable: keep trying, slower, a few more times.
            startDeadline = now + START_RETRY_WINDOW_MS;
            handler.postDelayed(retryStart, 2_000L);
        } else {
            Log.w(TAG, "Microphone unavailable, audio evidence gave up");
            stopped = true;
            thread.quitSafely();
        }
    }

    private void rotate() {
        if (stopped) return;
        finishSegment();
        startDeadline = SystemClock.elapsedRealtime() + START_RETRY_WINDOW_MS;
        attemptStart();
    }

    @SuppressWarnings("deprecation")
    private boolean beginSegment() {
        File file = nextFile();
        MediaRecorder mr = null;
        try {
            mr = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? new MediaRecorder(ctx) : new MediaRecorder();
            mr.setAudioSource(MediaRecorder.AudioSource.MIC);
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mr.setAudioEncodingBitRate(64_000);
            mr.setAudioChannels(1);
            mr.setAudioSamplingRate(44_100);
            mr.setOutputFile(file.getAbsolutePath());
            mr.setMaxDuration((int) (SEGMENT_MS + 1_500L));
            final MediaRecorder self = mr;
            mr.setOnInfoListener((r, what, extra) -> {
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED
                        || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                    if (recorder == self) {
                        handler.removeCallbacks(rotate);
                        handler.post(rotate);
                    }
                }
            });
            mr.setOnErrorListener((r, what, extra) -> {
                Log.w(TAG, "MediaRecorder error " + what + "/" + extra);
                if (recorder == self) {
                    handler.removeCallbacks(rotate);
                    handler.post(rotate);
                }
            });
            mr.prepare();
            mr.start();
        } catch (Throwable t) {
            Log.w(TAG, "Audio segment start failed: " + t.getMessage());
            if (mr != null) {
                try { mr.reset(); } catch (Throwable ignored) { }
                try { mr.release(); } catch (Throwable ignored) { }
            }
            if (file.exists() && !file.delete()) Log.w(TAG, "Could not delete " + file);
            return false;
        }
        recorder = mr;
        currentFile = file;
        currentStartedAt = System.currentTimeMillis();
        currentStartedElapsed = SystemClock.elapsedRealtime();
        nextIndex++;
        handler.postDelayed(rotate, SEGMENT_MS);
        return true;
    }

    /** Stops the current recorder and delivers its file when it is valid. */
    private void finishSegment() {
        MediaRecorder mr = recorder;
        File file = currentFile;
        recorder = null;
        currentFile = null;
        if (mr == null) return;
        boolean ok = true;
        try {
            mr.stop();
        } catch (Throwable t) {
            // Too short (no data) or already stopped by max duration with nothing valid.
            ok = SystemClock.elapsedRealtime() - currentStartedElapsed > SEGMENT_MS
                    && file != null && file.length() > 0;
            if (!ok) Log.w(TAG, "Segment discarded: " + t.getMessage());
        }
        try { mr.reset(); } catch (Throwable ignored) { }
        try { mr.release(); } catch (Throwable ignored) { }
        if (file == null) return;
        if (ok && file.length() > 0) {
            try {
                listener.onSegment(file, currentStartedAt);
            } catch (Throwable t) {
                Log.e(TAG, "Segment listener failed", t);
            }
        } else if (file.exists() && !file.delete()) {
            Log.w(TAG, "Could not delete " + file);
        }
    }

    private File nextFile() {
        if (!dir.isDirectory() && !dir.mkdirs()) Log.w(TAG, "Can't create " + dir);
        File f = new File(dir, "audio_" + nextIndex + ".m4a");
        while (f.exists()) {
            nextIndex++;
            f = new File(dir, "audio_" + nextIndex + ".m4a");
        }
        return f;
    }

    private boolean hasSpace() {
        return hasFreeSpace(dir, MIN_FREE_BYTES);
    }

    /** True when {@code dir} exists (or was created) and its volume has more than {@code min} bytes free. */
    static boolean hasFreeSpace(File dir, long min) {
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) return false;
            return dir.getUsableSpace() > min;
        } catch (Exception e) {
            return true; // Can't tell (security manager etc.): let the recorder try.
        }
    }
}
