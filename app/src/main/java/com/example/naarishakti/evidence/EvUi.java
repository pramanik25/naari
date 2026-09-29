package com.example.naarishakti.evidence;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.content.FileProvider;

import com.example.naarishakti.R;

import java.io.File;
import java.util.Locale;

/** Formatting, sharing and clipboard helpers shared by the evidence screens. */
final class EvUi {

    private static final String TAG = "EvUi";
    /** Temporary share files (zips, PDFs) older than this are deleted. */
    private static final long EXPORT_TTL_MS = 60 * 60 * 1000L;

    private EvUi() {}

    // ---- labels ----

    static String sourceLabel(Context c, @Nullable String source) {
        @StringRes int res = R.string.ev_src_unknown;
        if (source != null) {
            switch (source.toLowerCase(Locale.ROOT)) {
                case "voice": res = R.string.ev_src_voice; break;
                case "power": res = R.string.ev_src_power; break;
                case "shake": res = R.string.ev_src_shake; break;
                case "sos_button": res = R.string.ev_src_sos_button; break;
                case "scream": res = R.string.ev_src_scream; break;
                case "fall": res = R.string.ev_src_fall; break;
                case "ble_button": res = R.string.ev_src_ble_button; break;
                case "headset": res = R.string.ev_src_headset; break;
                case "checkin": res = R.string.ev_src_checkin; break;
                case "duress": res = R.string.ev_src_duress; break;
                case "volume":
                case "volume_key":
                case "volume_keys": res = R.string.ev_src_volume; break;
                case "geofence": res = R.string.ev_src_geofence; break;
                default: break;
            }
        }
        return c.getString(res);
    }

    static String kindLabel(Context c, @Nullable String kind) {
        if (EvidenceStore.KIND_AUDIO.equals(kind)) return c.getString(R.string.ev_kind_audio);
        if (EvidenceStore.KIND_VIDEO.equals(kind)) return c.getString(R.string.ev_kind_video);
        return c.getString(R.string.ev_kind_photo);
    }

    // ---- time ----

    /** "Mon, 28 Sep 2026 · 21:41" in the user's locale and 12/24h preference. */
    static String dateTime(Context c, long ts) {
        int flags = DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_SHOW_DATE
                | DateUtils.FORMAT_SHOW_YEAR | DateUtils.FORMAT_ABBREV_ALL;
        return c.getString(R.string.ev_date_time, DateUtils.formatDateTime(c, ts, flags), time(c, ts));
    }

    static String time(Context c, long ts) {
        return DateFormat.getTimeFormat(c).format(ts);
    }

    /** Time with seconds, e.g. for evidence items. */
    static String timeWithSeconds(Context c, long ts) {
        String pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(),
                DateFormat.is24HourFormat(c) ? "Hms" : "hms");
        return DateFormat.format(pattern, ts).toString();
    }

    static String duration(Context c, long ms) {
        long s = Math.max(0, ms / 1000);
        if (s < 60) return c.getString(R.string.ev_dur_seconds, (int) s);
        long m = s / 60;
        if (m < 60) return c.getString(R.string.ev_dur_minutes, (int) m);
        return c.getString(R.string.ev_dur_hours, (int) (m / 60), (int) (m % 60));
    }

    static String size(Context c, long bytes) {
        return android.text.format.Formatter.formatShortFileSize(c, bytes);
    }

    static String shortHash(Context c, @Nullable String sha) {
        if (TextUtils.isEmpty(sha)) return c.getString(R.string.ev_hash_label);
        return c.getString(R.string.ev_hash_short, sha.substring(0, Math.min(12, sha.length())));
    }

    // ---- clipboard ----

    static void copy(Context c, String label, String text) {
        ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText(label, text));
    }

    // ---- files ----

    /** Cache folder for files handed to other apps; covered by the FileProvider "exports/" path. */
    static File exportsDir(Context c) {
        File dir = new File(c.getCacheDir(), "exports");
        if (!dir.isDirectory() && !dir.mkdirs()) Log.w(TAG, "Can't create " + dir);
        return dir;
    }

    /** Removes old shared exports so unencrypted copies don't linger. Call off the main thread. */
    static void cleanupExports(Context c) {
        File[] files = new File(c.getCacheDir(), "exports").listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - EXPORT_TTL_MS;
        for (File f : files) {
            if (f.lastModified() < cutoff && !f.delete()) Log.w(TAG, "Could not delete " + f);
        }
    }

    @Nullable
    static Uri uriFor(Context c, File file) {
        try {
            return FileProvider.getUriForFile(c, c.getPackageName() + ".provider", file);
        } catch (IllegalArgumentException e) {
            // The file's folder isn't listed in res/xml/file_paths.xml.
            Log.e(TAG, "FileProvider can't share " + file, e);
            return null;
        }
    }

    /** Opens the share sheet for a file. */
    static void shareFile(Activity a, File file, String mime, @StringRes int chooserTitle,
                          @Nullable String subject) {
        Uri uri = uriFor(a, file);
        if (uri == null) {
            toast(a, R.string.ev_share_unavailable);
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType(mime)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (subject != null) send.putExtra(Intent.EXTRA_SUBJECT, subject);
        send.setClipData(ClipData.newRawUri(file.getName(), uri));
        start(a, Intent.createChooser(send, a.getString(chooserTitle)));
    }

    /** Opens a file in an external viewer/player. */
    static void openFile(Activity a, File file, String mime) {
        if (file == null || !file.exists()) {
            toast(a, R.string.ev_item_missing);
            return;
        }
        Uri uri = uriFor(a, file);
        if (uri == null) {
            toast(a, R.string.ev_share_unavailable);
            return;
        }
        Intent view = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        start(a, view);
    }

    static void openUrl(Activity a, String url) {
        start(a, new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }

    static void start(Activity a, Intent intent) {
        try {
            a.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            toast(a, R.string.ev_no_app);
        }
    }

    static void toast(Context c, @StringRes int res) {
        Toast.makeText(c, res, Toast.LENGTH_SHORT).show();
    }
}
