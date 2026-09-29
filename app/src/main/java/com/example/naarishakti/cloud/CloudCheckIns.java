package com.example.naarishakti.cloud;

import android.content.Context;
import android.location.Location;
import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * Mirrors timed "I'm safe" check-ins to the server so it can raise the alarm even when the phone
 * is dead (CONTRACT.md "Check-ins"). All calls return immediately; requests are persisted in the
 * ordered {@link CloudOutbox} and retried through WorkManager, so they survive process death.
 * No-ops when cloud is off.
 */
public final class CloudCheckIns {

    private CloudCheckIns() {}

    /** Start (or re-send) check-in {@code id}; the deadline must be 1 min to 24 h ahead. */
    public static void start(Context context, String id, long deadlineMs, @Nullable String note,
                             @Nullable List<String> contacts) {
        if (!ready(context, id)) return;
        JsonObject body = new JsonObject();
        body.addProperty("deadline", deadlineMs);
        body.addProperty("note", note == null ? "" : note);
        JsonArray arr = new JsonArray();
        if (contacts != null) {
            for (String c : contacts) if (!TextUtils.isEmpty(c)) arr.add(c);
        }
        body.add("contacts", arr);
        CloudOutbox.add(context, "PUT", path(id), body, null);
    }

    /** Latest position for the check-in (older unsent positions are replaced). */
    public static void updateLocation(Context context, String id, @Nullable Location location) {
        if (!ready(context, id) || location == null) return;
        JsonObject body = new JsonObject();
        body.addProperty("lat", location.getLatitude());
        body.addProperty("lng", location.getLongitude());
        if (location.hasAccuracy()) body.addProperty("accuracy", location.getAccuracy());
        body.addProperty("at", location.getTime() > 0 ? location.getTime() : System.currentTimeMillis());
        CloudOutbox.add(context, "POST", path(id) + "/location", body, "checkin:" + id + ":location");
    }

    public static void extend(Context context, String id, long deadlineMs) {
        if (!ready(context, id)) return;
        JsonObject body = new JsonObject();
        body.addProperty("deadline", deadlineMs);
        CloudOutbox.add(context, "POST", path(id) + "/extend", body, "checkin:" + id + ":extend");
    }

    /** She is safe. */
    public static void complete(Context context, String id) {
        if (!ready(context, id)) return;
        CloudOutbox.add(context, "POST", path(id) + "/complete", null, null);
    }

    public static void cancel(Context context, String id) {
        if (!ready(context, id)) return;
        CloudOutbox.add(context, "POST", path(id) + "/cancel", null, null);
    }

    private static boolean ready(@Nullable Context context, @Nullable String id) {
        return context != null && !TextUtils.isEmpty(id) && Cloud.isActive(context);
    }

    private static String path(String id) {
        return "/api/v1/checkins/" + Uri.encode(id);
    }
}
