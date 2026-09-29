package com.example.naarishakti.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.tencent.mmkv.MMKV;

/**
 * Key-value storage backed by MMKV: memory-mapped, fast, and free of the size and ANR problems
 * of SharedPreferences XML files (large values like contact lists, geofence JSON and alert
 * metadata are written incrementally instead of rewriting the whole file). Each file is imported
 * from its old SharedPreferences XML once, on first access, so nothing is lost on upgrade.
 * Falls back to plain SharedPreferences if the MMKV native library cannot load.
 */
public final class Kv {

    private static final String TAG = "NsKv";
    private static final String MIGRATED_MARK = "__mmkv_migrated";

    private static volatile boolean ready;

    private Kv() {}

    /** Call once from Application.onCreate(), before anything reads settings. */
    public static void init(Context ctx) {
        try {
            MMKV.initialize(ctx.getApplicationContext());
            ready = true;
        } catch (Throwable t) {
            Log.e(TAG, "MMKV unavailable; falling back to SharedPreferences", t);
            ready = false;
        }
    }

    /** MMKV-backed store for {@code file} (implements SharedPreferences), or the plain XML file. */
    public static SharedPreferences get(Context ctx, String file) {
        Context app = ctx.getApplicationContext();
        if (ready) {
            try {
                MMKV kv = MMKV.mmkvWithID(file);
                if (!kv.getBoolean(MIGRATED_MARK, false)) {
                    SharedPreferences old = app.getSharedPreferences(file, Context.MODE_PRIVATE);
                    if (!old.getAll().isEmpty()) kv.importFromSharedPreferences(old);
                    kv.putBoolean(MIGRATED_MARK, true);
                }
                return kv;
            } catch (Throwable t) {
                Log.e(TAG, "MMKV open failed for " + file, t);
            }
        }
        return app.getSharedPreferences(file, Context.MODE_PRIVATE);
    }
}
