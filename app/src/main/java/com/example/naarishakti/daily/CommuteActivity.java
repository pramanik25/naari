package com.example.naarishakti.daily;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.example.naarishakti.MainActivity;
import com.example.naarishakti.R;
import com.example.naarishakti.databinding.DlActivityCommuteBinding;
import com.example.naarishakti.databinding.DlDialogRouteBinding;
import com.example.naarishakti.databinding.DlItemRouteBinding;
import com.example.naarishakti.journey.CheckIn;
import com.example.naarishakti.journey.CheckInActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.util.List;

/**
 * Daily commute: saved routes with a one-tap "I'm leaving". A trip is a check-in timer with the
 * route's usual duration, so everything that protects a check-in (SOS at the deadline, server
 * escalation, PIN-protected "I've reached") applies without any setup.
 */
public class CommuteActivity extends AppCompatActivity {

    /** Local broadcast sent by the journey module when a check-in starts, changes or ends. */
    private static final String ACTION_JOURNEY_CHANGED = "com.example.naarishakti.journey.ACTION_CHANGED";

    private DlActivityCommuteBinding b;
    private List<Commute.Route> routes;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            renderActive();
            handler.postDelayed(this, 1000L);
        }
    };

    private final BroadcastReceiver changeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            renderActive();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = DlActivityCommuteBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        b.backButton.setOnClickListener(v -> finish());
        b.addButton.setOnClickListener(v -> editRoute(null));
        b.reachedButton.setOnClickListener(v -> Daily.open(this,
                new Intent(this, CheckInActivity.class).setAction(CheckInActivity.ACTION_CHECKOUT)));
        b.activeCard.setOnClickListener(v -> Daily.open(this, new Intent(this, CheckInActivity.class)));
        MainActivity.tintBadge(b.activeIcon, R.color.ns_safe, R.color.ns_safe_container);
        routes = Commute.routes(this);
        renderRoutes();
    }

    @Override
    protected void onStart() {
        super.onStart();
        LocalBroadcastManager.getInstance(this)
                .registerReceiver(changeReceiver, new IntentFilter(ACTION_JOURNEY_CHANGED));
    }

    @Override
    protected void onResume() {
        super.onResume();
        CheckIn.rearm(this);
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
    }

    @Override
    protected void onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(changeReceiver);
        super.onStop();
    }

    // ------------------------------------------------------------------ render

    private void renderActive() {
        boolean active = CheckIn.isActive(this);
        Daily.show(b.activeCard, active);
        if (active) {
            long left = CheckIn.deadline(this) - System.currentTimeMillis();
            boolean overdue = CheckIn.isOverdue(this) || left <= 0;
            b.activeTitle.setText(overdue ? R.string.dl_cm_active_overdue : R.string.dl_cm_active_title);
            b.activeSubtitle.setText(overdue
                    ? getString(R.string.dl_cm_active_overdue_sub)
                    : getString(R.string.dl_cm_active_sub, Daily.countdown(left)));
            MainActivity.tintBadge(b.activeIcon, overdue ? R.color.ns_danger : R.color.ns_safe,
                    overdue ? R.color.ns_danger_container : R.color.ns_safe_container);
        }
    }

    private void renderRoutes() {
        b.routes.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (final Commute.Route r : routes) {
            DlItemRouteBinding row = DlItemRouteBinding.inflate(inflater, b.routes, false);
            MainActivity.tintBadge(row.icon, R.color.ns_rose, R.color.ns_rose_container);
            row.name.setText(r.name);
            row.duration.setText(getString(R.string.dl_cm_minutes, r.minutes));
            row.getRoot().setContentDescription(r.name + ". " + getString(R.string.dl_cm_minutes, r.minutes));
            row.getRoot().setOnClickListener(v -> editRoute(r));
            row.startButton.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                start(r);
            });
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) row.getRoot().getLayoutParams();
            if (b.routes.getChildCount() > 0) lp.topMargin = Daily.dp(this, 12);
            b.routes.addView(row.getRoot(), lp);
        }
        Daily.show(b.emptyText, routes.isEmpty());
        Daily.show(b.addButton, routes.size() < Commute.MAX_ROUTES);
    }

    // ------------------------------------------------------------------ actions

    private void start(Commute.Route r) {
        if (CheckIn.isActive(this)) {
            // One trip at a time: show the running one instead of silently replacing it.
            Snackbar.make(b.getRoot(), R.string.dl_cm_already_running, Snackbar.LENGTH_LONG).show();
            return;
        }
        Daily.open(this, new Intent(this, CheckInActivity.class)
                .putExtra(CheckInActivity.EXTRA_DEADLINE, System.currentTimeMillis() + r.minutes * 60_000L)
                .putExtra(CheckInActivity.EXTRA_NOTE, r.name)
                .putExtra(CheckInActivity.EXTRA_AUTOSTART, true));
    }

    private void editRoute(@Nullable final Commute.Route route) {
        final DlDialogRouteBinding d = DlDialogRouteBinding.inflate(getLayoutInflater());
        if (route != null) d.nameInput.setText(route.name);
        d.minutesSlider.setValueFrom(Commute.MIN_MINUTES);
        d.minutesSlider.setValueTo(Commute.MAX_MINUTES);
        d.minutesSlider.setStepSize(5f);
        d.minutesSlider.setValue(route != null ? clampMinutes(route.minutes) : 30);
        d.minutesLabel.setText(getString(R.string.dl_cm_minutes, (int) d.minutesSlider.getValue()));
        d.minutesSlider.addOnChangeListener((slider, value, fromUser) ->
                d.minutesLabel.setText(getString(R.string.dl_cm_minutes, (int) value)));

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle(route == null ? R.string.dl_cm_add_title : R.string.dl_cm_edit_title)
                .setView(d.getRoot())
                .setPositiveButton(R.string.dl_save, null)
                .setNegativeButton(R.string.dl_cancel, null);
        if (route != null) {
            builder.setNeutralButton(R.string.dl_delete, (dialog, which) -> {
                routes.remove(route);
                Commute.save(this, routes);
                renderRoutes();
            });
        }
        final AlertDialog dialog = builder.create();
        // Set after show() so an empty name keeps the dialog open.
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = d.nameInput.getText() == null ? "" : d.nameInput.getText().toString().trim();
            if (TextUtils.isEmpty(name)) {
                d.nameLayout.setError(getString(R.string.dl_cm_name_required));
                return;
            }
            int minutes = (int) d.minutesSlider.getValue();
            if (route == null) {
                routes.add(new Commute.Route(name, minutes));
            } else {
                route.name = name;
                route.minutes = minutes;
            }
            Commute.save(this, routes);
            renderRoutes();
            dialog.dismiss();
        }));
        dialog.show();
    }

    /** Keeps a stored duration on the slider's 5-minute grid. */
    private static int clampMinutes(int minutes) {
        int snapped = Math.round(minutes / 5f) * 5;
        return Math.max(Commute.MIN_MINUTES, Math.min(Commute.MAX_MINUTES, snapped));
    }
}
