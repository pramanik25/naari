package com.example.naarishakti.evidence;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.security.crypto.EncryptedFile;
import androidx.security.crypto.MasterKey;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Encrypted storage for the incident diary: the entry list is one JSON document and each attached
 * photo is its own file, all written with {@link EncryptedFile} (AES256-GCM-HKDF-4KB streaming,
 * keys wrapped by an AES256-GCM {@link MasterKey} in the Android Keystore).
 *
 * EncryptedFile binds the ciphertext to its file name and refuses to overwrite, so the entry list
 * is saved as a new "entries_<n>.enc" and older versions are deleted only after the write
 * succeeded. A "memory only" store (for the duress decoy) never touches the disk.
 *
 * Every method does I/O and crypto; call it off the main thread.
 */
final class DiaryStore {

    private static final String TAG = "DiaryStore";
    private static final String DIR = "diary";
    private static final String ENTRIES_PREFIX = "entries_";
    private static final String ENTRIES_SUFFIX = ".enc";
    private static final String PHOTO_PREFIX = "photo_";
    static final long MAX_PHOTO_BYTES = 25L * 1024 * 1024;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    static final class Entry {
        String id;
        long occurredAt;
        String title;
        String body;
        @Nullable String location;
        /** File name of the encrypted photo in the diary folder, or null. */
        @Nullable String photo;
        long createdAt;
        long updatedAt;
    }

    static final class TooLargeException extends IOException {
        TooLargeException() {
            super("Photo too large");
        }
    }

    private final Context ctx;
    private final boolean memoryOnly;
    private final File dir;
    private final Gson gson = new Gson();
    @Nullable private MasterKey masterKey;
    private final List<Entry> memoryEntries = new ArrayList<>();
    private final Map<String, byte[]> memoryPhotos = new HashMap<>();

    private DiaryStore(Context ctx, boolean memoryOnly) {
        this.ctx = ctx.getApplicationContext();
        this.memoryOnly = memoryOnly;
        this.dir = new File(this.ctx.getFilesDir(), DIR);
    }

    static DiaryStore open(Context ctx) {
        return new DiaryStore(ctx, false);
    }

    /** An empty diary that only lives in memory (shown under duress). */
    static DiaryStore decoy(Context ctx) {
        return new DiaryStore(ctx, true);
    }

    // ---- entries ----

    /** All entries, newest occurrence first. */
    synchronized List<Entry> load() throws Exception {
        List<Entry> out = new ArrayList<>();
        if (memoryOnly) {
            out.addAll(memoryEntries);
        } else {
            File[] versions = entryFiles();
            Exception last = null;
            for (File f : versions) {
                try {
                    String json = new String(read(f), UTF8);
                    Type type = new TypeToken<List<Entry>>() {}.getType();
                    List<Entry> parsed = gson.fromJson(json, type);
                    if (parsed != null) out.addAll(parsed);
                    last = null;
                    break;
                } catch (Exception e) {
                    // A newer version that failed to decrypt (interrupted write): try the previous one.
                    Log.w(TAG, "Couldn't read " + f.getName(), e);
                    last = e;
                }
            }
            if (last != null) throw last;
        }
        sort(out);
        return out;
    }

    synchronized void save(List<Entry> entries) throws Exception {
        List<Entry> copy = new ArrayList<>(entries);
        sort(copy);
        if (memoryOnly) {
            memoryEntries.clear();
            memoryEntries.addAll(copy);
            return;
        }
        ensureDir();
        File[] old = entryFiles();
        long version = System.currentTimeMillis();
        for (File f : old) version = Math.max(version, versionOf(f) + 1);
        File target = new File(dir, ENTRIES_PREFIX + version + ENTRIES_SUFFIX);
        write(target, gson.toJson(copy).getBytes(UTF8));
        for (File f : old) {
            if (!f.delete()) Log.w(TAG, "Couldn't delete old version " + f.getName());
        }
    }

    // ---- photos ----

    /** Copies a photo into encrypted storage and returns its name. */
    synchronized String putPhoto(InputStream in) throws Exception {
        byte[] bytes = readAll(in, MAX_PHOTO_BYTES);
        String name = PHOTO_PREFIX + UUID.randomUUID().toString() + ENTRIES_SUFFIX;
        if (memoryOnly) {
            memoryPhotos.put(name, bytes);
        } else {
            ensureDir();
            write(new File(dir, name), bytes);
        }
        return name;
    }

    @Nullable
    synchronized byte[] readPhoto(@Nullable String name) {
        if (name == null || !name.startsWith(PHOTO_PREFIX)) return null;
        if (memoryOnly) return memoryPhotos.get(name);
        try {
            return read(new File(dir, name));
        } catch (Exception e) {
            Log.w(TAG, "Couldn't read photo " + name, e);
            return null;
        }
    }

    synchronized void deletePhoto(@Nullable String name) {
        if (name == null || !name.startsWith(PHOTO_PREFIX)) return;
        if (memoryOnly) {
            memoryPhotos.remove(name);
            return;
        }
        File f = new File(dir, name);
        if (f.exists() && !f.delete()) Log.w(TAG, "Couldn't delete photo " + name);
    }

    // ---- crypto ----

    private MasterKey key() throws Exception {
        if (masterKey == null) {
            masterKey = new MasterKey.Builder(ctx)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
        }
        return masterKey;
    }

    private EncryptedFile encrypted(File f) throws Exception {
        return new EncryptedFile.Builder(ctx, f, key(),
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB).build();
    }

    private byte[] read(File f) throws Exception {
        try (InputStream in = encrypted(f).openFileInput()) {
            return readAll(in, Long.MAX_VALUE);
        }
    }

    private void write(File f, byte[] data) throws Exception {
        if (f.exists() && !f.delete()) throw new IOException("Can't replace " + f.getName());
        boolean ok = false;
        try (OutputStream out = encrypted(f).openFileOutput()) {
            out.write(data);
            out.flush();
            ok = true;
        } finally {
            if (!ok && f.exists() && !f.delete()) Log.w(TAG, "Couldn't clean up " + f.getName());
        }
    }

    // ---- helpers ----

    private void ensureDir() throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Can't create diary folder");
    }

    /** Entry list versions, newest first. */
    private File[] entryFiles() {
        File[] files = dir.listFiles((d, name) -> name.startsWith(ENTRIES_PREFIX) && name.endsWith(ENTRIES_SUFFIX));
        if (files == null) return new File[0];
        Arrays.sort(files, (a, b) -> Long.compare(versionOf(b), versionOf(a)));
        return files;
    }

    private static long versionOf(File f) {
        String n = f.getName();
        try {
            return Long.parseLong(n.substring(ENTRIES_PREFIX.length(), n.length() - ENTRIES_SUFFIX.length()));
        } catch (Exception e) {
            return 0;
        }
    }

    private static void sort(List<Entry> list) {
        Collections.sort(list, (a, b) -> Long.compare(b.occurredAt, a.occurredAt));
    }

    private static byte[] readAll(InputStream in, long max) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[32 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > max) throw new TooLargeException();
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }
}
