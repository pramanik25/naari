package com.example.naarishakti.cloud;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.example.naarishakti.evidence.EvidenceStore;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Uploads {@link EvidenceStore#pending} items one by one as raw PUT bodies
 * (CONTRACT.md "Evidence"), loops until nothing is pending. Transient failures retry with the
 * WorkManager exponential backoff; items the server refuses are skipped for this run.
 */
public final class UploadWorker extends Worker {

    private static final String TAG = "NsCloud.Upload";
    private static final int BATCH = 20;

    private static final int OK = 0;
    private static final int SKIP = 1;
    private static final int RETRY = 2;

    public UploadWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        if (!Cloud.isActive(ctx)) return Result.success();

        // Incidents are created through the outbox; send those first so evidence has a parent.
        if (CloudOutbox.drain(ctx) == CloudOutbox.TRANSIENT) return Result.retry();

        ApiClient api = ApiClient.get(ctx);
        Set<Long> skipped = new HashSet<>();
        Set<Long> done = new HashSet<>();
        while (!isStopped()) {
            List<EvidenceStore.Item> items;
            try {
                items = EvidenceStore.pending(ctx, BATCH + skipped.size() + done.size());
            } catch (Throwable t) {
                Log.e(TAG, "EvidenceStore.pending failed", t);
                return Result.retry();
            }
            if (items == null || items.isEmpty()) return Result.success();

            boolean progressed = false;
            for (EvidenceStore.Item item : items) {
                if (isStopped()) return Result.retry();
                if (item == null || item.uploaded || skipped.contains(item.id) || done.contains(item.id)) continue;
                int r = upload(ctx, api, item);
                if (r == OK) {
                    // Guards against a store that fails to persist markUploaded (no endless loop).
                    done.add(item.id);
                    progressed = true;
                } else if (r == SKIP) {
                    skipped.add(item.id);
                } else {
                    return Result.retry();
                }
            }
            if (!progressed) return Result.success();
        }
        return Result.retry();
    }

    private int upload(Context ctx, ApiClient api, EvidenceStore.Item item) {
        File file = item.file;
        if (file == null || !file.isFile() || TextUtils.isEmpty(item.incidentId)
                || TextUtils.isEmpty(item.evidenceId)) {
            Log.w(TAG, "Evidence " + item.id + " has no file or ids; skipping");
            return SKIP;
        }
        String kind = TextUtils.isEmpty(item.kind) ? kindFor(item.contentType) : item.kind;
        String contentType = TextUtils.isEmpty(item.contentType) ? contentTypeFor(kind) : item.contentType;
        String sha = TextUtils.isEmpty(item.sha256) ? sha256(file) : item.sha256.toLowerCase(Locale.US);
        if (sha == null) return SKIP;

        Map<String, String> headers = new HashMap<>();
        headers.put("X-Evidence-Kind", kind);
        headers.put("X-Sha256", sha);
        headers.put("X-Captured-At", String.valueOf(item.capturedAt > 0 ? item.capturedAt : file.lastModified()));

        String path = "/api/v1/incidents/" + Uri.encode(item.incidentId)
                + "/evidence/" + Uri.encode(item.evidenceId);
        try {
            JsonObject res = api.putFile(path, file, contentType, headers);
            markUploaded(ctx, item.id, Json.str(res, "serverSha256"), Json.bool(res, "verified", false));
            return OK;
        } catch (CloudException e) {
            if (e.status == 409) {
                // Same id already stored with a different hash; the server never overwrites.
                Log.w(TAG, "Evidence " + item.evidenceId + " already exists on the server");
                markUploaded(ctx, item.id, null, false);
                return OK;
            }
            if (e instanceof CloudException.Disabled) return SKIP;
            if (e.isTransient()) {
                Log.i(TAG, "Upload paused: " + e.getMessage());
                return RETRY;
            }
            Log.w(TAG, "Server refused evidence " + item.evidenceId + ": " + e.status + " " + e.code);
            return SKIP;
        } catch (RuntimeException e) {
            Log.e(TAG, "Upload failed for evidence " + item.evidenceId, e);
            return SKIP;
        }
    }

    private static void markUploaded(Context ctx, long id, @Nullable String serverSha, boolean verified) {
        try {
            EvidenceStore.markUploaded(ctx, id, serverSha, verified);
        } catch (Throwable t) {
            Log.e(TAG, "EvidenceStore.markUploaded failed", t);
        }
    }

    private static String kindFor(@Nullable String contentType) {
        if (contentType == null) return "photo";
        if (contentType.startsWith("audio")) return "audio";
        if (contentType.startsWith("video")) return "video";
        return "photo";
    }

    private static String contentTypeFor(String kind) {
        if ("audio".equals(kind)) return "audio/mp4";
        if ("video".equals(kind)) return "video/mp4";
        return "image/jpeg";
    }

    @Nullable
    private static String sha256(File file) {
        InputStream in = null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            in = new FileInputStream(file);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format(Locale.US, "%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "Hashing " + file + " failed", e);
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
