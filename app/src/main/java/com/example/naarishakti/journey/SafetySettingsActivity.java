package com.example.naarishakti.journey;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;

import com.example.naarishakti.MainActivity;
import com.example.naarishakti.R;
import com.example.naarishakti.cloud.CloudSettingsActivity;
import com.example.naarishakti.core.Prefs;
import com.example.naarishakti.databinding.JrActivitySafetySettingsBinding;
import com.example.naarishakti.databinding.JrCardListBinding;
import com.example.naarishakti.databinding.JrItemStatusLineBinding;
import com.example.naarishakti.databinding.UaItemRowBinding;
import com.example.naarishakti.databinding.UaItemSwitchRowBinding;
import com.example.naarishakti.mesh.Mesh;
import com.example.naarishakti.triggers.PanicButtonActivity;
import com.example.naarishakti.triggers.TriggerSetup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Every Safety v2 toggle, grouped and auto-saved. Switching a feature on asks for the runtime
 * permission or special access it needs; if that is refused the switch goes back off with an
 * explanation. Each save is broadcast with {@link Prefs#notifyChanged}.
 */
public class SafetySettingsActivity extends AppCompatActivity {

    /** One boolean setting row. */
    private static final class Toggle {
        String key;
        boolean def;
        @DrawableRes int icon;
        @ColorRes int solid;
        @ColorRes int container;
        @StringRes int title;
        @StringRes int subtitle;
        /** Runtime permissions needed while on (may be empty). */
        String[] perms = new String[0];
        /** Confirmation shown before enabling (0 = none). */
        @StringRes int confirmTitle;
        @StringRes int confirmBody;
        /** Needs the accessibility service. */
        boolean accessibility;
        UaItemSwitchRowBinding row;
    }

    private JrActivitySafetySettingsBinding b;
    private final List<Toggle> toggles = new ArrayList<>();
    private ActivityResultLauncher<String[]> permLauncher;
    @Nullable private Toggle pendingToggle;
    private boolean pendingBle;
    private boolean suppress;
    @Nullable private Toggle awaitingAccessibility;

    @Nullable private JrItemStatusLineBinding accessibilityLine;
    @Nullable private UaItemRowBinding panicRow;
    @Nullable private UaItemRowBinding helperRow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = JrActivitySafetySettingsBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        permLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(), this::onPermissions);
        b.backButton.setOnClickListener(v -> finish());

        bindRow(b.appearanceRow, R.drawable.ua_ic_tune, R.color.ns_gold, R.color.ns_gold_container,
                getString(R.string.jr_ss_appearance), getString(R.string.jr_ss_appearance_sub));
        b.appearanceRow.getRoot().setOnClickListener(v ->
                startActivity(new Intent(this, com.example.naarishakti.core.AppearanceActivity.class)));

        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (awaitingAccessibility != null) {
            Toggle t = awaitingAccessibility;
            awaitingAccessibility = null;
            if (!accessibilityOn()) {
                setSaved(t, false);
                explain(t, getString(R.string.jr_ss_denied_accessibility), false);
            }
        }
        refresh();
    }

    // ------------------------------------------------------------------ build

    private void build() {
        final String phoneState = Manifest.permission.READ_PHONE_STATE;
        final String sms = Manifest.permission.SEND_SMS;
        final String call = Manifest.permission.CALL_PHONE;
        final String mic = Manifest.permission.RECORD_AUDIO;
        final String camera = Manifest.permission.CAMERA;
        String[] location = JourneyUtil.locationPerms();

        // SOS behaviour
        LinearLayout sos = card(R.string.jr_ss_section_sos);
        add(sos, toggle(Prefs.SILENT_SOS, false, R.drawable.jr_ic_silent, R.color.ns_violet, R.color.ns_violet_container,
                R.string.jr_ss_silent, R.string.jr_ss_silent_sub));
        add(sos, toggle(Prefs.STROBE_ENABLED, true, R.drawable.jr_ic_flash, R.color.ns_gold, R.color.ns_gold_container,
                R.string.jr_ss_strobe, R.string.jr_ss_strobe_sub));
        add(sos, toggle(Prefs.SPEAK_ALERT, false, R.drawable.ua_ic_volume, R.color.ns_rose, R.color.ns_rose_container,
                R.string.jr_ss_speak, R.string.jr_ss_speak_sub));
        Toggle esc = toggle(Prefs.CALL_ESCALATION, true, R.drawable.ua_ic_phone, R.color.ns_info, R.color.ns_info_container,
                R.string.jr_ss_escalation, R.string.jr_ss_escalation_sub);
        esc.perms = new String[]{phoneState, call};
        add(sos, esc);
        Toggle e112 = toggle(Prefs.ESCALATE_TO_112, false, R.drawable.ua_ic_police, R.color.ns_danger, R.color.ns_danger_container,
                R.string.jr_ss_112, R.string.jr_ss_112_sub);
        e112.perms = new String[]{call};
        e112.confirmTitle = R.string.jr_ss_112_confirm_title;
        e112.confirmBody = R.string.jr_ss_112_confirm_body;
        add(sos, e112);

        // Evidence
        LinearLayout ev = card(R.string.jr_ss_section_evidence);
        Toggle audio = toggle(Prefs.EVIDENCE_AUDIO, true, R.drawable.ua_ic_mic, R.color.ns_violet, R.color.ns_violet_container,
                R.string.jr_ss_audio, R.string.jr_ss_audio_sub);
        audio.perms = new String[]{mic};
        add(ev, audio);
        Toggle video = toggle(Prefs.EVIDENCE_VIDEO, false, R.drawable.jr_ic_videocam, R.color.ns_gold, R.color.ns_gold_container,
                R.string.jr_ss_video, R.string.jr_ss_video_sub);
        video.perms = new String[]{camera, mic};
        video.confirmTitle = R.string.jr_ss_video_confirm_title;
        video.confirmBody = R.string.jr_ss_video_confirm_body;
        add(ev, video);
        add(ev, toggle(Prefs.EVIDENCE_UPLOAD_MOBILE, true, R.drawable.jr_ic_data, R.color.ns_info, R.color.ns_info_container,
                R.string.jr_ss_mobile, R.string.jr_ss_mobile_sub));

        // Automatic triggers
        LinearLayout tr = card(R.string.jr_ss_section_triggers);
        Toggle scream = toggle(Prefs.SCREAM_DETECTION, false, R.drawable.jr_ic_wave, R.color.ns_rose, R.color.ns_rose_container,
                R.string.jr_ss_scream, R.string.jr_ss_scream_sub);
        scream.perms = new String[]{mic};
        add(tr, scream);
        add(tr, toggle(Prefs.FALL_DETECTION, false, R.drawable.jr_ic_fall, R.color.ns_warn, R.color.ns_warn_container,
                R.string.jr_ss_fall, R.string.jr_ss_fall_sub));
        add(tr, toggle(Prefs.HEADSET_TRIGGER, true, R.drawable.ua_ic_headset, R.color.ns_violet, R.color.ns_violet_container,
                R.string.jr_ss_headset, R.string.jr_ss_headset_sub));
        Toggle volume = toggle(Prefs.VOLUME_KEY_TRIGGER, true, R.drawable.ua_ic_volume, R.color.ns_info, R.color.ns_info_container,
                R.string.jr_ss_volume, R.string.jr_ss_volume_sub);
        volume.accessibility = true;
        add(tr, volume);
        JrItemStatusLineBinding line = JrItemStatusLineBinding.inflate(LayoutInflater.from(this), tr, false);
        line.action.setText(R.string.jr_ss_enable);
        line.action.setOnClickListener(v -> TriggerSetup.openAccessibilitySettings(this));
        tr.addView(line.getRoot());
        accessibilityLine = line;
        tr.addView(JourneyUtil.divider(this));
        UaItemRowBinding panic = UaItemRowBinding.inflate(LayoutInflater.from(this), tr, false);
        bindRow(panic, R.drawable.jr_ic_bluetooth, R.color.ns_info, R.color.ns_info_container,
                getString(R.string.jr_ss_panic), getString(R.string.jr_ss_panic_none));
        panic.getRoot().setOnClickListener(v -> openPanicButton());
        tr.addView(panic.getRoot());
        panicRow = panic;

        // Phone status alerts
        LinearLayout ph = card(R.string.jr_ss_section_phone);
        Toggle shutdown = toggle(Prefs.SHUTDOWN_ALERT, true, R.drawable.ua_ic_power, R.color.ns_rose, R.color.ns_rose_container,
                R.string.jr_ss_shutdown, R.string.jr_ss_shutdown_sub);
        shutdown.perms = JourneyUtil.concat(new String[]{sms}, location);
        add(ph, shutdown);
        Toggle battery = toggle(Prefs.LOW_BATTERY_ALERT, true, R.drawable.ua_ic_battery, R.color.ns_warn, R.color.ns_warn_container,
                R.string.jr_ss_battery, R.string.jr_ss_battery_sub);
        battery.perms = JourneyUtil.concat(new String[]{sms}, location);
        add(ph, battery);
        Toggle sim = toggle(Prefs.SIM_ALERT, true, R.drawable.jr_ic_sim, R.color.ns_gold, R.color.ns_gold_container,
                R.string.jr_ss_sim, R.string.jr_ss_sim_sub);
        sim.perms = new String[]{phoneState, sms};
        add(ph, sim);

        // Community
        LinearLayout co = card(R.string.jr_ss_section_community);
        Toggle mesh = toggle(Prefs.MESH_ENABLED, false, R.drawable.ua_ic_radar, R.color.ns_safe, R.color.ns_safe_container,
                R.string.jr_ss_mesh, R.string.jr_ss_mesh_sub);
        mesh.perms = JourneyUtil.perms(Mesh.requiredPermissions());
        add(co, mesh);
        co.addView(JourneyUtil.divider(this));
        UaItemRowBinding helper = UaItemRowBinding.inflate(LayoutInflater.from(this), co, false);
        bindRow(helper, R.drawable.ua_ic_group, R.color.ns_safe, R.color.ns_safe_container,
                getString(R.string.jr_ss_helper), getString(R.string.jr_ss_helper_off));
        helper.getRoot().setOnClickListener(v -> startActivity(new Intent(this, CloudSettingsActivity.class)));
        co.addView(helper.getRoot());
        helperRow = helper;
        // v1.1: let nearby helpers be alerted during her SOS (sent as "broadcast" in the incident).
        add(co, toggle(Prefs.NEARBY_BROADCAST, true, R.drawable.cl_ic_helper, R.color.ns_rose,
                R.color.ns_rose_container, R.string.nb_broadcast_title, R.string.nb_broadcast_sub));
    }

    private LinearLayout card(@StringRes int label) {
        TextView tv = (TextView) LayoutInflater.from(this).inflate(R.layout.jr_section_label, b.sections, false);
        tv.setText(label);
        b.sections.addView(tv);
        JrCardListBinding card = JrCardListBinding.inflate(LayoutInflater.from(this), b.sections, false);
        b.sections.addView(card.getRoot());
        return card.list;
    }

    private Toggle toggle(String key, boolean def, @DrawableRes int icon, @ColorRes int solid,
                          @ColorRes int container, @StringRes int title, @StringRes int subtitle) {
        Toggle t = new Toggle();
        t.key = key;
        t.def = def;
        t.icon = icon;
        t.solid = solid;
        t.container = container;
        t.title = title;
        t.subtitle = subtitle;
        return t;
    }

    private void add(LinearLayout list, final Toggle t) {
        if (list.getChildCount() > 0) list.addView(JourneyUtil.divider(this));
        final UaItemSwitchRowBinding row = UaItemSwitchRowBinding.inflate(LayoutInflater.from(this), list, false);
        row.icon.setImageResource(t.icon);
        MainActivity.tintBadge(row.icon, t.solid, t.container);
        row.title.setText(t.title);
        row.subtitle.setText(t.subtitle);
        row.toggle.setChecked(isOn(t));
        row.toggle.setOnCheckedChangeListener((btn, checked) -> {
            if (suppress) return;
            btn.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            if (checked) enable(t);
            else setSaved(t, false);
        });
        row.getRoot().setOnClickListener(v -> {
            if (row.toggle.isChecked() && needsFix(t)) enable(t);
            else row.toggle.toggle();
        });
        t.row = row;
        toggles.add(t);
        list.addView(row.getRoot());
    }

    private void bindRow(UaItemRowBinding row, @DrawableRes int icon, @ColorRes int solid,
                         @ColorRes int container, CharSequence title, CharSequence subtitle) {
        row.icon.setImageResource(icon);
        MainActivity.tintBadge(row.icon, solid, container);
        row.title.setText(title);
        row.subtitle.setText(subtitle);
    }

    // ------------------------------------------------------------------ enabling

    private void enable(final Toggle t) {
        if (t.confirmBody != 0 && !isOn(t)) {
            final boolean[] accepted = {false};
            new MaterialAlertDialogBuilder(this)
                    .setTitle(t.confirmTitle)
                    .setMessage(t.confirmBody)
                    .setPositiveButton(R.string.jr_ss_enable, (d, w) -> {
                        accepted[0] = true;
                        requestAccess(t);
                    })
                    .setNegativeButton(R.string.jr_cancel, null)
                    .setOnDismissListener(d -> {
                        if (!accepted[0]) setSaved(t, false);
                    })
                    .show();
            return;
        }
        requestAccess(t);
    }

    private void requestAccess(Toggle t) {
        String[] missing = JourneyUtil.missing(this, t.perms);
        if (missing.length > 0) {
            pendingToggle = t;
            permLauncher.launch(missing);
            return;
        }
        if (t.accessibility && !accessibilityOn()) {
            askAccessibility(t);
            return;
        }
        setSaved(t, true);
    }

    private void onPermissions(Map<String, Boolean> result) {
        if (pendingBle) {
            pendingBle = false;
            startActivity(new Intent(this, PanicButtonActivity.class));
            return;
        }
        Toggle t = pendingToggle;
        pendingToggle = null;
        if (t == null) return;
        String[] missing = JourneyUtil.missing(this, t.perms);
        if (missing.length == 0) {
            if (t.accessibility && !accessibilityOn()) askAccessibility(t);
            else setSaved(t, true);
            return;
        }
        boolean permanent = false;
        for (String p : missing) {
            if (!shouldShowRequestPermissionRationale(p)) permanent = true;
        }
        setSaved(t, false);
        explain(t, getString(R.string.jr_ss_denied_body, getString(t.title)), permanent);
    }

    private void askAccessibility(final Toggle t) {
        final boolean[] accepted = {false};
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.jr_ss_accessibility_title)
                .setMessage(R.string.jr_ss_accessibility_body)
                .setPositiveButton(R.string.jr_ss_enable, (d, w) -> {
                    accepted[0] = true;
                    setSaved(t, true);
                    awaitingAccessibility = t;
                    TriggerSetup.openAccessibilitySettings(this);
                })
                .setNegativeButton(R.string.jr_cancel, null)
                .setOnDismissListener(d -> {
                    if (!accepted[0]) {
                        setSaved(t, false);
                        explain(t, getString(R.string.jr_ss_denied_accessibility), false);
                    }
                })
                .show();
    }

    private void explain(Toggle t, String message, boolean offerSettings) {
        MaterialAlertDialogBuilder d = new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.jr_ss_denied_title, getString(t.title)))
                .setMessage(message)
                .setNegativeButton(R.string.jr_ok, null);
        if (offerSettings) {
            d.setPositiveButton(R.string.jr_open_settings, (x, w) -> JourneyUtil.openAppSettings(this));
        }
        d.show();
    }

    private void openPanicButton() {
        String[] missing = JourneyUtil.missing(this, JourneyUtil.perms(TriggerSetup.requiredBlePermissions()));
        if (missing.length > 0) {
            pendingBle = true;
            permLauncher.launch(missing);
        } else {
            startActivity(new Intent(this, PanicButtonActivity.class));
        }
    }

    // ------------------------------------------------------------------ state

    private boolean isOn(Toggle t) {
        return Prefs.get(this).getBoolean(t.key, t.def);
    }

    private boolean needsFix(Toggle t) {
        return JourneyUtil.missing(this, t.perms).length > 0 || (t.accessibility && !accessibilityOn());
    }

    private boolean accessibilityOn() {
        try {
            return TriggerSetup.isAccessibilityEnabled(this);
        } catch (Exception e) {
            return false;
        }
    }

    private void setSaved(Toggle t, boolean on) {
        Prefs.get(this).edit().putBoolean(t.key, on).apply();
        Prefs.notifyChanged(this);
        suppress = true;
        t.row.toggle.setChecked(on);
        suppress = false;
        renderRow(t);
        renderAccessibility();
    }

    private void refresh() {
        suppress = true;
        for (Toggle t : toggles) t.row.toggle.setChecked(isOn(t));
        suppress = false;
        for (Toggle t : toggles) renderRow(t);
        renderAccessibility();

        SharedPreferences p = Prefs.get(this);
        if (panicRow != null) {
            String name = p.getString(Prefs.BLE_BUTTON_NAME, "");
            String address = p.getString(Prefs.BLE_BUTTON_ADDRESS, "");
            panicRow.subtitle.setText(!TextUtils.isEmpty(name) ? getString(R.string.jr_ss_panic_paired, name)
                    : !TextUtils.isEmpty(address) ? getString(R.string.jr_ss_panic_paired, address)
                    : getString(R.string.jr_ss_panic_none));
        }
        if (helperRow != null) {
            helperRow.subtitle.setText(p.getBoolean(Prefs.HELPER_OPT_IN, false)
                    ? R.string.jr_ss_helper_on : R.string.jr_ss_helper_off);
        }
    }

    /** Shows a "needs permission" hint on rows that are on but missing access. */
    private void renderRow(Toggle t) {
        boolean warn = isOn(t) && needsFix(t);
        t.row.subtitle.setText(warn ? getString(R.string.jr_ss_needs_access) : getString(t.subtitle));
        t.row.subtitle.setTextColor(ContextCompat.getColor(this, warn ? R.color.ns_warn : R.color.ns_text_muted));
    }

    private void renderAccessibility() {
        JrItemStatusLineBinding line = accessibilityLine;
        if (line == null) return;
        boolean on = accessibilityOn();
        line.text.setText(on ? R.string.jr_ss_accessibility_on : R.string.jr_ss_accessibility_off);
        ViewCompat.setBackgroundTintList(line.dot, ColorStateList.valueOf(
                ContextCompat.getColor(this, on ? R.color.ns_safe : R.color.ns_warn)));
        JourneyUtil.show(line.action, !on);
    }
}
