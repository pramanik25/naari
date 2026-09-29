package com.example.naarishakti.triggers;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.naarishakti.core.ProtectionController;

/**
 * Triple-press of the wired or Bluetooth headset button within 1.5 s fires
 * {@code triggerPanic(ctx, "headset")}.
 *
 * Uses a framework {@link MediaSession} (no androidx.media dependency) kept active with a
 * "playing" state. Android 8+ routes media buttons to the session that most recently played
 * audio, so on start a fraction of a second of silence is played (without audio focus, so other
 * apps' music is not paused) to make this session the media-button target.
 */
final class HeadsetTrigger {

    private static final String TAG = "HeadsetTrigger";

    private static final int PRESSES_REQUIRED = 3;
    private static final long WINDOW_MS = 1_500L;
    /** Two key-downs closer than this are one physical press (some headsets send duplicates). */
    private static final long DEBOUNCE_MS = 60L;
    private static final long COOLDOWN_MS = 10_000L;
    private static final int SILENCE_MS = 300;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    @Nullable private MediaSession session;

    private final long[] presses = new long[PRESSES_REQUIRED];
    private int pressCount;
    private long cooldownUntil;

    HeadsetTrigger(Context ctx) {
        this.app = ctx.getApplicationContext();
    }

    /** Must be called on the main thread. */
    void start() {
        if (session != null) return;
        try {
            MediaSession s = new MediaSession(app, "NaariShaktiHeadset");
            s.setCallback(callback, main);
            //noinspection deprecation - required before API 26, ignored after.
            s.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            s.setPlaybackState(new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                            | PlaybackState.ACTION_PLAY_PAUSE)
                    .setState(PlaybackState.STATE_PLAYING, 0, 0f)
                    .build());
            s.setActive(true);
            session = s;
            playSilence();
            Log.i(TAG, "Headset trigger on");
        } catch (Exception e) {
            Log.e(TAG, "Unable to create media session", e);
            session = null;
        }
    }

    void stop() {
        MediaSession s = session;
        session = null;
        pressCount = 0;
        if (s == null) return;
        try {
            s.setActive(false);
            s.release();
        } catch (Exception ignored) { }
        Log.i(TAG, "Headset trigger off");
    }

    boolean isRunning() {
        return session != null;
    }

    private final MediaSession.Callback callback = new MediaSession.Callback() {
        @Override
        public boolean onMediaButtonEvent(@NonNull Intent mediaButtonIntent) {
            KeyEvent event = mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
            if (event == null) return super.onMediaButtonEvent(mediaButtonIntent);
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_HEADSETHOOK:
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                        onPress();
                    }
                    return true;
                default:
                    return super.onMediaButtonEvent(mediaButtonIntent);
            }
        }
    };

    private void onPress() {
        long now = SystemClock.elapsedRealtime();
        if (pressCount > 0 && now - presses[pressCount - 1] < DEBOUNCE_MS) return;
        // Keep only presses inside the window.
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
        Log.d(TAG, "Headset press " + pressCount);

        if (pressCount >= PRESSES_REQUIRED) {
            pressCount = 0;
            if (now < cooldownUntil || ProtectionController.isPanicActive()) return;
            cooldownUntil = now + COOLDOWN_MS;
            Log.i(TAG, "Headset triple-press; triggering SOS");
            ProtectionController.triggerPanic(app, "headset");
        }
    }

    /** Plays a short burst of silence so Android treats this session as the last media player. */
    private void playSilence() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                AudioTrack track = null;
                try {
                    int rate = 16000;
                    int samples = rate * SILENCE_MS / 1000;
                    int minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT);
                    track = new AudioTrack.Builder()
                            .setAudioAttributes(new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                    .build())
                            .setAudioFormat(new AudioFormat.Builder()
                                    .setSampleRate(rate)
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                    .build())
                            .setBufferSizeInBytes(Math.max(minBuf, samples * 2))
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .build();
                    track.play();
                    track.write(new short[samples], 0, samples);
                    SystemClock.sleep(SILENCE_MS + 100);
                    track.stop();
                } catch (Exception e) {
                    Log.w(TAG, "Silent playback failed: " + e.getMessage());
                } finally {
                    if (track != null) track.release();
                }
            }
        }, "ns-headset-silence").start();
    }
}
