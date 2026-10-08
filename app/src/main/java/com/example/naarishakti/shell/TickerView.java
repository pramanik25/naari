package com.example.naarishakti.shell;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * One line of text that travels across the view from right to left in an endless loop (left to
 * right for right-to-left scripts, so the sentence still enters start first), fading out at both
 * edges. It only animates while it is on screen, and stands still (start-aligned, ellipsized)
 * when the phone has animations switched off.
 */
public class TickerView extends View {

    private static final float SPEED_DP_PER_SECOND = 55f;
    private static final float TEXT_SP = 13f;
    /** Space between one copy of the text and the next. */
    private static final float GAP_DP = 56f;
    private static final float FADE_DP = 20f;

    private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final float speedPxPerMs;
    private final float gapPx;
    private String text = "";
    private float textWidth;
    /** How far the loop has scrolled, in px; always less than one text width plus one gap. */
    private float travelled;
    private long lastFrameMs;

    public TickerView(Context context) {
        this(context, null);
    }

    public TickerView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        speedPxPerMs = SPEED_DP_PER_SECOND * density / 1000f;
        gapPx = GAP_DP * density;
        setHorizontalFadingEdgeEnabled(true);
        setFadingEdgeLength(Math.round(FADE_DP * density));
        paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TEXT_SP,
                getResources().getDisplayMetrics()));
        paint.setFakeBoldText(true);
    }

    public void setText(@Nullable CharSequence value) {
        text = value == null ? "" : value.toString();
        textWidth = paint.measureText(text);
        travelled = 0f;
        setContentDescription(text);
        invalidate();
    }

    public void setTextColor(@ColorInt int color) {
        paint.setColor(color);
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        Paint.FontMetrics fm = paint.getFontMetrics();
        int wanted = (int) Math.ceil(fm.descent - fm.ascent) + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(getDefaultSize(getSuggestedMinimumWidth(), widthMeasureSpec),
                resolveSize(wanted, heightMeasureSpec));
    }

    // A plain View never scrolls, so it reports no fade; the text runs off both edges here.
    @Override
    protected float getLeftFadingEdgeStrength() {
        return animationsEnabled() ? 1f : 0f;
    }

    @Override
    protected float getRightFadingEdgeStrength() {
        return animationsEnabled() ? 1f : 0f;
    }

    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        lastFrameMs = 0L; // don't jump ahead by the time spent hidden
        if (visibility == VISIBLE) invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (text.isEmpty()) return;
        Paint.FontMetrics fm = paint.getFontMetrics();
        float baseline = (getHeight() - fm.descent - fm.ascent) / 2f;

        if (!animationsEnabled()) {
            float room = getWidth() - getPaddingStart() - getPaddingEnd();
            CharSequence shown = TextUtils.ellipsize(text, paint, room, TextUtils.TruncateAt.END);
            canvas.drawText(shown, 0, shown.length(), getPaddingStart(), baseline, paint);
            return;
        }

        long now = SystemClock.uptimeMillis();
        if (lastFrameMs != 0L) travelled += (now - lastFrameMs) * speedPxPerMs;
        lastFrameMs = now;
        float period = textWidth + gapPx;
        travelled %= period;
        // Copies sit one period apart; shifting them all by the distance travelled makes the loop.
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        float first = rtl ? travelled - period : -travelled;
        for (float x = first; x < getWidth(); x += period) {
            canvas.drawText(text, x, baseline, paint);
        }
        if (isShown()) postInvalidateOnAnimation();
    }

    private static boolean animationsEnabled() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled();
    }
}
