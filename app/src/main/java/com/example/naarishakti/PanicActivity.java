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
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.example.naarishakti.core.ProtectionController;
import com.example.naarishakti.security.PinPadView;
import com.example.naarishakti.security.PinStore;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen SOS screen shown over the lock screen. Countdown state lets the user cancel a false
 * alarm; active state shows live progress from VoiceRecognitionService and a press-and-hold
 * "I'm safe" button. All state comes from the service (launch extras, {@link #ACTION_PANIC_UI}
 * broadcasts and {@link VoiceRecognitionService#getPanicStatus()}).
 *
 * With an app PIN set, stopping an active SOS asks for the PIN on an in-screen pad. The duress
 * PIN looks exactly like a normal stop here, while the engine continues covertly. A covert view
 * (opened from the silent "sharing location" notification) lets the owner end a silent SOS.
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

    /** Silent SOS or post-duress: the normal SOS screen must not show. */
    public static final String EXTRA_COVERT = "ns_covert";
    /** The evidence module records video instead of photos. */
    public static final String EXTRA_VIDEO = "ns_video";
    public static final String EXTRA_STROBE = "ns_strobe";
    public static final String EXTRA_SPEAK = "ns_speak";
    /** Call escalation: 1-based position, total planned calls and phase. */
    public static final String EXTRA_CALL_INDEX = "ns_call_index";
    public static final String EXTRA_CALL_TOTAL = "ns_call_total";
    public static final String EXTRA_CALL_PHASE = "ns_call_phase";
    public static final int CALL_PHASE_NONE = 0;
    public static final int CALL_PHASE_RINGING = 1;
    public static final int CALL_PHASE_CONNECTED = 2;
    public static final int CALL_PHASE_NO_ANSWER = 3;

    /** Launch extras: open the PIN pad straight away / show the covert view. */
    public static final String EXTRA_OPEN_PIN = "ns_open_pin";
    public static final String EXTRA_OPEN_COVERT = "ns_open_covert";

    /** Per-step progress values used by EXTRA_SMS / CALL / LOCATION / CAMERA. */
    public static final int STEP_PENDING = 0;
    public static final int STEP_DONE = 1;
    public static final int STEP_FAILED = 2;
    public static final int STEP_OFF = 3;

    private static final long HOLD_TO_STOP_MS = 2_000;
    private static final long STOPPED_SCREEN_MS = 1_800;
    private static final int MAX_PIN_ATTEMPTS = 5;
    private static final long PIN_LOCKOUT_MS = 60_000;

    /** Survive activity recreation for the whole incident. */
    private static int wrongPinAttempts;
    private static long pinLockedUntil;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService pinWorker = Executors.newSingleThreadExecutor();

    private View countdownGroup;
    private View activeGroup;
    private View covertGroup;
    private View pinGroup;
    private View stoppedGroup;
    private TextView countdownNumber;
    private TextView countdownCaption;
    private TextView holdHint;
    private TextView covertHint;
    private PinPadView pinPad;

    private int shownState = -1;
    private int lastSeconds = -1;
    private final List<ValueAnimator> pulses = new ArrayList<>();
    private ValueAnimator holdAnimator;
    @Nullable private Drawable activeFill;
    private boolean stopRequested;
    private boolean covertView;
    private boolean showingStopped;
    private boolean pinChecking;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Bundle extras = intent.getExtras();
            if (extras != null) render(extras);
        }
    };

    private final Runnable lockoutTick = new Runnable() {
        @Override
        public void run() {
            long left = pinLockedUntil - SystemClock.elapsedRealtime();
            if (left <= 0) {
                holdHint.setText(R.string.eng_panic_hold_hint);
                covertHint.setText(R.string.en_covert_hint);
                return;
            }
            String msg = getString(R.string.en_pin_locked_hint, (int) Math.ceil(left / 1000.0));
            holdHint.setText(msg);
            covertHint.setText(msg);
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        // The SOS screen is always dark, whatever theme the user picked: high contrast under stress.
        getDelegate().setLocalNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES);
        super.onCreate(savedInstanceState);
        showOverLockScreen();
        setContentView(R.layout.activity_panic);

        countdownGroup = findViewById(R.id.countdownGroup);
        activeGroup = findViewById(R.id.activeGroup);
        covertGroup = findViewById(R.id.covertGroup);
        pinGroup = findViewById(R.id.pinGroup);
        stoppedGroup = findViewById(R.id.stoppedGroup);
        countdownNumber = findViewById(R.id.countdownNumber);
        countdownCaption = findViewById(R.id.countdownCaption);
        holdHint = findViewById(R.id.holdHint);
        covertHint = findViewById(R.id.covertHint);
        pinPad = findViewById(R.id.panicPinPad);

        MaterialButton cancel = findViewById(R.id.cancelButton);
        cancel.setOnClickListener(v -> {
            ProtectionController.stopPanic(this);
            finish();
        });
        setupHoldToStop(findViewById(R.id.holdToStopButton), findViewById(R.id.holdFill));
        setupHoldToStop(findViewById(R.id.covertHoldButton), findViewById(R.id.covertHoldFill));
        findViewById(R.id.covertClose).setOnClickListener(v -> finish());
        findViewById(R.id.pinBack).setOnClickListener(v -> hidePinPad());
        pinPad.setListener(this::onPinEntered);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // Back never dismisses an SOS; it only closes the PIN pad or the covert view.
                if (pinGroup.getVisibility() == View.VISIBLE) {
                    hidePinPad();
                } else if (covertView && !showingStopped) {
                    finish();
                }
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
        if (showingStopped) return;
        Intent intent = getIntent();
        covertView = intent.getBooleanExtra(EXTRA_OPEN_COVERT, false);
        Bundle status = VoiceRecognitionService.getPanicStatus();
        boolean covert = status != null && status.getBoolean(EXTRA_COVERT, false);
        if (status == null || (covert ? !covertView : !ProtectionController.isPanicActive())) {
            finish();
            return;
        }
        render(status);
        if (intent.getBooleanExtra(EXTRA_OPEN_PIN, false)) {
            intent.removeExtra(EXTRA_OPEN_PIN);
            if (PinStore.isSet(this) && !isLockedOut()) showPinPad();
        }
        if (isLockedOut()) handler.post(lockoutTick);
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
        handler.removeCallbacksAndMessages(null);
        pinWorker.shutdown();
        super.onDestroy();
    }

    // ---- Rendering ----

    private void render(Bundle s) {
        if (showingStopped) return;
        int state = s.getInt(EXTRA_STATE, STATE_ENDED);
        if (state == STATE_ENDED) {
            finish();
            return;
        }
        if (s.getBoolean(EXTRA_COVERT, false)) {
            if (!covertView) {
                finish();
                return;
            }
            showCovert();
            return;
        }
        if (covertView) covertView = false; // an ordinary SOS is running; show the real screen
        covertGroup.setVisibility(View.GONE);
        if (state != shownState) {
            shownState = state;
            boolean countdown = state == STATE_COUNTDOWN;
            countdownGroup.setVisibility(countdown ? View.VISIBLE : View.GONE);
            activeGroup.setVisibility(countdown ? View.GONE : View.VISIBLE);
            getWindow().setStatusBarColor(ContextCompat.getColor(this,
                    countdown ? R.color.ns_bg_top : R.color.ns_rose_deep));
            stopPulses();
            if (countdown) {
                hidePinPad();
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

    private void showCovert() {
        if (shownState == -2) return;
        shownState = -2;
        stopPulses();
        countdownGroup.setVisibility(View.GONE);
        activeGroup.setVisibility(View.GONE);
        covertGroup.setVisibility(View.VISIBLE);
        getWindow().setStatusBarColor(ContextCompat.getColor(this, R.color.ns_bg));
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

        renderCall(s, callName);

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

        // Siren (+ strobe / spoken alert)
        if (s.getBoolean(EXTRA_SIREN, false)) {
            boolean strobe = s.getBoolean(EXTRA_STROBE, false);
            boolean speak = s.getBoolean(EXTRA_SPEAK, false);
            int detail = strobe && speak ? R.string.en_status_siren_detail_both
                    : strobe ? R.string.en_status_siren_detail_strobe
                    : speak ? R.string.en_status_siren_detail_voice
                    : R.string.eng_status_siren_detail;
            row(R.id.titleSiren, R.id.detailSiren, R.id.progressSiren, R.id.stateSiren, STEP_DONE,
                    getString(R.string.eng_status_siren_on), getString(detail));
        } else {
            row(R.id.titleSiren, R.id.detailSiren, R.id.progressSiren, R.id.stateSiren, STEP_FAILED,
                    getString(R.string.eng_status_siren_off), null);
        }

        // Evidence
        int camera = s.getInt(EXTRA_CAMERA, STEP_PENDING);
        int photos = s.getInt(EXTRA_PHOTOS, 0);
        if (s.getBoolean(EXTRA_VIDEO, false)) {
            row(R.id.titleCamera, R.id.detailCamera, R.id.progressCamera, R.id.stateCamera, STEP_PENDING,
                    getString(R.string.en_status_video), getString(R.string.en_status_video_detail));
        } else if (camera == STEP_FAILED) {
            row(R.id.titleCamera, R.id.detailCamera, R.id.progressCamera, R.id.stateCamera, STEP_FAILED,
                    getString(R.string.eng_status_camera_failed), getString(R.string.eng_status_camera_failed_detail));
        } else {
            // Capturing continues for the whole SOS, so this row keeps its spinner.
            row(R.id.titleCamera, R.id.detailCamera, R.id.progressCamera, R.id.stateCamera, STEP_PENDING,
                    getResources().getQuantityString(R.plurals.eng_status_camera_title, photos, photos),
                    getString(R.string.eng_status_camera_detail));
        }
    }

    private void renderCall(Bundle s, String callName) {
        int call = s.getInt(EXTRA_CALL, STEP_PENDING);
        int phase = s.getInt(EXTRA_CALL_PHASE, CALL_PHASE_NONE);
        int index = s.getInt(EXTRA_CALL_INDEX, 1);
        int total = s.getInt(EXTRA_CALL_TOTAL, 1);

        if (phase == CALL_PHASE_CONNECTED) {
            row(R.id.titleCall, R.id.detailCall, R.id.progressCall, R.id.stateCall, STEP_DONE,
                    getString(R.string.en_status_call_connected, callName),
                    getString(R.string.en_status_call_connected_detail));
            return;
        }
        if (phase == CALL_PHASE_NO_ANSWER) {
            row(R.id.titleCall, R.id.detailCall, R.id.progressCall, R.id.stateCall, STEP_FAILED,
                    getString(R.string.en_status_call_no_answer),
                    getString(R.string.en_status_call_no_answer_detail));
            return;
        }
        if (phase == CALL_PHASE_RINGING && call != STEP_OFF) {
            String title = total > 1
                    ? getString(R.string.en_status_call_progress, callName, index, total)
                    : getString(R.string.eng_status_call_pending, callName);
            row(R.id.titleCall, R.id.detailCall, R.id.progressCall, R.id.stateCall, STEP_PENDING,
                    title, getString(index < total
                            ? R.string.en_status_call_progress_detail
                            : R.string.eng_status_call_detail));
            return;
        }
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
    private void setupHoldToStop(MaterialButton button, final View fill) {
        fill.getBackground().setLevel(0);
        button.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    startHold(fill);
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

    private void startHold(final View fill) {
        cancelHold();
        if (isLockedOut()) return;
        final Drawable d = fill.getBackground();
        activeFill = d;
        holdAnimator = ValueAnimator.ofInt(d.getLevel(), 10_000);
        holdAnimator.setDuration(HOLD_TO_STOP_MS * (10_000 - d.getLevel()) / 10_000);
        holdAnimator.setInterpolator(new LinearInterpolator());
        holdAnimator.addUpdateListener(a -> {
            int level = (int) a.getAnimatedValue();
            d.setLevel(level);
            if (level >= 10_000) {
                fill.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
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
        final Drawable d = activeFill;
        if (d == null || d.getLevel() == 0) return;
        ValueAnimator back = ValueAnimator.ofInt(d.getLevel(), 0);
        back.setDuration(250);
        back.addUpdateListener(a -> d.setLevel((int) a.getAnimatedValue()));
        back.start();
    }

    /**
     * With an app PIN: open the PIN pad. Without: stopping an active SOS requires unlocking when
     * the phone is locked with a PIN/pattern.
     */
    private void requestStop() {
        if (stopRequested || showingStopped || isLockedOut()) return;
        if (PinStore.isSet(this)) {
            cancelHold();
            showPinPad();
            return;
        }
        stopRequested = true;
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && km != null && km.isKeyguardLocked()) {
            km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                @Override
                public void onDismissSucceeded() {
                    confirmStop(false);
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
            confirmStop(false);
        }
    }

    /** Tells the engine to stop (or to go covert for the duress PIN); both look identical here. */
    private void confirmStop(boolean duress) {
        stopRequested = true;
        wrongPinAttempts = 0;
        Intent i = new Intent(this, VoiceRecognitionService.class)
                .setAction(duress ? VoiceRecognitionService.ACTION_DURESS : VoiceRecognitionService.ACTION_STOP_VERIFIED);
        try {
            startService(i);
        } catch (Exception e) {
            if (!duress) ProtectionController.stopPanic(this);
        }
        showStopped();
    }

    private void showStopped() {
        showingStopped = true;
        hidePinPad();
        stopPulses();
        getWindow().setStatusBarColor(ContextCompat.getColor(this, R.color.ns_bg));
        stoppedGroup.setVisibility(View.VISIBLE);
        View badge = findViewById(R.id.stoppedBadge);
        badge.setScaleX(0.6f);
        badge.setScaleY(0.6f);
        badge.setAlpha(0f);
        badge.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(420)
                .setInterpolator(new OvershootInterpolator()).start();
        stoppedGroup.performHapticFeedback(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.LONG_PRESS);
        handler.postDelayed(this::finish, STOPPED_SCREEN_MS);
    }

    // ---- PIN pad ----

    private void showPinPad() {
        if (showingStopped || isLockedOut()) return;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        pinPad.clear();
        pinPad.setMessage(null, false);
        pinPad.setPadEnabled(true);
        pinGroup.setAlpha(0f);
        pinGroup.setVisibility(View.VISIBLE);
        pinGroup.animate().alpha(1f).setDuration(180).start();
    }

    private void hidePinPad() {
        if (pinGroup.getVisibility() != View.VISIBLE) return;
        pinGroup.setVisibility(View.GONE);
        pinPad.clear();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    private void onPinEntered(final String pin) {
        if (pinChecking || showingStopped) return;
        pinChecking = true;
        final Context app = getApplicationContext();
        pinWorker.execute(() -> {
            final PinStore.Result r = PinStore.verify(app, pin);
            handler.post(() -> onPinResult(r));
        });
    }

    private void onPinResult(PinStore.Result r) {
        pinChecking = false;
        if (isFinishing() || showingStopped) return;
        switch (r) {
            case OK:
            case NOT_SET:
                confirmStop(false);
                break;
            case DURESS:
                confirmStop(true);
                break;
            default:
                wrongPinAttempts++;
                pinPad.shake();
                if (wrongPinAttempts >= MAX_PIN_ATTEMPTS) {
                    // The SOS simply keeps going.
                    wrongPinAttempts = 0;
                    pinLockedUntil = SystemClock.elapsedRealtime() + PIN_LOCKOUT_MS;
                    handler.postDelayed(this::hidePinPad, 600);
                    handler.post(lockoutTick);
                } else {
                    int left = MAX_PIN_ATTEMPTS - wrongPinAttempts;
                    pinPad.setMessage(getResources().getQuantityString(R.plurals.en_pin_attempts_left, left, left), true);
                }
        }
    }

    private static boolean isLockedOut() {
        return SystemClock.elapsedRealtime() < pinLockedUntil;
    }
}
