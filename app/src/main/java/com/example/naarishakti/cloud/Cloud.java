package com.example.naarishakti.cloud;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.naarishakti.BuildConfig;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Connection to the Naari Shakti API server.
 *
 * <p>Everything in this package is a harmless no-op when {@link BuildConfig#API_BASE_URL} is empty
 * ({@link #isAvailable()} false) or when the user deleted her cloud data ({@link #isOptedOut}).
 * No public method blocks the caller: network work always runs on {@link #io()} or WorkManager.
 */
public final class Cloud {

    static final String TAG = "NsCloud";

    /** Non-secret cloud state (the device token lives in {@link ApiClient}'s encrypted store). */
    private static final String STATE_FILE = "ns_cloud_state";
    private static final String KEY_OPTED_OUT = "opted_out";

    private static final AtomicInteger THREAD_NO = new AtomicInteger();
    private static final ExecutorService IO = Executors.newCachedThreadPool(threadFactory("ns-cloud-"));

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Grace period so a rotation / activity switch does not drop the realtime connection. */
    private static final long BACKGROUND_GRACE_MS = 5_000L;

    private static volatile boolean initialized;
    private static volatile int startedActivities;

    private Cloud() {}

    /**
     * Prepare the API client: creates notification channels, starts tracking app visibility for
     * the realtime connection and, on a background thread, registers this device if needed and
     * flushes anything queued by a previous process. Safe to call repeatedly; never blocks.
     */
    public static void init(Context context) {
        if (context == null || !isAvailable()) return;
        final Context app = context.getApplicationContext();
        synchronized (Cloud.class) {
            if (initialized) return;
            initialized = true;
        }
        try {
            CloudNotifier.ensureChannels(app);
        } catch (Throwable t) {
            Log.w(TAG, "Notification channels", t);
        }
        if (app instanceof Application) {
            ((Application) app).registerActivityLifecycleCallbacks(new VisibilityTracker(app));
        }
        IO.execute(new Runnable() {
            @Override
            public void run() {
                if (!isActive(app)) return;
                try {
                    ApiClient.get(app).ensureRegistered();
                } catch (Throwable t) {
                    // Offline or server down: every later call retries registration lazily.
                    Log.i(TAG, "Device registration deferred: " + t.getMessage());
                }
                try {
                    CloudOutbox.kick(app);
                    CloudUploads.enqueue(app);
                } catch (Throwable t) {
                    Log.w(TAG, "Startup flush failed", t);
                }
                CloudPush.sync(app);
                CloudAlerts.update(app);
                ProfileSync.run(app);
            }
        });
    }

    /** True when an API server is configured (BuildConfig.API_BASE_URL is set). */
    public static boolean isAvailable() {
        return !TextUtils.isEmpty(baseUrl());
    }

    // ------------------------------------------------------------------ package internals

    /** Configured and not switched off by the user ("Delete my cloud data"). */
    static boolean isActive(@Nullable Context ctx) {
        return ctx != null && isAvailable() && !isOptedOut(ctx);
    }

    static boolean isOptedOut(Context ctx) {
        return state(ctx).getBoolean(KEY_OPTED_OUT, false);
    }

    static void setOptedOut(Context ctx, boolean optedOut) {
        state(ctx).edit().putBoolean(KEY_OPTED_OUT, optedOut).apply();
    }

    static SharedPreferences state(Context ctx) {
        return com.example.naarishakti.core.Kv.get(ctx, STATE_FILE);
    }

    static ExecutorService io() {
        return IO;
    }

    /**
     * Daemon threads whose uncaught exceptions are logged instead of crashing the process: a
     * cloud bug must never take the SOS engine down with it.
     */
    static ThreadFactory threadFactory(final String prefix) {
        return new ThreadFactory() {
            @Override
            public Thread newThread(@NonNull Runnable r) {
                Thread t = new Thread(r, prefix + THREAD_NO.incrementAndGet());
                t.setDaemon(true);
                t.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                    @Override
                    public void uncaughtException(@NonNull Thread th, @NonNull Throwable e) {
                        Log.e(TAG, "Uncaught in " + th.getName(), e);
                    }
                });
                return t;
            }
        };
    }

    static Handler main() {
        return MAIN;
    }

    /** API base without a trailing slash, or "" when cloud is off. */
    static String baseUrl() {
        String base = BuildConfig.API_BASE_URL;
        if (base == null) return "";
        base = base.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base;
    }

    /** True while at least one of our activities is started (visible). */
    static boolean isAppVisible() {
        return startedActivities > 0;
    }

    private static final class VisibilityTracker implements Application.ActivityLifecycleCallbacks {
        private final Context app;
        private final Runnable wentBackground = new Runnable() {
            @Override
            public void run() {
                CloudAlerts.update(app);
            }
        };

        VisibilityTracker(Context app) {
            this.app = app;
        }

        @Override
        public void onActivityStarted(@NonNull Activity activity) {
            startedActivities++;
            if (startedActivities == 1) {
                MAIN.removeCallbacks(wentBackground);
                CloudAlerts.update(app);
                try {
                    CloudHelper.onAppOpened(app);
                } catch (Throwable t) {
                    Log.w(TAG, "Helper refresh on open failed", t);
                }
                try {
                    CircleShare.onAppOpened(app);
                } catch (Throwable t) {
                    Log.w(TAG, "Circle refresh on open failed", t);
                }
                CloudPush.sync(app);
            }
        }

        @Override
        public void onActivityStopped(@NonNull Activity activity) {
            if (startedActivities > 0) startedActivities--;
            if (startedActivities == 0) {
                MAIN.removeCallbacks(wentBackground);
                MAIN.postDelayed(wentBackground, BACKGROUND_GRACE_MS);
            }
        }

        @Override public void onActivityCreated(@NonNull Activity a, @Nullable Bundle b) {}
        @Override public void onActivityResumed(@NonNull Activity a) {}
        @Override public void onActivityPaused(@NonNull Activity a) {}
        @Override public void onActivitySaveInstanceState(@NonNull Activity a, @NonNull Bundle b) {}
        @Override public void onActivityDestroyed(@NonNull Activity a) {}
    }
}
