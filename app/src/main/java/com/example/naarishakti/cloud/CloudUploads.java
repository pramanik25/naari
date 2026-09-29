package com.example.naarishakti.cloud;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;

import com.example.naarishakti.core.Prefs;

import java.util.concurrent.TimeUnit;

/**
 * Evidence upload queue. Call {@link #enqueue} whenever a new item lands in
 * {@code EvidenceStore}; the worker uploads everything pending, oldest first.
 */
public final class CloudUploads {

    static final String WORK_NAME = "ns_evidence_upload";
    private static final String KEY_NETWORK = "upload_network";

    private CloudUploads() {}

    /**
     * Schedule (or keep) the upload worker. Network-connected constraint, unmetered only when
     * {@link Prefs#EVIDENCE_UPLOAD_MOBILE} is off. Never blocks; no-op when cloud is off.
     */
    public static void enqueue(Context context) {
        if (context == null || !Cloud.isAvailable()) return;
        final Context app = context.getApplicationContext();
        if (!Cloud.isActive(app)) return;
        Cloud.io().execute(new Runnable() {
            @Override
            public void run() {
                boolean mobile = Prefs.get(app).getBoolean(Prefs.EVIDENCE_UPLOAD_MOBILE, true);
                String network = mobile ? "any" : "unmetered";
                SharedPreferences state = Cloud.state(app);
                // Constraints changed since the last enqueue: replace the waiting job.
                boolean replace = !network.equals(state.getString(KEY_NETWORK, network));
                state.edit().putString(KEY_NETWORK, network).apply();

                OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(UploadWorker.class)
                        .setConstraints(new Constraints.Builder()
                                .setRequiredNetworkType(mobile ? NetworkType.CONNECTED : NetworkType.UNMETERED)
                                .build())
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                        .addTag(WORK_NAME)
                        .build();
                WorkUtil.enqueueDrain(app, WORK_NAME, req, replace);
            }
        });
    }
}
