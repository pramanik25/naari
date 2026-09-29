package com.example.naarishakti.journey;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.View;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.ColorRes;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.example.naarishakti.MainActivity;
import com.example.naarishakti.R;
import com.example.naarishakti.cloud.Cloud;
import com.example.naarishakti.core.Prefs;
import com.example.naarishakti.core.ProtectionController;
import com.example.naarishakti.databinding.JrActivityCheckInBinding;
import com.example.naarishakti.security.PinActivity;
import com.example.naarishakti.security.PinStore;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;

import java.util.Calendar;
import java.util.List;
import java.util.Map;

import Home_Activity.SettingsEmergencyActivity;

/**
 * Check-in timer: pick a time, add a note, start. If she doesn't check in by the deadline an SOS
 * fires (and the server escalates even if the phone is dead). "I'm safe" is PIN-protected when an
 * app PIN is set; the duress PIN looks like a normal check-out but raises a covert SOS.
 */
public class CheckInActivity extends AppCompatActivity {

    /** Opens straight into the (PIN-verified) check-out. */
    public static final String ACTION_CHECKOUT = "com.example.naarishakti.journey.CHECKOUT";
    /** Optional: epoch ms deadline to preset (used by meeting mode). */
    public static final String EXTRA_DEADLINE = "jr_extra_deadline";
    /** Optional: note to preset. */
    public static final String EXTRA_NOTE = "jr_extra_note";
    /** With EXTRA_DEADLINE: start immediately instead of waiting for the Start button. */
    public static final String EXTRA_AUTOSTART = "jr_extra_autostart";

    private JrActivityCheckInBinding b;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private ActivityResultLauncher<String[]> permLauncher;
    private ActivityResultLauncher<Intent> pinLauncher;

    /** Minutes for the preset chips, or -1 when a custom clock time is chosen. */
    private int presetMinutes = 30;
    private long customDeadline;
    private int lastChipId = R.id.chip30;
    private boolean pinPending;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            render();
            handler.postDelayed(this, 1000L);
        }
    };

    private final BroadcastReceiver changeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            render();
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = JrActivityCheckInBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        permLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(), this::onPermissions);
        pinLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), this::onPinResult);

        b.backButton.setOnClickListener(v -> finish());
        b.durationChips.setOnCheckedStateChangeListener((group, ids) -> {
            if (ids.isEmpty()) return;
            int id = ids.get(0);
            if (id == R.id.chipCustom) return; // handled by the click listener
            lastChipId = id;
            if (id == R.id.chip15) presetMinutes = 15;
            else if (id == R.id.chip45) presetMinutes = 45;
            else if (id == R.id.chip60) presetMinutes = 60;
            else presetMinutes = 30;
            render();
        });
        b.chipCustom.setOnClickListener(v -> pickCustomTime());
        b.startButton.setOnClickListener(v -> requestStart());
        b.addContactsButton.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsEmergencyActivity.class)));
        b.exactAlarmButton.setOnClickListener(v -> JourneyUtil.openExactAlarmSettings(this));
        b.safeButton.setOnClickListener(v -> checkout());
        b.extendButton.setOnClickListener(v -> {
            long deadline = CheckIn.extend(this);
            if (deadline > 0) {
                Snackbar.make(b.getRoot(), getString(R.string.jr_ci_extended,
                        JourneyUtil.clock(this, deadline)), Snackbar.LENGTH_SHORT).show();
            }
        });
        b.helpButton.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            ProtectionController.triggerPanic(this, "checkin");
        });

        MainActivity.tintBadge(b.contactsIcon, R.color.ns_rose, R.color.ns_rose_container);
        handleIntent(getIntent(), savedInstanceState == null);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent, true);
    }

    @Override
    protected void onStart() {
        super.onStart();
        LocalBroadcastManager.getInstance(this)
                .registerReceiver(changeReceiver, new IntentFilter(JourneyUtil.ACTION_CHANGED));
    }

    @Override
    protected void onResume() {
        super.onResume();
        CheckIn.rearm(this);
        renderStatic();
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
    }

    @Override
    protected void onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(changeReceiver);
        super.onStop();
    }

    private void handleIntent(@Nullable Intent intent, boolean fresh) {
        if (intent == null || !fresh) return;
        if (ACTION_CHECKOUT.equals(intent.getAction())) {
            if (CheckIn.isActive(this)) b.getRoot().post(this::checkout);
            return;
        }
        long deadline = intent.getLongExtra(EXTRA_DEADLINE, 0L);
        if (deadline > 0) {
            presetMinutes = -1;
            customDeadline = deadline;
            b.durationChips.check(R.id.chipCustom);
            b.chipCustom.setText(getString(R.string.jr_ci_custom_at, JourneyUtil.clock(this, deadline)));
            String note = intent.getStringExtra(EXTRA_NOTE);
            if (note != null) b.noteInput.setText(note);
            if (intent.getBooleanExtra(EXTRA_AUTOSTART, false)) {
                // Consume so a rotation / re-delivery does not start twice.
                intent.removeExtra(EXTRA_AUTOSTART);
                b.getRoot().post(this::requestStart);
            }
        }
    }

    // ------------------------------------------------------------------ setup

    private void pickCustomTime() {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.MINUTE, 90);
        MaterialTimePicker picker = new MaterialTimePicker.Builder()
                .setTimeFormat(DateFormat.is24HourFormat(this) ? TimeFormat.CLOCK_24H : TimeFormat.CLOCK_12H)
                .setHour(c.get(Calendar.HOUR_OF_DAY))
                .setMinute(c.get(Calendar.MINUTE))
                .setTitleText(R.string.jr_ci_custom_title)
                .build();
        final boolean[] chosen = {false};
        picker.addOnPositiveButtonClickListener(v -> {
            chosen[0] = true;
            customDeadline = nextOccurrence(picker.getHour(), picker.getMinute());
            presetMinutes = -1;
            lastChipId = R.id.chipCustom;
            b.chipCustom.setText(getString(R.string.jr_ci_custom_at, JourneyUtil.clock(this, customDeadline)));
            render();
        });
        picker.addOnDismissListener(d -> {
            if (!chosen[0] && presetMinutes > 0) b.durationChips.check(lastChipId);
        });
        picker.show(getSupportFragmentManager(), "jr_checkin_time");
    }

    /** Today at hh:mm, or tomorrow if that is less than a minute away. */
    static long nextOccurrence(int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis() + 60_000L) c.add(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }

    private long selectedDeadline() {
        if (presetMinutes > 0) return System.currentTimeMillis() + presetMinutes * 60_000L;
        return customDeadline;
    }

    private void requestStart() {
        if (Prefs.getContacts(this).isEmpty()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.jr_no_contacts_title)
                    .setMessage(R.string.jr_no_contacts_body)
                    .setPositiveButton(R.string.jr_add_contacts, (d, w) ->
                            startActivity(new Intent(this, SettingsEmergencyActivity.class)))
                    .setNegativeButton(R.string.jr_cancel, null)
                    .show();
            return;
        }
        if (presetMinutes < 0 && customDeadline <= System.currentTimeMillis() + 60_000L) {
            Snackbar.make(b.getRoot(), R.string.jr_ci_time_passed, Snackbar.LENGTH_LONG).show();
            return;
        }
        if (CheckIn.isActive(this)) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.jr_ci_replace_title)
                    .setMessage(R.string.jr_ci_replace_body)
                    .setPositiveButton(R.string.jr_ci_replace, (d, w) -> {
                        CheckIn.cancel(this);
                        askPermissionsThenStart();
                    })
                    .setNegativeButton(R.string.jr_cancel, null)
                    .show();
            return;
        }
        askPermissionsThenStart();
    }

    private void askPermissionsThenStart() {
        String[] missing = JourneyUtil.missing(this,
                JourneyUtil.concat(JourneyUtil.locationPerms(), JourneyUtil.notificationPerms()));
        if (missing.length == 0) {
            doStart();
        } else {
            permLauncher.launch(missing);
        }
    }

    private void onPermissions(Map<String, Boolean> result) {
        doStart();
        if (!JourneyUtil.hasLocation(this)) {
            Snackbar.make(b.getRoot(), R.string.jr_ci_no_location, Snackbar.LENGTH_LONG)
                    .setAction(R.string.jr_open_settings, v -> JourneyUtil.openAppSettings(this))
                    .show();
        }
    }

    private void doStart() {
        String note = b.noteInput.getText() == null ? "" : b.noteInput.getText().toString().trim();
        CheckIn.start(this, selectedDeadline(), note);
        b.getRoot().performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        render();
    }

    // ------------------------------------------------------------------ check-out

    private void checkout() {
        if (!CheckIn.isActive(this) || pinPending) return;
        boolean pinSet = false;
        try {
            pinSet = PinStore.isSet(this);
        } catch (Exception ignored) {
            // Security module unavailable: fall back to a plain check-out.
        }
        if (pinSet) {
            pinPending = true;
            pinLauncher.launch(PinActivity.verifyIntent(this, getString(R.string.jr_ci_pin_title)));
        } else {
            CheckIn.complete(this);
            showCheckedOut();
        }
    }

    private void onPinResult(ActivityResult result) {
        pinPending = false;
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) return;
        String outcome = result.getData().getStringExtra(PinActivity.EXTRA_RESULT);
        if (PinActivity.RESULT_VALUE_DURESS.equals(outcome)) {
            // Looks exactly like a normal check-out; the covert SOS and the server check-in continue.
            CheckIn.duress(this);
            showCheckedOut();
        } else if (PinActivity.RESULT_VALUE_OK.equals(outcome)) {
            CheckIn.complete(this);
            showCheckedOut();
        }
    }

    private void showCheckedOut() {
        render();
        Snackbar.make(b.getRoot(), R.string.jr_ci_checked_out, Snackbar.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------ render

    private void renderStatic() {
        List<Prefs.Contact> contacts = Prefs.getContacts(this);
        if (contacts.isEmpty()) {
            b.contactsTitle.setText(R.string.jr_contacts_none);
            b.contactsSubtitle.setText(R.string.jr_contacts_none_sub);
        } else {
            b.contactsTitle.setText(getString(R.string.jr_contacts_count, contacts.size()));
            b.contactsSubtitle.setText(JourneyUtil.displayName(this, contacts));
        }
        JourneyUtil.show(b.addContactsButton, contacts.isEmpty());
        JourneyUtil.show(b.exactAlarmRow, !JourneyUtil.canScheduleExact(this));
        boolean cloud = false;
        try {
            cloud = Cloud.isAvailable();
        } catch (Exception ignored) {
            // Cloud module unavailable.
        }
        b.cloudNote.setText(cloud ? R.string.jr_ci_cloud_on : R.string.jr_ci_cloud_off);
    }

    private void render() {
        if (b == null) return;
        boolean active = CheckIn.isActive(this);
        JourneyUtil.show(b.setupGroup, !active);
        JourneyUtil.show(b.activeGroup, active);
        b.title.setText(active ? R.string.jr_ci_title_active : R.string.jr_ci_title);
        b.subtitle.setText(active ? R.string.jr_ci_body_active : R.string.jr_ci_body);

        if (!active) {
            long deadline = selectedDeadline();
            b.dueText.setText(deadline > System.currentTimeMillis()
                    ? getString(R.string.jr_ci_due_at, JourneyUtil.clock(this, deadline))
                    : getString(R.string.jr_ci_time_passed));
            return;
        }

        long now = System.currentTimeMillis();
        long deadline = CheckIn.deadline(this);
        long started = CheckIn.startedAt(this);
        boolean overdue = CheckIn.isOverdue(this) || now >= deadline;
        long remaining = Math.max(0L, deadline - now);
        long total = Math.max(1L, deadline - started);

        @ColorRes int tone;
        if (overdue) tone = R.color.ns_danger;
        else if (remaining <= CheckIn.WARN_BEFORE_MS) tone = R.color.ns_warn;
        else tone = R.color.ns_safe;

        b.ring.setIndicatorColor(color(tone));
        b.ring.setProgressCompat(overdue ? 1000 : (int) (1000L * remaining / total), false);
        b.countdownText.setText(overdue ? getString(R.string.jr_ci_overdue) : JourneyUtil.countdown(remaining));
        b.countdownCaption.setText(overdue
                ? getString(R.string.jr_ci_overdue_caption)
                : getString(R.string.jr_ci_left_caption, JourneyUtil.clock(this, deadline)));

        String note = CheckIn.note(this);
        b.activeNote.setText(note);
        JourneyUtil.show(b.activeNote, !TextUtils.isEmpty(note));

        int contacts = Prefs.getContactNumbers(this).size();
        b.activeStatus.setText(overdue
                ? getString(R.string.jr_ci_status_overdue)
                : getString(R.string.jr_ci_status_active, contacts));
        ViewCompat.setBackgroundTintList(b.activeStatusRow,
                ColorStateList.valueOf(color(overdue ? R.color.ns_danger_container : R.color.ns_safe_container)));
        ViewCompat.setBackgroundTintList(b.activeDot, ColorStateList.valueOf(color(tone)));
        b.extendButton.setVisibility(View.VISIBLE);
    }

    private int color(@ColorRes int res) {
        return ContextCompat.getColor(this, res);
    }
}
