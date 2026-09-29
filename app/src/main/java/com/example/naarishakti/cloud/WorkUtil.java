package com.example.naarishakti.cloud;

import android.content.Context;
import android.util.Log;

import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** WorkManager helpers. Blocking: call from a background thread. */
final class WorkUtil {

    private static final Object LOCK = new Object();

    private WorkUtil() {}

    /**
     * Enqueue a "drain the queue" worker without piling up duplicates: nothing is done while one
     * is already waiting (it will see the new items), a follow-up is appended while one is running
     * (it may already have looked at the queue), otherwise the request is enqueued fresh.
     *
     * @param replace cancel whatever is there and start over (e.g. constraints changed).
     */
    static void enqueueDrain(Context ctx, String uniqueName, OneTimeWorkRequest request, boolean replace) {
        synchronized (LOCK) {
            try {
                WorkManager wm = WorkManager.getInstance(ctx.getApplicationContext());
                if (replace) {
                    wm.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, request);
                    return;
                }
                boolean running = false;
                try {
                    List<WorkInfo> infos = wm.getWorkInfosForUniqueWork(uniqueName).get(5, TimeUnit.SECONDS);
                    if (infos != null) {
                        for (WorkInfo info : infos) {
                            WorkInfo.State s = info.getState();
                            if (s == WorkInfo.State.ENQUEUED || s == WorkInfo.State.BLOCKED) return;
                            if (s == WorkInfo.State.RUNNING) running = true;
                        }
                    }
                } catch (Exception e) {
                    Log.w(Cloud.TAG, "Work state lookup failed for " + uniqueName, e);
                }
                wm.enqueueUniqueWork(uniqueName,
                        running ? ExistingWorkPolicy.APPEND_OR_REPLACE : ExistingWorkPolicy.REPLACE,
                        request);
            } catch (Throwable t) {
                // WorkManager not initialised (custom Application config) or DB trouble.
                Log.e(Cloud.TAG, "Unable to enqueue " + uniqueName, t);
            }
        }
    }
}
