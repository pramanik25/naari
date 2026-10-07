package com.example.naarishakti.shell;

import android.app.Dialog;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

import com.example.naarishakti.R;
import com.example.naarishakti.cloud.ProfileSync;
import com.example.naarishakti.databinding.ShDialogProfileBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;

import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import Home_Activity.ProfileActivity;
import SQLite_Database.ProfileDbHelper;

/**
 * "Complete your profile" popup on Home. Shown every time the app is opened until a name and a
 * mobile number are saved (both required; guardian, address and pincode are optional); the close
 * button only dismisses it until the next open. Saves into the same row {@link ProfileActivity}
 * edits and hands the name and number to {@link ProfileSync}.
 */
public class ProfilePromptDialog extends DialogFragment {

    private static final String TAG = "profile_prompt";
    /** Fragment result posted on the host FragmentManager after a successful save. */
    public static final String RESULT_SAVED = "profile_prompt_saved";

    private static final Pattern MOBILE = Pattern.compile("\\d{10}");
    private static final Pattern PINCODE = Pattern.compile("[1-9]\\d{5}");

    private static final String[] COLUMNS = {
            ProfileDbHelper.COLUMN_NAME, ProfileDbHelper.COLUMN_MOBILE_NUMBER,
            ProfileDbHelper.COLUMN_FATHER_NAME, ProfileDbHelper.COLUMN_FATHER_MOBILE_NUMBER,
            ProfileDbHelper.COLUMN_ADDRESS, ProfileDbHelper.COLUMN_PINCODE};

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    @Nullable private ShDialogProfileBinding b;
    private boolean saving;

    /** Shows the popup unless the saved profile already has a name and a valid mobile number. */
    public static void maybeShow(@NonNull FragmentActivity activity) {
        final Context app = activity.getApplicationContext();
        final WeakReference<FragmentActivity> ref = new WeakReference<>(activity);
        IO.execute(() -> {
            if (isComplete(app)) return;
            MAIN.post(() -> {
                FragmentActivity a = ref.get();
                if (a == null || a.isFinishing() || a.isDestroyed()) return;
                FragmentManager fm = a.getSupportFragmentManager();
                if (fm.isStateSaved() || fm.findFragmentByTag(TAG) != null) return;
                new ProfilePromptDialog().show(fm, TAG);
            });
        });
    }

    private static boolean isComplete(Context app) {
        ProfileDbHelper helper = new ProfileDbHelper(app);
        try {
            String mobile = helper.getProfileMobile();
            return helper.getProfileName() != null && mobile != null && MOBILE.matcher(mobile).matches();
        } catch (Exception e) {
            Log.w(TAG, "Unable to read profile", e);
            return false;
        } finally {
            helper.close();
        }
    }

    // ------------------------------------------------------------------ dialog

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext());
        b = ShDialogProfileBinding.inflate(LayoutInflater.from(builder.getContext()));

        b.nameLayout.setHint(getString(R.string.profile_full_name) + " *");
        b.mobileLayout.setHint(getString(R.string.profile_mobile) + " *");
        clearErrorOnEdit(b.nameLayout);
        clearErrorOnEdit(b.mobileLayout);
        clearErrorOnEdit(b.guardianMobileLayout);
        clearErrorOnEdit(b.pincodeLayout);

        b.closeButton.setOnClickListener(v -> dismiss());
        b.saveButton.setOnClickListener(v -> save());

        Dialog dialog = builder.setView(b.getRoot()).create();
        // Only the close button or Back dismisses: a stray tap outside must not throw the form away.
        dialog.setCanceledOnTouchOutside(false);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        loadExisting();
        return dialog;
    }

    @Override
    public void onDestroyView() {
        b = null;
        super.onDestroyView();
    }

    /** Prefills whatever an earlier, incomplete save already has (e.g. a name but no number). */
    private void loadExisting() {
        final Context app = requireContext().getApplicationContext();
        IO.execute(() -> {
            final String[] row = new String[COLUMNS.length];
            try (ProfileDbHelper helper = new ProfileDbHelper(app);
                 SQLiteDatabase db = helper.getReadableDatabase();
                 Cursor c = db.query(ProfileDbHelper.TABLE_NAME, COLUMNS, null, null, null, null,
                         ProfileDbHelper.COLUMN_ID + " DESC", "1")) {
                if (!c.moveToFirst()) return;
                for (int i = 0; i < COLUMNS.length; i++) row[i] = c.getString(i);
            } catch (Exception e) {
                Log.w(TAG, "Unable to load profile", e);
                return;
            }
            MAIN.post(() -> {
                if (b == null) return;
                EditText[] fields = {b.nameInput, b.mobileInput, b.guardianNameInput,
                        b.guardianMobileInput, b.addressInput, b.pincodeInput};
                for (int i = 0; i < fields.length; i++) {
                    if (!TextUtils.isEmpty(row[i]) && TextUtils.isEmpty(fields[i].getText())) {
                        fields[i].setText(row[i]);
                    }
                }
            });
        });
    }

    // ------------------------------------------------------------------ save

    private void save() {
        if (b == null || saving) return;
        String name = textOf(b.nameInput);
        String mobile = ProfileActivity.normalizeMobile(textOf(b.mobileInput));
        String guardianMobile = ProfileActivity.normalizeMobile(textOf(b.guardianMobileInput));
        String pincode = textOf(b.pincodeInput);

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
            b.guardianMobileLayout.setError(getString(R.string.profile_error_mobile));
            if (firstError == null) firstError = b.guardianMobileLayout;
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
        values.put(ProfileDbHelper.COLUMN_MOBILE_NUMBER, mobile);
        values.put(ProfileDbHelper.COLUMN_FATHER_NAME, textOf(b.guardianNameInput));
        values.put(ProfileDbHelper.COLUMN_FATHER_MOBILE_NUMBER, guardianMobile);
        values.put(ProfileDbHelper.COLUMN_ADDRESS, textOf(b.addressInput));
        values.put(ProfileDbHelper.COLUMN_PINCODE, pincode);

        saving = true;
        b.saveButton.setEnabled(false);
        final Context app = requireContext().getApplicationContext();
        IO.execute(() -> {
            boolean ok = false;
            try (ProfileDbHelper helper = new ProfileDbHelper(app);
                 SQLiteDatabase db = helper.getWritableDatabase()) {
                // Update the row ProfileActivity edits (the newest), so its photo and occupation stay.
                long id = -1;
                try (Cursor c = db.query(ProfileDbHelper.TABLE_NAME, new String[]{ProfileDbHelper.COLUMN_ID},
                        null, null, null, null, ProfileDbHelper.COLUMN_ID + " DESC", "1")) {
                    if (c.moveToFirst()) id = c.getLong(0);
                }
                ok = id != -1 && db.update(ProfileDbHelper.TABLE_NAME, values,
                        ProfileDbHelper.COLUMN_ID + "=?", new String[]{String.valueOf(id)}) > 0;
                if (!ok) ok = db.insert(ProfileDbHelper.TABLE_NAME, null, values) != -1;
            } catch (Exception e) {
                Log.e(TAG, "Unable to save profile", e);
            }
            final boolean saved = ok;
            MAIN.post(() -> onSaved(app, saved));
        });
    }

    private void onSaved(Context app, boolean saved) {
        saving = false;
        if (saved) ProfileSync.push(app);
        if (b == null || !isAdded()) return;
        b.saveButton.setEnabled(true);
        if (!saved) {
            Toast.makeText(app, R.string.profile_save_error, Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(app, R.string.profile_saved, Toast.LENGTH_SHORT).show();
        getParentFragmentManager().setFragmentResult(RESULT_SAVED, Bundle.EMPTY);
        dismissAllowingStateLoss();
    }

    // ------------------------------------------------------------------ helpers

    private static String textOf(EditText field) {
        return field.getText() == null ? "" : field.getText().toString().trim();
    }

    private static void clearErrorOnEdit(final TextInputLayout layout) {
        if (layout.getEditText() == null) return;
        layout.getEditText().addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override
            public void afterTextChanged(Editable s) {
                if (layout.getError() != null) layout.setError(null);
            }
        });
    }
}
