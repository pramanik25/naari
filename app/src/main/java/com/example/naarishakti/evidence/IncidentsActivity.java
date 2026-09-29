package com.example.naarishakti.evidence;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.naarishakti.BuildConfig;
import com.example.naarishakti.R;
import com.example.naarishakti.core.SafetyHooks;
import com.example.naarishakti.databinding.EvActivityIncidentsBinding;
import com.example.naarishakti.databinding.EvItemEvidenceBinding;
import com.example.naarishakti.databinding.EvItemIncidentBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Incident history: every SOS with its evidence, upload/verification status, playback, hashes,
 * and per-incident export / complaint / delete.
 */
public class IncidentsActivity extends AppCompatActivity {

    private static final String TAG = "IncidentsActivity";
    private static final String STATE_EXPANDED = "expanded";
    private static final int THUMB_PX = 160;

    /** One incident with its evidence, prepared off the main thread. */
    static final class Row {
        String id;
        @Nullable EvidenceStore.IncidentInfo info;
        final List<EvidenceStore.Item> items = new ArrayList<>();
        int photos, audio, videos, uploaded, verified;
    }

    private EvActivityIncidentsBinding b;
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Row> rows = new ArrayList<>();
    private final Set<String> expanded = new HashSet<>();
    private Adapter adapter;
    private LruCache<Long, Bitmap> thumbs;

    // Audio playback (one clip at a time)
    @Nullable private MediaPlayer player;
    private long playingId = -1;
    private final Map<Long, EvItemEvidenceBinding> audioRows = new HashMap<>();
    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            if (player != null) main.postDelayed(this, 200);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = EvActivityIncidentsBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        if (savedInstanceState != null) {
            ArrayList<String> ex = savedInstanceState.getStringArrayList(STATE_EXPANDED);
            if (ex != null) expanded.addAll(ex);
        }
        int cacheKb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 16);
        thumbs = new LruCache<Long, Bitmap>(cacheKb) {
            @Override
            protected int sizeOf(Long key, Bitmap value) {
                return Math.max(1, value.getByteCount() / 1024);
            }
        };

        b.backButton.setOnClickListener(v -> finish());
        adapter = new Adapter();
        b.list.setLayoutManager(new LinearLayoutManager(this));
        b.list.setAdapter(adapter);
        b.list.setItemAnimator(null);
        io.execute(() -> EvUi.cleanupExports(getApplicationContext()));
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    @Override
    protected void onPause() {
        stopPlayback();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putStringArrayList(STATE_EXPANDED, new ArrayList<>(expanded));
    }

    @Override
    protected void onDestroy() {
        stopPlayback();
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
        if (thumbs != null) thumbs.evictAll();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ data

    private void load() {
        if (rows.isEmpty()) b.loading.setVisibility(View.VISIBLE);
        final android.content.Context app = getApplicationContext();
        io.execute(() -> {
            final List<Row> loaded = new ArrayList<>();
            boolean failed = false;
            try {
                for (String id : EvidenceStore.incidentIds(app)) {
                    Row r = new Row();
                    r.id = id;
                    r.info = EvidenceStore.incident(app, id);
                    r.items.addAll(EvidenceStore.forIncident(app, id));
                    for (EvidenceStore.Item it : r.items) {
                        if (EvidenceStore.KIND_AUDIO.equals(it.kind)) r.audio++;
                        else if (EvidenceStore.KIND_VIDEO.equals(it.kind)) r.videos++;
                        else r.photos++;
                        if (it.uploaded) r.uploaded++;
                        if (it.verified) r.verified++;
                    }
                    loaded.add(r);
                }
            } catch (Exception e) {
                Log.e(TAG, "Loading incidents failed", e);
                failed = true;
            }
            final boolean error = failed;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                rows.clear();
                rows.addAll(loaded);
                audioRows.clear();
                adapter.notifyDataSetChanged();
                b.loading.setVisibility(View.GONE);
                renderState();
                if (error) snack(getString(R.string.ev_inc_load_error));
            });
        });
    }

    private void renderState() {
        int n = rows.size();
        b.emptyState.setVisibility(n == 0 ? View.VISIBLE : View.GONE);
        b.list.setVisibility(n == 0 ? View.GONE : View.VISIBLE);
        b.countText.setVisibility(n == 0 ? View.GONE : View.VISIBLE);
        b.countText.setText(getResources().getQuantityString(R.plurals.ev_inc_count, n, n));
    }

    // ------------------------------------------------------------------ actions

    private void export(final Row r) {
        if (r.items.isEmpty()) {
            snack(getString(R.string.ev_export_empty));
            return;
        }
        snack(getString(R.string.ev_exporting));
        final android.content.Context app = getApplicationContext();
        io.execute(() -> {
            final File zip = EvidenceExporter.export(app, r.id);
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (zip == null) {
                    snack(getString(R.string.ev_export_failed));
                    return;
                }
                EvUi.shareFile(this, zip, "application/zip", R.string.ev_export_chooser,
                        getString(R.string.ev_export_subject));
            });
        });
    }

    private void draftComplaint(Row r) {
        startActivity(new Intent(this, ComplaintActivity.class)
                .putExtra(ComplaintActivity.EXTRA_INCIDENT_ID, r.id));
    }

    private void confirmDelete(final Row r) {
        SafetyHooks.Incident current = SafetyHooks.current();
        if (current != null && current.id.equals(r.id)) {
            snack(getString(R.string.ev_delete_active));
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.ev_delete_title)
                .setMessage(getString(R.string.ev_delete_body, r.items.size()))
                .setPositiveButton(R.string.ev_delete, (d, w) -> delete(r))
                .setNegativeButton(R.string.ev_cancel, null)
                .show();
    }

    private void delete(final Row r) {
        for (EvidenceStore.Item it : r.items) {
            if (it.id == playingId) stopPlayback();
            thumbs.remove(it.id);
        }
        final android.content.Context app = getApplicationContext();
        io.execute(() -> {
            EvidenceStore.deleteIncident(app, r.id);
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                expanded.remove(r.id);
                snack(getString(R.string.ev_deleted));
                load();
            });
        });
    }

    private void openItem(EvidenceStore.Item it) {
        String mime = it.contentType;
        if (TextUtils.isEmpty(mime)) {
            mime = EvidenceStore.KIND_VIDEO.equals(it.kind) ? "video/mp4"
                    : EvidenceStore.KIND_AUDIO.equals(it.kind) ? "audio/mp4" : "image/jpeg";
        }
        EvUi.openFile(this, it.file, mime);
    }

    private void copyHash(EvidenceStore.Item it) {
        if (TextUtils.isEmpty(it.sha256)) return;
        EvUi.copy(this, getString(R.string.ev_hash_label), it.sha256);
        snack(getString(R.string.ev_hash_copied));
    }

    // ------------------------------------------------------------------ audio

    private void togglePlay(EvidenceStore.Item it) {
        if (playingId == it.id && player != null) {
            if (player.isPlaying()) {
                player.pause();
                main.removeCallbacks(progressTick);
            } else {
                player.start();
                main.post(progressTick);
            }
            refreshAudioButton(it.id);
            return;
        }
        long previous = playingId;
        stopPlayback();
        refreshAudioButton(previous);
        if (it.file == null || !it.file.exists()) {
            snack(getString(R.string.ev_item_missing));
            return;
        }
        final MediaPlayer mp = new MediaPlayer();
        try {
            mp.setDataSource(it.file.getAbsolutePath());
            mp.setOnCompletionListener(p -> {
                long id = playingId;
                stopPlayback();
                refreshAudioButton(id);
            });
            mp.setOnErrorListener((p, what, extra) -> {
                long id = playingId;
                stopPlayback();
                refreshAudioButton(id);
                snack(getString(R.string.ev_play_failed));
                return true;
            });
            mp.prepare();
            mp.start();
        } catch (Exception e) {
            Log.w(TAG, "Playback failed", e);
            mp.release();
            snack(getString(R.string.ev_play_failed));
            return;
        }
        player = mp;
        playingId = it.id;
        refreshAudioButton(it.id);
        main.post(progressTick);
    }

    private void stopPlayback() {
        main.removeCallbacks(progressTick);
        MediaPlayer mp = player;
        player = null;
        long id = playingId;
        playingId = -1;
        if (mp != null) {
            try { mp.stop(); } catch (Exception ignored) { }
            mp.release();
        }
        EvItemEvidenceBinding row = audioRows.get(id);
        if (row != null) row.progress.setProgress(0);
    }

    private void refreshAudioButton(long itemId) {
        EvItemEvidenceBinding row = audioRows.get(itemId);
        if (row == null) return;
        boolean playing = itemId == playingId && player != null && player.isPlaying();
        row.playButton.setIconResource(playing ? R.drawable.ev_ic_pause : R.drawable.ev_ic_play);
        row.playButton.setContentDescription(getString(playing ? R.string.ev_cd_pause : R.string.ev_cd_play));
    }

    private void updateProgress() {
        MediaPlayer mp = player;
        EvItemEvidenceBinding row = audioRows.get(playingId);
        if (mp == null || row == null) return;
        try {
            int dur = mp.getDuration();
            if (dur > 0) row.progress.setProgress((int) (1000L * mp.getCurrentPosition() / dur));
        } catch (IllegalStateException ignored) {
            // Released between ticks.
        }
    }

    // ------------------------------------------------------------------ thumbnails

    private void loadThumb(final EvidenceStore.Item it, final ImageView target) {
        target.setTag(it.id);
        Bitmap cached = thumbs.get(it.id);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }
        target.setImageDrawable(null);
        if (io.isShutdown() || it.file == null || !it.file.exists()) return;
        io.execute(() -> {
            final Bitmap bmp = EvidenceStore.KIND_VIDEO.equals(it.kind)
                    ? videoThumb(it.file) : photoThumb(it.file);
            if (bmp != null) thumbs.put(it.id, bmp);
            main.post(() -> {
                if (!Long.valueOf(it.id).equals(target.getTag()) || bmp == null) return;
                target.setImageBitmap(bmp);
                target.setAlpha(0f);
                target.animate().alpha(1f).setDuration(160).start();
            });
        });
    }

    @Nullable
    private static Bitmap photoThumb(File file) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= THUMB_PX && o.outHeight / (sample * 2) >= THUMB_PX) sample *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath(), d);
            if (bmp == null) return null;
            int rotation = 0;
            try (InputStream in = new FileInputStream(file)) {
                int orientation = new ExifInterface(in).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                if (orientation == ExifInterface.ORIENTATION_ROTATE_90) rotation = 90;
                else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) rotation = 180;
                else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) rotation = 270;
            } catch (Exception ignored) {
                // No EXIF.
            }
            if (rotation == 0) return bmp;
            Matrix m = new Matrix();
            m.postRotate(rotation);
            Bitmap out = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (out != bmp) bmp.recycle();
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    private static Bitmap videoThumb(File file) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(file.getAbsolutePath());
            Bitmap frame = r.getFrameAtTime(0);
            if (frame == null) return null;
            float scale = THUMB_PX / (float) Math.max(1, Math.min(frame.getWidth(), frame.getHeight()));
            if (scale >= 1f) return frame;
            Bitmap small = Bitmap.createScaledBitmap(frame,
                    Math.max(1, Math.round(frame.getWidth() * scale)),
                    Math.max(1, Math.round(frame.getHeight() * scale)), true);
            if (small != frame) frame.recycle();
            return small;
        } catch (Throwable t) {
            return null;
        } finally {
            try { r.release(); } catch (Throwable ignored) { }
        }
    }

    // ------------------------------------------------------------------ binding

    private void bindRow(EvItemIncidentBinding h, final Row r) {
        EvidenceStore.IncidentInfo info = r.info;
        long started = info != null && info.startedAt > 0 ? info.startedAt
                : (r.items.isEmpty() ? 0 : r.items.get(0).capturedAt);
        h.title.setText(started > 0 ? EvUi.dateTime(this, started) : getString(R.string.ev_src_unknown));

        SafetyHooks.Incident current = SafetyHooks.current();
        boolean live = current != null && current.id.equals(r.id);
        String duration;
        if (live) duration = getString(R.string.ev_inc_in_progress);
        else if (info != null && info.endedAt > 0 && started > 0) duration = EvUi.duration(this, info.endedAt - started);
        else duration = getString(R.string.ev_inc_duration_unknown);
        h.meta.setText(getString(R.string.ev_inc_meta, EvUi.sourceLabel(this, info == null ? null : info.source), duration));

        h.silentChip.setVisibility(info != null && info.silent ? View.VISIBLE : View.GONE);
        h.duressChip.setVisibility(info != null && info.duress ? View.VISIBLE : View.GONE);
        h.counts.setText(getString(R.string.ev_counts,
                getResources().getQuantityString(R.plurals.ev_count_photos, r.photos, r.photos),
                getResources().getQuantityString(R.plurals.ev_count_audio, r.audio, r.audio),
                getResources().getQuantityString(R.plurals.ev_count_videos, r.videos, r.videos)));

        int total = r.items.size();
        @ColorRes int dot;
        if (total == 0) {
            h.uploadText.setText(R.string.ev_upload_none);
            dot = R.color.ns_text_faint;
        } else if (TextUtils.isEmpty(BuildConfig.API_BASE_URL) && r.uploaded == 0) {
            h.uploadText.setText(getString(R.string.ev_upload_local, total));
            dot = R.color.ns_info;
        } else {
            h.uploadText.setText(getString(R.string.ev_upload_status, r.uploaded, total, r.verified));
            dot = r.verified == total ? R.color.ns_safe : R.color.ns_warn;
        }
        ImageViewCompat.setImageTintList(h.uploadDot, ColorStateList.valueOf(ContextCompat.getColor(this, dot)));

        boolean open = expanded.contains(r.id);
        h.details.setVisibility(open ? View.VISIBLE : View.GONE);
        h.expandIcon.setRotation(open ? 180f : 0f);
        ViewCompat.replaceAccessibilityAction(h.header,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
                getString(open ? R.string.ev_cd_collapse : R.string.ev_cd_expand), null);
        h.header.setOnClickListener(v -> {
            if (expanded.contains(r.id)) expanded.remove(r.id); else expanded.add(r.id);
            int pos = rows.indexOf(r);
            if (pos >= 0) adapter.notifyItemChanged(pos);
        });

        h.exportButton.setOnClickListener(v -> export(r));
        h.complaintButton.setOnClickListener(v -> draftComplaint(r));
        h.deleteButton.setOnClickListener(v -> confirmDelete(r));

        h.itemsContainer.removeAllViews();
        h.noItems.setVisibility(open && r.items.isEmpty() ? View.VISIBLE : View.GONE);
        if (!open) return;
        LayoutInflater inflater = LayoutInflater.from(this);
        for (EvidenceStore.Item it : r.items) {
            EvItemEvidenceBinding ib = EvItemEvidenceBinding.inflate(inflater, h.itemsContainer, false);
            bindItem(ib, it);
            h.itemsContainer.addView(ib.getRoot());
        }
    }

    private void bindItem(final EvItemEvidenceBinding ib, final EvidenceStore.Item it) {
        boolean audio = EvidenceStore.KIND_AUDIO.equals(it.kind);
        boolean video = EvidenceStore.KIND_VIDEO.equals(it.kind);
        boolean exists = it.file != null && it.file.exists();

        ib.title.setText(getString(R.string.ev_item_title, EvUi.kindLabel(this, it.kind),
                EvUi.timeWithSeconds(this, it.capturedAt)));
        String status;
        if (!exists) status = getString(R.string.ev_item_missing);
        else if (it.uploaded) status = getString(it.verified ? R.string.ev_item_verified : R.string.ev_item_unverified);
        else if (TextUtils.isEmpty(BuildConfig.API_BASE_URL)) status = getString(R.string.ev_item_local);
        else status = getString(R.string.ev_item_pending);
        ib.status.setText(getString(R.string.ev_inc_meta, EvUi.size(this, it.size), status));
        ib.status.setTextColor(ContextCompat.getColor(this,
                !exists ? R.color.ns_danger : it.verified ? R.color.ns_safe : R.color.ns_text_faint));
        ib.hash.setText(EvUi.shortHash(this, it.sha256));
        ib.copyButton.setOnClickListener(v -> copyHash(it));
        ib.hash.setOnClickListener(v -> copyHash(it));

        if (audio) {
            ib.thumbCard.setVisibility(View.GONE);
            ib.playButton.setVisibility(View.VISIBLE);
            ib.progress.setVisibility(View.VISIBLE);
            ib.progress.setProgress(0);
            audioRows.put(it.id, ib);
            refreshAudioButton(it.id);
            ib.playButton.setEnabled(exists);
            ib.playButton.setOnClickListener(v -> togglePlay(it));
            ib.row.setOnClickListener(v -> togglePlay(it));
            if (it.id == playingId) updateProgress();
        } else {
            ib.thumbCard.setVisibility(View.VISIBLE);
            ib.playButton.setVisibility(View.GONE);
            ib.progress.setVisibility(View.GONE);
            ib.kindIcon.setImageResource(video ? R.drawable.ev_ic_play : R.drawable.ua_ic_camera);
            ib.kindIcon.setVisibility(video || !exists ? View.VISIBLE : View.GONE);
            loadThumb(it, ib.thumb);
            ib.row.setOnClickListener(v -> openItem(it));
        }
    }

    private void snack(CharSequence text) {
        Snackbar.make(b.getRoot(), text, Snackbar.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ adapter

    private final class Adapter extends RecyclerView.Adapter<Adapter.Holder> {

        final class Holder extends RecyclerView.ViewHolder {
            final EvItemIncidentBinding vb;

            Holder(EvItemIncidentBinding vb) {
                super(vb.getRoot());
                this.vb = vb;
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(EvItemIncidentBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            bindRow(h.vb, rows.get(position));
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }
}
