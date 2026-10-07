package com.example.naarishakti.daily;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.View;

import com.example.naarishakti.R;
import com.example.naarishakti.core.Kv;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Small shared toolkit for the daily package (commute, cycle tracker, quiz): storage, calendar
 * days, formatting. Days are "epoch days" of the local calendar date, so they never shift with
 * the time zone or daylight saving.
 */
final class Daily {

    private static final String TAG = "Daily";
    /** Non-secret daily state: commute routes, quiz streak, reminder switches. */
    private static final String FILE = "ns_daily";
    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private Daily() {}

    static SharedPreferences kv(Context ctx) {
        return Kv.get(ctx, FILE);
    }

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

    // ------------------------------------------------------------------ days

    static int today() {
        Calendar c = Calendar.getInstance();
        return epochDay(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
    }

    /** {@code month} is zero-based, as in {@link Calendar}. */
    static int epochDay(int year, int month, int day) {
        Calendar u = Calendar.getInstance(UTC);
        u.clear();
        u.set(year, month, day);
        return (int) Math.floorDiv(u.getTimeInMillis(), DAY_MS);
    }

    /** A UTC calendar positioned on {@code epochDay}; read its date fields only. */
    static Calendar calendar(int epochDay) {
        Calendar u = Calendar.getInstance(UTC);
        u.clear();
        u.setTimeInMillis(epochDay * DAY_MS);
        return u;
    }

    /** "12 Mar" in the user's locale. */
    static String shortDate(int epochDay) {
        return format(epochDay, "dMMM");
    }

    /** "Tuesday, 12 March". */
    static String longDate(int epochDay) {
        return format(epochDay, "EEEEdMMMM");
    }

    /** "March 2026". */
    static String monthTitle(int epochDay) {
        return format(epochDay, "MMMMy");
    }

    private static String format(int epochDay, String skeleton) {
        Locale locale = Locale.getDefault();
        SimpleDateFormat f = new SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale);
        f.setTimeZone(UTC);
        return f.format(new Date(epochDay * DAY_MS));
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

    // ------------------------------------------------------------------ views

    static void show(View v, boolean visible) {
        v.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    static int dp(Context ctx, int value) {
        return Math.round(value * ctx.getResources().getDisplayMetrics().density);
    }

    static void open(Context ctx, Intent intent) {
        try {
            ctx.startActivity(intent);
            if (ctx instanceof Activity) {
                ((Activity) ctx).overridePendingTransition(R.anim.slide_in, R.anim.slide_out);
            }
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "Cannot start " + intent, e);
        }
    }
}
