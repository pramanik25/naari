package Location;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.example.naarishakti.MainActivity;
import com.example.naarishakti.R;

public class GeofenceBroadcastReceiver extends BroadcastReceiver {

    private static final String TAG = "GeofenceReceiver";
    private static final String CHANNEL_ID = "GeofenceTransitionChannel";
    private static final int TRANSITION_NOTIFICATION_ID = 3;

    // Define keys for the Intent extras
    public static final String EXTRA_GEOFENCE_ID = "geofence_id";
    public static final String EXTRA_TRANSITION_TYPE = "transition_type";
    public static final int TRANSITION_ENTER = 1;
    public static final int TRANSITION_EXIT = 2;

    @Override
    public void onReceive(Context context, Intent intent) {
        createNotificationChannel(context);

        String geofenceId = intent.getStringExtra(EXTRA_GEOFENCE_ID);
        int transitionType = intent.getIntExtra(EXTRA_TRANSITION_TYPE, -1);

        if (geofenceId == null || transitionType == -1) {
            Log.e(TAG, "Received invalid geofence broadcast intent");
            return;
        }

        Log.d(TAG, "Geofence transition: " + (transitionType == TRANSITION_ENTER ? "Entered" : "Exited") + " - " + geofenceId);
        sendNotification(context, transitionType, geofenceId);
    }

    private void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Geofence Transition Alerts",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private void sendNotification(Context context, int transitionType, String geofenceId) {
        String transitionName = transitionType == TRANSITION_ENTER ? "entered" : "exited";

        Intent notificationIntent = new Intent(context, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Geofence Alert")
                .setContentText("You have " + transitionName + " the " + geofenceId + " region")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent);

        NotificationManager notificationManager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(TRANSITION_NOTIFICATION_ID, builder.build());
    }
}