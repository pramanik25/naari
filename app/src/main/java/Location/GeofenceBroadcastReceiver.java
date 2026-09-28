package Location;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.example.naarishakti.MainActivity;
import com.example.naarishakti.R;
import com.example.naarishakti.VoiceRecognitionService;
import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofenceStatusCodes;
import com.google.android.gms.location.GeofencingEvent;

import java.util.List;

/** Posts "Entered/Left <place message>" when Play Services reports a geofence transition. */
public class GeofenceBroadcastReceiver extends BroadcastReceiver {

    private static final String TAG = "GeofenceReceiver";
    private static final int NOTIFICATION_ID_BASE = 3000;

    @Override
    public void onReceive(Context context, Intent intent) {
        GeofencingEvent event = GeofencingEvent.fromIntent(intent);
        if (event == null) return;
        if (event.hasError()) {
            Log.e(TAG, "Geofence error: " + GeofenceStatusCodes.getStatusCodeString(event.getErrorCode()));
            return;
        }
        int transition = event.getGeofenceTransition();
        if (transition != Geofence.GEOFENCE_TRANSITION_ENTER && transition != Geofence.GEOFENCE_TRANSITION_EXIT) {
            return;
        }
        List<Geofence> triggered = event.getTriggeringGeofences();
        if (triggered == null) return;

        List<GeofenceService.Place> places = GeofenceService.loadPlaces(context);
        for (Geofence geofence : triggered) {
            String message = null;
            for (GeofenceService.Place p : places) {
                if (p.id.equals(geofence.getRequestId())) message = p.message;
            }
            notify(context, geofence.getRequestId(), transition == Geofence.GEOFENCE_TRANSITION_ENTER, message);
        }
    }

    private static void notify(Context context, String id, boolean entered, String message) {
        String place = TextUtils.isEmpty(message)
                ? context.getString(R.string.eng_geofence_default_place) : message;
        String title = context.getString(entered ? R.string.eng_geofence_entered : R.string.eng_geofence_left, place);

        VoiceRecognitionService.createChannels(context);
        PendingIntent open = PendingIntent.getActivity(context, 4,
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(context, VoiceRecognitionService.CHANNEL_GEOFENCE)
                .setSmallIcon(R.drawable.eng_ic_location)
                .setColor(ContextCompat.getColor(context, R.color.ns_violet))
                .setContentTitle(title)
                .setContentText(context.getString(R.string.eng_geofence_text))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(open);
        VoiceRecognitionService.notifySafely(context, NOTIFICATION_ID_BASE + (id.hashCode() & 0xFFF), b.build());
    }
}
