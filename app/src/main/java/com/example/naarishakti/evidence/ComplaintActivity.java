package com.example.naarishakti.evidence;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.naarishakti.R;
import com.example.naarishakti.databinding.EvActivityComplaintBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import SQLite_Database.ProfileDbHelper;

/**
 * One-tap complaint draft for the police (SHO) or the National Commission for Women, in English or
 * Hindi, filled from the profile and (optionally) an incident's times, location and evidence hashes.
 */
public class ComplaintActivity extends AppCompatActivity {

    /** Optional String extra: the incident to base the draft on. */
    public static final String EXTRA_INCIDENT_ID = "incident_id";

    private static final String TAG = "ComplaintActivity";
    private static final String URL_NCW = "https://ncwapps.nic.in/onlinecomplaintsv2/";
    private static final String URL_CYBERCRIME = "https://cybercrime.gov.in/";
    private static final String STATE_EDITED = "edited";
    private static final String STATE_GENERATED = "generated";
    private static final String STATE_HINDI = "hindi";
    private static final String STATE_NCW = "ncw";

    private EvActivityComplaintBinding b;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private ComplaintTemplate.Profile profile = new ComplaintTemplate.Profile();
    @Nullable private EvidenceStore.IncidentInfo info;
    private final List<EvidenceStore.Item> items = new ArrayList<>();
    private boolean loaded;
    private boolean generated;
    private boolean userEdited;
    private boolean settingText;
    private boolean revertingChip;
    private boolean hindi;
    private boolean ncw;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = EvActivityComplaintBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        if (savedInstanceState != null) {
            userEdited = savedInstanceState.getBoolean(STATE_EDITED);
            generated = savedInstanceState.getBoolean(STATE_GENERATED);
            hindi = savedInstanceState.getBoolean(STATE_HINDI);
            ncw = savedInstanceState.getBoolean(STATE_NCW);
        }
        b.chipHi.setChecked(hindi);
        b.chipEn.setChecked(!hindi);
        b.chipNcw.setChecked(ncw);
        b.chipSho.setChecked(!ncw);

        b.backButton.setOnClickListener(v -> finish());
        b.copyButton.setOnClickListener(v -> copy());
        b.shareButton.setOnClickListener(v -> share());
        b.pdfButton.setOnClickListener(v -> savePdf());
        b.ncwRow.setOnClickListener(v -> EvUi.openUrl(this, URL_NCW));
        b.cyberRow.setOnClickListener(v -> EvUi.openUrl(this, URL_CYBERCRIME));
        b.callRow.setOnClickListener(v -> EvUi.start(this, new Intent(Intent.ACTION_DIAL, Uri.parse("tel:112"))));

        b.draftInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override
            public void afterTextChanged(Editable s) {
                if (!settingText && generated) userEdited = true;
            }
        });
        b.toGroup.setOnCheckedStateChangeListener((group, ids) -> onOptionChanged());
        b.langGroup.setOnCheckedStateChangeListener((group, ids) -> onOptionChanged());

        b.incidentText.setText(R.string.ev_cmp_incident_none);
        io.execute(() -> EvUi.cleanupExports(getApplicationContext()));
        load(getIntent().getStringExtra(EXTRA_INCIDENT_ID));
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_EDITED, userEdited);
        outState.putBoolean(STATE_GENERATED, generated);
        outState.putBoolean(STATE_HINDI, hindi);
        outState.putBoolean(STATE_NCW, ncw);
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ data

    private void load(@Nullable final String incidentId) {
        final Context app = getApplicationContext();
        io.execute(() -> {
            final ComplaintTemplate.Profile p = readProfile(app);
            EvidenceStore.IncidentInfo i = null;
            List<EvidenceStore.Item> list = new ArrayList<>();
            if (!TextUtils.isEmpty(incidentId)) {
                i = EvidenceStore.incident(app, incidentId);
                list = EvidenceStore.forIncident(app, incidentId);
            }
            final EvidenceStore.IncidentInfo incident = i;
            final List<EvidenceStore.Item> evidence = list;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                profile = p;
                info = incident;
                items.clear();
                items.addAll(evidence);
                loaded = true;
                long started = incident == null ? 0
                        : incident.startedAt > 0 ? incident.startedAt
                        : evidence.isEmpty() ? 0 : evidence.get(0).capturedAt;
                if (started > 0) {
                    b.incidentText.setText(getString(R.string.ev_cmp_incident_from, EvUi.dateTime(this, started)));
                }
                // After a rotation the EditText restored its own (possibly edited) text.
                boolean restored = generated && b.draftInput.getText() != null && b.draftInput.getText().length() > 0;
                if (!restored) regenerate();
            });
        });
    }

    private static ComplaintTemplate.Profile readProfile(Context ctx) {
        ComplaintTemplate.Profile p = new ComplaintTemplate.Profile();
        ProfileDbHelper helper = null;
        try {
            helper = new ProfileDbHelper(ctx);
            try (Cursor c = helper.getReadableDatabase().query(ProfileDbHelper.TABLE_NAME,
                    new String[]{ProfileDbHelper.COLUMN_NAME, ProfileDbHelper.COLUMN_FATHER_NAME,
                            ProfileDbHelper.COLUMN_ADDRESS, ProfileDbHelper.COLUMN_PINCODE,
                            ProfileDbHelper.COLUMN_MOBILE_NUMBER, ProfileDbHelper.COLUMN_OCCUPATION},
                    null, null, null, null, ProfileDbHelper.COLUMN_ID + " DESC", "1")) {
                if (c.moveToFirst()) {
                    p.name = str(c, 0);
                    p.father = str(c, 1);
                    p.address = str(c, 2);
                    p.pincode = str(c, 3);
                    p.mobile = str(c, 4);
                    p.occupation = str(c, 5);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Profile unavailable", e);
        } finally {
            if (helper != null) helper.close();
        }
        return p;
    }

    private static String str(Cursor c, int i) {
        try {
            return c.isNull(i) ? "" : c.getString(i).trim();
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ draft

    private void onOptionChanged() {
        if (revertingChip) return;
        final boolean newHindi = b.chipHi.isChecked();
        final boolean newNcw = b.chipNcw.isChecked();
        if (newHindi == hindi && newNcw == ncw) return;
        if (!loaded) {
            hindi = newHindi;
            ncw = newNcw;
            return;
        }
        if (userEdited) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.ev_cmp_replace_title)
                    .setMessage(R.string.ev_cmp_replace_body)
                    .setPositiveButton(R.string.ev_cmp_replace, (d, w) -> {
                        hindi = newHindi;
                        ncw = newNcw;
                        regenerate();
                    })
                    .setNegativeButton(R.string.ev_cancel, (d, w) -> revertChips())
                    .setOnCancelListener(d -> revertChips())
                    .show();
        } else {
            hindi = newHindi;
            ncw = newNcw;
            regenerate();
        }
    }

    private void revertChips() {
        revertingChip = true;
        b.chipHi.setChecked(hindi);
        b.chipEn.setChecked(!hindi);
        b.chipNcw.setChecked(ncw);
        b.chipSho.setChecked(!ncw);
        revertingChip = false;
    }

    private void regenerate() {
        String text = ComplaintTemplate.build(this, hindi, ncw, profile, info, items);
        settingText = true;
        b.draftInput.setText(text);
        settingText = false;
        generated = true;
        userEdited = false;
    }

    private String draft() {
        CharSequence t = b.draftInput.getText();
        return t == null ? "" : t.toString();
    }

    // ------------------------------------------------------------------ actions

    private void copy() {
        EvUi.copy(this, getString(R.string.ev_cmp_pdf_title), draft());
        snack(getString(R.string.ev_cmp_copied));
    }

    private void share() {
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.ev_cmp_pdf_title))
                .putExtra(Intent.EXTRA_TEXT, draft());
        EvUi.start(this, Intent.createChooser(send, getString(R.string.ev_cmp_share_chooser)));
    }

    private void savePdf() {
        final String text = draft();
        if (TextUtils.isEmpty(text.trim())) return;
        final Context ctx = this;
        b.pdfButton.setEnabled(false);
        io.execute(() -> {
            File out;
            PdfComposer pdf = new PdfComposer(ctx, getString(R.string.ev_pdf_confidential));
            try {
                pdf.body(text);
                String stamp = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date());
                out = pdf.finish(new File(EvUi.exportsDir(ctx), "naari_complaint_" + stamp + ".pdf"));
            } catch (Throwable t) {
                Log.e(TAG, "Complaint PDF failed", t);
                pdf.abandon();
                out = null;
            }
            final File file = out;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                b.pdfButton.setEnabled(true);
                if (file == null) {
                    snack(getString(R.string.ev_pdf_failed));
                    return;
                }
                EvUi.shareFile(this, file, "application/pdf", R.string.ev_cmp_pdf_chooser,
                        getString(R.string.ev_cmp_pdf_title));
            });
        });
    }

    private void snack(CharSequence text) {
        Snackbar.make(b.getRoot(), text, Snackbar.LENGTH_SHORT).show();
    }
}
