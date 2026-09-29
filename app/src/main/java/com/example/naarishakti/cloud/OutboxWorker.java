package com.example.naarishakti.cloud;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/** WorkManager side of {@link CloudOutbox}: runs when the network is back, survives process death. */
public final class OutboxWorker extends Worker {

    public OutboxWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context app = getApplicationContext();
        if (!Cloud.isActive(app)) return Result.success();
        return CloudOutbox.drain(app) == CloudOutbox.TRANSIENT ? Result.retry() : Result.success();
    }
}
