package com.example.naarishakti.cloud;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.example.naarishakti.R;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Notification channels and the per-type presentation of realtime alerts (CONTRACT.md table). */
final class CloudNotifier {

    static final String CH_GUARDIAN = "ns_guardian";
    static final String CH_HELPER = "ns_helper";
    static final String CH_CLOUD = "ns_cloud";

    /** Local broadcast when an incident this phone was alerted about has ended. */
    static final String ACTION_ALERT_ENDED = "com.example.naarishakti.cloud.ACTION_ALERT_ENDED";
    /** Local broadcast for an open alert screen: a newer photo / evidence count is available. */
    static final String ACTION_EVIDENCE_UPDATE = "com.example.naarishakti.cloud.ACTION_EVIDENCE_UPDATE";
    static final String EXTRA_INCIDENT_ID = "incidentId";
    static final String EXTRA_PHOTO_URL = "photoUrl";
    static final String EXTRA_EVIDENCE_COUNT = "evidenceCount";
    static final String EXTRA_TRACK_URL = "trackUrl";

    /** Alarms older than this (caught up after being offline) are shown quietly. */
    private static final long STALE_ALARM_MS = 30 * 60_000L;
    /** A helper alert older than this is no longer actionable. */
    private static final long STALE_HELPER_MS = 15 * 60_000L;
    /** Remembered alert details are dropped after this. */
    private static final long META_TTL_MS = 24 * 3_600_000L;
    /** BigPicture photos are downsampled to about this many pixels on the long side. */
    private static final int NOTIFICATION_PHOTO_PX = 1024;

    private static final String KEY_META = "alert_meta";
    private static final String ROLE_GUARDIAN = "g";
    private static final String ROLE_HELPER = "h";

    private static final long[] VIBRATION = {0, 800, 400, 800, 400, 800};
    private static final Object META_LOCK = new Object();

    private CloudNotifier() {}

    /** Idempotent: channel settings the user changed are kept by the system. */
    static void ensureChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        AudioAttributes alarmAttrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        Uri alarm = alarmSound();

        NotificationChannel guardian = new NotificationChannel(CH_GUARDIAN,
                ctx.getString(R.string.cl_channel_guardian), NotificationManager.IMPORTANCE_HIGH);
        guardian.setDescription(ctx.getString(R.string.cl_channel_guardian_desc));
        guardian.setSound(alarm, alarmAttrs);
        guardian.enableVibration(true);
        guardian.setVibrationPattern(VIBRATION);
        guardian.enableLights(true);
        guardian.setLightColor(ContextCompat.getColor(ctx, R.color.ns_rose));
        guardian.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(guardian);

        NotificationChannel helper = new NotificationChannel(CH_HELPER,
                ctx.getString(R.string.cl_channel_helper), NotificationManager.IMPORTANCE_HIGH);
        helper.setDescription(ctx.getString(R.string.cl_channel_helper_desc));
        helper.setSound(alarm, alarmAttrs);
        helper.enableVibration(true);
        helper.setVibrationPattern(VIBRATION);
        helper.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(helper);

        NotificationChannel cloud = new NotificationChannel(CH_CLOUD,
                ctx.getString(R.string.cl_channel_cloud), NotificationManager.IMPORTANCE_DEFAULT);
        cloud.setDescription(ctx.getString(R.string.cl_channel_cloud_desc));
        nm.createNotificationChannel(cloud);
    }

    private static Uri alarmSound() {
        Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        return u;
    }

    /** Present one server notification ({@code {id, type, at, ...fields}}). */
    static void show(Context ctx, JsonObject n) {
        ensureChannels(ctx);
        String type = Json.str(n, "type");
        if (type == null) return;
        String incidentId = Json.str(n, "incidentId");
        long at = Json.lng(n, "at", 0L);
        long age = at > 0 ? System.currentTimeMillis() - at : 0L;
        String owner = Json.str(n, "ownerName");
        if (owner == null) owner = ctx.getString(R.string.cl_n_someone);
        String trackUrl = Json.str(n, "trackUrl");

        switch (type) {
            case "sos":
                guardianAlarm(ctx, incidentId, age,
                        ctx.getString(R.string.cl_n_sos_title, owner),
                        ctx.getString(R.string.cl_n_sos_text), trackUrl);
                break;
            case "duress":
                guardianAlarm(ctx, incidentId, age,
                        ctx.getString(R.string.cl_n_duress_title, owner),
                        ctx.getString(R.string.cl_n_duress_text), trackUrl);
                break;
            case "checkin_overdue": {
                String note = Json.str(n, "note");
                String text = TextUtils.isEmpty(note)
                        ? ctx.getString(R.string.cl_n_overdue_text)
                        : ctx.getString(R.string.cl_n_overdue_text_note, note);
                guardianAlarm(ctx, incidentId, age, ctx.getString(R.string.cl_n_overdue_title, owner), text, trackUrl);
                break;
            }
            case "helper_alert":
                if (age > STALE_HELPER_MS) return;
                helperAlert(ctx, n, incidentId, trackUrl);
                break;
            case "evidence_update":
                evidenceUpdate(ctx, n, incidentId, trackUrl);
                break;
            case "responder": {
                String helper = Json.str(n, "helperName");
                if (helper == null) helper = ctx.getString(R.string.cl_n_responder_someone);
                double d = Json.dbl(n, "distanceM", -1);
                String text = d >= 0
                        ? ctx.getString(R.string.cl_n_responder_text, helper, formatDistance(ctx, d))
                        : ctx.getString(R.string.cl_n_responder_text_nodist, helper);
                plain(ctx, id("resp", incidentId + helper), ctx.getString(R.string.cl_n_responder_title),
                        text, trackUrl);
                break;
            }
            case "ended":
                cancel(ctx, id("inc", incidentId));
                cancel(ctx, id("help", incidentId));
                forgetMeta(ctx, incidentId);
                plain(ctx, id("inc", incidentId), ctx.getString(R.string.cl_n_ended_title, owner),
                        ctx.getString(R.string.cl_n_ended_text), null);
                if (incidentId != null) {
                    LocalBroadcastManager.getInstance(ctx).sendBroadcast(
                            new Intent(ACTION_ALERT_ENDED).putExtra(EXTRA_INCIDENT_ID, incidentId));
                }
                break;
            default:
                Log.i(Cloud.TAG, "Ignoring unknown alert type " + type);
        }
    }

    // ================================================================== guardians

    /** sos / duress / checkin_overdue: full-screen, alarm sound, opens live tracking. */
    private static void guardianAlarm(Context ctx, @Nullable String incidentId, long age, String title,
                                      String text, @Nullable String trackUrl) {
        boolean stale = age > STALE_ALARM_MS;
        JsonObject meta = new JsonObject();
        meta.addProperty("title", title);
        meta.addProperty("text", text);
        meta.addProperty("alarm", !stale);
        if (trackUrl != null) meta.addProperty("trackUrl", trackUrl);
        if (incidentId != null) {
            JsonObject old = getMeta(ctx, ROLE_GUARDIAN, incidentId);
            // Keep evidence already known for this incident (sos → duress keeps the photo).
            copyIfPresent(old, meta, "photoUrl");
            copyIfPresent(old, meta, "evidenceCount");
            putMeta(ctx, ROLE_GUARDIAN, incidentId, meta);
        }
        notify(ctx, id("inc", incidentId), guardianBuilder(ctx, incidentId, meta, !stale, !stale, stale, age, null));
    }

    /**
     * @param alerting   alarm sound, repeating until acted on
     * @param fullScreen also fire the full-screen intent (first post only, never on updates)
     */
    private static Notification guardianBuilder(Context ctx, @Nullable String incidentId, JsonObject meta,
                                                boolean alerting, boolean fullScreen, boolean stale,
                                                long age, @Nullable Bitmap photo) {
        int nid = id("inc", incidentId);
        String title = Json.str(meta, "title");
        String text = Json.str(meta, "text");
        if (title == null) title = ctx.getString(R.string.cl_n_sos_title, ctx.getString(R.string.cl_n_someone));
        if (text == null) text = ctx.getString(R.string.cl_n_sos_text);
        PendingIntent open = PendingIntent.getActivity(ctx, nid,
                trackingIntent(ctx, Json.str(meta, "trackUrl")), piFlags());

        String channel = alerting ? CH_GUARDIAN : (stale || !isActive(ctx, nid) ? CH_CLOUD : CH_GUARDIAN);
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, channel)
                .setSmallIcon(R.drawable.cl_ic_shield)
                .setColor(ContextCompat.getColor(ctx, R.color.ns_rose))
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(open)
                .setAutoCancel(true)
                .addAction(R.drawable.cl_ic_location, ctx.getString(R.string.cl_action_open_tracking), open);
        applyEvidenceStyle(ctx, b, meta, text, photo);
        if (alerting) {
            b.setPriority(NotificationCompat.PRIORITY_MAX)
                    .setSound(alarmSound(), AudioManager.STREAM_ALARM)
                    .setVibrate(VIBRATION);
            if (fullScreen) b.setFullScreenIntent(open, true);
        } else {
            b.setOnlyAlertOnce(true).setPriority(NotificationCompat.PRIORITY_DEFAULT);
            if (stale) {
                b.setSubText(ctx.getString(R.string.cl_n_earlier))
                        .setWhen(System.currentTimeMillis() - age)
                        .setShowWhen(true);
            }
        }
        Notification notification = b.build();
        if (alerting) notification.flags |= Notification.FLAG_INSISTENT;
        return notification;
    }

    // ================================================================== helpers

    private static void helperAlert(Context ctx, JsonObject n, @Nullable String incidentId,
                                    @Nullable String trackUrl) {
        if (incidentId == null) return;
        JsonObject meta = new JsonObject();
        meta.addProperty("lat", Json.dbl(n, "lat", Double.NaN));
        meta.addProperty("lng", Json.dbl(n, "lng", Double.NaN));
        meta.addProperty("distanceM", Json.dbl(n, "distanceM", -1));
        meta.addProperty("radiusKm", Json.lng(n, "radiusKm", 0L));
        meta.addProperty("evidenceCount", Json.lng(n, "evidenceCount", 0L));
        String photoUrl = Json.str(n, "photoUrl");
        if (photoUrl != null) meta.addProperty("photoUrl", photoUrl);
        if (trackUrl != null) meta.addProperty("trackUrl", trackUrl);
        putMeta(ctx, ROLE_HELPER, incidentId, meta);

        Intent activity = helperIntent(ctx, incidentId, meta);
        notify(ctx, id("help", incidentId), helperBuilder(ctx, incidentId, meta, true, null));

        // While the app is on screen a direct start is allowed and faster than the heads-up.
        if (Cloud.isAppVisible()) {
            try {
                ctx.startActivity(activity);
            } catch (Throwable t) {
                Log.w(Cloud.TAG, "Direct helper alert start refused", t);
            }
        }
        if (photoUrl != null) refreshWithPhoto(ctx, ROLE_HELPER, incidentId);
    }

    private static Intent helperIntent(Context ctx, String incidentId, JsonObject meta) {
        return new Intent(ctx, HelperAlertActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(HelperAlertActivity.EXTRA_INCIDENT_ID, incidentId)
                .putExtra(HelperAlertActivity.EXTRA_LAT, Json.dbl(meta, "lat", Double.NaN))
                .putExtra(HelperAlertActivity.EXTRA_LNG, Json.dbl(meta, "lng", Double.NaN))
                .putExtra(HelperAlertActivity.EXTRA_DISTANCE_M, Json.dbl(meta, "distanceM", -1))
                .putExtra(HelperAlertActivity.EXTRA_RADIUS_KM, (int) Json.lng(meta, "radiusKm", 0L))
                .putExtra(HelperAlertActivity.EXTRA_EVIDENCE_COUNT, (int) Json.lng(meta, "evidenceCount", 0L))
                .putExtra(HelperAlertActivity.EXTRA_PHOTO_URL, Json.str(meta, "photoUrl"))
                .putExtra(HelperAlertActivity.EXTRA_TRACK_URL, Json.str(meta, "trackUrl"))
                .putExtra(HelperAlertActivity.EXTRA_NOTIFICATION_ID, id("help", incidentId));
    }

    private static Notification helperBuilder(Context ctx, String incidentId, JsonObject meta,
                                              boolean alerting, @Nullable Bitmap photo) {
        int nid = id("help", incidentId);
        PendingIntent pi = PendingIntent.getActivity(ctx, nid, helperIntent(ctx, incidentId, meta), piFlags());
        double d = Json.dbl(meta, "distanceM", -1);
        String text = d >= 0
                ? ctx.getString(R.string.cl_n_helper_text, formatDistance(ctx, d))
                : ctx.getString(R.string.cl_n_helper_text_nodist);
        String channel = alerting || isActive(ctx, nid) ? CH_HELPER : CH_CLOUD;
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, channel)
                .setSmallIcon(R.drawable.cl_ic_shield)
                .setColor(ContextCompat.getColor(ctx, R.color.ns_rose))
                .setContentTitle(ctx.getString(R.string.cl_n_helper_title))
                .setContentText(text)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .addAction(R.drawable.cl_ic_navigation, ctx.getString(R.string.cl_action_respond), pi);
        applyEvidenceStyle(ctx, b, meta, text, photo);
        if (alerting) {
            b.setPriority(NotificationCompat.PRIORITY_MAX)
                    .setFullScreenIntent(pi, true)
                    .setSound(alarmSound(), AudioManager.STREAM_ALARM)
                    .setVibrate(VIBRATION);
        } else {
            b.setOnlyAlertOnce(true).setPriority(NotificationCompat.PRIORITY_HIGH);
        }
        return b.build();
    }

    // ================================================================== evidence

    /**
     * {@code evidence_update}: an open alert screen refreshes its photo in place; otherwise the
     * existing notification for the incident (same id) is re-posted quietly with the photo.
     */
    private static void evidenceUpdate(Context ctx, JsonObject n, @Nullable String incidentId,
                                       @Nullable String trackUrl) {
        if (incidentId == null) return;
        String photoUrl = Json.str(n, "photoUrl");
        long count = Json.lng(n, "evidenceCount", -1L);
        boolean known = false;
        for (String role : new String[]{ROLE_GUARDIAN, ROLE_HELPER}) {
            JsonObject meta = getMeta(ctx, role, incidentId);
            if (meta == null) continue;
            known = true;
            if (photoUrl != null) meta.addProperty("photoUrl", photoUrl);
            if (count >= 0) meta.addProperty("evidenceCount", count);
            if (trackUrl != null) meta.addProperty("trackUrl", trackUrl);
            putMeta(ctx, role, incidentId, meta);
        }
        if (!known) {
            Log.i(Cloud.TAG, "evidence_update for an incident this phone was not alerted about");
            return;
        }
        if (HelperAlertActivity.isShowing(incidentId)) {
            LocalBroadcastManager.getInstance(ctx).sendBroadcast(new Intent(ACTION_EVIDENCE_UPDATE)
                    .putExtra(EXTRA_INCIDENT_ID, incidentId)
                    .putExtra(EXTRA_PHOTO_URL, photoUrl)
                    .putExtra(EXTRA_EVIDENCE_COUNT, (int) count)
                    .putExtra(EXTRA_TRACK_URL, trackUrl));
            // The open screen covers the helper side; a guardian notification still updates.
            if (getMeta(ctx, ROLE_GUARDIAN, incidentId) != null) refreshWithPhoto(ctx, ROLE_GUARDIAN, incidentId);
            return;
        }
        if (getMeta(ctx, ROLE_GUARDIAN, incidentId) != null) refreshWithPhoto(ctx, ROLE_GUARDIAN, incidentId);
        if (getMeta(ctx, ROLE_HELPER, incidentId) != null) refreshWithPhoto(ctx, ROLE_HELPER, incidentId);
    }

    /** Downloads the photo off the caller's thread, then re-posts the notification quietly. */
    private static void refreshWithPhoto(final Context ctx, final String role, final String incidentId) {
        Cloud.io().execute(new Runnable() {
            @Override
            public void run() {
                JsonObject meta = getMeta(ctx, role, incidentId);
                if (meta == null) return;
                Bitmap photo = CloudImages.load(ctx, Json.str(meta, "photoUrl"), NOTIFICATION_PHOTO_PX);
                // The incident may have ended while downloading.
                meta = getMeta(ctx, role, incidentId);
                if (meta == null) return;
                if (ROLE_HELPER.equals(role)) {
                    // Qualified: inside a Runnable, a bare notify() resolves to Object.notify().
                    CloudNotifier.notify(ctx, id("help", incidentId), helperBuilder(ctx, incidentId, meta, false, photo));
                } else {
                    // An alarm nobody has acted on yet keeps ringing; otherwise update quietly.
                    int nid = id("inc", incidentId);
                    boolean ringing = Json.bool(meta, "alarm", false) && isActive(ctx, nid);
                    CloudNotifier.notify(ctx, nid, guardianBuilder(ctx, incidentId, meta, ringing, false, false, 0L, photo));
                }
            }
        });
    }

    /** BigPicture when a photo is available, otherwise BigText with the evidence count. */
    private static void applyEvidenceStyle(Context ctx, NotificationCompat.Builder b, JsonObject meta,
                                           String text, @Nullable Bitmap photo) {
        int count = (int) Json.lng(meta, "evidenceCount", 0L);
        String summary = count > 0
                ? ctx.getString(R.string.nb_n_evidence_text,
                ctx.getResources().getQuantityString(R.plurals.nb_evidence_count, count, count))
                : text;
        if (photo != null) {
            b.setLargeIcon(photo)
                    .setContentText(summary)
                    .setStyle(new NotificationCompat.BigPictureStyle()
                            .bigPicture(photo)
                            .bigLargeIcon((Bitmap) null)
                            .setSummaryText(summary));
        } else {
            b.setStyle(new NotificationCompat.BigTextStyle().bigText(count > 0 ? text + "\n" + summary : text));
        }
    }

    // ================================================================== meta store

    @Nullable
    private static JsonObject getMeta(Context ctx, String role, String incidentId) {
        synchronized (META_LOCK) {
            JsonObject all = Json.parseObject(Cloud.state(ctx).getString(KEY_META, null));
            if (all == null) return null;
            JsonElement e = all.get(role + ":" + incidentId);
            return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
        }
    }

    private static void putMeta(Context ctx, String role, String incidentId, JsonObject meta) {
        synchronized (META_LOCK) {
            SharedPreferences st = Cloud.state(ctx);
            JsonObject all = Json.parseObject(st.getString(KEY_META, null));
            if (all == null) all = new JsonObject();
            long now = System.currentTimeMillis();
            meta.addProperty("savedAt", now);
            all.add(role + ":" + incidentId, meta);
            List<String> expired = new ArrayList<>();
            for (Map.Entry<String, JsonElement> e : all.entrySet()) {
                JsonElement v = e.getValue();
                long saved = v != null && v.isJsonObject() ? Json.lng(v.getAsJsonObject(), "savedAt", 0L) : 0L;
                if (now - saved > META_TTL_MS) expired.add(e.getKey());
            }
            for (String k : expired) all.remove(k);
            st.edit().putString(KEY_META, all.toString()).apply();
        }
    }

    private static void forgetMeta(Context ctx, @Nullable String incidentId) {
        if (incidentId == null) return;
        synchronized (META_LOCK) {
            SharedPreferences st = Cloud.state(ctx);
            JsonObject all = Json.parseObject(st.getString(KEY_META, null));
            if (all == null) return;
            all.remove(ROLE_GUARDIAN + ":" + incidentId);
            all.remove(ROLE_HELPER + ":" + incidentId);
            st.edit().putString(KEY_META, all.toString()).apply();
        }
    }

    private static void copyIfPresent(@Nullable JsonObject from, JsonObject to, String key) {
        if (from != null && Json.has(from, key)) to.add(key, from.get(key));
    }

    // ================================================================== plain

    private static void plain(Context ctx, int nid, String title, String text, @Nullable String url) {
        PendingIntent pi = PendingIntent.getActivity(ctx, nid, trackingIntent(ctx, url), piFlags());
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CH_CLOUD)
                .setSmallIcon(R.drawable.cl_ic_shield)
                .setColor(ContextCompat.getColor(ctx, R.color.ns_rose))
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setContentIntent(pi)
                .setAutoCancel(true);
        notify(ctx, nid, b.build());
    }

    // ================================================================== helpers

    /** Opens the live-tracking page, or the app when there is no URL. */
    static Intent trackingIntent(Context ctx, @Nullable String url) {
        Intent i;
        if (!TextUtils.isEmpty(url)) {
            i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        } else {
            i = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
            if (i == null) i = new Intent();
        }
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    static String formatDistance(Context ctx, double meters) {
        if (meters < 1000) {
            int m = (int) (Math.round(meters / 10d) * 10);
            return ctx.getString(R.string.cl_distance_m, Math.max(m, 10));
        }
        return ctx.getString(R.string.cl_distance_km,
                String.format(Locale.getDefault(), "%.1f", meters / 1000d));
    }

    static int id(String kind, @Nullable String key) {
        return (kind + ":" + (key == null ? "" : key)).hashCode();
    }

    private static int piFlags() {
        int f = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) f |= PendingIntent.FLAG_IMMUTABLE;
        return f;
    }

    /** True when our notification {@code nid} is still in the shade. */
    private static boolean isActive(Context ctx, int nid) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return false;
            for (StatusBarNotification s : nm.getActiveNotifications()) {
                if (s.getId() == nid) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    static void cancel(Context ctx, int nid) {
        try {
            NotificationManagerCompat.from(ctx).cancel(nid);
        } catch (Throwable ignored) {
        }
    }

    private static void notify(Context ctx, int nid, Notification n) {
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(Cloud.TAG, "POST_NOTIFICATIONS not granted; alert not shown");
            return;
        }
        try {
            NotificationManagerCompat.from(ctx).notify(nid, n);
        } catch (SecurityException e) {
            Log.e(Cloud.TAG, "Notification refused", e);
        }
    }
}
