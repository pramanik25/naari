package com.example.naarishakti.daily;

import android.content.Context;
import android.util.Log;

import com.example.naarishakti.R;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Saved everyday routes ("Home to work, 45 min"). Starting one is a one-tap check-in timer: if
 * she has not tapped "I've reached" by then, the usual check-in SOS takes over.
 */
final class Commute {

    private static final String TAG = "Commute";
    private static final String K_ROUTES = "commute_routes";

    static final int MIN_MINUTES = 5;
    static final int MAX_MINUTES = 240;
    static final int MAX_ROUTES = 8;

    static final class Route {
        String id;
        String name;
        int minutes;

        Route(String name, int minutes) {
            this.id = UUID.randomUUID().toString();
            this.name = name;
            this.minutes = minutes;
        }
    }

    private Commute() {}

    /** Saved routes; two starter routes the first time. */
    static List<Route> routes(Context ctx) {
        String json = Daily.kv(ctx).getString(K_ROUTES, null);
        if (json != null) {
            try {
                Type type = new TypeToken<List<Route>>() {}.getType();
                List<Route> parsed = new Gson().fromJson(json, type);
                if (parsed != null) {
                    List<Route> out = new ArrayList<>();
                    for (Route r : parsed) {
                        if (r != null && r.id != null && r.name != null) out.add(r);
                    }
                    return out;
                }
            } catch (Exception e) {
                Log.w(TAG, "Saved routes unreadable", e);
            }
        }
        List<Route> starter = new ArrayList<>();
        starter.add(new Route(ctx.getString(R.string.dl_cm_default_to_work), 45));
        starter.add(new Route(ctx.getString(R.string.dl_cm_default_to_home), 45));
        save(ctx, starter);
        return starter;
    }

    static void save(Context ctx, List<Route> routes) {
        Daily.kv(ctx).edit().putString(K_ROUTES, new Gson().toJson(routes)).apply();
    }
}
