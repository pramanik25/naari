package com.example.naarishakti.triggers;

import android.annotation.SuppressLint;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.SystemClock;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.example.naarishakti.R;
import com.example.naarishakti.VoiceRecognitionService;
import com.example.naarishakti.core.Prefs;
import com.example.naarishakti.core.ProtectionController;
import com.example.naarishakti.core.SafetyHooks;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import SQLite_Database.ProfileDbHelper;

/**
 * Last-known-location SMS alerts for device events while protection is wanted: shutdown, battery
 * at 5% and SIM change. Each is opt-in (Prefs keys, default on) and sent once per event.
 * Everything here is synchronous and fast: a shutdown gives no time to wait for a location fix.
 */
@SuppressLint("MissingPermission") // permissions are checked (or SecurityException caught) at runtime
final class DeviceGuard {

    private static final String TAG = "DeviceGuard";

    /** Internal state only (not settings), kept apart from the shared AppPrefs file. */
    private static final String STATE_FILE = "ns_triggers_state";
    private static final String KEY_LOW_BATTERY_SENT = "low_battery_sent";

    static final int LOW_BATTERY_PERCENT = 5;
    /** Battery must climb above this (or be charging) before another low-battery alert. */
    private static final int LOW_BATTERY_REARM_PERCENT = 10;
    /** The manifest and the dynamic receiver can both see one shutdown; alert once. */
    private static final long SHUTDOWN_DEDUPE_MS = 60_000L;

    private static final Object LOCK = new Object();
    private static long lastShutdownAlertAt;

    private DeviceGuard() {}

    // ------------------------------------------------------------------ shutdown

    static void onShutdown(Context ctx) {
        if (!ProtectionController.isProtectionWanted(ctx)) return;
        if (!Prefs.get(ctx).getBoolean(Prefs.SHUTDOWN_ALERT, true)) return;
        synchronized (LOCK) {
            long now = SystemClock.elapsedRealtime();
            if (lastShutdownAlertAt != 0 && now - lastShutdownAlertAt < SHUTDOWN_DEDUPE_MS) return;
            lastShutdownAlertAt = now;
        }
        String text = ctx.getString(R.string.tr_sms_shutdown, ownerName(ctx), locationText(ctx));
        int sent = sendToContacts(ctx, text);
        Log.i(TAG, "Shutdown alert sent to " + sent + " contact(s)");
    }

    // ------------------------------------------------------------------ battery

    /** Handles a sticky or live ACTION_BATTERY_CHANGED intent. */
    static void onBatteryChanged(Context ctx, @Nullable Intent battery) {
        if (battery == null) return;
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (level < 0 || scale <= 0) return;
        int percent = Math.round(level * 100f / scale);
        int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
        boolean charging = plugged != 0
                || status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;

        SharedPreferences state = state(ctx);
        boolean sent = state.getBoolean(KEY_LOW_BATTERY_SENT, false);
        if (charging || percent > LOW_BATTERY_REARM_PERCENT) {
            if (sent) state.edit().putBoolean(KEY_LOW_BATTERY_SENT, false).apply();
            return;
        }
        if (sent || percent > LOW_BATTERY_PERCENT) return;
        if (!ProtectionController.isProtectionWanted(ctx)) return;
        if (!Prefs.get(ctx).getBoolean(Prefs.LOW_BATTERY_ALERT, true)) return;

        // Mark first so a burst of battery intents can never send twice.
        state.edit().putBoolean(KEY_LOW_BATTERY_SENT, true).apply();
        String text = ctx.getString(R.string.tr_sms_low_battery, ownerName(ctx), percent, locationText(ctx));
        int n = sendToContacts(ctx, text);
        Log.i(TAG, "Low battery alert (" + percent + "%) sent to " + n + " contact(s)");
    }

    // ------------------------------------------------------------------ SIM

    /**
     * Compares the current SIM identity with {@link Prefs#SIM_SNAPSHOT}. On the first run the
     * snapshot is just stored. If it changed while protection is wanted (and SIM alerts are on) the
     * contacts get an SMS from the new SIM. The snapshot is updated afterwards in every case.
     * No SIM / not yet loaded / no READ_PHONE_STATE: does nothing.
     */
    static void checkSim(Context ctx) {
        String current = simSnapshot(ctx);
        if (current == null) return;
        SharedPreferences prefs = Prefs.get(ctx);
        String previous;
        synchronized (LOCK) {
            previous = prefs.getString(Prefs.SIM_SNAPSHOT, "");
            if (current.equals(previous)) return;
            prefs.edit().putString(Prefs.SIM_SNAPSHOT, current).commit();
        }
        if (TextUtils.isEmpty(previous)) {
            Log.i(TAG, "SIM snapshot stored");
            return;
        }
        Log.i(TAG, "SIM change detected");
        if (!ProtectionController.isProtectionWanted(ctx)) return;
        if (!prefs.getBoolean(Prefs.SIM_ALERT, true)) return;
        String text = ctx.getString(R.string.tr_sms_sim_changed, ownerName(ctx), locationText(ctx));
        int n = sendToContacts(ctx, text);
        Log.i(TAG, "SIM change alert sent to " + n + " contact(s)");
    }

    /** SHA-256 over sorted "subscriptionId:carrierName" entries, or null when unavailable. */
    @Nullable
    private static String simSnapshot(Context ctx) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        SubscriptionManager sm = (SubscriptionManager) ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        if (sm == null) return null;
        List<SubscriptionInfo> subs;
        try {
            subs = sm.getActiveSubscriptionInfoList();
        } catch (SecurityException e) {
            return null;
        }
        if (subs == null || subs.isEmpty()) return null; // no SIM or not loaded yet: keep the old snapshot
        List<SubscriptionInfo> sorted = new ArrayList<>(subs);
        Collections.sort(sorted, new Comparator<SubscriptionInfo>() {
            @Override
            public int compare(SubscriptionInfo a, SubscriptionInfo b) {
                return Integer.compare(a.getSubscriptionId(), b.getSubscriptionId());
            }
        });
        List<String> parts = new ArrayList<>();
        for (SubscriptionInfo info : sorted) {
            CharSequence carrier = info.getCarrierName();
            parts.add(info.getSubscriptionId() + ":" + (carrier == null ? "" : carrier.toString()));
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(TextUtils.join("|", parts).getBytes(Charset.forName("UTF-8")));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(b & 0xFF);
                if (hex.length() == 1) sb.append('0');
                sb.append(hex);
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ shared

    private static int sendToContacts(Context ctx, String text) {
        List<String> numbers = Prefs.getContactNumbers(ctx);
        if (numbers.isEmpty()) {
            Log.w(TAG, "No emergency contacts; alert not sent");
            return 0;
        }
        return VoiceRecognitionService.sendSmsToAll(ctx, numbers, text);
    }

    static String ownerName(Context ctx) {
        String name = null;
        ProfileDbHelper db = null;
        try {
            db = new ProfileDbHelper(ctx);
            name = db.getProfileName();
        } catch (Exception e) {
            Log.w(TAG, "Profile name unavailable: " + e.getMessage());
        } finally {
            if (db != null) db.close();
        }
        return TextUtils.isEmpty(name) ? ctx.getString(R.string.tr_default_owner_name) : name;
    }

    /** "maps link (time)" from the newest location already known to the phone, never waits. */
    static String locationText(Context ctx) {
        Location loc = lastKnownLocation(ctx);
        if (loc == null) return ctx.getString(R.string.tr_sms_location_unknown);
        long t = loc.getTime() > 0 ? loc.getTime() : System.currentTimeMillis();
        int flags = DateUtils.FORMAT_SHOW_TIME;
        if (!DateUtils.isToday(t)) flags |= DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH;
        return ctx.getString(R.string.tr_sms_location_fmt,
                VoiceRecognitionService.mapsLink(loc), DateUtils.formatDateTime(ctx, t, flags));
    }

    @Nullable
    static Location lastKnownLocation(Context ctx) {
        Location best = null;
        SafetyHooks.Incident incident = SafetyHooks.current();
        if (incident != null) best = incident.lastLocation;
        if (!VoiceRecognitionService.hasLocationPermission(ctx)) return best;
        LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return best;
        String[] providers = {LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER};
        for (String p : providers) {
            try {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (SecurityException | IllegalArgumentException ignored) {
                // Provider missing or permission revoked mid-way.
            }
        }
        return best;
    }

    private static SharedPreferences state(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(STATE_FILE, Context.MODE_PRIVATE);
    }
}
