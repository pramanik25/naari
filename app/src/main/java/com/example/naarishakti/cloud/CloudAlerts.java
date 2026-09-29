package com.example.naarishakti.cloud;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Realtime alerts: an OkHttp WebSocket to {@code /api/v1/ws}, alive while the protection engine
 * runs (foreground service) or while any app screen is visible. Reconnects with exponential
 * backoff and immediately when the network returns; on every connect it catches up with
 * {@code GET /notifications?since=}. Every message is acked and de-duplicated by id.
 */
final class CloudAlerts {

    private static final String TAG = "NsCloud.Alerts";
    private static final String KEY_LAST_SEEN = "alerts_last_seen";
    private static final String KEY_SEEN_IDS = "alerts_seen_ids";
    private static final int MAX_SEEN_IDS = 300;
    private static final long MIN_BACKOFF_MS = 2_000L;
    private static final long MAX_BACKOFF_MS = 5 * 60_000L;

    private static final Object LOCK = new Object();
    private static final Random RANDOM = new Random();

    private static volatile boolean engineRunning;
    private static volatile Context app;

    // Guarded by LOCK.
    @Nullable private static WebSocket socket;
    private static int generation;
    private static int attempt;
    @Nullable private static ConnectivityManager.NetworkCallback networkCallback;
    @Nullable private static LinkedHashSet<String> seenIds;

    private static final Runnable RECONNECT = new Runnable() {
        @Override
        public void run() {
            Context a = app;
            if (a != null) update(a);
        }
    };

    private CloudAlerts() {}

    static void setEngineRunning(Context ctx, boolean running) {
        engineRunning = running;
        update(ctx);
    }

    /** Re-evaluate whether the socket should be open and connect/disconnect accordingly. */
    static void update(Context ctx) {
        if (ctx == null || !Cloud.isAvailable()) return;
        app = ctx.getApplicationContext();
        Cloud.io().execute(new Runnable() {
            @Override
            public void run() {
                evaluate();
            }
        });
    }

    private static boolean shouldRun(Context a) {
        return Cloud.isActive(a) && (engineRunning || Cloud.isAppVisible());
    }

    private static void evaluate() {
        final Context a = app;
        if (a == null) return;
        if (!shouldRun(a)) {
            disconnect();
            return;
        }
        synchronized (LOCK) {
            if (socket != null) return;
        }
        ApiClient api = ApiClient.get(a);
        try {
            api.ensureRegistered();
        } catch (CloudException e) {
            if (!(e instanceof CloudException.Disabled)) scheduleReconnect();
            return;
        }
        String token = api.token();
        if (token == null) {
            scheduleReconnect();
            return;
        }
        Request request;
        try {
            request = new Request.Builder()
                    .url(api.url("/api/v1/ws"))
                    .header("Authorization", "Bearer " + token)
                    .build();
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Bad websocket URL", e);
            return;
        }
        synchronized (LOCK) {
            if (socket != null || !shouldRun(a)) return;
            int gen = ++generation;
            Cloud.main().removeCallbacks(RECONNECT);
            socket = api.wsClient().newWebSocket(request, new Listener(gen, token));
            registerNetworkCallback(a);
        }
    }

    private static void disconnect() {
        synchronized (LOCK) {
            Cloud.main().removeCallbacks(RECONNECT);
            generation++;
            attempt = 0;
            if (socket != null) {
                try {
                    socket.close(1000, null);
                } catch (Throwable ignored) {
                }
                socket = null;
            }
            unregisterNetworkCallback();
        }
    }

    private static void scheduleReconnect() {
        Context a = app;
        if (a == null || !shouldRun(a)) return;
        long delay;
        synchronized (LOCK) {
            long base = MIN_BACKOFF_MS << Math.min(attempt, 8);
            delay = Math.min(MAX_BACKOFF_MS, base) + RANDOM.nextInt(1000);
            attempt++;
        }
        Cloud.main().removeCallbacks(RECONNECT);
        Cloud.main().postDelayed(RECONNECT, delay);
    }

    // ------------------------------------------------------------------ socket events

    private static final class Listener extends WebSocketListener {
        private final int gen;
        private final String token;

        Listener(int gen, String token) {
            this.gen = gen;
            this.token = token;
        }

        private boolean current() {
            synchronized (LOCK) {
                return gen == generation;
            }
        }

        @Override
        public void onOpen(@NonNull final WebSocket ws, @NonNull Response response) {
            if (!current()) {
                ws.close(1000, null);
                return;
            }
            synchronized (LOCK) {
                attempt = 0;
            }
            Log.i(TAG, "Realtime connected");
            Cloud.io().execute(new Runnable() {
                @Override
                public void run() {
                    catchUp(ws);
                }
            });
        }

        @Override
        public void onMessage(@NonNull WebSocket ws, @NonNull String text) {
            if (!current()) return;
            JsonObject msg = Json.parseObject(text);
            if (msg != null) handle(msg, ws);
        }

        @Override
        public void onClosing(@NonNull WebSocket ws, int code, @NonNull String reason) {
            ws.close(1000, null);
        }

        @Override
        public void onClosed(@NonNull WebSocket ws, int code, @NonNull String reason) {
            lost(null);
        }

        @Override
        public void onFailure(@NonNull WebSocket ws, @NonNull Throwable t, @Nullable Response response) {
            Log.i(TAG, "Realtime connection lost: " + t.getMessage());
            lost(response);
        }

        private void lost(@Nullable Response response) {
            synchronized (LOCK) {
                if (gen != generation) return;
                socket = null;
            }
            final boolean unauthorized = response != null && response.code() == 401;
            Cloud.io().execute(new Runnable() {
                @Override
                public void run() {
                    Context a = app;
                    if (unauthorized && a != null) {
                        try {
                            ApiClient.get(a).reRegister(token);
                        } catch (CloudException e) {
                            Log.w(TAG, "Re-register failed: " + e.getMessage());
                        }
                    }
                    scheduleReconnect();
                }
            });
        }
    }

    // ------------------------------------------------------------------ messages

    private static void catchUp(WebSocket ws) {
        Context a = app;
        if (a == null) return;
        long since = Cloud.state(a).getLong(KEY_LAST_SEEN, 0L);
        try {
            JsonObject res = ApiClient.get(a).call("GET", "/api/v1/notifications?since=" + since, null);
            JsonArray list = Json.arr(res, "notifications");
            if (list == null) return;
            List<JsonObject> items = new ArrayList<>();
            for (JsonElement e : list) if (e != null && e.isJsonObject()) items.add(e.getAsJsonObject());
            for (JsonObject n : items) handle(n, ws);
        } catch (CloudException e) {
            Log.i(TAG, "Catch-up failed: " + e.getMessage());
        }
    }

    private static void handle(JsonObject msg, WebSocket ws) {
        Context a = app;
        if (a == null) return;
        String type = Json.str(msg, "type");
        String id = Json.str(msg, "id");
        if (type == null || id == null || "pong".equals(type) || "ping".equals(type)) return;

        if (markSeen(a, id)) {
            try {
                CloudNotifier.show(a, msg);
            } catch (Throwable t) {
                Log.e(TAG, "Showing alert " + type + " failed", t);
            }
        }
        long at = Json.lng(msg, "at", 0L);
        SharedPreferences st = Cloud.state(a);
        if (at > st.getLong(KEY_LAST_SEEN, 0L)) st.edit().putLong(KEY_LAST_SEEN, at).apply();

        JsonObject ack = new JsonObject();
        ack.addProperty("type", "ack");
        ack.addProperty("id", id);
        ws.send(ack.toString());
    }

    /** True the first time an id is seen (persisted across restarts). */
    private static boolean markSeen(Context a, String id) {
        synchronized (LOCK) {
            if (seenIds == null) {
                seenIds = new LinkedHashSet<>();
                String raw = Cloud.state(a).getString(KEY_SEEN_IDS, "");
                if (!TextUtils.isEmpty(raw)) Collections.addAll(seenIds, raw.split(","));
            }
            if (!seenIds.add(id)) return false;
            while (seenIds.size() > MAX_SEEN_IDS) {
                seenIds.remove(seenIds.iterator().next());
            }
            Cloud.state(a).edit().putString(KEY_SEEN_IDS, TextUtils.join(",", seenIds)).apply();
            return true;
        }
    }

    // ------------------------------------------------------------------ connectivity

    private static void registerNetworkCallback(Context a) {
        if (networkCallback != null) return;
        ConnectivityManager cm = (ConnectivityManager) a.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        ConnectivityManager.NetworkCallback cb = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                boolean idle;
                synchronized (LOCK) {
                    idle = socket == null;
                    if (idle) attempt = 0;
                }
                if (idle) {
                    Cloud.main().removeCallbacks(RECONNECT);
                    Cloud.main().post(RECONNECT);
                }
            }
        };
        try {
            cm.registerDefaultNetworkCallback(cb);
            networkCallback = cb;
        } catch (Throwable t) {
            Log.w(TAG, "Network callback unavailable", t);
        }
    }

    private static void unregisterNetworkCallback() {
        ConnectivityManager.NetworkCallback cb = networkCallback;
        Context a = app;
        networkCallback = null;
        if (cb == null || a == null) return;
        try {
            ConnectivityManager cm = (ConnectivityManager) a.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) cm.unregisterNetworkCallback(cb);
        } catch (Throwable ignored) {
        }
    }
}
