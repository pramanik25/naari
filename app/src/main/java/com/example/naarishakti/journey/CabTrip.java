package com.example.naarishakti.journey;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.example.naarishakti.R;
import com.example.naarishakti.core.Prefs;

/** Cab mode trip state, persisted under jr_cab_* keys so the service can recover it. */
final class CabTrip {

    static final String K_ACTIVE = "jr_cab_active";
    static final String K_PLATE = "jr_cab_plate";
    static final String K_APP = "jr_cab_app";
    static final String K_DRIVER = "jr_cab_driver";
    static final String K_HAS_DEST = "jr_cab_has_dest";
    static final String K_DEST_LAT = "jr_cab_dest_lat";
    static final String K_DEST_LNG = "jr_cab_dest_lng";
    static final String K_DEST_NAME = "jr_cab_dest_name";
    static final String K_STARTED = "jr_cab_started";
    /** Last measured distance to the destination in metres, -1 when unknown. */
    static final String K_DIST_LEFT = "jr_cab_dist_left";
    /** True once within the arrival radius. */
    static final String K_NEAR = "jr_cab_near";
    /** When an "Is everything OK?" prompt is pending (epoch ms), 0 otherwise. */
    static final String K_PROMPT_AT = "jr_cab_prompt_at";

    static final float ARRIVAL_RADIUS_M = 150f;

    private CabTrip() {}

    private static SharedPreferences p(Context ctx) {
        return Prefs.get(ctx);
    }

    static boolean isActive(Context ctx) {
        return p(ctx).getBoolean(K_ACTIVE, false);
    }

    static void start(Context ctx, String plate, String app, String driver,
                      @Nullable double[] dest, String destName) {
        SharedPreferences.Editor e = p(ctx).edit()
                .putBoolean(K_ACTIVE, true)
                .putString(K_PLATE, plate)
                .putString(K_APP, app == null ? "" : app)
                .putString(K_DRIVER, driver == null ? "" : driver)
                .putString(K_DEST_NAME, destName == null ? "" : destName)
                .putLong(K_STARTED, System.currentTimeMillis())
                .putFloat(K_DIST_LEFT, -1f)
                .putBoolean(K_NEAR, false)
                .putLong(K_PROMPT_AT, 0L)
                .putBoolean(K_HAS_DEST, dest != null);
        if (dest != null) {
            e.putLong(K_DEST_LAT, Double.doubleToRawLongBits(dest[0]))
                    .putLong(K_DEST_LNG, Double.doubleToRawLongBits(dest[1]));
        }
        e.apply();
        CheckIn.changed(ctx.getApplicationContext());
    }

    static void clear(Context ctx) {
        p(ctx).edit()
                .remove(K_ACTIVE).remove(K_PLATE).remove(K_APP).remove(K_DRIVER)
                .remove(K_HAS_DEST).remove(K_DEST_LAT).remove(K_DEST_LNG).remove(K_DEST_NAME)
                .remove(K_STARTED).remove(K_DIST_LEFT).remove(K_NEAR).remove(K_PROMPT_AT)
                .apply();
        CheckIn.changed(ctx.getApplicationContext());
    }

    @Nullable
    static double[] dest(Context ctx) {
        SharedPreferences prefs = p(ctx);
        if (!prefs.getBoolean(K_HAS_DEST, false)) return null;
        return new double[]{
                Double.longBitsToDouble(prefs.getLong(K_DEST_LAT, 0L)),
                Double.longBitsToDouble(prefs.getLong(K_DEST_LNG, 0L))};
    }

    static String plate(Context ctx) {
        return p(ctx).getString(K_PLATE, "");
    }

    static String app(Context ctx) {
        return p(ctx).getString(K_APP, "");
    }

    static String driver(Context ctx) {
        return p(ctx).getString(K_DRIVER, "");
    }

    static String destName(Context ctx) {
        return p(ctx).getString(K_DEST_NAME, "");
    }

    static long startedAt(Context ctx) {
        return p(ctx).getLong(K_STARTED, 0L);
    }

    static float distLeft(Context ctx) {
        return p(ctx).getFloat(K_DIST_LEFT, -1f);
    }

    static boolean isNear(Context ctx) {
        return p(ctx).getBoolean(K_NEAR, false);
    }

    static long promptAt(Context ctx) {
        return p(ctx).getLong(K_PROMPT_AT, 0L);
    }

    static void saveProgress(Context ctx, float distLeft, boolean near) {
        p(ctx).edit().putFloat(K_DIST_LEFT, distLeft).putBoolean(K_NEAR, near).apply();
        CheckIn.changed(ctx.getApplicationContext());
    }

    static void setPromptAt(Context ctx, long at) {
        p(ctx).edit().putLong(K_PROMPT_AT, at).apply();
        CheckIn.changed(ctx.getApplicationContext());
    }

    /** Destination label for messages: the typed/geocoded name or a generic word. */
    static String destLabel(Context ctx) {
        String name = destName(ctx);
        return TextUtils.isEmpty(name) ? ctx.getString(R.string.jr_cab_dest_generic) : name;
    }
}
