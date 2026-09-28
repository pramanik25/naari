package Home_Activity;

import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.example.naarishakti.core.ProtectionController;

import Services.BootReceiver;

/**
 * Receives the schedule alarms set by {@link AlarmManagerHelper} and turns protection on or off.
 * Also re-arms the schedule when the clock, time zone or exact-alarm permission changes.
 */
public class AlarmReceiver extends BroadcastReceiver {

    private static final String TAG = "AlarmReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.d(TAG, "Received " + action);

        if (AlarmManagerHelper.ACTION_SCHEDULE_START.equals(action)) {
            try {
                ProtectionController.start(context);
            } catch (Exception e) {
                Log.e(TAG, "Scheduled start failed", e);
                BootReceiver.showResumeNotification(context);
            }
            if (AlarmManagerHelper.isDaily(context)) AlarmManagerHelper.reschedule(context);
        } else if (AlarmManagerHelper.ACTION_SCHEDULE_END.equals(action)) {
            if (!ProtectionController.isPanicActive()) ProtectionController.stop(context);
            if (AlarmManagerHelper.isDaily(context)) AlarmManagerHelper.reschedule(context);
        } else if (Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action)
                || AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED.equals(action)) {
            AlarmManagerHelper.reschedule(context);
        }
    }
}
