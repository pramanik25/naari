package Home_Activity;

import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.example.naarishakti.R;
import com.example.naarishakti.core.Prefs;
import com.example.naarishakti.databinding.ActivityTimeSettingsBinding;
import com.google.android.material.chip.Chip;
import com.google.android.material.datepicker.CalendarConstraints;
import com.google.android.material.datepicker.DateValidatorPointForward;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Scheduled protection: a daily / one-off time window during which protection runs automatically.
 * Everything is stored in the Prefs.SCHEDULE_* keys; {@link AlarmManagerHelper#reschedule} turns the
 * stored window into alarms (and cancels them when the schedule is disabled).
 */
public class TimeSettingsActivity extends AppCompatActivity {

    private static final String TAG = "TimeSettingsActivity";
    private static final String TAG_START = "pick_start_time";
    private static final String TAG_END = "pick_end_time";
    private static final String TAG_DATE = "pick_date";

    private static final int DEFAULT_START_HOUR = 22;
    private static final int DEFAULT_END_HOUR = 6;

    private static final String STATE_ENABLED = "enabled";
    private static final String STATE_START_H = "sh";
    private static final String STATE_START_M = "sm";
    private static final String STATE_END_H = "eh";
    private static final String STATE_END_M = "em";
    private static final String STATE_MODE = "mode";
    private static final String STATE_DATE = "date";

    private ActivityTimeSettingsBinding b;
    private boolean enabled;
    private int startH;
    private int startM;
    private int endH;
    private int endM;
    private String mode;
    private long dateMillis;
    private boolean suppressToggle;
    private boolean exactAlarmBlocked;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityTimeSettingsBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        if (savedInstanceState != null) {
            enabled = savedInstanceState.getBoolean(STATE_ENABLED);
            startH = savedInstanceState.getInt(STATE_START_H);
            startM = savedInstanceState.getInt(STATE_START_M);
            endH = savedInstanceState.getInt(STATE_END_H);
            endM = savedInstanceState.getInt(STATE_END_M);
            mode = savedInstanceState.getString(STATE_MODE, Prefs.SCHEDULE_MODE_DAILY);
            dateMillis = savedInstanceState.getLong(STATE_DATE);
        } else {
            loadFromPrefs();
        }

        b.backButton.setOnClickListener(v -> finish());

        b.masterRow.icon.setImageResource(R.drawable.ua_ic_schedule);
        tintBadge(b.masterRow.icon, R.color.ns_gold, R.color.ns_gold_container);
        b.masterRow.title.setText(R.string.schedule_master_title);
        b.masterRow.toggle.setOnCheckedChangeListener((btn, checked) -> {
            if (!suppressToggle) onMasterToggled(checked);
        });
        b.masterRow.getRoot().setOnClickListener(v -> b.masterRow.toggle.toggle());

        b.startTile.setOnClickListener(v -> pickTime(true));
        b.endTile.setOnClickListener(v -> pickTime(false));

        b.chipDaily.setOnClickListener(v -> setMode(Prefs.SCHEDULE_MODE_DAILY));
        b.chipToday.setOnClickListener(v -> setMode(Prefs.SCHEDULE_MODE_TODAY));
        b.chipTomorrow.setOnClickListener(v -> setMode(Prefs.SCHEDULE_MODE_TOMORROW));
        b.chipDate.setOnClickListener(v -> pickDate());

        b.exactAlarmButton.setOnClickListener(v -> openExactAlarmSettings());
        b.saveButton.setOnClickListener(v -> save());

        reattachPickers();
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshExactAlarmCard();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean(STATE_ENABLED, enabled);
        out.putInt(STATE_START_H, startH);
        out.putInt(STATE_START_M, startM);
        out.putInt(STATE_END_H, endH);
        out.putInt(STATE_END_M, endM);
        out.putString(STATE_MODE, mode);
        out.putLong(STATE_DATE, dateMillis);
    }

    private void loadFromPrefs() {
        SharedPreferences p = Prefs.get(this);
        enabled = p.getBoolean(Prefs.SCHEDULE_ENABLED, false);
        startH = p.getInt(Prefs.SCHEDULE_START_HOUR, DEFAULT_START_HOUR);
        startM = p.getInt(Prefs.SCHEDULE_START_MINUTE, 0);
        endH = p.getInt(Prefs.SCHEDULE_END_HOUR, DEFAULT_END_HOUR);
        endM = p.getInt(Prefs.SCHEDULE_END_MINUTE, 0);
        mode = p.getString(Prefs.SCHEDULE_MODE, Prefs.SCHEDULE_MODE_DAILY);
        dateMillis = p.getLong(Prefs.SCHEDULE_DATE_MILLIS, 0L);
        if (mode == null) mode = Prefs.SCHEDULE_MODE_DAILY;
    }

    // ------------------------------------------------------------------ actions

    private void onMasterToggled(boolean checked) {
        b.masterRow.toggle.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        if (checked) {
            Integer error = validate();
            if (error != null) {
                setToggle(false);
                snack(getString(error));
                return;
            }
        }
        enabled = checked;
        persist();
        render();
        refreshExactAlarmCard();
    }

    private void setMode(String newMode) {
        mode = newMode;
        Calendar day = Calendar.getInstance();
        if (Prefs.SCHEDULE_MODE_TOMORROW.equals(newMode)) day.add(Calendar.DAY_OF_YEAR, 1);
        if (!Prefs.SCHEDULE_MODE_DAILY.equals(newMode)) dateMillis = noonOf(day);
        render();
    }

    private void save() {
        if (enabled) {
            Integer error = validate();
            if (error != null) {
                snack(getString(error));
                return;
            }
        }
        persist();
        Toast.makeText(this, R.string.schedule_saved, Toast.LENGTH_SHORT).show();
        finish();
    }

    /** Writes the current window to Prefs and asks the engine to (re)schedule or cancel alarms. */
    private void persist() {
        Prefs.get(this).edit()
                .putBoolean(Prefs.SCHEDULE_ENABLED, enabled)
                .putInt(Prefs.SCHEDULE_START_HOUR, startH)
                .putInt(Prefs.SCHEDULE_START_MINUTE, startM)
                .putInt(Prefs.SCHEDULE_END_HOUR, endH)
                .putInt(Prefs.SCHEDULE_END_MINUTE, endM)
                .putString(Prefs.SCHEDULE_MODE, mode)
                .putLong(Prefs.SCHEDULE_DATE_MILLIS, dateMillis)
                .apply();
        try {
            AlarmManagerHelper.reschedule(this);
        } catch (Exception e) {
            Log.e(TAG, "Unable to reschedule protection alarms", e);
        }
        Prefs.notifyChanged(this);
    }

    /** @return an error string resource, or null when the schedule is valid. */
    @Nullable
    private Integer validate() {
        int start = startH * 60 + startM;
        int end = endH * 60 + endM;
        if (start == end) return R.string.schedule_error_same;
        if (Prefs.SCHEDULE_MODE_DATE.equals(mode)) {
            if (dateMillis <= 0 || dateMillis < startOfToday()) return R.string.schedule_error_date;
        }
        if (Prefs.SCHEDULE_MODE_TODAY.equals(mode) && end > start) {
            Calendar now = Calendar.getInstance();
            int nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
            if (nowMin >= end) return R.string.schedule_error_passed;
        }
        return null;
    }

    // ------------------------------------------------------------------ pickers

    private void pickTime(final boolean start) {
        FragmentManager fm = getSupportFragmentManager();
        String tag = start ? TAG_START : TAG_END;
        if (fm.findFragmentByTag(tag) != null) return;
        MaterialTimePicker picker = new MaterialTimePicker.Builder()
                .setTimeFormat(DateFormat.is24HourFormat(this) ? TimeFormat.CLOCK_24H : TimeFormat.CLOCK_12H)
                .setHour(start ? startH : endH)
                .setMinute(start ? startM : endM)
                .setTitleText(start ? R.string.schedule_pick_start : R.string.schedule_pick_end)
                .setInputMode(MaterialTimePicker.INPUT_MODE_CLOCK)
                .build();
        attachTimeListener(picker, start);
        picker.show(fm, tag);
    }

    private void attachTimeListener(final MaterialTimePicker picker, final boolean start) {
        picker.addOnPositiveButtonClickListener(v -> {
            if (start) {
                startH = picker.getHour();
                startM = picker.getMinute();
            } else {
                endH = picker.getHour();
                endM = picker.getMinute();
            }
            render();
        });
    }

    private void pickDate() {
        FragmentManager fm = getSupportFragmentManager();
        if (fm.findFragmentByTag(TAG_DATE) != null) return;
        long selection = (Prefs.SCHEDULE_MODE_DATE.equals(mode) && dateMillis > 0)
                ? localDayToUtc(dateMillis) : MaterialDatePicker.todayInUtcMilliseconds();
        MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText(R.string.schedule_pick_date_title)
                .setSelection(selection)
                .setCalendarConstraints(new CalendarConstraints.Builder()
                        .setValidator(DateValidatorPointForward.now())
                        .build())
                .build();
        attachDateListener(picker);
        picker.show(fm, TAG_DATE);
    }

    private void attachDateListener(MaterialDatePicker<Long> picker) {
        picker.addOnPositiveButtonClickListener(selection -> {
            if (selection != null) {
                dateMillis = utcDayToLocalNoon(selection);
                mode = Prefs.SCHEDULE_MODE_DATE;
            }
            render();
        });
        // Cancelling restores the previously selected chip.
        picker.addOnNegativeButtonClickListener(v -> render());
        picker.addOnCancelListener(d -> render());
    }

    @SuppressWarnings("unchecked")
    private void reattachPickers() {
        FragmentManager fm = getSupportFragmentManager();
        Fragment f = fm.findFragmentByTag(TAG_START);
        if (f instanceof MaterialTimePicker) attachTimeListener((MaterialTimePicker) f, true);
        f = fm.findFragmentByTag(TAG_END);
        if (f instanceof MaterialTimePicker) attachTimeListener((MaterialTimePicker) f, false);
        f = fm.findFragmentByTag(TAG_DATE);
        if (f instanceof MaterialDatePicker) attachDateListener((MaterialDatePicker<Long>) f);
    }

    // ------------------------------------------------------------------ rendering

    private void render() {
        setToggle(enabled);
        b.masterRow.subtitle.setText(enabled ? R.string.schedule_master_on : R.string.schedule_master_off);

        b.scheduleSection.animate().alpha(enabled ? 1f : 0.45f).setDuration(180).start();
        b.startTile.setEnabled(enabled);
        b.endTile.setEnabled(enabled);
        for (Chip chip : new Chip[]{b.chipDaily, b.chipToday, b.chipTomorrow, b.chipDate}) {
            chip.setEnabled(enabled);
        }

        bindTime(startH, startM, b.startTime, b.startAmPm);
        bindTime(endH, endM, b.endTime, b.endAmPm);

        int chipId;
        if (Prefs.SCHEDULE_MODE_TODAY.equals(mode)) chipId = R.id.chipToday;
        else if (Prefs.SCHEDULE_MODE_TOMORROW.equals(mode)) chipId = R.id.chipTomorrow;
        else if (Prefs.SCHEDULE_MODE_DATE.equals(mode)) chipId = R.id.chipDate;
        else chipId = R.id.chipDaily;
        b.repeatGroup.check(chipId);
        b.chipDate.setText(Prefs.SCHEDULE_MODE_DATE.equals(mode) && dateMillis > 0
                ? formatDay(this, dateMillis) : getString(R.string.schedule_pick_date));

        Integer error = validate();
        boolean showError = enabled && error != null;
        String summary = enabled
                ? describe(this, startH, startM, endH, endM, mode, dateMillis)
                : getString(R.string.schedule_off_summary);
        if (showError) summary = summary + "\n" + getString(error);
        b.summaryText.setText(summary);
        b.summaryIcon.setImageResource(showError ? R.drawable.ua_ic_warning : R.drawable.ua_ic_info);
        if (showError) tintBadge(b.summaryIcon, R.color.ns_warn, R.color.ns_warn_container);
        else tintBadge(b.summaryIcon, R.color.ns_gold, R.color.ns_gold_container);
    }

    private void bindTime(int h, int m, android.widget.TextView time, android.widget.TextView amPm) {
        if (DateFormat.is24HourFormat(this)) {
            time.setText(String.format(java.util.Locale.getDefault(), "%02d:%02d", h, m));
            amPm.setVisibility(View.GONE);
        } else {
            int h12 = h % 12 == 0 ? 12 : h % 12;
            time.setText(String.format(java.util.Locale.getDefault(), "%d:%02d", h12, m));
            Calendar c = Calendar.getInstance();
            c.set(Calendar.HOUR_OF_DAY, h);
            amPm.setText(DateFormat.format("a", c));
            amPm.setVisibility(View.VISIBLE);
        }
    }

    private void setToggle(boolean checked) {
        if (b.masterRow.toggle.isChecked() == checked) return;
        suppressToggle = true;
        b.masterRow.toggle.setChecked(checked);
        suppressToggle = false;
    }

    private void refreshExactAlarmCard() {
        boolean blocked = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AlarmManager am = getSystemService(AlarmManager.class);
            blocked = am != null && !am.canScheduleExactAlarms();
        }
        b.exactAlarmCard.setVisibility(blocked ? View.VISIBLE : View.GONE);
        if (exactAlarmBlocked && !blocked && enabled) {
            // Permission was just granted in system settings: schedule precisely now.
            try {
                AlarmManagerHelper.reschedule(this);
            } catch (Exception e) {
                Log.e(TAG, "Unable to reschedule protection alarms", e);
            }
        }
        exactAlarmBlocked = blocked;
    }

    private void openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            Log.w(TAG, "Exact alarm settings unavailable", e);
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        }
    }

    private void snack(CharSequence text) {
        Snackbar.make(b.getRoot(), text, Snackbar.LENGTH_LONG).show();
    }

    private void tintBadge(android.widget.ImageView badge, @ColorRes int solid, @ColorRes int container) {
        ViewCompat.setBackgroundTintList(badge, ColorStateList.valueOf(ContextCompat.getColor(this, container)));
        ImageViewCompat.setImageTintList(badge, ColorStateList.valueOf(ContextCompat.getColor(this, solid)));
    }

    // ------------------------------------------------------------------ static summaries (used by Home & Settings)

    /** Full sentence, e.g. "Protection turns on at 22:00 and off at 06:00 the next day, every day." */
    @NonNull
    public static String buildSummary(@NonNull Context c) {
        SharedPreferences p = Prefs.get(c);
        if (!p.getBoolean(Prefs.SCHEDULE_ENABLED, false)) return c.getString(R.string.schedule_off_summary);
        return describe(c,
                p.getInt(Prefs.SCHEDULE_START_HOUR, DEFAULT_START_HOUR),
                p.getInt(Prefs.SCHEDULE_START_MINUTE, 0),
                p.getInt(Prefs.SCHEDULE_END_HOUR, DEFAULT_END_HOUR),
                p.getInt(Prefs.SCHEDULE_END_MINUTE, 0),
                p.getString(Prefs.SCHEDULE_MODE, Prefs.SCHEDULE_MODE_DAILY),
                p.getLong(Prefs.SCHEDULE_DATE_MILLIS, 0L));
    }

    /** Compact line for rows, e.g. "22:00 – 06:00 · Every day", or "Not scheduled". */
    @NonNull
    public static String buildShortSummary(@NonNull Context c) {
        SharedPreferences p = Prefs.get(c);
        if (!p.getBoolean(Prefs.SCHEDULE_ENABLED, false)) return c.getString(R.string.schedule_not_scheduled);
        String mode = p.getString(Prefs.SCHEDULE_MODE, Prefs.SCHEDULE_MODE_DAILY);
        long date = p.getLong(Prefs.SCHEDULE_DATE_MILLIS, 0L);
        String when;
        if (Prefs.SCHEDULE_MODE_DAILY.equals(mode) || mode == null) {
            when = c.getString(R.string.schedule_every_day);
        } else if (date > 0) {
            if (date < startOfToday() && endOfWindow(p, date) < System.currentTimeMillis()) {
                return c.getString(R.string.schedule_not_scheduled);
            }
            when = relativeDay(c, date);
        } else {
            when = Prefs.SCHEDULE_MODE_TOMORROW.equals(mode)
                    ? c.getString(R.string.schedule_tomorrow) : c.getString(R.string.schedule_today);
        }
        return c.getString(R.string.schedule_short,
                formatTime(c, p.getInt(Prefs.SCHEDULE_START_HOUR, DEFAULT_START_HOUR), p.getInt(Prefs.SCHEDULE_START_MINUTE, 0)),
                formatTime(c, p.getInt(Prefs.SCHEDULE_END_HOUR, DEFAULT_END_HOUR), p.getInt(Prefs.SCHEDULE_END_MINUTE, 0)),
                when);
    }

    @NonNull
    static String describe(Context c, int sh, int sm, int eh, int em, String mode, long date) {
        boolean crossesMidnight = (eh * 60 + em) <= (sh * 60 + sm);
        String when;
        if (Prefs.SCHEDULE_MODE_TODAY.equals(mode) || Prefs.SCHEDULE_MODE_TOMORROW.equals(mode)
                || Prefs.SCHEDULE_MODE_DATE.equals(mode)) {
            if (date > 0) {
                long today = startOfToday();
                long dayDiff = Math.round((startOfDay(date) - today) / (double) DateUtils.DAY_IN_MILLIS);
                if (dayDiff == 0) when = c.getString(R.string.schedule_when_today);
                else if (dayDiff == 1) when = c.getString(R.string.schedule_when_tomorrow);
                else when = c.getString(R.string.schedule_when_date, formatDay(c, date));
            } else {
                when = c.getString(Prefs.SCHEDULE_MODE_TOMORROW.equals(mode)
                        ? R.string.schedule_when_tomorrow : R.string.schedule_when_today);
            }
        } else {
            when = c.getString(R.string.schedule_when_daily);
        }
        return c.getString(R.string.schedule_summary,
                formatTime(c, sh, sm), formatTime(c, eh, em),
                crossesMidnight ? c.getString(R.string.schedule_next_day) : "", when);
    }

    private static long endOfWindow(SharedPreferences p, long day) {
        int sh = p.getInt(Prefs.SCHEDULE_START_HOUR, DEFAULT_START_HOUR);
        int sm = p.getInt(Prefs.SCHEDULE_START_MINUTE, 0);
        int eh = p.getInt(Prefs.SCHEDULE_END_HOUR, DEFAULT_END_HOUR);
        int em = p.getInt(Prefs.SCHEDULE_END_MINUTE, 0);
        Calendar end = Calendar.getInstance();
        end.setTimeInMillis(startOfDay(day));
        end.set(Calendar.HOUR_OF_DAY, eh);
        end.set(Calendar.MINUTE, em);
        if (eh * 60 + em <= sh * 60 + sm) end.add(Calendar.DAY_OF_YEAR, 1);
        return end.getTimeInMillis();
    }

    private static String relativeDay(Context c, long date) {
        long dayDiff = Math.round((startOfDay(date) - startOfToday()) / (double) DateUtils.DAY_IN_MILLIS);
        if (dayDiff == 0) return c.getString(R.string.schedule_today);
        if (dayDiff == 1) return c.getString(R.string.schedule_tomorrow);
        return formatDay(c, date);
    }

    static String formatTime(Context c, int hour, int minute) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, hour);
        cal.set(Calendar.MINUTE, minute);
        return DateFormat.getTimeFormat(c).format(cal.getTime());
    }

    static String formatDay(Context c, long millis) {
        return DateUtils.formatDateTime(c, millis, DateUtils.FORMAT_SHOW_DATE
                | DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_ABBREV_ALL | DateUtils.FORMAT_NO_YEAR);
    }

    private static long startOfToday() {
        return startOfDay(System.currentTimeMillis());
    }

    private static long startOfDay(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static long noonOf(Calendar day) {
        Calendar c = (Calendar) day.clone();
        c.set(Calendar.HOUR_OF_DAY, 12);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** MaterialDatePicker works in UTC midnights; convert a local day to that representation. */
    private static long localDayToUtc(long localMillis) {
        Calendar local = Calendar.getInstance();
        local.setTimeInMillis(localMillis);
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        utc.clear();
        utc.set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH));
        return utc.getTimeInMillis();
    }

    private static long utcDayToLocalNoon(long utcMillis) {
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        utc.setTimeInMillis(utcMillis);
        Calendar local = Calendar.getInstance();
        local.clear();
        local.set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), 12, 0, 0);
        return local.getTimeInMillis();
    }
}
