package com.example.naarishakti.together;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.View;

import androidx.annotation.ColorRes;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;

import com.example.naarishakti.R;
import com.example.naarishakti.cloud.CloudException;
import com.example.naarishakti.cloud.CloudSocial;
import com.example.naarishakti.core.Kv;

/** Small shared toolkit for the together package (family circle, safety map, community). */
final class Tg {

    private static final String TAG = "Together";
    private static final String FILE = "ns_together";

    /** Centre of India, shown until a real position is known. */
    static final double DEFAULT_LAT = 20.5937;
    static final double DEFAULT_LNG = 78.9629;

    private Tg() {}

    static SharedPreferences kv(Context ctx) {
        return Kv.get(ctx, FILE);
    }

    static void show(View v, boolean visible) {
        v.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    static int dp(Context ctx, int value) {
        return Math.round(value * ctx.getResources().getDisplayMetrics().density);
    }

    static void open(Context ctx, Intent intent) {
        try {
            ctx.startActivity(intent);
            if (ctx instanceof Activity) {
                ((Activity) ctx).overridePendingTransition(R.anim.slide_in, R.anim.slide_out);
            }
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "Cannot start " + intent, e);
        }
    }

    /** "5 minutes ago", "Yesterday". */
    static CharSequence ago(long ms) {
        long now = System.currentTimeMillis();
        return DateUtils.getRelativeTimeSpanString(Math.min(ms, now), now, DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE);
    }

    /** What to tell her when an API call failed. */
    @StringRes
    static int errorText(Throwable t) {
        if (t instanceof CloudException) {
            CloudException e = (CloudException) t;
            if ("daily_limit".equals(e.code)) return R.string.tg_error_daily_limit;
            if ("contact_info".equals(e.code)) return R.string.tg_error_contact_info;
            if (e instanceof CloudException.Disabled) return R.string.tg_cloud_off;
            if (e instanceof CloudException.Network) return R.string.tg_error_offline;
        }
        return R.string.tg_error_generic;
    }

    // ------------------------------------------------------------------ safety map categories

    static final String[] CATEGORIES = {CloudSocial.CAT_POORLY_LIT, CloudSocial.CAT_ISOLATED,
            CloudSocial.CAT_HARASSMENT, CloudSocial.CAT_TRANSPORT, CloudSocial.CAT_SAFE_SPOT};

    @StringRes
    static int categoryLabel(String category) {
        if (CloudSocial.CAT_POORLY_LIT.equals(category)) return R.string.tg_cat_poorly_lit;
        if (CloudSocial.CAT_ISOLATED.equals(category)) return R.string.tg_cat_isolated;
        if (CloudSocial.CAT_HARASSMENT.equals(category)) return R.string.tg_cat_harassment;
        if (CloudSocial.CAT_TRANSPORT.equals(category)) return R.string.tg_cat_transport;
        return R.string.tg_cat_safe_spot;
    }

    @ColorRes
    static int categoryColor(String category) {
        if (CloudSocial.CAT_POORLY_LIT.equals(category)) return R.color.ns_warn;
        if (CloudSocial.CAT_ISOLATED.equals(category)) return R.color.ns_violet;
        if (CloudSocial.CAT_HARASSMENT.equals(category)) return R.color.ns_danger;
        if (CloudSocial.CAT_TRANSPORT.equals(category)) return R.color.ns_gold;
        return R.color.ns_safe;
    }

    /** A filled dot with a white rim, used as a map marker. */
    static Drawable dot(Context ctx, @ColorRes int color, int sizeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(ContextCompat.getColor(ctx, color));
        d.setStroke(dp(ctx, 2), ContextCompat.getColor(ctx, R.color.white));
        d.setSize(dp(ctx, sizeDp), dp(ctx, sizeDp));
        return d;
    }

    // ------------------------------------------------------------------ community topics

    static final String[] TOPICS = {"advice", "experience", "legal", "health", "support"};

    @StringRes
    static int topicLabel(String topic) {
        if ("experience".equals(topic)) return R.string.tg_topic_experience;
        if ("legal".equals(topic)) return R.string.tg_topic_legal;
        if ("health".equals(topic)) return R.string.tg_topic_health;
        if ("support".equals(topic)) return R.string.tg_topic_support;
        return R.string.tg_topic_advice;
    }
}
