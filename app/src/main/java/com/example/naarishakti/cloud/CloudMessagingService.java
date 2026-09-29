package com.example.naarishakti.cloud;

import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.google.gson.JsonObject;

/**
 * Receives the server's FCM data messages ({@code {"n": "<notification json>"}}, see
 * backend/CONTRACT.md "Real-time alerts") and shows them through the same pipeline as the
 * WebSocket, so nearby-helper and guardian alerts ring full-screen on the lock screen even when
 * the app is closed. A phone that got the same alert over the socket drops the duplicate via the
 * shared seen-id store.
 */
public final class CloudMessagingService extends FirebaseMessagingService {

    @Override
    public void onMessageReceived(@NonNull RemoteMessage message) {
        JsonObject n = Json.parseObject(message.getData().get("n"));
        if (n == null) return;
        try {
            CloudAlerts.handlePush(this, n);
        } catch (Throwable t) {
            Log.e(Cloud.TAG, "Pushed alert failed", t);
        }
    }

    @Override
    public void onNewToken(@NonNull String token) {
        CloudPush.onNewToken(this, token);
    }
}
