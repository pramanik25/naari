package com.example.naarishakti.cloud;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.google.gson.JsonObject;

import SQLite_Database.ProfileDbHelper;

/**
 * Sends the profile's name and mobile number to the server (PATCH /api/v1/me) so they show up in
 * the admin console. Runs on app start and after every profile save; a failed attempt (offline,
 * server down) is simply repeated on the next one.
 */
public final class ProfileSync {

    /** "userId\nname\nmobile" of the last successful sync, so unchanged profiles cost no request. */
    private static final String KEY_SYNCED = "profile_synced";

    private ProfileSync() {}

    /** Call after the profile was saved. Never blocks. */
    public static void push(Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        Cloud.io().execute(new Runnable() {
            @Override
            public void run() {
                ProfileSync.run(app);
            }
        });
    }

    /** Blocking; must run on {@link Cloud#io()}. */
    static void run(Context app) {
        if (!Cloud.isActive(app)) return;
        String name = null;
        String mobile = null;
        try {
            ProfileDbHelper db = new ProfileDbHelper(app);
            try {
                name = db.getProfileName();
                mobile = db.getProfileMobile();
            } finally {
                db.close();
            }
        } catch (Throwable t) {
            Log.w(Cloud.TAG, "Profile unreadable", t);
        }
        if (TextUtils.isEmpty(name) && TextUtils.isEmpty(mobile)) return;
        try {
            ApiClient api = ApiClient.get(app);
            api.ensureRegistered();
            String signature = api.userId() + "\n" + name + "\n" + mobile;
            if (signature.equals(Cloud.state(app).getString(KEY_SYNCED, null))) return;
            JsonObject patch = new JsonObject();
            if (!TextUtils.isEmpty(name)) patch.addProperty("name", name.length() > 80 ? name.substring(0, 80) : name);
            if (!TextUtils.isEmpty(mobile)) patch.addProperty("phone", mobile);
            JsonObject me = api.call("PATCH", "/api/v1/me", patch);
            // A server from before the phone field answers 200 without storing it: try again later.
            if (!TextUtils.isEmpty(mobile) && Json.str(me, "phone") == null) return;
            Cloud.state(app).edit().putString(KEY_SYNCED, signature).apply();
        } catch (Throwable t) {
            Log.i(Cloud.TAG, "Profile sync deferred: " + t.getMessage());
        }
    }
}
