package Location;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.example.naarishakti.R;
import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofencingClient;
import com.google.android.gms.location.GeofencingRequest;
import com.google.android.gms.location.LocationServices;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class GeofenceService extends Service {

    private static final String TAG = "GeofenceService";
    private static final String CHANNEL_ID = "GeofenceChannel";
    private static final int NOTIFICATION_ID = 2;
    private GeofencingClient geofencingClient;
    private PendingIntent geofencePendingIntent;
    private SharedPreferences sharedPreferences;
    private List<GeofenceData> geofenceDataList = new ArrayList<>();
    private final String GEOFENCE_LIST_KEY = "geofence_list_key";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "GeofenceService created");
        geofencingClient = LocationServices.getGeofencingClient(this);
        sharedPreferences = getSharedPreferences("AppPrefs", MODE_PRIVATE);
        loadGeofenceList();
        createNotificationChannel();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Geofence Alerts",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "GeofenceService started");
        startForegroundService();
        if (intent != null) {
            String action = intent.getAction();
            if ("ADD_GEOFENCE".equals(action)) {
                double latitude = intent.getDoubleExtra("latitude", 0);
                double longitude = intent.getDoubleExtra("longitude", 0);
                float radius = intent.getFloatExtra("radius", 100);
                String transitionAlert=intent.getStringExtra("transitionAlert");
                String geofenceId=intent.getStringExtra("geofenceId");
                if (geofenceId == null){
                    addGeofence(latitude, longitude, radius,transitionAlert);
                } else {
                    addGeofence(latitude, longitude, radius, transitionAlert,geofenceId);
                }
            } else if ("REMOVE_GEOFENCE".equals(action)) {
                String geofenceId=intent.getStringExtra("geofenceId");
                removeGeofence(geofenceId);

            } else if ("REMOVE_ALL_GEOFENCE".equals(action)) {
                removeAllGeofences();
            }
        }

        return START_STICKY;
    }

    private void startForegroundService() {
        Notification notification = buildNotification();
        startForeground(NOTIFICATION_ID, notification);
    }

    private Notification buildNotification() {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Geofence Service")
                .setContentText("Monitoring geofences...")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true);
        return builder.build();
    }


    private void addGeofence(double latitude, double longitude, float radius,String transitionAlert) {
        addGeofence( latitude, longitude,  radius, transitionAlert,null);

    }

    private void addGeofence(double latitude, double longitude, float radius,String transitionAlert,String geofenceId) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Location permission not granted.");
            return;
        }
        if (geofenceId == null){
            geofenceId = UUID.randomUUID().toString();

        }
        Geofence geofence = new Geofence.Builder()
                .setRequestId(geofenceId)
                .setCircularRegion(latitude, longitude, radius)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER | Geofence.GEOFENCE_TRANSITION_EXIT)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .build();

        GeofencingRequest geofencingRequest = new GeofencingRequest.Builder()
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                .addGeofence(geofence)
                .build();


        Intent geofenceIntent = new Intent(this, GeofenceBroadcastReceiver.class);
        geofencePendingIntent = PendingIntent.getBroadcast(this, 0, geofenceIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);


        String finalGeofenceId = geofenceId;
        geofencingClient.addGeofences(geofencingRequest, geofencePendingIntent)
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "Geofence added successfully: " + finalGeofenceId);

                    saveGeofenceData(finalGeofenceId,latitude,longitude,radius,transitionAlert);

                })
                .addOnFailureListener(e -> {

                    Log.e(TAG, "Failed to add geofence: " + e.getMessage());
                });

    }
    private void saveGeofenceData(String geofenceId,double latitude,double longitude, float radius,String transitionAlert) {
        GeofenceData geofenceData = new GeofenceData(geofenceId,latitude, longitude, radius, transitionAlert);
        geofenceDataList.add(geofenceData);

        saveGeofenceList();
    }

    private void loadGeofenceList() {

        String json = sharedPreferences.getString(GEOFENCE_LIST_KEY,null);
        if(json != null){
            Gson gson =new Gson();
            Type type = new TypeToken<ArrayList<GeofenceData>>() {}.getType();
            geofenceDataList = gson.fromJson(json,type);

            Log.d(TAG, "List: " +  geofenceDataList);

        }


        if (geofenceDataList == null ) {
            geofenceDataList=new ArrayList<>();
        }


    }

    private void saveGeofenceList(){
        SharedPreferences.Editor editor= sharedPreferences.edit();
        Gson gson = new Gson();
        String json =gson.toJson(geofenceDataList);
        editor.putString(GEOFENCE_LIST_KEY,json);
        editor.apply();


    }
    private void removeGeofence(String geofenceId) {
        List<String> geofenceIdsToRemove = new ArrayList<>();
        geofenceIdsToRemove.add(geofenceId);


        geofencingClient.removeGeofences(geofenceIdsToRemove)
                .addOnSuccessListener(aVoid -> {


                    removeGeofenceData(geofenceId);

                    Log.d(TAG, "Geofence removed successfully with id " + geofenceId);

                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to remove geofence with id " + geofenceId+  ": " + e.getMessage());
                });


    }

    private void removeGeofenceData(String geofenceId){
        for (GeofenceData data : geofenceDataList) {
            if(data.getGeofenceId().equals(geofenceId)){
                geofenceDataList.remove(data);
                break;
            }
        }

        saveGeofenceList();


    }

    private void removeAllGeofences(){
        if(geofencePendingIntent != null)
            geofencingClient.removeGeofences(geofencePendingIntent)
                    .addOnSuccessListener(aVoid -> {
                        Log.d(TAG,"Removed all  geofences");

                        clearGeofenceList();

                    })
                    .addOnFailureListener(e ->  Log.e(TAG,"failed to remove geofence " + e.getMessage()));
    }

    private void clearGeofenceList() {
        geofenceDataList.clear();
        saveGeofenceList();

    }


    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "GeofenceService destroyed");
        stopForeground(true);
        removeAllGeofences();

    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    private static class GeofenceData {
        private String geofenceId;
        private double latitude;
        private double longitude;
        private float radius;
        private String transitionAlert;

        public GeofenceData(String geofenceId,double latitude,double longitude, float radius, String transitionAlert) {
            this.geofenceId = geofenceId;
            this.latitude = latitude;
            this.longitude = longitude;
            this.radius = radius;
            this.transitionAlert = transitionAlert;
        }

        public String getGeofenceId() {
            return geofenceId;
        }


        public double getLatitude() {
            return latitude;
        }

        public double getLongitude() {
            return longitude;
        }

        public float getRadius() {
            return radius;
        }
        public String getTransitionAlert(){
            return  transitionAlert;

        }

    }
}