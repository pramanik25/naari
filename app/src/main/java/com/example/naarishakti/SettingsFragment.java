package com.example.naarishakti;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;

import com.example.naarishakti.core.Prefs;
import com.example.naarishakti.databinding.FragmentSettingsBinding;
import com.example.naarishakti.databinding.UaItemPermissionBinding;
import com.example.naarishakti.databinding.UaItemRowBinding;
import com.example.naarishakti.databinding.UaItemSwitchRowBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.Slider;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import Home_Activity.DatabaseViewActivity;
import Home_Activity.ProfileActivity;
import Home_Activity.TimeSettingsActivity;

/**
 * Settings: SOS behaviour, triggers and deactivation method (auto-saved to {@link Prefs} and
 * broadcast with {@link Prefs#notifyChanged}), a live permission checklist, shortcuts and About.
 */
public class SettingsFragment extends Fragment {

    private static final String TAG = "SettingsFragment";

    /** Defaults for the trigger switches (Prefs has no DEFAULT_* for these booleans). */
    public static final boolean DEFAULT_SHAKE_ENABLED = true;
    public static final boolean DEFAULT_POWER_ENABLED = true;

    private FragmentSettingsBinding binding;
    private ActivityResultLauncher<String[]> runtimeLauncher;
    private final List<PermItem> permItems = new ArrayList<>();
    @Nullable private PermItem pendingItem;
    private boolean suppressEvents;

    private interface Check {
        boolean ok();
    }

    private interface FloatConsumer {
        void accept(float value);
    }

    /** One row of the permission checklist. */
    private static final class PermItem {
        @DrawableRes int icon;
        @ColorRes int solid;
        @ColorRes int container;
        @StringRes int title;
        @StringRes int why;
        /** Runtime permissions to request, or null for "special" permissions opened in system settings. */
        @Nullable String[] runtime;
        Check check;
        /** Optional "partially granted" check (e.g. approximate location only). */
        @Nullable Check partial;
        @Nullable Runnable specialFix;
        UaItemPermissionBinding row;
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        runtimeLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(), this::onRuntimeResult);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        binding = FragmentSettingsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setupSosSection();
        setupTriggerSection();
        setupDeactivationSection();
        buildPermissionRows();
        setupMoreSection();
        binding.versionText.setText(getString(R.string.settings_version, BuildConfig.VERSION_NAME));
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshDynamic();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) refreshDynamic();
    }

    @Override
    public void onDestroyView() {
        permItems.clear();
        binding = null;
        super.onDestroyView();
    }

    private void refreshDynamic() {
        if (binding == null || getContext() == null) return;
        refreshPermissions();
        binding.rowSchedule.subtitle.setText(TimeSettingsActivity.buildShortSummary(requireContext()));
    }

    // ------------------------------------------------------------------ SOS behaviour

    private void setupSosSection() {
        SharedPreferences p = prefs();

        bindSlider(binding.countdownSlider,
                safeLong(p, Prefs.CONFIRMATION_TIMEOUT_MS, Prefs.DEFAULT_CONFIRMATION_TIMEOUT_MS) / 1000f,
                v -> {
                    int s = Math.round(v);
                    binding.countdownLabel.setText(s == 0
                            ? getString(R.string.settings_countdown_off)
                            : getString(R.string.settings_countdown_label, s));
                },
                v -> save(prefs().edit().putLong(Prefs.CONFIRMATION_TIMEOUT_MS, Math.round(v) * 1000L)));
    }

    private String formatDuration(int seconds) {
        if (seconds < 60) return getString(R.string.duration_seconds, seconds);
        int min = seconds / 60;
        int sec = seconds % 60;
        return sec == 0 ? getString(R.string.duration_minutes, min)
                : getString(R.string.duration_minutes_seconds, min, sec);
    }

    // ------------------------------------------------------------------ triggers

    private void setupTriggerSection() {
        SharedPreferences p = prefs();

        bindSwitchRow(binding.shakeRow, R.drawable.ua_ic_vibration, R.color.ns_violet, R.color.ns_violet_container,
                getString(R.string.settings_shake_title), getString(R.string.settings_shake_sub),
                p.getBoolean(Prefs.SHAKE_ENABLED, DEFAULT_SHAKE_ENABLED),
                checked -> save(prefs().edit().putBoolean(Prefs.SHAKE_ENABLED, checked)));

        final int presses = safeInt(p, Prefs.REQUIRED_PRESSES, Prefs.DEFAULT_REQUIRED_PRESSES);
        boolean powerOn = p.getBoolean(Prefs.POWER_BUTTON_ENABLED, DEFAULT_POWER_ENABLED);
        bindSwitchRow(binding.powerRow, R.drawable.ua_ic_power, R.color.ns_rose, R.color.ns_rose_container,
                getString(R.string.settings_power_title, presses), getString(R.string.settings_power_sub),
                powerOn,
                checked -> {
                    save(prefs().edit().putBoolean(Prefs.POWER_BUTTON_ENABLED, checked));
                    setPowerOptionsEnabled(checked);
                });
        setPowerOptionsEnabled(powerOn);

        bindSlider(binding.pressesSlider, presses,
                v -> {
                    int n = Math.round(v);
                    binding.pressesLabel.setText(getString(R.string.settings_presses_label, n));
                    binding.powerRow.title.setText(getString(R.string.settings_power_title, n));
                },
                v -> save(prefs().edit().putInt(Prefs.REQUIRED_PRESSES, Math.round(v))));

        bindSlider(binding.windowSlider,
                safeLong(p, Prefs.PRESS_WINDOW_MS, Prefs.DEFAULT_PRESS_WINDOW_MS) / 1000f,
                v -> binding.windowLabel.setText(getString(R.string.settings_window_label, oneDecimal(v))),
                v -> save(prefs().edit().putLong(Prefs.PRESS_WINDOW_MS, Math.round(v * 1000f))));
    }

    private void setPowerOptionsEnabled(boolean enabled) {
        if (binding == null) return;
        binding.powerOptions.animate().alpha(enabled ? 1f : 0.4f).setDuration(180).start();
        binding.pressesSlider.setEnabled(enabled);
        binding.windowSlider.setEnabled(enabled);
    }

    // ------------------------------------------------------------------ deactivation

    private void setupDeactivationSection() {
        SharedPreferences p = prefs();
        String method = p.getString(Prefs.DEACTIVATION_METHOD, Prefs.DEACTIVATION_VOLUME);
        boolean shake = Prefs.DEACTIVATION_SHAKE.equals(method);

        suppressEvents = true;
        binding.deactivationGroup.check(shake ? R.id.deactShake : R.id.deactVolume);
        suppressEvents = false;
        renderDeactivation(shake);

        binding.deactivationGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked || suppressEvents) return;
            boolean isShake = checkedId == R.id.deactShake;
            group.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            save(prefs().edit().putString(Prefs.DEACTIVATION_METHOD,
                    isShake ? Prefs.DEACTIVATION_SHAKE : Prefs.DEACTIVATION_VOLUME));
            renderDeactivation(isShake);
        });

        bindSlider(binding.inputTimeoutSlider,
                safeLong(p, Prefs.INPUT_TIMEOUT_MS, Prefs.DEFAULT_INPUT_TIMEOUT_MS) / 1000f,
                v -> binding.inputTimeoutLabel.setText(getString(R.string.settings_input_label, oneDecimal(v))),
                v -> save(prefs().edit().putLong(Prefs.INPUT_TIMEOUT_MS, Math.round(v * 1000f))));
    }

    private void renderDeactivation(boolean shake) {
        binding.deactivationCaption.setText(shake
                ? R.string.settings_stop_shake_caption : R.string.settings_stop_volume_caption);
        binding.inputTimeoutBlock.setVisibility(shake ? View.GONE : View.VISIBLE);
    }

    // ------------------------------------------------------------------ permissions

    private void buildPermissionRows() {
        final Context ctx = requireContext();
        permItems.clear();
        binding.permissionList.removeAllViews();

        addRuntime(R.drawable.ua_ic_mic, R.color.ns_violet, R.color.ns_violet_container,
                R.string.perm_mic_title, R.string.perm_mic_why, Manifest.permission.RECORD_AUDIO);
        addRuntime(R.drawable.ua_ic_sms, R.color.ns_rose, R.color.ns_rose_container,
                R.string.perm_sms_title, R.string.perm_sms_why, Manifest.permission.SEND_SMS);
        addRuntime(R.drawable.ua_ic_phone, R.color.ns_info, R.color.ns_info_container,
                R.string.perm_phone_title, R.string.perm_phone_why, Manifest.permission.CALL_PHONE);

        PermItem location = addRuntime(R.drawable.ua_ic_location, R.color.ns_safe, R.color.ns_safe_container,
                R.string.perm_location_title, R.string.perm_location_why,
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION);
        location.check = () -> MainActivity.hasPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION);
        location.partial = () -> MainActivity.hasPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION);

        addRuntime(R.drawable.ua_ic_camera, R.color.ns_gold, R.color.ns_gold_container,
                R.string.perm_camera_title, R.string.perm_camera_why, Manifest.permission.CAMERA);
        addRuntime(R.drawable.ua_ic_group, R.color.ns_violet, R.color.ns_violet_container,
                R.string.perm_contacts_title, R.string.perm_contacts_why, Manifest.permission.READ_CONTACTS);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermItem notif = addRuntime(R.drawable.ua_ic_notifications, R.color.ns_gold, R.color.ns_gold_container,
                    R.string.perm_notifications_title, R.string.perm_notifications_why,
                    Manifest.permission.POST_NOTIFICATIONS);
            notif.check = () -> MainActivity.hasPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                    && NotificationManagerCompat.from(ctx).areNotificationsEnabled();
        } else {
            addSpecial(R.drawable.ua_ic_notifications, R.color.ns_gold, R.color.ns_gold_container,
                    R.string.perm_notifications_title, R.string.perm_notifications_why,
                    () -> NotificationManagerCompat.from(ctx).areNotificationsEnabled(),
                    this::openNotificationSettings);
        }

        addSpecial(R.drawable.ua_ic_battery, R.color.ns_safe, R.color.ns_safe_container,
                R.string.perm_battery_title, R.string.perm_battery_why,
                () -> {
                    PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                    return pm == null || pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
                },
                this::openBatterySettings);

        addSpecial(R.drawable.ua_ic_layers, R.color.ns_rose, R.color.ns_rose_container,
                R.string.perm_overlay_title, R.string.perm_overlay_why,
                () -> Settings.canDrawOverlays(ctx),
                () -> openSettings(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri())));

        if (Build.VERSION.SDK_INT >= 34) {
            addSpecial(R.drawable.ua_ic_fullscreen, R.color.ns_info, R.color.ns_info_container,
                    R.string.perm_fullscreen_title, R.string.perm_fullscreen_why,
                    () -> canUseFullScreenIntent(ctx),
                    () -> openSettings(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri())));
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            addSpecial(R.drawable.ua_ic_alarm, R.color.ns_gold, R.color.ns_gold_container,
                    R.string.perm_exact_alarm_title, R.string.perm_exact_alarm_why,
                    () -> canScheduleExactAlarms(ctx),
                    () -> openSettings(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri())));
        }

        LayoutInflater inflater = LayoutInflater.from(ctx);
        for (int i = 0; i < permItems.size(); i++) {
            final PermItem item = permItems.get(i);
            if (i > 0) binding.permissionList.addView(divider());
            UaItemPermissionBinding row = UaItemPermissionBinding.inflate(inflater, binding.permissionList, false);
            row.icon.setImageResource(item.icon);
            MainActivity.tintBadge(row.icon, item.solid, item.container);
            row.title.setText(item.title);
            row.subtitle.setText(item.why);
            row.action.setOnClickListener(v -> fix(item));
            row.getRoot().setOnClickListener(v -> {
                if (!item.check.ok()) fix(item);
            });
            item.row = row;
            binding.permissionList.addView(row.getRoot());
        }
    }

    private PermItem addRuntime(@DrawableRes int icon, @ColorRes int solid, @ColorRes int container,
                                @StringRes int title, @StringRes int why, final String... permissions) {
        final Context ctx = requireContext();
        PermItem item = new PermItem();
        item.icon = icon;
        item.solid = solid;
        item.container = container;
        item.title = title;
        item.why = why;
        item.runtime = permissions;
        item.check = () -> {
            for (String p : permissions) if (!MainActivity.hasPermission(ctx, p)) return false;
            return true;
        };
        permItems.add(item);
        return item;
    }

    private void addSpecial(@DrawableRes int icon, @ColorRes int solid, @ColorRes int container,
                            @StringRes int title, @StringRes int why, Check check, Runnable fix) {
        PermItem item = new PermItem();
        item.icon = icon;
        item.solid = solid;
        item.container = container;
        item.title = title;
        item.why = why;
        item.check = check;
        item.specialFix = fix;
        permItems.add(item);
    }

    private void refreshPermissions() {
        if (binding == null) return;
        int ready = 0;
        for (PermItem item : permItems) {
            boolean ok = item.check.ok();
            boolean partial = !ok && item.partial != null && item.partial.ok();
            if (ok) ready++;
            UaItemPermissionBinding row = item.row;
            @ColorRes int dot = ok ? R.color.ns_safe : (partial ? R.color.ns_warn : R.color.ns_danger);
            ViewCompat.setBackgroundTintList(row.statusDot, ColorStateList.valueOf(color(dot)));
            row.statusText.setText(ok ? R.string.status_allowed
                    : (partial ? R.string.status_limited : R.string.status_not_allowed));
            row.statusText.setTextColor(color(ok ? R.color.ns_safe : R.color.ns_text_muted));
            row.action.setVisibility(ok ? View.GONE : View.VISIBLE);
            row.action.setText(item.runtime != null && !partial ? R.string.action_grant : R.string.action_fix);
            row.getRoot().setClickable(!ok);
        }
        int total = permItems.size();
        binding.permissionSummary.setText(getString(R.string.perm_summary, ready, total));
        ViewCompat.setBackgroundTintList(binding.permissionSummaryDot, ColorStateList.valueOf(
                color(ready == total ? R.color.ns_safe : R.color.ns_warn)));
    }

    private void fix(PermItem item) {
        if (item.runtime != null) {
            pendingItem = item;
            runtimeLauncher.launch(item.runtime);
        } else if (item.specialFix != null) {
            item.specialFix.run();
        }
    }

    private void onRuntimeResult(Map<String, Boolean> result) {
        if (!isAdded()) return;
        refreshPermissions();
        PermItem item = pendingItem;
        pendingItem = null;
        if (item == null || item.runtime == null || item.check.ok()) return;
        for (String p : item.runtime) {
            if (!MainActivity.hasPermission(requireContext(), p) && !shouldShowRequestPermissionRationale(p)) {
                // Denied with "don't ask again" (or twice on Android 11+): only Settings can fix it.
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.perm_denied_title)
                        .setMessage(getString(R.string.perm_denied_body, getString(item.title)))
                        .setPositiveButton(R.string.action_open_settings, (d, w) -> openSettings(
                                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri())))
                        .setNegativeButton(R.string.action_not_now, null)
                        .show();
                return;
            }
        }
    }

    private void openNotificationSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            openSettings(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().getPackageName()));
        } else {
            openSettings(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri()));
        }
    }

    @SuppressLint("BatteryLife")
    private void openBatterySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri()));
        } catch (Exception e) {
            openSettings(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private void openSettings(Intent intent) {
        try {
            startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "Settings screen unavailable: " + intent.getAction(), e);
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri()));
            } catch (Exception ignored) {
                // Nothing else to try.
            }
        }
    }

    private Uri packageUri() {
        return Uri.parse("package:" + requireContext().getPackageName());
    }

    private static boolean canUseFullScreenIntent(Context ctx) {
        if (Build.VERSION.SDK_INT < 34) return true;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        return nm == null || nm.canUseFullScreenIntent();
    }

    private static boolean canScheduleExactAlarms(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        return am == null || am.canScheduleExactAlarms();
    }

    // ------------------------------------------------------------------ more

    private void setupMoreSection() {
        bindRow(binding.rowProfile, R.drawable.ua_ic_person, R.color.ns_rose, R.color.ns_rose_container,
                R.string.profile_overline, getString(R.string.settings_profile_sub), ProfileActivity.class);
        bindRow(binding.rowSchedule, R.drawable.ua_ic_schedule, R.color.ns_gold, R.color.ns_gold_container,
                R.string.row_schedule_title, getString(R.string.schedule_not_scheduled), TimeSettingsActivity.class);
        bindRow(binding.rowVault, R.drawable.ua_ic_photo_library, R.color.ns_violet, R.color.ns_violet_container,
                R.string.row_vault_title, getString(R.string.settings_vault_sub), DatabaseViewActivity.class);

        // Safety tools entry points (journey module).
        bindRow(binding.rowSafetySettings, R.drawable.ua_ic_tune, R.color.ns_rose, R.color.ns_rose_container,
                R.string.jr_settings_row_safety, getString(R.string.jr_settings_row_safety_sub),
                com.example.naarishakti.journey.SafetySettingsActivity.class);
        bindRow(binding.rowAppPin, R.drawable.ua_ic_lock, R.color.ns_safe, R.color.ns_safe_container,
                R.string.jr_settings_row_pin, getString(R.string.jr_settings_row_pin_sub),
                com.example.naarishakti.security.PinActivity.class);
        binding.rowAppPin.getRoot().setOnClickListener(v -> {
            startActivity(com.example.naarishakti.security.PinActivity.setupIntent(requireContext()));
            requireActivity().overridePendingTransition(R.anim.slide_in, R.anim.slide_out);
        });
        bindRow(binding.rowCloud, R.drawable.jr_ic_cloud, R.color.ns_info, R.color.ns_info_container,
                R.string.jr_settings_row_cloud, getString(R.string.jr_settings_row_cloud_sub),
                com.example.naarishakti.cloud.CloudSettingsActivity.class);
        bindRow(binding.rowAppearance, R.drawable.ua_ic_tune, R.color.ns_gold, R.color.ns_gold_container,
                R.string.jr_ss_appearance, getString(R.string.jr_ss_appearance_sub),
                com.example.naarishakti.core.AppearanceActivity.class);
    }

    private void bindRow(UaItemRowBinding row, @DrawableRes int icon, @ColorRes int solid, @ColorRes int container,
                         @StringRes int title, CharSequence subtitle, final Class<?> target) {
        row.icon.setImageResource(icon);
        MainActivity.tintBadge(row.icon, solid, container);
        row.title.setText(title);
        row.subtitle.setText(subtitle);
        row.getRoot().setOnClickListener(v -> {
            startActivity(new Intent(requireContext(), target));
            requireActivity().overridePendingTransition(R.anim.slide_in, R.anim.slide_out);
        });
    }

    // ------------------------------------------------------------------ control helpers

    private interface BoolConsumer {
        void accept(boolean value);
    }

    private void bindSwitchRow(final UaItemSwitchRowBinding row, @DrawableRes int icon, @ColorRes int solid,
                               @ColorRes int container, CharSequence title, CharSequence subtitle,
                               boolean checked, final BoolConsumer onChange) {
        row.icon.setImageResource(icon);
        MainActivity.tintBadge(row.icon, solid, container);
        row.title.setText(title);
        row.subtitle.setText(subtitle);
        row.toggle.setChecked(checked);
        row.toggle.setOnCheckedChangeListener((b, isChecked) -> {
            b.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            onChange.accept(isChecked);
        });
        row.getRoot().setOnClickListener(v -> row.toggle.toggle());
    }

    private void bindSlider(final Slider slider, float initial, final FloatConsumer label,
                            final FloatConsumer onUserChange) {
        float value = snap(slider, initial);
        slider.setValue(value);
        label.accept(value);
        slider.addOnChangeListener((s, v, fromUser) -> {
            label.accept(v);
            if (fromUser) {
                s.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                onUserChange.accept(v);
            }
        });
    }

    /** Clamp to the slider range and align to its step, so setValue() never throws. */
    private static float snap(Slider slider, float value) {
        float from = slider.getValueFrom();
        float to = slider.getValueTo();
        float step = slider.getStepSize();
        float v = Math.max(from, Math.min(to, value));
        if (step > 0f) v = from + Math.round((v - from) / step) * step;
        return Math.max(from, Math.min(to, v));
    }

    private static String oneDecimal(float v) {
        return String.format(Locale.getDefault(), "%.1f", v);
    }

    private void save(SharedPreferences.Editor editor) {
        editor.apply();
        Context ctx = getContext();
        if (ctx != null) Prefs.notifyChanged(ctx);
    }

    private SharedPreferences prefs() {
        return Prefs.get(requireContext());
    }

    /** Reads a long that older builds may have stored as an int. */
    static long safeLong(SharedPreferences p, String key, long def) {
        try {
            return p.getLong(key, def);
        } catch (ClassCastException e) {
            try {
                return p.getInt(key, (int) def);
            } catch (ClassCastException e2) {
                return def;
            }
        }
    }

    /** Reads an int that older builds may have stored as a long. */
    static int safeInt(SharedPreferences p, String key, int def) {
        try {
            return p.getInt(key, def);
        } catch (ClassCastException e) {
            try {
                return (int) p.getLong(key, def);
            } catch (ClassCastException e2) {
                return def;
            }
        }
    }

    private View divider() {
        View v = new View(requireContext());
        ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(getResources().getDisplayMetrics().density)));
        lp.setMarginStart(Math.round(80 * getResources().getDisplayMetrics().density));
        v.setLayoutParams(lp);
        v.setBackgroundColor(color(R.color.ns_stroke));
        return v;
    }

    private int color(@ColorRes int res) {
        return ContextCompat.getColor(requireContext(), res);
    }
}
