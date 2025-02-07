package Location;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.airbnb.lottie.LottieAnimationView;
import com.example.naarishakti.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.gson.Gson;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import org.osmdroid.api.IMapController;
import org.osmdroid.config.Configuration;
import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polygon; // Import for the overlay Polygon

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class GeofenceSettingsActivity extends AppCompatActivity {
    private MapView mapView;
    private IMapController mapController;
    private TextInputEditText latitudeEditText;
    private TextInputEditText longitudeEditText;
    private TextView radiusTextView;
    private TextInputLayout transitionTextLayout;
    private TextInputEditText transitionEditText;

    private MaterialButton addGeofenceButton;
    private MaterialButton removeGeofenceButton;
    private Slider radiusSlider;
    private LottieAnimationView geofenceAnimation;
    private RecyclerView geofencesRecyclerView;
    private SharedPreferences sharedPreferences;
    private List<GeofenceData> geofenceDataList = new ArrayList<>();

    private GeofenceAdapter geofenceAdapter;
    private final String GEOFENCE_LIST_KEY = "geofence_list_key";
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Configure osmdroid user agent
        Configuration.getInstance().setUserAgentValue(getPackageName());

        // Set the activity's layout
        setContentView(R.layout.activity_geofence_settings);

        // Initialize UI components using layout IDs
        try {
            latitudeEditText = findViewById(R.id.latitudeEditText);
            longitudeEditText = findViewById(R.id.longitudeEditText);
            radiusTextView = findViewById(R.id.radiusTextView);
            transitionTextLayout = findViewById(R.id.transitionTextLayout);
            transitionEditText = findViewById(R.id.transitionEditText);
            addGeofenceButton = findViewById(R.id.addGeofenceButton);
            removeGeofenceButton = findViewById(R.id.removeGeofenceButton);
            radiusSlider = findViewById(R.id.radiusSlider);
            geofenceAnimation = findViewById(R.id.geofenceAnimation);
            mapView = findViewById(R.id.mapView);
            geofencesRecyclerView = findViewById(R.id.geofencesRecyclerView);

            // Initialize mapView settings
            mapView.setTileSource(TileSourceFactory.MAPNIK);
            mapView.setMultiTouchControls(true);
            mapController = mapView.getController();
            mapController.setZoom(15.0);

            // Set up RecyclerView
            geofencesRecyclerView.setLayoutManager(new LinearLayoutManager(this));

            // Initialize SharedPreferences
            sharedPreferences = getSharedPreferences("AppPrefs", MODE_PRIVATE);
            loadGeofenceList();

            // Set up GeofenceAdapter
            geofenceAdapter = new GeofenceAdapter(this, geofenceDataList);
            geofencesRecyclerView.setAdapter(geofenceAdapter);

            // Set button click listeners
            addGeofenceButton.setOnClickListener(v -> addGeofence());
            removeGeofenceButton.setOnClickListener(v -> removeAllGeofences());

            // Set up map event overlay for taps
            MapEventsOverlay mapEventsOverlay = new MapEventsOverlay(new MapEventsReceiver() {
                @Override
                public boolean singleTapConfirmedHelper(GeoPoint geoPoint) {
                    latitudeEditText.setText(String.valueOf(geoPoint.getLatitude()));
                    longitudeEditText.setText(String.valueOf(geoPoint.getLongitude()));
                    updateMap(geoPoint, radiusTextView.getText().toString());
                    return true;
                }

                @Override
                public boolean longPressHelper(GeoPoint p) {
                    return false;
                }
            });
            mapView.getOverlays().add(mapEventsOverlay);

            // Request location permissions if not already granted
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                getCurrentLocation();
            } else {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 101);
            }

            // Set up radius slider change listener
            radiusSlider.addOnChangeListener((slider, value, fromUser) -> radiusTextView.setText(String.valueOf((int) value)));

        } catch (Exception e) {
            Log.e("onCreate", "Error initializing components: " + e.getMessage(), e);
            Toast.makeText(this, "Initialization error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }


    private void updateMap(GeoPoint geoPoint, String radiusStr) {
        mapView.getOverlays().clear();
        mapView.invalidate(); // Refresh the map

        if (geoPoint != null) {
            mapController.setCenter(geoPoint);

            Marker startMarker = new Marker(mapView);
            startMarker.setPosition(geoPoint);
            startMarker.setTitle("Selected Geofence Location");
            mapView.getOverlays().add(startMarker);

            if (!radiusStr.isEmpty()) {
                try {
                    double radius = Double.parseDouble(radiusStr);
                    Polygon circle = new Polygon(mapView); // Use the overlay Polygon
                    circle.setPoints(getCirclePoints(geoPoint, radius));
                    circle.setFillColor(0x220000FF); // Semi-transparent blue
                    circle.setStrokeColor(Color.BLUE);
                    circle.setStrokeWidth(2.0f);
                    mapView.getOverlays().add(circle);
                } catch (NumberFormatException e) {
                    Toast.makeText(this, "Invalid Radius Value", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    // Generate points for the circle
    private List<GeoPoint> getCirclePoints(GeoPoint center, double radius) {
        List<GeoPoint> circlePoints = new ArrayList<>();
        int numPoints = 360; // Number of points for the circle

        for (int i = 0; i < numPoints; i++) {
            double angle = (2 * Math.PI * i) / numPoints;
            double dx = radius * Math.cos(angle);
            double dy = radius * Math.sin(angle);
            GeoPoint point = new GeoPoint(center.getLatitude() + (dy / 110540), center.getLongitude() + (dx / (111320 * Math.cos(Math.toRadians(center.getLatitude())))));
            circlePoints.add(point);
        }

        return circlePoints;
    }

    @SuppressLint("MissingPermission")
    private void getCurrentLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        android.location.LocationManager locationManager = (android.location.LocationManager) getSystemService(Context.LOCATION_SERVICE);
        android.location.Location lastKnownLocation = locationManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER);
        if (lastKnownLocation == null) {
            lastKnownLocation = locationManager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER);
        }

        if (lastKnownLocation != null) {
            GeoPoint geoPoint = new GeoPoint(lastKnownLocation.getLatitude(), lastKnownLocation.getLongitude());
            mapController.setCenter(geoPoint);
            latitudeEditText.setText(String.valueOf(lastKnownLocation.getLatitude()));
            longitudeEditText.setText(String.valueOf(lastKnownLocation.getLongitude()));
            updateMap(geoPoint, radiusTextView.getText().toString());
        } else {
            Log.e("GEO Settings ", "Could not receive last location by Providers.");
            Toast.makeText(this, "Could not find current device locations from service providers!", Toast.LENGTH_SHORT).show();
        }
    }

    private void addGeofence() {
        String latitudeStr = latitudeEditText.getText().toString();
        String longitudeStr = longitudeEditText.getText().toString();
        String radiusStr = radiusTextView.getText().toString();
        String transitionAlertText = transitionEditText.getText().toString();

        if (latitudeStr.isEmpty() || longitudeStr.isEmpty() || radiusStr.isEmpty()) {
            Toast.makeText(this, "Fill All Data to setup locations.", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            double latitude = Double.parseDouble(latitudeStr);
            double longitude = Double.parseDouble(longitudeStr);
            float radius = Float.parseFloat(radiusStr);
            if (radius <= 0) {
                Toast.makeText(this, "Invalid radius: " + radius + ". Radius must be greater than zero.", Toast.LENGTH_SHORT).show();
                return;
            }

            String geofenceId = UUID.randomUUID().toString();
            GeofenceData geofenceData = new GeofenceData(geofenceId, latitude, longitude, radius, transitionAlertText);
            geofenceDataList.add(geofenceData);
            saveGeofenceList();
            geofenceAdapter.updateData(geofenceDataList);

            // Start the service to handle geofencing
            Intent serviceIntent = new Intent(this, GeofenceService.class);
            serviceIntent.setAction("ADD_GEOFENCE");
            serviceIntent.putExtra("geofenceId", geofenceId);
            serviceIntent.putExtra("latitude", latitude);
            serviceIntent.putExtra("longitude", longitude);
            serviceIntent.putExtra("radius", radius);
            serviceIntent.putExtra("transitionAlert", transitionAlertText);
            startService(serviceIntent);

            Toast.makeText(this, "Added location to saved places", Toast.LENGTH_SHORT).show();

        } catch (NumberFormatException e) {
            Toast.makeText(this, "Invalid latitude, longitude, or radius number format: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            Log.e("number format exc", e.getMessage());
        }
    }


    private void loadGeofenceList() {
        if (sharedPreferences == null) {
            throw new IllegalStateException("SharedPreferences not initialized");
        }

        String json = sharedPreferences.getString(GEOFENCE_LIST_KEY, null);
        if (json != null) {
            try {
                Gson gson = new Gson();
                Type type = new TypeToken<ArrayList<GeofenceData>>() {}.getType();
                geofenceDataList = gson.fromJson(json, type);
            } catch (JsonSyntaxException e) {
                Log.e("GeofenceService", "Failed to parse JSON: " + e.getMessage());
                geofenceDataList = new ArrayList<>();
            }
        }

        if (geofenceDataList == null) {
            geofenceDataList = new ArrayList<>();
        }
    }


    private void saveGeofenceList() {
        if (sharedPreferences == null) {
            throw new IllegalStateException("SharedPreferences not initialized");
        }

        try {
            SharedPreferences.Editor editor = sharedPreferences.edit();
            Gson gson = new Gson();
            String json = gson.toJson(geofenceDataList);

            // Adding an additional check to ensure the list is properly converted to JSON
            if (json == null) {
                throw new JsonIOException("Failed to convert geofence data list to JSON");
            }

            editor.putString(GEOFENCE_LIST_KEY, json);

            // Ensure the apply operation completes successfully
            boolean commitResult = editor.commit();
            if (!commitResult) {
                throw new RuntimeException("Failed to save geofence data list");
            }

            Log.d("saveGeofenceList", "Geofence data list saved successfully");

        } catch (JsonIOException e) {
            Log.e("saveGeofenceList", "Failed to convert geofence data list to JSON: " + e.getMessage());
        } catch (Exception e) {
            Log.e("saveGeofenceList", "Unexpected error while saving geofence data list: " + e.getMessage());
        }
    }


    private void removeAllGeofences() {
        if (geofenceDataList != null && !geofenceDataList.isEmpty()) {
            for (GeofenceData data : geofenceDataList) {
                Intent serviceIntent = new Intent(this, GeofenceService.class);
                serviceIntent.setAction("REMOVE_GEOFENCE");
                serviceIntent.putExtra("geofenceId", data.getGeofenceId());
                startService(serviceIntent);
            }
            geofenceDataList.clear();
            saveGeofenceList();
            geofenceAdapter.updateData(geofenceDataList);
            Toast.makeText(this, "All stored locations removed", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "You haven't any stored locations", Toast.LENGTH_SHORT).show();
        }
    }

    // Custom Modal Data type
    public static class GeofenceData {
        private String geofenceId;
        private double latitude;
        private double longitude;
        private float radius;
        private String transitionAlert;

        public GeofenceData(String geofenceId, double latitude, double longitude, float radius, String transitionAlert) {
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

        public String getTransitionAlert() {
            return transitionAlert;
        }
    }

    // Data adapter for recycleView for specific components for the activity list to show data
    private static class GeofenceAdapter extends RecyclerView.Adapter<GeofenceAdapter.ViewHolder> {
        private List<GeofenceData> geofenceDataList;
        private LayoutInflater layoutInflater;

        public GeofenceAdapter(Context context, List<GeofenceData> geofenceDataList) {
            this.geofenceDataList = geofenceDataList;
            layoutInflater = LayoutInflater.from(context);
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = layoutInflater.inflate(R.layout.geofence_item_card, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            GeofenceData geofenceData = geofenceDataList.get(position);
            holder.geoFenceIdTextview.setText("GeoID: " + geofenceData.getGeofenceId().substring(0, 8) + "...");
            holder.locationTextview.setText("" + geofenceData.getLatitude() + ", " + geofenceData.getLongitude());
            holder.radiusTextView.setText("Radius: " + String.valueOf(geofenceData.getRadius()));
            holder.transitionAlertText.setText("" + geofenceData.getTransitionAlert());
        }

        @Override
        public int getItemCount() {
            return geofenceDataList.size();
        }

        public void updateData(List<GeofenceData> newGeofenceDataList) {
            this.geofenceDataList = newGeofenceDataList;
            notifyDataSetChanged();
        }

        private static class ViewHolder extends RecyclerView.ViewHolder {
            TextView geoFenceIdTextview;
            TextView locationTextview;
            TextView radiusTextView;
            TextView transitionAlertText;

            public ViewHolder(@NonNull View itemView) {
                super(itemView);
                geoFenceIdTextview = itemView.findViewById(R.id.geoFenceIdTextView);
                locationTextview = itemView.findViewById(R.id.locationTextView);
                radiusTextView = itemView.findViewById(R.id.radiusTextView);
                transitionAlertText = itemView.findViewById(R.id.transitionTextview);
            }
        }
    }
}