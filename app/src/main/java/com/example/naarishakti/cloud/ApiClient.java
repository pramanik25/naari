package com.example.naarishakti.cloud;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import com.example.naarishakti.BuildConfig;
import com.example.naarishakti.core.Prefs;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import SQLite_Database.ProfileDbHelper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * HTTP client for the Naari Kavach API (backend/CONTRACT.md).
 *
 * <p>Every call is blocking and must run off the main thread (enforced). The device token is kept
 * in EncryptedSharedPreferences (private prefs if the keystore is broken); the user id in
 * {@link Prefs#CLOUD_USER_ID}. A 401 re-registers the device once and repeats the request.
 */
public final class ApiClient {

    private static final String TAG = "NsCloud.Api";
    static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private static final String SECURE_FILE = "ns_cloud_secure";
    private static final String FALLBACK_FILE = "ns_cloud_secure_fallback";
    private static final String KEY_TOKEN = "device_token";

    private static volatile ApiClient instance;

    private final Context app;
    private final OkHttpClient http;
    private volatile OkHttpClient wsClient;
    private volatile SharedPreferences secure;
    private final Object registerLock = new Object();

    public static ApiClient get(Context context) {
        ApiClient c = instance;
        if (c == null) {
            synchronized (ApiClient.class) {
                c = instance;
                if (c == null) {
                    c = new ApiClient(context.getApplicationContext());
                    instance = c;
                }
            }
        }
        return c;
    }

    private ApiClient(Context app) {
        this.app = app;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.MILLISECONDS) // evidence uploads may take minutes
                .retryOnConnectionFailure(true)
                .build();
    }

    /** Plain client (no auth), e.g. for signed evidence URLs. */
    OkHttpClient httpClient() {
        return http;
    }

    /** Client for the realtime socket: shares the pool, no read timeout, keep-alive pings. */
    OkHttpClient wsClient() {
        OkHttpClient c = wsClient;
        if (c == null) {
            synchronized (this) {
                c = wsClient;
                if (c == null) {
                    c = http.newBuilder()
                            .readTimeout(0, TimeUnit.MILLISECONDS)
                            .pingInterval(30, TimeUnit.SECONDS)
                            .build();
                    wsClient = c;
                }
            }
        }
        return c;
    }

    // ================================================================== credentials

    public boolean isRegistered() {
        return !TextUtils.isEmpty(token());
    }

    @Nullable
    String token() {
        String t = null;
        try {
            t = secure().getString(KEY_TOKEN, null);
        } catch (Throwable e) {
            Log.w(TAG, "Token read failed", e);
        }
        if (TextUtils.isEmpty(t)) {
            // Written while the keystore was unavailable.
            t = app.getSharedPreferences(FALLBACK_FILE, Context.MODE_PRIVATE).getString(KEY_TOKEN, null);
        }
        return TextUtils.isEmpty(t) ? null : t;
    }

    public String userId() {
        return Prefs.get(app).getString(Prefs.CLOUD_USER_ID, "");
    }

    private void saveCredentials(String token, String userId) {
        boolean saved = false;
        try {
            saved = secure().edit().putString(KEY_TOKEN, token).commit();
        } catch (Throwable e) {
            Log.e(TAG, "Encrypted token write failed; using private prefs", e);
        }
        SharedPreferences fb = app.getSharedPreferences(FALLBACK_FILE, Context.MODE_PRIVATE);
        if (saved && secure != fb) fb.edit().remove(KEY_TOKEN).apply();
        else if (!saved) fb.edit().putString(KEY_TOKEN, token).apply();
        Prefs.get(app).edit()
                .putString(Prefs.CLOUD_USER_ID, userId)
                .remove(Prefs.GUARDIAN_CODE)
                .apply();
    }

    /** Forget the token, user id and cached guardian code. */
    public void clearCredentials() {
        try {
            secure().edit().remove(KEY_TOKEN).commit();
        } catch (Throwable e) {
            Log.w(TAG, "Token clear failed", e);
        }
        app.getSharedPreferences(FALLBACK_FILE, Context.MODE_PRIVATE).edit().remove(KEY_TOKEN).apply();
        Prefs.get(app).edit().remove(Prefs.CLOUD_USER_ID).remove(Prefs.GUARDIAN_CODE).apply();
    }

    private SharedPreferences secure() {
        SharedPreferences s = secure;
        if (s != null) return s;
        synchronized (this) {
            if (secure == null) {
                try {
                    MasterKey key = new MasterKey.Builder(app)
                            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                            .build();
                    secure = EncryptedSharedPreferences.create(app, SECURE_FILE, key,
                            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
                } catch (Throwable t) {
                    Log.e(TAG, "Android keystore unavailable; device token stored in private prefs", t);
                    secure = app.getSharedPreferences(FALLBACK_FILE, Context.MODE_PRIVATE);
                }
            }
            return secure;
        }
    }

    // ================================================================== registration

    /** Registers this device when it has no token yet. Blocking. */
    public void ensureRegistered() throws CloudException {
        checkEnabled();
        if (isRegistered()) return;
        synchronized (registerLock) {
            if (isRegistered()) return;
            register();
        }
    }

    /** The server rejected {@code staleToken}: register again unless another thread already did. */
    void reRegister(@Nullable String staleToken) throws CloudException {
        checkEnabled();
        synchronized (registerLock) {
            String current = token();
            if (current != null && !current.equals(staleToken)) return;
            Log.w(TAG, "Device token rejected; registering again");
            clearCredentials();
            register();
        }
    }

    private void register() throws CloudException {
        assertBackground();
        JsonObject body = new JsonObject();
        body.addProperty("deviceName", deviceName());
        String name = profileName();
        if (!TextUtils.isEmpty(name)) body.addProperty("name", name);
        JsonObject res = runOnce(base("/api/v1/devices/register", null)
                .post(RequestBody.create(body.toString(), JSON))
                .build());
        String token = Json.str(res, "token");
        String userId = Json.str(res, "userId");
        if (TextUtils.isEmpty(token) || TextUtils.isEmpty(userId)) {
            throw new CloudException.BadResponse("register: missing token/userId");
        }
        saveCredentials(token, userId);
        Log.i(TAG, "Device registered");
    }

    private static String deviceName() {
        String m = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        String model = Build.MODEL == null ? "Android" : Build.MODEL.trim();
        if (!m.isEmpty() && !model.toLowerCase().startsWith(m.toLowerCase())) {
            model = Character.toUpperCase(m.charAt(0)) + m.substring(1) + " " + model;
        }
        return model;
    }

    @Nullable
    String profileName() {
        try {
            ProfileDbHelper db = new ProfileDbHelper(app);
            try {
                return db.getProfileName();
            } finally {
                db.close();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    // ================================================================== calls

    /**
     * Authenticated JSON call. {@code body} may be null (an empty object is sent for
     * POST/PUT/PATCH). Returns the parsed object, empty for 204 / non-object bodies.
     */
    @NonNull
    public JsonObject call(final String method, final String path, @Nullable JsonElement body)
            throws CloudException {
        final String json = body == null ? null : body.toString();
        return runAuthed(new RequestFactory() {
            @Override
            public Request build(@Nullable String token) throws CloudException {
                Request.Builder b = base(path, token);
                boolean needsBody = !"GET".equals(method) && !"DELETE".equals(method);
                RequestBody rb = json != null
                        ? RequestBody.create(json, JSON)
                        : (needsBody ? RequestBody.create("{}", JSON) : null);
                return b.method(method, rb).build();
            }
        });
    }

    /** Authenticated raw-body PUT (evidence upload). 409 surfaces as {@link CloudException.Http}. */
    @NonNull
    public JsonObject putFile(final String path, final File file, final String contentType,
                              final Map<String, String> headers) throws CloudException {
        return runAuthed(new RequestFactory() {
            @Override
            public Request build(@Nullable String token) throws CloudException {
                Request.Builder b = base(path, token);
                for (Map.Entry<String, String> h : headers.entrySet()) {
                    if (h.getValue() != null) b.header(h.getKey(), h.getValue());
                }
                return b.put(RequestBody.create(file, MediaType.parse(contentType))).build();
            }
        });
    }

    /** GET /healthz without auth. True when the server answers 2xx. Blocking. */
    public boolean ping() {
        if (!Cloud.isAvailable()) return false;
        try {
            runOnce(base("/healthz", null).get().build());
            return true;
        } catch (CloudException e) {
            return false;
        }
    }

    /** Absolute URL for an API path, e.g. "/api/v1/ws". */
    String url(String path) {
        return Cloud.baseUrl() + path;
    }

    // ------------------------------------------------------------------ plumbing

    private interface RequestFactory {
        Request build(@Nullable String token) throws CloudException;
    }

    private JsonObject runAuthed(RequestFactory factory) throws CloudException {
        assertBackground();
        ensureRegistered();
        String token = token();
        try {
            return runOnce(factory.build(token));
        } catch (CloudException.Unauthorized e) {
            reRegister(token);
            return runOnce(factory.build(token()));
        }
    }

    private Request.Builder base(String path, @Nullable String token) throws CloudException {
        Request.Builder b = new Request.Builder();
        try {
            b.url(url(path));
        } catch (IllegalArgumentException e) {
            throw new CloudException.Disabled("Invalid API_BASE_URL: " + e.getMessage());
        }
        b.header("Accept", "application/json");
        b.header("User-Agent", "NaariShakti-Android/" + BuildConfig.VERSION_NAME);
        if (token != null) b.header("Authorization", "Bearer " + token);
        return b;
    }

    private JsonObject runOnce(Request request) throws CloudException {
        assertBackground();
        Response response;
        try {
            response = http.newCall(request).execute();
        } catch (IOException e) {
            throw new CloudException.Network(e);
        }
        try {
            String text = "";
            ResponseBody rb = response.body();
            if (rb != null) {
                try {
                    text = rb.string();
                } catch (IOException e) {
                    if (!response.isSuccessful()) throw new CloudException.Network(e);
                }
            }
            JsonObject obj = Json.parseObject(text);
            if (response.isSuccessful()) return obj == null ? new JsonObject() : obj;
            throw toError(response.code(), obj, text);
        } finally {
            response.close();
        }
    }

    static CloudException toError(int status, @Nullable JsonObject body, @Nullable String raw) {
        String code = Json.str(body, "error");
        String msg = Json.str(body, "message");
        if (msg == null) msg = "HTTP " + status + (code != null ? " " + code : "");
        if (status == 401) return new CloudException.Unauthorized(code, msg);
        return new CloudException.Http(status, code, msg);
    }

    private void checkEnabled() throws CloudException {
        if (!Cloud.isAvailable()) throw new CloudException.Disabled("API_BASE_URL is not set");
        if (Cloud.isOptedOut(app)) throw new CloudException.Disabled("Cloud data was deleted by the user");
    }

    private static void assertBackground() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("Naari Kavach API calls must not run on the main thread");
        }
    }
}
