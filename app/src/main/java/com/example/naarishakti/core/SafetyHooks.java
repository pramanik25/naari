package com.example.naarishakti.core;

import android.content.Context;
import android.location.Location;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.naarishakti.BuildConfig;

import java.io.File;
import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Event bus between the SOS engine (VoiceRecognitionService) and the feature modules
 * (cloud sync, evidence, extra triggers, mesh...). The engine only calls the dispatch methods;
 * modules register once in {@link com.example.naarishakti.NaariApp}. A failing module is
 * logged and skipped so it can never break the SOS itself.
 *
 * All callbacks run on the main thread; do heavy work on your own thread.
 */
public final class SafetyHooks {

    private static final String TAG = "SafetyHooks";

    /** Implement the callbacks you need; all are optional. */
    public interface Module {
        /** Protection turned on (engine service created). Start background detectors here. */
        default void onProtectionStarted(@NonNull Context ctx) {}

        /** Protection turned off (engine service destroyed). Release everything. */
        default void onProtectionStopped(@NonNull Context ctx) {}

        /** SOS actually fired (after the countdown, or immediately). */
        default void onSosStarted(@NonNull Context ctx, @NonNull Incident incident) {}

        /** Every fresh location fix during an SOS. */
        default void onSosLocation(@NonNull Context ctx, @NonNull Incident incident, @NonNull Location location) {}

        /** A still photo was saved during the SOS (full-size JPEG on disk). */
        default void onSosPhoto(@NonNull Context ctx, @NonNull Incident incident, @NonNull File jpeg, boolean front) {}

        /** The duress PIN was entered: the SOS now looks stopped on the phone but continues covertly. */
        default void onSosDuress(@NonNull Context ctx, @NonNull Incident incident) {}

        /** SOS ended. {@code userInitiated} is false when the service was killed/stopped. */
        default void onSosStopped(@NonNull Context ctx, @NonNull Incident incident, boolean userInitiated) {}
    }

    /** One fired SOS. Immutable except for {@link #duress} and {@link #lastLocation}. */
    public static final class Incident {
        /** Stable id, also used as the Firestore document id. */
        public final String id;
        /** Unguessable token for the public live-tracking link. */
        public final String token;
        public final long startedAt;
        /** "voice", "power", "shake", "sos_button", "scream", "fall", "ble_button", "headset", "checkin", "duress"... */
        public final String source;
        public final boolean silent;
        public volatile boolean duress;
        @Nullable public volatile Location lastLocation;

        Incident(String source, boolean silent) {
            this.id = UUID.randomUUID().toString();
            this.token = newToken();
            this.startedAt = System.currentTimeMillis();
            this.source = source == null ? "unknown" : source;
            this.silent = silent;
        }

        /** Public live-tracking URL for contacts, or null when no tracking site is configured. */
        @Nullable
        public String trackingUrl() {
            String base = BuildConfig.TRACKING_BASE_URL;
            if (TextUtils.isEmpty(base)) return null;
            if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            return base + "/t/" + token;
        }

        private static String newToken() {
            byte[] b = new byte[16];
            new SecureRandom().nextBytes(b);
            return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        }
    }

    private static final List<Module> MODULES = new CopyOnWriteArrayList<>();
    @Nullable private static volatile Incident current;

    private SafetyHooks() {}

    public static void register(Module module) {
        if (!MODULES.contains(module)) MODULES.add(module);
    }

    /** The running SOS, or null. */
    @Nullable
    public static Incident current() {
        return current;
    }

    // ---- Dispatch (called by the engine only) ----

    public static void protectionStarted(Context ctx) {
        for (Module m : MODULES) {
            try { m.onProtectionStarted(ctx); } catch (Throwable t) { log(m, t); }
        }
    }

    public static void protectionStopped(Context ctx) {
        for (Module m : MODULES) {
            try { m.onProtectionStopped(ctx); } catch (Throwable t) { log(m, t); }
        }
    }

    /** Creates the incident and notifies modules. */
    public static Incident sosStarted(Context ctx, String source, boolean silent) {
        Incident incident = new Incident(source, silent);
        current = incident;
        for (Module m : MODULES) {
            try { m.onSosStarted(ctx, incident); } catch (Throwable t) { log(m, t); }
        }
        return incident;
    }

    public static void sosLocation(Context ctx, Location location) {
        Incident incident = current;
        if (incident == null) return;
        incident.lastLocation = location;
        for (Module m : MODULES) {
            try { m.onSosLocation(ctx, incident, location); } catch (Throwable t) { log(m, t); }
        }
    }

    public static void sosPhoto(Context ctx, File jpeg, boolean front) {
        Incident incident = current;
        if (incident == null) return;
        for (Module m : MODULES) {
            try { m.onSosPhoto(ctx, incident, jpeg, front); } catch (Throwable t) { log(m, t); }
        }
    }

    public static void sosDuress(Context ctx) {
        Incident incident = current;
        if (incident == null || incident.duress) return;
        incident.duress = true;
        for (Module m : MODULES) {
            try { m.onSosDuress(ctx, incident); } catch (Throwable t) { log(m, t); }
        }
    }

    public static void sosStopped(Context ctx, boolean userInitiated) {
        Incident incident = current;
        current = null;
        if (incident == null) return;
        for (Module m : MODULES) {
            try { m.onSosStopped(ctx, incident, userInitiated); } catch (Throwable t) { log(m, t); }
        }
    }

    private static void log(Module m, Throwable t) {
        Log.e(TAG, "Module " + m.getClass().getSimpleName() + " failed", t);
    }
}
