package Home_Activity;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.naarishakti.R;
import com.example.naarishakti.VoskService;

import java.util.Calendar;

public class TimeSettingsActivity extends AppCompatActivity {

    private TextView startTimeTextView;
    private TextView endTimeTextView;
    private Button setStartTimeButton;
    private Button setEndTimeButton;
    private Button saveSettingsButton;

    private int startTimeHour;
    private int startTimeMinute;
    private int endTimeHour;
    private int endTimeMinute;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_time_settings);

        startTimeTextView = findViewById(R.id.start_time_text_view);
        endTimeTextView = findViewById(R.id.end_time_text_view);
        setStartTimeButton = findViewById(R.id.set_start_time_button);
        setEndTimeButton = findViewById(R.id.set_end_time_button);
        saveSettingsButton = findViewById(R.id.save_time_settings_button);

        loadTimeSettings();
        updateTimeTextViews();

        setStartTimeButton.setOnClickListener(v -> showTimePickerDialog(true));
        setEndTimeButton.setOnClickListener(v -> showTimePickerDialog(false));
        saveSettingsButton.setOnClickListener(v -> saveTimeSettings());
    }

    private void loadTimeSettings() {
        SharedPreferences sharedPreferences = getSharedPreferences("AppPrefs", MODE_PRIVATE);
        if (sharedPreferences != null) {
            startTimeHour = sharedPreferences.getInt("start_time_hour", 0);
            startTimeMinute = sharedPreferences.getInt("start_time_minute", 0);
            endTimeHour = sharedPreferences.getInt("end_time_hour", 23);
            endTimeMinute = sharedPreferences.getInt("end_time_minute", 59);
        } else {
            Log.e("TimeSettingsActivity", "SharedPreferences is null, cannot load settings.");
        }
    }

    private void updateTimeTextViews() {
        startTimeTextView.setText(String.format("%02d:%02d", startTimeHour, startTimeMinute));
        endTimeTextView.setText(String.format("%02d:%02d", endTimeHour, endTimeMinute));
    }

    private void showTimePickerDialog(final boolean isStartTime) {
        Calendar calendar = Calendar.getInstance();
        int hour = isStartTime ? startTimeHour : endTimeHour;
        int minute = isStartTime ? startTimeMinute : endTimeMinute;

        TimePickerDialog timePickerDialog = new TimePickerDialog(this,
                (view, hourOfDay, minuteOfHour) -> {
                    if (isStartTime) {
                        startTimeHour = hourOfDay;
                        startTimeMinute = minuteOfHour;
                    } else {
                        endTimeHour = hourOfDay;
                        endTimeMinute = minuteOfHour;
                    }
                    updateTimeTextViews();
                }, hour, minute, true);
        timePickerDialog.show();
    }

    private void saveTimeSettings() {
        SharedPreferences sharedPreferences = getSharedPreferences("AppPrefs", MODE_PRIVATE);
        if (sharedPreferences != null) {
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putInt("start_time_hour", startTimeHour);
            editor.putInt("start_time_minute", startTimeMinute);
            editor.putInt("end_time_hour", endTimeHour);
            editor.putInt("end_time_minute", endTimeMinute);
            editor.apply();

            Toast.makeText(this, "Time settings saved", Toast.LENGTH_SHORT).show();

            // Send broadcast to update the service
            Intent intent = new Intent("com.example.naarishakti.ACTION_TIME_SETTINGS_UPDATED");
            sendBroadcast(intent);

            // Schedule the alarms
            scheduleStartAlarm();
            scheduleStopAlarm();

            finish(); // Close the activity after saving
        } else {
            Log.e("TimeSettingsActivity", "SharedPreferences is null, cannot save settings.");
            Toast.makeText(this, "Failed to save time settings.", Toast.LENGTH_SHORT).show();
        }
    }

    private void scheduleStartAlarm() {
        AlarmManager alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
        Intent alarmIntentService = new Intent(this, VoskService.class);
        alarmIntentService.putExtra("start_service", true); // Explicitly start the service
        PendingIntent alarmIntent = PendingIntent.getService(this, 1, alarmIntentService, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, startTimeHour);
        calendar.set(Calendar.MINUTE, startTimeMinute);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);

        // If the time is in the past, add one day
        if (calendar.getTimeInMillis() <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_YEAR, 1);
        }

        long triggerTime = calendar.getTimeInMillis();

        if (alarmManager != null) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, alarmIntent);
            Log.d("TimeSettingsActivity", "Start alarm scheduled for: " + calendar.getTime());
        } else {
            Log.e("TimeSettingsActivity", "AlarmManager is null, cannot schedule start alarm.");
        }
    }

    private void scheduleStopAlarm() {
        AlarmManager alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
        Intent alarmIntentService = new Intent(this, VoskService.class);
        alarmIntentService.putExtra("stop_service", true);
        PendingIntent alarmIntent = PendingIntent.getService(this, 2, alarmIntentService, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, endTimeHour);
        calendar.set(Calendar.MINUTE, endTimeMinute);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);

        if (calendar.getTimeInMillis() <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_YEAR, 1);
        }

        long triggerTime = calendar.getTimeInMillis();

        if (alarmManager != null) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, alarmIntent);
            Log.d("TimeSettingsActivity", "Stop alarm scheduled for: " + calendar.getTime());
        } else {
            Log.e("TimeSettingsActivity", "AlarmManager is null, cannot schedule stop alarm.");
        }
    }


}
