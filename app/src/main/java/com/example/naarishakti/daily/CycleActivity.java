package com.example.naarishakti.daily;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.widget.TextViewCompat;

import com.example.naarishakti.R;
import com.example.naarishakti.core.ProtectionController;
import com.example.naarishakti.databinding.DlActivityCycleBinding;
import com.example.naarishakti.databinding.DlSheetDayBinding;
import com.example.naarishakti.security.PinActivity;
import com.example.naarishakti.security.PinStore;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.snackbar.Snackbar;

import java.text.DateFormatSymbols;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Private cycle and mood tracker: a month calendar with logged, expected and fertile days, a
 * per-day log (period, flow, mood, symptoms) and a summary of where she is in her cycle.
 *
 * Like the diary it is encrypted on the phone, gated by the app PIN when one is set, hidden from
 * screenshots/recents and re-locks after a while in the background. A duress PIN opens an empty,
 * memory-only log and fires a silent SOS.
 */
public class CycleActivity extends AppCompatActivity {

    private static final String TAG = "CycleActivity";
    private static final long RELOCK_AFTER_MS = 2 * 60 * 1000L;
    private static final String STATE_UNLOCKED = "unlocked";
    private static final String STATE_DECOY = "decoy";
    private static final String STATE_YEAR = "year";
    private static final String STATE_MONTH = "month";
    private static final int DAYS_IN_WEEK = 7;

    /** Symptom keys as stored, with their labels. Keys must never change. */
    private static final String[] SYMPTOMS = {"cramps", "headache", "backache", "bloating",
            "fatigue", "acne", "tender", "nausea"};
    @StringRes
    private static final int[] SYMPTOM_LABELS = {R.string.dl_cy_sym_cramps, R.string.dl_cy_sym_headache,
            R.string.dl_cy_sym_backache, R.string.dl_cy_sym_bloating, R.string.dl_cy_sym_fatigue,
            R.string.dl_cy_sym_acne, R.string.dl_cy_sym_tender, R.string.dl_cy_sym_nausea};
    /** Labels for mood 1 (low) to 5 (great). */
    @StringRes
    private static final int[] MOOD_LABELS = {R.string.dl_cy_mood_1, R.string.dl_cy_mood_2,
            R.string.dl_cy_mood_3, R.string.dl_cy_mood_4, R.string.dl_cy_mood_5};

    /**
     * Process-scoped: true while the tracker is unlocked. Saved instance state alone is not
     * trusted, so a process restored after being killed asks for the PIN again.
     */
    private static volatile boolean sessionUnlocked;

    private DlActivityCycleBinding b;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private ActivityResultLauncher<android.content.Intent> pinLauncher;
    private CycleStore store;
    private CycleStore.Data data = new CycleStore.Data();
    private Cycle cycle = new Cycle(data.periodDays());
    private boolean unlocked;
    private boolean decoy;
    private boolean pinPending;
    private long backgroundAt;
    /** Month on screen; {@code month} is zero-based. */
    private int year;
    private int month;
    @Nullable private BottomSheetDialog sheet;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        b = DlActivityCycleBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        pinLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), this::onPinResult);

        Calendar now = Calendar.getInstance();
        year = savedInstanceState != null ? savedInstanceState.getInt(STATE_YEAR) : now.get(Calendar.YEAR);
        month = savedInstanceState != null ? savedInstanceState.getInt(STATE_MONTH) : now.get(Calendar.MONTH);

        b.backButton.setOnClickListener(v -> finish());
        b.unlockButton.setOnClickListener(v -> requestPin());
        b.prevButton.setOnClickListener(v -> shiftMonth(-1));
        b.nextButton.setOnClickListener(v -> shiftMonth(1));
        b.logTodayButton.setOnClickListener(v -> openDay(Daily.today()));
        b.reminderSwitch.setChecked(DailyNudge.cycleReminderOn(this));
        b.reminderSwitch.setOnCheckedChangeListener((btn, checked) -> {
            Daily.kv(this).edit().putBoolean(DailyNudge.K_CYCLE_REMINDER, checked).apply();
            DailyNudge.sync(this);
        });
        b.reminderRow.setOnClickListener(v -> b.reminderSwitch.toggle());
        buildWeekdayHeader();

        if (savedInstanceState != null && savedInstanceState.getBoolean(STATE_UNLOCKED) && sessionUnlocked) {
            // Rotation etc.: stay unlocked; a decoy stays a (now empty) decoy.
            unlock(savedInstanceState.getBoolean(STATE_DECOY));
        } else if (pinIsSet()) {
            lock();
            requestPin();
        } else {
            unlock(false);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (unlocked && !decoy && backgroundAt > 0
                && SystemClock.elapsedRealtime() - backgroundAt > RELOCK_AFTER_MS && pinIsSet()) {
            lock();
            requestPin();
        }
        backgroundAt = 0;
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!isChangingConfigurations()) backgroundAt = SystemClock.elapsedRealtime();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_UNLOCKED, unlocked);
        outState.putBoolean(STATE_DECOY, decoy);
        outState.putInt(STATE_YEAR, year);
        outState.putInt(STATE_MONTH, month);
    }

    @Override
    protected void onDestroy() {
        dismissSheet();
        if (isFinishing()) sessionUnlocked = false;
        main.removeCallbacksAndMessages(null);
        io.shutdown();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ lock

    private boolean pinIsSet() {
        try {
            return PinStore.isSet(this);
        } catch (Throwable t) {
            Log.e(TAG, "PinStore unavailable", t);
            return false;
        }
    }

    private void requestPin() {
        if (pinPending) return;
        try {
            pinLauncher.launch(PinActivity.verifyIntent(this, getString(R.string.dl_cy_pin_title)));
            pinPending = true;
        } catch (Throwable t) {
            // No PIN screen available: never show a locked log's contents without it.
            Log.e(TAG, "Can't open the PIN screen", t);
            finish();
        }
    }

    private void onPinResult(ActivityResult result) {
        pinPending = false;
        String outcome = result.getResultCode() == Activity.RESULT_OK && result.getData() != null
                ? result.getData().getStringExtra(PinActivity.EXTRA_RESULT) : null;
        if (PinActivity.RESULT_VALUE_OK.equals(outcome)) {
            unlock(false);
        } else if (PinActivity.RESULT_VALUE_DURESS.equals(outcome)) {
            // She is being forced to open it: show an empty log and raise a covert SOS.
            unlock(true);
            ProtectionController.triggerPanic(this, "duress", true);
        } else {
            finish();
        }
    }

    private void lock() {
        unlocked = false;
        sessionUnlocked = false;
        dismissSheet();
        data = new CycleStore.Data();
        cycle = new Cycle(data.periodDays());
        Daily.show(b.content, false);
        Daily.show(b.lockedState, true);
    }

    private void unlock(boolean asDecoy) {
        unlocked = true;
        sessionUnlocked = true;
        decoy = asDecoy;
        store = asDecoy ? CycleStore.decoy(this) : CycleStore.open(this);
        Daily.show(b.lockedState, false);
        Daily.show(b.content, true);
        load();
    }

    // ------------------------------------------------------------------ data

    private void load() {
        final CycleStore s = store;
        io.execute(() -> {
            CycleStore.Data loaded;
            boolean failed = false;
            try {
                loaded = s.load();
            } catch (Exception e) {
                Log.e(TAG, "Loading the cycle log failed", e);
                loaded = new CycleStore.Data();
                failed = true;
            }
            final CycleStore.Data result = loaded;
            final boolean error = failed;
            main.post(() -> {
                if (isFinishing() || isDestroyed() || s != store || !unlocked) return;
                data = result;
                // A log that could not be read must not be overwritten by the next save.
                b.logTodayButton.setEnabled(!error);
                b.calendar.setEnabled(!error);
                if (error) snack(R.string.dl_cy_load_error);
                render();
            });
        });
    }

    private void persist() {
        final CycleStore s = store;
        // Gson walks the map on the io thread, so hand it a copy the UI can't change underneath.
        final CycleStore.Data snapshot = new CycleStore.Data();
        snapshot.days.putAll(data.days);
        io.execute(() -> {
            try {
                s.save(snapshot);
            } catch (Exception e) {
                Log.e(TAG, "Saving the cycle log failed", e);
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    snack(R.string.dl_cy_save_error);
                    load();
                });
            }
        });
    }

    // ------------------------------------------------------------------ render

    private void render() {
        cycle = new Cycle(data.periodDays());
        renderSummary();
        renderCalendar();
    }

    private void renderSummary() {
        int today = Daily.today();
        if (!cycle.hasData()) {
            b.summaryTitle.setText(R.string.dl_cy_summary_empty);
            b.summarySub.setText(R.string.dl_cy_summary_empty_sub);
            Daily.show(b.stats, false);
            return;
        }
        CycleStore.Day todayLog = data.days.get(today);
        int cycleDay = cycle.cycleDay(today);
        b.summaryTitle.setText(todayLog != null && todayLog.period
                ? getString(R.string.dl_cy_summary_period, cycleDay)
                : getString(R.string.dl_cy_summary_day, cycleDay));

        int daysLeft = cycle.nextStart() - today;
        String next;
        if (daysLeft > 0) {
            next = getResources().getQuantityString(R.plurals.dl_cy_next_in, daysLeft, daysLeft,
                    Daily.shortDate(cycle.nextStart()));
        } else if (daysLeft == 0) {
            next = getString(R.string.dl_cy_next_today);
        } else {
            next = getResources().getQuantityString(R.plurals.dl_cy_next_late, -daysLeft, -daysLeft);
        }
        b.summarySub.setText(cycle.personal ? next : next + "\n" + getString(R.string.dl_cy_default_cycle_note));

        Daily.show(b.stats, true);
        b.statCycle.setText(getResources().getQuantityString(R.plurals.dl_cy_days, cycle.cycleLength, cycle.cycleLength));
        b.statPeriod.setText(getResources().getQuantityString(R.plurals.dl_cy_days, cycle.periodLength, cycle.periodLength));
    }

    private void buildWeekdayHeader() {
        String[] names = new DateFormatSymbols().getShortWeekdays(); // index = Calendar.SUNDAY..SATURDAY
        int first = Calendar.getInstance().getFirstDayOfWeek();
        for (int i = 0; i < DAYS_IN_WEEK; i++) {
            TextView tv = new TextView(this);
            TextViewCompat.setTextAppearance(tv, R.style.Ns_Text_Caption);
            tv.setGravity(Gravity.CENTER);
            tv.setText(names[(first - 1 + i) % DAYS_IN_WEEK + 1]);
            tv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            b.weekdays.addView(tv, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
    }

    private void renderCalendar() {
        int firstOfMonth = Daily.epochDay(year, month, 1);
        Calendar c = Daily.calendar(firstOfMonth);
        int daysInMonth = c.getActualMaximum(Calendar.DAY_OF_MONTH);
        int weekStart = Calendar.getInstance().getFirstDayOfWeek();
        int lead = Math.floorMod(c.get(Calendar.DAY_OF_WEEK) - weekStart, DAYS_IN_WEEK);
        int today = Daily.today();
        b.monthTitle.setText(Daily.monthTitle(firstOfMonth));

        b.calendar.removeAllViews();
        int cell = Daily.dp(this, 40);
        LinearLayout row = null;
        for (int slot = 0; slot < lead + daysInMonth || slot % DAYS_IN_WEEK != 0; slot++) {
            if (slot % DAYS_IN_WEEK == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rowLp.topMargin = Daily.dp(this, 4);
                b.calendar.addView(row, rowLp);
            }
            LinearLayout holder = new LinearLayout(this);
            holder.setGravity(Gravity.CENTER);
            row.addView(holder, new LinearLayout.LayoutParams(0, cell, 1f));
            int dayOfMonth = slot - lead + 1;
            if (dayOfMonth < 1 || dayOfMonth > daysInMonth) continue;
            holder.addView(dayCell(firstOfMonth + dayOfMonth - 1, dayOfMonth, today),
                    new LinearLayout.LayoutParams(cell, cell));
        }
    }

    private TextView dayCell(final int day, int dayOfMonth, int today) {
        TextView tv = new TextView(this);
        TextViewCompat.setTextAppearance(tv, R.style.Ns_Text_Label);
        tv.setGravity(Gravity.CENTER);
        tv.setText(String.valueOf(dayOfMonth));

        CycleStore.Day log = data.days.get(day);
        boolean period = log != null && log.period;
        boolean predicted = !period && cycle.isPredictedPeriod(day);
        boolean fertile = !period && !predicted && cycle.isFertile(day);
        boolean other = log != null && !log.period && !log.isEmpty();

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        @ColorRes int fill = period ? R.color.ns_rose : predicted ? R.color.ns_rose_container
                : fertile ? R.color.ns_violet_container : android.R.color.transparent;
        bg.setColor(color(fill));
        if (day == today) bg.setStroke(Daily.dp(this, 2), color(R.color.ns_text));
        else if (other) bg.setStroke(Daily.dp(this, 1), color(R.color.ns_gold));
        tv.setBackground(bg);
        tv.setTextColor(color(period ? R.color.ns_on_rose : day > today ? R.color.ns_text_faint : R.color.ns_text));
        if (day == today) tv.setTypeface(tv.getTypeface(), Typeface.BOLD);

        @StringRes int state = period ? R.string.dl_cy_legend_period : predicted ? R.string.dl_cy_legend_predicted
                : fertile ? R.string.dl_cy_legend_fertile : 0;
        tv.setContentDescription(state == 0 ? Daily.longDate(day)
                : Daily.longDate(day) + ", " + getString(state));
        if (day <= today) tv.setOnClickListener(v -> openDay(day)); // the future can't be logged
        return tv;
    }

    private void shiftMonth(int delta) {
        month += delta;
        if (month < 0) {
            month = 11;
            year--;
        } else if (month > 11) {
            month = 0;
            year++;
        }
        renderCalendar();
    }

    // ------------------------------------------------------------------ day sheet

    private void openDay(final int day) {
        if (!unlocked || !b.logTodayButton.isEnabled() || (sheet != null && sheet.isShowing())) return;
        final BottomSheetDialog dialog = new BottomSheetDialog(this);
        final DlSheetDayBinding s = DlSheetDayBinding.inflate(LayoutInflater.from(this));
        dialog.setContentView(s.getRoot());
        Window w = dialog.getWindow();
        if (w != null) w.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);

        final CycleStore.Day existing = data.days.get(day);
        s.dateTitle.setText(Daily.longDate(day));
        s.periodSwitch.setChecked(existing != null && existing.period);
        s.periodRow.setOnClickListener(v -> s.periodSwitch.toggle());
        Daily.show(s.flowGroup, s.periodSwitch.isChecked());
        s.periodSwitch.setOnCheckedChangeListener((btn, checked) -> Daily.show(s.flowGroup, checked));
        int flow = existing == null ? CycleStore.FLOW_NONE : existing.flow;
        if (flow == 1) s.flowChips.check(R.id.chipLight);
        else if (flow == 2) s.flowChips.check(R.id.chipMedium);
        else if (flow == 3) s.flowChips.check(R.id.chipHeavy);

        final List<Chip> moodChips = addChips(s.moodChips, MOOD_LABELS);
        if (existing != null && existing.mood >= 1 && existing.mood <= moodChips.size()) {
            moodChips.get(existing.mood - 1).setChecked(true);
        }
        final List<Chip> symptomChips = addChips(s.symptomChips, SYMPTOM_LABELS);
        if (existing != null && existing.symptoms != null) {
            for (int i = 0; i < SYMPTOMS.length; i++) {
                symptomChips.get(i).setChecked(existing.symptoms.contains(SYMPTOMS[i]));
            }
        }

        s.saveButton.setOnClickListener(v -> {
            CycleStore.Day log = new CycleStore.Day();
            log.period = s.periodSwitch.isChecked();
            int id = s.flowChips.getCheckedChipId();
            log.flow = !log.period ? CycleStore.FLOW_NONE
                    : id == R.id.chipLight ? 1 : id == R.id.chipMedium ? 2 : id == R.id.chipHeavy ? 3 : CycleStore.FLOW_NONE;
            for (int i = 0; i < moodChips.size(); i++) if (moodChips.get(i).isChecked()) log.mood = i + 1;
            for (int i = 0; i < SYMPTOMS.length; i++) if (symptomChips.get(i).isChecked()) log.symptoms.add(SYMPTOMS[i]);
            if (log.isEmpty()) data.days.remove(day);
            else data.days.put(day, log);
            dialog.dismiss();
            render();
            persist();
        });
        Daily.show(s.clearButton, existing != null);
        s.clearButton.setOnClickListener(v -> {
            data.days.remove(day);
            dialog.dismiss();
            render();
            persist();
        });
        dialog.setOnShowListener(d -> {
            View container = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (container != null) {
                ViewCompat.setBackgroundTintList(container, ColorStateList.valueOf(color(R.color.ns_surface_high)));
            }
        });
        dialog.setOnDismissListener(d -> {
            if (sheet == dialog) sheet = null;
        });
        sheet = dialog;
        dialog.show();
    }

    private List<Chip> addChips(ChipGroup group, @StringRes int[] labels) {
        List<Chip> chips = new ArrayList<>();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int label : labels) {
            Chip chip = (Chip) inflater.inflate(R.layout.dl_item_chip, group, false);
            chip.setId(View.generateViewId());
            chip.setText(label);
            group.addView(chip);
            chips.add(chip);
        }
        return chips;
    }

    private void dismissSheet() {
        if (sheet != null && sheet.isShowing()) sheet.dismiss();
        sheet = null;
    }

    // ------------------------------------------------------------------ helpers

    private int color(@ColorRes int res) {
        return ContextCompat.getColor(this, res);
    }

    private void snack(@StringRes int text) {
        Snackbar.make(b.getRoot(), text, Snackbar.LENGTH_LONG).show();
    }
}
