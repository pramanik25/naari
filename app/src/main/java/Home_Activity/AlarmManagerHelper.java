package Home_Activity;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import com.example.naarishakti.core.Prefs;
import com.example.naarishakti.core.ProtectionController;

import java.util.Calendar;

/**
 * Turns protection on and off on the schedule saved by the Time Settings screen
 * ({@link Prefs#SCHEDULE_ENABLED} and friends). Only the next start and next end are ever
 * scheduled; {@link AlarmReceiver} calls {@link #reschedule} again after each alarm.
 */
public final class AlarmManagerHelper {

    private static final String TAG = "AlarmManagerHelper";

    static final String ACTION_SCHEDULE_START = "com.example.naarishakti.ACTION_SCHEDULE_START";
    static final String ACTION_SCHEDULE_END = "com.example.naarishakti.ACTION_SCHEDULE_END";
    private static final int REQUEST_START = 1001;
    private static final int REQUEST_END = 1002;
    /** An alarm that fires a little early still counts as "inside" the window. */
    private static final long EARLY_TOLERANCE_MS = 60_000;

    private AlarmManagerHelper() {}

    /** Cancels old alarms and, if the schedule is enabled, schedules the next start and end. */
    public static void reschedule(Context ctx) {
        Context app = ctx.getApplicationContext();
        cancelAll(app);

        SharedPreferences prefs = Prefs.get(app);
        if (!prefs.getBoolean(Prefs.SCHEDULE_ENABLED, false)) return;

        int startH = prefs.getInt(Prefs.SCHEDULE_START_HOUR, 22);
        int startM = prefs.getInt(Prefs.SCHEDULE_START_MINUTE, 0);
        int endH = prefs.getInt(Prefs.SCHEDULE_END_HOUR, 6);
        int endM = prefs.getInt(Prefs.SCHEDULE_END_MINUTE, 0);
        String mode = prefs.getString(Prefs.SCHEDULE_MODE, Prefs.SCHEDULE_MODE_DAILY);
        long now = System.currentTimeMillis();

        if (Prefs.SCHEDULE_MODE_DAILY.equals(mode)) {
            // A window that started yesterday may still be running (e.g. 22:00 -> 06:00).
            long[] current = null;
            long[] next = null;
            for (int dayOffset = -1; dayOffset <= 1; dayOffset++) {
                long[] w = window(dayStart(now, dayOffset), startH, startM, endH, endM);
                if (isInside(now, w)) current = w;
                if (next == null && w[0] > now + EARLY_TOLERANCE_MS) next = w;
            }
            if (next == null) next = window(dayStart(now, 2), startH, startM, endH, endM);
            if (current != null) {
                startProtection(app);
                setAlarm(app, ACTION_SCHEDULE_END, REQUEST_END, current[1]);
            } else {
                setAlarm(app, ACTION_SCHEDULE_END, REQUEST_END, next[1]);
            }
            setAlarm(app, ACTION_SCHEDULE_START, REQUEST_START, next[0]);
            return;
        }

        // The settings screen stores the resolved day for Today/Tomorrow too, so a reschedule
        // after a reboot on a later day keeps the original date instead of shifting it.
        long base;
        if (prefs.contains(Prefs.SCHEDULE_DATE_MILLIS)) {
            base = dayStart(prefs.getLong(Prefs.SCHEDULE_DATE_MILLIS, now), 0);
        } else if (Prefs.SCHEDULE_MODE_TOMORROW.equals(mode)) {
            base = dayStart(now, 1);
        } else {
            base = dayStart(now, 0);
        }
        long[] w = window(base, startH, startM, endH, endM);
        if (now >= w[1]) {
            Log.d(TAG, "One-off schedule already over");
            return;
        }
        if (isInside(now, w)) {
            startProtection(app);
        } else {
            setAlarm(app, ACTION_SCHEDULE_START, REQUEST_START, w[0]);
        }
        setAlarm(app, ACTION_SCHEDULE_END, REQUEST_END, w[1]);
    }

    public static void cancelAll(Context ctx) {
        Context app = ctx.getApplicationContext();
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.cancel(pending(app, ACTION_SCHEDULE_START, REQUEST_START));
        am.cancel(pending(app, ACTION_SCHEDULE_END, REQUEST_END));
    }

    /** Whether the saved schedule is a daily one (AlarmReceiver re-arms those after firing). */
    static boolean isDaily(Context ctx) {
        return Prefs.SCHEDULE_MODE_DAILY.equals(
                Prefs.get(ctx).getString(Prefs.SCHEDULE_MODE, Prefs.SCHEDULE_MODE_DAILY));
    }

    private static void startProtection(Context app) {
        if (ProtectionController.isProtectionActive()) return;
        try {
            ProtectionController.start(app);
        } catch (Exception e) {
            Log.e(TAG, "Couldn't start scheduled protection", e);
        }
    }

    private static boolean isInside(long now, long[] window) {
        return now >= window[0] - EARLY_TOLERANCE_MS && now < window[1];
    }

    /** [start, end] millis for the window beginning on the day at {@code dayStartMillis}. */
    private static long[] window(long dayStartMillis, int startH, int startM, int endH, int endM) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(dayStartMillis);
        c.set(Calendar.HOUR_OF_DAY, startH);
        c.set(Calendar.MINUTE, startM);
        long start = c.getTimeInMillis();
        c.setTimeInMillis(dayStartMillis);
        c.set(Calendar.HOUR_OF_DAY, endH);
        c.set(Calendar.MINUTE, endM);
        if (c.getTimeInMillis() <= start) c.add(Calendar.DAY_OF_YEAR, 1); // crosses midnight
        return new long[]{start, c.getTimeInMillis()};
    }

    private static long dayStart(long millis, int dayOffset) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.DAY_OF_YEAR, dayOffset);
        return c.getTimeInMillis();
    }

    private static PendingIntent pending(Context app, String action, int requestCode) {
        Intent intent = new Intent(app, AlarmReceiver.class).setAction(action);
        return PendingIntent.getBroadcast(app, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void setAlarm(Context app, String action, int requestCode, long atMillis) {
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = pending(app, action, requestCode);
        try {
            boolean exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms();
            if (exact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi);
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi);
            }
            Log.d(TAG, action + " at " + new java.util.Date(atMillis) + (exact ? " (exact)" : " (inexact)"));
        } catch (SecurityException e) {
            Log.e(TAG, "Exact alarm not permitted; falling back to inexact", e);
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi);
        }
    }
}
