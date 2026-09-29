package com.example.naarishakti.escape;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.Person;
import androidx.core.content.ContextCompat;

import com.example.naarishakti.R;
import com.example.naarishakti.core.Prefs;

/**
 * Fake incoming call, used as an excuse to leave an uncomfortable situation.
 * {@link #schedule} sets an alarm that fires {@link FakeCallReceiver}, which rings and shows
 * {@link FakeCallActivity} (full-screen intent, so it also works with the screen locked).
 */
public final class FakeCall {

    private static final String TAG = "FakeCall";

    static final String ACTION_RING = "com.example.naarishakti.escape.ACTION_RING";
    static final String ACTION_SCHEDULE = "com.example.naarishakti.escape.ACTION_SCHEDULE";
    static final String ACTION_DECLINE = "com.example.naarishakti.escape.ACTION_DECLINE";
    static final String EXTRA_NAME = "fake_call_name";
    static final String EXTRA_TOKEN = "fake_call_token";
    static final String EXTRA_DELAY = "fake_call_delay";
    static final String EXTRA_ANSWER = "fake_call_answer";

    public static final String CHANNEL = "ns_fake_call";
    static final int NOTIFICATION_ID = 1301;
    private static final int RC_RING = 7301;
    private static final int RC_SCHEDULE = 7302;
    private static final int RC_DECLINE = 7303;
    private static final int RC_ANSWER = 7304;
    private static final int RC_FULL = 7305;

    private static final String STATE_FILE = "fake_call";
    private static final String K_TOKEN = "pending_token";
    /** In-process backup for short delays, in case the inexact alarm is deferred. */
    private static final long BACKUP_MAX_DELAY_MS = 5 * 60_000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private FakeCall() {}

    /** Schedules a fake incoming call. {@code callerName} null/empty = the saved name (default "Papa"). */
    public static void schedule(Context ctx, int delaySeconds, @Nullable String callerName) {
        final Context app = ctx.getApplicationContext();
        final String name = TextUtils.isEmpty(callerName) ? callerName(app) : callerName.trim();
        final long delayMs = Math.max(0, delaySeconds) * 1000L;
        final long token = SystemClock.elapsedRealtime() + System.nanoTime() % 1000;
        state(app).edit().putLong(K_TOKEN, token).apply();

        PendingIntent pi = ringIntent(app, name, token);
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        long at = SystemClock.elapsedRealtime() + delayMs;
        if (am != null) {
            try {
                boolean exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms();
                if (exact) {
                    am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
                }
            } catch (SecurityException e) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            }
        }
        if (delayMs <= BACKUP_MAX_DELAY_MS) {
            MAIN.postDelayed(() -> onAlarm(app, name, token), delayMs);
        }
    }

    /** Cancels a scheduled call and stops one that is ringing. */
    public static void cancel(Context ctx) {
        Context app = ctx.getApplicationContext();
        state(app).edit().remove(K_TOKEN).apply();
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(ringIntent(app, "", 0));
        dismiss(app);
    }

    /** Saved caller name, or the localized default ("Papa"). */
    public static String callerName(Context ctx) {
        String n = Prefs.get(ctx).getString(Prefs.FAKE_CALL_NAME, "");
        return TextUtils.isEmpty(n) ? ctx.getString(R.string.en_fake_default_name) : n.trim();
    }

    /** Broadcast that schedules a fake call in {@code delaySeconds} (used by notification actions). */
    public static PendingIntent scheduleIntent(Context ctx, int delaySeconds) {
        Intent i = new Intent(ctx, FakeCallReceiver.class)
                .setAction(ACTION_SCHEDULE)
                .putExtra(EXTRA_DELAY, delaySeconds);
        return PendingIntent.getBroadcast(ctx, RC_SCHEDULE, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // ---- Ringing ----

    /** Alarm (or the in-process backup) fired. Rings once per scheduled token. */
    static void onAlarm(Context ctx, String name, long token) {
        Context app = ctx.getApplicationContext();
        SharedPreferences s = state(app);
        if (s.getLong(K_TOKEN, -1) != token) return; // cancelled, or already rung by the other path
        s.edit().remove(K_TOKEN).apply();
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(ringIntent(app, name, token));
        ring(app, name);
    }

    static void ring(Context app, String name) {
        createChannel(app);
        Intent full = new Intent(app, FakeCallActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_NAME, name);
        PendingIntent fullPi = PendingIntent.getActivity(app, RC_FULL, full,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent answer = new Intent(full).putExtra(EXTRA_ANSWER, true);
        PendingIntent answerPi = PendingIntent.getActivity(app, RC_ANSWER, answer,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent declinePi = PendingIntent.getBroadcast(app, RC_DECLINE,
                new Intent(app, FakeCallReceiver.class).setAction(ACTION_DECLINE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Person caller = new Person.Builder().setName(name).setImportant(true).build();
        NotificationCompat.Builder b = new NotificationCompat.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.eng_ic_call)
                .setContentTitle(name)
                .setContentText(app.getString(R.string.en_fake_incoming))
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(true)
                .setTimeoutAfter(Ringer.MAX_RING_MS)
                .setContentIntent(fullPi)
                .setFullScreenIntent(fullPi, true)
                .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, declinePi, answerPi));
        notify(app, b.build());

        Ringer.start(app);
        try {
            app.startActivity(full); // works when the app is visible or may draw over other apps
        } catch (Exception e) {
            Log.w(TAG, "Full-screen intent will show the call: " + e.getMessage());
        }
    }

    /** Stops ringing and removes the call notification. */
    static void dismiss(Context ctx) {
        Ringer.stop();
        NotificationManagerCompat.from(ctx).cancel(NOTIFICATION_ID);
    }

    private static void notify(Context ctx, Notification n) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, n);
        } catch (SecurityException e) {
            Log.w(TAG, "notify failed: " + e.getMessage());
        }
    }

    static void createChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel c = new NotificationChannel(CHANNEL,
                ctx.getString(R.string.en_channel_fake_call), NotificationManager.IMPORTANCE_HIGH);
        c.setDescription(ctx.getString(R.string.en_channel_fake_call_desc));
        c.setSound(null, null); // the Ringer plays the ringtone itself (once)
        c.enableVibration(false);
        c.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(c);
    }

    private static PendingIntent ringIntent(Context app, String name, long token) {
        Intent i = new Intent(app, FakeCallReceiver.class)
                .setAction(ACTION_RING)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_TOKEN, token);
        return PendingIntent.getBroadcast(app, RC_RING, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static SharedPreferences state(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(STATE_FILE, Context.MODE_PRIVATE);
    }

    /** Default ringtone + vibration, honouring the ringer mode like a real call. */
    static final class Ringer {
        static final long MAX_RING_MS = 45_000;

        @Nullable private static MediaPlayer player;
        @Nullable private static Vibrator vibrator;
        private static boolean ringing;
        private static final Runnable TIMEOUT = Ringer::stop;

        private Ringer() {}

        static boolean isRinging() {
            return ringing;
        }

        @SuppressWarnings("deprecation")
        static synchronized void start(Context ctx) {
            if (ringing) return;
            ringing = true;
            Context app = ctx.getApplicationContext();
            AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            int mode = am == null ? AudioManager.RINGER_MODE_NORMAL : am.getRingerMode();

            if (mode == AudioManager.RINGER_MODE_NORMAL) {
                try {
                    Uri uri = RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_RINGTONE);
                    if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
                    MediaPlayer mp = new MediaPlayer();
                    mp.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build());
                    mp.setDataSource(app, uri);
                    mp.setLooping(true);
                    mp.prepare();
                    mp.start();
                    player = mp;
                } catch (Exception e) {
                    Log.w(TAG, "Ringtone unavailable: " + e.getMessage());
                }
            }
            if (mode != AudioManager.RINGER_MODE_SILENT) {
                Vibrator v = (Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null && v.hasVibrator()) {
                    long[] pattern = {0, 1000, 1000};
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        v.vibrate(VibrationEffect.createWaveform(pattern, 0));
                    } else {
                        v.vibrate(pattern, 0);
                    }
                    vibrator = v;
                }
            }
            MAIN.postDelayed(TIMEOUT, MAX_RING_MS);
        }

        static synchronized void stop() {
            MAIN.removeCallbacks(TIMEOUT);
            ringing = false;
            if (player != null) {
                try {
                    player.stop();
                } catch (IllegalStateException ignored) {
                    // Not started.
                }
                player.release();
                player = null;
            }
            if (vibrator != null) {
                vibrator.cancel();
                vibrator = null;
            }
        }
    }
}
