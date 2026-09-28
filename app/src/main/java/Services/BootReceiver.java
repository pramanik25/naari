package Services;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.example.naarishakti.MainActivity;
import com.example.naarishakti.R;
import com.example.naarishakti.VoiceRecognitionService;
import com.example.naarishakti.core.ProtectionController;

import Home_Activity.AlarmManagerHelper;
import Location.GeofenceService;

/**
 * After a reboot or app update: restore schedules and geofences, and bring protection back if the
 * user had it on. Android 14 does not let a boot receiver start a microphone foreground service,
 * so there we post a "Tap to resume protection" notification instead.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";
    private static final int RESUME_NOTIFICATION_ID = 1003;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        Log.d(TAG, "Restoring state after " + action);

        try {
            AlarmManagerHelper.reschedule(context);
        } catch (Exception e) {
            Log.e(TAG, "Reschedule failed", e);
        }
        GeofenceService.sync(context);

        if (!ProtectionController.isProtectionWanted(context)) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            showResumeNotification(context);
            return;
        }
        try {
            ProtectionController.start(context);
        } catch (Exception e) {
            Log.e(TAG, "Couldn't resume protection", e);
            showResumeNotification(context);
        }
    }

    /** High-priority "Tap to resume protection" notification that opens the app. */
    public static void showResumeNotification(Context context) {
        Context app = context.getApplicationContext();
        VoiceRecognitionService.createChannels(app);
        PendingIntent open = PendingIntent.getActivity(app, 3,
                new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(app, VoiceRecognitionService.CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.eng_ic_shield)
                .setColor(ContextCompat.getColor(app, R.color.ns_rose))
                .setContentTitle(app.getString(R.string.eng_resume_title))
                .setContentText(app.getString(R.string.eng_resume_text))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(app.getString(R.string.eng_resume_text)))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(open);
        VoiceRecognitionService.notifySafely(app, RESUME_NOTIFICATION_ID, b.build());
    }
}
