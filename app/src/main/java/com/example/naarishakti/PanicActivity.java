package com.example.naarishakti;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.example.naarishakti.core.ProtectionController;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen SOS screen shown over the lock screen. Countdown state lets the user cancel a false
 * alarm; active state shows live progress from VoiceRecognitionService and a press-and-hold
 * "I'm safe" button. All state comes from the service (launch extras, {@link #ACTION_PANIC_UI}
 * broadcasts and {@link VoiceRecognitionService#getPanicStatus()}).
 */
public class PanicActivity extends AppCompatActivity {

    /** Local broadcast from the engine carrying the full status as extras. */
    public static final String ACTION_PANIC_UI = "com.example.naarishakti.ACTION_PANIC_UI";

    public static final String EXTRA_STATE = "ns_panic_state";
    public static final int STATE_ENDED = 0;
    public static final int STATE_COUNTDOWN = 1;
    public static final int STATE_ACTIVE = 2;

    public static final String EXTRA_SECONDS_LEFT = "ns_seconds_left";
    public static final String EXTRA_CONTACT_COUNT = "ns_contact_count";
    public static final String EXTRA_SMS = "ns_sms";
    public static final String EXTRA_SMS_SENT = "ns_sms_sent";
    public static final String EXTRA_SMS_WITH_LOCATION = "ns_sms_with_location";
    public static final String EXTRA_CALL = "ns_call";
    public static final String EXTRA_CALL_NAME = "ns_call_name";
    public static final String EXTRA_LOCATION = "ns_location";
    public static final String EXTRA_SIREN = "ns_siren";
    public static final String EXTRA_CAMERA = "ns_camera";
    public static final String EXTRA_PHOTOS = "ns_photos";

    /** Per-step progress values used by EXTRA_SMS / CALL / LOCATION / CAMERA. */
    public static final int STEP_PENDING = 0;
    public static final int STEP_DONE = 1;
    public static final int STEP_FAILED = 2;
    public static final int STEP_OFF = 3;

    private static final long HOLD_TO_STOP_MS = 2_000;

    private View countdownGroup;
    private View activeGroup;
    private TextView countdownNumber;
    private TextView countdownCaption;
    private View holdFill;
    private Drawable holdFillDrawable;

    private int shownState = -1;
    private int lastSeconds = -1;
    private final List<ValueAnimator> pulses = new ArrayList<>();
    private ValueAnimator holdAnimator;
    private boolean stopRequested;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Bundle extras = intent.getExtras();
            if (extras != null) render(extras);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        showOverLockScreen();
        setContentView(R.layout.activity_panic);

        countdownGroup = findViewById(R.id.countdownGroup);
        activeGroup = findViewById(R.id.activeGroup);
        countdownNumber = findViewById(R.id.countdownNumber);
        countdownCaption = findViewById(R.id.countdownCaption);
        holdFill = findViewById(R.id.holdFill);
        holdFillDrawable = holdFill.getBackground();
        holdFillDrawable.setLevel(0);

        MaterialButton cancel = findViewById(R.id.cancelButton);
        cancel.setOnClickListener(v -> {
            ProtectionController.stopPanic(this);
            finish();
        });
        setupHoldToStop(findViewById(R.id.holdToStopButton));

        // Back never dismisses the SOS screen; cancelling or stopping is always explicit.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // Intentionally ignored.
            }
        });
    }

    private void showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        stopRequested = false;
    }

    @Override
    protected void onStart() {
        super.onStart();
        LocalBroadcastManager.getInstance(this)
                .registerReceiver(statusReceiver, new IntentFilter(ACTION_PANIC_UI));
        Bundle status = VoiceRecognitionService.getPanicStatus();
        if (status == null) status = getIntent().getExtras();
        if (status == null || !ProtectionController.isPanicActive()) {
            finish();
            return;
        }
        render(status);
    }

    @Override
    protected void onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver);
        cancelHold();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        stopPulses();
        super.onDestroy();
    }

    // ---- Rendering ----

    private void render(Bundle s) {
        int state = s.getInt(EXTRA_STATE, STATE_ENDED);
        if (state == STATE_ENDED) {
            finish();
            return;
        }
        if (state != shownState) {
            shownState = state;
            boolean countdown = state == STATE_COUNTDOWN;
            countdownGroup.setVisibility(countdown ? View.VISIBLE : View.GONE);
            activeGroup.setVisibility(countdown ? View.GONE : View.VISIBLE);
            getWindow().setStatusBarColor(ContextCompat.getColor(this,
                    countdown ? R.color.ns_bg_top : R.color.ns_rose_deep));
            stopPulses();
            if (countdown) {
                startPulse(findViewById(R.id.countdownRingInner), 0);
                startPulse(findViewById(R.id.countdownRingMiddle), 500);
                startPulse(findViewById(R.id.countdownRingOuter), 1000);
            } else {
                startPulse(findViewById(R.id.activeRingInner), 0);
                startPulse(findViewById(R.id.activeRingOuter), 700);
            }
        }
        if (state == STATE_COUNTDOWN) {
            renderCountdown(s);
        } else {
            renderActive(s);
        }
    }

    private void renderCountdown(Bundle s) {
        int seconds = Math.max(0, s.getInt(EXTRA_SECONDS_LEFT, 0));
        if (seconds != lastSeconds) {
            lastSeconds = seconds;
            countdownNumber.setText(String.valueOf(seconds));
            countdownNumber.setScaleX(1.25f);
            countdownNumber.setScaleY(1.25f);
            countdownNumber.animate().scaleX(1f).scaleY(1f).setDuration(350)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }
        int contacts = s.getInt(EXTRA_CONTACT_COUNT, 0);
        countdownCaption.setText(contacts > 0
                ? getResources().getQuantityString(R.plurals.eng_panic_countdown_caption, contacts, contacts)
                : getString(R.string.eng_panic_countdown_no_contacts));
    }

    private void renderActive(Bundle s) {
        int contacts = s.getInt(EXTRA_CONTACT_COUNT, 0);
        String callName = s.getString(EXTRA_CALL_NAME);
        if (callName == null) callName = "";

        // SMS
        int sms = s.getInt(EXTRA_SMS, STEP_PENDING);
        int sent = s.getInt(EXTRA_SMS_SENT, contacts);
        switch (sms) {
            case STEP_DONE:
                row(R.id.titleSms, R.id.detailSms, R.id.progressSms, R.id.stateSms, STEP_DONE,
                        getResources().getQuantityString(R.plurals.eng_status_sms_done, sent, sent),
                        getString(s.getBoolean(EXTRA_SMS_WITH_LOCATION)
                                ? R.string.eng_status_sms_detail_location
                                : R.string.eng_status_sms_detail_no_location));
                break;
            case STEP_FAILED:
                row(R.id.titleSms, R.id.detailSms, R.id.progressSms, R.id.stateSms, STEP_FAILED,
                        getString(R.string.eng_status_sms_failed), getString(R.string.eng_status_sms_failed_detail));
                break;
            case STEP_OFF:
                row(R.id.titleSms, R.id.detailSms, R.id.progressSms, R.id.stateSms, STEP_OFF,
                        getString(R.string.eng_status_sms_none), getString(R.string.eng_status_sms_none_detail));
                break;
            default:
                row(R.id.titleSms, R.id.detailSms, R.id.progressSms, R.id.stateSms, STEP_PENDING,
                        getString(R.string.eng_status_sms_pending), null);
        }

        // Call
        int call = s.getInt(EXTRA_CALL, STEP_PENDING);
        switch (call) {
            case STEP_DONE:
                row(R.id.titleCall, R.id.detailCall, R.id.progressCall, R.id.stateCall, STEP_DONE,
                        getString(R.string.eng_status_call_done, callName), getString(R.string.eng_status_call_detail));
                break;
            case STEP_FAILED:
                row(R.id.titleCall, R.id.detailCall, R.id.progressCall, R.id.stateCall, STEP_FAILED,
                        getString(R.string.eng_status_call_failed, callName),
                        getString(R.string.eng_status_call_failed_detail));
                break;
            case STEP_OFF:
                row(R.id.titleCall, R.id.detailCall, R.id.progressCall, R.id.stateCall, STEP_OFF,
                        getString(R.string.eng_status_call_none), null);
                break;
            default:
                row(R.id.titleCall, R.id.detailCall, R.id.progressCall, R.id.stateCall, STEP_PENDING,
                        getString(R.string.eng_status_call_pending, callName), null);
        }

        // Location
        int location = s.getInt(EXTRA_LOCATION, STEP_PENDING);
        if (location == STEP_DONE) {
            row(R.id.titleLocation, R.id.detailLocation, R.id.progressLocation, R.id.stateLocation, STEP_DONE,
                    getString(R.string.eng_status_location_done), getString(R.string.eng_status_location_detail));
        } else if (location == STEP_FAILED) {
            row(R.id.titleLocation, R.id.detailLocation, R.id.progressLocation, R.id.stateLocation, STEP_FAILED,
                    getString(R.string.eng_status_location_failed),
                    getString(R.string.eng_status_location_failed_detail));
        } else {
            row(R.id.titleLocation, R.id.detailLocation, R.id.progressLocation, R.id.stateLocation, STEP_PENDING,
                    getString(R.string.eng_status_location_pending), null);
        }

        // Siren
        if (s.getBoolean(EXTRA_SIREN, false)) {
            row(R.id.titleSiren, R.id.detailSiren, R.id.progressSiren, R.id.stateSiren, STEP_DONE,
                    getString(R.string.eng_status_siren_on), getString(R.string.eng_status_siren_detail));
        } else {
            row(R.id.titleSiren, R.id.detailSiren, R.id.progressSiren, R.id.stateSiren, STEP_FAILED,
                    getString(R.string.eng_status_siren_off), null);
        }

        // Evidence
        int camera = s.getInt(EXTRA_CAMERA, STEP_PENDING);
        int photos = s.getInt(EXTRA_PHOTOS, 0);
        if (camera == STEP_FAILED) {
            row(R.id.titleCamera, R.id.detailCamera, R.id.progressCamera, R.id.stateCamera, STEP_FAILED,
                    getString(R.string.eng_status_camera_failed), getString(R.string.eng_status_camera_failed_detail));
        } else {
            // Capturing continues for the whole SOS, so this row keeps its spinner.
            row(R.id.titleCamera, R.id.detailCamera, R.id.progressCamera, R.id.stateCamera, STEP_PENDING,
                    getResources().getQuantityString(R.plurals.eng_status_camera_title, photos, photos),
                    getString(R.string.eng_status_camera_detail));
        }
    }

    private void row(int titleId, int detailId, int progressId, int stateId, int step,
                     String title, @Nullable String detail) {
        TextView titleView = findViewById(titleId);
        TextView detailView = findViewById(detailId);
        View progress = findViewById(progressId);
        ImageView stateIcon = findViewById(stateId);

        titleView.setText(title);
        titleView.setTextColor(ContextCompat.getColor(this, step == STEP_OFF ? R.color.ns_text_muted : R.color.ns_text));
        detailView.setText(detail);
        detailView.setVisibility(detail == null ? View.GONE : View.VISIBLE);

        progress.setVisibility(step == STEP_PENDING ? View.VISIBLE : View.GONE);
        if (step == STEP_DONE || step == STEP_FAILED) {
            stateIcon.setVisibility(View.VISIBLE);
            stateIcon.setImageResource(step == STEP_DONE ? R.drawable.eng_ic_check : R.drawable.eng_ic_warning);
            ImageViewCompat.setImageTintList(stateIcon, ContextCompat.getColorStateList(this,
                    step == STEP_DONE ? R.color.ns_safe : R.color.ns_warn));
        } else {
            stateIcon.setVisibility(View.GONE);
        }
    }

    // ---- Animations ----

    private void startPulse(View ring, long delay) {
        ObjectAnimator pulse = ObjectAnimator.ofPropertyValuesHolder(ring,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 0.85f, 1.2f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.85f, 1.2f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 0.9f, 0f));
        pulse.setDuration(1800);
        pulse.setStartDelay(delay);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setInterpolator(new AccelerateDecelerateInterpolator());
        pulse.start();
        pulses.add(pulse);
    }

    private void stopPulses() {
        for (ValueAnimator a : pulses) a.cancel();
        pulses.clear();
    }

    // ---- Hold to stop ----

    @SuppressLint("ClickableViewAccessibility") // the long-hold is the accessible action (see below)
    private void setupHoldToStop(MaterialButton button) {
        button.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    startHold();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    cancelHold();
                    return true;
                default:
                    return true;
            }
        });
        // Screen-reader users double-tap and hold, which arrives as a long click.
        button.setOnLongClickListener(v -> {
            requestStop();
            return true;
        });
    }

    private void startHold() {
        cancelHold();
        holdAnimator = ValueAnimator.ofInt(holdFillDrawable.getLevel(), 10_000);
        holdAnimator.setDuration(HOLD_TO_STOP_MS * (10_000 - holdFillDrawable.getLevel()) / 10_000);
        holdAnimator.setInterpolator(new LinearInterpolator());
        holdAnimator.addUpdateListener(a -> {
            int level = (int) a.getAnimatedValue();
            holdFillDrawable.setLevel(level);
            if (level >= 10_000) {
                holdFill.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                requestStop();
            }
        });
        holdAnimator.start();
    }

    private void cancelHold() {
        if (holdAnimator != null) {
            holdAnimator.cancel();
            holdAnimator = null;
        }
        if (holdFillDrawable == null || holdFillDrawable.getLevel() == 0) return;
        ValueAnimator back = ValueAnimator.ofInt(holdFillDrawable.getLevel(), 0);
        back.setDuration(250);
        back.addUpdateListener(a -> holdFillDrawable.setLevel((int) a.getAnimatedValue()));
        back.start();
    }

    /** Stopping an active SOS requires unlocking when the phone is locked with a PIN/pattern. */
    private void requestStop() {
        if (stopRequested) return;
        stopRequested = true;
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && km != null && km.isKeyguardLocked()) {
            km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                @Override
                public void onDismissSucceeded() {
                    ProtectionController.stopPanic(PanicActivity.this);
                }

                @Override
                public void onDismissCancelled() {
                    stopRequested = false;
                    cancelHold();
                }

                @Override
                public void onDismissError() {
                    stopRequested = false;
                    cancelHold();
                }
            });
        } else {
            ProtectionController.stopPanic(this);
        }
    }
}
