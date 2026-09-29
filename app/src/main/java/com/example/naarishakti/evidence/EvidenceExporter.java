package com.example.naarishakti.evidence;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds a shareable evidence package for one incident: every file plus a {@code manifest.json}
 * listing name, kind, capture time, SHA-256, size, the server's hash and whether it was verified.
 * While zipping, each file is re-hashed so the manifest also says whether it is still intact.
 */
final class EvidenceExporter {

    private static final String TAG = "EvidenceExporter";

    private EvidenceExporter() {}

    /** Returns the zip in the exports cache, or null when there is nothing to export / it failed. */
    @Nullable
    static File export(Context ctx, String incidentId) {
        List<EvidenceStore.Item> items = EvidenceStore.forIncident(ctx, incidentId);
        List<EvidenceStore.Item> present = new ArrayList<>();
        for (EvidenceStore.Item it : items) if (it.file != null && it.file.isFile()) present.add(it);
        if (present.isEmpty()) return null;

        EvidenceStore.IncidentInfo info = EvidenceStore.incident(ctx, incidentId);
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US)
                .format(new Date(info != null && info.startedAt > 0 ? info.startedAt : System.currentTimeMillis()));
        String shortId = incidentId.length() > 8 ? incidentId.substring(0, 8) : incidentId;
        File out = new File(EvUi.exportsDir(ctx), "naari_evidence_" + stamp + "_" + shortId + ".zip");

        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));

        List<Map<String, Object>> files = new ArrayList<>();
        Set<String> names = new HashSet<>();
        byte[] buf = new byte[64 * 1024];
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(out))) {
            for (EvidenceStore.Item it : present) {
                String name = uniqueName(names, it.kind + "/" + it.file.getName());
                zip.putNextEntry(new ZipEntry(name));
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                try (InputStream in = new FileInputStream(it.file)) {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        md.update(buf, 0, n);
                        zip.write(buf, 0, n);
                    }
                }
                zip.closeEntry();
                String actual = EvidenceStore.hex(md.digest());

                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", name);
                m.put("evidenceId", it.evidenceId);
                m.put("kind", it.kind);
                m.put("contentType", it.contentType);
                m.put("capturedAt", it.capturedAt);
                m.put("capturedAtUtc", iso.format(new Date(it.capturedAt)));
                m.put("sha256", it.sha256);
                m.put("size", it.size);
                m.put("serverSha256", it.serverSha256);
                m.put("verified", it.verified);
                m.put("intact", actual.equals(it.sha256));
                files.add(m);
            }

            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("format", "naari-shakti-evidence/1");
            manifest.put("hashAlgorithm", "SHA-256");
            manifest.put("incidentId", incidentId);
            if (info != null) {
                manifest.put("source", info.source);
                manifest.put("startedAt", info.startedAt);
                if (info.startedAt > 0) manifest.put("startedAtUtc", iso.format(new Date(info.startedAt)));
                manifest.put("endedAt", info.endedAt);
                if (info.endedAt > 0) manifest.put("endedAtUtc", iso.format(new Date(info.endedAt)));
                manifest.put("silent", info.silent);
                manifest.put("duress", info.duress);
                if (info.hasLocation) {
                    Map<String, Object> loc = new LinkedHashMap<>();
                    loc.put("lat", info.lastLat);
                    loc.put("lng", info.lastLng);
                    loc.put("accuracyM", info.lastAccuracy);
                    loc.put("at", info.lastLocationAt);
                    manifest.put("lastLocation", loc);
                }
            }
            long now = System.currentTimeMillis();
            manifest.put("generatedAt", now);
            manifest.put("generatedAtUtc", iso.format(new Date(now)));
            manifest.put("files", files);

            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(new GsonBuilder().setPrettyPrinting().serializeNulls().create()
                    .toJson(manifest).getBytes(Charset.forName("UTF-8")));
            zip.closeEntry();
        } catch (Exception e) {
            Log.e(TAG, "Export failed", e);
            if (out.exists() && !out.delete()) Log.w(TAG, "Could not delete " + out);
            return null;
        }
        return out;
    }

    private static String uniqueName(Set<String> used, String name) {
        String candidate = name;
        int n = 2;
        while (!used.add(candidate)) {
            int dot = name.lastIndexOf('.');
            candidate = dot > 0 ? name.substring(0, dot) + "_" + n + name.substring(dot) : name + "_" + n;
            n++;
        }
        return candidate;
    }
}
