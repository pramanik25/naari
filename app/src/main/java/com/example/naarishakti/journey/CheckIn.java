package com.example.naarishakti.journey;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.example.naarishakti.R;
import com.example.naarishakti.cloud.CloudCheckIns;
import com.example.naarishakti.core.Prefs;
import com.example.naarishakti.core.ProtectionController;

import java.util.List;
import java.util.UUID;

/**
 * Check-in timer state machine. State lives in {@link Prefs} under jr_ci_* keys so it survives
 * process death; alarms go to {@link CheckInReceiver}; {@link JourneyService} streams location
 * and double-checks the deadline; the API server holds a copy so it can escalate if the phone dies.
 */
public final class CheckIn {

    private static final String TAG = "CheckIn";

    static final String K_ACTIVE = "jr_ci_active";
    static final String K_ID = "jr_ci_id";
    static final String K_DEADLINE = "jr_ci_deadline";
    static final String K_STARTED = "jr_ci_started";
    static final String K_NOTE = "jr_ci_note";
    /** Deadline passed and the SOS was triggered. */
    static final String K_FIRED = "jr_ci_fired";
    /** The "due in 2 min" warning was shown. */
    static final String K_WARNED = "jr_ci_warned";

    static final long WARN_BEFORE_MS = 2 * 60_000L;
    static final long EXTEND_MS = 15 * 60_000L;

    static final int NOTIF_WARN = 7301;
    static final int NOTIF_MISSED = 7302;

    private static final int REQ_WARN = 7401;
    private static final int REQ_DEADLINE = 7402;
    private static final int REQ_OPEN = 7403;
    private static final int REQ_SAFE = 7404;

    private CheckIn() {}

    private static SharedPreferences p(Context ctx) {
        return Prefs.get(ctx);
    }

    // ------------------------------------------------------------------ queries

    public static boolean isActive(Context ctx) {
        return p(ctx).getBoolean(K_ACTIVE, false);
    }

    public static long deadline(Context ctx) {
        return p(ctx).getLong(K_DEADLINE, 0L);
    }

    static long startedAt(Context ctx) {
        return p(ctx).getLong(K_STARTED, 0L);
    }

    static String note(Context ctx) {
        return p(ctx).getString(K_NOTE, "");
    }

    @Nullable
    static String id(Context ctx) {
        return p(ctx).getString(K_ID, null);
    }

    /** Deadline passed and the SOS was fired, but she has not checked in yet. */
    public static boolean isOverdue(Context ctx) {
        return isActive(ctx) && p(ctx).getBoolean(K_FIRED, false);
    }

    // ------------------------------------------------------------------ actions

    public static void start(Context ctx, long deadlineMs, String note) {
        final Context app = ctx.getApplicationContext();
        final String id = UUID.randomUUID().toString();
        final String safeNote = note == null ? "" : note.trim();
        final long deadline = Math.max(deadlineMs, System.currentTimeMillis() + 60_000L);
        p(app).edit()
                .putBoolean(K_ACTIVE, true)
                .putString(K_ID, id)
                .putLong(K_DEADLINE, deadline)
                .putLong(K_STARTED, System.currentTimeMillis())
                .putString(K_NOTE, safeNote)
                .putBoolean(K_FIRED, false)
                .putBoolean(K_WARNED, false)
                .apply();
        arm(app);
        JourneyService.sync(app, true);
        final List<String> contacts = Prefs.getContactNumbers(app);
        JourneyUtil.io(() -> {
            try {
                CloudCheckIns.start(app, id, deadline, safeNote, contacts);
            } catch (Exception e) {
                Log.w(TAG, "Cloud check-in start failed", e);
            }
        });
        changed(app);
    }

    /** Pushes the deadline 15 minutes later (from now if already overdue). */
    public static long extend(Context ctx) {
        final Context app = ctx.getApplicationContext();
        if (!isActive(app)) return 0L;
        final long deadline = Math.max(deadline(app), System.currentTimeMillis()) + EXTEND_MS;
        final String id = id(app);
        p(app).edit()
                .putLong(K_DEADLINE, deadline)
                .putBoolean(K_WARNED, false)
                .putBoolean(K_FIRED, false)
                .apply();
        JourneyUtil.cancelNotification(app, NOTIF_WARN);
        JourneyUtil.cancelNotification(app, NOTIF_MISSED);
        arm(app);
        if (id != null) {
            JourneyUtil.io(() -> {
                try {
                    CloudCheckIns.extend(app, id, deadline);
                } catch (Exception e) {
                    Log.w(TAG, "Cloud check-in extend failed", e);
                }
            });
        }
        changed(app);
        return deadline;
    }

    /** "I'm safe" with the real PIN (or no PIN set): close everything, here and on the server. */
    public static void complete(Context ctx) {
        final Context app = ctx.getApplicationContext();
        final String id = id(app);
        clearLocal(app);
        if (id != null) {
            JourneyUtil.io(() -> {
                try {
                    CloudCheckIns.complete(app, id);
                } catch (Exception e) {
                    Log.w(TAG, "Cloud check-in complete failed", e);
                }
            });
        }
    }

    /** Silently drops a check-in (e.g. replaced by a new one); the server copy is cancelled. */
    static void cancel(Context ctx) {
        final Context app = ctx.getApplicationContext();
        final String id = id(app);
        clearLocal(app);
        if (id != null) {
            JourneyUtil.io(() -> {
                try {
                    CloudCheckIns.cancel(app, id);
                } catch (Exception e) {
                    Log.w(TAG, "Cloud check-in cancel failed", e);
                }
            });
        }
    }

    /**
     * "I'm safe" answered with the duress PIN: the phone looks checked out, but a covert SOS
     * starts and the server check-in keeps running so it still escalates at the deadline.
     */
    public static void duress(Context ctx) {
        Context app = ctx.getApplicationContext();
        ProtectionController.triggerPanic(app, "duress", true);
        clearLocal(app);
    }

    /**
     * Re-arms alarms and the location service from saved state (alarms are lost on reboot).
     * Call from a visible screen: the location service may only start while the app is in front.
     */
    public static void rearm(Context ctx) {
        Context app = ctx.getApplicationContext();
        if (!isActive(app)) return;
        arm(app);
        JourneyService.sync(app, true);
    }

    /** Same as {@link #rearm} but from a receiver (boot): alarms only; the service is left to sync. */
    static void rearmFromBackground(Context ctx) {
        Context app = ctx.getApplicationContext();
        if (isActive(app)) arm(app);
    }

    static void uploadLocation(Context ctx, final Location location) {
        final Context app = ctx.getApplicationContext();
        final String id = id(app);
        if (id == null || !isActive(app)) return;
        JourneyUtil.io(() -> {
            try {
                CloudCheckIns.updateLocation(app, id, location);
            } catch (Exception e) {
                Log.w(TAG, "Cloud location update failed", e);
            }
        });
    }

    private static void clearLocal(Context app) {
        disarm(app);
        p(app).edit()
                .remove(K_ACTIVE).remove(K_ID).remove(K_DEADLINE).remove(K_STARTED)
                .remove(K_NOTE).remove(K_FIRED).remove(K_WARNED)
                .apply();
        JourneyUtil.cancelNotification(app, NOTIF_WARN);
        JourneyUtil.cancelNotification(app, NOTIF_MISSED);
        JourneyService.sync(app, false);
        changed(app);
    }

    // ------------------------------------------------------------------ alarms

    private static void arm(Context app) {
        long deadline = deadline(app);
        long now = System.currentTimeMillis();
        SharedPreferences prefs = p(app);
        long warnAt = deadline - WARN_BEFORE_MS;
        if (!prefs.getBoolean(K_WARNED, false) && warnAt > now) {
            JourneyUtil.setAlarm(app, warnAt, alarmIntent(app, CheckInReceiver.ACTION_WARN, REQ_WARN));
        }
        if (!prefs.getBoolean(K_FIRED, false)) {
            // A deadline that passed while the phone was off fires a few seconds from now.
            JourneyUtil.setAlarm(app, Math.max(deadline, now + 5_000L),
                    alarmIntent(app, CheckInReceiver.ACTION_DEADLINE, REQ_DEADLINE));
        }
    }

    private static void disarm(Context app) {
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.cancel(alarmIntent(app, CheckInReceiver.ACTION_WARN, REQ_WARN));
        am.cancel(alarmIntent(app, CheckInReceiver.ACTION_DEADLINE, REQ_DEADLINE));
    }

    private static PendingIntent alarmIntent(Context app, String action, int req) {
        Intent i = new Intent(app, CheckInReceiver.class).setAction(action);
        return PendingIntent.getBroadcast(app, req, i, JourneyUtil.piFlags());
    }

    // ------------------------------------------------------------------ alarm callbacks

    static void onWarning(Context ctx) {
        Context app = ctx.getApplicationContext();
        SharedPreferences prefs = p(app);
        if (!isActive(app) || prefs.getBoolean(K_FIRED, false) || prefs.getBoolean(K_WARNED, false)) return;
        long deadline = deadline(app);
        if (System.currentTimeMillis() < deadline - WARN_BEFORE_MS - 30_000L) {
            arm(app); // early inexact delivery: try again later
            return;
        }
        prefs.edit().putBoolean(K_WARNED, true).apply();

        JourneyUtil.createChannels(app);
        NotificationCompat.Builder nb = new NotificationCompat.Builder(app, JourneyUtil.CH_ALERTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(ContextCompat.getColor(app, R.color.ns_rose))
                .setContentTitle(app.getString(R.string.jr_ci_warn_title))
                .setContentText(app.getString(R.string.jr_ci_warn_text, JourneyUtil.clock(app, deadline)))
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(app.getString(R.string.jr_ci_warn_text, JourneyUtil.clock(app, deadline))))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setWhen(deadline)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setTimeoutAfter(WARN_BEFORE_MS + 60_000L)
                .setContentIntent(openIntent(app, false))
                .addAction(0, app.getString(R.string.jr_ci_safe), openIntent(app, true));
        JourneyUtil.notifySafely(app, NOTIF_WARN, nb.build());
        changed(app);
    }

    static void onDeadline(Context ctx) {
        Context app = ctx.getApplicationContext();
        SharedPreferences prefs = p(app);
        if (!isActive(app) || prefs.getBoolean(K_FIRED, false)) return;
        if (System.currentTimeMillis() < deadline(app) - 5_000L) {
            arm(app); // stale alarm from before an extension
            return;
        }
        prefs.edit().putBoolean(K_FIRED, true).apply();
        JourneyUtil.cancelNotification(app, NOTIF_WARN);
        Log.w(TAG, "Check-in missed: triggering SOS");
        // The engine's countdown gives her a last chance to cancel a false alarm.
        ProtectionController.triggerPanic(app, "checkin");

        if (!Prefs.get(app).getBoolean(Prefs.SILENT_SOS, false)) {
            JourneyUtil.createChannels(app);
            Notification n = new NotificationCompat.Builder(app, JourneyUtil.CH_ALERTS)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setColor(ContextCompat.getColor(app, R.color.ns_danger))
                    .setContentTitle(app.getString(R.string.jr_ci_missed_title))
                    .setContentText(app.getString(R.string.jr_ci_missed_text))
                    .setStyle(new NotificationCompat.BigTextStyle()
                            .bigText(app.getString(R.string.jr_ci_missed_text)))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setAutoCancel(true)
                    .setContentIntent(openIntent(app, false))
                    .addAction(0, app.getString(R.string.jr_ci_safe), openIntent(app, true))
                    .build();
            JourneyUtil.notifySafely(app, NOTIF_MISSED, n);
        }
        changed(app);
    }

    /** Opens the check-in screen; with {@code checkout} it goes straight to the (PIN) check-out. */
    static PendingIntent openIntent(Context app, boolean checkout) {
        Intent i = new Intent(app, CheckInActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (checkout) i.setAction(CheckInActivity.ACTION_CHECKOUT);
        return PendingIntent.getActivity(app, checkout ? REQ_SAFE : REQ_OPEN, i, JourneyUtil.piFlags());
    }

    static String summary(Context ctx) {
        String note = note(ctx);
        String due = JourneyUtil.clock(ctx, deadline(ctx));
        return TextUtils.isEmpty(note)
                ? ctx.getString(R.string.jr_ci_ongoing_text, due)
                : ctx.getString(R.string.jr_ci_ongoing_text_note, due, note);
    }

    static void changed(Context app) {
        LocalBroadcastManager.getInstance(app).sendBroadcast(new Intent(JourneyUtil.ACTION_CHANGED));
    }
}
