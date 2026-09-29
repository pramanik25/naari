package com.example.naarishakti.security;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Build;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.core.widget.TextViewCompat;

import com.example.naarishakti.R;

/**
 * Premium numeric PIN pad: dot indicator, message line and a 3×4 keypad
 * (1–9, delete, 0, done). Accepts 4–6 digits; submits on "done" or automatically at 6 digits.
 * Used by {@link PinActivity} and by the SOS screen.
 */
public class PinPadView extends LinearLayout {

    public interface Listener {
        void onPinEntered(String pin);
    }

    private final StringBuilder digits = new StringBuilder();
    private LinearLayout dotsRow;
    private TextView message;
    private View confirmKey;
    private Listener listener;
    private boolean padEnabled = true;
    private boolean errorShown;

    public PinPadView(Context context) {
        this(context, null);
    }

    public PinPadView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        build();
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    /** Clears the entered digits (keeps the message). */
    public void clear() {
        digits.setLength(0);
        renderDots();
    }

    public void setMessage(@Nullable CharSequence text, boolean error) {
        message.setText(text);
        message.setTextColor(ContextCompat.getColor(getContext(), error ? R.color.ns_danger : R.color.ns_text_muted));
    }

    public void setPadEnabled(boolean enabled) {
        padEnabled = enabled;
        setAlpha(enabled ? 1f : 0.45f);
        if (!enabled) clear();
    }

    /** Wrong PIN feedback: shake, red dots, reject haptic; then clears. */
    public void shake() {
        errorShown = true;
        renderDots();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            performHapticFeedback(HapticFeedbackConstants.REJECT);
        } else {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        }
        ObjectAnimator a = ObjectAnimator.ofFloat(dotsRow, View.TRANSLATION_X,
                0, dp(22), -dp(22), dp(16), -dp(16), dp(8), -dp(8), 0);
        a.setDuration(440);
        a.start();
        postDelayed(() -> {
            errorShown = false;
            clear();
        }, 520);
    }

    // ---- Building ----

    private void build() {
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setClipChildren(false);
        setClipToPadding(false);

        dotsRow = new LinearLayout(getContext());
        dotsRow.setOrientation(HORIZONTAL);
        dotsRow.setGravity(Gravity.CENTER);
        ViewCompat.setAccessibilityLiveRegion(dotsRow, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE);
        addView(dotsRow, new LayoutParams(LayoutParams.MATCH_PARENT, dp(28)));

        message = new TextView(getContext());
        TextViewCompat.setTextAppearance(message, R.style.Ns_Text_Caption);
        message.setGravity(Gravity.CENTER);
        message.setMinHeight(dp(20));
        LayoutParams mp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        mp.topMargin = dp(10);
        mp.bottomMargin = dp(18);
        addView(message, mp);

        String[][] rows = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {"<", "0", "ok"}};
        for (int r = 0; r < rows.length; r++) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            LayoutParams rp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            if (r > 0) rp.topMargin = dp(14);
            addView(row, rp);
            for (int c = 0; c < 3; c++) {
                View key = makeKey(rows[r][c]);
                LayoutParams kp = new LayoutParams(dp(72), dp(72));
                if (c > 0) kp.leftMargin = dp(26);
                row.addView(key, kp);
            }
        }
        renderDots();
    }

    private View makeKey(final String label) {
        if ("<".equals(label)) {
            ImageView v = iconKey(R.drawable.en_ic_backspace, R.color.ns_text_muted, 0);
            v.setContentDescription(getContext().getString(R.string.en_pin_key_delete));
            v.setOnClickListener(x -> {
                tap(x);
                if (digits.length() > 0) digits.setLength(digits.length() - 1);
                renderDots();
            });
            v.setOnLongClickListener(x -> {
                tap(x);
                clear();
                return true;
            });
            return v;
        }
        if ("ok".equals(label)) {
            ImageView v = iconKey(R.drawable.eng_ic_check, R.color.ns_on_rose, R.drawable.en_pin_key_confirm);
            v.setContentDescription(getContext().getString(R.string.en_pin_key_done));
            v.setOnClickListener(x -> {
                if (!padEnabled || digits.length() < PinStore.MIN_LENGTH) return;
                tap(x);
                submit();
            });
            confirmKey = v;
            return v;
        }
        TextView t = new TextView(getContext());
        TextViewCompat.setTextAppearance(t, R.style.Ns_Text_Title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        t.setTextColor(ContextCompat.getColor(getContext(), R.color.ns_text));
        t.setGravity(Gravity.CENTER);
        t.setText(label);
        t.setBackgroundResource(R.drawable.en_pin_key);
        t.setClickable(true);
        t.setFocusable(true);
        t.setOnClickListener(x -> {
            if (!padEnabled || digits.length() >= PinStore.MAX_LENGTH) return;
            tap(x);
            digits.append(label);
            renderDots();
            popLastDot();
            if (digits.length() == PinStore.MAX_LENGTH) postDelayed(this::submit, 120);
        });
        return t;
    }

    private ImageView iconKey(int icon, int tint, int background) {
        ImageView v = new ImageView(getContext());
        v.setImageResource(icon);
        ImageViewCompat.setImageTintList(v, ColorStateList.valueOf(ContextCompat.getColor(getContext(), tint)));
        v.setScaleType(ImageView.ScaleType.CENTER);
        v.setBackgroundResource(background == 0 ? R.drawable.en_pin_key_flat : background);
        v.setClickable(true);
        v.setFocusable(true);
        return v;
    }

    private void tap(View v) {
        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
    }

    private void submit() {
        if (!padEnabled || digits.length() < PinStore.MIN_LENGTH) return;
        String pin = digits.toString();
        if (listener != null) listener.onPinEntered(pin);
    }

    private void renderDots() {
        int count = digits.length();
        int slots = Math.max(PinStore.MIN_LENGTH, count);
        dotsRow.removeAllViews();
        for (int i = 0; i < slots; i++) {
            View dot = new View(getContext());
            int bg;
            if (errorShown) bg = R.drawable.en_pin_dot_error;
            else bg = i < count ? R.drawable.en_pin_dot_filled : R.drawable.en_pin_dot_empty;
            dot.setBackgroundResource(bg);
            LayoutParams lp = new LayoutParams(dp(14), dp(14));
            lp.leftMargin = dp(9);
            lp.rightMargin = dp(9);
            dotsRow.addView(dot, lp);
        }
        dotsRow.setContentDescription(getResources().getQuantityString(R.plurals.en_pin_digits_entered, count, count));
        if (confirmKey != null) {
            boolean ready = count >= PinStore.MIN_LENGTH;
            confirmKey.setAlpha(ready ? 1f : 0.35f);
            confirmKey.setEnabled(ready);
        }
    }

    private void popLastDot() {
        int i = digits.length() - 1;
        if (i < 0 || i >= dotsRow.getChildCount()) return;
        View dot = dotsRow.getChildAt(i);
        dot.setScaleX(0.4f);
        dot.setScaleY(0.4f);
        dot.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
