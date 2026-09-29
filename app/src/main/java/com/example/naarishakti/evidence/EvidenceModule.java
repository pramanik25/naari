package com.example.naarishakti.evidence;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.example.naarishakti.core.Prefs;
import com.example.naarishakti.core.SafetyHooks;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Records audio/video evidence during an SOS, stores every file (and the engine's still photos)
 * in {@link EvidenceStore} with its SHA-256, and keeps the incident index current.
 *
 * Recording only happens while the engine's SOS is active, and the engine shows its persistent
 * SOS notification for the whole time, so the phone's owner can always see it is running.
 */
public final class EvidenceModule implements SafetyHooks.Module {

    private static final String TAG = "EvidenceModule";
    /** Write the incident's last location at most this often. */
    private static final long LOCATION_WRITE_INTERVAL_MS = 15_000L;

    /** Single thread: keeps hashing/inserts in capture order. */
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ev-store");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private final Handler main = new Handler(Looper.getMainLooper());

    // Main-thread state
    @Nullable private String activeIncidentId;
    @Nullable private AudioChunkRecorder audio;
    @Nullable private VideoChunkRecorder video;
    private long lastLocationWrite;

    // ---- SafetyHooks ----

    @Override
    public void onSosStarted(@NonNull Context ctx, @NonNull SafetyHooks.Incident incident) {
        final Context app = ctx.getApplicationContext();
        stopRecorders();
        activeIncidentId = incident.id;
        lastLocationWrite = 0;
        final String id = incident.id;
        final String source = incident.source;
        final long startedAt = incident.startedAt;
        final boolean silent = incident.silent;
        io.execute(() -> EvidenceStore.recordIncidentStarted(app, id, source, startedAt, silent));

        boolean wantAudio = Prefs.get(app).getBoolean(Prefs.EVIDENCE_AUDIO, true);
        boolean wantVideo = Prefs.get(app).getBoolean(Prefs.EVIDENCE_VIDEO, false);
        boolean mic = granted(app, Manifest.permission.RECORD_AUDIO);
        boolean cam = granted(app, Manifest.permission.CAMERA)
                && app.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);

        if (wantVideo && mic && cam) {
            startVideo(app, id, wantAudio);
        } else if (wantAudio && mic) {
            startAudio(app, id);
        } else if (wantAudio || wantVideo) {
            Log.w(TAG, "Evidence recording skipped: permission missing (mic=" + mic + ", camera=" + cam + ")");
        }
    }

    @Override
    public void onSosPhoto(@NonNull Context ctx, @NonNull SafetyHooks.Incident incident,
                           @NonNull File jpeg, boolean front) {
        final Context app = ctx.getApplicationContext();
        final String id = incident.id;
        final long capturedAt = jpeg.lastModified() > 0 ? jpeg.lastModified() : System.currentTimeMillis();
        io.execute(() -> {
            // Keep a private copy inside the incident folder: the engine's photo can be deleted
            // from the photo vault, but the evidence package must stay complete and unchanged.
            File dir = EvidenceStore.incidentDir(app, id);
            File copy = uniqueFile(dir, "photo_" + capturedAt + (front ? "_front" : "_back"), ".jpg");
            File stored = copyFile(jpeg, copy) ? copy : jpeg;
            EvidenceStore.add(app, id, stored, EvidenceStore.KIND_PHOTO, "image/jpeg", capturedAt);
        });
    }

    @Override
    public void onSosLocation(@NonNull Context ctx, @NonNull SafetyHooks.Incident incident,
                              @NonNull Location location) {
        long now = System.currentTimeMillis();
        if (now - lastLocationWrite < LOCATION_WRITE_INTERVAL_MS) return;
        lastLocationWrite = now;
        final Context app = ctx.getApplicationContext();
        final String id = incident.id;
        final double lat = location.getLatitude();
        final double lng = location.getLongitude();
        final float acc = location.hasAccuracy() ? location.getAccuracy() : 0f;
        final long at = location.getTime() > 0 ? location.getTime() : now;
        io.execute(() -> EvidenceStore.recordLocation(app, id, lat, lng, acc, at));
    }

    @Override
    public void onSosDuress(@NonNull Context ctx, @NonNull SafetyHooks.Incident incident) {
        final Context app = ctx.getApplicationContext();
        final String id = incident.id;
        // Recording deliberately continues: the SOS carries on covertly after a duress PIN.
        io.execute(() -> EvidenceStore.recordDuress(app, id));
    }

    @Override
    public void onSosStopped(@NonNull Context ctx, @NonNull SafetyHooks.Incident incident,
                             boolean userInitiated) {
        final Context app = ctx.getApplicationContext();
        final String id = incident.id;
        final long endedAt = System.currentTimeMillis();
        final Location last = incident.lastLocation;
        if (id.equals(activeIncidentId)) {
            stopRecorders();
            activeIncidentId = null;
        }
        io.execute(() -> {
            if (last != null) {
                EvidenceStore.recordLocation(app, id, last.getLatitude(), last.getLongitude(),
                        last.hasAccuracy() ? last.getAccuracy() : 0f,
                        last.getTime() > 0 ? last.getTime() : endedAt);
            }
            EvidenceStore.recordIncidentEnded(app, id, endedAt);
        });
    }

    @Override
    public void onProtectionStopped(@NonNull Context ctx) {
        // The engine ends the SOS before this, but never leave the mic/camera running.
        stopRecorders();
        activeIncidentId = null;
    }

    // ---- recorders ----

    private void startAudio(final Context app, final String incidentId) {
        File dir = EvidenceStore.incidentDir(app, incidentId);
        audio = new AudioChunkRecorder(app, dir, (file, capturedAt) ->
                io.execute(() -> EvidenceStore.add(app, incidentId, file,
                        EvidenceStore.KIND_AUDIO, "audio/mp4", capturedAt)));
        audio.start();
    }

    private void startVideo(final Context app, final String incidentId, final boolean audioFallback) {
        File dir = EvidenceStore.incidentDir(app, incidentId);
        final VideoChunkRecorder[] self = new VideoChunkRecorder[1];
        self[0] = new VideoChunkRecorder(app, dir, new VideoChunkRecorder.Listener() {
            @Override
            public void onSegment(File file, long capturedAt) {
                io.execute(() -> EvidenceStore.add(app, incidentId, file,
                        EvidenceStore.KIND_VIDEO, "video/mp4", capturedAt));
            }

            @Override
            public void onFailed() {
                // Camera unusable: keep collecting audio evidence instead, if she wants audio.
                main.post(() -> {
                    if (video != self[0] || !incidentId.equals(activeIncidentId)) return;
                    video = null;
                    if (audioFallback && granted(app, Manifest.permission.RECORD_AUDIO)) {
                        startAudio(app, incidentId);
                    }
                });
            }
        });
        video = self[0];
        video.start();
    }

    private void stopRecorders() {
        if (audio != null) {
            audio.stop();
            audio = null;
        }
        if (video != null) {
            video.stop();
            video = null;
        }
    }

    // ---- helpers ----

    private static boolean granted(Context ctx, String permission) {
        return ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private static File uniqueFile(File dir, String base, String ext) {
        File f = new File(dir, base + ext);
        int n = 2;
        while (f.exists()) f = new File(dir, base + "_" + (n++) + ext);
        return f;
    }

    private static boolean copyFile(File from, File to) {
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Photo copy failed, keeping the original path", e);
            if (to.exists() && !to.delete()) Log.w(TAG, "Could not delete " + to);
            return false;
        }
    }
}
