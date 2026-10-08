package Home_Activity;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.EditText;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;

import com.example.naarishakti.R;
import com.example.naarishakti.databinding.ActivityProfileBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputLayout;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import SQLite_Database.ProfileDbHelper;

/**
 * Profile editor. Loads the saved profile on open, keeps the picked photo as a Bitmap field (no
 * drawable casting), and remembers the row id after the first insert so later saves update it.
 */
public class ProfileActivity extends AppCompatActivity {

    private static final String TAG = "ProfileActivity";
    private static final int PHOTO_MAX_PX = 512;

    private static final String OCC_STUDENT = "student";
    private static final String OCC_COLLEGE = "college_student";
    private static final String OCC_WORKING = "working";

    private static final Pattern MOBILE = Pattern.compile("\\d{10}");
    private static final Pattern PINCODE = Pattern.compile("[1-9]\\d{5}");

    private ActivityProfileBinding b;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private ActivityResultLauncher<String> pickImage;

    /** Row id of the saved profile, or null before the first save. */
    @Nullable private Long profileId;
    /** Photo picked in this session (null if the user didn't pick one). */
    @Nullable private Bitmap pickedPhoto;
    /** True if the stored profile already has a photo, so we don't wipe it on save. */
    private boolean hasStoredPhoto;
    private String occupation = "";
    private boolean saving;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityProfileBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        pickImage = registerForActivityResult(new ActivityResultContracts.GetContent(), this::onImagePicked);

        b.backButton.setOnClickListener(v -> finish());
        b.infoButton.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setIcon(R.drawable.ua_ic_info)
                .setTitle(R.string.sh_profile_info_title)
                .setMessage(R.string.sh_profile_info_body)
                .setPositiveButton(android.R.string.ok, null)
                .show());
        b.avatarButton.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            pickImage.launch("image/*");
        });

        b.occupationGroup.setOnCheckedStateChangeListener((group, ids) -> {
            int id = ids.isEmpty() ? View.NO_ID : ids.get(0);
            if (id == R.id.chipStudent) occupation = OCC_STUDENT;
            else if (id == R.id.chipCollege) occupation = OCC_COLLEGE;
            else if (id == R.id.chipWorking) occupation = OCC_WORKING;
            else occupation = "";
            renderOccupation();
        });

        clearErrorOnEdit(b.nameLayout);
        clearErrorOnEdit(b.mobileLayout);
        clearErrorOnEdit(b.fatherMobileLayout);
        clearErrorOnEdit(b.pincodeLayout);

        b.saveButton.setOnClickListener(v -> save());
        b.nameEditText.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                renderAvatar();
            }
        });

        renderAvatar();
        loadProfile();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ------------------------------------------------------------------ load

    private void loadProfile() {
        final Context app = getApplicationContext();
        io.execute(() -> {
            ContentValues row = null;
            Bitmap photo = null;
            try (ProfileDbHelper helper = new ProfileDbHelper(app);
                 SQLiteDatabase db = helper.getReadableDatabase()) {
                String[] columns = {
                        ProfileDbHelper.COLUMN_ID, ProfileDbHelper.COLUMN_NAME, ProfileDbHelper.COLUMN_FATHER_NAME,
                        ProfileDbHelper.COLUMN_ADDRESS, ProfileDbHelper.COLUMN_PINCODE,
                        ProfileDbHelper.COLUMN_MOBILE_NUMBER, ProfileDbHelper.COLUMN_FATHER_MOBILE_NUMBER,
                        ProfileDbHelper.COLUMN_OCCUPATION, ProfileDbHelper.COLUMN_SCHOOL_NAME,
                        ProfileDbHelper.COLUMN_CLASS, ProfileDbHelper.COLUMN_COLLEGE_NAME,
                        ProfileDbHelper.COLUMN_DEGREE, ProfileDbHelper.COLUMN_COMPANY_NAME,
                        ProfileDbHelper.COLUMN_DEPARTMENT, ProfileDbHelper.COLUMN_OFFICE_NAME};
                try (Cursor c = db.query(ProfileDbHelper.TABLE_NAME, columns, null, null, null, null,
                        ProfileDbHelper.COLUMN_ID + " DESC", "1")) {
                    if (c.moveToFirst()) {
                        row = new ContentValues();
                        row.put(ProfileDbHelper.COLUMN_ID, c.getLong(0));
                        for (int i = 1; i < columns.length; i++) row.put(columns[i], c.getString(i));
                    }
                }
                if (row != null) {
                    try (Cursor c = db.query(ProfileDbHelper.TABLE_NAME,
                            new String[]{ProfileDbHelper.COLUMN_PROFILE_IMAGE},
                            ProfileDbHelper.COLUMN_ID + "=?",
                            new String[]{String.valueOf(row.getAsLong(ProfileDbHelper.COLUMN_ID))},
                            null, null, null)) {
                        if (c.moveToFirst() && !c.isNull(0)) {
                            byte[] bytes = c.getBlob(0);
                            if (bytes != null && bytes.length > 0) {
                                photo = decodeBytes(bytes, 256);
                                if (photo != null) row.put(ProfileDbHelper.COLUMN_PROFILE_IMAGE, 1);
                            }
                        }
                    } catch (Exception blobError) {
                        // Legacy full-size photos can exceed the cursor window; treat as "has photo".
                        row.put(ProfileDbHelper.COLUMN_PROFILE_IMAGE, 1);
                        Log.w(TAG, "Stored profile photo is unreadable", blobError);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Unable to load profile", e);
            }
            final ContentValues loaded = row;
            final Bitmap loadedPhoto = photo;
            main.post(() -> applyLoaded(loaded, loadedPhoto));
        });
    }

    private void applyLoaded(@Nullable ContentValues row, @Nullable Bitmap photo) {
        if (isFinishing() || isDestroyed()) return;
        if (row == null) {
            b.saveButton.setText(R.string.save_profile);
            return;
        }
        profileId = row.getAsLong(ProfileDbHelper.COLUMN_ID);
        hasStoredPhoto = row.containsKey(ProfileDbHelper.COLUMN_PROFILE_IMAGE);
        setText(b.nameEditText, row.getAsString(ProfileDbHelper.COLUMN_NAME));
        setText(b.fatherNameEditText, row.getAsString(ProfileDbHelper.COLUMN_FATHER_NAME));
        setText(b.addressEditText, row.getAsString(ProfileDbHelper.COLUMN_ADDRESS));
        setText(b.pincodeEditText, row.getAsString(ProfileDbHelper.COLUMN_PINCODE));
        setText(b.mobileNumberEditText, row.getAsString(ProfileDbHelper.COLUMN_MOBILE_NUMBER));
        setText(b.fatherMobileNumberEditText, row.getAsString(ProfileDbHelper.COLUMN_FATHER_MOBILE_NUMBER));
        setText(b.schoolNameEditText, row.getAsString(ProfileDbHelper.COLUMN_SCHOOL_NAME));
        setText(b.classEditText, row.getAsString(ProfileDbHelper.COLUMN_CLASS));
        setText(b.collegeNameEditText, row.getAsString(ProfileDbHelper.COLUMN_COLLEGE_NAME));
        setText(b.degreeEditText, row.getAsString(ProfileDbHelper.COLUMN_DEGREE));
        setText(b.companyNameEditText, row.getAsString(ProfileDbHelper.COLUMN_COMPANY_NAME));
        setText(b.departmentEditText, row.getAsString(ProfileDbHelper.COLUMN_DEPARTMENT));
        setText(b.officeNameEditText, row.getAsString(ProfileDbHelper.COLUMN_OFFICE_NAME));

        String occ = row.getAsString(ProfileDbHelper.COLUMN_OCCUPATION);
        if (OCC_STUDENT.equals(occ)) b.occupationGroup.check(R.id.chipStudent);
        else if (OCC_COLLEGE.equals(occ)) b.occupationGroup.check(R.id.chipCollege);
        else if (OCC_WORKING.equals(occ)) b.occupationGroup.check(R.id.chipWorking);

        if (pickedPhoto == null && photo != null) showPhoto(photo);
        renderAvatar();
        b.saveButton.setText(R.string.profile_update);
    }

    private static void setText(EditText field, @Nullable String value) {
        if (!TextUtils.isEmpty(value) && TextUtils.isEmpty(field.getText())) field.setText(value);
    }

    // ------------------------------------------------------------------ photo

    private void onImagePicked(@Nullable Uri uri) {
        if (uri == null) return;
        final Context app = getApplicationContext();
        io.execute(() -> {
            Bitmap bitmap = null;
            try {
                bitmap = decodeUri(app, uri, PHOTO_MAX_PX);
            } catch (Exception e) {
                Log.e(TAG, "Unable to read picked image", e);
            }
            final Bitmap result = bitmap;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (result == null) {
                    snack(getString(R.string.profile_photo_error));
                    return;
                }
                pickedPhoto = result;
                showPhoto(result);
                renderAvatar();
            });
        });
    }

    private void showPhoto(Bitmap photo) {
        b.avatarImage.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        b.avatarImage.setPadding(0, 0, 0, 0);
        ImageViewCompat.setImageTintList(b.avatarImage, null);
        b.avatarImage.setImageBitmap(photo);
        b.avatarImage.setTag(photo);
        b.avatarImage.animate().alpha(1f).setDuration(200).start();
    }

    /** Initials (or a person glyph) when there's no photo; hint text under the avatar. */
    private void renderAvatar() {
        boolean hasPhoto = b.avatarImage.getTag() instanceof Bitmap;
        b.avatarHint.setText(hasPhoto || hasStoredPhoto ? R.string.profile_change_photo : R.string.profile_add_photo);
        if (hasPhoto) {
            b.avatarInitials.setVisibility(View.GONE);
            return;
        }
        String initials = initials(textOf(b.nameEditText));
        if (initials != null) {
            b.avatarImage.setImageDrawable(null);
            b.avatarInitials.setText(initials);
            b.avatarInitials.setVisibility(View.VISIBLE);
        } else {
            int pad = Math.round(30 * getResources().getDisplayMetrics().density);
            b.avatarImage.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            b.avatarImage.setPadding(pad, pad, pad, pad);
            b.avatarImage.setImageResource(R.drawable.ua_ic_person);
            ImageViewCompat.setImageTintList(b.avatarImage, android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.ns_text_faint)));
            b.avatarInitials.setVisibility(View.GONE);
        }
    }

    @Nullable
    private static String initials(String full) {
        if (full == null || full.trim().isEmpty()) return null;
        String[] parts = full.trim().split("\\s+");
        StringBuilder sb = new StringBuilder().append(Character.toUpperCase(parts[0].charAt(0)));
        if (parts.length > 1) sb.append(Character.toUpperCase(parts[parts.length - 1].charAt(0)));
        return sb.toString();
    }

    private static Bitmap decodeBytes(byte[] bytes, int reqPx) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        o.inSampleSize = sampleSize(o.outWidth, o.outHeight, reqPx);
        o.inJustDecodeBounds = false;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
    }

    /** Decodes a content Uri downsampled to ~maxPx, applies EXIF rotation, and scales to fit maxPx. */
    @Nullable
    private static Bitmap decodeUri(Context ctx, Uri uri, int maxPx) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, o);
        }
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        o.inSampleSize = sampleSize(o.outWidth, o.outHeight, maxPx);
        o.inJustDecodeBounds = false;
        Bitmap bitmap;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            bitmap = BitmapFactory.decodeStream(in, null, o);
        }
        if (bitmap == null) return null;

        int rotation = 0;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in != null) {
                int orientation = new ExifInterface(in).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                if (orientation == ExifInterface.ORIENTATION_ROTATE_90) rotation = 90;
                else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) rotation = 180;
                else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) rotation = 270;
            }
        } catch (Exception ignored) {
            // No EXIF (PNG, screenshots…): keep as is.
        }

        float scale = Math.min(1f, maxPx / (float) Math.max(bitmap.getWidth(), bitmap.getHeight()));
        if (rotation != 0 || scale < 1f) {
            Matrix m = new Matrix();
            m.postScale(scale, scale);
            if (rotation != 0) m.postRotate(rotation);
            Bitmap transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), m, true);
            if (transformed != bitmap) bitmap.recycle();
            bitmap = transformed;
        }
        return bitmap;
    }

    private static int sampleSize(int w, int h, int reqPx) {
        int sample = 1;
        while (w / (sample * 2) >= reqPx && h / (sample * 2) >= reqPx) sample *= 2;
        return sample;
    }

    // ------------------------------------------------------------------ save

    private void save() {
        if (saving) return;
        String name = textOf(b.nameEditText);
        String mobile = normalizeMobile(textOf(b.mobileNumberEditText));
        String guardianMobile = normalizeMobile(textOf(b.fatherMobileNumberEditText));
        String pincode = textOf(b.pincodeEditText);

        TextInputLayout firstError = null;
        if (name.isEmpty()) {
            b.nameLayout.setError(getString(R.string.profile_error_required));
            firstError = b.nameLayout;
        }
        if (!MOBILE.matcher(mobile).matches()) {
            b.mobileLayout.setError(getString(mobile.isEmpty()
                    ? R.string.profile_error_required : R.string.profile_error_mobile));
            if (firstError == null) firstError = b.mobileLayout;
        }
        if (!guardianMobile.isEmpty() && !MOBILE.matcher(guardianMobile).matches()) {
            b.fatherMobileLayout.setError(getString(R.string.profile_error_mobile));
            if (firstError == null) firstError = b.fatherMobileLayout;
        }
        if (!pincode.isEmpty() && !PINCODE.matcher(pincode).matches()) {
            b.pincodeLayout.setError(getString(R.string.profile_error_pincode));
            if (firstError == null) firstError = b.pincodeLayout;
        }
        if (firstError != null) {
            b.saveButton.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            if (firstError.getEditText() != null) firstError.getEditText().requestFocus();
            return;
        }

        final ContentValues values = new ContentValues();
        values.put(ProfileDbHelper.COLUMN_NAME, name);
        values.put(ProfileDbHelper.COLUMN_FATHER_NAME, textOf(b.fatherNameEditText));
        values.put(ProfileDbHelper.COLUMN_ADDRESS, textOf(b.addressEditText));
        values.put(ProfileDbHelper.COLUMN_PINCODE, pincode);
        values.put(ProfileDbHelper.COLUMN_MOBILE_NUMBER, mobile);
        values.put(ProfileDbHelper.COLUMN_FATHER_MOBILE_NUMBER, guardianMobile);
        values.put(ProfileDbHelper.COLUMN_OCCUPATION, occupation);
        values.put(ProfileDbHelper.COLUMN_SCHOOL_NAME, OCC_STUDENT.equals(occupation) ? textOf(b.schoolNameEditText) : "");
        values.put(ProfileDbHelper.COLUMN_CLASS, OCC_STUDENT.equals(occupation) ? textOf(b.classEditText) : "");
        values.put(ProfileDbHelper.COLUMN_COLLEGE_NAME, OCC_COLLEGE.equals(occupation) ? textOf(b.collegeNameEditText) : "");
        values.put(ProfileDbHelper.COLUMN_DEGREE, OCC_COLLEGE.equals(occupation) ? textOf(b.degreeEditText) : "");
        values.put(ProfileDbHelper.COLUMN_COMPANY_NAME, OCC_WORKING.equals(occupation) ? textOf(b.companyNameEditText) : "");
        values.put(ProfileDbHelper.COLUMN_DEPARTMENT, OCC_WORKING.equals(occupation) ? textOf(b.departmentEditText) : "");
        values.put(ProfileDbHelper.COLUMN_OFFICE_NAME, OCC_WORKING.equals(occupation) ? textOf(b.officeNameEditText) : "");

        if (pickedPhoto != null) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            pickedPhoto.compress(Bitmap.CompressFormat.JPEG, 88, out);
            values.put(ProfileDbHelper.COLUMN_PROFILE_IMAGE, out.toByteArray());
        } else if (profileId == null && !hasStoredPhoto) {
            values.putNull(ProfileDbHelper.COLUMN_PROFILE_IMAGE);
        }
        // else: leave the stored photo column untouched on update.

        saving = true;
        b.saveButton.setEnabled(false);
        final Long existingId = profileId;
        final Context app = getApplicationContext();
        io.execute(() -> {
            long id = -1;
            try (ProfileDbHelper helper = new ProfileDbHelper(app);
                 SQLiteDatabase db = helper.getWritableDatabase()) {
                if (existingId != null) {
                    int rows = db.update(ProfileDbHelper.TABLE_NAME, values,
                            ProfileDbHelper.COLUMN_ID + "=?", new String[]{String.valueOf(existingId)});
                    id = rows > 0 ? existingId : -1;
                }
                if (id == -1) {
                    if (!values.containsKey(ProfileDbHelper.COLUMN_PROFILE_IMAGE)) {
                        values.putNull(ProfileDbHelper.COLUMN_PROFILE_IMAGE);
                    }
                    id = db.insert(ProfileDbHelper.TABLE_NAME, null, values);
                }
            } catch (Exception e) {
                Log.e(TAG, "Unable to save profile", e);
                id = -1;
            }
            final long savedId = id;
            main.post(() -> onSaved(savedId));
        });
    }

    private void onSaved(long id) {
        if (isFinishing() || isDestroyed()) return;
        saving = false;
        b.saveButton.setEnabled(true);
        if (id == -1) {
            snack(getString(R.string.profile_save_error));
            return;
        }
        profileId = id;
        if (pickedPhoto != null) hasStoredPhoto = true;
        b.saveButton.setText(R.string.profile_update);
        b.saveButton.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        setResult(RESULT_OK);
        snack(getString(R.string.profile_saved));
        com.example.naarishakti.cloud.ProfileSync.push(this);
    }

    /** Strips spaces/dashes and an optional +91 / 91 / 0 prefix; returns the remaining digits. */
    public static String normalizeMobile(String raw) {
        if (raw == null) return "";
        String s = raw.replaceAll("[\\s\\-()]", "");
        if (s.startsWith("+91")) s = s.substring(3);
        else if (s.startsWith("91") && s.length() == 12) s = s.substring(2);
        else if (s.startsWith("0") && s.length() == 11) s = s.substring(1);
        return s;
    }

    // ------------------------------------------------------------------ helpers

    private void renderOccupation() {
        b.studentFields.setVisibility(OCC_STUDENT.equals(occupation) ? View.VISIBLE : View.GONE);
        b.collegeFields.setVisibility(OCC_COLLEGE.equals(occupation) ? View.VISIBLE : View.GONE);
        b.workingFields.setVisibility(OCC_WORKING.equals(occupation) ? View.VISIBLE : View.GONE);
    }

    private static String textOf(EditText field) {
        return field.getText() == null ? "" : field.getText().toString().trim();
    }

    private void clearErrorOnEdit(final TextInputLayout layout) {
        if (layout.getEditText() == null) return;
        layout.getEditText().addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                if (layout.getError() != null) layout.setError(null);
            }
        });
    }

    private void snack(CharSequence text) {
        Snackbar.make(b.getRoot(), text, Snackbar.LENGTH_LONG).setAnchorView(b.saveButton).show();
    }

    private abstract static class SimpleWatcher implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) { }
    }
}
