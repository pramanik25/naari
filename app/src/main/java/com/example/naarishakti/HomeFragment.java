package com.example.naarishakti;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.example.naarishakti.core.Prefs;
import com.example.naarishakti.core.ProtectionController;
import com.example.naarishakti.databinding.FragmentHomeBinding;
import com.example.naarishakti.databinding.UaItemQuickActionBinding;
import com.example.naarishakti.databinding.UaItemRowBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import Home_Activity.CallPoliceActivity;
import Home_Activity.DatabaseViewActivity;
import Home_Activity.LiveLocationActivity;
import Home_Activity.ProfileActivity;
import Home_Activity.SettingsEmergencyActivity;
import Home_Activity.TimeSettingsActivity;
import Home_Activity.TriggerWordActivity;
import Home_Activity.WomenHelplineActivity;
import Location.GeofenceSettingsActivity;
import SQLite_Database.ProfileDbHelper;

/**
 * Home: greeting, live protection status + switch, press-and-hold SOS hero, setup nudges,
 * quick actions and the safety-kit rows.
 */
public class HomeFragment extends Fragment {

    private static final String TAG = "HomeFragment";
    private static final long HOLD_DURATION_MS = 3000L;
    private static final int HOLD_STEPS = 3;
    /** How long "Starting…" is shown before we assume the services failed to come up. */
    private static final long START_GRACE_MS = 8000L;

    private FragmentHomeBinding binding;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<ObjectAnimator> ringAnimators = new ArrayList<>();

    private ActivityResultLauncher<String[]> micPermissionLauncher;
    private ValueAnimator holdAnimator;
    private int lastHoldStep;
    private boolean suppressSwitchEvents;
    private long startRequestedAt;

    private final Runnable rerenderState = this::renderProtectionState;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            renderProtectionState();
            renderNudges();
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        micPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(), this::onMicPermissionResult);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        binding = FragmentHomeBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setupHeader();
        setupProtectionCard();
        setupSos();
        setupNudges();
        setupQuickActions();
        setupRows();
    }

    @Override
    public void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(ProtectionController.ACTION_STATE_CHANGED);
        filter.addAction(Prefs.ACTION_SETTINGS_UPDATED);
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(stateReceiver, filter);
        if (!isHidden()) startPulse();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshAll();
    }

    @Override
    public void onStop() {
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(stateReceiver);
        stopPulse();
        cancelHold(false);
        super.onStop();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (binding == null) return;
        if (hidden) {
            stopPulse();
            cancelHold(false);
        } else {
            startPulse();
            refreshAll();
        }
    }

    @Override
    public void onDestroyView() {
        main.removeCallbacksAndMessages(null);
        stopPulse();
        if (holdAnimator != null) {
            holdAnimator.removeAllListeners();
            holdAnimator.cancel();
            holdAnimator = null;
        }
        binding = null;
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void refreshAll() {
        if (binding == null) return;
        binding.greeting.setText(greetingRes());
        renderProtectionState();
        renderNudges();
        binding.rowSchedule.subtitle.setText(TimeSettingsActivity.buildShortSummary(requireContext()));
        loadProfileAndVault();
    }

    // ------------------------------------------------------------------ header

    private void setupHeader() {
        binding.avatarButton.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            launch(ProfileActivity.class);
        });
        binding.userName.setText(R.string.home_default_name);
        applyAvatar(null, null);
    }

    @StringRes
    private static int greetingRes() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hour >= 5 && hour < 12) return R.string.greeting_morning;
        if (hour >= 12 && hour < 17) return R.string.greeting_afternoon;
        if (hour >= 17 && hour < 22) return R.string.greeting_evening;
        return R.string.greeting_night;
    }

    /** Reads the profile name/photo and the evidence count off the main thread. */
    private void loadProfileAndVault() {
        final Context app = requireContext().getApplicationContext();
        if (io.isShutdown()) return;
        io.execute(() -> {
            String name = null;
            Bitmap photo = null;
            long evidenceCount = 0;
            try (ProfileDbHelper helper = new ProfileDbHelper(app);
                 SQLiteDatabase db = helper.getReadableDatabase()) {
                try (Cursor c = db.query(ProfileDbHelper.TABLE_NAME,
                        new String[]{ProfileDbHelper.COLUMN_NAME},
                        null, null, null, null, ProfileDbHelper.COLUMN_ID + " DESC", "1")) {
                    if (c.moveToFirst()) name = c.getString(0);
                }
                try (Cursor c = db.query(ProfileDbHelper.TABLE_NAME,
                        new String[]{ProfileDbHelper.COLUMN_PROFILE_IMAGE},
                        null, null, null, null, ProfileDbHelper.COLUMN_ID + " DESC", "1")) {
                    if (c.moveToFirst() && !c.isNull(0)) {
                        byte[] bytes = c.getBlob(0);
                        if (bytes != null && bytes.length > 0) photo = decodeSampled(bytes, 160);
                    }
                } catch (Exception blobError) {
                    // Very old profiles stored full-size PNGs that can exceed the cursor window.
                    Log.w(TAG, "Profile photo unavailable", blobError);
                }
                evidenceCount = DatabaseUtils.queryNumEntries(db, ProfileDbHelper.TABLE_EMERGENCY_IMAGES);
            } catch (Exception e) {
                Log.w(TAG, "Unable to read profile", e);
            }
            final String finalName = name;
            final Bitmap finalPhoto = photo;
            final long finalCount = evidenceCount;
            main.post(() -> {
                if (binding == null) return;
                String first = firstName(finalName);
                binding.userName.setText(first != null ? first : getString(R.string.home_default_name));
                applyAvatar(finalName, finalPhoto);
                binding.rowVault.subtitle.setText(finalCount > 0
                        ? getResources().getQuantityString(R.plurals.row_vault_count, (int) finalCount, (int) finalCount)
                        : getString(R.string.row_vault_empty));
            });
        });
    }

    @Nullable
    private static String firstName(@Nullable String full) {
        if (full == null || full.trim().isEmpty()) return null;
        return full.trim().split("\\s+")[0];
    }

    private void applyAvatar(@Nullable String fullName, @Nullable Bitmap photo) {
        if (binding == null) return;
        ImageView avatar = binding.avatarImage;
        if (photo != null) {
            avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
            avatar.setPadding(0, 0, 0, 0);
            ImageViewCompat.setImageTintList(avatar, null);
            avatar.setImageBitmap(photo);
            binding.avatarInitials.setVisibility(View.GONE);
            return;
        }
        String initials = initials(fullName);
        if (initials != null) {
            avatar.setImageDrawable(null);
            binding.avatarInitials.setText(initials);
            binding.avatarInitials.setVisibility(View.VISIBLE);
        } else {
            int pad = dp(10);
            avatar.setScaleType(ImageView.ScaleType.FIT_CENTER);
            avatar.setPadding(pad, pad, pad, pad);
            avatar.setImageResource(R.drawable.ua_ic_person);
            ImageViewCompat.setImageTintList(avatar,
                    ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.ns_text_muted)));
            binding.avatarInitials.setVisibility(View.GONE);
        }
    }

    @Nullable
    private static String initials(@Nullable String full) {
        if (full == null || full.trim().isEmpty()) return null;
        String[] parts = full.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        sb.append(Character.toUpperCase(parts[0].charAt(0)));
        if (parts.length > 1) sb.append(Character.toUpperCase(parts[parts.length - 1].charAt(0)));
        return sb.toString();
    }

    static Bitmap decodeSampled(byte[] bytes, int reqPx) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= reqPx && o.outHeight / (sample * 2) >= reqPx) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
    }

    // ------------------------------------------------------------------ protection card

    private void setupProtectionCard() {
        binding.protectionSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (suppressSwitchEvents) return;
            button.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            if (checked) {
                requestStartProtection();
            } else {
                startRequestedAt = 0;
                ProtectionController.stop(requireContext());
                renderProtectionState();
            }
        });
        binding.protectionCard.setOnClickListener(v -> {
            Context ctx = requireContext();
            boolean wanted = ProtectionController.isProtectionWanted(ctx);
            boolean active = ProtectionController.isProtectionActive();
            if (wanted && !active && !isStarting()) {
                // "Paused": the user wants protection but the services are not running; restart.
                requestStartProtection();
            } else {
                binding.protectionSwitch.toggle();
            }
        });
    }

    private boolean isStarting() {
        return startRequestedAt > 0 && SystemClock.elapsedRealtime() - startRequestedAt < START_GRACE_MS;
    }

    private void requestStartProtection() {
        Context ctx = requireContext();
        if (MainActivity.hasPermission(ctx, Manifest.permission.RECORD_AUDIO)) {
            startProtection();
            return;
        }
        setSwitchChecked(false);
        new MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.home_mic_needed_title)
                .setMessage(R.string.home_mic_needed_body)
                .setPositiveButton(R.string.action_allow, (d, w) -> launchMicRequest())
                .setNegativeButton(R.string.action_not_now, null)
                .show();
    }

    private void launchMicRequest() {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && !MainActivity.hasPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS)) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        micPermissionLauncher.launch(perms.toArray(new String[0]));
    }

    private void onMicPermissionResult(Map<String, Boolean> result) {
        if (!isAdded()) return;
        if (MainActivity.hasPermission(requireContext(), Manifest.permission.RECORD_AUDIO)) {
            startProtection();
            return;
        }
        if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            // Permanently denied: the only way forward is the app's settings page.
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.perm_denied_title)
                    .setMessage(getString(R.string.perm_denied_body, getString(R.string.perm_mic_title)))
                    .setPositiveButton(R.string.action_open_settings, (d, w) -> openAppSettings())
                    .setNegativeButton(R.string.action_not_now, null)
                    .show();
        } else if (binding != null) {
            showSnack(getString(R.string.home_mic_denied));
        }
    }

    private void openAppSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", requireContext().getPackageName(), null)));
        } catch (Exception e) {
            Log.w(TAG, "No app settings screen", e);
        }
    }

    private void startProtection() {
        startRequestedAt = SystemClock.elapsedRealtime();
        ProtectionController.start(requireContext());
        renderProtectionState();
        main.removeCallbacks(rerenderState);
        main.postDelayed(rerenderState, START_GRACE_MS + 100);
    }

    private void setSwitchChecked(boolean checked) {
        if (binding == null || binding.protectionSwitch.isChecked() == checked) return;
        suppressSwitchEvents = true;
        binding.protectionSwitch.setChecked(checked);
        suppressSwitchEvents = false;
    }

    private void renderProtectionState() {
        if (binding == null || getContext() == null) return;
        Context ctx = requireContext();
        boolean wanted = ProtectionController.isProtectionWanted(ctx);
        boolean active = ProtectionController.isProtectionActive();

        @ColorRes int dot;
        @ColorRes int pillText;
        @StringRes int pill;
        @StringRes int title;
        CharSequence subtitle;
        if (active) {
            dot = R.color.ns_safe;
            pillText = R.color.ns_safe;
            pill = R.string.home_status_protected;
            title = R.string.home_protection_on;
            subtitle = listeningSummary(ctx);
            startRequestedAt = 0;
        } else if (wanted && isStarting()) {
            dot = R.color.ns_warn;
            pillText = R.color.ns_text_muted;
            pill = R.string.home_status_starting;
            title = R.string.home_protection_starting;
            subtitle = getString(R.string.home_protection_starting_sub);
        } else if (wanted) {
            dot = R.color.ns_warn;
            pillText = R.color.ns_warn;
            pill = R.string.home_status_paused;
            title = R.string.home_protection_paused;
            subtitle = getString(R.string.home_protection_paused_sub);
        } else {
            dot = R.color.ns_text_faint;
            pillText = R.color.ns_text_muted;
            pill = R.string.home_status_off;
            title = R.string.home_protection_off;
            subtitle = getString(R.string.home_protection_off_sub);
        }

        setSwitchChecked(active || (wanted && isStarting()));
        ViewCompat.setBackgroundTintList(binding.statusDot, ColorStateList.valueOf(color(dot)));
        binding.statusPillText.setText(pill);
        binding.statusPillText.setTextColor(color(pillText));
        binding.protectionTitle.setText(title);
        binding.protectionSubtitle.setText(subtitle);
        binding.protectionCard.setStrokeColor(ColorStateList.valueOf(
                color(active ? R.color.ns_safe_glow : R.color.ns_stroke)));

        renderPanicState();
    }

    private CharSequence listeningSummary(Context ctx) {
        List<String> parts = new ArrayList<>();
        parts.add(getString(R.string.home_listening_for, Prefs.getTriggerPhrase(ctx)));
        if (Prefs.get(ctx).getBoolean(Prefs.SHAKE_ENABLED, SettingsFragment.DEFAULT_SHAKE_ENABLED)) {
            parts.add(getString(R.string.home_trigger_shake));
        }
        if (Prefs.get(ctx).getBoolean(Prefs.POWER_BUTTON_ENABLED, SettingsFragment.DEFAULT_POWER_ENABLED)) {
            int presses = SettingsFragment.safeInt(Prefs.get(ctx), Prefs.REQUIRED_PRESSES,
                    Prefs.DEFAULT_REQUIRED_PRESSES);
            parts.add(getString(R.string.home_trigger_power, presses));
        }
        return TextUtils.join(" · ", parts);
    }

    // ------------------------------------------------------------------ SOS hero

    @SuppressLint("ClickableViewAccessibility")
    private void setupSos() {
        binding.sosProgress.setMax(1000);
        binding.sosProgress.setProgress(0);
        binding.sosButton.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                    beginHold();
                    return true;
                case MotionEvent.ACTION_UP:
                    v.performClick();
                    cancelHold(true);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    cancelHold(true);
                    return true;
                default:
                    return true;
            }
        });

        // Accessibility: TalkBack users get an explicit "Send SOS" action with a confirmation step.
        ViewCompat.setAccessibilityDelegate(binding.sosButton, new AccessibilityDelegateCompat() {
            @Override
            public void onInitializeAccessibilityNodeInfo(@NonNull View host,
                                                          @NonNull AccessibilityNodeInfoCompat info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.addAction(new AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                        AccessibilityNodeInfoCompat.ACTION_CLICK, getString(R.string.sos_a11y_action)));
            }

            @Override
            public boolean performAccessibilityAction(@NonNull View host, int action, Bundle args) {
                if (action == AccessibilityNodeInfoCompat.ACTION_CLICK) {
                    confirmSosForAccessibility();
                    return true;
                }
                return super.performAccessibilityAction(host, action, args);
            }
        });

        binding.stopSosButton.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            ProtectionController.stopPanic(requireContext());
        });
    }

    private void confirmSosForAccessibility() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.sos_confirm_title)
                .setMessage(R.string.sos_confirm_body)
                .setPositiveButton(R.string.sos_a11y_action, (d, w) -> fireSos())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void beginHold() {
        if (binding == null) return;
        if (holdAnimator != null) {
            holdAnimator.removeAllListeners();
            holdAnimator.cancel();
        }
        lastHoldStep = 0;
        tick(binding.sosButton);
        binding.sosButton.animate().scaleX(0.95f).scaleY(0.95f).setDuration(160)
                .setInterpolator(new DecelerateInterpolator()).start();
        binding.sosHint.setText(getString(R.string.sos_hint_holding, HOLD_STEPS));

        holdAnimator = ValueAnimator.ofInt(0, 1000);
        holdAnimator.setDuration(HOLD_DURATION_MS);
        holdAnimator.setInterpolator(new LinearInterpolator());
        holdAnimator.addUpdateListener(a -> {
            if (binding == null) return;
            int value = (int) a.getAnimatedValue();
            binding.sosProgress.setProgress(value);
            int step = (int) (a.getAnimatedFraction() * HOLD_STEPS);
            if (step > lastHoldStep && step < HOLD_STEPS) {
                lastHoldStep = step;
                tick(binding.sosButton);
                binding.sosHint.setText(getString(R.string.sos_hint_holding, HOLD_STEPS - step));
            }
        });
        holdAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (!cancelled) onHoldComplete();
            }
        });
        holdAnimator.start();
    }

    /** Release before 3 s (or view hidden): roll the ring back without sending anything. */
    private void cancelHold(boolean animate) {
        if (holdAnimator != null && holdAnimator.isRunning()) {
            holdAnimator.cancel();
        }
        holdAnimator = null;
        if (binding == null) return;
        binding.sosButton.animate().scaleX(1f).scaleY(1f).setDuration(200).start();
        resetHoldUi(animate);
    }

    private void resetHoldUi(boolean animate) {
        if (binding == null) return;
        int current = binding.sosProgress.getProgress();
        if (animate && current > 0) {
            ObjectAnimator back = ObjectAnimator.ofInt(binding.sosProgress, "progress", current, 0);
            back.setDuration(220);
            back.setInterpolator(new DecelerateInterpolator());
            back.start();
        } else {
            binding.sosProgress.setProgress(0);
        }
        renderPanicState();
    }

    private void onHoldComplete() {
        holdAnimator = null;
        if (binding == null) return;
        fireSos();
        binding.sosButton.animate().scaleX(1.06f).scaleY(1.06f).setDuration(120)
                .withEndAction(() -> {
                    if (binding != null) {
                        binding.sosButton.animate().scaleX(1f).scaleY(1f).setDuration(220).start();
                    }
                }).start();
        resetHoldUi(true);
    }

    private void fireSos() {
        Context ctx = getContext();
        if (ctx == null) return;
        strongHaptic(ctx);
        ProtectionController.triggerPanic(ctx, "sos_button");
        showSnack(getString(Prefs.getContacts(ctx).isEmpty()
                ? R.string.sos_sent_no_contacts : R.string.sos_sent));
    }

    private void renderPanicState() {
        if (binding == null) return;
        boolean panic = ProtectionController.isPanicActive();
        boolean holding = holdAnimator != null && holdAnimator.isRunning();
        if (!holding) {
            binding.sosHint.setText(panic ? R.string.sos_hint_active : R.string.sos_hint);
        }
        binding.sosCaption.setText(panic ? R.string.sos_caption_active : R.string.sos_caption);
        binding.stopSosButton.setVisibility(panic ? View.VISIBLE : View.GONE);
    }

    private void startPulse() {
        if (binding == null || !ringAnimators.isEmpty()) return;
        View[] rings = {binding.sosRing1, binding.sosRing2, binding.sosRing3};
        for (int i = 0; i < rings.length; i++) {
            ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(rings[i],
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.35f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.35f),
                    PropertyValuesHolder.ofFloat(View.ALPHA, 0.6f, 0f));
            a.setDuration(2700);
            a.setStartDelay(i * 900L);
            a.setRepeatCount(ValueAnimator.INFINITE);
            a.setInterpolator(new DecelerateInterpolator(1.4f));
            a.start();
            ringAnimators.add(a);
        }
    }

    private void stopPulse() {
        for (ObjectAnimator a : ringAnimators) a.cancel();
        ringAnimators.clear();
        if (binding != null) {
            for (View ring : new View[]{binding.sosRing1, binding.sosRing2, binding.sosRing3}) {
                ring.setAlpha(0f);
                ring.setScaleX(1f);
                ring.setScaleY(1f);
            }
        }
    }

    // ------------------------------------------------------------------ nudges, tiles, rows

    private void setupNudges() {
        bindRow(binding.contactsNudgeRow, R.drawable.ua_ic_warning, R.color.ns_warn, R.color.ns_warn_container,
                getString(R.string.nudge_contacts_title), getString(R.string.nudge_contacts_sub));
        binding.contactsNudgeRow.getRoot().setOnClickListener(v -> launch(SettingsEmergencyActivity.class));

        bindRow(binding.triggerNudgeRow, R.drawable.ua_ic_mic, R.color.ns_violet, R.color.ns_violet_container,
                getString(R.string.nudge_trigger_title), "");
        binding.triggerNudgeRow.getRoot().setOnClickListener(v -> launch(TriggerWordActivity.class));
    }

    private void renderNudges() {
        if (binding == null || getContext() == null) return;
        Context ctx = requireContext();
        boolean noContacts = Prefs.getContacts(ctx).isEmpty();
        String phrase = Prefs.get(ctx).getString(Prefs.TRIGGER_PHRASE, null);
        boolean noTrigger = phrase == null || phrase.trim().isEmpty();
        binding.contactsNudge.setVisibility(noContacts ? View.VISIBLE : View.GONE);
        binding.triggerNudge.setVisibility(noTrigger ? View.VISIBLE : View.GONE);
        binding.triggerNudgeRow.subtitle.setText(
                getString(R.string.nudge_trigger_sub, Prefs.DEFAULT_TRIGGER_PHRASE));
    }

    private void setupQuickActions() {
        bindTile(binding.qaTrigger, R.drawable.ua_ic_mic, R.color.ns_violet, R.color.ns_violet_container,
                R.string.qa_trigger_title, R.string.qa_trigger_caption, TriggerWordActivity.class);
        bindTile(binding.qaContacts, R.drawable.ua_ic_group, R.color.ns_rose, R.color.ns_rose_container,
                R.string.qa_contacts_title, R.string.qa_contacts_caption, SettingsEmergencyActivity.class);
        bindTile(binding.qaPolice, R.drawable.ua_ic_police, R.color.ns_info, R.color.ns_info_container,
                R.string.qa_police_title, R.string.qa_police_caption, CallPoliceActivity.class);
        bindTile(binding.qaHelplines, R.drawable.ua_ic_headset, R.color.ns_gold, R.color.ns_gold_container,
                R.string.qa_helplines_title, R.string.qa_helplines_caption, WomenHelplineActivity.class);
        bindTile(binding.qaLiveLocation, R.drawable.ua_ic_near_me, R.color.ns_safe, R.color.ns_safe_container,
                R.string.qa_live_location_title, R.string.qa_live_location_caption, LiveLocationActivity.class);
        bindTile(binding.qaSafeZones, R.drawable.ua_ic_radar, R.color.ns_violet, R.color.ns_violet_container,
                R.string.qa_safe_zones_title, R.string.qa_safe_zones_caption, GeofenceSettingsActivity.class);
    }

    private void setupRows() {
        bindRow(binding.rowSchedule, R.drawable.ua_ic_schedule, R.color.ns_gold, R.color.ns_gold_container,
                getString(R.string.row_schedule_title), getString(R.string.schedule_not_scheduled));
        binding.rowSchedule.getRoot().setOnClickListener(v -> launch(TimeSettingsActivity.class));

        bindRow(binding.rowVault, R.drawable.ua_ic_photo_library, R.color.ns_violet, R.color.ns_violet_container,
                getString(R.string.row_vault_title), getString(R.string.row_vault_empty));
        binding.rowVault.getRoot().setOnClickListener(v -> launch(DatabaseViewActivity.class));
    }

    private void bindTile(UaItemQuickActionBinding tile, @DrawableRes int icon, @ColorRes int solid,
                          @ColorRes int container, @StringRes int title, @StringRes int caption,
                          final Class<?> target) {
        tile.icon.setImageResource(icon);
        MainActivity.tintBadge(tile.icon, solid, container);
        tile.title.setText(title);
        tile.caption.setText(caption);
        tile.getRoot().setContentDescription(getString(title) + ". " + getString(caption));
        tile.getRoot().setOnClickListener(v -> launch(target));
    }

    private void bindRow(UaItemRowBinding row, @DrawableRes int icon, @ColorRes int solid,
                         @ColorRes int container, CharSequence title, CharSequence subtitle) {
        row.icon.setImageResource(icon);
        MainActivity.tintBadge(row.icon, solid, container);
        row.title.setText(title);
        row.subtitle.setText(subtitle);
    }

    // ------------------------------------------------------------------ helpers

    private void launch(Class<?> activity) {
        if (getContext() == null) return;
        startActivity(new Intent(requireContext(), activity));
        requireActivity().overridePendingTransition(R.anim.slide_in, R.anim.slide_out);
    }

    private void showSnack(CharSequence text) {
        if (binding == null) return;
        Snackbar bar = Snackbar.make(binding.getRoot(), text, Snackbar.LENGTH_LONG);
        View nav = requireActivity().findViewById(R.id.bottom_navigation);
        if (nav != null) bar.setAnchorView(nav);
        bar.show();
    }

    private static void tick(View v) {
        v.performHapticFeedback(Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                ? HapticFeedbackConstants.CLOCK_TICK : HapticFeedbackConstants.VIRTUAL_KEY);
    }

    @SuppressWarnings("deprecation")
    private static void strongHaptic(Context ctx) {
        Vibrator vibrator = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 120, 80, 260}, -1));
            } else {
                vibrator.vibrate(new long[]{0, 120, 80, 260}, -1);
            }
        } catch (SecurityException ignored) {
            // VIBRATE is declared in the manifest; ignore OEM oddities.
        }
    }

    private int color(@ColorRes int res) {
        return ContextCompat.getColor(requireContext(), res);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
