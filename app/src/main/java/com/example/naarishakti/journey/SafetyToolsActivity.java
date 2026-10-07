package com.example.naarishakti.journey;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.example.naarishakti.MainActivity;
import com.example.naarishakti.R;
import com.example.naarishakti.cloud.Cloud;
import com.example.naarishakti.cloud.CloudSettingsActivity;
import com.example.naarishakti.core.Prefs;
import com.example.naarishakti.core.ProtectionController;
import com.example.naarishakti.databinding.JrActivitySafetyToolsBinding;
import com.example.naarishakti.databinding.JrItemTileBinding;
import com.example.naarishakti.databinding.JrSheetFakeCallBinding;
import com.example.naarishakti.escape.FakeCall;
import com.example.naarishakti.evidence.ComplaintActivity;
import com.example.naarishakti.evidence.DiaryActivity;
import com.example.naarishakti.evidence.IncidentsActivity;
import com.example.naarishakti.security.PinActivity;
import com.example.naarishakti.security.PinStore;
import com.example.naarishakti.triggers.PanicButtonActivity;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.snackbar.Snackbar;

/**
 * Safety tools hub: the entry point to every v2 feature, grouped into 2-column tile sections,
 * with a status strip (protection, PIN, cloud) on top.
 */
public class SafetyToolsActivity extends AppCompatActivity {

    /** Remembered delay chip for the fake call (seconds). Own key: FakeCall owns FAKE_CALL_DELAY_S. */
    private static final String K_FAKE_DELAY = "jr_fake_delay_s";

    private JrActivitySafetyToolsBinding b;
    private JrItemTileBinding checkInTile;
    private JrItemTileBinding cabTile;
    private JrItemTileBinding silentTile;
    private JrItemTileBinding pinTile;
    private boolean suppress;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            renderJourneyCaptions();
            handler.postDelayed(this, 1000L);
        }
    };

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            renderStatus();
            renderJourneyCaptions();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = JrActivitySafetyToolsBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        b.backButton.setOnClickListener(v -> finish());
        b.statusProtection.setOnClickListener(v -> finish()); // protection switch lives on Home
        b.statusPin.setOnClickListener(v -> openPinSetup());
        b.statusCloud.setOnClickListener(v -> open(new Intent(this, CloudSettingsActivity.class)));
        buildSections();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter(ProtectionController.ACTION_STATE_CHANGED);
        f.addAction(Prefs.ACTION_SETTINGS_UPDATED);
        f.addAction(JourneyUtil.ACTION_CHANGED);
        LocalBroadcastManager.getInstance(this).registerReceiver(stateReceiver, f);
    }

    @Override
    protected void onResume() {
        super.onResume();
        CheckIn.rearm(this);
        renderStatus();
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
        LocalBroadcastManager.getInstance(this).unregisterReceiver(stateReceiver);
        super.onStop();
    }

    // ------------------------------------------------------------------ sections

    private void buildSections() {
        section(R.string.jr_section_move);
        checkInTile = tile(R.drawable.ua_ic_timer, R.color.ns_rose, R.color.ns_rose_container,
                R.string.jr_tile_checkin, R.string.jr_tile_checkin_cap,
                v -> open(new Intent(this, CheckInActivity.class)));
        cabTile = tile(R.drawable.jr_ic_cab, R.color.ns_rose, R.color.ns_rose_container,
                R.string.jr_tile_cab, R.string.jr_tile_cab_cap,
                v -> open(new Intent(this, CabModeActivity.class)));
        row(checkInTile, cabTile);
        row(tile(R.drawable.ua_ic_group, R.color.ns_rose, R.color.ns_rose_container,
                        R.string.jr_tile_meeting, R.string.jr_tile_meeting_cap,
                        v -> open(new Intent(this, MeetingModeActivity.class))),
                tile(R.drawable.ua_ic_location, R.color.ns_rose, R.color.ns_rose_container,
                        R.string.jr_tile_places, R.string.jr_tile_places_cap,
                        v -> open(new Intent(this, SafePlacesActivity.class))));

        section(R.string.jr_section_escape);
        silentTile = tile(R.drawable.jr_ic_silent, R.color.ns_violet, R.color.ns_violet_container,
                R.string.jr_tile_silent, R.string.jr_tile_silent_cap, null);
        setupSilentTile();
        row(tile(R.drawable.ua_ic_phone, R.color.ns_violet, R.color.ns_violet_container,
                        R.string.jr_tile_fake_call, R.string.jr_tile_fake_call_cap, v -> showFakeCallSheet()),
                silentTile);

        section(R.string.jr_section_evidence);
        row(tile(R.drawable.jr_ic_history, R.color.ns_gold, R.color.ns_gold_container,
                        R.string.jr_tile_incidents, R.string.jr_tile_incidents_cap,
                        v -> open(new Intent(this, IncidentsActivity.class))),
                tile(R.drawable.jr_ic_diary, R.color.ns_gold, R.color.ns_gold_container,
                        R.string.jr_tile_diary, R.string.jr_tile_diary_cap,
                        v -> open(new Intent(this, DiaryActivity.class))));
        row(tile(R.drawable.jr_ic_complaint, R.color.ns_gold, R.color.ns_gold_container,
                R.string.jr_tile_complaint, R.string.jr_tile_complaint_cap,
                v -> open(new Intent(this, ComplaintActivity.class))), null);

        section(R.string.jr_section_network);
        row(tile(R.drawable.jr_ic_cloud, R.color.ns_info, R.color.ns_info_container,
                        R.string.jr_tile_cloud, R.string.jr_tile_cloud_cap,
                        v -> open(new Intent(this, CloudSettingsActivity.class))),
                tile(R.drawable.jr_ic_bluetooth, R.color.ns_info, R.color.ns_info_container,
                        R.string.jr_tile_ble, R.string.jr_tile_ble_cap,
                        v -> open(new Intent(this, PanicButtonActivity.class))));

        section(R.string.jr_section_settings);
        pinTile = tile(R.drawable.ua_ic_lock, R.color.ns_safe, R.color.ns_safe_container,
                R.string.jr_tile_pin, R.string.jr_tile_pin_cap, v -> openPinSetup());
        row(tile(R.drawable.ua_ic_tune, R.color.ns_safe, R.color.ns_safe_container,
                        R.string.jr_tile_settings, R.string.jr_tile_settings_cap,
                        v -> open(new Intent(this, SafetySettingsActivity.class))),
                pinTile);
    }

    private void section(@StringRes int label) {
        TextView tv = (TextView) LayoutInflater.from(this).inflate(R.layout.jr_section_label, b.sections, false);
        tv.setText(label);
        b.sections.addView(tv);
    }

    private JrItemTileBinding tile(@DrawableRes int icon, @ColorRes int solid, @ColorRes int container,
                                   @StringRes int title, @StringRes int caption,
                                   View.OnClickListener onClick) {
        JrItemTileBinding t = JrItemTileBinding.inflate(LayoutInflater.from(this), b.sections, false);
        t.icon.setImageResource(icon);
        MainActivity.tintBadge(t.icon, solid, container);
        t.title.setText(title);
        t.caption.setText(caption);
        t.getRoot().setContentDescription(getString(title) + ". " + getString(caption));
        if (onClick != null) {
            t.getRoot().setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                onClick.onClick(v);
            });
        }
        return t;
    }

    /** Two tiles side by side; a null right tile leaves an empty half. */
    private void row(JrItemTileBinding left, JrItemTileBinding right) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBaselineAligned(false);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        if (b.sections.getChildCount() > 0
                && !(b.sections.getChildAt(b.sections.getChildCount() - 1) instanceof TextView)) {
            rowLp.topMargin = JourneyUtil.dp(this, 12);
        }
        row.setLayoutParams(rowLp);

        int gap = JourneyUtil.dp(this, 6);
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        l.setMarginEnd(gap);
        row.addView(left.getRoot(), l);
        LinearLayout.LayoutParams r = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        r.setMarginStart(gap);
        if (right != null) {
            row.addView(right.getRoot(), r);
        } else {
            View spacer = new View(this);
            spacer.setVisibility(View.INVISIBLE);
            row.addView(spacer, r);
        }
        b.sections.addView(row);
    }

    // ------------------------------------------------------------------ silent SOS

    private void setupSilentTile() {
        silentTile.toggle.setVisibility(View.VISIBLE);
        silentTile.toggle.setContentDescription(getString(R.string.jr_tile_silent));
        silentTile.toggle.setChecked(Prefs.get(this).getBoolean(Prefs.SILENT_SOS, false));
        silentTile.toggle.setOnCheckedChangeListener((btn, checked) -> {
            if (suppress) return;
            btn.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            Prefs.get(this).edit().putBoolean(Prefs.SILENT_SOS, checked).apply();
            Prefs.notifyChanged(this);
            renderSilentCaption();
        });
        silentTile.getRoot().setOnClickListener(v -> silentTile.toggle.toggle());
        renderSilentCaption();
    }

    private void renderSilentCaption() {
        boolean on = Prefs.get(this).getBoolean(Prefs.SILENT_SOS, false);
        silentTile.caption.setText(on ? R.string.jr_tile_silent_on : R.string.jr_tile_silent_cap);
    }

    // ------------------------------------------------------------------ fake call

    private void showFakeCallSheet() {
        final BottomSheetDialog sheet = new BottomSheetDialog(this);
        final JrSheetFakeCallBinding s = JrSheetFakeCallBinding.inflate(LayoutInflater.from(this));
        sheet.setContentView(s.getRoot());
        final SharedPreferences prefs = Prefs.get(this);

        String name = prefs.getString(Prefs.FAKE_CALL_NAME, "");
        s.nameInput.setText(TextUtils.isEmpty(name) ? getString(R.string.jr_fake_default_name) : name);
        int delay = prefs.getInt(K_FAKE_DELAY, 30);
        s.delayChips.check(delay <= 5 ? R.id.chip5s : delay <= 30 ? R.id.chip30s
                : delay <= 60 ? R.id.chip1m : R.id.chip5m);

        s.scheduleButton.setOnClickListener(v -> {
            int id = s.delayChips.getCheckedChipId();
            final int seconds = id == R.id.chip5s ? 5 : id == R.id.chip1m ? 60 : id == R.id.chip5m ? 300 : 30;
            String caller = s.nameInput.getText() == null ? "" : s.nameInput.getText().toString().trim();
            if (caller.isEmpty()) caller = getString(R.string.jr_fake_default_name);
            prefs.edit().putString(Prefs.FAKE_CALL_NAME, caller).putInt(K_FAKE_DELAY, seconds).apply();
            Prefs.notifyChanged(this);
            FakeCall.schedule(this, seconds, caller);
            sheet.dismiss();
            Snackbar.make(b.getRoot(), getString(R.string.jr_fake_scheduled, delayLabel(seconds)),
                            Snackbar.LENGTH_LONG)
                    .setAction(R.string.jr_cancel, x -> {
                        FakeCall.cancel(this);
                        Snackbar.make(b.getRoot(), R.string.jr_fake_cancelled, Snackbar.LENGTH_SHORT).show();
                    })
                    .show();
        });
        sheet.setOnShowListener(d -> {
            View container = sheet.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (container != null) {
                ViewCompat.setBackgroundTintList(container,
                        ColorStateList.valueOf(ContextCompat.getColor(this, R.color.ns_surface_high)));
            }
        });
        sheet.show();
    }

    private String delayLabel(int seconds) {
        if (seconds == 5) return getString(R.string.jr_fake_5s);
        if (seconds == 60) return getString(R.string.jr_fake_1m);
        if (seconds == 300) return getString(R.string.jr_fake_5m);
        return getString(R.string.jr_fake_30s);
    }

    // ------------------------------------------------------------------ status

    private void renderStatus() {
        boolean protection = ProtectionController.isProtectionActive();
        setStatus(b.protectionDot, b.protectionValue, protection,
                protection ? R.string.jr_status_on : R.string.jr_status_off);

        boolean pin = false;
        try {
            pin = PinStore.isSet(this);
        } catch (Exception ignored) {
            // Security module unavailable.
        }
        setStatus(b.pinDot, b.pinValue, pin, pin ? R.string.jr_status_pin_set : R.string.jr_status_pin_none);
        if (pinTile != null) {
            pinTile.caption.setText(pin ? R.string.jr_tile_pin_cap_set : R.string.jr_tile_pin_cap);
        }

        boolean available = false;
        try {
            available = Cloud.isAvailable();
        } catch (Exception ignored) {
            // Cloud module unavailable.
        }
        boolean linked = available && !TextUtils.isEmpty(Prefs.get(this).getString(Prefs.CLOUD_USER_ID, ""));
        setStatus(b.cloudDot, b.cloudValue, linked, linked ? R.string.jr_status_cloud_on
                : available ? R.string.jr_status_cloud_pending : R.string.jr_status_cloud_off);

        if (silentTile != null) {
            suppress = true;
            silentTile.toggle.setChecked(Prefs.get(this).getBoolean(Prefs.SILENT_SOS, false));
            suppress = false;
            renderSilentCaption();
        }
    }

    private void setStatus(View dot, TextView value, boolean good, @StringRes int text) {
        @ColorRes int c = good ? R.color.ns_safe : R.color.ns_text_faint;
        ViewCompat.setBackgroundTintList(dot, ColorStateList.valueOf(ContextCompat.getColor(this, c)));
        value.setText(text);
        value.setTextColor(ContextCompat.getColor(this, good ? R.color.ns_text : R.color.ns_text_muted));
    }

    private void renderJourneyCaptions() {
        if (checkInTile != null) {
            if (CheckIn.isActive(this)) {
                long left = CheckIn.deadline(this) - System.currentTimeMillis();
                checkInTile.caption.setText(CheckIn.isOverdue(this) || left <= 0
                        ? getString(R.string.jr_tile_checkin_overdue)
                        : getString(R.string.jr_tile_checkin_active, JourneyUtil.countdown(left)));
                checkInTile.caption.setTextColor(ContextCompat.getColor(this, R.color.ns_safe));
            } else {
                checkInTile.caption.setText(R.string.jr_tile_checkin_cap);
                checkInTile.caption.setTextColor(ContextCompat.getColor(this, R.color.ns_text_faint));
            }
        }
        if (cabTile != null) {
            boolean trip = CabTrip.isActive(this);
            cabTile.caption.setText(trip ? getString(R.string.jr_tile_cab_active, CabTrip.plate(this))
                    : getString(R.string.jr_tile_cab_cap));
            cabTile.caption.setTextColor(ContextCompat.getColor(this,
                    trip ? R.color.ns_safe : R.color.ns_text_faint));
        }
    }

    // ------------------------------------------------------------------ navigation

    private void openPinSetup() {
        open(PinActivity.setupIntent(this));
    }

    private void open(Intent intent) {
        if (JourneyUtil.startSafely(this, intent)) {
            overridePendingTransition(R.anim.slide_in, R.anim.slide_out);
        }
    }
}
