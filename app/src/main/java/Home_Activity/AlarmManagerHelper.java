package Alarm;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

import com.example.naarishakti.VoskService;

import java.util.Calendar;

public class AlarmManagerHelper {

    private static final String TAG = "AlarmManagerHelper";

    public static void scheduleStartAlarm(Context context, Calendar calendar) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent alarmIntent = new Intent(context, VoskService.class);
        alarmIntent.putExtra("start_service", true); // Explicitly start the service
        PendingIntent pendingIntent = PendingIntent.getService(context, 1, alarmIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        if (alarmManager != null) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.getTimeInMillis(), pendingIntent);
            Log.d(TAG, "Start alarm scheduled for: " + calendar.getTime());
        } else {
            Log.e(TAG, "AlarmManager is null, cannot schedule start alarm.");
            Toast.makeText(context,"Alarm Schedule Cancelled", Toast.LENGTH_SHORT).show();
        }
    }

    public static void scheduleStopAlarm(Context context, Calendar calendar) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent alarmIntent = new Intent(context, VoskService.class);
        alarmIntent.putExtra("stop_service", true);
        PendingIntent pendingIntent = PendingIntent.getService(context, 2, alarmIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        if (alarmManager != null) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.getTimeInMillis(), pendingIntent);
            Log.d(TAG, "Stop alarm scheduled for: " + calendar.getTime());
        } else {
            Log.e(TAG, "AlarmManager is null, cannot schedule stop alarm.");
            Toast.makeText(context,"Alarm Schedule Cancelled", Toast.LENGTH_SHORT).show();
        }
    }
}