package Home_Activity;

import android.app.Dialog;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.util.Log;
import android.util.LruCache;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.naarishakti.R;
import com.example.naarishakti.databinding.ActivityDatabaseViewBinding;
import com.example.naarishakti.databinding.UaDialogPhotoBinding;
import com.example.naarishakti.databinding.UaItemEvidenceBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import SQLite_Database.ProfileDbHelper;

/**
 * Evidence vault: grid of photos captured automatically during an SOS. Thumbnails are decoded
 * downsampled off the main thread (from the DB blob, or the file path as a fallback) and cached.
 */
public class DatabaseViewActivity extends AppCompatActivity {

    private static final String TAG = "EvidenceVault";
    private static final int THUMB_PX = 420;
    private static final int FULL_PX = 1600;

    /** One captured photo (metadata only; pixels are loaded lazily). */
    static final class Evidence {
        long id;
        @Nullable String type;
        long timestamp;
        @Nullable String path;
    }

    private ActivityDatabaseViewBinding b;
    private ProfileDbHelper helper;
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Evidence> items = new ArrayList<>();
    private LruCache<Long, Bitmap> thumbCache;
    private EvidenceAdapter adapter;
    @Nullable private Dialog viewer;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityDatabaseViewBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        helper = new ProfileDbHelper(getApplicationContext());
        int cacheKb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 8);
        thumbCache = new LruCache<Long, Bitmap>(cacheKb) {
            @Override
            protected int sizeOf(Long key, Bitmap value) {
                return Math.max(1, value.getByteCount() / 1024);
            }
        };

        b.backButton.setOnClickListener(v -> finish());
        adapter = new EvidenceAdapter();
        b.evidenceList.setLayoutManager(new GridLayoutManager(this, 2));
        b.evidenceList.setAdapter(adapter);

        loadEvidence();
    }

    @Override
    protected void onDestroy() {
        if (viewer != null && viewer.isShowing()) viewer.dismiss();
        viewer = null;
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
        if (thumbCache != null) thumbCache.evictAll();
        if (helper != null) helper.close();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ data

    private void loadEvidence() {
        b.loading.setVisibility(View.VISIBLE);
        io.execute(() -> {
            final List<Evidence> loaded = new ArrayList<>();
            boolean failed = false;
            try {
                SQLiteDatabase db = helper.getReadableDatabase();
                try (Cursor c = db.query(ProfileDbHelper.TABLE_EMERGENCY_IMAGES,
                        new String[]{ProfileDbHelper.COLUMN_IMAGE_ID, ProfileDbHelper.COLUMN_IMAGE_TYPE,
                                ProfileDbHelper.COLUMN_TIMESTAMP, ProfileDbHelper.COLUMN_IMAGE_PATH},
                        null, null, null, null,
                        ProfileDbHelper.COLUMN_TIMESTAMP + " DESC, " + ProfileDbHelper.COLUMN_IMAGE_ID + " DESC")) {
                    while (c.moveToNext()) {
                        Evidence e = new Evidence();
                        e.id = c.getLong(0);
                        e.type = c.isNull(1) ? null : c.getString(1);
                        e.timestamp = c.isNull(2) ? 0L : c.getLong(2);
                        e.path = c.isNull(3) ? null : c.getString(3);
                        loaded.add(e);
                    }
                }
            } catch (Exception ex) {
                Log.e(TAG, "Unable to read evidence", ex);
                failed = true;
            }
            final boolean error = failed;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                items.clear();
                items.addAll(loaded);
                adapter.notifyDataSetChanged();
                b.loading.setVisibility(View.GONE);
                renderState();
                if (error) snack(getString(R.string.vault_load_error));
            });
        });
    }

    private void renderState() {
        int count = items.size();
        b.emptyState.setVisibility(count == 0 ? View.VISIBLE : View.GONE);
        b.evidenceList.setVisibility(count == 0 ? View.GONE : View.VISIBLE);
        b.countText.setVisibility(count == 0 ? View.GONE : View.VISIBLE);
        b.countText.setText(getResources().getQuantityString(R.plurals.vault_count, count, count));
    }

    /** Loads pixels for one photo: DB blob first, then the saved file path. Runs off the main thread. */
    @Nullable
    private Bitmap decodeEvidence(Evidence e, int reqPx) {
        byte[] bytes = null;
        try {
            SQLiteDatabase db = helper.getReadableDatabase();
            try (Cursor c = db.query(ProfileDbHelper.TABLE_EMERGENCY_IMAGES,
                    new String[]{ProfileDbHelper.COLUMN_IMAGE_DATA},
                    ProfileDbHelper.COLUMN_IMAGE_ID + "=?", new String[]{String.valueOf(e.id)},
                    null, null, null)) {
                if (c.moveToFirst() && !c.isNull(0)) bytes = c.getBlob(0);
            }
        } catch (Exception ex) {
            // Blob missing or larger than the cursor window: fall back to the file.
            Log.w(TAG, "Blob unavailable for evidence " + e.id, ex);
        }
        try {
            if (bytes != null && bytes.length > 0) {
                BitmapFactory.Options o = boundsOptions();
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
                o.inSampleSize = sampleSize(o, reqPx);
                o.inJustDecodeBounds = false;
                Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
                return rotate(bmp, exifRotation(new ByteArrayInputStream(bytes)));
            }
            if (e.path != null) {
                File file = new File(e.path);
                if (file.exists()) {
                    BitmapFactory.Options o = boundsOptions();
                    BitmapFactory.decodeFile(file.getAbsolutePath(), o);
                    o.inSampleSize = sampleSize(o, reqPx);
                    o.inJustDecodeBounds = false;
                    Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath(), o);
                    int rotation = 0;
                    try (InputStream in = new java.io.FileInputStream(file)) {
                        rotation = exifRotation(in);
                    } catch (Exception ignored) {
                        // No EXIF.
                    }
                    return rotate(bmp, rotation);
                }
            }
        } catch (OutOfMemoryError oom) {
            Log.w(TAG, "Out of memory decoding evidence " + e.id);
        } catch (Exception ex) {
            Log.w(TAG, "Unable to decode evidence " + e.id, ex);
        }
        return null;
    }

    private static BitmapFactory.Options boundsOptions() {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        return o;
    }

    private static int sampleSize(BitmapFactory.Options o, int reqPx) {
        int sample = 1;
        while (o.outWidth / (sample * 2) >= reqPx && o.outHeight / (sample * 2) >= reqPx) sample *= 2;
        return sample;
    }

    private static int exifRotation(InputStream in) {
        try {
            int orientation = new ExifInterface(in).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (orientation == ExifInterface.ORIENTATION_ROTATE_90) return 90;
            if (orientation == ExifInterface.ORIENTATION_ROTATE_180) return 180;
            if (orientation == ExifInterface.ORIENTATION_ROTATE_270) return 270;
        } catch (Exception ignored) {
            // Not a JPEG / no EXIF.
        }
        return 0;
    }

    @Nullable
    private static Bitmap rotate(@Nullable Bitmap bmp, int degrees) {
        if (bmp == null || degrees == 0) return bmp;
        Matrix m = new Matrix();
        m.postRotate(degrees);
        Bitmap out = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
        if (out != bmp) bmp.recycle();
        return out;
    }

    // ------------------------------------------------------------------ delete

    private void confirmDelete(final Evidence e) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.vault_delete_title)
                .setMessage(R.string.vault_delete_body)
                .setPositiveButton(R.string.action_delete, (d, w) -> delete(e))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void delete(final Evidence e) {
        io.execute(() -> {
            boolean ok = false;
            try {
                SQLiteDatabase db = helper.getWritableDatabase();
                ok = db.delete(ProfileDbHelper.TABLE_EMERGENCY_IMAGES,
                        ProfileDbHelper.COLUMN_IMAGE_ID + "=?", new String[]{String.valueOf(e.id)}) > 0;
                if (ok && e.path != null) {
                    File f = new File(e.path);
                    if (f.exists() && !f.delete()) Log.w(TAG, "Could not delete file " + e.path);
                }
            } catch (Exception ex) {
                Log.e(TAG, "Unable to delete evidence " + e.id, ex);
            }
            final boolean deleted = ok;
            main.post(() -> {
                if (isFinishing() || isDestroyed() || !deleted) return;
                int index = items.indexOf(e);
                if (index >= 0) {
                    items.remove(index);
                    adapter.notifyItemRemoved(index);
                }
                thumbCache.remove(e.id);
                if (viewer != null && viewer.isShowing()) viewer.dismiss();
                renderState();
                snack(getString(R.string.vault_deleted));
            });
        });
    }

    // ------------------------------------------------------------------ viewer

    private void openViewer(final Evidence e) {
        if (viewer != null && viewer.isShowing()) return;
        final UaDialogPhotoBinding vb = UaDialogPhotoBinding.inflate(getLayoutInflater());
        final Dialog dialog = new Dialog(this, R.style.Theme_NaariShakti);
        dialog.setContentView(vb.getRoot());
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setWindowAnimations(android.R.style.Animation_Dialog);
        }
        vb.photoTitle.setText(typeLabel(e.type));
        vb.photoTime.setText(formatTime(this, e.timestamp, true));
        vb.closeButton.setOnClickListener(v -> dialog.dismiss());
        vb.photoDelete.setOnClickListener(v -> confirmDelete(e));
        dialog.setOnDismissListener(d -> {
            if (viewer == dialog) viewer = null;
        });

        Bitmap cached = thumbCache.get(e.id);
        if (cached != null) vb.photo.setImageBitmap(cached);
        io.execute(() -> {
            final Bitmap full = decodeEvidence(e, FULL_PX);
            main.post(() -> {
                if (!dialog.isShowing()) return;
                vb.photoLoading.setVisibility(View.GONE);
                if (full != null) vb.photo.setImageBitmap(full);
            });
        });

        viewer = dialog;
        dialog.show();
    }

    // ------------------------------------------------------------------ formatting

    private String typeLabel(@Nullable String type) {
        if (type != null) {
            String t = type.toLowerCase(Locale.ROOT);
            if (t.contains("front")) return getString(R.string.vault_front);
            if (t.contains("back") || t.contains("rear")) return getString(R.string.vault_back);
        }
        return getString(R.string.vault_photo);
    }

    private static String formatTime(Context c, long ts, boolean full) {
        if (ts <= 0) return c.getString(R.string.vault_unknown_time);
        int flags = full
                ? DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR
                | DateUtils.FORMAT_ABBREV_ALL
                : DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH | DateUtils.FORMAT_NO_YEAR;
        return DateUtils.formatDateTime(c, ts, flags) + " · " + DateFormat.getTimeFormat(c).format(ts);
    }

    private void snack(CharSequence text) {
        Snackbar.make(b.getRoot(), text, Snackbar.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ adapter

    private final class EvidenceAdapter extends RecyclerView.Adapter<EvidenceAdapter.Holder> {

        final class Holder extends RecyclerView.ViewHolder {
            final UaItemEvidenceBinding vb;

            Holder(UaItemEvidenceBinding vb) {
                super(vb.getRoot());
                this.vb = vb;
            }
        }

        EvidenceAdapter() {
            setHasStableIds(true);
        }

        @Override
        public long getItemId(int position) {
            return items.get(position).id;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(UaItemEvidenceBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            final Evidence e = items.get(position);
            String caption = typeLabel(e.type);
            String time = formatTime(DatabaseViewActivity.this, e.timestamp, false);
            h.vb.caption.setText(caption);
            h.vb.time.setText(time);
            h.vb.getRoot().setContentDescription(caption + ", " + time);

            h.vb.getRoot().setOnClickListener(v -> openViewer(e));
            h.vb.getRoot().setOnLongClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                confirmDelete(e);
                return true;
            });
            h.vb.deleteButton.setOnClickListener(v -> confirmDelete(e));

            final ImageView thumb = h.vb.thumb;
            thumb.setTag(e.id);
            Bitmap cached = thumbCache.get(e.id);
            if (cached != null) {
                thumb.setAlpha(1f);
                thumb.setImageBitmap(cached);
                return;
            }
            thumb.setImageDrawable(null);
            thumb.setAlpha(0f);
            if (io.isShutdown()) return;
            io.execute(() -> {
                final Bitmap bmp = decodeEvidence(e, THUMB_PX);
                if (bmp != null) thumbCache.put(e.id, bmp);
                main.post(() -> {
                    if (!Long.valueOf(e.id).equals(thumb.getTag())) return;
                    if (bmp != null) thumb.setImageBitmap(bmp);
                    thumb.animate().alpha(1f).setDuration(200).start();
                });
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }
}
