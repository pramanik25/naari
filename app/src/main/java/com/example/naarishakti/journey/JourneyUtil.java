package com.example.naarishakti.journey;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.example.naarishakti.R;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import SQLite_Database.ProfileDbHelper;

/** Small shared toolkit for the journey package: permissions, notifications, formatting, IO. */
final class JourneyUtil {

    private static final String TAG = "JourneyUtil";

    /** Low-importance channel for the ongoing trip / check-in notification. */
    static final String CH_ONGOING = "jr_journey";
    /** High-importance channel for check-in warnings and "Is everything OK?" prompts. */
    static final String CH_ALERTS = "jr_alerts";

    /** Local broadcast: check-in or cab trip state changed. */
    static final String ACTION_CHANGED = "com.example.naarishakti.journey.ACTION_CHANGED";

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private JourneyUtil() {}

    // ------------------------------------------------------------------ threads

    /** Runs {@code r} off the main thread; failures are logged and never propagate. */
    static void io(final Runnable r) {
        IO.execute(() -> {
            try {
                r.run();
            } catch (Throwable t) {
                Log.e(TAG, "Background task failed", t);
            }
        });
    }

    // ------------------------------------------------------------------ permissions

    static boolean granted(Context ctx, String permission) {
        return ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean hasLocation(Context ctx) {
        return granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
                || granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION);
    }

    static boolean hasBackgroundLocation(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return hasLocation(ctx);
        return granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION);
    }

    /** The subset of {@code permissions} that is not granted yet. */
    static String[] missing(Context ctx, @Nullable String[] permissions) {
        List<String> out = new ArrayList<>();
        if (permissions != null) {
            for (String p : permissions) {
                if (p != null && !granted(ctx, p) && !out.contains(p)) out.add(p);
            }
        }
        return out.toArray(new String[0]);
    }

    /** Normalises a permission list from another module, whether it is an array or a collection. */
    static String[] perms(@Nullable String[] permissions) {
        return permissions == null ? new String[0] : permissions;
    }

    static String[] perms(@Nullable Collection<String> permissions) {
        return permissions == null ? new String[0] : permissions.toArray(new String[0]);
    }

    static String[] concat(String[] a, String... b) {
        List<String> out = new ArrayList<>();
        for (String s : a) if (!out.contains(s)) out.add(s);
        for (String s : b) if (!out.contains(s)) out.add(s);
        return out.toArray(new String[0]);
    }

    static String[] locationPerms() {
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION};
    }

    /** POST_NOTIFICATIONS on Android 13+, nothing before. */
    static String[] notificationPerms() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new String[]{Manifest.permission.POST_NOTIFICATIONS};
        }
        return new String[0];
    }

    static void openAppSettings(Context ctx) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", ctx.getPackageName(), null));
        if (!(ctx instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startSafely(ctx, i);
    }

    static boolean startSafely(Context ctx, Intent intent) {
        try {
            ctx.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "Cannot start " + intent, e);
            return false;
        }
    }

    // ------------------------------------------------------------------ alarms

    static boolean canScheduleExact(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        return am == null || am.canScheduleExactAlarms();
    }

    static void openExactAlarmSettings(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent i = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + activity.getPackageName()));
            if (!startSafely(activity, i)) openAppSettings(activity);
        }
    }

    /** Exact wake-up alarm when allowed, otherwise the closest inexact equivalent. */
    @android.annotation.SuppressLint("ScheduleExactAlarm") // guarded by canScheduleExact()
    static void setAlarm(Context ctx, long atMs, PendingIntent pi) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try {
            if (canScheduleExact(ctx)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi);
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi);
            }
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi);
        }
    }

    static int piFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
    }

    // ------------------------------------------------------------------ notifications

    static void createChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ongoing = new NotificationChannel(CH_ONGOING,
                ctx.getString(R.string.jr_channel_ongoing), NotificationManager.IMPORTANCE_LOW);
        ongoing.setDescription(ctx.getString(R.string.jr_channel_ongoing_desc));
        ongoing.setShowBadge(false);
        NotificationChannel alerts = new NotificationChannel(CH_ALERTS,
                ctx.getString(R.string.jr_channel_alerts), NotificationManager.IMPORTANCE_HIGH);
        alerts.setDescription(ctx.getString(R.string.jr_channel_alerts_desc));
        alerts.enableVibration(true);
        alerts.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        List<NotificationChannel> list = new ArrayList<>();
        list.add(ongoing);
        list.add(alerts);
        nm.createNotificationChannels(list);
    }

    static void notifySafely(Context ctx, int id, Notification n) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && !granted(ctx, Manifest.permission.POST_NOTIFICATIONS)) {
            Log.w(TAG, "Notifications not allowed; " + id + " not shown");
            return;
        }
        try {
            NotificationManagerCompat.from(ctx).notify(id, n);
        } catch (SecurityException e) {
            Log.w(TAG, "notify failed", e);
        }
    }

    static void cancelNotification(Context ctx, int id) {
        NotificationManagerCompat.from(ctx).cancel(id);
    }

    // ------------------------------------------------------------------ formatting

    /** Wall-clock time in the user's 12/24 h format, e.g. "21:45" or "9:45 PM". */
    static String clock(Context ctx, long ms) {
        return DateFormat.getTimeFormat(ctx).format(new Date(ms));
    }

    /** "1:02:03" or "04:05". Negative values are clamped to zero. */
    static String countdown(long ms) {
        long total = Math.max(0L, ms) / 1000L;
        long h = total / 3600L;
        long m = (total % 3600L) / 60L;
        long s = total % 60L;
        return h > 0
                ? String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
                : String.format(Locale.getDefault(), "%02d:%02d", m, s);
    }

    static String distance(Context ctx, float meters) {
        if (meters < 1000f) {
            return ctx.getString(R.string.jr_distance_m, Math.round(meters));
        }
        return ctx.getString(R.string.jr_distance_km, meters / 1000f);
    }

    static String mapsLink(double lat, double lng) {
        return String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", lat, lng);
    }

    static String displayName(Context ctx, List<com.example.naarishakti.core.Prefs.Contact> contacts) {
        List<String> names = new ArrayList<>();
        for (com.example.naarishakti.core.Prefs.Contact c : contacts) names.add(c.displayName());
        return TextUtils.join(ctx.getString(R.string.jr_list_separator), names);
    }

    // ------------------------------------------------------------------ profile

    /** The owner's first name from the profile, or a neutral fallback. Blocking: call off the main thread. */
    @NonNull
    static String userName(Context ctx) {
        String name = null;
        try (ProfileDbHelper helper = new ProfileDbHelper(ctx.getApplicationContext());
             SQLiteDatabase db = helper.getReadableDatabase();
             Cursor c = db.query(ProfileDbHelper.TABLE_NAME, new String[]{ProfileDbHelper.COLUMN_NAME},
                     null, null, null, null, ProfileDbHelper.COLUMN_ID + " DESC", "1")) {
            if (c.moveToFirst()) name = c.getString(0);
        } catch (Exception e) {
            Log.w(TAG, "Profile unavailable", e);
        }
        if (name == null || name.trim().isEmpty()) return ctx.getString(R.string.jr_sms_default_name);
        return name.trim();
    }

    // ------------------------------------------------------------------ views

    static void show(View v, boolean visible) {
        v.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    static View divider(Context ctx) {
        View v = new View(ctx);
        float d = ctx.getResources().getDisplayMetrics().density;
        ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(d)));
        lp.setMarginStart(Math.round(80 * d));
        v.setLayoutParams(lp);
        v.setBackgroundColor(ContextCompat.getColor(ctx, R.color.ns_stroke));
        return v;
    }

    static int dp(Context ctx, int value) {
        return Math.round(value * ctx.getResources().getDisplayMetrics().density);
    }
}
