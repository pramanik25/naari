package com.example.naarishakti.cloud;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.gson.JsonObject;

/**
 * Keeps this device's FCM registration token on the server ({@code PUT /push-token}) so alerts
 * are pushed even when neither the app nor the protection engine is running (app closed, lock
 * screen). When Firebase is not configured (no google-services.json in the build) everything
 * here is a silent no-op, matching the rest of the cloud package.
 */
final class CloudPush {

    /** "<userId>:<fcmToken>" last queued for upload; re-sent when either part changes. */
    private static final String KEY_SYNCED = "push_synced";

    private CloudPush() {}

    /** Fetch the current FCM token and upload it if the server doesn't have it yet. Never blocks. */
    static void sync(@Nullable Context ctx) {
        if (ctx == null || !Cloud.isActive(ctx)) return;
        final Context app = ctx.getApplicationContext();
        try {
            FirebaseMessaging.getInstance().getToken()
                    .addOnCompleteListener(new OnCompleteListener<String>() {
                        @Override
                        public void onComplete(@NonNull Task<String> task) {
                            if (task.isSuccessful()) {
                                upload(app, task.getResult());
                            } else {
                                Log.i(Cloud.TAG, "FCM token unavailable", task.getException());
                            }
                        }
                    });
        } catch (Throwable t) {
            // Firebase not initialized (google-services.json missing): push stays off.
            Log.i(Cloud.TAG, "Push disabled: " + t.getMessage());
        }
    }

    /** FCM rotated the token (called by {@link CloudMessagingService#onNewToken}). */
    static void onNewToken(@Nullable Context ctx, @Nullable String token) {
        if (ctx == null || !Cloud.isActive(ctx)) return;
        Context app = ctx.getApplicationContext();
        Cloud.state(app).edit().remove(KEY_SYNCED).apply();
        upload(app, token);
    }

    private static void upload(Context app, @Nullable String token) {
        if (TextUtils.isEmpty(token)) return;
        // The marker includes the user id so a re-registered device (new account row on the
        // server, empty push token there) uploads again even though the FCM token is unchanged.
        String marker = ApiClient.get(app).userId() + ":" + token;
        if (marker.equals(Cloud.state(app).getString(KEY_SYNCED, null))) return;
        JsonObject body = new JsonObject();
        body.addProperty("token", token);
        CloudOutbox.add(app, "PUT", "/api/v1/push-token", body, "push_token");
        Cloud.state(app).edit().putString(KEY_SYNCED, marker).apply();
    }
}
