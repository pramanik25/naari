package com.example.naarishakti.evidence;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Local, tamper-evident record of every evidence file captured during an SOS, plus an index of
 * incidents (source, times, silent/duress, last location). Backed by its own "evidence.db".
 *
 * Every method does disk I/O; call it off the main thread.
 */
public final class EvidenceStore {

    private static final String TAG = "EvidenceStore";

    public static final String KIND_PHOTO = "photo";
    public static final String KIND_AUDIO = "audio";
    public static final String KIND_VIDEO = "video";

    /** Folder under getExternalFilesDir() holding one sub-folder per incident. */
    public static final String DIR_NAME = "Evidence";

    public static final class Item {
        public long id;
        public String incidentId;
        public String evidenceId;
        /** photo | audio | video */
        public String kind;
        public java.io.File file;
        public String contentType;
        public long size;
        public String sha256;
        public long capturedAt;
        public boolean uploaded;
        public String serverSha256;
        public boolean verified;
    }

    /** One row of the incident index. Times are epoch ms; 0 = unknown. */
    public static final class IncidentInfo {
        public String incidentId;
        @Nullable public String source;
        public long startedAt;
        public long endedAt;
        public boolean silent;
        public boolean duress;
        /** True when the incident is only known from its evidence rows (no index row). */
        public boolean inferred;
        public boolean hasLocation;
        public double lastLat;
        public double lastLng;
        public float lastAccuracy;
        public long lastLocationAt;
    }

    private EvidenceStore() {}

    // =====================================================================================
    // Evidence API (called by other modules)
    // =====================================================================================

    /**
     * Hashes the file (SHA-256, lowercase hex), stores a row with a new UUID evidenceId, then
     * calls {@code CloudUploads.enqueue(ctx)}. Call off the main thread.
     *
     * @return the stored item, or null when the file is missing/empty or the database failed.
     */
    @Nullable
    public static Item add(Context ctx, String incidentId, java.io.File file, String kind,
                           String contentType, long capturedAt) {
        if (ctx == null || file == null || TextUtils.isEmpty(incidentId)) return null;
        if (!file.isFile() || file.length() == 0) {
            Log.w(TAG, "Not adding missing/empty evidence file " + file);
            return null;
        }
        String sha;
        try {
            sha = sha256(file);
        } catch (Exception e) {
            Log.e(TAG, "Hashing failed for " + file, e);
            return null;
        }
        Item item = new Item();
        item.incidentId = incidentId;
        item.evidenceId = UUID.randomUUID().toString();
        item.kind = kind == null ? KIND_PHOTO : kind;
        item.file = file;
        item.contentType = contentType == null ? "application/octet-stream" : contentType;
        item.size = file.length();
        item.sha256 = sha;
        item.capturedAt = capturedAt > 0 ? capturedAt : file.lastModified();
        item.uploaded = false;
        item.serverSha256 = null;
        item.verified = false;
        try {
            ContentValues v = new ContentValues();
            v.put(Db.C_INCIDENT, item.incidentId);
            v.put(Db.C_EVIDENCE_ID, item.evidenceId);
            v.put(Db.C_KIND, item.kind);
            v.put(Db.C_PATH, file.getAbsolutePath());
            v.put(Db.C_CONTENT_TYPE, item.contentType);
            v.put(Db.C_SIZE, item.size);
            v.put(Db.C_SHA, item.sha256);
            v.put(Db.C_CAPTURED_AT, item.capturedAt);
            v.put(Db.C_UPLOADED, 0);
            v.put(Db.C_VERIFIED, 0);
            item.id = db(ctx).getWritableDatabase().insertOrThrow(Db.T_EVIDENCE, null, v);
        } catch (Exception e) {
            Log.e(TAG, "Insert failed for " + file, e);
            return null;
        }
        try {
            com.example.naarishakti.cloud.CloudUploads.enqueue(ctx);
        } catch (Throwable t) {
            // The upload queue is best-effort; the local copy and its hash are already safe.
            Log.w(TAG, "CloudUploads.enqueue failed", t);
        }
        return item;
    }

    /** Items not uploaded yet, oldest first. */
    public static List<Item> pending(Context ctx, int limit) {
        return query(ctx, Db.C_UPLOADED + "=0", null,
                Db.C_CAPTURED_AT + " ASC, " + Db.C_ID + " ASC",
                limit > 0 ? String.valueOf(limit) : null);
    }

    public static void markUploaded(Context ctx, long id, String serverSha256, boolean verified) {
        try {
            ContentValues v = new ContentValues();
            v.put(Db.C_UPLOADED, 1);
            v.put(Db.C_SERVER_SHA, serverSha256);
            v.put(Db.C_VERIFIED, verified ? 1 : 0);
            db(ctx).getWritableDatabase().update(Db.T_EVIDENCE, v, Db.C_ID + "=?",
                    new String[]{String.valueOf(id)});
        } catch (Exception e) {
            Log.e(TAG, "markUploaded failed for " + id, e);
        }
    }

    /** Items of one incident, oldest first. */
    public static List<Item> forIncident(Context ctx, String incidentId) {
        if (TextUtils.isEmpty(incidentId)) return new ArrayList<>();
        return query(ctx, Db.C_INCIDENT + "=?", new String[]{incidentId},
                Db.C_CAPTURED_AT + " ASC, " + Db.C_ID + " ASC", null);
    }

    /** Every incident known from the index or from evidence rows, newest first. */
    public static List<String> incidentIds(Context ctx) {
        List<String> out = new ArrayList<>();
        String sql = "SELECT id FROM ("
                + " SELECT " + Db.C_INCIDENT + " AS id, " + Db.I_STARTED + " AS t FROM " + Db.T_INCIDENTS
                + " UNION ALL"
                + " SELECT " + Db.C_INCIDENT + " AS id, MIN(" + Db.C_CAPTURED_AT + ") AS t FROM " + Db.T_EVIDENCE
                + " WHERE " + Db.C_INCIDENT + " NOT IN (SELECT " + Db.C_INCIDENT + " FROM " + Db.T_INCIDENTS + ")"
                + " GROUP BY " + Db.C_INCIDENT
                + ") ORDER BY t DESC";
        try (Cursor c = db(ctx).getReadableDatabase().rawQuery(sql, null)) {
            while (c.moveToNext()) {
                if (!c.isNull(0)) out.add(c.getString(0));
            }
        } catch (Exception e) {
            Log.e(TAG, "incidentIds failed", e);
        }
        return out;
    }

    /** Deletes the incident's rows, its index entry and its files. */
    public static void deleteIncident(Context ctx, String incidentId) {
        if (TextUtils.isEmpty(incidentId)) return;
        for (Item item : forIncident(ctx, incidentId)) {
            if (item.file != null && item.file.exists() && !item.file.delete()) {
                Log.w(TAG, "Could not delete " + item.file);
            }
        }
        try {
            SQLiteDatabase w = db(ctx).getWritableDatabase();
            w.delete(Db.T_EVIDENCE, Db.C_INCIDENT + "=?", new String[]{incidentId});
            w.delete(Db.T_INCIDENTS, Db.C_INCIDENT + "=?", new String[]{incidentId});
        } catch (Exception e) {
            Log.e(TAG, "deleteIncident failed for " + incidentId, e);
        }
        File dir = incidentDirOrNull(ctx, incidentId);
        if (dir != null) deleteRecursively(dir);
    }

    // =====================================================================================
    // Incident index (filled by EvidenceModule from the SafetyHooks callbacks)
    // =====================================================================================

    public static void recordIncidentStarted(Context ctx, String incidentId, String source,
                                             long startedAt, boolean silent) {
        try {
            ContentValues v = new ContentValues();
            v.put(Db.C_INCIDENT, incidentId);
            v.put(Db.I_SOURCE, source);
            v.put(Db.I_STARTED, startedAt);
            v.put(Db.I_SILENT, silent ? 1 : 0);
            SQLiteDatabase w = db(ctx).getWritableDatabase();
            long row = w.insertWithOnConflict(Db.T_INCIDENTS, null, v, SQLiteDatabase.CONFLICT_IGNORE);
            if (row == -1) {
                v.remove(Db.C_INCIDENT);
                w.update(Db.T_INCIDENTS, v, Db.C_INCIDENT + "=?", new String[]{incidentId});
            }
        } catch (Exception e) {
            Log.e(TAG, "recordIncidentStarted failed", e);
        }
    }

    public static void recordDuress(Context ctx, String incidentId) {
        ContentValues v = new ContentValues();
        v.put(Db.I_DURESS, 1);
        updateIncident(ctx, incidentId, v);
    }

    public static void recordIncidentEnded(Context ctx, String incidentId, long endedAt) {
        ContentValues v = new ContentValues();
        v.put(Db.I_ENDED, endedAt);
        updateIncident(ctx, incidentId, v);
    }

    public static void recordLocation(Context ctx, String incidentId, double lat, double lng,
                                      float accuracy, long at) {
        ContentValues v = new ContentValues();
        v.put(Db.I_LAT, lat);
        v.put(Db.I_LNG, lng);
        v.put(Db.I_ACC, accuracy);
        v.put(Db.I_LOC_AT, at);
        updateIncident(ctx, incidentId, v);
    }

    /** Index row for an incident; synthesised from its evidence when no row exists. Null if unknown. */
    @Nullable
    public static IncidentInfo incident(Context ctx, String incidentId) {
        if (TextUtils.isEmpty(incidentId)) return null;
        try (Cursor c = db(ctx).getReadableDatabase().query(Db.T_INCIDENTS,
                new String[]{Db.I_SOURCE, Db.I_STARTED, Db.I_ENDED, Db.I_SILENT, Db.I_DURESS,
                        Db.I_LAT, Db.I_LNG, Db.I_ACC, Db.I_LOC_AT},
                Db.C_INCIDENT + "=?", new String[]{incidentId}, null, null, null)) {
            if (c.moveToFirst()) {
                IncidentInfo info = new IncidentInfo();
                info.incidentId = incidentId;
                info.source = c.isNull(0) ? null : c.getString(0);
                info.startedAt = c.isNull(1) ? 0 : c.getLong(1);
                info.endedAt = c.isNull(2) ? 0 : c.getLong(2);
                info.silent = !c.isNull(3) && c.getInt(3) != 0;
                info.duress = !c.isNull(4) && c.getInt(4) != 0;
                info.hasLocation = !c.isNull(5) && !c.isNull(6);
                if (info.hasLocation) {
                    info.lastLat = c.getDouble(5);
                    info.lastLng = c.getDouble(6);
                    info.lastAccuracy = c.isNull(7) ? 0f : c.getFloat(7);
                    info.lastLocationAt = c.isNull(8) ? 0 : c.getLong(8);
                }
                return info;
            }
        } catch (Exception e) {
            Log.e(TAG, "incident() failed", e);
            return null;
        }
        List<Item> items = forIncident(ctx, incidentId);
        if (items.isEmpty()) return null;
        IncidentInfo info = new IncidentInfo();
        info.incidentId = incidentId;
        info.inferred = true;
        info.startedAt = items.get(0).capturedAt;
        info.endedAt = items.get(items.size() - 1).capturedAt;
        return info;
    }

    // =====================================================================================
    // Files
    // =====================================================================================

    /** Evidence root: external app files when available, else internal files. */
    @NonNull
    public static File rootDir(Context ctx) {
        File ext = null;
        try {
            ext = ctx.getExternalFilesDir(DIR_NAME);
        } catch (Exception ignored) {
            // External storage unavailable.
        }
        return ext != null ? ext : new File(ctx.getFilesDir(), DIR_NAME);
    }

    /** Folder for one incident's files, created if needed. */
    @NonNull
    public static File incidentDir(Context ctx, String incidentId) {
        File dir = new File(rootDir(ctx), safeName(incidentId));
        if (!dir.isDirectory() && !dir.mkdirs()) Log.w(TAG, "Can't create " + dir);
        return dir;
    }

    @Nullable
    private static File incidentDirOrNull(Context ctx, String incidentId) {
        File dir = new File(rootDir(ctx), safeName(incidentId));
        return dir.isDirectory() ? dir : null;
    }

    private static String safeName(String id) {
        return id == null ? "unknown" : id.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static void deleteRecursively(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteRecursively(c);
        if (f.exists() && !f.delete()) Log.w(TAG, "Could not delete " + f);
    }

    /** SHA-256 of a file as lowercase hex. */
    public static String sha256(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return hex(md.digest());
    }

    public static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format(Locale.US, "%02x", b & 0xff));
        return sb.toString();
    }

    // =====================================================================================
    // Internals
    // =====================================================================================

    private static void updateIncident(Context ctx, String incidentId, ContentValues v) {
        if (TextUtils.isEmpty(incidentId)) return;
        try {
            SQLiteDatabase w = db(ctx).getWritableDatabase();
            int n = w.update(Db.T_INCIDENTS, v, Db.C_INCIDENT + "=?", new String[]{incidentId});
            if (n == 0) {
                // The start callback was missed (e.g. process restart): create a minimal row.
                ContentValues full = new ContentValues(v);
                full.put(Db.C_INCIDENT, incidentId);
                w.insertWithOnConflict(Db.T_INCIDENTS, null, full, SQLiteDatabase.CONFLICT_IGNORE);
            }
        } catch (Exception e) {
            Log.e(TAG, "updateIncident failed", e);
        }
    }

    private static List<Item> query(Context ctx, String where, @Nullable String[] args,
                                    String order, @Nullable String limit) {
        List<Item> out = new ArrayList<>();
        try (Cursor c = db(ctx).getReadableDatabase().query(Db.T_EVIDENCE,
                new String[]{Db.C_ID, Db.C_INCIDENT, Db.C_EVIDENCE_ID, Db.C_KIND, Db.C_PATH,
                        Db.C_CONTENT_TYPE, Db.C_SIZE, Db.C_SHA, Db.C_CAPTURED_AT, Db.C_UPLOADED,
                        Db.C_SERVER_SHA, Db.C_VERIFIED},
                where, args, null, null, order, limit)) {
            while (c.moveToNext()) {
                Item it = new Item();
                it.id = c.getLong(0);
                it.incidentId = c.getString(1);
                it.evidenceId = c.getString(2);
                it.kind = c.isNull(3) ? KIND_PHOTO : c.getString(3);
                it.file = c.isNull(4) ? null : new File(c.getString(4));
                it.contentType = c.isNull(5) ? null : c.getString(5);
                it.size = c.isNull(6) ? 0 : c.getLong(6);
                it.sha256 = c.isNull(7) ? null : c.getString(7);
                it.capturedAt = c.isNull(8) ? 0 : c.getLong(8);
                it.uploaded = !c.isNull(9) && c.getInt(9) != 0;
                it.serverSha256 = c.isNull(10) ? null : c.getString(10);
                it.verified = !c.isNull(11) && c.getInt(11) != 0;
                out.add(it);
            }
        } catch (Exception e) {
            Log.e(TAG, "query failed", e);
        }
        return out;
    }

    private static volatile Db helper;

    private static Db db(Context ctx) {
        Db h = helper;
        if (h == null) {
            synchronized (EvidenceStore.class) {
                h = helper;
                if (h == null) {
                    h = new Db(ctx.getApplicationContext());
                    helper = h;
                }
            }
        }
        return h;
    }

    private static final class Db extends SQLiteOpenHelper {
        static final String NAME = "evidence.db";
        static final int VERSION = 1;

        static final String T_EVIDENCE = "evidence";
        static final String C_ID = "_id";
        static final String C_INCIDENT = "incident_id";
        static final String C_EVIDENCE_ID = "evidence_id";
        static final String C_KIND = "kind";
        static final String C_PATH = "path";
        static final String C_CONTENT_TYPE = "content_type";
        static final String C_SIZE = "size";
        static final String C_SHA = "sha256";
        static final String C_CAPTURED_AT = "captured_at";
        static final String C_UPLOADED = "uploaded";
        static final String C_SERVER_SHA = "server_sha256";
        static final String C_VERIFIED = "verified";

        static final String T_INCIDENTS = "incidents";
        static final String I_SOURCE = "source";
        static final String I_STARTED = "started_at";
        static final String I_ENDED = "ended_at";
        static final String I_SILENT = "silent";
        static final String I_DURESS = "duress";
        static final String I_LAT = "last_lat";
        static final String I_LNG = "last_lng";
        static final String I_ACC = "last_accuracy";
        static final String I_LOC_AT = "last_location_at";

        Db(Context ctx) {
            super(ctx, NAME, null, VERSION);
        }

        @Override
        public void onConfigure(SQLiteDatabase db) {
            super.onConfigure(db);
            db.enableWriteAheadLogging();
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + T_EVIDENCE + " ("
                    + C_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + C_INCIDENT + " TEXT NOT NULL, "
                    + C_EVIDENCE_ID + " TEXT NOT NULL UNIQUE, "
                    + C_KIND + " TEXT NOT NULL, "
                    + C_PATH + " TEXT NOT NULL, "
                    + C_CONTENT_TYPE + " TEXT, "
                    + C_SIZE + " INTEGER NOT NULL DEFAULT 0, "
                    + C_SHA + " TEXT NOT NULL, "
                    + C_CAPTURED_AT + " INTEGER NOT NULL, "
                    + C_UPLOADED + " INTEGER NOT NULL DEFAULT 0, "
                    + C_SERVER_SHA + " TEXT, "
                    + C_VERIFIED + " INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("CREATE INDEX idx_evidence_incident ON " + T_EVIDENCE + "(" + C_INCIDENT + ")");
            db.execSQL("CREATE INDEX idx_evidence_pending ON " + T_EVIDENCE + "(" + C_UPLOADED + ", " + C_CAPTURED_AT + ")");
            db.execSQL("CREATE TABLE " + T_INCIDENTS + " ("
                    + C_INCIDENT + " TEXT PRIMARY KEY, "
                    + I_SOURCE + " TEXT, "
                    + I_STARTED + " INTEGER NOT NULL DEFAULT 0, "
                    + I_ENDED + " INTEGER NOT NULL DEFAULT 0, "
                    + I_SILENT + " INTEGER NOT NULL DEFAULT 0, "
                    + I_DURESS + " INTEGER NOT NULL DEFAULT 0, "
                    + I_LAT + " REAL, "
                    + I_LNG + " REAL, "
                    + I_ACC + " REAL, "
                    + I_LOC_AT + " INTEGER)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // Version 1 is the first schema. Future versions must migrate, never drop evidence.
        }
    }
}
