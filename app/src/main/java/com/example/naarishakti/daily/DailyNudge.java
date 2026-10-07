package com.example.naarishakti.daily;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.WorkerParameters;

import com.example.naarishakti.R;

import java.util.Calendar;
import java.util.concurrent.TimeUnit;

/**
 * The once-a-day reminder: "today's quiz is waiting" and, when she asked for it, a discreet note
 * a couple of days before her period is expected. Runs as periodic work around the evening, only
 * while at least one reminder is switched on.
 */
public final class DailyNudge {

    private static final String TAG = "DailyNudge";
    private static final String WORK = "dl_daily_nudge";
    private static final String CHANNEL = "dl_daily";
    private static final int HOUR = 19;

    private static final int NOTIF_QUIZ = 7601;
    private static final int NOTIF_CYCLE = 7602;
    private static final int REQ_QUIZ = 7611;
    private static final int REQ_CYCLE = 7612;

    /** Days before the expected period on which the cycle reminder is shown. */
    private static final int CYCLE_HEADS_UP_DAYS = 2;

    static final String K_CYCLE_REMINDER = "cycle_reminder";

    private DailyNudge() {}

    static boolean cycleReminderOn(Context ctx) {
        return Daily.kv(ctx).getBoolean(K_CYCLE_REMINDER, false);
    }

    /** Schedules or cancels the daily work to match the reminder switches. Never blocks. */
    public static void sync(Context context) {
        final Context app = context.getApplicationContext();
        Daily.io(() -> {
            try {
                WorkManager wm = WorkManager.getInstance(app);
                boolean wanted = (Quiz.reminderOn(app) && Quiz.everPlayed(app)) || cycleReminderOn(app);
                if (!wanted) {
                    wm.cancelUniqueWork(WORK);
                    return;
                }
                PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(Worker.class, 24, TimeUnit.HOURS)
                        .setInitialDelay(millisUntilNext(HOUR), TimeUnit.MILLISECONDS)
                        .build();
                wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request);
            } catch (Throwable t) {
                // WorkManager not initialised or DB trouble: reminders are optional.
                Log.e(TAG, "Unable to schedule the daily reminder", t);
            }
        });
    }

    private static long millisUntilNext(int hour) {
        Calendar c = Calendar.getInstance();
        long now = c.getTimeInMillis();
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= now) c.add(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis() - now;
    }

    public static final class Worker extends androidx.work.Worker {

        public Worker(@NonNull Context context, @NonNull WorkerParameters params) {
            super(context, params);
        }

        @NonNull
        @Override
        public Result doWork() {
            Context app = getApplicationContext();
            try {
                if (Quiz.reminderOn(app) && Quiz.everPlayed(app) && !Quiz.playedToday(app)) {
                    int streak = Quiz.streak(app);
                    notify(app, NOTIF_QUIZ, REQ_QUIZ, QuizActivity.class,
                            app.getString(R.string.dl_nudge_quiz_title),
                            streak > 0
                                    ? app.getResources().getQuantityString(R.plurals.dl_nudge_quiz_streak, streak, streak)
                                    : app.getString(R.string.dl_nudge_quiz_text));
                }
                if (cycleReminderOn(app)) {
                    Cycle cycle = new Cycle(CycleStore.open(app).load().periodDays());
                    if (cycle.hasData() && cycle.nextStart() - Daily.today() == CYCLE_HEADS_UP_DAYS) {
                        // Deliberately vague: this can show on a lock screen someone else is looking at.
                        notify(app, NOTIF_CYCLE, REQ_CYCLE, CycleActivity.class,
                                app.getString(R.string.dl_nudge_cycle_title),
                                app.getString(R.string.dl_nudge_cycle_text));
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "Daily reminder failed", t);
            }
            return Result.success();
        }

        private static void notify(Context app, int id, int requestCode, Class<?> target,
                                   String title, String text) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    NotificationChannel ch = new NotificationChannel(CHANNEL,
                            app.getString(R.string.dl_channel_daily), NotificationManager.IMPORTANCE_DEFAULT);
                    ch.setDescription(app.getString(R.string.dl_channel_daily_desc));
                    nm.createNotificationChannel(ch);
                }
            }
            PendingIntent open = PendingIntent.getActivity(app, requestCode,
                    new Intent(app, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            NotificationCompat.Builder nb = new NotificationCompat.Builder(app, CHANNEL)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setColor(ContextCompat.getColor(app, R.color.ns_rose))
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setAutoCancel(true)
                    .setContentIntent(open);
            try {
                NotificationManagerCompat.from(app).notify(id, nb.build());
            } catch (SecurityException e) {
                Log.w(TAG, "notify failed", e);
            }
        }
    }
}
