package com.example.naarishakti.escape;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.OvershootInterpolator;
import android.widget.Chronometer;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;

import com.example.naarishakti.R;

import java.util.Locale;

/**
 * Realistic incoming-call screen (stock dialer look). Tap or swipe up Answer, or tap Decline.
 * Answering shows an in-call screen with a running timer, Mute / Keypad / Speaker toggles and End.
 */
public class FakeCallActivity extends AppCompatActivity {

    private static final int[] AVATAR_COLORS = {
            R.color.en_call_avatar_1, R.color.en_call_avatar_2, R.color.en_call_avatar_3,
            R.color.en_call_avatar_4, R.color.en_call_avatar_5};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private View ringingGroup;
    private View inCallGroup;
    private TextView status;
    private TextView label;
    private Chronometer timer;
    private View acceptButton;
    private ObjectAnimator acceptBounce;
    private boolean inCall;
    private boolean ended;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        showOverLockScreen();
        setContentView(R.layout.en_activity_fake_call);
        getWindow().setStatusBarColor(ContextCompat.getColor(this, R.color.en_call_bg_top));
        getWindow().setNavigationBarColor(ContextCompat.getColor(this, R.color.en_call_bg_bottom));

        ringingGroup = findViewById(R.id.ringingGroup);
        inCallGroup = findViewById(R.id.inCallGroup);
        status = findViewById(R.id.callStatus);
        label = findViewById(R.id.callLabel);
        timer = findViewById(R.id.callTimer);
        acceptButton = findViewById(R.id.acceptButton);

        findViewById(R.id.declineButton).setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            endCall(false);
        });
        setupAccept();
        setupToggle(R.id.muteButton);
        setupToggle(R.id.keypadButton);
        setupToggle(R.id.speakerButton);
        findViewById(R.id.endButton).setOnClickListener(v -> endCall(true));

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // Like a real call screen: back does nothing while ringing; in a call it hides the screen.
                if (inCall) moveTaskToBack(true);
            }
        });
        bind(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        bind(intent);
    }

    private void bind(Intent intent) {
        String name = intent.getStringExtra(FakeCall.EXTRA_NAME);
        if (TextUtils.isEmpty(name)) name = FakeCall.callerName(this);
        ((TextView) findViewById(R.id.callName)).setText(name);
        ((TextView) findViewById(R.id.callInitials)).setText(initials(name));
        int color = AVATAR_COLORS[(name.hashCode() & 0x7fffffff) % AVATAR_COLORS.length];
        ViewCompat.setBackgroundTintList(findViewById(R.id.callAvatar),
                ColorStateList.valueOf(ContextCompat.getColor(this, color)));

        if (intent.getBooleanExtra(FakeCall.EXTRA_ANSWER, false)) {
            answer();
        } else if (!inCall) {
            showRinging();
        }
    }

    private void showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    // ---- Ringing ----

    private void showRinging() {
        ringingGroup.setVisibility(View.VISIBLE);
        inCallGroup.setVisibility(View.GONE);
        status.setText(R.string.en_fake_incoming);
        label.setVisibility(View.VISIBLE);
        timer.setVisibility(View.GONE);
        FakeCall.Ringer.start(this);
        if (acceptBounce == null) {
            acceptBounce = ObjectAnimator.ofFloat(acceptButton, View.TRANSLATION_Y, 0, -dp(14), 0);
            acceptBounce.setDuration(1100);
            acceptBounce.setRepeatCount(ValueAnimator.INFINITE);
            acceptBounce.setInterpolator(new OvershootInterpolator());
            acceptBounce.start();
        }
        // Missed call after the ringer's timeout, like a real phone.
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(() -> {
            if (!inCall) endCall(false);
        }, FakeCall.Ringer.MAX_RING_MS);
    }

    @SuppressLint("ClickableViewAccessibility") // tapping still performs a normal click
    private void setupAccept() {
        acceptButton.setOnClickListener(v -> answer());
        final float threshold = dp(90);
        acceptButton.setOnTouchListener(new View.OnTouchListener() {
            private float downY;
            private boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downY = e.getRawY();
                        dragging = false;
                        if (acceptBounce != null) acceptBounce.pause();
                        return false;
                    case MotionEvent.ACTION_MOVE: {
                        float dy = Math.min(0, e.getRawY() - downY);
                        if (dy < -dp(8)) dragging = true;
                        if (dragging) {
                            v.setTranslationY(dy);
                            if (dy < -threshold) {
                                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                                answer();
                            }
                            return true;
                        }
                        return false;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (dragging) {
                            v.animate().translationY(0).setDuration(200).start();
                            if (acceptBounce != null && !inCall) acceptBounce.resume();
                            return true;
                        }
                        if (acceptBounce != null && !inCall) acceptBounce.resume();
                        return false;
                    default:
                        return false;
                }
            }
        });
    }

    // ---- In call ----

    private void answer() {
        if (inCall || ended) return;
        inCall = true;
        FakeCall.dismiss(this);
        handler.removeCallbacksAndMessages(null);
        if (acceptBounce != null) {
            acceptBounce.cancel();
            acceptBounce = null;
        }
        ringingGroup.setVisibility(View.GONE);
        inCallGroup.setVisibility(View.VISIBLE);
        status.setVisibility(View.GONE);
        label.setVisibility(View.GONE);
        timer.setVisibility(View.VISIBLE);
        timer.setBase(SystemClock.elapsedRealtime());
        timer.start();
    }

    private void setupToggle(int id) {
        findViewById(id).setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            v.setSelected(!v.isSelected());
        });
    }

    private void endCall(boolean wasInCall) {
        if (ended) return;
        ended = true;
        FakeCall.dismiss(this);
        handler.removeCallbacksAndMessages(null);
        if (acceptBounce != null) acceptBounce.cancel();
        timer.stop();
        status.setVisibility(View.VISIBLE);
        status.setText(R.string.en_fake_ended);
        ringingGroup.setVisibility(View.GONE);
        inCallGroup.setVisibility(View.GONE);
        handler.postDelayed(this::finishAndRemoveTask, wasInCall ? 1200 : 400);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (!inCall) FakeCall.dismiss(this);
        super.onDestroy();
    }

    private static String initials(String name) {
        StringBuilder sb = new StringBuilder();
        for (String part : name.trim().split("\\s+")) {
            if (part.isEmpty()) continue;
            sb.appendCodePoint(part.codePointAt(0));
            if (sb.length() >= 2) break;
        }
        return sb.toString().toUpperCase(Locale.getDefault());
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
