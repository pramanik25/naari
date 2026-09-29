package com.example.naarishakti.evidence;

import android.Manifest;
import android.app.DatePickerDialog;
import android.app.Dialog;
import android.app.TimePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.location.Location;
import android.location.LocationManager;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.naarishakti.R;
import com.example.naarishakti.core.ProtectionController;
import com.example.naarishakti.databinding.EvActivityDiaryBinding;
import com.example.naarishakti.databinding.EvDialogDiaryEntryBinding;
import com.example.naarishakti.databinding.EvItemDiaryBinding;
import com.example.naarishakti.security.PinActivity;
import com.example.naarishakti.security.PinStore;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Private incident journal. Entries (and attached photos) are encrypted on the phone; the screen
 * is gated by the app PIN when one is set, hidden from screenshots/recents, and re-locks after a
 * while in the background. A duress PIN opens an empty, memory-only diary and fires a silent SOS.
 */
public class DiaryActivity extends AppCompatActivity {

    private static final String TAG = "DiaryActivity";
    private static final int REQ_PIN = 7101;
    private static final int REQ_PHOTO = 7102;
    private static final long RELOCK_AFTER_MS = 2 * 60 * 1000L;
    private static final String STATE_UNLOCKED = "unlocked";
    private static final String STATE_DECOY = "decoy";
    private static final int PREVIEW_PX = 1080;
    private static final int PDF_PHOTO_PX = 1000;

    /**
     * Process-scoped: true while a diary screen is unlocked. Saved instance state alone is not
     * trusted, so a process restored after being killed asks for the PIN again.
     */
    private static volatile boolean sessionUnlocked;

    private EvActivityDiaryBinding b;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<DiaryStore.Entry> entries = new ArrayList<>();
    private Adapter adapter;
    private DiaryStore store;
    private boolean unlocked;
    private boolean decoy;
    private boolean pinPending;
    private boolean externalPending;
    private long backgroundAt;

    // Editor
    @Nullable private Dialog editor;
    @Nullable private EvDialogDiaryEntryBinding eb;
    @Nullable private DiaryStore.Entry editing;
    private long editWhen;
    @Nullable private String editPhoto;
    @Nullable private String editOriginalPhoto;
    private final List<String> editNewPhotos = new ArrayList<>();
    private boolean editSaved;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        b = EvActivityDiaryBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        b.backButton.setOnClickListener(v -> finish());
        b.addButton.setOnClickListener(v -> openEditor(null));
        b.exportButton.setOnClickListener(v -> exportPdf());
        b.unlockButton.setOnClickListener(v -> requestPin());
        adapter = new Adapter();
        b.list.setLayoutManager(new LinearLayoutManager(this));
        b.list.setAdapter(adapter);
        io.execute(() -> EvUi.cleanupExports(getApplicationContext()));

        if (savedInstanceState != null && savedInstanceState.getBoolean(STATE_UNLOCKED) && sessionUnlocked) {
            // Rotation etc.: stay unlocked; a decoy stays a (now empty) decoy.
            unlock(savedInstanceState.getBoolean(STATE_DECOY));
        } else {
            gate();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (unlocked && !decoy && !externalPending && backgroundAt > 0
                && SystemClock.elapsedRealtime() - backgroundAt > RELOCK_AFTER_MS && pinIsSet()) {
            lock();
            requestPin();
        }
        backgroundAt = 0;
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!isChangingConfigurations()) backgroundAt = SystemClock.elapsedRealtime();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_UNLOCKED, unlocked);
        outState.putBoolean(STATE_DECOY, decoy);
    }

    @Override
    protected void onDestroy() {
        if (editor != null && editor.isShowing()) editor.dismiss();
        editor = null;
        if (isFinishing()) sessionUnlocked = false;
        main.removeCallbacksAndMessages(null);
        io.shutdown();
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PIN) {
            pinPending = false;
            String result = resultCode == RESULT_OK && data != null
                    ? data.getStringExtra(PinActivity.EXTRA_RESULT) : null;
            if ("ok".equals(result)) {
                unlock(false);
            } else if ("duress".equals(result)) {
                // She is being forced to open it: show an empty diary and raise a covert SOS.
                unlock(true);
                ProtectionController.triggerPanic(this, "duress", true);
            } else {
                finish();
            }
        } else if (requestCode == REQ_PHOTO) {
            externalPending = false;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) importPhoto(data.getData());
        }
    }

    // ------------------------------------------------------------------ lock

    private void gate() {
        if (pinIsSet()) {
            lock();
            requestPin();
        } else {
            unlock(false);
        }
    }

    private boolean pinIsSet() {
        try {
            return PinStore.isSet(this);
        } catch (Throwable t) {
            Log.e(TAG, "PinStore unavailable", t);
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private void requestPin() {
        if (pinPending) return;
        try {
            startActivityForResult(PinActivity.verifyIntent(this, getString(R.string.ev_diary_pin_title)), REQ_PIN);
            pinPending = true;
        } catch (Throwable t) {
            // No PIN screen available: never show a locked diary's contents without it.
            Log.e(TAG, "Can't open the PIN screen", t);
            EvUi.toast(this, R.string.ev_diary_load_error);
            finish();
        }
    }

    private void lock() {
        unlocked = false;
        sessionUnlocked = false;
        if (editor != null && editor.isShowing()) editor.dismiss();
        entries.clear();
        adapter.notifyDataSetChanged();
        b.actions.setVisibility(View.GONE);
        b.list.setVisibility(View.GONE);
        b.emptyState.setVisibility(View.GONE);
        b.loading.setVisibility(View.GONE);
        b.lockedState.setVisibility(View.VISIBLE);
    }

    private void unlock(boolean asDecoy) {
        unlocked = true;
        sessionUnlocked = true;
        decoy = asDecoy;
        store = asDecoy ? DiaryStore.decoy(this) : DiaryStore.open(this);
        b.lockedState.setVisibility(View.GONE);
        b.actions.setVisibility(View.VISIBLE);
        load();
    }

    // ------------------------------------------------------------------ data

    private void load() {
        b.loading.setVisibility(View.VISIBLE);
        final DiaryStore s = store;
        io.execute(() -> {
            List<DiaryStore.Entry> loaded;
            boolean failed = false;
            try {
                loaded = s.load();
            } catch (Exception e) {
                Log.e(TAG, "Loading diary failed", e);
                loaded = new ArrayList<>();
                failed = true;
            }
            final List<DiaryStore.Entry> result = loaded;
            final boolean error = failed;
            main.post(() -> {
                if (isFinishing() || isDestroyed() || s != store || !unlocked) return;
                entries.clear();
                entries.addAll(result);
                adapter.notifyDataSetChanged();
                b.loading.setVisibility(View.GONE);
                renderState();
                if (error) snack(getString(R.string.ev_diary_load_error));
            });
        });
    }

    private void renderState() {
        boolean empty = entries.isEmpty();
        b.emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        b.list.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    /** Persists the current list; on failure reloads what is on disk. */
    private void persist(final Runnable onSuccess) {
        final DiaryStore s = store;
        final List<DiaryStore.Entry> snapshot = new ArrayList<>(entries);
        io.execute(() -> {
            boolean ok;
            try {
                s.save(snapshot);
                ok = true;
            } catch (Exception e) {
                Log.e(TAG, "Saving diary failed", e);
                ok = false;
            }
            final boolean saved = ok;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (saved) {
                    onSuccess.run();
                } else {
                    snack(getString(R.string.ev_diary_save_error));
                    load();
                }
            });
        });
    }

    private void confirmDelete(final DiaryStore.Entry e) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.ev_diary_delete_title)
                .setMessage(R.string.ev_diary_delete_body)
                .setPositiveButton(R.string.ev_delete, (d, w) -> {
                    entries.remove(e);
                    adapter.notifyDataSetChanged();
                    renderState();
                    final String photo = e.photo;
                    final DiaryStore s = store;
                    persist(() -> {
                        if (photo != null) io.execute(() -> s.deletePhoto(photo));
                        snack(getString(R.string.ev_diary_deleted));
                    });
                })
                .setNegativeButton(R.string.ev_cancel, null)
                .show();
    }

    // ------------------------------------------------------------------ editor

    private void openEditor(@Nullable final DiaryStore.Entry entry) {
        if (!unlocked || (editor != null && editor.isShowing())) return;
        final EvDialogDiaryEntryBinding vb = EvDialogDiaryEntryBinding.inflate(getLayoutInflater());
        final Dialog dialog = new Dialog(this, R.style.Theme_NaariShakti);
        dialog.setContentView(vb.getRoot());
        Window w = dialog.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        editing = entry;
        editWhen = entry != null ? entry.occurredAt : System.currentTimeMillis();
        editPhoto = entry != null ? entry.photo : null;
        editOriginalPhoto = editPhoto;
        editNewPhotos.clear();
        editSaved = false;

        vb.editorTitle.setText(entry == null ? R.string.ev_diary_new_title : R.string.ev_diary_edit_title);
        if (entry != null) {
            vb.titleInput.setText(entry.title);
            vb.bodyInput.setText(entry.body);
            vb.locationInput.setText(entry.location);
        }
        vb.whenButton.setText(EvUi.dateTime(this, editWhen));
        vb.whenButton.setOnClickListener(v -> pickWhen(vb));
        vb.useLocationButton.setOnClickListener(v -> fillLocation(vb));
        vb.attachButton.setOnClickListener(v -> pickPhoto());
        vb.removePhotoButton.setOnClickListener(v -> {
            editPhoto = null;
            renderPhoto(vb);
        });
        vb.closeButton.setOnClickListener(v -> dialog.dismiss());
        vb.saveButton.setOnClickListener(v -> saveEditor(vb, dialog));
        vb.titleInput.setOnFocusChangeListener((v, has) -> vb.titleLayout.setError(null));
        dialog.setOnDismissListener(d -> {
            if (!editSaved) {
                // Discard photos imported during this edit that won't be kept.
                final DiaryStore s = store;
                final List<String> drop = new ArrayList<>(editNewPhotos);
                io.execute(() -> {
                    for (String name : drop) s.deletePhoto(name);
                });
            }
            editNewPhotos.clear();
            if (editor == dialog) {
                editor = null;
                eb = null;
            }
        });
        renderPhoto(vb);
        editor = dialog;
        eb = vb;
        dialog.show();
    }

    private void saveEditor(EvDialogDiaryEntryBinding vb, Dialog dialog) {
        String title = text(vb.titleInput.getText());
        if (TextUtils.isEmpty(title)) {
            vb.titleLayout.setError(getString(R.string.ev_diary_title_required));
            vb.titleInput.requestFocus();
            return;
        }
        long now = System.currentTimeMillis();
        DiaryStore.Entry e = editing;
        if (e == null) {
            e = new DiaryStore.Entry();
            e.id = UUID.randomUUID().toString();
            e.createdAt = now;
            entries.add(e);
        }
        e.occurredAt = editWhen;
        e.title = title;
        e.body = text(vb.bodyInput.getText());
        String place = text(vb.locationInput.getText());
        e.location = TextUtils.isEmpty(place) ? null : place;
        e.photo = editPhoto;
        e.updatedAt = now;

        final List<String> drop = new ArrayList<>();
        for (String name : editNewPhotos) if (!name.equals(editPhoto)) drop.add(name);
        if (editOriginalPhoto != null && !editOriginalPhoto.equals(editPhoto)) drop.add(editOriginalPhoto);

        java.util.Collections.sort(entries, (x, y) -> Long.compare(y.occurredAt, x.occurredAt));
        adapter.notifyDataSetChanged();
        renderState();
        editSaved = true;
        dialog.dismiss();
        final DiaryStore s = store;
        persist(() -> {
            io.execute(() -> {
                for (String name : drop) s.deletePhoto(name);
            });
            snack(getString(R.string.ev_diary_saved));
        });
    }

    private void pickWhen(final EvDialogDiaryEntryBinding vb) {
        final Calendar c = Calendar.getInstance();
        c.setTimeInMillis(editWhen);
        DatePickerDialog dp = new DatePickerDialog(this, (view, y, m, d) -> {
            c.set(Calendar.YEAR, y);
            c.set(Calendar.MONTH, m);
            c.set(Calendar.DAY_OF_MONTH, d);
            new TimePickerDialog(this, (tv, hour, minute) -> {
                c.set(Calendar.HOUR_OF_DAY, hour);
                c.set(Calendar.MINUTE, minute);
                c.set(Calendar.SECOND, 0);
                c.set(Calendar.MILLISECOND, 0);
                editWhen = c.getTimeInMillis();
                vb.whenButton.setText(EvUi.dateTime(this, editWhen));
            }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), DateFormat.is24HourFormat(this)).show();
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
        dp.getDatePicker().setMaxDate(System.currentTimeMillis() + 24L * 60 * 60 * 1000);
        dp.show();
    }

    private void fillLocation(EvDialogDiaryEntryBinding vb) {
        boolean fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        Location best = null;
        if (fine || coarse) {
            try {
                LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
                if (lm != null) {
                    for (String p : lm.getProviders(true)) {
                        Location l = lm.getLastKnownLocation(p);
                        if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                    }
                }
            } catch (SecurityException e) {
                best = null;
            }
        }
        if (best == null) {
            snack(getString(R.string.ev_diary_no_location));
            return;
        }
        vb.locationInput.setText(String.format(Locale.US, getString(R.string.ev_latlng),
                best.getLatitude(), best.getLongitude()));
    }

    @SuppressWarnings("deprecation")
    private void pickPhoto() {
        Intent pick = new Intent(Intent.ACTION_GET_CONTENT)
                .setType("image/*")
                .addCategory(Intent.CATEGORY_OPENABLE);
        try {
            externalPending = true;
            startActivityForResult(pick, REQ_PHOTO);
        } catch (ActivityNotFoundException e) {
            externalPending = false;
            snack(getString(R.string.ev_no_app));
        }
    }

    private void importPhoto(final Uri uri) {
        final EvDialogDiaryEntryBinding vb = eb;
        if (vb == null || store == null) return;
        vb.photoCard.setVisibility(View.VISIBLE);
        vb.photoLoading.setVisibility(View.VISIBLE);
        final DiaryStore s = store;
        io.execute(() -> {
            String name = null;
            int error = 0;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new java.io.FileNotFoundException(String.valueOf(uri));
                name = s.putPhoto(in);
            } catch (DiaryStore.TooLargeException e) {
                error = R.string.ev_diary_photo_too_big;
            } catch (Exception e) {
                Log.e(TAG, "Photo import failed", e);
                error = R.string.ev_diary_photo_error;
            }
            final String imported = name;
            final int err = error;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (imported == null) {
                    if (eb == vb) {
                        vb.photoLoading.setVisibility(View.GONE);
                        renderPhoto(vb);
                    }
                    snack(getString(err));
                    return;
                }
                if (eb != vb) {
                    // Editor closed meanwhile: drop the orphan.
                    io.execute(() -> s.deletePhoto(imported));
                    return;
                }
                editNewPhotos.add(imported);
                editPhoto = imported;
                renderPhoto(vb);
            });
        });
    }

    private void renderPhoto(final EvDialogDiaryEntryBinding vb) {
        final String name = editPhoto;
        vb.removePhotoButton.setVisibility(name == null ? View.GONE : View.VISIBLE);
        vb.attachButton.setText(name == null ? R.string.ev_diary_attach : R.string.ev_diary_replace_photo);
        if (name == null) {
            vb.photoCard.setVisibility(View.GONE);
            vb.photo.setImageDrawable(null);
            return;
        }
        vb.photoCard.setVisibility(View.VISIBLE);
        vb.photoLoading.setVisibility(View.VISIBLE);
        final DiaryStore s = store;
        io.execute(() -> {
            final Bitmap bmp = decode(s.readPhoto(name), PREVIEW_PX);
            main.post(() -> {
                if (eb != vb || !name.equals(editPhoto)) return;
                vb.photoLoading.setVisibility(View.GONE);
                vb.photo.setImageBitmap(bmp);
            });
        });
    }

    // ------------------------------------------------------------------ export

    private void exportPdf() {
        if (entries.isEmpty()) {
            snack(getString(R.string.ev_diary_pdf_empty));
            return;
        }
        final List<DiaryStore.Entry> snapshot = new ArrayList<>(entries);
        final DiaryStore s = store;
        final Context ctx = this;
        io.execute(() -> {
            File out = null;
            PdfComposer pdf = new PdfComposer(ctx, getString(R.string.ev_pdf_confidential));
            try {
                long now = System.currentTimeMillis();
                pdf.title(getString(R.string.ev_diary_title));
                pdf.muted(getString(R.string.ev_diary_pdf_subtitle, EvUi.dateTime(ctx, now), snapshot.size()));
                pdf.rule();
                for (DiaryStore.Entry e : snapshot) {
                    pdf.muted(EvUi.dateTime(ctx, e.occurredAt));
                    pdf.heading(TextUtils.isEmpty(e.title) ? getString(R.string.ev_diary_untitled) : e.title);
                    if (!TextUtils.isEmpty(e.location)) {
                        pdf.muted(getString(R.string.ev_diary_pdf_location, e.location));
                    }
                    if (!TextUtils.isEmpty(e.body)) pdf.body(e.body);
                    if (e.photo != null) {
                        Bitmap bmp = decode(s.readPhoto(e.photo), PDF_PHOTO_PX);
                        if (bmp != null) {
                            pdf.image(bmp, 320f);
                            bmp.recycle();
                        }
                    }
                    pdf.muted(getString(R.string.ev_diary_pdf_written, EvUi.dateTime(ctx, e.createdAt)));
                    pdf.rule();
                }
                String stamp = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date(now));
                out = pdf.finish(new File(EvUi.exportsDir(ctx), "naari_diary_" + stamp + ".pdf"));
            } catch (Throwable t) {
                Log.e(TAG, "Diary PDF failed", t);
                pdf.abandon();
                out = null;
            }
            final File file = out;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (file == null) {
                    snack(getString(R.string.ev_pdf_failed));
                    return;
                }
                EvUi.shareFile(this, file, "application/pdf", R.string.ev_diary_pdf_chooser,
                        getString(R.string.ev_diary_title));
            });
        });
    }

    // ------------------------------------------------------------------ helpers

    @Nullable
    private static Bitmap decode(@Nullable byte[] bytes, int reqPx) {
        if (bytes == null || bytes.length == 0) return null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= reqPx && o.outHeight / (sample * 2) >= reqPx) sample *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, d);
            if (bmp == null) return null;
            int rotation = 0;
            try {
                int orientation = new ExifInterface(new ByteArrayInputStream(bytes)).getAttributeInt(
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

    private static String text(@Nullable CharSequence cs) {
        return cs == null ? "" : cs.toString().trim();
    }

    private void snack(CharSequence text) {
        View anchor = editor != null && editor.isShowing() && eb != null ? eb.getRoot() : b.getRoot();
        Snackbar.make(anchor, text, Snackbar.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ adapter

    private final class Adapter extends RecyclerView.Adapter<Adapter.Holder> {

        final class Holder extends RecyclerView.ViewHolder {
            final EvItemDiaryBinding vb;

            Holder(EvItemDiaryBinding vb) {
                super(vb.getRoot());
                this.vb = vb;
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(EvItemDiaryBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            final DiaryStore.Entry e = entries.get(position);
            h.vb.when.setText(EvUi.dateTime(DiaryActivity.this, e.occurredAt));
            h.vb.title.setText(TextUtils.isEmpty(e.title) ? getString(R.string.ev_diary_untitled) : e.title);
            h.vb.preview.setText(e.body);
            h.vb.preview.setVisibility(TextUtils.isEmpty(e.body) ? View.GONE : View.VISIBLE);
            h.vb.place.setText(e.location);
            h.vb.placeRow.setVisibility(TextUtils.isEmpty(e.location) ? View.GONE : View.VISIBLE);
            h.vb.photoRow.setVisibility(e.photo == null ? View.GONE : View.VISIBLE);
            h.vb.getRoot().setOnClickListener(v -> openEditor(e));
            h.vb.deleteButton.setOnClickListener(v -> confirmDelete(e));
        }

        @Override
        public int getItemCount() {
            return entries.size();
        }
    }
}
