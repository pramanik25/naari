package com.example.naarishakti.journey;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.example.naarishakti.MainActivity;
import com.example.naarishakti.R;
import com.google.android.material.card.MaterialCardView;

/**
 * Home screen entry points owned by the journey module: the "Safety tools" gradient card and the
 * live check-in / cab-trip banner. HomeFragment only creates this and forwards resume/pause.
 */
public final class HomeJourneyEntry {

    private final Context ctx;
    private final Handler handler = new Handler(Looper.getMainLooper());
    @Nullable private final MaterialCardView banner;
    @Nullable private final ImageView bannerIcon;
    @Nullable private final TextView bannerTitle;
    @Nullable private final TextView bannerSubtitle;
    private boolean resumed;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            render();
            if (resumed) handler.postDelayed(this, 1000L);
        }
    };

    public HomeJourneyEntry(@NonNull View root) {
        ctx = root.getContext();
        View tools = root.findViewById(R.id.jrSafetyToolsCard);
        if (tools != null) {
            tools.setOnClickListener(v -> open(new Intent(ctx, SafetyToolsActivity.class)));
        }
        banner = root.findViewById(R.id.jrJourneyBanner);
        bannerIcon = root.findViewById(R.id.jrJourneyIcon);
        bannerTitle = root.findViewById(R.id.jrJourneyTitle);
        bannerSubtitle = root.findViewById(R.id.jrJourneySubtitle);
        if (banner != null) {
            banner.setOnClickListener(v -> open(new Intent(ctx,
                    CabTrip.isActive(ctx) && !CheckIn.isActive(ctx)
                            ? CabModeActivity.class : CheckInActivity.class)));
        }
    }

    /** Call from the fragment's onResume / when it becomes visible. Also re-arms a check-in lazily. */
    public void resume() {
        resumed = true;
        CheckIn.rearm(ctx);
        if (CabTrip.isActive(ctx)) JourneyService.sync(ctx, true);
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    /** Call from onStop / when hidden / onDestroyView. */
    public void pause() {
        resumed = false;
        handler.removeCallbacks(ticker);
    }

    private void render() {
        if (banner == null || bannerTitle == null || bannerSubtitle == null || bannerIcon == null) return;
        boolean checkIn = CheckIn.isActive(ctx);
        boolean cab = CabTrip.isActive(ctx);
        if (!checkIn && !cab) {
            banner.setVisibility(View.GONE);
            return;
        }
        banner.setVisibility(View.VISIBLE);
        int tone;
        int container;
        if (checkIn) {
            long left = CheckIn.deadline(ctx) - System.currentTimeMillis();
            boolean overdue = CheckIn.isOverdue(ctx) || left <= 0;
            bannerIcon.setImageResource(R.drawable.ua_ic_timer);
            bannerTitle.setText(overdue ? R.string.jr_home_checkin_overdue : R.string.jr_home_checkin_title);
            bannerSubtitle.setText(overdue
                    ? ctx.getString(R.string.jr_home_checkin_overdue_sub)
                    : ctx.getString(R.string.jr_home_checkin_sub, JourneyUtil.countdown(left),
                    JourneyUtil.clock(ctx, CheckIn.deadline(ctx))));
            tone = overdue ? R.color.ns_danger : left <= CheckIn.WARN_BEFORE_MS ? R.color.ns_warn : R.color.ns_safe;
            container = overdue ? R.color.ns_danger_container
                    : left <= CheckIn.WARN_BEFORE_MS ? R.color.ns_warn_container : R.color.ns_safe_container;
        } else {
            bannerIcon.setImageResource(R.drawable.jr_ic_cab);
            bannerTitle.setText(ctx.getString(R.string.jr_home_cab_title, CabTrip.plate(ctx)));
            float d = CabTrip.distLeft(ctx);
            bannerSubtitle.setText(d >= 0
                    ? ctx.getString(R.string.jr_home_cab_sub, JourneyUtil.distance(ctx, d))
                    : ctx.getString(R.string.jr_home_cab_sub_nodist));
            tone = CabTrip.promptAt(ctx) > 0 ? R.color.ns_danger : R.color.ns_safe;
            container = CabTrip.promptAt(ctx) > 0 ? R.color.ns_danger_container : R.color.ns_safe_container;
        }
        MainActivity.tintBadge(bannerIcon, tone, container);
        banner.setStrokeColor(ColorStateList.valueOf(ContextCompat.getColor(ctx, tone)));
    }

    private void open(Intent intent) {
        if (JourneyUtil.startSafely(ctx, intent) && ctx instanceof Activity) {
            ((Activity) ctx).overridePendingTransition(R.anim.slide_in, R.anim.slide_out);
        }
    }
}
