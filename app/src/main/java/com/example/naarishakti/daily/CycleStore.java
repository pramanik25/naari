package com.example.naarishakti.daily;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.security.crypto.EncryptedFile;
import androidx.security.crypto.MasterKey;

import com.google.gson.Gson;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Encrypted storage for the cycle and mood log: one JSON document written with
 * {@link EncryptedFile} (keys wrapped by a {@link MasterKey} in the Android Keystore), the same
 * scheme as the incident diary. It never leaves the phone and is excluded from backups.
 *
 * EncryptedFile refuses to overwrite, so each save is a new "log_<n>.enc" and older versions are
 * deleted only after the write succeeded. A "memory only" store (the duress decoy) never touches
 * the disk.
 *
 * Every method does I/O and crypto; call it off the main thread.
 */
final class CycleStore {

    private static final String TAG = "CycleStore";
    private static final String DIR = "cycle";
    private static final String PREFIX = "log_";
    private static final String SUFFIX = ".enc";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    static final int FLOW_NONE = 0;
    static final int MOOD_NONE = 0;

    /** What she logged for one day. */
    static final class Day {
        boolean period;
        /** 0 = not set, 1 light, 2 medium, 3 heavy. */
        int flow;
        /** 0 = not set, 1 (low) to 5 (great). */
        int mood;
        /** Symptom keys, see {@code CycleActivity.SYMPTOMS}. */
        List<String> symptoms = new ArrayList<>();

        boolean isEmpty() {
            return !period && flow == FLOW_NONE && mood == MOOD_NONE && (symptoms == null || symptoms.isEmpty());
        }
    }

    static final class Data {
        /** Epoch day to that day's log. */
        TreeMap<Integer, Day> days = new TreeMap<>();

        SortedSet<Integer> periodDays() {
            SortedSet<Integer> out = new TreeSet<>();
            for (java.util.Map.Entry<Integer, Day> e : days.entrySet()) {
                if (e.getValue() != null && e.getValue().period) out.add(e.getKey());
            }
            return out;
        }
    }

    private final Context ctx;
    private final boolean memoryOnly;
    private final File dir;
    private final Gson gson = new Gson();
    @Nullable private MasterKey masterKey;
    private Data memory = new Data();

    private CycleStore(Context ctx, boolean memoryOnly) {
        this.ctx = ctx.getApplicationContext();
        this.memoryOnly = memoryOnly;
        this.dir = new File(this.ctx.getFilesDir(), DIR);
    }

    static CycleStore open(Context ctx) {
        return new CycleStore(ctx, false);
    }

    /** An empty log that only lives in memory (shown under duress). */
    static CycleStore decoy(Context ctx) {
        return new CycleStore(ctx, true);
    }

    synchronized Data load() throws Exception {
        if (memoryOnly) return memory;
        Exception last = null;
        for (File f : versions()) {
            try {
                Data parsed = gson.fromJson(new String(read(f), UTF8), Data.class);
                if (parsed == null) parsed = new Data();
                if (parsed.days == null) parsed.days = new TreeMap<>();
                return parsed;
            } catch (Exception e) {
                // A newer version that failed to decrypt (interrupted write): try the previous one.
                Log.w(TAG, "Couldn't read " + f.getName(), e);
                last = e;
            }
        }
        if (last != null) throw last;
        return new Data();
    }

    synchronized void save(Data data) throws Exception {
        if (memoryOnly) {
            memory = data;
            return;
        }
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Can't create cycle folder");
        File[] old = versions();
        long version = System.currentTimeMillis();
        for (File f : old) version = Math.max(version, versionOf(f) + 1);
        File target = new File(dir, PREFIX + version + SUFFIX);
        boolean ok = false;
        try (OutputStream out = encrypted(target).openFileOutput()) {
            out.write(gson.toJson(data).getBytes(UTF8));
            out.flush();
            ok = true;
        } finally {
            if (!ok && target.exists() && !target.delete()) Log.w(TAG, "Couldn't clean up " + target.getName());
        }
        for (File f : old) {
            if (!f.delete()) Log.w(TAG, "Couldn't delete old version " + f.getName());
        }
    }

    // ---- crypto ----

    private EncryptedFile encrypted(File f) throws Exception {
        if (masterKey == null) {
            masterKey = new MasterKey.Builder(ctx)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
        }
        return new EncryptedFile.Builder(ctx, f, masterKey,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB).build();
    }

    private byte[] read(File f) throws Exception {
        try (InputStream in = encrypted(f).openFileInput()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    // ---- helpers ----

    /** Saved versions, newest first. */
    private File[] versions() {
        File[] files = dir.listFiles((d, name) -> name.startsWith(PREFIX) && name.endsWith(SUFFIX));
        if (files == null) return new File[0];
        Arrays.sort(files, (a, b) -> Long.compare(versionOf(b), versionOf(a)));
        return files;
    }

    private static long versionOf(File f) {
        String n = f.getName();
        try {
            return Long.parseLong(n.substring(PREFIX.length(), n.length() - SUFFIX.length()));
        } catch (Exception e) {
            return 0;
        }
    }
}
