package com.example.naarishakti.cloud;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;

import com.google.gson.JsonObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Persisted, strictly ordered queue of API writes (incident mirror, check-ins, helper opt-out).
 *
 * <p>{@link #add} stores the request in SQLite on the calling thread (a ~1 ms insert, so it
 * survives process death immediately and keeps call order), then a single in-process drainer
 * sends the queue head-first. A transient failure (offline, 5xx) stops the drain and hands over to
 * a network-constrained WorkManager job with exponential backoff; a permanent failure (4xx) drops
 * that one request so it can never block the queue.
 */
final class CloudOutbox {

    static final String WORK_NAME = "ns_cloud_outbox";

    static final int DONE = 0;
    static final int TRANSIENT = 1;

    /** Requests older than this are no longer useful to anyone. */
    private static final long MAX_AGE_MS = TimeUnit.DAYS.toMillis(3);

    private static final Object DRAIN_LOCK = new Object();
    private static final ExecutorService DRAINER = Executors.newSingleThreadExecutor(Cloud.threadFactory("ns-outbox-"));
    private static final AtomicBoolean KICK_QUEUED = new AtomicBoolean();
    private static volatile Db db;

    private CloudOutbox() {}

    /**
     * Queue one request.
     *
     * @param replaceKey when non-null, older queued requests with the same key are dropped first
     *                   (latest-wins updates like "check-in location").
     */
    static void add(Context ctx, String method, String path, @Nullable JsonObject body,
                    @Nullable String replaceKey) {
        if (!Cloud.isActive(ctx)) return;
        Context app = ctx.getApplicationContext();
        try {
            SQLiteDatabase w = db(app).getWritableDatabase();
            if (replaceKey != null) w.delete(Db.T, "rkey = ?", new String[]{replaceKey});
            ContentValues v = new ContentValues();
            v.put("method", method);
            v.put("path", path);
            v.put("body", body == null ? null : body.toString());
            v.put("rkey", replaceKey);
            v.put("created", System.currentTimeMillis());
            v.put("attempts", 0);
            w.insert(Db.T, null, v);
        } catch (Throwable t) {
            Log.e(Cloud.TAG, "Outbox insert failed for " + method + " " + path, t);
            return;
        }
        kick(app);
    }

    /** Drain in-process now (coalesced); falls back to WorkManager when the network fails. */
    static void kick(Context ctx) {
        if (!Cloud.isActive(ctx)) return;
        final Context app = ctx.getApplicationContext();
        if (!KICK_QUEUED.compareAndSet(false, true)) return;
        DRAINER.execute(new Runnable() {
            @Override
            public void run() {
                KICK_QUEUED.set(false);
                if (drain(app) == TRANSIENT) scheduleWorker(app);
            }
        });
    }

    /** Remove everything queued (used when the user deletes her cloud data). */
    static void clear(Context ctx) {
        try {
            db(ctx.getApplicationContext()).getWritableDatabase().delete(Db.T, null, null);
        } catch (Throwable t) {
            Log.w(Cloud.TAG, "Outbox clear failed", t);
        }
    }

    /** Drop queued requests added with {@code replaceKey}. */
    static void removeKey(Context ctx, String replaceKey) {
        try {
            db(ctx.getApplicationContext()).getWritableDatabase().delete(Db.T, "rkey = ?", new String[]{replaceKey});
        } catch (Throwable t) {
            Log.w(Cloud.TAG, "Outbox removeKey failed", t);
        }
    }

    static int size(Context ctx) {
        try {
            Cursor c = db(ctx.getApplicationContext()).getReadableDatabase()
                    .rawQuery("SELECT COUNT(*) FROM " + Db.T, null);
            try {
                return c.moveToFirst() ? c.getInt(0) : 0;
            } finally {
                c.close();
            }
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Send queued requests in order until empty or a transient failure. Blocking. */
    static int drain(Context ctx) {
        Context app = ctx.getApplicationContext();
        synchronized (DRAIN_LOCK) {
            if (!Cloud.isActive(app)) return DONE;
            ApiClient api = ApiClient.get(app);
            while (true) {
                Op op;
                try {
                    op = peek(app);
                } catch (Throwable t) {
                    Log.e(Cloud.TAG, "Outbox read failed", t);
                    return DONE;
                }
                if (op == null) return DONE;
                if (System.currentTimeMillis() - op.created > MAX_AGE_MS) {
                    Log.w(Cloud.TAG, "Outbox: dropping stale " + op.method + " " + op.path);
                    delete(app, op.id);
                    continue;
                }
                try {
                    api.call(op.method, op.path, Json.parseObject(op.body));
                    delete(app, op.id);
                } catch (CloudException e) {
                    if (e instanceof CloudException.Disabled) return DONE;
                    if (e.isTransient()) {
                        bumpAttempts(app, op.id);
                        Log.i(Cloud.TAG, "Outbox paused at " + op.method + " " + op.path + ": " + e.getMessage());
                        return TRANSIENT;
                    }
                    Log.w(Cloud.TAG, "Outbox: server refused " + op.method + " " + op.path
                            + " (" + e.status + " " + e.code + "), dropping");
                    delete(app, op.id);
                } catch (RuntimeException e) {
                    Log.e(Cloud.TAG, "Outbox: unexpected failure, dropping " + op.path, e);
                    delete(app, op.id);
                }
            }
        }
    }

    static void scheduleWorker(Context ctx) {
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(OutboxWorker.class)
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .addTag(WORK_NAME)
                .build();
        WorkUtil.enqueueDrain(ctx, WORK_NAME, req, false);
    }

    // ------------------------------------------------------------------ storage

    private static final class Op {
        long id;
        String method;
        String path;
        String body;
        long created;
    }

    @Nullable
    private static Op peek(Context app) {
        Cursor c = db(app).getReadableDatabase().query(Db.T,
                new String[]{"id", "method", "path", "body", "created"},
                null, null, null, null, "id ASC", "1");
        try {
            if (!c.moveToFirst()) return null;
            Op op = new Op();
            op.id = c.getLong(0);
            op.method = c.getString(1);
            op.path = c.getString(2);
            op.body = c.isNull(3) ? null : c.getString(3);
            op.created = c.getLong(4);
            return op;
        } finally {
            c.close();
        }
    }

    private static void delete(Context app, long id) {
        try {
            db(app).getWritableDatabase().delete(Db.T, "id = ?", new String[]{String.valueOf(id)});
        } catch (Throwable t) {
            Log.e(Cloud.TAG, "Outbox delete failed", t);
        }
    }

    private static void bumpAttempts(Context app, long id) {
        try {
            db(app).getWritableDatabase().execSQL(
                    "UPDATE " + Db.T + " SET attempts = attempts + 1 WHERE id = ?", new Object[]{id});
        } catch (Throwable ignored) {
        }
    }

    private static Db db(Context app) {
        Db d = db;
        if (d == null) {
            synchronized (CloudOutbox.class) {
                d = db;
                if (d == null) {
                    d = new Db(app);
                    db = d;
                }
            }
        }
        return d;
    }

    private static final class Db extends SQLiteOpenHelper {
        static final String T = "ops";

        Db(Context ctx) {
            super(ctx, "ns_cloud_outbox.db", null, 1);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + T + " ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "method TEXT NOT NULL, "
                    + "path TEXT NOT NULL, "
                    + "body TEXT, "
                    + "rkey TEXT, "
                    + "created INTEGER NOT NULL, "
                    + "attempts INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("CREATE INDEX idx_ops_rkey ON " + T + " (rkey)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            db.execSQL("DROP TABLE IF EXISTS " + T);
            onCreate(db);
        }
    }
}
