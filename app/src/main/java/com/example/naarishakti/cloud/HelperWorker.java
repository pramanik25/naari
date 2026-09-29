package com.example.naarishakti.cloud;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/** Refreshes the nearby-helper location on the server (PUT /helper). */
public final class HelperWorker extends Worker {

    public HelperWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        if (!Cloud.isActive(ctx) || !CloudHelper.isOptedIn(ctx)) return Result.success();
        try {
            if (!CloudHelper.pushLocation(ctx)) Log.i(Cloud.TAG, "Helper refresh skipped: no location");
            return Result.success();
        } catch (CloudException e) {
            return e.isTransient() ? Result.retry() : Result.success();
        } catch (RuntimeException e) {
            Log.e(Cloud.TAG, "Helper refresh failed", e);
            return Result.success();
        }
    }
}
