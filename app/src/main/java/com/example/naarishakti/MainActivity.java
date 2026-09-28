package com.example.naarishakti;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.example.naarishakti.core.Prefs;
import com.example.naarishakti.core.ProtectionController;
import com.example.naarishakti.databinding.ActivityMainBinding;
import com.example.naarishakti.databinding.UaItemRowBinding;
import com.example.naarishakti.databinding.UaSheetOnboardingBinding;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * App shell: hosts Home and Settings (hide/show, restored correctly after rotation) and runs the
 * one-time permission onboarding. Protection is controlled from HomeFragment via
 * {@link ProtectionController}; this activity no longer touches services or receivers.
 */
public class MainActivity extends AppCompatActivity {

    /** Extra used by notifications/receivers to bring the Home tab to front. */
    public static final String EXTRA_OPEN_HOME = "loadHomeFragment";

    private static final String TAG_HOME = "tab_home";
    private static final String TAG_SETTINGS = "tab_settings";

    private ActivityMainBinding binding;
    private BottomSheetDialog onboardingSheet;
    private ActivityResultLauncher<String[]> onboardingLauncher;

    // ------------------------------------------------------------------ permissions (shared)

    /** Runtime permissions the core safety features need, built for the running OS version. */
    @NonNull
    public static String[] corePermissions() {
        List<String> list = new ArrayList<>(Arrays.asList(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.SEND_SMS,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.CAMERA,
                Manifest.permission.READ_CONTACTS));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        return list.toArray(new String[0]);
    }

    public static boolean hasPermission(@NonNull Context context, @NonNull String permission) {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED;
    }

    @NonNull
    public static List<String> missingPermissions(@NonNull Context context) {
        List<String> missing = new ArrayList<>();
        for (String p : corePermissions()) {
            if (!hasPermission(context, p)) missing.add(p);
        }
        return missing;
    }

    /** Tints a Ns.IconBadge: badge background uses the container colour, glyph uses the solid colour. */
    public static void tintBadge(@NonNull ImageView badge, @ColorRes int solid, @ColorRes int container) {
        Context c = badge.getContext();
        ViewCompat.setBackgroundTintList(badge, ColorStateList.valueOf(ContextCompat.getColor(c, container)));
        ImageViewCompat.setImageTintList(badge, ColorStateList.valueOf(ContextCompat.getColor(c, solid)));
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        onboardingLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(), this::onOnboardingResult);

        binding.bottomNavigation.setOnItemSelectedListener(item -> {
            showTab(item.getItemId());
            return true;
        });
        binding.bottomNavigation.setOnItemReselectedListener(item -> { /* already showing */ });

        if (savedInstanceState == null) {
            // Fresh start: Home is the default tab. After rotation the FragmentManager restores the
            // existing fragments (with their hidden state) and the nav view restores its selection.
            binding.bottomNavigation.getMenu().findItem(R.id.menu_home).setChecked(true);
            showTab(R.id.menu_home);
        }

        maybeShowOnboarding();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra(EXTRA_OPEN_HOME, false)) {
            binding.bottomNavigation.setSelectedItemId(R.id.menu_home);
        }
    }

    @Override
    protected void onDestroy() {
        if (onboardingSheet != null && onboardingSheet.isShowing()) onboardingSheet.dismiss();
        onboardingSheet = null;
        super.onDestroy();
    }

    /** Lets child fragments switch tabs (e.g. "Review permissions"). */
    public void selectTab(int menuItemId) {
        binding.bottomNavigation.setSelectedItemId(menuItemId);
    }

    // ------------------------------------------------------------------ tabs

    private void showTab(int itemId) {
        FragmentManager fm = getSupportFragmentManager();
        if (fm.isStateSaved()) return;

        String tag = itemId == R.id.menu_settings ? TAG_SETTINGS : TAG_HOME;
        Fragment target = fm.findFragmentByTag(tag);

        FragmentTransaction ft = fm.beginTransaction()
                .setReorderingAllowed(true)
                .setCustomAnimations(R.anim.ua_fade_in, R.anim.ua_fade_out);

        for (String other : new String[]{TAG_HOME, TAG_SETTINGS}) {
            if (other.equals(tag)) continue;
            Fragment f = fm.findFragmentByTag(other);
            if (f != null && !f.isHidden()) ft.hide(f);
        }

        if (target == null) {
            target = TAG_SETTINGS.equals(tag) ? new SettingsFragment() : new HomeFragment();
            ft.add(R.id.fragment_container, target, tag);
        } else if (target.isHidden()) {
            ft.show(target);
        }
        ft.commit();
    }

    // ------------------------------------------------------------------ onboarding

    private void maybeShowOnboarding() {
        SharedPreferences prefs = Prefs.get(this);
        if (prefs.getBoolean(Prefs.ONBOARDING_DONE, false)) return;
        if (missingPermissions(this).isEmpty()) {
            markOnboardingDone();
            return;
        }
        binding.getRoot().post(this::showOnboardingSheet);
    }

    private void markOnboardingDone() {
        Prefs.get(this).edit().putBoolean(Prefs.ONBOARDING_DONE, true).apply();
    }

    private void showOnboardingSheet() {
        if (isFinishing() || (onboardingSheet != null && onboardingSheet.isShowing())) return;

        UaSheetOnboardingBinding sheet = UaSheetOnboardingBinding.inflate(getLayoutInflater());
        LinearLayout list = sheet.onbList;
        addOnboardingRow(list, R.drawable.ua_ic_mic, R.color.ns_violet, R.color.ns_violet_container,
                R.string.perm_mic_title, R.string.perm_mic_why);
        addOnboardingRow(list, R.drawable.ua_ic_location, R.color.ns_safe, R.color.ns_safe_container,
                R.string.perm_location_title, R.string.perm_location_why);
        addOnboardingRow(list, R.drawable.ua_ic_sms, R.color.ns_rose, R.color.ns_rose_container,
                R.string.perm_sms_title, R.string.perm_sms_why);
        addOnboardingRow(list, R.drawable.ua_ic_phone, R.color.ns_info, R.color.ns_info_container,
                R.string.perm_phone_title, R.string.perm_phone_why);
        addOnboardingRow(list, R.drawable.ua_ic_camera, R.color.ns_gold, R.color.ns_gold_container,
                R.string.perm_camera_title, R.string.perm_camera_why);
        addOnboardingRow(list, R.drawable.ua_ic_group, R.color.ns_violet, R.color.ns_violet_container,
                R.string.perm_contacts_title, R.string.perm_contacts_why);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            addOnboardingRow(list, R.drawable.ua_ic_notifications, R.color.ns_gold, R.color.ns_gold_container,
                    R.string.perm_notifications_title, R.string.perm_notifications_why);
        }

        final BottomSheetDialog dialog = new BottomSheetDialog(this);
        dialog.setContentView(sheet.getRoot());
        dialog.setOnShowListener(d -> {
            View bottomSheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                ViewCompat.setBackgroundTintList(bottomSheet,
                        ColorStateList.valueOf(ContextCompat.getColor(this, R.color.ns_surface)));
            }
            dialog.getBehavior().setSkipCollapsed(true);
            dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        });
        // Swiping the sheet away counts as "Not now"; a dismiss caused by rotation does not.
        dialog.setOnCancelListener(d -> markOnboardingDone());

        sheet.onbAllow.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            markOnboardingDone();
            dialog.dismiss();
            List<String> missing = missingPermissions(this);
            if (!missing.isEmpty()) onboardingLauncher.launch(missing.toArray(new String[0]));
        });
        sheet.onbLater.setOnClickListener(v -> {
            markOnboardingDone();
            dialog.dismiss();
        });

        onboardingSheet = dialog;
        dialog.show();
    }

    private void addOnboardingRow(LinearLayout parent, @DrawableRes int icon, @ColorRes int solid,
                                  @ColorRes int container, @StringRes int title, @StringRes int why) {
        UaItemRowBinding row = UaItemRowBinding.inflate(LayoutInflater.from(this), parent, false);
        row.getRoot().setBackground(null);
        row.getRoot().setClickable(false);
        row.getRoot().setFocusable(false);
        row.getRoot().setMinimumHeight(0);
        row.getRoot().setPadding(0, dp(10), 0, dp(10));
        row.icon.setImageResource(icon);
        tintBadge(row.icon, solid, container);
        row.title.setText(title);
        row.subtitle.setText(why);
        row.chevron.setVisibility(View.GONE);
        parent.addView(row.getRoot());
    }

    private void onOnboardingResult(Map<String, Boolean> result) {
        List<String> missing = missingPermissions(this);
        Snackbar bar;
        if (missing.isEmpty()) {
            bar = Snackbar.make(binding.getRoot(), R.string.onb_done, Snackbar.LENGTH_LONG);
            if (!ProtectionController.isProtectionWanted(this)) {
                bar.setAction(R.string.action_turn_on, v -> ProtectionController.start(this));
            }
        } else {
            bar = Snackbar.make(binding.getRoot(), R.string.onb_partial, Snackbar.LENGTH_LONG)
                    .setAction(R.string.action_review, v -> selectTab(R.id.menu_settings));
        }
        bar.setAnchorView(binding.bottomNavigation)
                .setActionTextColor(ContextCompat.getColor(this, R.color.ns_rose))
                .show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
