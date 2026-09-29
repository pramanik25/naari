package com.example.naarishakti.journey;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.location.Geocoder;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.util.Log;
import android.widget.ImageView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.widget.ImageViewCompat;

import com.example.naarishakti.R;
import com.example.naarishakti.VoiceRecognitionService;
import com.example.naarishakti.core.Prefs;
import com.example.naarishakti.databinding.JrActivityMeetingBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;

import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Marker;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import Home_Activity.FeatureKit;
import Home_Activity.SettingsEmergencyActivity;

/**
 * Meeting someone new (a date, a buyer/seller, an interview): note who, where and until when,
 * SMS the contacts a summary, then run a check-in that ends at the expected end time.
 */
public class MeetingModeActivity extends AppCompatActivity {

    private static final String TAG = "MeetingMode";
    private static final double DEFAULT_LAT = 20.5937;
    private static final double DEFAULT_LNG = 78.9629;
    private static final int PENDING_NONE = 0;
    private static final int PENDING_CAMERA = 1;
    private static final int PENDING_START = 2;
    private static final int PENDING_HERE = 3;

    private JrActivityMeetingBinding b;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private ActivityResultLauncher<String[]> permLauncher;
    private ActivityResultLauncher<Uri> takePicture;
    private ActivityResultLauncher<String> pickImage;
    private int pending = PENDING_NONE;

    @Nullable private File tempCapture;
    @Nullable private File photoFile;
    @Nullable private GeoPoint place;
    @Nullable private Marker placeMarker;
    private boolean placeTyped;
    private boolean settingPlace;
    private long endTime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FeatureKit.initOsmdroid(this);
        b = JrActivityMeetingBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        permLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(), this::onPermissions);
        takePicture = registerForActivityResult(new ActivityResultContracts.TakePicture(), ok -> {
            File tmp = tempCapture;
            tempCapture = null;
            if (Boolean.TRUE.equals(ok) && tmp != null) {
                importPhoto(Uri.fromFile(tmp), tmp);
            } else if (tmp != null) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        });
        pickImage = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) importPhoto(uri, null);
        });

        b.backButton.setOnClickListener(v -> finish());
        b.cameraButton.setOnClickListener(v -> {
            if (JourneyUtil.granted(this, Manifest.permission.CAMERA)) {
                launchCamera();
            } else {
                pending = PENDING_CAMERA;
                permLauncher.launch(new String[]{Manifest.permission.CAMERA});
            }
        });
        b.galleryButton.setOnClickListener(v -> {
            try {
                pickImage.launch("image/*");
            } catch (Exception e) {
                Snackbar.make(b.getRoot(), R.string.jr_meet_no_gallery, Snackbar.LENGTH_SHORT).show();
            }
        });
        b.hereButton.setOnClickListener(v -> {
            if (JourneyUtil.hasLocation(this)) {
                useCurrentLocation();
            } else {
                pending = PENDING_HERE;
                permLauncher.launch(JourneyUtil.locationPerms());
            }
        });
        b.placeInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) {}
            @Override public void onTextChanged(CharSequence s, int a, int c, int d) {}
            @Override public void afterTextChanged(Editable s) {
                if (!settingPlace) placeTyped = s.length() > 0;
            }
        });
        b.endCard.setOnClickListener(v -> pickEndTime());
        b.startButton.setOnClickListener(v -> onStartTapped());

        Calendar c = Calendar.getInstance();
        c.add(Calendar.HOUR_OF_DAY, 2);
        int minute = ((c.get(Calendar.MINUTE) + 14) / 15) * 15;
        c.set(Calendar.MINUTE, 0);
        c.add(Calendar.MINUTE, minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        endTime = c.getTimeInMillis();
        renderEnd();

        setupMap();
        if (JourneyUtil.hasLocation(this)) useCurrentLocation();
    }

    @Override
    protected void onResume() {
        super.onResume();
        b.mapView.onResume();
        int n = Prefs.getContactNumbers(this).size();
        b.contactsNote.setText(n == 0 ? getString(R.string.jr_contacts_none_sub)
                : getString(R.string.jr_meet_contacts_note, n));
    }

    @Override
    protected void onPause() {
        super.onPause();
        b.mapView.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        io.shutdownNow();
        b.mapView.onDetach();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ permissions

    private void onPermissions(Map<String, Boolean> result) {
        int action = pending;
        pending = PENDING_NONE;
        if (action == PENDING_CAMERA) {
            if (JourneyUtil.granted(this, Manifest.permission.CAMERA)) {
                launchCamera();
            } else {
                Snackbar.make(b.getRoot(), R.string.jr_meet_camera_denied, Snackbar.LENGTH_LONG)
                        .setAction(R.string.jr_open_settings, v -> JourneyUtil.openAppSettings(this))
                        .show();
            }
        } else if (action == PENDING_HERE) {
            if (JourneyUtil.hasLocation(this)) useCurrentLocation();
        } else if (action == PENDING_START) {
            startNow();
        }
    }

    // ------------------------------------------------------------------ photo

    private void launchCamera() {
        try {
            // FileProvider only exposes Pictures/Emergency; the file is moved to private storage after capture.
            File dir = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Emergency");
            if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("No capture dir");
            File tmp = new File(dir, "jr_meet_capture_" + System.currentTimeMillis() + ".jpg");
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".provider", tmp);
            tempCapture = tmp;
            takePicture.launch(uri);
        } catch (Exception e) {
            Log.w(TAG, "Camera capture unavailable", e);
            tempCapture = null;
            Snackbar.make(b.getRoot(), R.string.jr_meet_no_camera, Snackbar.LENGTH_SHORT).show();
        }
    }

    /** Copies (downscaled) into app-private storage; deletes the temporary capture. */
    private void importPhoto(final Uri source, @Nullable final File deleteAfter) {
        final Context app = getApplicationContext();
        io.execute(() -> {
            File out = null;
            try {
                Bitmap bmp = decodeSampled(app, source, 1280);
                if (bmp != null) {
                    File dir = new File(app.getFilesDir(), "jr_meeting");
                    if (dir.exists() || dir.mkdirs()) {
                        out = new File(dir, "person_" + System.currentTimeMillis() + ".jpg");
                        try (OutputStream os = new FileOutputStream(out)) {
                            bmp.compress(Bitmap.CompressFormat.JPEG, 88, os);
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Photo import failed", e);
                out = null;
            } finally {
                if (deleteAfter != null) {
                    //noinspection ResultOfMethodCallIgnored
                    deleteAfter.delete();
                }
            }
            final File saved = out;
            handler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (saved == null) {
                    Snackbar.make(b.getRoot(), R.string.jr_meet_photo_failed, Snackbar.LENGTH_SHORT).show();
                    return;
                }
                if (photoFile != null && !photoFile.equals(saved)) {
                    //noinspection ResultOfMethodCallIgnored
                    photoFile.delete();
                }
                photoFile = saved;
                Bitmap preview = BitmapFactory.decodeFile(saved.getAbsolutePath());
                ImageViewCompat.setImageTintList(b.photoView, null);
                b.photoView.setPadding(0, 0, 0, 0);
                b.photoView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                b.photoView.setImageBitmap(preview);
                Snackbar.make(b.getRoot(), R.string.jr_meet_photo_saved, Snackbar.LENGTH_SHORT).show();
            });
        });
    }

    @Nullable
    private static Bitmap decodeSampled(Context ctx, Uri uri, int maxPx) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            BitmapFactory.decodeStream(in, null, o);
        }
        int sample = 1;
        while (o.outWidth / (sample * 2) >= maxPx || o.outHeight / (sample * 2) >= maxPx) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            return BitmapFactory.decodeStream(in, null, o);
        }
    }

    // ------------------------------------------------------------------ place

    private void setupMap() {
        FeatureKit.styleMap(b.mapView);
        b.mapView.getController().setZoom(5.0);
        b.mapView.getController().setCenter(new GeoPoint(DEFAULT_LAT, DEFAULT_LNG));
        b.mapView.getOverlays().add(0, new MapEventsOverlay(new MapEventsReceiver() {
            @Override
            public boolean singleTapConfirmedHelper(GeoPoint p) {
                setPlace(p, true);
                return true;
            }

            @Override
            public boolean longPressHelper(GeoPoint p) {
                setPlace(p, true);
                return true;
            }
        }));
    }

    private void useCurrentLocation() {
        FeatureKit.fetchLocation(this, loc -> {
            if (loc == null || isFinishing() || isDestroyed()) return;
            GeoPoint p = new GeoPoint(loc.getLatitude(), loc.getLongitude());
            setPlace(p, false);
            b.mapView.getController().setZoom(16.0);
            b.mapView.getController().setCenter(p);
        });
    }

    private void setPlace(final GeoPoint p, boolean fromTap) {
        place = p;
        if (placeMarker == null) {
            placeMarker = new Marker(b.mapView);
            placeMarker.setIcon(ContextCompat.getDrawable(this, R.drawable.jr_pin_dest));
            placeMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            placeMarker.setInfoWindow(null);
            b.mapView.getOverlays().add(placeMarker);
        }
        placeMarker.setPosition(p);
        b.mapView.invalidate();
        if (fromTap) b.mapView.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
        if (placeTyped) return;
        setPlaceText(FeatureKit.formatCoords(p.getLatitude(), p.getLongitude()));
        if (!Geocoder.isPresent() || io.isShutdown()) return;
        final Context app = getApplicationContext();
        io.execute(() -> {
            final String line = CabModeActivity.geocodeLine(app, p.getLatitude(), p.getLongitude());
            handler.post(() -> {
                if (line != null && !isFinishing() && !placeTyped && place == p) setPlaceText(line);
            });
        });
    }

    private void setPlaceText(String text) {
        settingPlace = true;
        b.placeInput.setText(text);
        settingPlace = false;
    }

    // ------------------------------------------------------------------ end time

    private void pickEndTime() {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(endTime);
        MaterialTimePicker picker = new MaterialTimePicker.Builder()
                .setTimeFormat(DateFormat.is24HourFormat(this) ? TimeFormat.CLOCK_24H : TimeFormat.CLOCK_12H)
                .setHour(c.get(Calendar.HOUR_OF_DAY))
                .setMinute(c.get(Calendar.MINUTE))
                .setTitleText(R.string.jr_meet_end_title)
                .build();
        picker.addOnPositiveButtonClickListener(v -> {
            endTime = CheckInActivity.nextOccurrence(picker.getHour(), picker.getMinute());
            renderEnd();
        });
        picker.show(getSupportFragmentManager(), "jr_meet_end");
    }

    private void renderEnd() {
        b.endText.setText(getString(R.string.jr_meet_ends_at, JourneyUtil.clock(this, endTime)));
    }

    // ------------------------------------------------------------------ start

    private void onStartTapped() {
        String name = text(b.nameInput.getText());
        if (name.isEmpty()) {
            b.nameLayout.setError(getString(R.string.jr_meet_name_needed));
            b.nameInput.requestFocus();
            return;
        }
        b.nameLayout.setError(null);
        if (Prefs.getContacts(this).isEmpty()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.jr_no_contacts_title)
                    .setMessage(R.string.jr_no_contacts_body)
                    .setPositiveButton(R.string.jr_add_contacts, (d, w) ->
                            startActivity(new Intent(this, SettingsEmergencyActivity.class)))
                    .setNegativeButton(R.string.jr_cancel, null)
                    .show();
            return;
        }
        if (endTime <= System.currentTimeMillis() + 60_000L) {
            Snackbar.make(b.getRoot(), R.string.jr_ci_time_passed, Snackbar.LENGTH_LONG).show();
            return;
        }
        String[] missing = JourneyUtil.missing(this, JourneyUtil.concat(
                JourneyUtil.concat(new String[]{Manifest.permission.SEND_SMS}, JourneyUtil.locationPerms()),
                JourneyUtil.notificationPerms()));
        if (missing.length == 0) {
            startNow();
        } else {
            pending = PENDING_START;
            permLauncher.launch(missing);
        }
    }

    private void startNow() {
        final String person = text(b.nameInput.getText());
        final String contact = text(b.contactInput.getText());
        final String placeName = text(b.placeInput.getText());
        final GeoPoint point = place;
        final long end = endTime;
        final Context app = getApplicationContext();
        final List<String> numbers = Prefs.getContactNumbers(app);
        final boolean canSms = JourneyUtil.granted(this, Manifest.permission.SEND_SMS);

        b.startButton.setEnabled(false);
        FeatureKit.LocationResult send = loc -> {
            final String link;
            if (point != null) link = JourneyUtil.mapsLink(point.getLatitude(), point.getLongitude());
            else if (loc != null) link = VoiceRecognitionService.mapsLink(loc);
            else link = null;
            JourneyUtil.io(() -> {
                String sms = buildSms(app, JourneyUtil.userName(app), person, contact, placeName, link, end);
                if (canSms) VoiceRecognitionService.sendSmsToAll(app, numbers, sms);
            });
            if (!canSms) Snackbar.make(b.getRoot(), R.string.jr_sms_denied, Snackbar.LENGTH_LONG).show();
            startCheckIn(person, placeName, end);
        };
        if (point != null) send.onLocation(null);
        else FeatureKit.fetchLocation(this, send);
    }

    private void startCheckIn(String person, String placeName, long end) {
        String note = TextUtils.isEmpty(placeName)
                ? getString(R.string.jr_meet_note, person)
                : getString(R.string.jr_meet_note_place, person, placeName);
        Intent i = new Intent(this, CheckInActivity.class)
                .putExtra(CheckInActivity.EXTRA_DEADLINE, end)
                .putExtra(CheckInActivity.EXTRA_NOTE, note)
                .putExtra(CheckInActivity.EXTRA_AUTOSTART, true);
        startActivity(i);
        finish();
    }

    static String buildSms(Context ctx, String me, String person, String contact, String placeName,
                           @Nullable String link, long end) {
        List<String> parts = new ArrayList<>();
        parts.add(ctx.getString(R.string.jr_sms_meet_intro, me, person));
        if (!TextUtils.isEmpty(contact)) parts.add(ctx.getString(R.string.jr_sms_meet_contact, contact));
        String where = TextUtils.isEmpty(placeName) ? ctx.getString(R.string.jr_sms_meet_place_unknown) : placeName;
        parts.add(link != null
                ? ctx.getString(R.string.jr_sms_meet_place, where, link)
                : ctx.getString(R.string.jr_sms_meet_place_nolink, where));
        parts.add(ctx.getString(R.string.jr_sms_meet_end, JourneyUtil.clock(ctx, end), me));
        return TextUtils.join(" ", parts);
    }

    private static String text(@Nullable Editable e) {
        return e == null ? "" : e.toString().trim();
    }
}
